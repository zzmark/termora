package app.termora.masterpassword

import app.termora.Application
import kotlin.test.Test
import kotlin.test.assertEquals

class DbSecretTest {
    @Test
    fun `serialize round-trip preserves fields`() {
        val s = DbSecret(password = "abc123", salt = "salt!")
        val json = Application.ohMyJson.encodeToString(DbSecret.serializer(), s)
        val back = Application.ohMyJson.decodeFromString(DbSecret.serializer(), json)
        assertEquals(s, back)
    }

    @Test
    fun `json contains both password and salt`() {
        val json = Application.ohMyJson.encodeToString(DbSecret.serializer(), DbSecret("p", "s"))
        assert(json.contains("\"password\":\"p\""))
        assert(json.contains("\"salt\":\"s\""))
    }
}
