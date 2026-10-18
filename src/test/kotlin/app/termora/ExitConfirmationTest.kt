package app.termora

import app.termora.database.DatabaseManager
import app.termora.terminal.VisualTerminal
import app.termora.tlog.LogViewerTerminalTab
import app.termora.transfer.TransportSelectionPanel
import app.termora.transfer.TransportPanel
import app.termora.transfer.TransportTerminalTab
import app.termora.transfer.TransportViewer
import com.formdev.flatlaf.FlatLightLaf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowListener
import java.nio.file.Files
import javax.swing.*
import kotlin.test.*

/** Runs with an available desktop; all application configuration is isolated in a temporary directory. */
@EnabledIf("desktopAvailable")
class ExitConfirmationTest {
    companion object {
        @JvmStatic
        fun desktopAvailable(): Boolean = !GraphicsEnvironment.isHeadless()

        @JvmStatic
        @BeforeAll
        fun initializeIsolatedApplication() {
            System.setProperty("termora.base-data-dir", Files.createTempDirectory("termora-exit-test-").toString())
            System.setProperty("tinylog.writer_file", "console")
            SwingUtilities.invokeAndWait {
                FlatLightLaf.setup()
                UIManager.put("Tree.rowHeight", 24)
                DatabaseManager.getInstance().appearance.backgroundRunning = false
                DatabaseManager.getInstance().sftp.defaultDirectory = System.getProperty("termora.base-data-dir")
            }
        }
    }

    private class HostTab(scope: WindowScope, protocol: String = "SSH") : HostTerminalTab(
        scope, Host(name = "unconnected-test-host", protocol = protocol), VisualTerminal(),
    ) {
        private val panel = JPanel()
        override fun getJComponent() = panel
    }

    private fun ordinaryTab() = object : PropertyTerminalTab() {
        private val panel = JPanel()
        override fun getJComponent() = panel
        override fun getTitle() = "ordinary page"
        override fun getIcon() = Icons.empty
        override fun <T : Any> getData(dataKey: app.termora.terminal.DataKey<T>): T? = null
    }

    private data class TestWindow(val frame: TermoraFrame, val closing: WindowListener) {
        val scope get() = ApplicationScope.forWindowScope(frame)
        val tabs get() = scope.get(TerminalTabbedManager::class)

        fun closeAndObservePrompt(answer: Int = JOptionPane.NO_OPTION): Boolean {
            var prompted = false
            val timer = Timer(30, null)
            timer.addActionListener {
                for (dialog in Window.getWindows().filterIsInstance<JDialog>().filter { it.isShowing }) {
                    findOptionPane(dialog)?.let { pane ->
                        prompted = true
                        pane.value = answer
                    }
                }
            }
            timer.start()
            try {
                closing.windowClosing(WindowEvent(frame, WindowEvent.WINDOW_CLOSING))
            } finally {
                timer.stop()
            }
            return prompted
        }

        companion object {
            private fun findOptionPane(component: Component): JOptionPane? {
                if (component is JOptionPane) return component
                return (component as? Container)?.components?.firstNotNullOfOrNull(::findOptionPane)
            }
        }
    }

    private fun withWindow(block: (TestWindow) -> Unit) {
        val action = Runnable {
            val manager = TermoraFrameManager()
            val frame = manager.createWindow()
            val listener = frame.windowListeners.first { it.javaClass.name.startsWith("app.termora.TermoraFrameManager$") }
            // Exercise the real windowClosing callback without terminating the JUnit worker in windowClosed.
            frame.removeWindowListener(listener)
            frame.addNotify()
            val window = TestWindow(frame, listener)
            try {
                block(window)
            } finally {
                val scope = window.scope
                frame.dispose()
                Disposer.disposeChildren(scope, null)
                Disposer.dispose(scope)
            }
        }
        if (SwingUtilities.isEventDispatchThread()) action.run() else SwingUtilities.invokeAndWait(action)
    }

    @Test
    @DisplayName("没有连接标签页或仅有普通页面时不需要退出确认")
    fun ignoresEmptyAndOrdinaryTabs() {
        assertFalse(TermoraFrameManager.hasOpenConnectionTabs(emptyList()))
        assertFalse(TermoraFrameManager.hasOpenConnectionTabs(listOf(ordinaryTab())))
    }

    @Test
    @DisplayName("主机标签页不检查在线状态且日志回放不计入连接")
    fun countsHostTabsRegardlessOfConnectionStateAndExcludesLogReplay() = withWindow { window ->
        for (protocol in listOf("SSH", "Local", "WSL", "Telnet", "RDP", "VNC", "Serial")) {
            val tab = HostTab(window.scope, protocol)
            try {
                assertTrue(TermoraFrameManager.hasOpenConnectionTabs(listOf(tab)), protocol)
            } finally {
                tab.dispose()
            }
        }
        val replay = LogViewerTerminalTab(window.scope, Files.createTempFile("exit-log-", ".tlog").toFile())
        try {
            assertFalse(TermoraFrameManager.hasOpenConnectionTabs(listOf(replay)))
        } finally {
            Disposer.dispose(replay)
        }
    }

