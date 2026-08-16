package app.termora.plugin.internal.ssh

import app.termora.Application
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.client.kex.DHGClient
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.cipher.Cipher
import org.apache.sshd.common.compression.BuiltinCompressions
import org.apache.sshd.common.compression.Compression
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.KexProposalOption
import org.apache.sshd.common.kex.KeyExchangeFactory
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.mac.Mac
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.signature.Signature

internal enum class SshVersion {
    Auto,
    V1,
    V2,
}

internal enum class SshAlgorithmCategory(
    val policyKey: String,
    val legacyOrderKey: String,
) {
    KeyExchange("sshKeyExchangePolicy", "sshKeyExchangeAlgorithms"),
    HostKey("sshHostKeyPolicy", "sshHostKeyAlgorithms"),
    Cipher("sshCipherPolicy", "sshCipherAlgorithms"),
    Mac("sshMacPolicy", "sshMacAlgorithms"),
    Compression("sshCompressionPolicy", "sshCompressionAlgorithms"),
}

@Serializable
internal data class SshAlgorithmPolicy(
    val version: Int = 1,
    val normal: List<String> = emptyList(),
    val warn: List<String> = emptyList(),
    val disabled: List<String> = emptyList(),
) {
    fun enabled(): List<String> = normal + warn

    fun normalized(available: List<String>): SshAlgorithmPolicy {
        val seen = mutableSetOf<String>()
        fun unique(values: List<String>): List<String> {
            return values.map(String::trim).filter(String::isNotEmpty).filter(seen::add)
        }

        val normalizedNormal = unique(normal)
        val normalizedWarn = unique(warn)
        val normalizedDisabled = unique(disabled).toMutableList()
        normalizedDisabled.addAll(available.filter(seen::add))
        return copy(
            version = 1,
            normal = normalizedNormal,
            warn = normalizedWarn,
            disabled = normalizedDisabled,
        )
    }
}

internal data class SshAlgorithmCatalog(
    val category: SshAlgorithmCategory,
    val available: List<String>,
    val minaDefaults: List<String>,
    val compatibilityWarnings: List<String>,
) {
    fun defaultPolicy(): SshAlgorithmPolicy {
        val normal = minaDefaults.filter { it in available }
        val warn = compatibilityWarnings.filter { it in available && it !in normal }
        val disabled = available.filter { it !in normal && it !in warn }
        return SshAlgorithmPolicy(normal = normal, warn = warn, disabled = disabled)
    }
}

internal data class SshAlgorithmWarning(
    val category: SshAlgorithmCategory,
    val algorithm: String,
)

internal object SshAlgorithms {
    const val VERSION_KEY = "sshVersion"
    const val CUSTOMIZED_KEY = "sshAlgorithmsCustomized"

    private val catalogs by lazy {
        SshAlgorithmCategory.entries.associateWith(::createCatalog)
    }

    fun version(extras: Map<String, String>): SshVersion {
        return runCatching { SshVersion.valueOf(extras[VERSION_KEY] ?: SshVersion.Auto.name) }
            .getOrDefault(SshVersion.Auto)
    }

    fun isCustomized(extras: Map<String, String>): Boolean {
        extras[CUSTOMIZED_KEY]?.toBooleanStrictOrNull()?.let { return it }

        val savedOrders = SshAlgorithmCategory.entries.mapNotNull { category ->
            extras[category.legacyOrderKey]?.let { category to parseOrder(it) }
        }
        if (savedOrders.isEmpty()) {
            return false
        }

        return savedOrders.any { (category, saved) -> saved != legacyEffectiveOrder(category) }
    }

    fun catalog(category: SshAlgorithmCategory): SshAlgorithmCatalog = catalogs.getValue(category)

