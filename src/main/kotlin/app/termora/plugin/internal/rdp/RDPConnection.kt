package app.termora.plugin.internal.rdp

import app.termora.Host
import app.termora.HostManager
import app.termora.I18n
import app.termora.ProxyType
import app.termora.plugin.internal.ssh.SSHProtocolProvider
import app.termora.plugin.internal.ssh.SshClients
import org.apache.sshd.common.util.net.SshdSocketAddress
import java.awt.Window
import java.io.IOException

internal class RDPConnection private constructor(
    val address: String,
    val port: Int,
    private val forwarding: AutoCloseable? = null,
) : AutoCloseable {
    val isForwarded: Boolean get() = forwarding != null

    override fun close() {
        forwarding?.close()
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"

        fun open(
            host: Host,
            owner: Window,
            isCancelled: () -> Boolean = { false },
            onProxyFailure: (IOException) -> Unit,
        ): RDPConnection {
            val jumpIds = host.options.jumpHosts
            val usesProxy = host.proxy.type != ProxyType.No
            require(!usesProxy || jumpIds.isEmpty()) {
                I18n.getString("termora.rdp.error.proxy-conflict")
            }
            if (usesProxy) {
                val relay = RDPProxyRelay(host, onProxyFailure)
                return RDPConnection(LOOPBACK, relay.port, relay)
            }
            if (jumpIds.isEmpty()) return RDPConnection(host.host, host.port)

            val hosts = HostManager.getInstance().hosts().associateBy { it.id }
            val jumps = jumpIds.map { id ->
                hosts[id]?.takeIf { it.protocol.equals(SSHProtocolProvider.PROTOCOL, ignoreCase = true) }
                    ?: throw IOException(I18n.getString("termora.rdp.error.jump-host-unavailable", id))
            }
            val (endpoint, forwarding) =
                SshClients.openJumpHostForwarding(jumps, SshdSocketAddress(host.host, host.port), owner, isCancelled)
            return RDPConnection(LOOPBACK, endpoint.port, forwarding)
        }
    }
}
