package app.termora.plugin.internal.rdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RDPSettingsTest {
    @Test
    fun `existing hosts keep their saved resolution and open in ActiveX`() {
        val settings = RDPSettings.fromExtras(mapOf("desktop" to "1920x1080"))

        assertEquals(RDPResolution(1920, 1080), settings.resolution)
        assertEquals(RDPConnectionMethod.MSTSCAX, settings.connectionMethod)
        assertEquals(32, settings.colorDepth)
        assertEquals(RDPSettings(), RDPSettings.fromExtras(emptyMap()))
    }

    @Test
    fun `automatic resolution clears the old desktop value when saving an edit`() {
        val old = mapOf("desktop" to "1920x1080")
        val updated = RDPSettings.fromExtras(old).copy(resolution = null)
        val merged = old + updated.toExtras()

        assertNull(RDPSettings.fromExtras(merged).resolution)
        val properties = buildString { updated.appendMstscProperties(this) }.lineSequence().toSet()
        assertTrue("dynamic resolution:i:1" in properties)
        assertFalse(properties.any { it.startsWith("desktopwidth:") || it.startsWith("desktopheight:") })
    }

    @Test
    fun `fixed resolution accepts pasted dimensions and rejects invalid sizes`() {
        assertEquals(RDPResolution(1920, 1080), RDPResolution.parse(" 1920 × 1080 "))
        assertEquals(RDPResolution(200, 8192), RDPResolution.parse("200X8192"))
        assertNull(RDPResolution.parse("1920x"))
        assertNull(RDPResolution.parse("199x1080"))
        assertNull(RDPResolution.parse("1920x8193"))
        assertNull(RDPResolution.parse("9999999999999999999x1080"))
    }

    @Test
    fun `external connection writes fixed display settings and local resource preferences`() {
        val settings = RDPSettings(
            connectionMethod = RDPConnectionMethod.EXTERNAL,
            resolution = RDPResolution(1920, 1080),
            colorDepth = 24,
            desktopScale = 150,
            smartSizing = true,
            audioMode = 2,
            redirectMicrophone = true,
            redirectClipboard = false,
            redirectPrinters = true,
            redirectDrives = true,
            redirectSmartCards = true,
            bitmapCache = false,
        )

        assertEquals(settings, RDPSettings.fromExtras(settings.toExtras()))
        val properties = buildString { settings.appendMstscProperties(this) }.trimEnd().lines()
        assertEquals(
            listOf(
                "screen mode id:i:1",
                "use multimon:i:0",
                "dynamic resolution:i:0",
                "desktopwidth:i:1920",
                "desktopheight:i:1080",
                "session bpp:i:24",
                "desktopscalefactor:i:150",
                "smart sizing:i:1",
                "audiomode:i:2",
                "audiocapturemode:i:1",
                "redirectclipboard:i:0",
                "redirectprinters:i:1",
                "drivestoredirect:s:*",
                "redirectsmartcards:i:1",
                "bitmapcachepersistenable:i:0",
            ),
            properties,
        )
    }
}
