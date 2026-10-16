package app.termora.plugin.internal.ssh

import app.termora.Host
import org.junit.jupiter.api.DisplayName
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.*

class SshSystemStatusBarTest {
    private fun labels(component: Component): List<JLabel> = when (component) {
        is JLabel -> listOf(component)
        is Container -> component.components.flatMap(::labels)
        else -> emptyList()
    }

    @Test
    @DisplayName("数值位数及单位变化时CPU内存与网络模块保持固定宽度")
    fun preservesModuleWidthsWhenDigitsAndUnitsChange() {
        SwingUtilities.invokeAndWait {
            val monitor = SshSystemMonitor()
            val bar = SshSystemStatusBar(Host(name = "test", protocol = "SSH"), monitor, StatusBarPreferences("") {})
            try {
                for (module in listOf(StatusModule.Cpu, StatusModule.Memory, StatusModule.Upload, StatusModule.Download)) {
                    val label = labels(bar).first { it.accessibleContext.accessibleName.startsWith(module.title() + ":") }
                    val samples = when (module) {
                        StatusModule.Cpu -> listOf("-", "0%", "9%", "100%")
                        StatusModule.Memory -> listOf("-", "0.00B / 8.00GB", "99.99GB / 1023GB")
                        else -> listOf("-", "0.00B/s", "99.99KB/s", "1023GB/s")
                    }
                    val width = label.preferredSize.width
                    for (sample in samples) {
                        label.text = sample
                        assertEquals(width, label.preferredSize.width)
                        assertEquals(width, label.minimumSize.width)
                        assertEquals(width, label.maximumSize.width)
                        val available = width - label.insets.left - label.insets.right - label.icon.iconWidth - label.iconTextGap
                        assertTrue(label.getFontMetrics(label.font).stringWidth(sample) <= available)
                    }
                }
            } finally {
                bar.dispose()
                monitor.dispose()
            }
        }
    }

    @Test
    @DisplayName("容量格式小于100保留两位小数达到100显示整数并处理单位边界")
    fun formatsBytesWithTwoDecimalsBelow100AndHandlesUnitBoundaries() {
        val cases = mapOf(
            0L to "0.00B", 99L to "99.00B", 100L to "100B", 1023L to "1023B", 1024L to "1.00KB",
            102_389L to "99.99KB", 102_399L to "100KB", 102_400L to "100KB",
            102_912L to "101KB", 1_048_063L to "1023KB", 1_048_064L to "1.00MB",
            Long.MAX_VALUE to "8.00EB",
        )
        val decimalSeparator = String.format("%.2f", 1.5).first { !it.isDigit() }
        for ((bytes, expected) in cases) {
            assertEquals(expected.replace('.', decimalSeparator), formatStatusBytes(bytes))
        }
    }

    @Test
    @DisplayName("模块偏好保存后可恢复并通知所有订阅者")
    fun restoresSavedModulePreferencesAndNotifiesSubscribers() {
        var saved = ""
        val preferences = StatusBarPreferences("") { saved = it }
        var first = 0
        var second = 0
        preferences.listeners.add { first++ }
        preferences.listeners.add { second++ }
        preferences.toggle(StatusModule.Cpu)
        preferences.toggle(StatusModule.Storage)
        val restored = StatusBarPreferences(saved) {}
        assertFalse(restored.visible(StatusModule.Cpu))
        assertFalse(restored.visible(StatusModule.Storage))
        assertTrue(restored.visible(StatusModule.Memory))
        assertEquals(2, first)
        assertEquals(2, second)
        preferences.toggle(StatusModule.Cpu)
        assertTrue(preferences.visible(StatusModule.Cpu))
    }
}
