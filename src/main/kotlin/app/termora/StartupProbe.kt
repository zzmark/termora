package app.termora

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-overhead startup timeline used by the packaged-application benchmark.
 *
 * The probe is disabled unless a trace directory is explicitly configured. Events stay in memory during startup and
 * are written atomically only after the application becomes interactive, or from a shutdown hook for partial traces.
 */
internal object StartupProbe {
    const val TRACE_DIR_PROPERTY = "termora.startup.trace-dir"
    const val TRACE_DIR_ENV = "TERMORA_STARTUP_TRACE_DIR"
    const val RUN_ID_PROPERTY = "termora.startup.run-id"
    const val RUN_ID_ENV = "TERMORA_STARTUP_RUN_ID"
    const val SKIP_AOT_PROPERTY = "termora.startup.skip-aot"
    const val SKIP_AOT_ENV = "TERMORA_STARTUP_SKIP_AOT"

    const val MAIN_ENTER = "main-enter"
    const val MAIN_RETURN = "main-return"
    const val INITIALIZER_ENTER = "initializer-enter"
    const val NATIVE_LIBRARIES_READY = "native-libraries-ready"
    const val LOGGING_READY = "logging-ready"
    const val AOT_PREPARED = "aot-prepared"
    const val AOT_SKIPPED = "aot-skipped"
    const val SINGLETON_READY = "singleton-ready"
    const val APPLICATION_RUNNER_ENTER = "application-runner-enter"
    const val PLUGIN_LOAD_STARTED = "plugin-load-started"
    const val PLUGIN_INITIALIZE_STARTED = "plugin-initialize-started"
    const val PLUGIN_INITIALIZE_COMPLETE = "plugin-initialize-complete"
    const val PLUGIN_LOAD_COMPLETE = "plugin-load-complete"
    const val TEMPORARY_LAF_READY = "temporary-laf-ready"
    const val DATABASE_LOAD_STARTED = "database-load-started"
    const val DATABASE_INITIALIZE_STARTED = "database-initialize-started"
    const val DATABASE_INITIALIZE_COMPLETE = "database-initialize-complete"
    const val DATABASE_READY = "database-ready"
    const val SETTINGS_READY = "settings-ready"
    const val FINAL_LAF_READY = "final-laf-ready"
    const val PLUGIN_BARRIER_COMPLETE = "plugin-barrier-complete"
    const val EXTENSIONS_READY = "extensions-ready"
    const val FRAME_TASK_QUEUED = "frame-task-queued"
    const val FRAME_EDT_ENTER = "frame-edt-enter"
    const val FRAME_CONSTRUCTION_STARTED = "frame-construction-started"
    const val FRAME_CONSTRUCTED = "frame-constructed"
    const val HOST_MODEL_READY = "host-model-ready"
    const val KEYMAP_READY = "keymap-ready"
    const val FRAME_VISIBLE = "frame-visible"
    const val FIRST_PAINT = "first-paint"
    const val EDT_FENCE_1 = "edt-fence-1"
    const val EDT_FENCE_2 = "edt-fence-2"
    const val EDT_FENCE_3 = "edt-fence-3"
    const val INTERACTIVE = "interactive"

    private val session = createSession()?.also { trace ->
        runCatching {
            Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform()
                    .name("startup-trace-shutdown")
                    .unstarted { trace.writeSnapshotSafely() }
            )
        }
    }

    val isEnabled: Boolean
        get() = session != null

    val shouldSkipAot: Boolean
        get() = configuredValue(SKIP_AOT_PROPERTY, SKIP_AOT_ENV).equals("true", ignoreCase = true)

    fun mark(name: String): Boolean {
        val trace = session ?: return false
        return runCatching { trace.mark(name) }.getOrDefault(false)
    }

    fun hasMarked(name: String): Boolean {
        return session?.hasMarked(name) == true
    }

    fun flushAsync() {
        session?.writeSnapshotAsync()
    }

    private fun createSession(): StartupTraceSession? {
        val traceDir = configuredValue(TRACE_DIR_PROPERTY, TRACE_DIR_ENV) ?: return null
        val runId = configuredValue(RUN_ID_PROPERTY, RUN_ID_ENV)
            ?: "manual-${System.currentTimeMillis()}"

        return runCatching {
            StartupTraceSession(
                runId = runId,
                processId = ProcessHandle.current().pid(),
                traceDir = Path.of(traceDir),
            )
        }.onFailure {
            System.err.println("Unable to initialize startup trace: ${it.message}")
        }.getOrNull()
    }

    private fun configuredValue(property: String, environment: String): String? {
        return System.getProperty(property)?.takeIf { it.isNotBlank() }
            ?: System.getenv(environment)?.takeIf { it.isNotBlank() }
    }
}

