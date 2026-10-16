package app.termora.plugin.internal.ssh

import app.termora.*
import app.termora.database.DatabaseManager
import org.apache.commons.text.StringEscapeUtils
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.*
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val statusByteUnits = listOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

internal fun formatStatusBytes(bytes: Long): String {
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < statusByteUnits.lastIndex) {
        value /= 1024
        unit++
    }
    val scale = if (value < 100) 100.0 else 1.0
    value = (value * scale).roundToLong() / scale
    if (value >= 1024 && unit < statusByteUnits.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(if (value < 100) "%.2f%s" else "%.0f%s", value, statusByteUnits[unit])
}

internal enum class StatusModule(val key: String) {
    Host("host"), Cpu("cpu"), History("history"), Memory("memory"),
    Upload("upload"), Download("download"), User("user"), Storage("storage");

    fun title(): String = I18n.getString("termora.system-status.$key")
}

internal class StatusBarPreferences(hidden: String, private val save: (String) -> Unit) {
    private val hiddenModules = hidden.split(',').toMutableSet()
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun visible(module: StatusModule): Boolean = module.name !in hiddenModules

    fun toggle(module: StatusModule) {
        if (!hiddenModules.remove(module.name)) hiddenModules.add(module.name)
        save(hiddenModules.filter { it.isNotBlank() }.sorted().joinToString(","))
        listeners.forEach { it() }
    }

    companion object {
        val shared by lazy {
            val terminal = DatabaseManager.getInstance().terminal
            StatusBarPreferences(terminal.hiddenSystemStatusModules) { terminal.hiddenSystemStatusModules = it }
        }
    }
}

