package app.termora.plugin.internal.rdp

import app.termora.*
import app.termora.actions.DataProvider
import app.termora.protocol.GenericProtocolProvider
import com.formdev.flatlaf.util.SystemInfo
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.text.StringEscapeUtils
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.seconds

internal class RDPProtocolProvider private constructor() : GenericProtocolProvider {
    companion object {
        val instance by lazy { RDPProtocolProvider() }
        const val PROTOCOL = "RDP"
    }

    override fun getProtocol(): String {
        return PROTOCOL
    }

    override fun createTerminalTab(dataProvider: DataProvider, windowScope: WindowScope, host: Host): TerminalTab {
        return RDPTerminalTab(windowScope, host)
    }

    override fun getIcon(width: Int, height: Int): DynamicIcon {
        return Icons.microsoftWindows
    }

    override fun canCreateTerminalTab(dataProvider: DataProvider, windowScope: WindowScope, host: Host): Boolean {
        if (SystemInfo.isWindows) {
            val settings = RDPSettings.fromExtras(host.options.extras)
            if (settings.connectionMethod == RDPConnectionMethod.MSTSCAX) return true
            launchMstsc(windowScope, host, settings)
        } else {
            openRDP(windowScope, host)
        }
        return false
    }

    private fun launchMstsc(windowScope: WindowScope, host: Host, settings: RDPSettings) {
        swingCoroutineScope.launch(Dispatchers.IO) {
            val failureShown = AtomicBoolean()
            fun reportFailure(e: Exception) {
                if (!failureShown.compareAndSet(false, true)) return
                SwingUtilities.invokeLater {
                    OptionPane.showMessageDialog(
                        windowScope.window,
                        StringEscapeUtils.escapeHtml4(e.localizedMessage ?: e.javaClass.simpleName),
                        title = I18n.getString("termora.rdp.error.startup"),
                        messageType = JOptionPane.ERROR_MESSAGE
                    )
                }
            }
            try {
                RDPConnection.open(host, windowScope.window, { !isActive }, ::reportFailure).use { connection ->
                    openMstsc(host, settings, connection)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(e)
            }
        }
    }

    private suspend fun openMstsc(host: Host, settings: RDPSettings, connection: RDPConnection) {
        require(listOf(host.host, host.username).none { '\r' in it || '\n' in it || '\u0000' in it }) {
            I18n.getString("termora.new-host.rdp.invalid-address")
        }
        val address = connection.address.let { if (':' in it) "[${it.removeSurrounding("[", "]")}]" else it }
        val configuration = buildString {
            appendLine("full address:s:$address:${connection.port}")
            appendLine("username:s:${host.username}")
            if (connection.isForwarded) {
                appendLine("authentication level:i:2")
                appendLine("gatewayprofileusagemethod:i:1")
                appendLine("gatewayusagemethod:i:0")
            }
            settings.appendMstscProperties(this)
            if (host.authentication.type == AuthenticationType.Password && host.authentication.password.isNotEmpty()) {
                val password = host.authentication.password.toByteArray(Charsets.UTF_16LE)
                val encrypted = try {
                    runCatching { Crypt32Util.cryptProtectData(password) }.getOrNull()
                } finally {
                    password.fill(0)
                }
                if (encrypted != null) {
                    appendLine("password 51:b:${HexFormat.of().formatHex(encrypted)}")
                }
            }
        }
        val file = FileUtils.getFile(Application.getTemporaryDir(), randomUUID() + ".rdp")
        try {
            file.writeText("\uFEFF" + configuration.replace("\n", "\r\n"), Charsets.UTF_16LE)
            val mstsc = FileUtils.getFile(System.getenv("WINDIR") ?: "C:\\Windows", "System32", "mstsc.exe")
            val process = ProcessBuilder(mstsc.absolutePath, file.absolutePath).start()
            if (connection.isForwarded) {
                process.onExit().await()
                FileUtils.deleteQuietly(file)
            }
        } catch (e: Exception) {
            FileUtils.deleteQuietly(file)
            throw e
        }
    }

    private fun openRDP(windowScope: WindowScope, host: Host) {
        if (SystemInfo.isLinux) {
            OptionPane.showMessageDialog(
                windowScope.window,
                "Linux cannot connect to Windows Remote Server, Supported only for macOS and Windows",
                messageType = JOptionPane.WARNING_MESSAGE
            )
            return
        }

        if (SystemInfo.isMacOS) {
            if (!FileUtils.getFile("/Applications/Windows App.app").exists()) {
                val option = OptionPane.showConfirmDialog(
                    windowScope.window,
                    "If you want to connect to a Windows Remote Server, You have to install the Windows App",
                    optionType = JOptionPane.OK_CANCEL_OPTION
                )
                if (option == JOptionPane.OK_OPTION) {
                    Application.browse(URI.create("https://apps.apple.com/app/windows-app/id1295203466"))
                }
                return
            }
        }

        val sb = StringBuilder()
        sb.append("full address:s:").append(host.host).append(':').append(host.port).appendLine()
        sb.append("username:s:").append(host.username).appendLine()
        val desktop = host.options.extras[RDPSettings.DESKTOP]
        if (desktop.isNullOrBlank().not()) {
            val resolution = desktop.split("x")
            if (resolution.size == 2) {
                sb.append("use multimon:i:0").appendLine()
                sb.append("screen mode id:i:1").appendLine()
                sb.append("desktopwidth:i:").append(resolution.first()).appendLine()
                sb.append("desktopheight:i:").append(resolution.last()).appendLine()

                // macOS 上需要关闭动态分辨率，否则分辨率可能不生效
                sb.append("dynamic resolution:i:0").appendLine()
            }
        }

        if (host.authentication.type == AuthenticationType.Password) {
            val password = host.authentication.password
            val systemClipboard = windowScope.window.toolkit.systemClipboard
            systemClipboard.setContents(StringSelection(password), null)
            swingCoroutineScope.launch(Dispatchers.IO) {
                delay(30.seconds)
                if (systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    if (systemClipboard.getData(DataFlavor.stringFlavor) == password) {
                        systemClipboard.setContents(StringSelection(StringUtils.EMPTY), null)
                    }
                }
            }
        }


        val file = FileUtils.getFile(Application.getTemporaryDir(), randomUUID() + ".rdp")
        file.writeText(sb.toString())
        ProcessBuilder("open", file.absolutePath).start()

    }
}