internal data class StartupTraceEvent(
    val name: String,
    val elapsedNanos: Long,
    val thread: String,
)

internal class StartupTraceSession(
    val runId: String,
    val processId: Long,
    private val traceDir: Path,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val currentThreadName: () -> String = {
        Thread.currentThread().let { thread ->
            thread.name.ifBlank { if (thread.isVirtual) "virtual-thread" else "unnamed-thread" }
        }
    },
) {
    val startedEpochMillis = epochMillis()
    private val startedNanos = nanoTime()
    private val eventNames = ConcurrentHashMap.newKeySet<String>()
    private val events = ConcurrentLinkedQueue<StartupTraceEvent>()
    private val asyncWriteScheduled = AtomicBoolean(false)
    private val writeLock = Any()

    fun mark(name: String): Boolean {
        if (name.isBlank() || !eventNames.add(name)) return false

        events.add(
            StartupTraceEvent(
                name = name,
                elapsedNanos = (nanoTime() - startedNanos).coerceAtLeast(0),
                thread = currentThreadName(),
            )
        )
        return true
    }

    internal fun hasMarked(name: String): Boolean {
        return eventNames.contains(name)
    }

    internal fun snapshot(): List<StartupTraceEvent> {
        return events.toList().sortedBy { it.elapsedNanos }
    }

    internal fun renderJson(): String {
        val snapshot = snapshot()
        return buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": 1,")
            append("  \"runId\": ").appendJsonString(runId).appendLine(",")
            appendLine("  \"pid\": $processId,")
            appendLine("  \"startedEpochMillis\": $startedEpochMillis,")
            appendLine("  \"completed\": ${snapshot.any { it.name == StartupProbe.INTERACTIVE }},")
            appendLine("  \"events\": [")
            snapshot.forEachIndexed { index, event ->
                append("    {\"name\": ").appendJsonString(event.name)
                append(", \"elapsedNanos\": ${event.elapsedNanos}, \"thread\": ")
                    .appendJsonString(event.thread)
                append('}')
                if (index != snapshot.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }
    }

    internal fun writeSnapshot() {
        synchronized(writeLock) {
            Files.createDirectories(traceDir)
            val output = traceDir.resolve("${safeFilename(runId)}-$processId.json")
            val temporary = Files.createTempFile(traceDir, ".${output.fileName}-", ".tmp")
            try {
                Files.writeString(
                    temporary,
                    renderJson(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                try {
                    Files.move(
                        temporary,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    internal fun writeSnapshotAsync() {
        if (!asyncWriteScheduled.compareAndSet(false, true)) return
        Thread.ofVirtual()
            .name("startup-trace-writer")
            .start { writeSnapshotSafely() }
    }

    internal fun writeSnapshotSafely() {
        runCatching { writeSnapshot() }.onFailure {
            System.err.println("Unable to write startup trace: ${it.message}")
        }
    }

    private fun safeFilename(value: String): String {
        val safe = value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return safe.ifBlank { "startup" }
    }

    private fun StringBuilder.appendJsonString(value: String): StringBuilder {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(HEX[(char.code ushr 12) and 0xF])
                        append(HEX[(char.code ushr 8) and 0xF])
                        append(HEX[(char.code ushr 4) and 0xF])
                        append(HEX[char.code and 0xF])
                    } else {
                        append(char)
                    }
                }
            }
        }
        return append('"')
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
