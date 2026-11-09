package app.termora.plugin.internal.rdp

import app.termora.I18n
import app.termora.Icons
import app.termora.OptionsPane
import app.termora.OutlineTextField
import com.jgoodies.forms.builder.FormBuilder
import com.jgoodies.forms.layout.FormLayout
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*

internal class RDPDisplayOption : JPanel(BorderLayout()), OptionsPane.Option {
    val resolutionField = OutlineTextField(255).apply {
        placeholderText = "1920 × 1080"
        toolTipText = label("resolution-help")
        accessibleContext.accessibleName = label("resolution")
    }
    private val resolutionMode = JComboBox(arrayOf(label("automatic"), label("fixed")))
    private val colorDepth = JComboBox(RDPSettings.colorDepths.map { label("bits-per-pixel", it) }.toTypedArray())
    private val desktopScale = JComboBox(RDPSettings.desktopScales.map {
        if (it == 0) label("follow-local-scale") else "$it%"
    }.toTypedArray())
    private val smartSizing = JCheckBox(label("smart-sizing"))
    private val audioMode = JComboBox(arrayOf(label("audio-local"), label("audio-remote"), label("audio-off")))
    private val microphone = JCheckBox(label("microphone"))
    private val clipboard = JCheckBox(label("clipboard"))
    private val printers = JCheckBox(label("printers"))
    private val drives = JCheckBox(label("drives"))
    private val smartCards = JCheckBox(label("smart-cards"))
    private val bitmapCache = JCheckBox(label("bitmap-cache"))

    init {
        val resolution = JPanel(BorderLayout(8, 0)).apply {
            add(resolutionMode, BorderLayout.WEST)
            add(resolutionField, BorderLayout.CENTER)
        }
        resolutionMode.addActionListener { updateResolutionField() }
        colorDepth.toolTipText = label("color-depth-help")
        smartSizing.toolTipText = label("smart-sizing-help")
        desktopScale.toolTipText = label("desktop-scale-help")

        val resources = JPanel(GridLayout(0, 2, 8, 0)).apply {
            add(clipboard)
            add(microphone)
            add(printers)
            add(drives)
            add(smartCards)
        }
        val margin = OptionsPane.FORM_MARGIN
        val form = FormBuilder.create()
            .layout(FormLayout("left:pref, $margin, default:grow", List(7) { "pref" }.joinToString(", $margin, ")))
            .add(JLabel(label("resolution")).apply { labelFor = resolutionMode }).xy(1, 1)
            .add(resolution).xy(3, 1)
            .add(JLabel(label("color-depth")).apply { labelFor = colorDepth }).xy(1, 3)
            .add(colorDepth).xy(3, 3)
            .add(JLabel(label("desktop-scale")).apply { labelFor = desktopScale }).xy(1, 5)
            .add(desktopScale).xy(3, 5)
            .add(smartSizing).xy(3, 7)
            .add(JLabel(label("audio")).apply { labelFor = audioMode }).xy(1, 9)
            .add(audioMode).xy(3, 9)
            .add(label("local-resources")).xy(1, 11)
            .add(resources).xy(3, 11)
            .add(bitmapCache).xy(3, 13)
            .build()
        add(form, BorderLayout.NORTH)
        setSettings(RDPSettings(), "")
    }

    val resolutionText: String
        get() = if (resolutionMode.selectedIndex == 1) resolutionField.text.trim() else ""

    fun setSettings(settings: RDPSettings, savedResolution: String) {
        resolutionField.text = settings.resolution?.toString() ?: savedResolution
        resolutionField.outline = null
        resolutionMode.selectedIndex = if (savedResolution.isBlank()) 0 else 1
        updateResolutionField()
        colorDepth.selectedIndex = RDPSettings.colorDepths.indexOf(settings.colorDepth)
        desktopScale.selectedIndex = RDPSettings.desktopScales.indexOf(settings.desktopScale)
        smartSizing.isSelected = settings.smartSizing
        audioMode.selectedIndex = settings.audioMode
        microphone.isSelected = settings.redirectMicrophone
        clipboard.isSelected = settings.redirectClipboard
        printers.isSelected = settings.redirectPrinters
        drives.isSelected = settings.redirectDrives
        smartCards.isSelected = settings.redirectSmartCards
        bitmapCache.isSelected = settings.bitmapCache
    }

    fun getSettings(connectionMethod: RDPConnectionMethod): RDPSettings = RDPSettings(
        connectionMethod = connectionMethod,
        resolution = RDPResolution.parse(resolutionText),
        colorDepth = RDPSettings.colorDepths[colorDepth.selectedIndex],
        desktopScale = RDPSettings.desktopScales[desktopScale.selectedIndex],
        smartSizing = smartSizing.isSelected,
        audioMode = audioMode.selectedIndex,
        redirectMicrophone = microphone.isSelected,
        redirectClipboard = clipboard.isSelected,
        redirectPrinters = printers.isSelected,
        redirectDrives = drives.isSelected,
        redirectSmartCards = smartCards.isSelected,
        bitmapCache = bitmapCache.isSelected,
    )

    private fun updateResolutionField() {
        resolutionField.isEnabled = resolutionMode.selectedIndex == 1
        resolutionField.outline = null
    }

    override fun getIcon(isSelected: Boolean): Icon = Icons.microsoftWindows
    override fun getTitle(): String = "RDP"
    override fun getJComponent(): JComponent = this

    private fun label(key: String, vararg args: Any): String = I18n.getString("termora.new-host.rdp.$key", *args)
}
