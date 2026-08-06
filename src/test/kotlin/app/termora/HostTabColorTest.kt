package app.termora

import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostTabColorTest {

    @Test
    fun `preset colors match light and dark reference palettes`() {
        val expected = mapOf(
            "blue" to (0xE9F6FF to 0x4F556B),
            "gray" to (0xF6F6F6 to 0x44484B),
            "green" to (0xEFFBE7 to 0x48554C),
            "orange" to (0xF8E5DF to 0x7C6253),
            "rose" to (0xF3DCD6 to 0x6E535A),
            "violet" to (0xE8DFF0 to 0x524A57),
            "yellow" to (0xFFFFE5 to 0x4E4C3F),
        )

        for (preset in HostTabColor.presets) {
            assertEquals(expected.getValue(preset.value).first, preset.color(false).rgb and 0xFFFFFF)
            assertEquals(expected.getValue(preset.value).second, preset.color(true).rgb and 0xFFFFFF)
        }
    }

    @Test
    fun `custom colors are normalized and blended with the tab background`() {
        assertEquals("#12ABEF", HostTabColor.format(Color(0x12ABEF)))
        assertEquals(0xFFD1D1, HostTabColor.resolve("#FF0000", Color.WHITE, false)?.rgb?.and(0xFFFFFF))
        assertNull(HostTabColor.resolve("invalid", Color.WHITE, false))
    }

    @Test
    fun `preset colors selected through custom chooser are stored as presets`() {
        val blue = HostTabColor.presets.first { it.value == "blue" }

        assertEquals(blue, HostTabColor.preset("blue"))
        assertEquals(blue, HostTabColor.preset("#E9F6FF"))
        assertEquals(blue, HostTabColor.preset("#4F556B"))
        assertEquals("blue", HostTabColor.valueOf(Color(0xE9F6FF)))
        assertEquals("blue", HostTabColor.valueOf(Color(0x4F556B)))
        assertEquals("#12ABEF", HostTabColor.valueOf(Color(0x12ABEF)))
    }

    @Test
    fun `host color survives serialization while old data defaults to no color`() {
        val host = Host(name = "demo", protocol = "SSH", options = Options(color = "blue"))
        val decoded = Application.ohMyJson.decodeFromString<Host>(Application.ohMyJson.encodeToString(host))
        assertEquals("blue", decoded.options.color)

        val oldHost = Application.ohMyJson.decodeFromString<Host>("""{"name":"demo","protocol":"SSH"}""")
        assertEquals("", oldHost.options.color)
    }
}
