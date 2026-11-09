package app.termora.plugin.internal.rdp

internal enum class RDPConnectionMethod {
    MSTSCAX,
    EXTERNAL,
}

internal data class RDPResolution(val width: Int, val height: Int) {
    companion object {
        private val pattern = Regex("""(\d+)\s*[xX×]\s*(\d+)""")

        fun parse(value: String): RDPResolution? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            val width = match.groupValues[1].toIntOrNull() ?: return null
            val height = match.groupValues[2].toIntOrNull() ?: return null
            return if (width in 200..8192 && height in 200..8192) RDPResolution(width, height) else null
        }
    }

    override fun toString(): String = "${width}x${height}"
}

internal data class RDPSettings(
    val connectionMethod: RDPConnectionMethod = RDPConnectionMethod.MSTSCAX,
    val resolution: RDPResolution? = null,
    val colorDepth: Int = 32,
    val desktopScale: Int = 0,
    val smartSizing: Boolean = false,
    val audioMode: Int = 0,
    val redirectMicrophone: Boolean = false,
    val redirectClipboard: Boolean = true,
    val redirectPrinters: Boolean = false,
    val redirectDrives: Boolean = false,
    val redirectSmartCards: Boolean = false,
    val bitmapCache: Boolean = true,
) {
    companion object {
        const val DESKTOP = "desktop"
        val colorDepths = listOf(32, 24, 16, 15)
        val desktopScales = listOf(0, 100, 125, 150, 175, 200, 250, 300, 400, 500)

        fun fromExtras(extras: Map<String, String>): RDPSettings = RDPSettings(
            connectionMethod = if (extras["rdp.connectionMethod"] == "external") {
                RDPConnectionMethod.EXTERNAL
            } else {
                RDPConnectionMethod.MSTSCAX
            },
            resolution = extras[DESKTOP]?.let(RDPResolution::parse),
            colorDepth = extras["rdp.colorDepth"]?.toIntOrNull()?.takeIf { it in colorDepths } ?: 32,
            desktopScale = extras["rdp.desktopScale"]?.toIntOrNull()?.takeIf { it in desktopScales } ?: 0,
            smartSizing = extras["rdp.smartSizing"].toBoolean(),
            audioMode = extras["rdp.audioMode"]?.toIntOrNull()?.takeIf { it in 0..2 } ?: 0,
            redirectMicrophone = extras["rdp.redirectMicrophone"].toBoolean(),
            redirectClipboard = extras["rdp.redirectClipboard"] != "false",
            redirectPrinters = extras["rdp.redirectPrinters"].toBoolean(),
            redirectDrives = extras["rdp.redirectDrives"].toBoolean(),
            redirectSmartCards = extras["rdp.redirectSmartCards"].toBoolean(),
            bitmapCache = extras["rdp.bitmapCache"] != "false",
        )
    }

    fun toExtras(): Map<String, String> = mapOf(
        "rdp.connectionMethod" to if (connectionMethod == RDPConnectionMethod.EXTERNAL) "external" else "mstscax",
        DESKTOP to resolution?.toString().orEmpty(),
        "rdp.colorDepth" to colorDepth.toString(),
        "rdp.desktopScale" to desktopScale.toString(),
        "rdp.smartSizing" to smartSizing.toString(),
        "rdp.audioMode" to audioMode.toString(),
        "rdp.redirectMicrophone" to redirectMicrophone.toString(),
        "rdp.redirectClipboard" to redirectClipboard.toString(),
        "rdp.redirectPrinters" to redirectPrinters.toString(),
        "rdp.redirectDrives" to redirectDrives.toString(),
        "rdp.redirectSmartCards" to redirectSmartCards.toString(),
        "rdp.bitmapCache" to bitmapCache.toString(),
    )

    fun appendMstscProperties(output: StringBuilder) = with(output) {
        appendLine("screen mode id:i:1")
        appendLine("use multimon:i:0")
        appendLine("dynamic resolution:i:${(resolution == null).toRdpFlag()}")
        resolution?.let {
            appendLine("desktopwidth:i:${it.width}")
            appendLine("desktopheight:i:${it.height}")
        }
        appendLine("session bpp:i:$colorDepth")
        if (desktopScale != 0) appendLine("desktopscalefactor:i:$desktopScale")
        appendLine("smart sizing:i:${smartSizing.toRdpFlag()}")
        appendLine("audiomode:i:$audioMode")
        appendLine("audiocapturemode:i:${redirectMicrophone.toRdpFlag()}")
        appendLine("redirectclipboard:i:${redirectClipboard.toRdpFlag()}")
        appendLine("redirectprinters:i:${redirectPrinters.toRdpFlag()}")
        appendLine("drivestoredirect:s:${if (redirectDrives) "*" else ""}")
        appendLine("redirectsmartcards:i:${redirectSmartCards.toRdpFlag()}")
        appendLine("bitmapcachepersistenable:i:${bitmapCache.toRdpFlag()}")
    }

    private fun Boolean.toRdpFlag(): Int = if (this) 1 else 0
}
