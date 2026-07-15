package app.termora.masterpassword

import app.termora.AES.decodeBase64
import app.termora.AES.encodeBase64String
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MasterPasswordCryptoTest {

    private val secret = DbSecret("p4ssw0rdHex16", "saltHex12")

    @Test
    fun `main password encrypt then decrypt round-trips`() {
        val salt = MasterPasswordCrypto.newKdfSalt()
        val enc = MasterPasswordCrypto.encryptWithMainPassword("s3cret".toCharArray(), salt, secret)
        val back = MasterPasswordCrypto.decryptWithMainPassword("s3cret".toCharArray(), salt, enc)
        assertEquals(secret, back)
    }

    @Test
    fun `wrong main password returns null`() {
        val salt = MasterPasswordCrypto.newKdfSalt()
        val enc = MasterPasswordCrypto.encryptWithMainPassword("right".toCharArray(), salt, secret)
        assertNull(MasterPasswordCrypto.decryptWithMainPassword("wrong".toCharArray(), salt, enc))
    }

    @Test
    fun `different ciphertext each encryption due to random iv`() {
        val salt = MasterPasswordCrypto.newKdfSalt()
        val pwd = "pwd".toCharArray()
        val a = MasterPasswordCrypto.encryptWithMainPassword(pwd, salt, secret)
        val b = MasterPasswordCrypto.encryptWithMainPassword(pwd, salt, secret)
        assertNotEquals(a, b)                      // IV 不同 → 密文不同
        assertEquals(secret, MasterPasswordCrypto.decryptWithMainPassword(pwd, salt, a))
        assertEquals(secret, MasterPasswordCrypto.decryptWithMainPassword(pwd, salt, b))
    }

    @Test
    fun `tampered ciphertext fails to decrypt`() {
        val salt = MasterPasswordCrypto.newKdfSalt()
        val enc = MasterPasswordCrypto.encryptWithMainPassword("p".toCharArray(), salt, secret)
        val bytes = enc.decodeBase64()
        bytes[bytes.lastIndex] = (bytes.lastIndex.toByte().toInt() xor 0xFF).toByte()
        assertNull(MasterPasswordCrypto.decryptWithMainPassword("p".toCharArray(), salt, bytes.encodeBase64String()))
    }

    @Test
    fun `ssh key path round-trips and is deterministic`() {
        val key = MasterPasswordCrypto.deriveSshKey(byteArrayOf(1, 2, 3))
        val enc = MasterPasswordCrypto.encryptWithKey(key, secret)
        assertEquals(secret, MasterPasswordCrypto.decryptWithKey(key, enc))
        // 相同 signature 派生相同 key
        assertEquals(key.toList(), MasterPasswordCrypto.deriveSshKey(byteArrayOf(1, 2, 3)).toList())
        assertTrue(key.size == 32)
    }
}
