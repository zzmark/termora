package app.termora.plugin.internal.ssh

import app.termora.*
import app.termora.actions.DataProviders
import app.termora.actions.TabReconnectAction
import app.termora.addons.zmodem.ZModemPtyConnectorAdaptor
import app.termora.database.DatabaseManager
import app.termora.keymap.KeyShortcut
import app.termora.keymap.KeymapManager
import app.termora.plugin.internal.telnet.TelnetHostOptionsPane
import app.termora.terminal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import org.apache.commons.io.Charsets
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.future.CloseFuture
import org.apache.sshd.common.future.SshFutureListener
import org.apache.sshd.common.kex.KexProposalOption
import org.slf4j.LoggerFactory
import java.awt.event.KeyEvent
import java.nio.charset.StandardCharsets
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities

internal fun sshServerIdentificationMessage(serverVersion: String?): String {
    return if (serverVersion.isNullOrBlank()) {
        I18n.getString("termora.ssh.connection.server-identified")
    } else {
        I18n.getString("termora.ssh.connection.server-version", serverVersion)
    }
}

internal fun sshAuthenticationMethodName(type: AuthenticationType): String = when (type) {
    AuthenticationType.No -> "none"
    AuthenticationType.Password -> "password"
    AuthenticationType.PublicKey -> "publickey"
    AuthenticationType.SSHAgent -> "publickey (ssh-agent)"
    AuthenticationType.KeyboardInteractive -> "keyboard-interactive"
}

internal fun sshFailureReason(error: Throwable): String {
    val rootCause = generateSequence(error) { it.cause }.last()
    return "${rootCause.javaClass.simpleName}: ${rootCause.message ?: rootCause.toString()}"
}

internal fun sshDirectionalAlgorithmDetails(label: String, c2s: String?, s2c: String?): List<String> {
    val clientToServer = c2s.orEmpty()
    val serverToClient = s2c.orEmpty()
    return if (clientToServer == serverToClient) {
        listOf("$label: $clientToServer")
    } else {
        listOf(
            "$label (C2S): $clientToServer",
            "$label (S2C): $serverToClient",
        )
    }
}

