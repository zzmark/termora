package app.termora

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.ArrayDeque
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupTraceSessionTest {

    @Test
    fun `AOT skip is opt in through the benchmark property`() {
        val previous = System.getProperty(StartupProbe.SKIP_AOT_PROPERTY)
        try {
            System.setProperty(StartupProbe.SKIP_AOT_PROPERTY, "true")
            assertTrue(StartupProbe.shouldSkipAot)

            System.setProperty(StartupProbe.SKIP_AOT_PROPERTY, "false")
            assertFalse(StartupProbe.shouldSkipAot)
        } finally {
            if (previous == null) {
                System.clearProperty(StartupProbe.SKIP_AOT_PROPERTY)
            } else {
                System.setProperty(StartupProbe.SKIP_AOT_PROPERTY, previous)
            }
        }
    }

    @Test
    fun `readiness gate waits for paint and content then posts each fence once`() {
        val orders = listOf(
            listOf("paint", "host", "keymap"),
            listOf("host", "keymap", "paint"),
            listOf("keymap", "paint", "host"),
        )

        for (order in orders) {
            var paintReady = false
            var hostReady = false
            var keymapReady = false
            var interactiveCount = 0
            val fences = mutableListOf<Int>()
            val posted = ArrayDeque<() -> Unit>()
            val gate = StartupReadinessGate(
                isReady = { paintReady && hostReady && keymapReady },
                canContinue = { true },
                post = { posted.addLast(it) },
                onFence = { fences.add(it) },
                onInteractive = { interactiveCount++ },
            )

            for (signal in order) {
                when (signal) {
                    "paint" -> paintReady = true
                    "host" -> hostReady = true
                    "keymap" -> keymapReady = true
                }
                gate.tryStart()
            }
            assertFalse(gate.tryStart())

            while (posted.isNotEmpty()) {
                posted.removeFirst().invoke()
            }

            assertEquals(listOf(1, 2, 3), fences)
            assertEquals(1, interactiveCount)
        }
    }

    @Test
    fun `marks each phase once with monotonic elapsed time`() {
        var nanos = 100L
        val traceDir = createTempDirectory("termora-startup-trace")
        val session = StartupTraceSession(
            runId = "test",
            processId = 7,
            traceDir = traceDir,
            epochMillis = { 1_000L },
            nanoTime = { nanos.also { nanos += 50L } },
            currentThreadName = { "test-thread" },
        )

        try {
            assertTrue(session.mark("alpha"))
            assertFalse(session.mark("alpha"))
            assertTrue(session.mark("beta"))
            assertTrue(session.hasMarked("alpha"))
            assertFalse(session.hasMarked("missing"))

            assertEquals(
                listOf(
                    StartupTraceEvent("alpha", 50L, "test-thread"),
                    StartupTraceEvent("beta", 100L, "test-thread"),
                ),
                session.snapshot(),
            )
        } finally {
            traceDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `records concurrent startup phases without loss`() {
        val traceDir = createTempDirectory("termora-startup-trace")
        val session = StartupTraceSession(
            runId = "concurrent",
            processId = 8,
            traceDir = traceDir,
        )

        try {
            val threads = List(8) { threadIndex ->
                Thread.ofPlatform().start {
                    repeat(100) { eventIndex ->
                        session.mark("phase-$threadIndex-$eventIndex")
                    }
                }
            }
            threads.forEach(Thread::join)

            assertEquals(800, session.snapshot().size)
            assertEquals(800, session.snapshot().map { it.name }.toSet().size)
        } finally {
            traceDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `renders valid json and escapes metadata`() {
        val traceDir = createTempDirectory("termora-startup-trace")
        val session = StartupTraceSession(
            runId = "run\"\\\n",
            processId = 42,
            traceDir = traceDir,
            epochMillis = { 2_000L },
            nanoTime = { 10L },
            currentThreadName = { "AWT-EventQueue-0" },
        )

        try {
            session.mark(StartupProbe.INTERACTIVE)
            val root = Json.parseToJsonElement(session.renderJson()).jsonObject

            assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
            assertEquals("run\"\\\n", root.getValue("runId").jsonPrimitive.content)
            assertEquals(42L, root.getValue("pid").jsonPrimitive.content.toLong())
            assertTrue(root.getValue("completed").jsonPrimitive.boolean)
            assertEquals(
                StartupProbe.INTERACTIVE,
                root.getValue("events").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content,
            )
        } finally {
            traceDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `writes one atomically replaceable trace per process`() {
        val traceDir = createTempDirectory("termora-startup-trace")
        val session = StartupTraceSession(
            runId = "run/id",
            processId = 99,
            traceDir = traceDir,
            epochMillis = { 3_000L },
            nanoTime = { 20L },
        )

        try {
            session.mark(StartupProbe.MAIN_ENTER)
            session.writeSnapshot()

            val output = traceDir.resolve("run_id-99.json")
            assertTrue(output.exists())
            assertFalse(Json.parseToJsonElement(output.readText()).jsonObject.getValue("completed").jsonPrimitive.boolean)

            session.mark(StartupProbe.INTERACTIVE)
            session.writeSnapshot()

            assertTrue(Json.parseToJsonElement(output.readText()).jsonObject.getValue("completed").jsonPrimitive.boolean)
            assertEquals(listOf(output), traceDir.listDirectoryEntries())
        } finally {
            traceDir.toFile().deleteRecursively()
        }
    }
}
