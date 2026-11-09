package app.termora.tlog

import app.termora.*
import app.termora.actions.AnAction
import app.termora.actions.AnActionEvent
import app.termora.database.DatabaseManager
import com.formdev.flatlaf.extras.components.FlatPopupMenu
import com.formdev.flatlaf.util.SystemInfo
import org.apache.commons.io.FileUtils
import java.awt.Window
import java.io.File
import java.time.LocalDate
import javax.swing.*

internal enum class TerminalLogFormat(val label: String) {
    PlainText("termora.terminal-logger.plain-text"),
    StyledText("termora.terminal-logger.styled-text"),
    Asciinema("termora.terminal-logger.asciinema"),
}

class TerminalLoggerAction private constructor() :
    AnAction(I18n.getString("termora.terminal-logger"), Icons.listFiles) {

    companion object {
        fun getInstance(): TerminalLoggerAction {
            return ApplicationScope.forApplicationScope()
                .getOrCreate(TerminalLoggerAction::class) { TerminalLoggerAction() }
        }

    }

    private val properties get() = DatabaseManager.getInstance().properties

    /**
     * 是否开启了记录
     */
    var isRecording = properties.getString("terminal.logger.isRecording")?.toBoolean() ?: false
        private set(value) {
            field = value
            // firePropertyChange
            putValue("Recording", value)
            properties.putString("terminal.logger.isRecording", value.toString())
        }

    internal var format = TerminalLogFormat.entries.firstOrNull {
        it.name == properties.getString("terminal.logger.format")
    } ?: if (properties.getString("terminal.logger.isPlainText").toBoolean()) {
        TerminalLogFormat.PlainText
    } else {
        TerminalLogFormat.StyledText
    }
        private set(value) {
            field = value
            properties.putString("terminal.logger.format", value.name)
        }

    internal var compressZstd = properties.getString("terminal.logger.compressZstd").toBoolean()
        private set(value) {
            field = value
            properties.putString("terminal.logger.compressZstd", value.toString())
        }

    internal var recordKeyboard = properties.getString("terminal.logger.recordKeyboard").toBoolean()
        private set(value) {
            field = value
            properties.putString("terminal.logger.recordKeyboard", value.toString())
        }

    init {
        smallIcon = if (isRecording) Icons.dotListFiles else Icons.listFiles
    }

    override fun actionPerformed(evt: AnActionEvent) {
        val source = evt.source
        if (source !is JComponent) return

        val popupMenu = FlatPopupMenu()
        if (isRecording) {
            // stop
            popupMenu.add(I18n.getString("termora.terminal-logger.stop-recording")).addActionListener {
                isRecording = false
                smallIcon = Icons.listFiles
            }
        } else {
            // start
            popupMenu.add(I18n.getString("termora.terminal-logger.start-recording")).addActionListener {
                isRecording = true
                smallIcon = Icons.dotListFiles
            }
        }

        val settingMenu = JMenu(I18n.getString("termora.setting"))
        val formats = ButtonGroup()
        val keyboard = JCheckBoxMenuItem(I18n.getString("termora.terminal-logger.record-keyboard"), recordKeyboard)
        keyboard.isEnabled = !isRecording && format == TerminalLogFormat.Asciinema
        for (option in TerminalLogFormat.entries) {
            val item = JRadioButtonMenuItem(I18n.getString(option.label), option == format)
            item.isEnabled = !isRecording
            item.addActionListener {
                format = option
                keyboard.isEnabled = option == TerminalLogFormat.Asciinema
            }
            formats.add(item)
            settingMenu.add(item)
        }

        settingMenu.addSeparator()
        val compression = JCheckBoxMenuItem(I18n.getString("termora.terminal-logger.compress-zstd"), compressZstd)
        compression.isEnabled = !isRecording
        compression.addActionListener { compressZstd = compression.isSelected }
        keyboard.addActionListener { recordKeyboard = keyboard.isSelected }
        keyboard.toolTipText = I18n.getString("termora.terminal-logger.record-keyboard-warning")
        settingMenu.add(compression)
        settingMenu.add(keyboard)

        popupMenu.add(settingMenu)

        popupMenu.addSeparator()

        // 打开日志浏览
        popupMenu.add(I18n.getString("termora.terminal-logger.open-log-viewer")).addActionListener {
            openLogViewer(SwingUtilities.getWindowAncestor(source))
        }

        // 打开日志文件夹
        popupMenu.add(
            I18n.getString(
                "termora.terminal-logger.open-in-folder",
                if (SystemInfo.isMacOS) I18n.getString("termora.finder")
                else if (SystemInfo.isWindows) I18n.getString("termora.explorer")
                else I18n.getString("termora.folder")
            )
        ).addActionListener {
            val dir = getLogDir()
            Application.browse(dir.toURI())
        }

        val width = popupMenu.preferredSize.width
        popupMenu.show(source, -(width / 2) + source.width / 2, source.height)
    }

    private fun openLogViewer(owner: Window) {
        val fc = FileChooser()
        fc.allowsMultiSelection = true
        fc.title = I18n.getString("termora.terminal-logger.open-log-viewer")
        fc.fileSelectionMode = JFileChooser.FILES_ONLY

        val extensions = listOf("log", "cast", "zst", TerminalLogFiles.PendingSuffix.removePrefix("."))
        if (SystemInfo.isMacOS) {
            fc.osxAllowedFileTypes = extensions
        } else if (SystemInfo.isWindows) {
            fc.win32Filters.add(Pair(I18n.getString("termora.terminal-logger"), extensions))
        }

        fc.defaultDirectory = getLogDir().absolutePath
        fc.showOpenDialog(owner).thenAccept { files ->
            if (files.isNotEmpty()) {
                SwingUtilities.invokeLater {
                    val manager = ApplicationScope.forWindowScope(owner).get(TerminalTabbedManager::class)
                    for (file in files) {
                        if (TerminalLogFiles.isCast(file)) {
                            val tab = AsciicastTerminalTab(file)
                            manager.addTerminalTab(tab)
                            tab.start()
                        } else {
                            val tab = LogViewerTerminalTab(ApplicationScope.forWindowScope(owner), file)
                            manager.addTerminalTab(tab)
                            tab.start()
                        }
                    }
                }
            }
        }
    }

    @JvmOverloads
    fun getLogDir(create: Boolean = true): File {
        val dir = TerminalLogFiles.directory().resolve(LocalDate.now().toString())
        if (create) FileUtils.forceMkdir(dir)
        return dir
    }
}