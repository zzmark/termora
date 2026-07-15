package app.termora.masterpassword

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * ssh-agent 协议（RFC 9987）payload 编解码。纯函数，无 I/O。
 *
 * 注意：length-frame（uint32 总长前缀）由 jgit Connector 处理；
 * 这里只编解码 payload（首字节为 message type，后接 SSH wire 字段）。
 *
 * SSH wire: uint32=4B 大端；string=uint32(len)+bytes。
 */
object SshAgentWire {
    const val SSH_AGENTC_REQUEST_IDENTITIES: Byte = 11
    const val SSH_AGENT_IDENTITIES_ANSWER: Byte = 12
    const val SSH_AGENTC_SIGN_REQUEST: Byte = 13
    const val SSH_AGENT_SIGN_RESPONSE: Byte = 14
    const val SSH_AGENT_RSA_SHA2_256: Byte = 0x02

    fun encodeRequestIdentities(): ByteArray = byteArrayOf(SSH_AGENTC_REQUEST_IDENTITIES)

    /** @return 每个身份的 public key blob 列表 */
    fun decodeIdentitiesAnswer(payload: ByteArray): List<ByteArray> {
        require(payload.isNotEmpty() && payload[0] == SSH_AGENT_IDENTITIES_ANSWER) {
            "not an identities answer"
        }
        val buf = ByteBuffer.wrap(payload, 1, payload.size - 1)
        val count = uint32(buf)
        val blobs = ArrayList<ByteArray>(count)
        repeat(count) {
            blobs.add(string(buf))   // key blob
            string(buf)              // comment（丢弃）
        }
        return blobs
    }

    fun encodeSignRequest(keyBlob: ByteArray, data: ByteArray, flags: Byte): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SSH_AGENTC_SIGN_REQUEST.toInt())
        writeString(out, keyBlob)
        writeString(out, data)
        writeUint32(out, flags.toInt())
        return out.toByteArray()
    }

    /** @return signature string 的原始内容（algo_name + raw signature） */
    fun decodeSignResponse(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty() && payload[0] == SSH_AGENT_SIGN_RESPONSE) {
            "not a sign response"
        }
        val buf = ByteBuffer.wrap(payload, 1, payload.size - 1)
        return string(buf)
    }

    // ---- SSH wire primitives ----

    private fun uint32(buf: ByteBuffer): Int = buf.int

    private fun string(buf: ByteBuffer): ByteArray {
        val len = buf.int
        require(len >= 0) { "negative length" }
        val dst = ByteArray(len)
        buf.get(dst)
        return dst
    }

    private fun writeUint32(out: ByteArrayOutputStream, v: Int) {
        out.write(v ushr 24)
        out.write(v ushr 16)
        out.write(v ushr 8)
        out.write(v)
    }

    private fun writeString(out: ByteArrayOutputStream, b: ByteArray) {
        writeUint32(out, b.size)
        out.write(b)
    }
}