    @Test
    @DisplayName("实际关闭流程在仅有欢迎页时跳过弹窗并关闭窗口")
    fun closesWindowWithoutPromptWhenOnlyWelcomePageRemains() = withWindow { window ->
        assertFalse(window.closeAndObservePrompt())
        assertFalse(window.frame.isDisplayable)
    }

    @Test
    @DisplayName("实际关闭流程在有连接标签页时询问并支持取消和确认")
    fun promptsForHostTabAndHonorsCancelAndConfirm() = withWindow { window ->
        window.tabs.addTerminalTab(HostTab(window.scope))
        assertTrue(window.closeAndObservePrompt(JOptionPane.NO_OPTION))
        assertTrue(window.frame.isDisplayable)
        assertTrue(window.closeAndObservePrompt(JOptionPane.YES_OPTION))
        assertFalse(window.frame.isDisplayable)
    }

    @Test
    @DisplayName("移出主窗口的连接标签页仍触发退出确认")
    fun promptsForDetachedHostTab() = withWindow { window ->
        val tab = HostTab(window.scope)
        val dialog = TerminalTabDialog(window.frame, java.awt.Dimension(640, 400), tab)
        Disposer.register(dialog, tab)
        dialog.addNotify()
        try {
            assertTrue(window.closeAndObservePrompt())
            assertTrue(window.frame.isDisplayable)
        } finally {
            dialog.dispose()
        }
    }

    @Test
    @DisplayName("多主窗口关闭时保留原有不询问流程")
    fun preservesMultipleWindowCloseBehavior() = withWindow { first ->
        first.tabs.addTerminalTab(HostTab(first.scope))
        withWindow { second ->
            assertFalse(first.closeAndObservePrompt())
            assertFalse(first.frame.isDisplayable)
            assertTrue(second.frame.isDisplayable)
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows后台运行开启时隐藏窗口而不是退出")
    fun preservesWindowsBackgroundRunningBehavior() = withWindow { window ->
        DatabaseManager.getInstance().appearance.backgroundRunning = true
        try {
            window.frame.isVisible = true
            assertTrue(window.frame.isVisible)
            assertFalse(window.closeAndObservePrompt())
            assertTrue(window.frame.isDisplayable)
            assertFalse(window.frame.isVisible)
        } finally {
            DatabaseManager.getInstance().appearance.backgroundRunning = false
        }
    }

    @Test
    @DisplayName("文件传输左右两侧的远端标签计入确认且返回空选择页后不再计入")
    fun checksBothTransferSidesAndIgnoresResetSelectionPage() = withWindow { window ->
        val tab = TransportTerminalTab()
        try {
            assertFalse(TermoraFrameManager.hasOpenConnectionTabs(listOf(tab)))
            val viewer = tab.getJComponent() as TransportViewer
            val localPanel = viewer.getLeftTabbed().components.filterIsInstance<TransportPanel>().first()
            val panelHost = TransportPanel::class.java.getDeclaredField("host").apply { isAccessible = true }
            val localHost = localPanel.host
            panelHost.set(localPanel, Host(name = "test-remote-panel", protocol = "SSH"))
            assertTrue(TermoraFrameManager.hasOpenConnectionTabs(listOf(tab)))
            panelHost.set(localPanel, localHost)
            assertFalse(TermoraFrameManager.hasOpenConnectionTabs(listOf(tab)))
            val selection = viewer.getRightTabbed().components.filterIsInstance<TransportSelectionPanel>().first()
            val hostField = TransportSelectionPanel::class.java.getDeclaredField("host").apply { isAccessible = true }
            val stateField = TransportSelectionPanel::class.java.getDeclaredField("state").apply { isAccessible = true }
            hostField.set(selection, Host(name = "test-remote", protocol = "SSH"))
            for (state in listOf(TransportSelectionPanel.State.Connecting, TransportSelectionPanel.State.ConnectFailed)) {
                stateField.set(selection, state)
                assertTrue(TermoraFrameManager.hasOpenConnectionTabs(listOf(tab)))
            }
            stateField.set(selection, TransportSelectionPanel.State.Initialized)
            assertFalse(TermoraFrameManager.hasOpenConnectionTabs(listOf(tab)))
            viewer.getRightTabbed().remove(selection)
            viewer.getLeftTabbed().addTab("remote", selection)
            stateField.set(selection, TransportSelectionPanel.State.ConnectFailed)
            assertTrue(TermoraFrameManager.hasOpenConnectionTabs(listOf(tab)))
        } finally {
            Disposer.dispose(tab)
        }
    }
}
