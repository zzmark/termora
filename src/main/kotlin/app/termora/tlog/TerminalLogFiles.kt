package app.termora.tlog

import app.termora.Application
import io.airlift.compress.v3.zstd.ZstdInputStream
import io.airlift.compress.v3.zstd.ZstdOutputStream
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

internal object TerminalLogFiles {
    const val PendingSuffix = ".pending-zstd"
    private val log = LoggerFactory.getLogger(TerminalLogFiles::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap<Path, TerminalLogRecording>()

    fun directory(): File = Application.getBaseDataDir().resolve("terminal/logs")

    fun open(file: File): BufferedReader {
        val input = file.inputStream().buffered()
        return if (file.name.endsWith(".zst", ignoreCase = true)) ZstdInputStream(input).bufferedReader()
        else input.bufferedReader()
    }

    fun isCast(file: File): Boolean = file.name.lowercase(Locale.ROOT).removeSuffix(PendingSuffix)
        .removeSuffix(".zst").endsWith(".cast")

    fun write(recording: TerminalLogRecording, onFailure: () -> Unit) {
        active[recording.path] = recording
        recording.job = scope.launch {
            try {
                recording.write()
            } catch (e: Exception) {
                log.error("Could not write terminal log {}", recording.path, e)
                SwingUtilities.invokeLater(onFailure)
            } finally {
                recording.finish()
                active.remove(recording.path)
            }
        }
    }

    fun startRecovery() {
        val directory = directory().toPath()
        Runtime.getRuntime().addShutdownHook(Thread({
            val recordings = active.values.toList()
            recordings.forEach { it.finish() }
            runBlocking { withTimeoutOrNull(5000) { recordings.mapNotNull { it.job }.joinAll() } }
        }, "terminal-log-shutdown"))
        scope.launch {
            runCatching { recover(directory) }.onFailure { log.warn("Could not recover terminal logs", it) }
        }
    }

    internal fun recover(directory: Path) {
        if (!Files.isDirectory(directory)) return
        Files.walk(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(PendingSuffix) }
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { path -> !active.containsKey(path) }
                .forEach { path -> runCatching { compress(path) }.onFailure { log.warn("Could not compress {}", path, it) } }
        }
    }

    internal fun compress(raw: Path) {
        val name = raw.fileName.toString()
        require(name.endsWith(PendingSuffix))
        val target = raw.resolveSibling(name.removeSuffix(PendingSuffix) + ".zst")
        val partial = raw.resolveSibling(name + ".part")
        Files.deleteIfExists(partial)
        try {
            Files.newInputStream(raw, LinkOption.NOFOLLOW_LINKS).buffered().use { input ->
                Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).buffered().use {
                    ZstdOutputStream(it).use { output -> input.copyTo(output) }
                }
            }
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.delete(raw)
        } finally {
            Files.deleteIfExists(partial)
        }
    }
}