    fun policy(category: SshAlgorithmCategory, extras: Map<String, String>): SshAlgorithmPolicy {
        val catalog = catalog(category)
        val encoded = extras[category.policyKey]
        if (!encoded.isNullOrBlank()) {
            val decoded = runCatching {
                Application.ohMyJson.decodeFromString<SshAlgorithmPolicy>(encoded)
            }.getOrNull()
            if (decoded != null) {
                return decoded.normalized(catalog.available)
            }
        }

        if (isCustomized(extras)) {
            val legacyOrder = extras[category.legacyOrderKey]?.let(::parseOrder)
            if (legacyOrder != null) {
                return SshAlgorithmPolicy(
                    normal = legacyOrder,
                    disabled = catalog.available.filter { it !in legacyOrder },
                ).normalized(catalog.available)
            }
        }

        return catalog.defaultPolicy()
    }

    fun encodePolicy(policy: SshAlgorithmPolicy): String {
        return Application.ohMyJson.encodeToString(policy)
    }

    fun hasAvailableEnabledAlgorithms(
        category: SshAlgorithmCategory,
        policy: SshAlgorithmPolicy,
    ): Boolean {
        val available = catalog(category).available.toSet()
        return policy.enabled().any { it in available }
    }

    fun keyExchangeFactories(extras: Map<String, String>): List<KeyExchangeFactory> {
        return orderedFactories(
            allKeyExchangeFactories(),
            policy(SshAlgorithmCategory.KeyExchange, extras).enabled(),
        )
    }

    fun hostKeyFactories(extras: Map<String, String>): List<NamedFactory<Signature>> {
        return orderedFactories(
            allHostKeyFactories(),
            policy(SshAlgorithmCategory.HostKey, extras).enabled(),
        )
    }

    fun cipherFactories(extras: Map<String, String>): List<NamedFactory<Cipher>> {
        return orderedFactories(
            allCipherFactories(),
            policy(SshAlgorithmCategory.Cipher, extras).enabled(),
        )
    }

    fun macFactories(extras: Map<String, String>): List<NamedFactory<Mac>> {
        return orderedFactories(
            allMacFactories(),
            policy(SshAlgorithmCategory.Mac, extras).enabled(),
        )
    }

    fun compressionFactories(extras: Map<String, String>): List<NamedFactory<Compression>> {
        return orderedFactories(
            allCompressionFactories(),
            policy(SshAlgorithmCategory.Compression, extras).enabled(),
        )
    }

    fun negotiatedWarnings(
        extras: Map<String, String>,
        negotiated: Map<KexProposalOption, String>,
    ): List<SshAlgorithmWarning> {
        if (!isCustomized(extras)) {
            return emptyList()
        }

        val proposals = mapOf(
            SshAlgorithmCategory.KeyExchange to listOf(KexProposalOption.ALGORITHMS),
            SshAlgorithmCategory.HostKey to listOf(KexProposalOption.SERVERKEYS),
            SshAlgorithmCategory.Cipher to listOf(KexProposalOption.C2SENC, KexProposalOption.S2CENC),
            SshAlgorithmCategory.Mac to listOf(KexProposalOption.C2SMAC, KexProposalOption.S2CMAC),
            SshAlgorithmCategory.Compression to listOf(KexProposalOption.C2SCOMP, KexProposalOption.S2CCOMP),
        )

        return proposals.flatMap { (category, options) ->
            val warnings = policy(category, extras).warn.toSet()
            options.mapNotNull { option ->
                negotiated[option]?.takeIf { it in warnings }?.let { SshAlgorithmWarning(category, it) }
            }
        }.distinct()
    }

    fun allowsDhGroup1(extras: Map<String, String>): Boolean {
        if (!isCustomized(extras)) {
            return false
        }
        return BuiltinDHFactories.Constants.DIFFIE_HELLMAN_GROUP1_SHA1 in
                policy(SshAlgorithmCategory.KeyExchange, extras).enabled()
    }

