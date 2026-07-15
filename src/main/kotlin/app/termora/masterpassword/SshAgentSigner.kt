package app.termora.masterpassword

import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.util.buffer.BufferUtils
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.eclipse.jgit.transport.sshd.agent.Connector
import org.eclipse.jgit.transport.sshd.agent.ConnectorFactory
import org.slf4j.LoggerFactory
import java.io.IOException
import java.security.PublicKey

/**
 * 通过本地 ssh-agent 列举公钥 / 用指定公钥对应的私钥对数据签名。
 *
 * 私钥永不离开 agent：本类只调用 agent 的 REQUEST_IDENTITIES 与 SIGN_REQUEST。
 *
 * 实现 note：
 * - 使用 jgit **public** 包 `org.eclipse.jgit.transport.sshd.agent.Connector`（`rpc(byte, byte[])`）。
 *   `Connector.rpc` 的契约（由 `AbstractConnector.prepareMessage` 定义）：
 *     request body = `[4-byte length placeholder][payload bytes]`，payload 不含命令字节；
 *     command byte 作为 `rpc` 的第一参数单独传递（`prepareMessage` 会把 body[4] 覆写为它）。
 * - `rpc` 的返回值 = response payload（**不含** length 前缀，**含** response command byte）。
 * - 因此本类在请求侧把 [app.termora.masterpassword.SshAgentWire] 产出的 payload（command byte + 字段）
 *   加 4 字节 length 占位前缀，并把 command byte 单独取出来传给 `rpc`；
 *   响应侧 [SshAgentWire] 的解码器直接消费 `rpc` 的返回值（其首字节即 response command byte）。
 *
 * 不依赖 `org.eclipse.jgit.internal.*`（internal 包，禁止）。
 */
class SshAgentSigner {

    /**
     * 列出 agent 中所有公钥（REQUEST_IDENTITIES）。
     * @throws IOException agent 不可用 / 通信失败。
     */
    fun identities(): List<PublicKey> {
        val connector = openConnector()
        return try {
            val (command, body) = frame(SshAgentWire.encodeRequestIdentities())
            val resp = connector.rpc(command, body)
                ?: throw IOException("SSH agent returned no response")
            SshAgentWire.decodeIdentitiesAnswer(resp).map { parseBlob(it) }
        } finally {
            connector.close()
        }
    }

    /**
     * 用 [publicKey] 对应的私钥对 [data] 签名。
     *
     * @return signature string 的原始内容（algo_name + raw signature），即 agent SIGN_RESPONSE 里
     *         那个 string 字段的字节。对 RSA 使用 sha2-256（避免 sha1）。
     * @throws IOException agent 不可用 / 无此 key / 拒绝签名。
     */
    fun sign(publicKeyText: String, data: ByteArray): ByteArray {
        // key blob 直接取自 OpenSSH 文本的 base64 段——这正是 agent 内部存储的 blob，
        // 避免 PublicKey → putPublicKey 往返可能产生的字节差异导致 agent 找不到 key。
        val keyBlob = extractKeyBlob(publicKeyText)
        val flags = flagsFor(extractAlgo(publicKeyText))
        val (command, body) = frame(
            SshAgentWire.encodeSignRequest(keyBlob, data, flags)
        )
        val connector = openConnector()
        return try {
            val resp = connector.rpc(command, body)
                ?: throw IOException("SSH agent returned no response")
            if (resp.isEmpty() || resp[0] != SshAgentWire.SSH_AGENT_SIGN_RESPONSE) {
                throw IOException(
                    "SSH agent refused to sign (resp=${resp.firstOrNull()?.toInt()?.and(0xff)}, " +
                        "blob=${java.util.Base64.getEncoder().encodeToString(keyBlob)}, " +
                        "no such key or unsupported algorithm)"
                )
            }
            SshAgentWire.decodeSignResponse(resp)
        } finally {
            connector.close()
        }
    }

    // ---- internals ----

