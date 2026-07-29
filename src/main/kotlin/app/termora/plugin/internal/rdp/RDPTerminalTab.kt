package app.termora.plugin.internal.rdp

import app.termora.Disposer
import app.termora.Host
import app.termora.HostTerminalTab
import app.termora.WindowScope
import javax.swing.Icon
import javax.swing.JComponent

internal class RDPTerminalTab(windowScope: WindowScope, host: Host) : HostTerminalTab(windowScope, host) {
    private val panel = RDPActiveXPanel(host)

    override fun getJComponent(): JComponent {
        return panel
    }

    override fun getIcon(): Icon {
        return RDPProtocolProvider.instance.getIcon()
    }

    override fun canReconnect(): Boolean {
        return false
    }

    override fun onGrabFocus() {
        super.onGrabFocus()
        panel.requestRdpFocus()
    }

    override fun beforeClose() {
        panel.disconnect()
    }

    override fun dispose() {
        Disposer.dispose(panel)
        super.dispose()
    }
}
