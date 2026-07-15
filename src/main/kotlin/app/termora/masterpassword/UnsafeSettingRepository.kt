package app.termora.masterpassword

import app.termora.database.UnsafeSettingEntity
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * 对明文表 tb_unsafe_setting 的轻量 KV 访问，供主密码机制读写密钥相关 key。
 */
class UnsafeSettingRepository(private val database: Database) {

    fun get(name: String): String? = getAll()[name]

    fun getAll(): Map<String, String> = transaction(database) {
        UnsafeSettingEntity.selectAll().associate { it[UnsafeSettingEntity.name] to it[UnsafeSettingEntity.value] }
    }

    /** upsert：先删同名再插入（id 由 clientDefault 随机生成）。 */
    fun put(name: String, value: String) {
        transaction(database) {
            UnsafeSettingEntity.deleteWhere { UnsafeSettingEntity.name eq name }
            UnsafeSettingEntity.insert {
                it[UnsafeSettingEntity.name] = name
                it[UnsafeSettingEntity.value] = value
            }
        }
    }

    fun remove(vararg names: String) {
        transaction(database) {
            for (n in names) {
                UnsafeSettingEntity.deleteWhere { UnsafeSettingEntity.name eq n }
            }
        }
    }
}