    private fun createCatalog(category: SshAlgorithmCategory): SshAlgorithmCatalog {
        return when (category) {
            SshAlgorithmCategory.KeyExchange -> {
                val defaults = ClientBuilder.setUpDefaultKeyExchanges(true).map { it.name }
                val compatibility = legacyKeyExchangeFactories().map { it.name }
                val available = mergeNames(defaults, allKeyExchangeFactories().map { it.name })
                SshAlgorithmCatalog(category, available, defaults, compatibility)
            }

            SshAlgorithmCategory.HostKey -> {
                val defaults = ClientBuilder.setUpDefaultSignatureFactories(true).map { it.name }
                val available = mergeNames(defaults, allHostKeyFactories().map { it.name })
                val compatibility = available.filter { it !in defaults }
                SshAlgorithmCatalog(category, available, defaults, compatibility)
            }

            SshAlgorithmCategory.Cipher -> {
                val defaults = ClientBuilder.setUpDefaultCiphers(true).map { it.name }
                val available = mergeNames(defaults, allCipherFactories().map { it.name })
                SshAlgorithmCatalog(category, available, defaults, emptyList())
            }

            SshAlgorithmCategory.Mac -> {
                val defaults = ClientBuilder.setUpDefaultMacs(true).map { it.name }
                val available = mergeNames(defaults, allMacFactories().map { it.name })
                SshAlgorithmCatalog(category, available, defaults, emptyList())
            }

            SshAlgorithmCategory.Compression -> {
                val defaults = ClientBuilder.setUpDefaultCompressionFactories(true).map { it.name }
                val compatibility = listOf(BuiltinCompressions.zlib.name, BuiltinCompressions.delayedZlib.name)
                val available = mergeNames(defaults, allCompressionFactories().map { it.name })
                SshAlgorithmCatalog(category, available, defaults, compatibility)
            }
        }
    }

    private fun legacyEffectiveOrder(category: SshAlgorithmCategory): List<String> {
        val catalog = catalog(category)
        return mergeNames(catalog.minaDefaults, catalog.compatibilityWarnings)
    }

    private fun allKeyExchangeFactories(): List<KeyExchangeFactory> {
        val factories = BuiltinDHFactories.entries.filter { it.isSupported }.map(ClientBuilder.DH2KEX::apply)
        return mergeFactories(ClientBuilder.setUpDefaultKeyExchanges(true), factories)
    }

    @Suppress("DEPRECATION")
    private fun legacyKeyExchangeFactories(): List<KeyExchangeFactory> {
        val available = allKeyExchangeFactories().map { it.name }.toSet()
        return listOf(
            DHGClient.newFactory(BuiltinDHFactories.dhg1),
            DHGClient.newFactory(BuiltinDHFactories.dhg14),
            DHGClient.newFactory(BuiltinDHFactories.dhgex),
        ).filter { it.name in available }
    }

    private fun allHostKeyFactories(): List<NamedFactory<Signature>> {
        return mergeFactories(
            ClientBuilder.setUpDefaultSignatureFactories(true),
            BuiltinSignatures.entries.filter { it.isSupported },
        )
    }

    private fun allCipherFactories(): List<NamedFactory<Cipher>> {
        val available = BuiltinCiphers.entries.filter { it.isSupported && it != BuiltinCiphers.none }
        return mergeFactories(ClientBuilder.setUpDefaultCiphers(true), available)
    }

    private fun allMacFactories(): List<NamedFactory<Mac>> {
        return mergeFactories(
            ClientBuilder.setUpDefaultMacs(true),
            BuiltinMacs.entries.filter { it.isSupported },
        )
    }

    private fun allCompressionFactories(): List<NamedFactory<Compression>> {
        return mergeFactories(
            ClientBuilder.setUpDefaultCompressionFactories(true),
            BuiltinCompressions.entries.filter { it.isSupported },
        )
    }

    private fun parseOrder(order: String): List<String> {
        return order.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    }

    private fun mergeNames(first: List<String>, second: List<String>): List<String> {
        return (first + second).distinct()
    }

    private fun <T : NamedResource> mergeFactories(first: List<T>, second: List<T>): List<T> {
        return (first + second).distinctBy { it.name }
    }

    private fun <T : NamedResource> orderedFactories(factories: List<T>, order: List<String>): List<T> {
        val byName = factories.associateBy { it.name }
        return order.mapNotNull(byName::get).distinctBy { it.name }
    }
}