    private fun openConnector(): Connector {
        val factory = ConnectorFactory.getDefault()
            ?: throw IOException("No SSH agent connector factory available")

        // 先尝试默认连接器
        val defaultDescriptor = factory.defaultConnector
        if (defaultDescriptor != null) {
            try {
                val connector = factory.create(defaultDescriptor.identityAgent, null)
                if (connector.connect()) return connector
                connector.close()
            } catch (_: Exception) {
                // 默认连接器不可用，继续尝试其他
            }
        }

        // 默认不可用，遍历所有支持的连接器逐个尝试
        // （例如 Windows 上 Pageant 不可用时回退到 \\.\pipe\openssh-ssh-agent）
        for (descriptor in factory.supportedConnectors) {
            if (descriptor == defaultDescriptor) continue // 已经试过
            try {
                val connector = factory.create(descriptor.identityAgent, null)
                if (connector.connect()) return connector
                connector.close()
            } catch (_: Exception) {
                // 该连接器不可用，继续
            }
        }

        throw IOException("SSH agent is not available (tried: ${factory.supportedConnectors.joinToString { it.displayName }})")
    }

    /**
     * 把 [app.termora.masterpassword.SshAgentWire] 产出的完整 payload（首字节为 command byte）
     * 包装成 `Connector.rpc` 期望的 body：`[4-byte length placeholder][payload]`，
     * 并单独返回 command byte。length 占位由 `AbstractConnector.prepareMessage` 覆写。
     */
    private fun frame(payload: ByteArray): RpcFrame {
        require(payload.isNotEmpty()) { "payload must contain command byte" }
        val command = payload[0]
        val body = ByteArray(4 + payload.size)
        // length placeholder（占位；prepareMessage 会用 body.size - 4 覆写）。写 0 仅为语义清晰。
        BufferUtils.putUInt(payload.size.toLong(), body, 0, 4)
        System.arraycopy(payload, 0, body, 4, payload.size)
        return RpcFrame(command, body)
    }

    private fun parseBlob(blob: ByteArray): PublicKey {
        // ByteArrayBuffer 从 SSH wire key blob 解析 PublicKey。
        return ByteArrayBuffer(blob).publicKey
    }

    private fun flagsFor(algo: String): Byte =
        when (algo) {
            // RSA 强制 sha2-256（避免 sha1）；Ed25519 / ECDSA 不需要 flag。
            "ssh-rsa" -> SshAgentWire.SSH_AGENT_RSA_SHA2_256
            else -> 0
        }

    /** rpc 调用所需的两元组：command byte + 已加 length 占位前缀的 body。仅作方法内临时载体。 */
    private data class RpcFrame(val command: Byte, val body: ByteArray) {
        // 保留 data class 以获得 component1/component2 解构能力（frame() 调用处使用）。
        // 不手写 equals/hashCode：data class 生成的版本会调用 ByteArray.equals，而
        // ByteArray.equals 即 Java 数组的引用相等（==），对这种临时载体足够，
        // 也就消除了「data 修饰符 + 手写 equals/hashCode」二者并存的矛盾。
    }

    companion object {
        private val log = LoggerFactory.getLogger(SshAgentSigner::class.java)

        /**
         * 工具：把 OpenSSH 文本公钥（如 `ssh-ed25519 AAAA... comment`）解析为 [PublicKey]。
         * 主要供测试 / 合法性校验使用；签名时优先用 [extractKeyBlob] 直接取 blob。
         */
        @Throws(IOException::class)
        fun parsePublicKey(opensshText: String): PublicKey {
            val entry = PublicKeyEntry.parsePublicKeyEntry(opensshText)
                ?: throw IllegalArgumentException("Invalid OpenSSH public key")
            return entry.resolvePublicKey(null, null, null)
        }

        /** 从 OpenSSH 文本提取 key blob：`algo <base64> comment` → base64 decode（= agent 内存的 blob）。 */
        fun extractKeyBlob(opensshText: String): ByteArray {
            val parts = opensshText.trim().split(Regex("\\s+"))
            require(parts.size >= 2) { "Invalid OpenSSH public key: $opensshText" }
            return java.util.Base64.getDecoder().decode(parts[1])
        }

        /** 从 OpenSSH 文本提取算法名（第一段）。 */
        fun extractAlgo(opensshText: String): String =
            opensshText.trim().split(Regex("\\s+"))[0]
    }
}
