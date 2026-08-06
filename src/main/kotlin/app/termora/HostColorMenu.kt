package app.termora

import org.apache.commons.lang3.StringUtils
import java.awt.Color
import java.awt.Component
import javax.swing.JColorChooser
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.UIManager

fun createHostColorMenu(
    owner: Component,
    currentColor: String,
    onColorChanged: (String) -> Unit,
): JMenu {
    val colorMenu = JMenu(I18n.getString("termora.host.color"))
    val currentPreset = HostTabColor.preset(currentColor)
    val emptyColorIcon = ColorIcon(color = Color(0, 0, 0, 0))

    colorMenu.add(JMenuItem(I18n.getString("termora.host.color.none"))).apply {
        isSelected = currentColor.isBlank()
        icon = CheckBoxMenuItemColorIcon(emptyColorIcon, isSelected)
        addActionListener { onColorChanged(StringUtils.EMPTY) }
    }

    for (preset in HostTabColor.presets) {
        colorMenu.add(JMenuItem(I18n.getString(preset.i18nKey))).apply {
            isSelected = currentPreset == preset
            icon = CheckBoxMenuItemColorIcon(
                ColorIcon(color = preset.color(), borderColor = Color.WHITE),
                isSelected,
            )
            addActionListener { onColorChanged(preset.value) }
        }
    }

    colorMenu.addSeparator()
    colorMenu.add(JMenuItem(I18n.getString("termora.host.color.custom"))).apply {
        val customColor = HostTabColor.parse(currentColor).takeIf { currentPreset == null }
        isSelected = customColor != null
        icon = CheckBoxMenuItemColorIcon(
            ColorIcon(
                color = customColor ?: Color(0, 0, 0, 0),
                borderColor = if (customColor != null) Color.WHITE else null,
            ),
            isSelected,
        )
        addActionListener {
            val selected = JColorChooser.showDialog(
                owner,
                I18n.getString("termora.host.color.custom"),
                currentPreset?.color() ?: customColor ?: UIManager.getColor("TabbedPane.background"),
            ) ?: return@addActionListener
            onColorChanged(HostTabColor.valueOf(selected))
        }
    }

    return colorMenu
}
