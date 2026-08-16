package app.termora.plugin.internal.ssh

import app.termora.AuthenticationType
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshConnectionStatusTest {
    @Test
    fun `missing server version uses a safe fallback`() {
        val message = sshServerIdentificationMessage(null)

        assertTrue(message.isNotBlank())
        assertFalse(message.contains("null", ignoreCase = true))
    }

    @Test
    fun `authentication method identifies protocol and credential source`() {
        assertEquals("password", sshAuthenticationMethodName(AuthenticationType.Password))
        assertEquals("publickey", sshAuthenticationMethodName(AuthenticationType.PublicKey))
        assertEquals("publickey (ssh-agent)", sshAuthenticationMethodName(AuthenticationType.SSHAgent))
        assertEquals("keyboard-interactive", sshAuthenticationMethodName(AuthenticationType.KeyboardInteractive))
        assertEquals("none", sshAuthenticationMethodName(AuthenticationType.No))
    }

    @Test
    fun `same directional algorithms are displayed once`() {
        assertEquals(
            listOf("Cipher: aes256-gcm"),
            sshDirectionalAlgorithmDetails("Cipher", "aes256-gcm", "aes256-gcm"),
        )
    }

    @Test
    fun `different directional algorithms remain visible`() {
        assertEquals(
            listOf("Cipher (C2S): aes256-gcm", "Cipher (S2C): aes128-gcm"),
            sshDirectionalAlgorithmDetails("Cipher", "aes256-gcm", "aes128-gcm"),
        )
    }

    @Test
    fun `failure reason uses the most specific cause`() {
        val failure = IllegalStateException("outer", IOException("connection refused"))

        assertEquals("IOException: connection refused", sshFailureReason(failure))
    }
}