class SSHTerminalTab(
    windowScope: WindowScope, host: Host,
    private val handler: SshHandler = SshHandler()
) : PtyHostTerminalTab(windowScope, host) {

    companion object {
        val SSHSession = DataKey(ClientSession::class)
        internal val MySshHandler = DataKey(SshHandler::class)
        private val log = LoggerFactory.getLogger(SSHTerminalTab::class.java)
    }

    private val mutex = Mutex()
    private val owner get() = SwingUtilities.getWindowAncestor(terminalPanel)
    private val tab get() = this
    private val debug get() = DatabaseManager.getInstance().terminal.debug

    init {
        terminalPanel.dropFiles = false
        terminalPanel.dataProviderSupport.addData(DataProviders.TerminalTab, this)
    }

    override fun getJComponent(): JComponent {
        return terminalPanel
    }

    override fun canReconnect(): Boolean {
        return mutex.isLocked.not()
    }

    override fun createReconnectTerminalTab(): TerminalTab {
        return SSHTerminalTab(windowScope, host)
    }

    override suspend fun openPtyConnector(): PtyConnector {
        if (mutex.tryLock()) {
            try {
                return doOpenPtyConnector()
            } finally {
                mutex.unlock()
            }
        }
        throw IllegalStateException("Opening PtyConnector")
    }


    private suspend fun doOpenPtyConnector(): PtyConnector {

        // 连接提示
        withContext(Dispatchers.Swing) {
            // clear screen
            terminal.clearScreen()
            // hide cursor
            terminalModel.setData(DataKey.Companion.ShowCursor, false)
            terminal.write("${I18n.getString("termora.ssh.connection.connecting")}\r\n")
        }

        val channel: ChannelShell
        writeConnectionStatus(I18n.getString("termora.ssh.connection.preparing-client"))
        val client = openClient()
        val session = openSession(client)
        writeConnectionStatus(I18n.getString("termora.ssh.connection.opening-channel"))
        channel = openChannel(session)
        // 打开隧道
        if (host.tunnelings.isNotEmpty()) {
            writeConnectionStatus(I18n.getString("termora.ssh.connection.opening-forwarding"))
        }
        openTunnelings(session, host)

        // 隐藏提示
        withContext(Dispatchers.Swing) {
            if (debug) {
                terminal.write("[SSH] ${I18n.getString("termora.ssh.connection.connected")}\r\n\r\n")
            } else {
                terminal.clearScreen()
            }
            // show cursor
            terminalModel.setData(DataKey.ShowCursor, true)

            val encoder = terminal.getKeyEncoder()
            if (encoder is KeyEncoderImpl) {
                val backspace = host.options.extras["backspace"]
                if (backspace == TelnetHostOptionsPane.Backspace.Backspace.name) {
                    encoder.putCode(TerminalKeyEvent(keyCode = KeyEvent.VK_BACK_SPACE), String(byteArrayOf(0x08)))
                } else if (backspace == TelnetHostOptionsPane.Backspace.VT220.name) {
                    encoder.putCode(TerminalKeyEvent(keyCode = KeyEvent.VK_BACK_SPACE), "${ControlCharacters.ESC}[3~")
                }
            }

        }

        return ptyConnectorFactory.decorate(
            ZModemPtyConnectorAdaptor(
                terminal,
                terminalPanel,
                ChannelShellPtyConnector(
                    channel,
                    charset = Charsets.toCharset(host.options.encoding, StandardCharsets.UTF_8)
                )
            )
        )
    }

    private suspend fun openTunnelings(session: ClientSession, host: Host) {
        if (host.tunnelings.isEmpty()) {
            return
        }

        for (tunneling in host.tunnelings) {
            try {
                SshClients.openTunneling(session, host, tunneling)
                withContext(Dispatchers.Swing) {
                    terminal.write("Start [${tunneling.name}] port forwarding successfully.\r\n")
                }
            } catch (e: Exception) {
                if (log.isErrorEnabled) {
                    log.error("Start [${tunneling.name}] port forwarding failed: {}", e.message, e)
                }
                withContext(Dispatchers.Swing) {
                    terminal.write("Start [${tunneling.name}] port forwarding failed: ${e.message}\r\n")
                }
            }

        }
    }

    private fun openClient(): SshClient {
        val client = handler.client
        if (client != null) return client
        return SshClients.openClient(host, owner).also { handler.client = it }
    }

    private fun openSession(client: SshClient): ClientSession {
        val session = handler.session
        if (session != null) return SshSessionPool.register(session, client)
        return SshClients.openSession(host, client, ::onConnectionProgress)
            .also { handler.session = SshSessionPool.register(it, client) }
    }

    private fun onConnectionProgress(progress: SshClients.ConnectionProgress) {
        val currentHost = progress.host
        when (progress.stage) {
            SshClients.ConnectionStage.Connecting -> writeConnectionStatus(
                I18n.getString(
                    "termora.ssh.connection.connecting-address",
                    currentHost.name,
                    currentHost.host,
                    currentHost.port,
                )
            )

            SshClients.ConnectionStage.TransportConnected -> writeConnectionStatus(
                I18n.getString("termora.ssh.connection.transport-connected")
            )

            SshClients.ConnectionStage.ServerIdentified -> {
                val session = progress.session ?: return
                writeConnectionStatus(sshServerIdentificationMessage(session.serverVersion))
            }

            SshClients.ConnectionStage.ClientAlgorithmsOffered -> writeAlgorithmGroup(
                "termora.ssh.connection.client-offered-algorithms",
                progress.algorithms,
            )

            SshClients.ConnectionStage.ServerAlgorithmsOffered -> writeAlgorithmGroup(
                "termora.ssh.connection.server-offered-algorithms",
                progress.algorithms,
            )

            SshClients.ConnectionStage.AlgorithmsNegotiated -> writeAlgorithmGroup(
                "termora.ssh.connection.negotiated-algorithms",
                progress.algorithms,
            )

            SshClients.ConnectionStage.KeyExchangeFailed -> {
                writeConnectionStatus(I18n.getString("termora.ssh.connection.kex-failed"))
                val reason = progress.error?.let(::sshFailureReason)
                    ?: I18n.getString("termora.ssh.connection.kex-failed")
                writeConnectionStatus(
                    I18n.getString("termora.ssh.connection.kex-failure-reason", reason),
                )
                if (log.isWarnEnabled) {
                    log.warn("SSH connection [{}] key exchange failed: {}", host.name, reason, progress.error)
                }
            }

            SshClients.ConnectionStage.AlgorithmWarning -> writeConnectionStatus(
                I18n.getString("termora.ssh.connection.algorithm-warning")
            )

            SshClients.ConnectionStage.Authenticating -> {
                writeConnectionStatus(
                    I18n.getString(
                        "termora.ssh.connection.authentication-method",
                        sshAuthenticationMethodName(currentHost.authentication.type),
                    ),
                    debugOnly = true,
                )
                writeConnectionStatus(
                    I18n.getString("termora.ssh.connection.authenticating", currentHost.username)
                )
            }

            SshClients.ConnectionStage.Authenticated -> writeConnectionStatus(
                I18n.getString("termora.ssh.connection.authenticated")
            )
        }
    }

    private fun writeAlgorithmGroup(titleKey: String, algorithms: Map<KexProposalOption, String>?) {
        writeConnectionStatus(
            I18n.getString(titleKey),
            debugOnly = true,
        )
        writeAlgorithmDetails(algorithms.orEmpty())
    }

    private fun writeAlgorithmDetails(algorithms: Map<KexProposalOption, String>) {
        val details = mutableListOf(
            "${I18n.getString("termora.new-host.ssh.key-exchange")}: ${algorithms[KexProposalOption.ALGORITHMS].orEmpty()}",
            "${I18n.getString("termora.new-host.ssh.host-key")}: ${algorithms[KexProposalOption.SERVERKEYS].orEmpty()}",
        )
        details += sshDirectionalAlgorithmDetails(
            I18n.getString("termora.new-host.ssh.cipher"),
            algorithms[KexProposalOption.C2SENC],
            algorithms[KexProposalOption.S2CENC],
        )
        details += sshDirectionalAlgorithmDetails(
            I18n.getString("termora.new-host.ssh.mac"),
            algorithms[KexProposalOption.C2SMAC],
            algorithms[KexProposalOption.S2CMAC],
        )
        details += sshDirectionalAlgorithmDetails(
            I18n.getString("termora.new-host.ssh.compression"),
            algorithms[KexProposalOption.C2SCOMP],
            algorithms[KexProposalOption.S2CCOMP],
        )
        details.forEach { writeConnectionStatus(it, debugOnly = true) }
    }

    private fun writeConnectionStatus(message: String, debugOnly: Boolean = false) {
        if (debugOnly && !debug) {
            return
        }

        val write = { terminal.write("[SSH] $message\r\n") }
        if (SwingUtilities.isEventDispatchThread()) {
            write()
        } else {
            SwingUtilities.invokeAndWait(write)
        }

        if (debug && log.isInfoEnabled) {
            log.info("SSH connection [{}]: {}", host.name, message)
        }
    }

    private fun openChannel(session: ClientSession): ChannelShell {
        val channel = SshClients.openShell(host, terminalPanel.winSize(), session)
        handler.channel = channel

        channel.addCloseFutureListener(object : SshFutureListener<CloseFuture> {
            private val reconnectShortcut
                get() = KeymapManager.Companion.getInstance().getActiveKeymap()
                    .getShortcut(TabReconnectAction.Companion.RECONNECT_TAB).firstOrNull()
            private val autoCloseTabWhenDisconnected get() = DatabaseManager.getInstance().terminal.autoCloseTabWhenDisconnected

            override fun operationComplete(future: CloseFuture) {
                coroutineScope.launch(Dispatchers.Swing) {
                    terminal.write("\r\n\r\n${ControlCharacters.Companion.ESC}[31m")
                    terminal.write(I18n.getString("termora.terminal.channel-disconnected"))
                    if (reconnectShortcut is KeyShortcut) {
                        terminal.write(
                            I18n.getString(
                                "termora.terminal.channel-reconnect",
                                reconnectShortcut.toString()
                            )
                        )
                    }
                    terminal.write("\r\n")
                    terminal.write("${ControlCharacters.Companion.ESC}[0m")
                    terminalModel.setData(DataKey.Companion.ShowCursor, false)

                    if (autoCloseTabWhenDisconnected) {
                        terminalTabbedManager?.let { manager ->
                            SwingUtilities.invokeLater {
                                manager.closeTerminalTab(tab, true)
                            }
                        }
                    }

                    // stop
                    stop()
                }
            }
        })

        return channel
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getData(dataKey: DataKey<T>): T? {
        if (dataKey == SSHSession) {
            return handler.session as T?
        }
        if (dataKey == MySshHandler) {
            return handler as T?
        }
        return super.getData(dataKey)
    }

    override fun stop() {
        if (mutex.tryLock()) {
            try {
                super.stop()
                handler.close()
            } finally {
                mutex.unlock()
            }
        }
    }

    override fun getIcon(): Icon {
        return Icons.terminal
    }

    override fun beforeClose() {
        // 保存窗口状态
        terminalPanel.storeVisualWindows(host.id)
    }

}
