package app.termora.masterpassword

import app.termora.AES.decodeBase64
import app.termora.database.DatabaseManager
import app.termora.database.DatabaseSecret
import org.slf4j.LoggerFactory

/**
 * 主密码机制的编排层：组合 DB 读写（[UnsafeSettingRepository]）、
 * 纯加解密（[MasterPasswordCrypto]）、ssh-agent 签名（[SshAgentSigner]）与
 * 内存中的当前 [DbSecret]（[DatabaseSecret]）。
 *
 * 该 object 只负责「把密文写进/读出 DB」与「在内存 DbSecret 与密文之间转换」，
 * 不直接持有 UI 状态；启动解锁流程在 Task 7（[DatabaseSecret] init）中调用
 * [loadMainPasswordEncryptedSecret] / [loadSshEncryptedSecret]。
 *
 * 所有存储 key 集中定义在本 object 的常量中，避免散落字符串。
 */
object MasterPasswordService {

    private val log = LoggerFactory.getLogger(MasterPasswordService::class.java)

    // ---- storage key 常量（集中定义）----
    const val K_PLAIN_PASSWORD = "__DB_PASSWORD"
    const val K_PLAIN_SALT = "__DB_SALT"
    const val K_KDF_SALT = "__DB_KDF_SALT"
    const val K_PASSWORD_ENC = "__DB_PASSWORD_ENC"
    const val K_SSH_AES_ENC = "__DB_SSH_AES_ENC"
    const val K_SSH_PUBLICKEY = "__DB_SSH_PUBLICKEY"
    const val K_SSH_CHALLENGE = "__DB_SSH_CHALLENGE"

    // 可注入点（默认指向生产单例；测试可替换）
    private var repoProvider: () -> UnsafeSettingRepository = {
        UnsafeSettingRepository(DatabaseManager.getInstance().database)
    }
    private var secretProvider: () -> DbSecret = {
        val ds = DatabaseSecret.getInstance()
        DbSecret(ds.password, ds.salt)
    }
    private val repo get() = repoProvider()

    // ---- 状态查询 ----

    /** 主密码是否已启用：存在加密后的 DbSecret 密文即视为启用。 */
    fun isEnabled(): Boolean = repo.get(K_PASSWORD_ENC) != null

    /** ssh-key 解锁是否已启用：存在 ssh-key 加密后的 DbSecret 密文即视为启用。 */
    fun isSshKeyEnabled(): Boolean = repo.get(K_SSH_AES_ENC) != null

    // ---- main-password ----

    /**
     * 开启主密码：用 [mainPassword] 加密当前内存中的 [DbSecret]，
     * 写入 KDF salt 与密文，并删除明文 password/salt。
     *
     * 前置：内存 [DatabaseSecret] 中已有有效 DbSecret（正常启动流程保证）。
     */
    fun enable(mainPassword: CharArray) {
        val secret = secretProvider()
        val kdfSalt = MasterPasswordCrypto.newKdfSalt()
        val enc = MasterPasswordCrypto.encryptWithMainPassword(mainPassword, kdfSalt, secret)
        repo.put(K_KDF_SALT, kdfSalt)
        repo.put(K_PASSWORD_ENC, enc)
        repo.remove(K_PLAIN_PASSWORD, K_PLAIN_SALT)
    }

    /**
     * 关闭主密码：用 [mainPassword] 解密密文，成功则写回明文 password/salt，
     * 并清空所有主密码 / ssh-key 相关密文。密码错误返回 false 且不改任何状态。
     */
    fun disable(mainPassword: CharArray): Boolean {
        val secret = loadMainPasswordEncryptedSecret(mainPassword) ?: return false
        repo.put(K_PLAIN_PASSWORD, secret.password)
        repo.put(K_PLAIN_SALT, secret.salt)
        repo.remove(K_PASSWORD_ENC, K_KDF_SALT, K_SSH_AES_ENC, K_SSH_PUBLICKEY, K_SSH_CHALLENGE)
        return true
    }

    /** 校验 [mainPassword] 是否正确；正确则返回解密出的 [DbSecret]，否则 null。 */
    fun verify(mainPassword: CharArray): DbSecret? = loadMainPasswordEncryptedSecret(mainPassword)

    /**
     * 启动解锁入口（主密码路径）：用 [mainPassword] 解密 [K_PASSWORD_ENC]。
     * GCM 校验失败（密码错）/ 密文缺失返回 null。
     */
    fun loadMainPasswordEncryptedSecret(mainPassword: CharArray): DbSecret? {
        val enc = repo.get(K_PASSWORD_ENC) ?: return null
        val kdfSalt = repo.get(K_KDF_SALT) ?: return null
        return MasterPasswordCrypto.decryptWithMainPassword(mainPassword, kdfSalt, enc)
    }

    // ---- ssh-key ----

