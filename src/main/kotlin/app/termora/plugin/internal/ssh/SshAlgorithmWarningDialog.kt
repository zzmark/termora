package app.termora.plugin.internal.ssh

import app.termora.I18n
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*

internal object SshAlgorithmWarningDialog {
    fun confirm(owner: Window?, hostName: String, address: String, warnings: List<SshAlgorithmWarning>): Boolean {
        if (owner == null || warnings.isEmpty()) return false

        val confirmed = AtomicBoolean(false)
        val showDialog = {
            val model = DefaultListModel<String>()
            for (warning in warnings) {
                model.addElement("${categoryTitle(warning.category)}: ${warning.algorithm}")
            }

            val list = JList(model)
            list.visibleRowCount = minOf(model.size(), 8)
            list.isFocusable = false

            val header = Box.createVerticalBox()
            header.add(JLabel(I18n.getString("termora.ssh.warning.server", hostName, address)))
            header.add(Box.createVerticalStrut(8))
            header.add(JLabel(I18n.getString("termora.ssh.warning.algorithms")))

            val panel = JPanel(BorderLayout(0, 8))
            panel.preferredSize = Dimension(520, 180)
            panel.add(header, BorderLayout.NORTH)
            panel.add(JScrollPane(list), BorderLayout.CENTER)
            panel.add(JLabel(I18n.getString("termora.ssh.warning.description")), BorderLayout.SOUTH)

            val options = arrayOf(
                I18n.getString("termora.ssh.warning.continue-once"),
                I18n.getString("termora.ssh.warning.cancel"),
            )
            val result = JOptionPane.showOptionDialog(
                owner,
                panel,
                I18n.getString("termora.ssh.warning.title"),
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[1],
            )
            confirmed.set(result == 0)
        }

        if (SwingUtilities.isEventDispatchThread()) {
            showDialog()
        } else {
            SwingUtilities.invokeAndWait(showDialog)
        }
        return confirmed.get()
    }

    private fun categoryTitle(category: SshAlgorithmCategory): String {
        val key = when (category) {
            SshAlgorithmCategory.KeyExchange -> "termora.new-host.ssh.key-exchange"
            SshAlgorithmCategory.HostKey -> "termora.new-host.ssh.host-key"
            SshAlgorithmCategory.Cipher -> "termora.new-host.ssh.cipher"
            SshAlgorithmCategory.Mac -> "termora.new-host.ssh.mac"
            SshAlgorithmCategory.Compression -> "termora.new-host.ssh.compression"
        }
        return I18n.getString(key)
    }
}
