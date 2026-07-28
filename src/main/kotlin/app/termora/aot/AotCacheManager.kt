package app.termora.aot

import app.termora.Application
import app.termora.TermoraRestarter
import com.formdev.flatlaf.util.SystemInfo
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess

/**
 * 为可写的便携版自动维护 JDK 25 AOT Cache。
 *
 * 生命周期：
 * 1. normal 启动检测能力并把主 cfg 临时切换为 record。
 * 2. record 进程立即恢复 normal cfg，随后承载本次用户会话。
 * 3. record 进程退出时由 JVM 创建 cache，再自动启动 normal 进程使用它。
 *
 * 任何探测或文件操作失败都会回退到普通启动，不影响 Termora 的基本功能。
 */
object AotCacheManager {
    private val log = LoggerFactory.getLogger(AotCacheManager::class.java)

    private const val MODE_PROPERTY = "termora.aot.mode"

    fun prepare() {
        // macOS 发行包不启用此功能：在读取布局、探测 Runtime 或访问 cfg 之前直接返回。
        if (SystemInfo.isMacOS) return

        runCatching { createContext()?.let(::handleStartup) }.onFailure {
            log.warn("Unable to prepare the AOT cache; continuing without it", it)
        }
    }

    /**
     * 这里只接受能够安全替换主 cfg 的便携版。
     * 安装型、只读型和签名型发行包在这里直接退出，不产生任何 AOT 文件。
     */
    private fun createContext(): AotContext? {
        val layout = Application.getLayout()
        if (!isAotMutableLayout(layout)) return null

        val launcher = Application.getAppPath()
            .takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return null
        val appDir = resolveAotAppDir(layout, launcher) ?: return null

        return AotContext.create(
            layout = layout,
            launcher = launcher,
            appDir = appDir,
            version = Application.getVersion(),
        )
    }

    private fun handleStartup(context: AotContext) {
        val action = selectAotStartupAction(
            mode = System.getProperty(MODE_PROPERTY) ?: "normal",
            cacheExists = context.files.cache.isFile,
            pendingExists = context.files.pending.isFile,
            failedExists = context.files.failed.isFile,
        )

        when (action) {
            AotStartupAction.NONE -> cleanSuccessfulSetup(context)
            AotStartupAction.RESTART_IN_RECORD_MODE -> startRecordMode(context)
            AotStartupAction.MARK_CREATION_FAILED -> disableFailedSetup(context)
            AotStartupAction.ARM_NORMAL_AFTER_EXIT -> finishRecordMode(context)
        }
    }

    private fun cleanSuccessfulSetup(context: AotContext) {
        if (!context.files.cache.isFile) return

        context.files.pending.delete()
        context.files.failed.delete()
        context.files.configuration.delete()
    }

    /**
     * 第一次 normal 启动会走这里。
     *
     * 先做 JVM 能力与目录写入探测，全部通过后才修改用户当前的 Termora.cfg。
     * 如果安排重启失败，则立刻写回原始配置，避免下次启动意外进入 record。
     */
    private fun startRecordMode(context: AotContext) {
        if (!AotFileOperations.canReplace(context.mainConfig)) {
            log.info("AOT cache is disabled because {} cannot be replaced", context.mainConfig)
            return
        }
        if (!AotRuntimeProbe.isSupported(context)) {
            log.info("AOT cache is not supported by {}", context.runtimeJava)
            return
        }

        val originalConfig = context.mainConfig.readText()
        writeRecordAndNormalConfigs(context, originalConfig)

        try {
            restartAndExit(context.launcher)
        } catch (e: Exception) {
            runCatching { AotFileOperations.atomicWrite(context.mainConfig, originalConfig) }
                .onFailure(e::addSuppressed)
            throw e
        }
    }

    /**
     * record JVM 此时已经读取完启动参数，所以可以马上把磁盘上的主 cfg 恢复为 normal。
     * 恢复必须尽早完成：即使训练过程中崩溃或被强制结束，下次也不会卡在 record 模式。
     */
    private fun finishRecordMode(context: AotContext) {
        restoreNormalConfig(context)
        scheduleNormalRestartAfterExit(context)
    }

    /**
     * pending 存在但 cache 不存在，表示上一轮 record 已正常退出、JVM create 阶段却失败了。
     * 记录失败并移除 AOT 参数，避免以后每次启动都重复训练或打印无效 cache 警告。
     */
    private fun disableFailedSetup(context: AotContext) {
        AotFileOperations.atomicMove(context.files.pending, context.files.failed)

        runCatching {
            val ordinaryConfig = removeAotOptions(context.mainConfig.readText())
            AotFileOperations.atomicWrite(context.mainConfig, ordinaryConfig)
        }.onFailure {
            log.warn("Unable to remove unusable AOT options from {}", context.mainConfig, it)
        }

        log.warn(
            "AOT cache creation failed; continuing without it. See {}",
            context.files.log,
        )
    }

    private fun writeRecordAndNormalConfigs(context: AotContext, originalConfig: String) {
        require(context.files.directory.mkdirs() || context.files.directory.isDirectory) {
            "Unable to create AOT directory: ${context.files.directory}"
        }

        val normalOptions =
            "java-options=-XX:AOTCache=${'$'}APPDIR/aot/${context.files.cache.name}"
        AotFileOperations.atomicWrite(
            context.files.normalConfig,
            replaceAotOptions(originalConfig, normalOptions),
        )

        val recordOptions = """
            java-options=-Dtermora.aot.mode=record
            java-options=-XX:AOTMode=record
            java-options=-XX:AOTConfiguration=${'$'}APPDIR/aot/${context.files.configuration.name}
            java-options=-XX:AOTCacheOutput=${'$'}APPDIR/aot/${context.files.cache.name}
            java-options=-Xlog:aot=info:file=${'$'}APPDIR/aot/${context.files.log.name}:time,uptime,level,tags
        """.trimIndent()
        AotFileOperations.atomicWrite(
            context.mainConfig,
            replaceAotOptions(originalConfig, recordOptions),
        )
    }

    private fun restoreNormalConfig(context: AotContext) {
        require(context.files.normalConfig.isFile) {
            "AOT normal configuration is missing: ${context.files.normalConfig}"
        }
        AotFileOperations.atomicCopy(context.files.normalConfig, context.mainConfig)
    }

    /**
     * pending 在 shutdown hook 中创建，而 cache 由 JVM 在退出流程中生成。
     * restart4j 会等当前进程彻底结束后再启动 Termora，因此新进程看到的 cache 已经完整落盘。
     */
    private fun scheduleNormalRestartAfterExit(context: AotContext) {
        val restarter = TermoraRestarter.getInstance()
        Runtime.getRuntime().addShutdownHook(
            Thread.ofPlatform()
                .name("aot-cache-finalizer")
                .unstarted {
                    if (restarter.isRestartScheduled) return@unstarted

                    runCatching {
                        context.files.pending.writeText("")
                        check(
                            restarter.restartAfterCurrentProcess(
                                listOf(context.launcher.absolutePath)
                            )
                        ) { "Unable to schedule normal restart" }
                    }.onFailure {
                        log.warn("Unable to complete AOT cache setup", it)
                    }
                }
        )
    }

    private fun restartAndExit(launcher: File): Nothing {
        require(launcher.isFile) { "AOT launcher is missing: $launcher" }
        val restarted = TermoraRestarter.getInstance()
            .restartAfterCurrentProcess(listOf(launcher.absolutePath))
        check(restarted) { "Unable to schedule AOT restart" }
        exitProcess(0)
    }
}
