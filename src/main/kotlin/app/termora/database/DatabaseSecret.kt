package app.termora.database

import app.termora.ApplicationScope
import app.termora.masterpassword.DbSecret
import app.termora.masterpassword.MasterPasswordService
import app.termora.masterpassword.MasterPasswordUnlockDialog
import app.termora.masterpassword.UnsafeSettingRepository
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.lang3.RandomUtils
import org.apache.commons.lang3.StringUtils
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

internal class DatabaseSecret(database: Database) {

    companion object {
        // keep in sync with MasterPasswordService.K_PLAIN_PASSWORD / K_PLAIN_SALT
        // （同值字符串 "__DB_PASSWORD" / "__DB_SALT"，两侧均读同一张 tb_unsafe_setting）
        private const val PASSWORD = "__DB_PASSWORD"
        private const val SALT = "__DB_SALT"

        fun getInstance(database: Database): DatabaseSecret {
            return ApplicationScope.forApplicationScope()
                .getOrCreate(DatabaseSecret::class) { DatabaseSecret(database) }
        }

        fun getInstance(): DatabaseSecret {
            return ApplicationScope.forApplicationScope().get(DatabaseSecret::class)
        }
    }

    @Volatile
    var password: String = StringUtils.EMPTY
        private set

    @Volatile
    var salt: String = StringUtils.EMPTY
        private set

    init {
        // 读取或解锁 aes-secret。
        // tb_unsafe_setting 表由 DatabaseManager.init 在新建库时（isExists.not()）
        // 先行 SchemaUtils.create(UnsafeSettingEntity) 创建；此处的 UnsafeSettingRepository
        // 读取/写入均基于该表，保证 init 顺序安全。
        loadOrUnlockSecret(database)
    }

    /**
     * 启动解锁写回入口：把 [MasterPasswordService] 解密出的 [DbSecret] 写入内存。
     *
     * 仅在主密码 / ssh-key 解锁成功后调用（init 流程）。`password`/`salt` 的
     * 真正 setter 保持 private，集中由本方法赋值，避免散落多处直接写。
     */
    internal fun setDecryptedSecret(secret: DbSecret) {
        password = secret.password
        salt = secret.salt
    }

    /**
     * 四分支启动解锁：
     *
     * 1. **明文兼容（旧库）**：`__DB_PASSWORD` 存在 → 直接从明文恢复 password/salt。
     *    极旧库可能没有 `__DB_SALT`，按原逻辑补一个并写回。
     * 2. **主密码启用**：`K_PASSWORD_ENC` 存在 → 驱动 [MasterPasswordUnlockDialog]
     *    弹框解锁（ssh-key 优先，失败回退主密码输入），成功后写回内存。
     * 3. **首次启动（无任何 key）**：随机生成 password+salt，写明文（保持原行为）。
     *
     * 注：原 init 中 `transaction(database) { DatabaseSecret.getInstance(database) }`
     * 的「读明文 / 随机生成」职责被本方法取代；self-reference 那一行已删除。
     */
    private fun loadOrUnlockSecret(database: Database) {
        val repo = UnsafeSettingRepository(database)
        val plain = repo.get(PASSWORD)

        if (plain != null) {
            // 未启用主密码：直接从明文表恢复（兼容原行为）
            password = plain
            salt = repo.get(SALT) ?: StringUtils.EMPTY
            if (salt.isEmpty()) {
                // 极旧库可能没有 salt：按原逻辑补
                salt = StringUtils.substring(DigestUtils.sha256Hex(RandomUtils.secureStrong().randomBytes(128)), 0, 12)
                repo.put(SALT, salt)
            }
            return
        }

        if (repo.get(MasterPasswordService.K_PASSWORD_ENC) != null) {
            // 主密码启用：驱动解锁 UI。
            //
            // EDT 安全性：本 init 由 DatabaseManager.init 在主线程（非 EDT）调用，
            // 而 unlock() 内部 `isVisible = true` 是模态阻塞——模态 setVisible 必须
            // 在 EDT 上执行，否则在 Linux/macOS 上可能 NPE 或卡死，用户被锁死且无后门。
            // 用 FutureTask + SwingUtilities.invokeAndWait 把解锁整体放到 EDT：
            //   - invokeAndWait 让主线程阻塞直到 EDT 执行完 FutureTask；
            //   - unlock() 在 EDT 上 isVisible=true 模态时进入 SecondaryLoop 泵送事件，
            //     按钮回调（tryUnlock）得以派发，不死锁；
            //   - 结果经 future.get() 回到主线程。
            // 这是 Swing 启动期弹模态框的标准模式。已在 EDT 上时 invokeAndWait 会抛
            // IllegalStateException，但本路径由主线程进入，不会触发。
            // sshEnabled 在主线程预读，且用 database 直接读（不调无参 isSshKeyEnabled()——那会走
            // DatabaseManager.getInstance()，而此刻正处在 DatabaseManager.init 内，ApplicationScope
            // 的 getOrCreate 尚未注册 DatabaseManager bean，重入会死锁/递归）。
            val sshEnabled = MasterPasswordService.isSshKeyEnabled(database)
            val future = FutureTask<DbSecret?> { MasterPasswordUnlockDialog(null, database).unlock(sshEnabled) }
            SwingUtilities.invokeAndWait(future)
            val secret = future.get() ?: exitProcess(1)
            setDecryptedSecret(secret)
            return
        }

        // 首次启动：保持原初始化（随机生成 password+salt，写明文）
        password = StringUtils.substring(DigestUtils.sha256Hex(RandomUtils.secureStrong().randomBytes(128)), 0, 16)
        salt = StringUtils.substring(DigestUtils.sha256Hex(RandomUtils.secureStrong().randomBytes(128)), 0, 12)
        repo.put(PASSWORD, password)
        repo.put(SALT, salt)
    }

}
