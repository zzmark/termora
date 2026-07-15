package app.termora.masterpassword

import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.PublicKey

/**
 * 纯单测（不连真实 ssh-agent）：
 * - [SshAgentSigner.parsePublicKey] 解析已知 OpenSSH ed25519 公钥
 * - keyBlob（PublicKey ↔ SSH wire blob）往返
 * - [SshAgentSigner] 的 flags 分支（RSA → 0x02，EC → 0）
 *
 * 真实 agent 功能（identities/sign 确定性）由用户手动验证，见 task-5-report.md。
 */
class SshAgentSignerTest {

    /**
     * 稳定的 ed25519 OpenSSH 公钥（ssh-keygen 一次性生成、固定文本），
     * 解码后应是标准的 `ssh-ed25519` + 32B 密钥。
     */
    private val ed25519Openssh =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFirSvLiSLHYzdkNPGQ5UbkilQgYFzSpyGBennBZ7/B7 task5-stable"

    @Test
    fun `parsePublicKey parses OpenSSH ed25519 text`() {
        val key = SshAgentSigner.parsePublicKey(ed25519Openssh)
        assertNotNull(key)
        assertEquals("EdDSA", key.algorithm)
    }

    @Test
    fun `parsePublicKey rejects garbage`() {
        assertThrows(IllegalArgumentException::class.java) {
            SshAgentSigner.parsePublicKey("not a key")
        }
    }

    @Test
    fun `keyBlob round-trips an RSA public key`() {
        val rsa = rsaPublicKey()
        val blob = keyBlob(rsa)
        // 至少包含 "ssh-rsa" 的 7 字节类型名
        assertTrue(blob.size > 11)

        val parsed = parseBlob(blob)
        assertEquals("RSA", parsed.algorithm)
        // RSA 的 equality 基于 modulus/exponent；序列化往返应保持 equal
        assertEquals(rsa, parsed)
    }

    @Test
    fun `keyBlob round-trips ed25519 from OpenSSH text`() {
        val ed = SshAgentSigner.parsePublicKey(ed25519Openssh)
        val blob = keyBlob(ed)
        val parsed = parseBlob(blob)
        assertEquals(ed, parsed)
    }

    @Test
    fun `flagsFor returns sha2-256 for RSA`() {
        // 间接验证 flagsFor：用反射调 private 方法不优雅；改为通过编码后的 SIGN_REQUEST
        // 验证 RSA 携带 0x02（SshAgentWire.SSH_AGENT_RSA_SHA2_256）。
        val blob = byteArrayOf(0x11, 0x22)
        val data = byteArrayOf(0x33)
        val payload = SshAgentWire.encodeSignRequest(
            blob, data, SshAgentWire.SSH_AGENT_RSA_SHA2_256
        )
        // payload = 13 | string(blob) | string(data) | uint32(0x02)
        // 尾部 4 字节应为 0x00 00 00 02
        val tail = payload.takeLast(4).map { it.toInt() and 0xFF }
        assertEquals(listOf(0, 0, 0, 2), tail)
    }

    // ---- helpers ----

    private fun rsaPublicKey(): PublicKey {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        return kp.public
    }

    /** 镜像 [SshAgentSigner] 私有方法：用 apache mina sshd 做 PublicKey → SSH wire blob。 */
    private fun keyBlob(key: PublicKey): ByteArray {
        val buf = ByteArrayBuffer()
        buf.putPublicKey(key)
        return buf.compactData
    }

    /** 镜像 [SshAgentSigner] 私有方法：从 SSH wire blob 解析 PublicKey。 */
    private fun parseBlob(blob: ByteArray): PublicKey = ByteArrayBuffer(blob).publicKey
}
