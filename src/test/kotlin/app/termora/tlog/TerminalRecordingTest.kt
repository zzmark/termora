package app.termora.tlog

import app.termora.terminal.TerminalSize
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*

class TerminalRecordingTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `recording writes a standard header and drains output resize and keyboard events`() = runBlocking {
        val start = Instant.parse("2026-11-09T01:00:00Z")
        val path = directory.resolve("test.cast${TerminalLogFiles.PendingSuffix}")
        val compressed = directory.resolve("test.cast.zst").toFile()
        val recording = TerminalLogRecording(
            path,
            Asciicast.header(TerminalSize(24, 80), start, "Test shell"),
            start,
            System.nanoTime(),
        )
        val output = "\u001b[31m你好\u001b[0m\r\n"
        recording.record("o", output)
        recording.record("r", "100x30")
        recording.record("i", "\u0003")
        recording.record("o", "\uD83D")
        recording.record("o", "\uDE42")
        recording.finish()
        recording.write()

        assertFalse(Files.exists(path))
        TerminalLogFiles.open(compressed).use { reader ->
            val json = Json.parseToJsonElement(reader.readLine()).jsonObject
            assertEquals(start.epochSecond, json.getValue("timestamp").jsonPrimitive.long)
            assertEquals("Test shell", json.getValue("title").jsonPrimitive.content)
            assertEquals(setOf("version", "term", "timestamp", "title"), json.keys)
        }
        TerminalLogFiles.open(compressed).use { reader ->
            val header = Asciicast.readHeader(reader)
            assertEquals(Asciicast.Header(3, TerminalSize(24, 80)), header)
            val events = Asciicast.events(reader, header).toList()
            assertEquals(
                listOf("o" to output, "r" to "100x30", "i" to "\u0003", "o" to "\uD83D\uDE42"),
                events.map { it.code to it.data },
            )
            assertTrue(events.zipWithNext().all { (first, second) -> first.seconds <= second.seconds })
        }
    }

    @Test
    fun `v2 absolute timestamps and v3 intervals produce the same timeline`() {
        val v2 = """{"version":2,"width":80,"height":24}
[1.5,"o","A"]
[2.0,"r","90x30"]
"""
        val v3 = """{"version":3,"term":{"cols":80,"rows":24}}
[1.5,"o","A"]
# comment
[0.5,"r","90x30"]
"""
        fun timeline(text: String) = text.reader().buffered().use { reader ->
            Asciicast.events(reader, Asciicast.readHeader(reader)).toList()
        }
        assertEquals(listOf(1.5, 2.0), timeline(v2).map { it.seconds })
        assertEquals(timeline(v2), timeline(v3))
    }

    @Test
    fun `startup recovery compresses marked files across dates and preserves unmarked logs`() {
        val older = Files.createDirectory(directory.resolve("2026-11-08"))
        val newer = Files.createDirectory(directory.resolve("2026-11-09"))
        val marker = TerminalLogFiles.PendingSuffix
        val cast = older.resolve("shell.cast$marker")
        val styled = newer.resolve("shell.log$marker")
        val unmarked = newer.resolve("unmarked.log")
        val castData = """{"version":3,"term":{"cols":80,"rows":24}}""" + "\n"
        cast.writeText(castData)
        styled.writeText("legacy\r\n")
        unmarked.writeText("untouched")
        cast.resolveSibling(cast.fileName.toString() + ".part").writeText("unfinished compressed file")

        repeat(2) { TerminalLogFiles.recover(directory) }

        val compressedCast = older.resolve("shell.cast.zst").toFile()
        val compressedLog = newer.resolve("shell.log.zst").toFile()
        assertFalse(Files.exists(cast))
        assertFalse(Files.exists(styled))
        assertTrue(TerminalLogFiles.isCast(compressedCast))
        assertEquals(castData, TerminalLogFiles.open(compressedCast).use { it.readText() })
        assertEquals("legacy\r\n", TerminalLogFiles.open(compressedLog).use { it.readText() })
        assertEquals("untouched", unmarked.readText())
        assertFalse(Files.exists(cast.resolveSibling(cast.fileName.toString() + ".part")))
        assertContentEquals(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()), compressedCast.readBytes().take(4).toByteArray())
    }

    @Test
    fun `compression failure preserves the raw file and can be retried`() {
        val raw = directory.resolve("test.cast${TerminalLogFiles.PendingSuffix}")
        raw.writeText("recoverable data")
        val target = Files.createDirectory(directory.resolve("test.cast.zst"))
        val blocker = target.resolve("existing")
        blocker.writeText("keep")
        TerminalLogFiles.recover(directory)
        assertEquals("recoverable data", raw.readText())
        assertEquals("keep", blocker.readText())

        Files.delete(blocker)
        Files.delete(target)
        TerminalLogFiles.recover(directory)
        assertFalse(Files.exists(raw))
        assertEquals("recoverable data", TerminalLogFiles.open(target.toFile()).use { it.readText() })
    }
}
