package app.termora.tlog

import app.termora.Disposer
import app.termora.I18n
import app.termora.Icons
import app.termora.PropertyTerminalTab
import app.termora.TerminalFactory
import app.termora.actions.DataProviders
import app.termora.terminal.DataKey
import app.termora.terminal.Terminal
import app.termora.terminal.panel.TerminalPanel
import app.termora.terminal.panel.TerminalWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.util.Locale
import javax.swing.*
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.TimeSource

internal class AsciicastTerminalTab(private val file: File) : PropertyTerminalTab() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val component = JPanel(BorderLayout())
    private val playback = JButton()
    private val restart = JButton(label("restart"))
    private val speed = JComboBox(arrayOf("0.5×", "1×", "1.5×", "2×", "4×"))
    private val position = JLabel()
    private val timeline = JSlider(0, 1, 0).apply {
        isEnabled = false
        accessibleContext.accessibleName = label("playback-position")
    }
    private val showInput = JCheckBox(label("show-input"))
    private val input = JLabel().apply { putClientProperty("html.disable", true) }
    private var terminalPanel: TerminalPanel? = null
    private var job: Job? = null
    private var durationScan: Job? = null
    private var paused = false
    private var lastInput = ""
    private var duration: Double? = null
    private var currentSeconds = 0.0
    private var updatingTimeline = false

    init {
        speed.selectedIndex = 1
        val controls = JPanel(FlowLayout(FlowLayout.LEADING))
        for (control in listOf(playback, restart, speed, position, showInput, input)) controls.add(control)
        val footer = JPanel(BorderLayout())
        footer.add(timeline, BorderLayout.NORTH)
        footer.add(controls, BorderLayout.CENTER)
        component.add(footer, BorderLayout.SOUTH)
        timeline.addChangeListener {
            if (!updatingTimeline && timeline.isEnabled) {
                val target = requireNotNull(duration) * (timeline.value.toDouble() / timeline.maximum)
                if (timeline.valueIsAdjusting) showPosition(target)
                else startAt(target, paused || !playback.isEnabled)
            }
        }
        restart.addActionListener { start() }
        playback.addActionListener {
            paused = !paused
            updatePlaybackButton()
        }
        showInput.addActionListener { updateInput() }
    }

    fun start() = startAt(0.0, false)

    private fun startAt(seconds: Double, startPaused: Boolean) {
        job?.cancel()
        removeTerminal()
        val terminal = PlaybackTerminal()
        val panel = TerminalPanel(this, terminal, NoInput, fitTerminalToPanel = false).apply {
            enableFloatingToolbar = false
            resizeToast = false
        }
        terminalPanel = panel
        component.add(panel, BorderLayout.CENTER)
        component.revalidate()
        component.repaint()

        paused = startPaused
        lastInput = ""
        showInput.isEnabled = false
        updateInput()
        showPosition(seconds)
        position.toolTipText = null
        timeline.isEnabled = (duration ?: 0.0) > 0.0
        playback.isEnabled = true
        updatePlaybackButton()
        if (durationScan == null) {
            durationScan = scope.launch {
                val end = try {
                    readDuration()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch
                }
                duration = end
                timeline.maximum = ceil(end * 1000).toInt().coerceAtLeast(1)
                if (position.toolTipText == null) {
                    timeline.isEnabled = end > 0.0
                    showPosition(currentSeconds)
                }
            }
        }
        job = scope.launch {
            try {
                val end = play(terminal, seconds)
                if (!timeline.valueIsAdjusting) showPosition(end)
                playback.isEnabled = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                playback.isEnabled = false
                timeline.isEnabled = false
                position.text = label("playback-error")
                position.toolTipText = e.localizedMessage
            }
        }
    }

    private suspend fun readDuration(): Double = withContext(Dispatchers.IO) {
        TerminalLogFiles.open(file).use { reader ->
            val header = Asciicast.readHeader(reader)
            var end = 0.0
            for (event in Asciicast.events(reader, header)) {
                ensureActive()
                end = event.seconds
            }
            end
        }
    }

    private suspend fun play(terminal: Terminal, startSeconds: Double): Double {
        val model = terminal.getTerminalModel()
        var previous: TimeSource.Monotonic.ValueTimeMark? = null
        var elapsed = startSeconds
        var restoredEvents = 0
        var end = startSeconds
        flow {
            TerminalLogFiles.open(file).use { reader ->
                val header = Asciicast.readHeader(reader)
                emit(Asciicast.Event(0.0, "r", "${header.size.cols}x${header.size.rows}"))
                for (event in Asciicast.events(reader, header)) emit(event)
            }
        }.flowOn(Dispatchers.IO).buffer(64).collect { event ->
            end = maxOf(end, event.seconds)
            if (event.seconds > startSeconds) {
                var tick = previous ?: TimeSource.Monotonic.markNow()
                while (paused || elapsed < event.seconds) {
                    delay(25)
                    val now = TimeSource.Monotonic.markNow()
                    if (!paused) {
                        elapsed += (now - tick).inWholeNanoseconds / 1e9 * rates[speed.selectedIndex]
                        if (!timeline.valueIsAdjusting) showPosition(elapsed.coerceAtMost(event.seconds))
                    }
                    tick = now
                }
                previous = tick
            } else if (++restoredEvents % 64 == 0) {
                yield()
            }
            when (event.code) {
                "o" -> terminal.write(event.data)
                "r" -> Asciicast.resize(event.data).let { model.resize(it.rows, it.cols) }
                "i" -> {
                    lastInput = displayInput(event.data)
                    showInput.isEnabled = true
                    updateInput()
                }
            }
        }
        return end
    }

    private fun showPosition(seconds: Double) {
        currentSeconds = seconds
        val end = duration
        val value = "${formatTime(seconds)} / ${end?.let(::formatTime) ?: "--:--"}"
        if (position.text != value) position.text = value
        if (end != null && end > 0.0 && !timeline.valueIsAdjusting) {
            updatingTimeline = true
            try {
                timeline.value = ((seconds / end).coerceIn(0.0, 1.0) * timeline.maximum).roundToInt()
            } finally {
                updatingTimeline = false
            }
        }
    }

    private fun formatTime(seconds: Double): String {
        val elapsed = seconds.toLong()
        return String.format(Locale.ROOT, "%02d:%02d", elapsed / 60, elapsed % 60)
    }

    private fun updatePlaybackButton() {
        playback.text = label(if (paused) "resume" else "pause")
    }

    private fun updateInput() {
        input.text = if (showInput.isSelected) lastInput else ""
    }

    private fun removeTerminal() {
        terminalPanel?.let {
            component.remove(it)
            Disposer.dispose(it)
            it.terminal.close()
        }
        terminalPanel = null
    }

    override fun getTitle(): String = file.name
    override fun getIcon(): Icon = Icons.listFiles
    override fun getJComponent(): JComponent = component
    override fun canReconnect(): Boolean = false
    override fun canClone(): Boolean = false

    override fun onGrabFocus() {
        super.onGrabFocus()
        terminalPanel?.requestFocusInWindow()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getData(dataKey: DataKey<T>): T? = when (dataKey) {
        DataProviders.TerminalPanel -> terminalPanel as T?
        DataProviders.Terminal -> terminalPanel?.terminal as T?
        else -> null
    }

    override fun dispose() {
        scope.cancel()
        removeTerminal()
    }

    private class PlaybackTerminal : TerminalFactory.MyVisualTerminal() {
        private val model = object : TerminalFactory.MyTerminalModel(this@PlaybackTerminal) {
            override fun bell() {}
            override fun isValidSize(rows: Int, cols: Int): Boolean = rows > 0 && cols > 0
        }.apply { setData(DataKey.AllowClipboardWrite, false) }

        override fun getTerminalModel() = model
    }

    private object NoInput : TerminalWriter {
        override fun onMounted(c: JComponent) {}
        override fun write(request: TerminalWriter.WriteRequest) {}
        override fun resize(rows: Int, cols: Int) {}
    }

    companion object {
        private val rates = doubleArrayOf(0.5, 1.0, 1.5, 2.0, 4.0)

        private fun label(key: String) = I18n.getString("termora.terminal-logger.$key")

        private fun displayInput(text: String): String = when (text) {
            "\u001b[A", "\u001bOA" -> "↑"
            "\u001b[B", "\u001bOB" -> "↓"
            "\u001b[C", "\u001bOC" -> "→"
            "\u001b[D", "\u001bOD" -> "←"
            else -> buildString {
                for (c in text.take(64)) {
                    append(when (c) {
                        '\r', '\n' -> " Enter "
                        '\t' -> " Tab "
                        '\b', '\u007f' -> " Backspace "
                        '\u001b' -> " Esc "
                        in '\u0001'..'\u001a' -> " Ctrl+${'A' + (c - '\u0001')} "
                        else -> if (c.isISOControl()) "" else c.toString()
                    })
                }
            }
        }.trim()
    }
}
