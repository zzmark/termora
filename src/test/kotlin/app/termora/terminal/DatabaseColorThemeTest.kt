package app.termora.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseColorThemeTest {
    @Test
    fun `theme colors have stable unique keys and valid defaults`() {
        assertEquals(DatabaseColorTheme.colors.size, DatabaseColorTheme.colors.values.toSet().size)

        DatabaseColorTheme.colors.keys.forEach { color ->
            assertTrue(DatabaseColorTheme.getDefaultColor(false, color) in 0x000000..0xffffff)
            assertTrue(DatabaseColorTheme.getDefaultColor(true, color) in 0x000000..0xffffff)
        }
    }

    @Test
    fun `only bundled themes are protected from deletion`() {
        assertTrue(CustomTheme.light.builtIn)
        assertTrue(CustomTheme.dark.builtIn)
        assertTrue(!CustomTheme("custom", "Custom", false).builtIn)
    }

    @Test
    fun `custom dark defaults match the configured color table`() {
        assertEquals(0x010000, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Basic.BACKGROUND))
        assertEquals(0xedecec, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Basic.FOREGROUND))
        assertEquals(0xb6b4c0, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Cursor.BACKGROUND))
        assertEquals(0xff6060, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Normal.RED))
        assertEquals(0xff8080, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Bright.RED))
        assertEquals(0x60ff60, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Normal.GREEN))
        assertEquals(0x80ff80, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Bright.GREEN))
        assertEquals(0xffff36, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Normal.YELLOW))
        assertEquals(0xffff80, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Bright.YELLOW))
        assertEquals(0x3b78ff, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Normal.BLUE))
        assertEquals(0x72a1ff, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Bright.BLUE))
        assertEquals(0xff36ff, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Normal.MAGENTA))
        assertEquals(0xff82ff, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Bright.MAGENTA))
        assertEquals(0x36ffff, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Normal.CYAN))
        assertEquals(0x82ffff, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Bright.CYAN))
        assertEquals(0xececec, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Normal.WHITE))
        assertEquals(0xffffff, DatabaseColorTheme.getDefaultColor(true, TerminalColor.Bright.WHITE))
    }
}
