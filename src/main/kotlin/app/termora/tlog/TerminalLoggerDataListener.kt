package app.termora.tlog

import app.termora.Host
import app.termora.HostTerminalTab
import app.termora.I18n
import app.termora.TerminalPanelFactory
import app.termora.terminal.*
import org.slf4j.LoggerFactory
import java.beans.PropertyChangeListener
import java.nio.charset.Charset
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class TerminalLoggerDataListener(private val terminal: Terminal) : DataListener {
    companion object {
        val IgnoreTerminalLogger = DataKey(Boolean::class)
        internal val Recorder = DataKey(TerminalLoggerDataListener::class)
        private val PtyOutput = DataKey(String::class)
        private val KeyboardInput = DataKey(String::class)

        internal fun forTerminal(terminal: Terminal): TerminalLoggerDataListener? {
            val model = terminal.getTerminalModel()
            return if (model.hasData(Recorder)) model.getData(Recorder) else null
        }
        private val log = LoggerFactory.getLogger(TerminalLoggerDataListener::class.java)
        private val filenameTime = DateTimeFormatter.ofPattern("HH_mm_ss_SSS")
    }

    private val model get() = terminal.getTerminalModel()
    private val action get() = TerminalLoggerAction.getInstance()
    private var recording: TerminalLogRecording? = null
    private var closed = false
    private var sessionEnded = false
    private val recordingListener = PropertyChangeListener {
        if (it.propertyName == "Recording" && it.newValue == false) finish()
    }

    init {
        terminal.addTerminalListener(object : TerminalListener {
            override fun onClose(terminal: Terminal) {
                synchronized(this@TerminalLoggerDataListener) {
                    closed = true
                    model.removeDataListener(this@TerminalLoggerDataListener)
                    finish()
                }
            }
        })
    }

    internal fun onOutput(text: String) = onChanged(PtyOutput, text)

    internal fun onKeyboardInput(bytes: ByteArray, charset: Charset) {
        val action = action
        if (action.isRecording && action.format == TerminalLogFormat.Asciinema && action.recordKeyboard) {
            onChanged(KeyboardInput, String(bytes, charset))
        }
    }

    @Synchronized
    internal fun startSession() {
        sessionEnded = false
    }

    @Synchronized
    internal fun endSession() {
        sessionEnded = true
        finish()
    }

    @Synchronized
    override fun onChanged(key: DataKey<*>, data: Any) {
        if (closed || sessionEnded) return
        if (key != TextProcessor.Written && key != VisualTerminal.Written &&
            key != PtyOutput && key != KeyboardInput && key != TerminalModel.Resize
        ) return
        if (model.getData(IgnoreTerminalLogger, false) || !model.hasData(HostTerminalTab.Host)) return

        val action = action
        if (!action.isRecording) return
        val format = action.format
        val event = when (key) {
            TextProcessor.Written -> if (format == TerminalLogFormat.PlainText) "o" else return
            VisualTerminal.Written -> if (format == TerminalLogFormat.StyledText) "o" else return
            PtyOutput -> if (format == TerminalLogFormat.Asciinema) "o" else return
            KeyboardInput -> if (format == TerminalLogFormat.Asciinema && action.recordKeyboard) "i" else return
            TerminalModel.Resize -> if (format == TerminalLogFormat.Asciinema && recording != null) "r" else return
            else -> return
        }

        try {
            val text = if (data is TerminalResize) {
                if (data.oldSize == data.newSize) return
                "${data.newSize.cols}x${data.newSize.rows}"
            } else data as String
            val current = recording ?: createRecording(model.getData(HostTerminalTab.Host), action)
            current.record(event, text)
        } catch (e: Exception) {
            log.error("Could not start terminal log recording", e)
        }
    }

    private fun createRecording(host: Host, action: TerminalLoggerAction): TerminalLogRecording {
        val started = Instant.now()
        val startNanos = System.nanoTime()
        val cast = action.format == TerminalLogFormat.Asciinema
        val extension = if (cast) "cast" else "log"
        val suffix = if (action.compressZstd) TerminalLogFiles.PendingSuffix else ""
        val title = host.name.map { c ->
            if (c.isISOControl() || c in "<>:\"/\\|?*") '_' else c
        }.joinToString("").take(80).trim(' ', '.').ifBlank { "terminal" }
        val name = "${LocalTime.now().format(filenameTime)}.$title.${UUID.randomUUID()}.$extension$suffix"
        val size = TerminalSize(model.getRows(), model.getCols())
        val header = if (cast) Asciicast.header(size, started, host.name) else null
        return TerminalLogRecording(action.getLogDir(create = false).toPath().resolve(name), header, started, startNanos).also {
            recording = it
            action.addPropertyChangeListener(recordingListener)
            TerminalLogFiles.write(it) {
                TerminalPanelFactory.getInstance().getTerminalPanels().firstOrNull { panel -> panel.terminal === terminal }
                    ?.toast(I18n.getString("termora.terminal-logger.write-error"), 5.seconds)
            }
        }
    }

    @Synchronized
    private fun finish() {
        val current = recording ?: return
        recording = null
        action.removePropertyChangeListener(recordingListener)
        current.finish()
    }
}
