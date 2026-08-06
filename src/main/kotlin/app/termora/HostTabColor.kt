package app.termora

import com.formdev.flatlaf.FlatLaf
import java.awt.Color

data class HostTabColorPreset(
    val value: String,
    val i18nKey: String,
    val light: Color,
    val dark: Color,
) {
    fun color(isDark: Boolean = FlatLaf.isLafDark()): Color = if (isDark) dark else light
}

object HostTabColor {
    val presets = listOf(
        HostTabColorPreset("blue", "termora.host.color.blue", Color(0xE9F6FF), Color(0x4F556B)),
        HostTabColorPreset("gray", "termora.host.color.gray", Color(0xF6F6F6), Color(0x44484B)),
        HostTabColorPreset("green", "termora.host.color.green", Color(0xEFFBE7), Color(0x48554C)),
        HostTabColorPreset("orange", "termora.host.color.orange", Color(0xF8E5DF), Color(0x7C6253)),
        HostTabColorPreset("rose", "termora.host.color.rose", Color(0xF3DCD6), Color(0x6E535A)),
        HostTabColorPreset("violet", "termora.host.color.violet", Color(0xE8DFF0), Color(0x524A57)),
        HostTabColorPreset("yellow", "termora.host.color.yellow", Color(0xFFFFE5), Color(0x4E4C3F)),
    )

    fun resolve(value: String, background: Color, isDark: Boolean = FlatLaf.isLafDark()): Color? {
        if (value.isBlank()) return null

        preset(value)?.let { return it.color(isDark) }

        val custom = parse(value) ?: return null
        return blend(background, custom, if (isDark) 0.32f else 0.18f)
    }

    fun preset(value: String): HostTabColorPreset? {
        presets.firstOrNull { it.value == value }?.let { return it }
        return parse(value)?.let { preset(it) }
    }

    fun preset(color: Color): HostTabColorPreset? {
        val rgb = color.rgb and 0xFFFFFF
        return presets.firstOrNull {
            it.light.rgb and 0xFFFFFF == rgb || it.dark.rgb and 0xFFFFFF == rgb
        }
    }

    fun valueOf(color: Color): String = preset(color)?.value ?: format(color)

    fun parse(value: String): Color? {
        if (!value.matches(Regex("#[0-9A-Fa-f]{6}"))) return null
        return runCatching { Color(value.substring(1).toInt(16)) }.getOrNull()
    }

    fun format(color: Color): String = "#%06X".format(color.rgb and 0xFFFFFF)

    fun blend(background: Color, foreground: Color, foregroundAlpha: Float): Color {
        val alpha = foregroundAlpha.coerceIn(0f, 1f)
        return Color(
            blend(background.red, foreground.red, alpha),
            blend(background.green, foreground.green, alpha),
            blend(background.blue, foreground.blue, alpha),
        )
    }

    private fun blend(background: Int, foreground: Int, foregroundAlpha: Float): Int {
        return (background * (1f - foregroundAlpha) + foreground * foregroundAlpha).toInt().coerceIn(0, 255)
    }
}
