package app.termora.masterpassword

import app.termora.database.UnsafeSettingEntity
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnsafeSettingRepositoryTest {

    /**
     * 每次调用都创建一个独立的临时文件 sqlite DB。
     *
     * 不使用 `mode=memory&cache=shared`：sqlite 的 shared in-memory DB 按 JDBC URL 名隔离，
     * 但在 Exposed/HikariCP 连接管理与 JUnit 并行执行叠加下，建表事务与读写事务可能落到
     * 不同底层连接，导致 `no such table`。临时文件 DB 完全独立、跨连接稳定，且测试结束自动清理。
     */
    private fun newRepo(): UnsafeSettingRepository {
        val dbFile = Files.createTempFile("termora-usr-", ".sqlite").toFile()
        dbFile.deleteOnExit()
        val db = Database.connect(
            "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC",
            user = "sa"
        )
        transaction(db) { SchemaUtils.create(UnsafeSettingEntity) }
        return UnsafeSettingRepository(db)
    }

    @Test
    fun `put then get`() {
        val repo = newRepo()
        repo.put("__KEY", "value1")
        assertEquals("value1", repo.get("__KEY"))
    }

    @Test
    fun `put overwrites existing`() {
        val repo = newRepo()
        repo.put("__KEY", "a")
        repo.put("__KEY", "b")
        assertEquals("b", repo.get("__KEY"))
    }

    @Test
    fun `missing key returns null`() {
        assertNull(newRepo().get("__NOPE"))
    }

    @Test
    fun `remove deletes keys`() {
        val repo = newRepo()
        repo.put("__A", "1")
        repo.put("__B", "2")
        repo.remove("__A", "__B")
        assertNull(repo.get("__A"))
        assertNull(repo.get("__B"))
    }

    @Test
    fun `getAll returns snapshot`() {
        val repo = newRepo()
        repo.put("__X", "x")
        assertTrue(repo.getAll()["__X"] == "x")
    }
}