internal class SshSystemStatusBar(
    private val host: Host,
    monitor: SshSystemMonitor,
    private val preferences: StatusBarPreferences = StatusBarPreferences.shared,
) : JPanel(BorderLayout()), Disposable {
    private val content = object : JPanel() {
        override fun getPreferredSize(): Dimension = super.getPreferredSize().apply { height = height.coerceAtLeast(28) }
    }.apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
    private val labels = StatusModule.entries.filter { it != StatusModule.History && it != StatusModule.Storage }
        .associateWith { module -> StatusLabel(module) }
    private val history = CpuHistory()
    private val storage = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
    private val disks = linkedMapOf<String, StatusLabel>()
    private var snapshot = SystemSnapshot()
    private val visibilityListener: () -> Unit = { rebuild() }
    private val subscription: Disposable

    init {
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, DynamicColor.BorderColor)
        minimumSize = Dimension(0, 29)
        content.border = BorderFactory.createEmptyBorder(0, 0, 0, 16)
        val scroll = JScrollPane(content, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.viewport.background = background
        scroll.addMouseWheelListener {
            scroll.horizontalScrollBar.value += it.wheelRotation * 32
            it.consume()
        }
        installPopup(this, null)
        installPopup(content, null)
        installPopup(scroll.viewport, null)
        installPopup(storage, null)
        installPopup(history, StatusModule.History)
        labels.forEach { (module, label) -> installPopup(label, module) }
        add(scroll, BorderLayout.CENTER)
        preferences.listeners.add(visibilityListener)
        rebuild()
        subscription = monitor.subscribe(::update)
    }

    private fun rebuild() {
        content.removeAll()
        for (module in StatusModule.entries) {
            if (!preferences.visible(module)) continue
            content.add(when (module) {
                StatusModule.History -> history
                StatusModule.Storage -> storage
                else -> labels.getValue(module)
            })
        }
        content.add(Box.createHorizontalGlue())
        content.minimumSize = Dimension(0, 28)
        content.revalidate()
        content.repaint()
    }

    private fun update(value: SystemSnapshot) {
        snapshot = value
        val unavailable = I18n.getString("termora.system-status.${value.state}")
        val unavailableText = "-"
        fun set(module: StatusModule, text: String?, details: String? = null) {
            labels.getValue(module).apply {
                this.text = text ?: unavailableText
                this.details = details
                toolTipText = tooltip(details ?: if (text == null) unavailable else module.title())
                accessibleContext.accessibleName = "${module.title()}: ${this.text}"
            }
        }
        val connected = value.state != "disconnected"
        set(StatusModule.Host, value.hostname.ifBlank { host.name },
            "${host.host}:${host.port}\n${value.system}\n$unavailable".trim())
        set(StatusModule.Cpu, value.cpu?.let { "$it%" })
        set(StatusModule.Memory, value.memoryUsed?.let { used ->
            value.memoryTotal?.let { total -> "${formatStatusBytes(used)} / ${formatStatusBytes(total)}" }
        })
        val networkDetails = value.networks?.joinToString("\n", "${StatusModule.Upload.title()} / ${StatusModule.Download.title()}\n") {
            "${it.name}  ↑ ${formatStatusBytes(it.sent)}/s  ↓ ${formatStatusBytes(it.received)}/s"
        }
        set(StatusModule.Upload, value.networks?.let { "${formatStatusBytes(it.sumOf { n -> n.sent })}/s" }, networkDetails)
        set(StatusModule.Download, value.networks?.let { "${formatStatusBytes(it.sumOf { n -> n.received })}/s" }, networkDetails)
        val detailsFresh = connected && value.detailsAt > 0 && System.currentTimeMillis() - value.detailsAt <= 20_000
        set(StatusModule.User, if (connected) value.username.ifBlank { host.username } else null,
            if (detailsFresh) value.who else unavailable)
        history.values = value.history
        history.toolTipText = tooltip(if (value.cpu == null) unavailable else StatusModule.History.title())
        history.repaint()
        val mounts = if (detailsFresh) value.disks.orEmpty().map { it.mount } else listOf("")
        if (disks.keys.toList() != mounts) {
            storage.removeAll()
            disks.clear()
            for (mount in mounts) {
                val label = StatusLabel(StatusModule.Storage)
                installPopup(label, StatusModule.Storage)
                disks[mount] = label
                storage.add(label)
            }
        }
        for ((mount, label) in disks) {
            val disk = value.disks?.firstOrNull { it.mount == mount }
            label.text = if (detailsFresh && disk != null) "${disk.mount} ${disk.percentage}%" else unavailableText
            label.details = if (detailsFresh) value.df else null
            label.toolTipText = tooltip(label.details ?: unavailable)
            label.foreground = if (detailsFresh && disk != null && disk.percentage >= 90)
                UIManager.getColor("Component.warning.focusedBorderColor") ?: foreground else foreground
        }
        storage.revalidate()
        revalidate()
        repaint()
    }

    private fun installPopup(component: JComponent, module: StatusModule?) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { show(e) }
            override fun mouseReleased(e: MouseEvent) { show(e) }
            private fun show(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val menu = JPopupMenu()
                if (module != null) {
                    val text = if (module == StatusModule.History) snapshot.history.joinToString(", ") { "$it%" }
                        else (component as? JLabel)?.text.orEmpty()
                    menu.add(JMenuItem(I18n.getString("termora.copy"), Icons.copy).apply {
                        addActionListener { copy(text) }
                    })
                    (component as? StatusLabel)?.details?.let { details ->
                        menu.add(JMenuItem(I18n.getString("termora.system-status.copy-details")).apply {
                            addActionListener { copy(details) }
                        })
                    }
                    menu.addSeparator()
                }
                for (entry in StatusModule.entries) {
                    menu.add(JCheckBoxMenuItem(entry.title(), preferences.visible(entry)).apply {
                        addActionListener { preferences.toggle(entry) }
                    })
                }
                menu.show(component, e.x, e.y)
            }
        })
    }

    private fun copy(text: String) {
        toolkit.systemClipboard.setContents(StringSelection(text), null)
    }

    private fun tooltip(text: String): String = "<html><pre>${StringEscapeUtils.escapeHtml4(text)}</pre></html>"

    private class StatusLabel(private val module: StatusModule) : JLabel("-") {
        var details: String? = null
        init {
            icon = when (module) {
                StatusModule.Host -> Icons.server
                StatusModule.Cpu, StatusModule.Memory, StatusModule.Storage -> MetricIcon(module)
                StatusModule.Upload -> Icons.upload
                StatusModule.Download -> Icons.download
                StatusModule.User -> Icons.user
                else -> null
            }
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, DynamicColor.BorderColor),
                BorderFactory.createEmptyBorder(5, 8, 5, 8),
            )
            iconTextGap = 5
        }

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            when (module) {
                StatusModule.Cpu, StatusModule.Memory, StatusModule.Upload, StatusModule.Download -> Unit
                else -> return size
            }
            val metrics = getFontMetrics(font)
            val digit = ('0'..'9').maxBy { metrics.charWidth(it) }
            val numbers = listOf(String.format("%.2f", 99.99), "1023").map { number ->
                number.map { if (it.isDigit()) digit else it }.joinToString("")
            }
            val bytesWidth = statusByteUnits.maxOf { unit ->
                numbers.maxOf { number -> metrics.stringWidth("$number$unit") }
            }
            val textWidth = when (module) {
                StatusModule.Cpu -> metrics.charWidth(digit) * 3 + metrics.stringWidth("%")
                StatusModule.Memory -> bytesWidth * 2 + metrics.stringWidth(" / ")
                else -> bytesWidth + metrics.stringWidth("/s")
            }
            size.width = insets.left + insets.right + (icon?.iconWidth ?: 0) + iconTextGap + textWidth
            return size
        }

        override fun getMinimumSize(): Dimension = when (module) {
            StatusModule.Cpu, StatusModule.Memory, StatusModule.Upload, StatusModule.Download -> preferredSize
            else -> super.getMinimumSize()
        }

        override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, Int.MAX_VALUE)
    }

    private class CpuHistory : JComponent() {
        var values: List<Int> = emptyList()
        init {
            preferredSize = Dimension(112, 28)
            minimumSize = preferredSize
            maximumSize = preferredSize
            ToolTipManager.sharedInstance().registerComponent(this)
            accessibleContext?.accessibleName = StatusModule.History.title()
        }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g.color = UIManager.getColor("Component.accentColor") ?: foreground
            val baseline = height - 5
            for ((index, value) in values.withIndex()) {
                val barHeight = ((height - 10) * value / 100.0).roundToInt().coerceAtLeast(1)
                g.fillRect(8 + (32 - values.size + index) * 3, baseline - barHeight, 2, barHeight)
            }
            g.color = DynamicColor.BorderColor
            g.drawLine(width - 1, 0, width - 1, height)
        }
    }

    private class MetricIcon(private val module: StatusModule) : Icon {
        override fun getIconWidth() = 16
        override fun getIconHeight() = 16
        override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
            val graphics = g.create(x, y, 16, 16) as Graphics2D
            graphics.color = c.foreground
            when (module) {
                StatusModule.Cpu -> {
                    graphics.drawRect(4, 4, 7, 7)
                    graphics.drawRect(6, 6, 3, 3)
                    for (i in 5..11 step 3) {
                        graphics.drawLine(i, 1, i, 3); graphics.drawLine(i, 12, i, 14)
                        graphics.drawLine(1, i, 3, i); graphics.drawLine(12, i, 14, i)
                    }
                }
                StatusModule.Memory -> {
                    graphics.drawRect(1, 4, 13, 7)
                    for (i in 3..12 step 3) {
                        graphics.fillRect(i, 6, 2, 3); graphics.drawLine(i, 12, i, 14)
                    }
                }
                else -> {
                    graphics.drawRoundRect(2, 2, 11, 12, 2, 2)
                    graphics.drawLine(3, 10, 12, 10)
                    graphics.fillRect(10, 12, 1, 1)
                }
            }
            graphics.dispose()
        }
    }

    override fun dispose() {
        subscription.dispose()
        preferences.listeners.remove(visibilityListener)
    }
}
