package app.termora.aot

import app.termora.AppLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AotCacheManagerTest {

    @Test
    fun `normal mode uses an existing cache`() {
        assertEquals(
            AotStartupAction.NONE,
            selectAotStartupAction(
                "normal",
                cacheExists = true,
                pendingExists = false,
                failedExists = false,
            ),
        )
    }

    @Test
    fun `normal mode restarts in record mode when no AOT files exist`() {
        assertEquals(
            AotStartupAction.RESTART_IN_RECORD_MODE,
            selectAotStartupAction(
                "normal",
                cacheExists = false,
                pendingExists = false,
                failedExists = false,
            ),
        )
    }

    @Test
    fun `normal mode marks a failed creation after record restart`() {
        assertEquals(
            AotStartupAction.MARK_CREATION_FAILED,
            selectAotStartupAction(
                "normal",
                cacheExists = false,
                pendingExists = true,
                failedExists = false,
            ),
        )
    }

    @Test
    fun `failed version continues without retrying every launch`() {
        assertEquals(
            AotStartupAction.NONE,
            selectAotStartupAction(
                "normal",
                cacheExists = false,
                pendingExists = false,
                failedExists = true,
            ),
        )
    }

    @Test
    fun `record mode restores normal mode after application exit`() {
        assertEquals(
            AotStartupAction.ARM_NORMAL_AFTER_EXIT,
            selectAotStartupAction(
                "record",
                cacheExists = false,
                pendingExists = false,
                failedExists = false,
            ),
        )
    }

    @Test
    fun `record config preserves user JVM options`() {
        val normal = """
            [Application]
            app.mainclass=app.termora.MainKt

            [JavaOptions]
            java-options=-Xmx4096m
            java-options=-Duser.option=custom
            java-options=-Dtermora.aot.mode=normal
            java-options=-XX:AOTMode=auto
            java-options=-XX:AOTCache=${'$'}APPDIR/aot/app_1.aot
        """.trimIndent()
        val record = """
            [JavaOptions]
            java-options=-Dtermora.aot.mode=record
            java-options=-XX:AOTMode=record
            java-options=-XX:AOTConfiguration=${'$'}APPDIR/aot/app_1.aotconfig
            java-options=-XX:AOTCacheOutput=${'$'}APPDIR/aot/app_1.aot
        """.trimIndent()

        val result = replaceAotOptions(normal, record)

        kotlin.test.assertContains(result, "java-options=-Xmx4096m")
        kotlin.test.assertContains(result, "java-options=-Duser.option=custom")
        kotlin.test.assertContains(result, "java-options=-Dtermora.aot.mode=record")
        kotlin.test.assertContains(result, "java-options=-XX:AOTCacheOutput=${'$'}APPDIR/aot/app_1.aot")
        kotlin.test.assertFalse(result.contains("java-options=-Dtermora.aot.mode=normal"))
        kotlin.test.assertFalse(result.contains("java-options=-XX:AOTCache=${'$'}APPDIR/aot/app_1.aot"))
    }

    @Test
    fun `normal AOT option is generated from current user config`() {
        val config = """
            [Application]
            app.mainclass=app.termora.MainKt

            [JavaOptions]
            java-options=-Xmx4096m
            java-options=-Duser.option=custom
        """.trimIndent()

        val result = replaceAotOptions(
            config,
            "java-options=-XX:AOTCache=${'$'}APPDIR/aot/app_1.aot",
        )

        assertTrue(result.contains("java-options=-Xmx4096m"))
        assertTrue(result.contains("java-options=-Duser.option=custom"))
        assertTrue(result.contains("java-options=-XX:AOTCache=${'$'}APPDIR/aot/app_1.aot"))
    }

    @Test
    fun `failed AOT setup removes only managed options`() {
        val config = """
            [JavaOptions]
            java-options=-Xmx4096m
            java-options=-Dtermora.aot.mode=record
            java-options=-XX:AOTMode=record
            java-options=-XX:AOTCacheOutput=${'$'}APPDIR/aot/app_1.aot
            java-options=-Duser.option=custom
        """.trimIndent()

        val result = removeAotOptions(config)

        assertTrue(result.contains("java-options=-Xmx4096m"))
        assertTrue(result.contains("java-options=-Duser.option=custom"))
        assertFalse(result.contains("termora.aot"))
        assertFalse(result.contains("-XX:AOT"))
    }

    @Test
    fun `only mutable portable layouts support AOT`() {
        assertTrue(isAotMutableLayout(AppLayout.Zip))
        assertTrue(isAotMutableLayout(AppLayout.TarGz))
        assertFalse(isAotMutableLayout(AppLayout.Appx))
        assertFalse(isAotMutableLayout(AppLayout.AppImage))
        assertFalse(isAotMutableLayout(AppLayout.Deb))
        assertFalse(isAotMutableLayout(AppLayout.App))
        assertFalse(isAotMutableLayout(AppLayout.AppStore))
    }

    @Test
    fun `application config directory follows jpackage layout`() {
        val zipLauncher = File("/tmp/Termora/Termora.exe")
        val tarLauncher = File("/tmp/Termora/bin/Termora")

        assertEquals(
            File("/tmp/Termora/app").absoluteFile,
            resolveAotAppDir(AppLayout.Zip, zipLauncher),
        )
        assertEquals(
            File("/tmp/Termora/lib/app").absoluteFile,
            resolveAotAppDir(AppLayout.TarGz, tarLauncher),
        )
        assertEquals(null, resolveAotAppDir(AppLayout.Appx, zipLauncher))
    }
}