    /**
     * 开启 ssh-key 解锁：要求主密码已启用（内存有 DbSecret）。
     * 用 agent 对随机 challenge 签名，把签名 SHA-256 派生成 AES key，再加密 DbSecret。
     *
     * @return true 成功；false 表示 agent 不可用 / 无此 key / 拒绝签名 / 文本非法。
     */
    fun enableSshKey(publicKeyText: String): Boolean {
        require(isEnabled()) { "ssh-key requires main-password enabled" }
        return try {
            // base64 随机 bytes 作为 challenge（newKdfSalt 即 base64 编码的 16 随机字节）。
            val challenge = MasterPasswordCrypto.newKdfSalt()
            val sig = SshAgentSigner().sign(publicKeyText, challenge.decodeBase64())
            val key = MasterPasswordCrypto.deriveSshKey(sig)
            val enc = MasterPasswordCrypto.encryptWithKey(key, secretProvider())
            repo.put(K_SSH_CHALLENGE, challenge)
            repo.put(K_SSH_PUBLICKEY, publicKeyText)
            repo.put(K_SSH_AES_ENC, enc)
            true
        } catch (e: Exception) {
            if (log.isWarnEnabled) log.warn("enableSshKey failed (key=${publicKeyText.take(50)}...)", e)
            false
        }
    }

    /** 关闭 ssh-key 解锁：仅清空 ssh 相关密文（保留主密码密文）。 */
    fun disableSshKey() {
        repo.remove(K_SSH_AES_ENC, K_SSH_PUBLICKEY, K_SSH_CHALLENGE)
    }

    /**
     * 启动解锁入口（ssh-key 路径）：用 agent 对存储的 challenge 重新签名派生 key，
     * 解 ssh 密文。agent 不可用 / 无此 key / 拒绝签名返回 null。
     */
    fun loadSshEncryptedSecret(): DbSecret? {
        val challenge = repo.get(K_SSH_CHALLENGE) ?: return null
        val publicKeyText = repo.get(K_SSH_PUBLICKEY) ?: return null
        val enc = repo.get(K_SSH_AES_ENC) ?: return null
        return try {
            val sig = SshAgentSigner().sign(publicKeyText, challenge.decodeBase64())
            MasterPasswordCrypto.decryptWithKey(MasterPasswordCrypto.deriveSshKey(sig), enc)
        } catch (e: Exception) {
            if (log.isWarnEnabled) log.warn("loadSshEncryptedSecret failed", e)
            null
        }
    }

    // ---- 启动解锁专用（database 重载）----
    // 这些重载用调用方传入的 [database] 直接访问 tb_unsafe_setting，**不**经过
    // repoProvider → DatabaseManager.getInstance()。
    //
    // 原因：启动解锁发生在 [DatabaseSecret.init] 内，而后者由 [DatabaseManager.init] 触发——
    // 此刻 ApplicationScope.getOrCreate(DatabaseManager) 的 create 尚未返回、beans[DatabaseManager]
    // 尚未注册。任何线程在此期间调 DatabaseManager.getInstance() 都会再次进入 getOrCreate：
    //   - EDT / 后台线程：synchronized(this) 等主线程释放锁 → 主线程又在 invokeAndWait 等 EDT → 死锁；
    //   - 主线程自身：重入 create → 再次 DatabaseManager().init → 无限递归 / StackOverflow。
    // 故解锁流程（含 ssh-key 尝试与密码校验）必须用 database 参数绕开 getInstance。

    fun isSshKeyEnabled(database: org.jetbrains.exposed.v1.jdbc.Database): Boolean =
        UnsafeSettingRepository(database).get(K_SSH_AES_ENC) != null

    fun loadSshEncryptedSecret(database: org.jetbrains.exposed.v1.jdbc.Database): DbSecret? {
        val repo = UnsafeSettingRepository(database)
        val challenge = repo.get(K_SSH_CHALLENGE) ?: return null
        val publicKeyText = repo.get(K_SSH_PUBLICKEY) ?: return null
        val enc = repo.get(K_SSH_AES_ENC) ?: return null
        return try {
            val sig = SshAgentSigner().sign(publicKeyText, challenge.decodeBase64())
            MasterPasswordCrypto.decryptWithKey(MasterPasswordCrypto.deriveSshKey(sig), enc)
        } catch (e: Exception) {
            if (log.isWarnEnabled) log.warn("loadSshEncryptedSecret(database) failed", e)
            null
        }
    }

    fun loadMainPasswordEncryptedSecret(
        database: org.jetbrains.exposed.v1.jdbc.Database,
        mainPassword: CharArray,
    ): DbSecret? {
        val repo = UnsafeSettingRepository(database)
        val enc = repo.get(K_PASSWORD_ENC) ?: return null
        val kdfSalt = repo.get(K_KDF_SALT) ?: return null
        return MasterPasswordCrypto.decryptWithMainPassword(mainPassword, kdfSalt, enc)
    }

    // ---- 测试注入 ----

    /**
     * 测试专用：注入临时 DB 的 repo 与固定内存 secret，替换默认的生产单例读取。
     * 仅在单测中使用，避免触碰真实 [DatabaseManager]。
     */
    internal fun useForTest(repo: UnsafeSettingRepository, currentSecret: DbSecret) {
        repoProvider = { repo }
        secretProvider = { currentSecret }
    }

    /** 测试专用：恢复默认生产注入，避免跨用例污染。 */
    internal fun resetForTest() {
        repoProvider = { UnsafeSettingRepository(DatabaseManager.getInstance().database) }
        secretProvider = {
            val ds = DatabaseSecret.getInstance()
            DbSecret(ds.password, ds.salt)
        }
    }
}
