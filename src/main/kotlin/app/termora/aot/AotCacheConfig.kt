package app.termora.aot

import app.termora.AppLayout
import java.io.File

internal enum class AotStartupAction {
    NONE,
    RESTART_IN_RECORD_MODE,
    MARK_CREATION_FAILED,
    ARM_NORMAL_AFTER_EXIT,
}

/**
 * 根据磁盘上的产物决定本次启动要做什么。
 *
 * pending 只会在 record 进程正常退出时创建。下一次启动如果仍然没有 cache，
 * 说明 JVM 的 create 阶段失败，应停止自动重试，避免每次启动都进入训练流程。
 */
internal fun selectAotStartupAction(
    mode: String?,
    cacheExists: Boolean,
    pendingExists: Boolean,
    failedExists: Boolean,
): AotStartupAction {
    return when (mode) {
        "normal" -> when {
            cacheExists -> AotStartupAction.NONE
            failedExists -> AotStartupAction.NONE
            pendingExists -> AotStartupAction.MARK_CREATION_FAILED
            else -> AotStartupAction.RESTART_IN_RECORD_MODE
        }

        "record" -> AotStartupAction.ARM_NORMAL_AFTER_EXIT
        else -> AotStartupAction.NONE
    }
}

/**
 * 只替换 Termora 自己管理的 AOT 参数，用户配置的堆大小、系统属性等参数保持不变。
 */
internal fun replaceAotOptions(config: String, replacement: String): String {
    val replacementOptions = replacement.lineSequence()
        .filter(::isManagedAotOption)
        .toList()
    require(replacementOptions.isNotEmpty()) { "Replacement config has no AOT options" }

    val lines = config.lines()
    val firstManagedIndex = lines.indexOfFirst(::isManagedAotOption)
    if (firstManagedIndex >= 0) {
        return buildList {
            lines.forEachIndexed { index, line ->
                if (index == firstManagedIndex) {
                    addAll(replacementOptions)
                }
                if (!isManagedAotOption(line)) {
                    add(line)
                }
            }
        }.joinToString(System.lineSeparator())
    }

    val javaOptionsIndex = lines.indexOf("[JavaOptions]")
    require(javaOptionsIndex >= 0) { "Config has no [JavaOptions] section" }
    val nextSectionIndex = (javaOptionsIndex + 1 until lines.size)
        .firstOrNull { lines[it].startsWith("[") && lines[it].endsWith("]") }
        ?: lines.size

    return buildList {
        addAll(lines.subList(0, nextSectionIndex))
        addAll(replacementOptions)
        addAll(lines.subList(nextSectionIndex, lines.size))
    }.joinToString(System.lineSeparator())
}

internal fun removeAotOptions(config: String): String {
    return config.lineSequence()
        .filterNot(::isManagedAotOption)
        .joinToString(System.lineSeparator())
}

/**
 * AOT 自动训练需要在启动前替换主 cfg，因此只支持安装目录可安全修改的便携版。
 * AppX、AppImage、DEB 和签名后的 macOS app 都必须跳过。
 */
internal fun isAotMutableLayout(layout: AppLayout): Boolean {
    return layout == AppLayout.Zip || layout == AppLayout.TarGz
}

internal fun resolveAotAppDir(layout: AppLayout, launcher: File): File? {
    return when (layout) {
        AppLayout.Zip -> launcher.absoluteFile.parentFile?.resolve("app")
        AppLayout.TarGz -> launcher.absoluteFile.parentFile?.parentFile?.resolve("lib/app")
        else -> null
    }
}

private fun isManagedAotOption(line: String): Boolean {
    return line.startsWith("java-options=-Dtermora.aot.") ||
            line.startsWith("java-options=-XX:AOTMode=") ||
            line.startsWith("java-options=-XX:AOTConfiguration=") ||
            line.startsWith("java-options=-XX:AOTCache=") ||
            line.startsWith("java-options=-XX:AOTCacheOutput=") ||
            line.startsWith("java-options=-Xlog:aot=")
}
