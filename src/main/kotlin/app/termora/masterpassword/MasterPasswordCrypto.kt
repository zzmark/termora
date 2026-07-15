package app.termora.masterpassword

import app.termora.Application
import app.termora.AES
import app.termora.PBKDF2
import app.termora.AES.decodeBase64
import app.termora.AES.encodeBase64String
import java.security.MessageDigest

/**
 * 主密码相关的纯加解密函数。无 DB、无 UI 依赖，完全可单测。
 *
 * 存储格式：base64( iv[12] || AES-GCM-ciphertext )
 */
object MasterPasswordCrypto {

    private const val IV_SIZE = 12
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256

    /** 生成新的 PBKDF2 salt（base64，16 字节随机）。 */
    fun newKdfSalt(): String = AES.randomBytes(16).encodeBase64String()

    /** main-password → DbSecret 加密。 */
    fun encryptWithMainPassword(mainPassword: CharArray, kdfSalt: String, secret: DbSecret): String {
        val key = deriveMainKey(mainPassword, kdfSalt)
        return encryptWithKey(key, secret)
    }

    /** main-password 解密；GCM 校验失败（密码错）返回 null。 */
    fun decryptWithMainPassword(mainPassword: CharArray, kdfSalt: String, enc: String): DbSecret? {
        val key = deriveMainKey(mainPassword, kdfSalt)
        return decryptWithKey(key, enc)
    }

    /** ssh-key 签名 → AES key（SHA-256，确定性）。 */
    fun deriveSshKey(signature: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(signature)

    /** 通用 key → DbSecret 加密。 */
    fun encryptWithKey(key: ByteArray, secret: DbSecret): String {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        val iv = AES.randomBytes(IV_SIZE)
        val plaintext = Application.ohMyJson.encodeToString(DbSecret.serializer(), secret).toByteArray(Charsets.UTF_8)
        val ct = AES.GCM.encrypt(key, iv, plaintext)
        return (iv + ct).encodeBase64String()
    }

    /** 通用 key 解密；失败返回 null。 */
    fun decryptWithKey(key: ByteArray, enc: String): DbSecret? {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        return runCatching {
            val blob = enc.decodeBase64()
            val iv = blob.copyOfRange(0, IV_SIZE)
            val ct = blob.copyOfRange(IV_SIZE, blob.size)
            val plain = AES.GCM.decrypt(key, iv, ct)
            Application.ohMyJson.decodeFromString(DbSecret.serializer(), plain.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun deriveMainKey(mainPassword: CharArray, kdfSalt: String): ByteArray =
        PBKDF2.hash(kdfSalt.decodeBase64(), mainPassword, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
}
