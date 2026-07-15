package app.termora.masterpassword

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SshAgentWireTest {

    @Test
    fun `request identities payload is single command byte`() {
        // ByteArray 用 contentEquals 比较（== 比较的是引用）
        assertTrue(SshAgentWire.encodeRequestIdentities().contentEquals(byteArrayOf(11)))
    }

    @Test
    fun `decode identities answer parses keyblobs`() {
        // answer = 12 | uint32(2) | string(blobA) | string(blobB)
        val blobA = byteArrayOf(0xAA.toByte())
        val blobB = byteArrayOf(0xBB.toByte(), 0xCC.toByte())
        val payload = byteArrayOf(12) +
            uint32(2) +
            string(blobA) + string(byteArrayOf()) +   // blob + 空 comment
            string(blobB) + string(byteArrayOf())
        val blobs = SshAgentWire.decodeIdentitiesAnswer(payload)
        assertEquals(2, blobs.size)
        assertTrue(blobs[0].contentEquals(blobA))
        assertTrue(blobs[1].contentEquals(blobB))
    }

    @Test
    fun `decode identities answer rejects wrong command`() {
        assertFailsWith<IllegalArgumentException> {
            SshAgentWire.decodeIdentitiesAnswer(byteArrayOf(99))
        }
    }

    @Test
    fun `sign request encodes command keyblob data flags`() {
        val keyBlob = byteArrayOf(1, 2)
        val data = byteArrayOf(3, 4, 5)
        val payload = SshAgentWire.encodeSignRequest(keyBlob, data, SshAgentWire.SSH_AGENT_RSA_SHA2_256)
        // 13 | string(keyBlob) | string(data) | uint32(flags)
        val expected = byteArrayOf(13) +
            string(keyBlob) + string(data) + uint32(SshAgentWire.SSH_AGENT_RSA_SHA2_256.toInt())
        assertTrue(payload.contentEquals(expected))
    }

    @Test
    fun `decode sign response returns raw signature bytes`() {
        val sigBlob = byteArrayOf(9, 9, 9)
        val payload = byteArrayOf(14) + string(sigBlob)
        assertTrue(SshAgentWire.decodeSignResponse(payload).contentEquals(sigBlob))
    }

    // ---- helpers ----
    private fun uint32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )
    private fun string(b: ByteArray): ByteArray = uint32(b.size) + b
}
