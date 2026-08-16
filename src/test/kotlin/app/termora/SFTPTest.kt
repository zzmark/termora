package app.termora

import app.termora.plugin.internal.ssh.SshAlgorithmCategory
import app.termora.plugin.internal.ssh.SshAlgorithmPolicy
import app.termora.plugin.internal.ssh.SshAlgorithms
import app.termora.plugin.internal.ssh.SshClients
import org.apache.sshd.common.SshException
import org.apache.sshd.sftp.client.impl.DefaultSftpClientFactory
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SFTPTest : SSHDTest() {


    @Test
    fun test() {
        val stages = Collections.synchronizedList(mutableListOf<SshClients.ConnectionStage>())
        val session = newClientSession { stages.add(it.stage) }
        assertTrue(session.isOpen)
        assertEquals(
            listOf(
                SshClients.ConnectionStage.Connecting,
                SshClients.ConnectionStage.TransportConnected,
                SshClients.ConnectionStage.ClientAlgorithmsOffered,
                SshClients.ConnectionStage.ServerIdentified,
                SshClients.ConnectionStage.ServerAlgorithmsOffered,
                SshClients.ConnectionStage.AlgorithmsNegotiated,
                SshClients.ConnectionStage.Authenticating,
                SshClients.ConnectionStage.Authenticated,
            ),
            stages,
        )

        val fileSystem = DefaultSftpClientFactory.INSTANCE.createSftpFileSystem(session)
        for (path in Files.list(fileSystem.rootDirectories.first())) {
            println(path)
        }

        val cipherCatalog = SshAlgorithms.catalog(SshAlgorithmCategory.Cipher)
        val incompatibleHost = host.copy(
            options = host.options.copy(
                extras = mapOf(
                    SshAlgorithms.CUSTOMIZED_KEY to "true",
                    SshAlgorithmCategory.Cipher.policyKey to SshAlgorithms.encodePolicy(
                        SshAlgorithmPolicy(
                            normal = listOf("3des-cbc"),
                            disabled = cipherCatalog.available.filter { it != "3des-cbc" },
                        )
                    ),
                )
            )
        )
        val failureProgress = Collections.synchronizedList(mutableListOf<SshClients.ConnectionProgress>())
        val incompatibleClient = SshClients.openClient(incompatibleHost)
        try {
            assertFailsWith<SshException> {
                SshClients.openSession(incompatibleHost, incompatibleClient) { failureProgress.add(it) }
            }
        } finally {
            incompatibleClient.close()
        }
        assertEquals(
            1,
            failureProgress.count { it.stage == SshClients.ConnectionStage.ClientAlgorithmsOffered },
        )
        assertEquals(
            1,
            failureProgress.count { it.stage == SshClients.ConnectionStage.ServerAlgorithmsOffered },
        )
        assertEquals(
            1,
            failureProgress.count { it.stage == SshClients.ConnectionStage.KeyExchangeFailed },
        )
        assertNotNull(
            failureProgress.single { it.stage == SshClients.ConnectionStage.KeyExchangeFailed }.error
        )
    }
}
