package app.termora.masterpassword

import app.termora.database.UnsafeSettingEntity
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MasterPasswordServiceTest {

    private val secret = DbSecret("pwdHex16", "saltHex12")
    private lateinit var db: Database
    private lateinit var repo: UnsafeSettingRepository

    @AfterTest
    fun reset() {
        MasterPasswordService.resetForTest()
    }

    /**
     * 每个用例独立临时文件 sqlite（同 UnsafeSettingRepositoryTest 做法），
     * 避免共享 in-memory 在 JUnit 并行 + 连接池下 `no such table`。
     */
    private fun service(): MasterPasswordService {
        val dbFile = Files.createTempFile("termora-mps-", ".sqlite").toFile()
        dbFile.deleteOnExit()
        db = Database.connect(
            "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC",
            user = "sa"
        )
        transaction(db) { SchemaUtils.create(UnsafeSettingEntity) }
        repo = UnsafeSettingRepository(db)
        MasterPasswordService.useForTest(repo, secret)
        return MasterPasswordService
    }

    @Test
    fun `enable writes enc and removes plaintext`() {
        val s = service()
        // 模拟未启用前的明文
        repo.put("__DB_PASSWORD", secret.password)
        repo.put("__DB_SALT", secret.salt)

        s.enable("m4ster".toCharArray())

        assertTrue(s.isEnabled())
        assertNull(repo.get("__DB_PASSWORD"))
        assertNull(repo.get("__DB_SALT"))
        assertEquals(secret, s.verify("m4ster".toCharArray()))
    }

    @Test
    fun `disable restores plaintext and clears all`() {
        val s = service()
        repo.put("__DB_PASSWORD", secret.password); repo.put("__DB_SALT", secret.salt)
        s.enable("m".toCharArray())

        assertTrue(s.disable("m".toCharArray()))
        assertFalse(s.isEnabled())
        assertFalse(s.isSshKeyEnabled())
        assertEquals(secret.password, repo.get("__DB_PASSWORD"))
        assertEquals(secret.salt, repo.get("__DB_SALT"))
    }

    @Test
    fun `disable with wrong password fails`() {
        val s = service()
        repo.put("__DB_PASSWORD", secret.password); repo.put("__DB_SALT", secret.salt)
        s.enable("right".toCharArray())
        assertFalse(s.disable("wrong".toCharArray()))
        assertTrue(s.isEnabled())
    }

    @Test
    fun `load main encrypted secret round-trips`() {
        val s = service()
        repo.put("__DB_PASSWORD", secret.password); repo.put("__DB_SALT", secret.salt)
        s.enable("k".toCharArray())
        assertEquals(secret, s.loadMainPasswordEncryptedSecret("k".toCharArray()))
        assertNull(s.loadMainPasswordEncryptedSecret("nope".toCharArray()))
    }
}
