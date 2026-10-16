package app.termora.terminal.panel.vw

import app.termora.Disposable
import app.termora.Disposer
import app.termora.I18n
import app.termora.formatBytes
import app.termora.plugin.internal.ssh.SSHTerminalTab
import app.termora.plugin.internal.ssh.SystemSnapshot
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

internal class SystemInformationVisualWindow(tab: SSHTerminalTab, visualWindowManager: VisualWindowManager) :
    SSHVisualWindow(tab, "SystemInformation", visualWindowManager) {

    private val cpu = SmartProgressBar()
    private val memory = SmartProgressBar()
    private val swap = SmartProgressBar()
    private val model = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val state = JLabel()
    private val subscription: Disposable

    init {
        title = I18n.getString("termora.visual-window.system-information")
        val gauges = JPanel(GridLayout(3, 2, 6, 6))
        gauges.add(JLabel("CPU:"))
        gauges.add(cpu)
        gauges.add(JLabel(I18n.getString("termora.visual-window.system-information.mem")))
        gauges.add(memory)
        gauges.add(JLabel(I18n.getString("termora.visual-window.system-information.swap")))
        gauges.add(swap)
        model.addColumn(I18n.getString("termora.system-status.mount"))
        model.addColumn(I18n.getString("termora.visual-window.system-information.used-total"))
        val content = JPanel(BorderLayout(6, 6))
        content.border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        content.add(gauges, BorderLayout.NORTH)
        content.add(JScrollPane(JTable(model).apply { fillsViewportHeight = true }), BorderLayout.CENTER)
        content.add(state, BorderLayout.SOUTH)
        add(content, BorderLayout.CENTER)
        subscription = tab.systemMonitor.subscribe(::update, keepSampling = true)
        Disposer.register(this, subscription)
        Disposer.register(tab, this)
        initVisualWindowPanel()
    }

    private fun update(snapshot: SystemSnapshot) {
        state.text = I18n.getString("termora.system-status.${snapshot.state}")
        cpu.value = snapshot.cpu ?: 0
        cpu.string = snapshot.cpu?.let { "$it%" } ?: "-"
        updateGauge(memory, snapshot.memoryUsed, snapshot.memoryTotal)
        updateGauge(swap, snapshot.swapUsed, snapshot.swapTotal)
        model.rowCount = 0
        if (snapshot.detailsAt > 0 && System.currentTimeMillis() - snapshot.detailsAt <= 20_000) {
            snapshot.disks.orEmpty().forEach {
                model.addRow(arrayOf(it.mount, "${formatBytes(it.used)} / ${formatBytes(it.total)}"))
            }
        }
    }

    private fun updateGauge(bar: SmartProgressBar, used: Long?, total: Long?) {
        bar.value = if (used != null && total != null && total > 0) (used.toDouble() / total * 100).toInt() else 0
        bar.string = if (used != null && total != null) "${formatBytes(used)} / ${formatBytes(total)}" else "-"
    }
}
