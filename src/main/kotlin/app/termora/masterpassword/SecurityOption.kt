package app.termora.masterpassword

import app.termora.Icons
import app.termora.I18n
import app.termora.OptionsPane
import app.termora.YesOrNoComboBox
import com.jgoodies.forms.builder.FormBuilder
import com.jgoodies.forms.layout.FormLayout
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import javax.swing.*

/**
 * 「安全」全局设置页：主密码开关 + ssh-key 解锁开关 + 公钥录入/测试。
 *
 * 本页只负责把用户操作转给 [MasterPasswordService]（DB 读写已在其中完成），
 * 不持有密码 / 密文状态。i18n key 由 Task 10 补，本类直接引用字符串占位。
 *
 * 行为约束：
 * - ssh-key 相关控件（含 [sshKeyBox]、[publicKeyField]、[testButton]）始终依赖主密码开关；
 *   主密码关闭时强制禁用并清空 ssh-key 选项。
 * - 用户在密码 / ssh-key 弹窗中取消时，将对应 ComboBox 还原为原状态（通过 [updating]
 *   守卫位避免重入触发联动）。
 */
class SecurityOption : JPanel(BorderLayout()), OptionsPane.Option {

    private val masterPasswordBox = YesOrNoComboBox()
    private val sshKeyBox = YesOrNoComboBox()
    private val publicKeyField = JTextField(30)
    private val testButton = JButton(I18n.getString("termora.masterpassword.test"))
    private val owner get() = SwingUtilities.getWindowAncestor(this)

    /**
     * 程序化修改 ComboBox 时的重入守卫：避免在还原选中态 / 初始化时
     * 触发 [masterPasswordBox]/[sshKeyBox] 的 ItemListener 联动。
     */
    private var updating = false

    init {
        initView()
        initEvents()
        add(buildForm(), BorderLayout.CENTER)
    }

    private fun initView() {
        updating = true
        masterPasswordBox.selectedItem = MasterPasswordService.isEnabled()
        sshKeyBox.selectedItem = MasterPasswordService.isSshKeyEnabled()
        updating = false
        refreshSshEnabled()
    }

    private fun initEvents() {
        masterPasswordBox.addItemListener {
            if (updating) return@addItemListener
            if (it.stateChange != ItemEvent.SELECTED) return@addItemListener
            val enabled = masterPasswordBox.selectedItem == true
            if (enabled) {
                val pwd = askPassword(
                    I18n.getString("termora.masterpassword.set"),
                    I18n.getString("termora.masterpassword.set-warn")
                )
                if (pwd == null) {
                    revert(masterPasswordBox, false)
                    return@addItemListener
                }
                MasterPasswordService.enable(pwd.toCharArray())
            } else {
                val pwd = askPassword(
                    I18n.getString("termora.masterpassword.disable"),
                    I18n.getString("termora.masterpassword.enter-current")
                )
                if (pwd == null) {
                    revert(masterPasswordBox, true)
                    return@addItemListener
                }
                if (!MasterPasswordService.disable(pwd.toCharArray())) {
                    JOptionPane.showMessageDialog(
                        owner,
                        I18n.getString("termora.masterpassword.wrong"),
                        I18n.getString("termora.masterpassword.disable"),
                        JOptionPane.WARNING_MESSAGE
                    )
                    revert(masterPasswordBox, true)
                    return@addItemListener
                }
            }
            refreshSshEnabled()
        }

        sshKeyBox.addItemListener {
            if (updating) return@addItemListener
            if (it.stateChange != ItemEvent.SELECTED) return@addItemListener
            val enabled = sshKeyBox.selectedItem == true
            if (enabled) {
                // 依赖主密码已启用；测试通过即视为开启
                if (!MasterPasswordService.isEnabled()) {
                    revert(sshKeyBox, false)
                    return@addItemListener
                }
                val pub = publicKeyField.text.trim()
                if (pub.isEmpty() || !MasterPasswordService.enableSshKey(pub)) {
                    JOptionPane.showMessageDialog(
                        owner,
                        I18n.getString("termora.masterpassword.test-failed"),
                        I18n.getString("termora.masterpassword.sshkey"),
                        JOptionPane.WARNING_MESSAGE
                    )
                    revert(sshKeyBox, false)
                    return@addItemListener
                }
            } else {
                MasterPasswordService.disableSshKey()
            }
        }

        testButton.addActionListener {
            if (!MasterPasswordService.isEnabled()) return@addActionListener
            val pub = publicKeyField.text.trim()
            if (pub.isEmpty()) return@addActionListener
            val ok = MasterPasswordService.enableSshKey(pub)
            updating = true
            sshKeyBox.selectedItem = ok
            updating = false
            JOptionPane.showMessageDialog(
                owner,
                if (ok) I18n.getString("termora.masterpassword.test-success") else I18n.getString("termora.masterpassword.test-failed"),
                I18n.getString("termora.masterpassword.test"),
                if (ok) JOptionPane.INFORMATION_MESSAGE else JOptionPane.WARNING_MESSAGE
            )
        }
    }

    /**
     * 把 [box] 的选中态还原为 [value]，期间置 [updating] 守卫避免重入联动。
     */
    private fun revert(box: JComboBox<*>, value: Boolean) {
        updating = true
        box.selectedItem = value
        updating = false
    }

    /**
     * 按主密码启用态刷新 ssh-key 相关控件，并在主密码关闭时强制 ssh-key 显示为关闭。
     */
    private fun refreshSshEnabled() {
        val on = MasterPasswordService.isEnabled()
        sshKeyBox.isEnabled = on
        publicKeyField.isEnabled = on
        testButton.isEnabled = on
        if (!on && sshKeyBox.selectedItem == true) {
            revert(sshKeyBox, false)
        }
    }

    // security-note: showInputDialog returns immutable String; switch to JPasswordField for hardening.
    // 当前 enable/disable 内部 pwd.toCharArray() 产生的是临时 CharArray，无法从这里回收 String 的底层 char[]。
    // 现状保留（设置页交互），后续如需硬化应改为自定义 JDialog + JPasswordField 并在用后 Arrays.fill。
    private fun askPassword(title: String, msg: String): String? =
        (JOptionPane.showInputDialog(owner, msg, title, JOptionPane.PLAIN_MESSAGE) as? String)
            ?.trim()?.takeIf { it.isNotEmpty() }

    private fun buildForm(): JComponent {
        val layout = FormLayout(
            "left:pref, 7dlu, default:grow, 7dlu, pref",
            "pref, 7dlu, pref, 7dlu, pref"
        )
        return FormBuilder.create().layout(layout)
            .add(I18n.getString("termora.masterpassword.enable") + ":").xy(1, 1).add(masterPasswordBox).xy(3, 1)
            .add(I18n.getString("termora.masterpassword.sshkey") + ":").xy(1, 3).add(sshKeyBox).xy(3, 3)
            .add(I18n.getString("termora.masterpassword.publickey") + ":").xy(1, 5)
            .add(publicKeyField).xy(3, 5)
            .add(testButton).xy(5, 5)
            .build()
    }

    override fun getIcon(isSelected: Boolean): Icon = Icons.locked
    override fun getTitle(): String = I18n.getString("termora.settings.security")
    override fun getIdentifier(): String = "Security"
    override fun getAnchor(): OptionsPane.Anchor = OptionsPane.Anchor.After("Appearance")
    override fun getJComponent(): JComponent = this
}
