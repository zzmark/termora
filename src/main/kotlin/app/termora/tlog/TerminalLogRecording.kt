package app.termora.tlog

import app.termora.I18n
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import org.apache.commons.lang3.time.DateFormatUtils
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Date

internal class TerminalLogRecording(
    val path: Path,
    private val castHeader: String?,
    private val started: Instant,
    private val startNanos: Long,
) {
    private val log = LoggerFactory.getLogger(TerminalLogRecording::class.java)
    private val events = Channel<Asciicast.Event>(Channel.UNLIMITED)
    @Volatile var job: Job? = null

    fun record(code: String, data: String) {
        events.trySend(Asciicast.Event((System.nanoTime() - startNanos) / 1e9, code, data))
    }

    fun finish() {
        events.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun write() {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
            fun date(instant: Instant) = DateFormatUtils.format(Date.from(instant), I18n.getString("termora.date-format"))

            if (castHeader != null) {
                writer.write(castHeader)
                writer.newLine()
            } else {
                writer.write("[BEGIN] ---- ${date(started)} ----\n\r")
            }
            writer.flush()

            var lastTime = 0L
            var highSurrogate = ""
            for (event in events) {
                if (castHeader == null) {
                    writer.write(event.data)
                } else {
                    val time = (event.seconds * 1_000_000).toLong().coerceAtLeast(lastTime)
                    val data = if (event.code == "o") {
                        val text = highSurrogate + event.data
                        highSurrogate = text.takeLast(1).takeIf { it.firstOrNull()?.isHighSurrogate() == true }.orEmpty()
                        text.dropLast(highSurrogate.length)
                    } else {
                        event.data
                    }
                    if (event.code != "o" || data.isNotEmpty()) {
                        writer.write(Asciicast.encode(Asciicast.Event((time - lastTime) / 1e6, event.code, data)))
                        writer.newLine()
                        lastTime = time
                    }
                }
                if (events.isEmpty) writer.flush()
            }
            if (castHeader == null) {
                writer.write("\n\r[END] ---- ${date(Instant.now())} ----")
            } else if (highSurrogate.isNotEmpty()) {
                writer.write(Asciicast.encode(Asciicast.Event(0.0, "o", "\uFFFD")))
                writer.newLine()
            }
        }
        val name = path.fileName.toString()
        if (name.endsWith(TerminalLogFiles.PendingSuffix)) {
            TerminalLogFiles.compress(path)
            log.info("Terminal log saved: {}", name.removeSuffix(TerminalLogFiles.PendingSuffix) + ".zst")
        } else {
            log.info("Terminal log saved: {}", name)
        }
    }
}
