package app.termora.plugin.internal.ssh

import app.termora.Authentication
import app.termora.AuthenticationType
import app.termora.Host
import app.termora.Options
import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.common.kex.KexProposalOption
import kotlin.test.*

class SshAlgorithmsTest {
    @Test
    fun `default policy starts with MINA priority and keeps compatibility algorithms in warning region`() {
        val catalog = SshAlgorithms.catalog(SshAlgorithmCategory.KeyExchange)
        val policy = catalog.defaultPolicy()

        assertEquals(catalog.minaDefaults, policy.normal)
        assertEquals(catalog.compatibilityWarnings, policy.warn)
        assertEquals(catalog.available.toSet(), (policy.normal + policy.warn + policy.disabled).toSet())
    }

    @Test
    fun `new algorithms are added to disabled while missing algorithms are preserved`() {
        val policy = SshAlgorithmPolicy(
            normal = listOf("removed-algorithm"),
            warn = listOf("warning-algorithm"),
        )

        val normalized = policy.normalized(listOf("new-algorithm"))

        assertEquals(listOf("removed-algorithm"), normalized.normal)
        assertEquals(listOf("warning-algorithm"), normalized.warn)
        assertEquals(listOf("new-algorithm"), normalized.disabled)
    }

    @Test
    fun `legacy reordered list migrates to enabled custom policy`() {
        val catalog = SshAlgorithms.catalog(SshAlgorithmCategory.Cipher)
        val reordered = catalog.minaDefaults.reversed()
        val extras = mapOf(SshAlgorithmCategory.Cipher.legacyOrderKey to reordered.joinToString(","))

        assertTrue(SshAlgorithms.isCustomized(extras))
        val policy = SshAlgorithms.policy(SshAlgorithmCategory.Cipher, extras)
        assertEquals(reordered, policy.normal)
        assertTrue(policy.warn.isEmpty())
    }

    @Test
    fun `client applies only enabled algorithms in configured priority`() {
        val category = SshAlgorithmCategory.Cipher
        val catalog = SshAlgorithms.catalog(category)
        val first = catalog.available.last()
        val second = catalog.available.first()
        val policy = SshAlgorithmPolicy(
            normal = listOf(first),
            warn = listOf(second),
            disabled = catalog.available.filter { it != first && it != second },
        )
        val host = host(
            mapOf(
                SshAlgorithms.CUSTOMIZED_KEY to "true",
                category.policyKey to SshAlgorithms.encodePolicy(policy),
            ) + defaultPoliciesExcept(category)
        )

        val client = SshClients.openClient(host)
        try {
            assertEquals(listOf(first, second), client.cipherFactories.map { it.name })
        } finally {
            client.close()
        }
    }

    @Test
    fun `customization off delegates to MINA defaults`() {
        val client = SshClients.openClient(host(mapOf(SshAlgorithms.CUSTOMIZED_KEY to "false")))
        try {
            assertEquals(
                ClientBuilder.setUpDefaultCiphers(true).map { it.name },
                client.cipherFactories.map { it.name },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `negotiated warning is reported once for both directions`() {
        val category = SshAlgorithmCategory.Cipher
        val warned = SshAlgorithms.catalog(category).available.first()
        val extras = mapOf(
            SshAlgorithms.CUSTOMIZED_KEY to "true",
            category.policyKey to SshAlgorithms.encodePolicy(SshAlgorithmPolicy(warn = listOf(warned))),
        )
        val warnings = SshAlgorithms.negotiatedWarnings(
            extras,
            mapOf(
                KexProposalOption.C2SENC to warned,
                KexProposalOption.S2CENC to warned,
            ),
        )

        assertEquals(listOf(SshAlgorithmWarning(category, warned)), warnings)
    }

    @Test
    fun `invalid saved version falls back to automatic`() {
        assertEquals(SshVersion.Auto, SshAlgorithms.version(mapOf(SshAlgorithms.VERSION_KEY to "future-version")))
    }

    private fun defaultPoliciesExcept(excluded: SshAlgorithmCategory): Map<String, String> {
        return SshAlgorithmCategory.entries.filter { it != excluded }.associate { category ->
            category.policyKey to SshAlgorithms.encodePolicy(SshAlgorithms.catalog(category).defaultPolicy())
        }
    }

    private fun host(extras: Map<String, String>): Host {
        return Host(
            name = "algorithm-test",
            protocol = "SSH",
            host = "localhost",
            port = 22,
            username = "test",
            authentication = Authentication.No.copy(type = AuthenticationType.Password),
            options = Options.Default.copy(extras = extras),
        )
    }
}
