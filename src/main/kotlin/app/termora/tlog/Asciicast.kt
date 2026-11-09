package app.termora.tlog

import app.termora.terminal.TerminalSize
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.time.Instant

internal object Asciicast {
    data class Header(val version: Int, val size: TerminalSize)
    data class Event(val seconds: Double, val code: String, val data: String)

    fun header(size: TerminalSize, timestamp: Instant, title: String): String = buildJsonObject {
        put("version", 3)
        putJsonObject("term") {
            put("cols", size.cols)
            put("rows", size.rows)
        }
        put("timestamp", timestamp.epochSecond)
        put("title", title)
    }.toString()

    fun encode(event: Event): String = buildJsonArray {
        add(event.seconds)
        add(event.code)
        add(event.data)
    }.toString()

    fun readHeader(reader: BufferedReader): Header {
        val json = Json.parseToJsonElement(requireNotNull(reader.readLine())).jsonObject
        val version = json.getValue("version").jsonPrimitive.int
        val size = when (version) {
            2 -> TerminalSize(json.getValue("height").jsonPrimitive.int, json.getValue("width").jsonPrimitive.int)
            3 -> json.getValue("term").jsonObject.let {
                TerminalSize(it.getValue("rows").jsonPrimitive.int, it.getValue("cols").jsonPrimitive.int)
            }
            else -> error("Unsupported asciicast version: $version")
        }
        return Header(version, validateSize(size))
    }

    fun events(reader: BufferedReader, header: Header): Sequence<Event> = sequence {
        var elapsed = 0.0
        for (line in reader.lineSequence()) {
            if (line.isBlank() || (header.version == 3 && line.startsWith('#'))) continue
            val fields = Json.parseToJsonElement(line).jsonArray
            require(fields.size == 3)
            val time = fields[0].jsonPrimitive.double
            val next = if (header.version == 3) elapsed + time else time
            require(next.isFinite() && next >= elapsed)
            elapsed = next
            val code = fields[1].jsonPrimitive.content
            val data = when (code) {
                "o", "i", "r" -> fields[2].jsonPrimitive.content
                else -> ""
            }
            yield(Event(elapsed, code, data))
        }
    }

    fun resize(data: String): TerminalSize {
        val (cols, rows) = data.split('x', limit = 2)
        return validateSize(TerminalSize(rows.toInt(), cols.toInt()))
    }

    private fun validateSize(size: TerminalSize): TerminalSize {
        require(size.rows in 1..1000 && size.cols in 1..1000)
        return size
    }
}
