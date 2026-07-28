package app.termora.aot

import app.termora.Application
import app.termora.AppLayout
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

internal data class AotContext(
    val layout: AppLayout,
    val launcher: File,
    val mainConfig: File,
    val runtimeJava: File,
    val files: AotFiles,
) {
    companion object {
        fun create(
            layout: AppLayout,
            launcher: File,
            appDir: File,
            version: String,
        ): AotContext {
            val aotDir = File(appDir, "aot")
            val cacheName = "app_$version.aot"
            val configurationName = cacheName.removeSuffix(".aot") + ".aotconfig"
            val javaExecutable = if (layout == AppLayout.Zip) "java.exe" else "java"

            return AotContext(
                layout = layout,
                launcher = launcher,
                mainConfig = File(appDir, "${Application.getName()}.cfg"),
                runtimeJava = File(System.getProperty("java.home"), "bin/$javaExecutable"),
                files = AotFiles(
                    directory = aotDir,
                    cache = File(aotDir, cacheName),
                    configuration = File(aotDir, configurationName),
                    normalConfig = File(aotDir, "${Application.getName()}.normal.cfg"),
                    pending = File(aotDir, "$cacheName.pending"),
                    failed = File(aotDir, "$cacheName.failed"),
                    log = File(aotDir, "aot-create.log"),
                ),
            )
        }
    }
}

internal data class AotFiles(
    val directory: File,
    val cache: File,
    val configuration: File,
    val normalConfig: File,
    val pending: File,
    val failed: File,
    val log: File,
)

internal object AotRuntimeProbe {
    /**
     * 使用精简 runtime 自己的 java 做无副作用探测。
     * AOTMode=off 保证不会读写真实 cache；旧 JVM 或不兼容 JVM 会直接返回非零。
     */
    fun isSupported(context: AotContext): Boolean {
        if (!context.runtimeJava.isFile || !context.runtimeJava.canExecute()) return false

        val nullDevice = if (context.layout == AppLayout.Zip) "NUL" else "/dev/null"
        val process = ProcessBuilder(
            context.runtimeJava.absolutePath,
            "-XX:AOTMode=off",
            "-XX:AOTConfiguration=$nullDevice",
            "-XX:AOTCacheOutput=$nullDevice",
            "-version",
        )
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }
}

internal object AotFileOperations {
    /**
     * 不能只依赖 File.canWrite()：原子替换还要求父目录允许创建、删除和改名。
     * 这里演练与真实 cfg 切换相同的替换操作，但不会触碰主 cfg。
     */
    fun canReplace(config: File): Boolean {
        if (!config.isFile || !config.canRead()) return false

        var source: File? = null
        var target: File? = null
        return try {
            source = Files.createTempFile(config.parentFile.toPath(), ".termora-aot-", ".tmp").toFile()
            target = Files.createTempFile(config.parentFile.toPath(), ".termora-aot-", ".tmp").toFile()
            atomicMove(source, target)
            true
        } catch (_: Exception) {
            false
        } finally {
            source?.delete()
            target?.delete()
        }
    }

    fun atomicCopy(source: File, target: File) {
        val temporary = createSiblingTemporaryFile(target)
        try {
            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            atomicMove(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    fun atomicWrite(target: File, content: String) {
        val temporary = createSiblingTemporaryFile(target)
        try {
            temporary.writeText(content)
            atomicMove(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    fun atomicMove(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun createSiblingTemporaryFile(target: File): File {
        return Files.createTempFile(
            target.parentFile.toPath(),
            ".${target.name}.",
            ".tmp",
        ).toFile()
    }
}
