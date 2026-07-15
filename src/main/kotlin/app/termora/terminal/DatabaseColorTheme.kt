package app.termora.terminal

import app.termora.database.DatabaseManager
import kotlinx.serialization.Serializable

@Serializable
internal data class CustomTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
) {
    val builtIn: Boolean get() = id == LIGHT_ID || id == DARK_ID

    companion object {
        const val LIGHT_ID = "light"
        const val DARK_ID = "dark"

        val light = CustomTheme(LIGHT_ID, "Custom Light", false)
        val dark = CustomTheme(DARK_ID, "Custom Dark", true)
    }
}

class DatabaseColorTheme : ColorTheme {
    companion object {
        internal val colors = linkedMapOf(
            TerminalColor.Basic.BACKGROUND to "background",
            TerminalColor.Basic.FOREGROUND to "foreground",
            TerminalColor.Basic.SELECTION_BACKGROUND to "selection-background",
            TerminalColor.Basic.SELECTION_FOREGROUND to "selection-foreground",
            TerminalColor.Basic.HYPERLINK to "hyperlink",
            TerminalColor.Cursor.BACKGROUND to "cursor",
            TerminalColor.Find.BACKGROUND to "find-background",
            TerminalColor.Find.FOREGROUND to "find-foreground",
            TerminalColor.Normal.BLACK to "normal-black",
            TerminalColor.Normal.RED to "normal-red",
            TerminalColor.Normal.GREEN to "normal-green",
            TerminalColor.Normal.YELLOW to "normal-yellow",
            TerminalColor.Normal.BLUE to "normal-blue",
            TerminalColor.Normal.MAGENTA to "normal-magenta",
            TerminalColor.Normal.CYAN to "normal-cyan",
            TerminalColor.Normal.WHITE to "normal-white",
            TerminalColor.Bright.BLACK to "bright-black",
            TerminalColor.Bright.RED to "bright-red",
            TerminalColor.Bright.GREEN to "bright-green",
            TerminalColor.Bright.YELLOW to "bright-yellow",
            TerminalColor.Bright.BLUE to "bright-blue",
            TerminalColor.Bright.MAGENTA to "bright-magenta",
            TerminalColor.Bright.CYAN to "bright-cyan",
            TerminalColor.Bright.WHITE to "bright-white",
        )

        private val lightDefaults = mapOf(
            TerminalColor.Basic.BACKGROUND to 0xffffff,
            TerminalColor.Basic.FOREGROUND to 0x121313,
            TerminalColor.Basic.SELECTION_BACKGROUND to 0xc6dcfc,
            TerminalColor.Basic.SELECTION_FOREGROUND to 0x000000,
            TerminalColor.Basic.HYPERLINK to 0x255ab4,
            TerminalColor.Cursor.BACKGROUND to 0x49434f,
            TerminalColor.Find.BACKGROUND to 0xffff00,
            TerminalColor.Find.FOREGROUND to 0x000000,
            TerminalColor.Normal.BLACK to 0x000000,
            TerminalColor.Normal.RED to 0xcc3030,
            TerminalColor.Normal.GREEN to 0x218c21,
            TerminalColor.Normal.YELLOW to 0xa57c00,
            TerminalColor.Normal.BLUE to 0x265fd0,
            TerminalColor.Normal.MAGENTA to 0xb020b0,
            TerminalColor.Normal.CYAN to 0x168c8c,
            TerminalColor.Normal.WHITE to 0xb0b0b0,
            TerminalColor.Bright.BLACK to 0x808080,
            TerminalColor.Bright.RED to 0xff6060,
            TerminalColor.Bright.GREEN to 0x42b842,
            TerminalColor.Bright.YELLOW to 0xd6a800,
            TerminalColor.Bright.BLUE to 0x3b78ff,
            TerminalColor.Bright.MAGENTA to 0xd936d9,
            TerminalColor.Bright.CYAN to 0x28b8b8,
            TerminalColor.Bright.WHITE to 0xececec,
        )

        private val darkDefaults = mapOf(
            TerminalColor.Basic.BACKGROUND to 0x010000,
            TerminalColor.Basic.FOREGROUND to 0xedecec,
            TerminalColor.Basic.SELECTION_BACKGROUND to 0x214283,
            TerminalColor.Basic.SELECTION_FOREGROUND to 0xffffff,
            TerminalColor.Basic.HYPERLINK to 0x589df6,
            TerminalColor.Cursor.BACKGROUND to 0xb6b4c0,
            TerminalColor.Find.BACKGROUND to 0xffff00,
            TerminalColor.Find.FOREGROUND to 0x000000,
            TerminalColor.Normal.BLACK to 0x000000,
            TerminalColor.Normal.RED to 0xff6060,
            TerminalColor.Normal.GREEN to 0x60ff60,
            TerminalColor.Normal.YELLOW to 0xffff36,
            TerminalColor.Normal.BLUE to 0x3b78ff,
            TerminalColor.Normal.MAGENTA to 0xff36ff,
            TerminalColor.Normal.CYAN to 0x36ffff,
            TerminalColor.Normal.WHITE to 0xececec,
            TerminalColor.Bright.BLACK to 0x808080,
            TerminalColor.Bright.RED to 0xff8080,
            TerminalColor.Bright.GREEN to 0x80ff80,
            TerminalColor.Bright.YELLOW to 0xffff80,
            TerminalColor.Bright.BLUE to 0x72a1ff,
            TerminalColor.Bright.MAGENTA to 0xff82ff,
            TerminalColor.Bright.CYAN to 0x82ffff,
            TerminalColor.Bright.WHITE to 0xffffff,
        )

        internal fun getDefaultColor(dark: Boolean, color: TerminalColor): Int {
            return if (dark) darkDefaults.getValue(color) else lightDefaults.getValue(color)
        }
    }

    private val settings get() = DatabaseManager.getInstance().theme

    override fun getColor(color: TerminalColor): Int {
        if (!colors.containsKey(color)) return Int.MAX_VALUE
        return settings.getColor(settings.activeTheme().id, color)
    }
}
