package app.termora.masterpassword

import app.termora.I18n
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Window
import javax.swing.*
import kotlin.system.exitProcess

/**
 * 启动解锁 UI。两阶段、事件驱动：
 *
 * 1. **ssh-key 阶段**（如已启用）：后台线程调用
 *    [MasterPasswordService.loadSshEncryptedSecret]，center 区显示
 *    “正在通过 SSH Agent 解锁…”进度卡片 + 取消按钮。成功→置 [result] 并关闭对话框；
 *    失败/agent 不可用→切换到密码输入卡片。
 * 2. **main-password 阶段**：密码框 + 错误标签，EDT 按钮回调驱动校验，
 *    校验通过置 [result] 并关闭；连续 [MAX_ATTEMPTS] 次错误或用户取消→`exitProcess`。
 *
 * [unlock] 通过模态 `setVisible(true)` 阻塞调用线程（通常是启动 EDT 之外的引导线程），
 * 全部 UI 状态切换均由 EDT 回调或 `SwingUtilities.invokeLater` 完成；无 sleep 轮询。
 *
 * 返回解锁出的 [DbSecret]，或 null（用户放弃 → 调用方应退出）。
 *
 * i18n key（`termora.masterpassword.unlock.ssh-progress` 等）由 Task 10 补齐；
 * 缺失时 [I18n.getString] 返回 key 本身，不会崩溃。
 */
class MasterPasswordUnlockDialog(owner: Window?, private val database: org.jetbrains.exposed.v1.jdbc.Database) :
    JDialog(owner, java.awt.Dialog.ModalityType.APPLICATION_MODAL) {

    private val passwordField = JPasswordField(24)
    private val errorLabel = JLabel(" ")

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    /** ssh-key 进度卡片。 */
    private val sshProgress: JPanel

    /** 密码输入卡片（含 prompt / 密码框 / 错误标签）。 */
    private val passwordPanel: JPanel

    @Volatile
    private var result: DbSecret? = null

    /** 已尝试次数（密码阶段）。达到 [MAX_ATTEMPTS] 即退出进程。 */
    private var attempts = 0

    init {
        title = I18n.getString("termora.masterpassword.unlock.title")
        isResizable = false
        defaultCloseOperation = DO_NOTHING_ON_CLOSE

        // ---- ssh-key 进度卡片 ----
        sshProgress = JPanel(BorderLayout(8, 8))
        sshProgress.border = BorderFactory.createEmptyBorder(24, 24, 24, 24)
        val progressLabel = JLabel(I18n.getString("termora.masterpassword.unlock.ssh-progress"))
        val progressBar = JProgressBar()
        progressBar.isIndeterminate = true
        val progressBox = JPanel(BorderLayout(8, 8))
        progressBox.add(progressLabel, BorderLayout.NORTH)
        progressBox.add(progressBar, BorderLayout.CENTER)
        sshProgress.add(progressBox, BorderLayout.CENTER)
        val sshCancel = JButton(I18n.getString("termora.masterpassword.unlock.cancel"))
        val sshCancelRow = JPanel()
        sshCancelRow.add(sshCancel)
        sshProgress.add(sshCancelRow, BorderLayout.SOUTH)
        sshCancel.addActionListener { exitProcess(1) }

        // ---- 密码输入卡片 ----
        passwordPanel = JPanel(BorderLayout(8, 8))
        passwordPanel.border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        passwordPanel.add(JLabel(I18n.getString("termora.masterpassword.unlock.prompt")), BorderLayout.NORTH)
        passwordPanel.add(passwordField, BorderLayout.CENTER)
        passwordPanel.add(errorLabel, BorderLayout.SOUTH)

        cards.add(sshProgress, CARD_SSH)
        cards.add(passwordPanel, CARD_PASSWORD)

        val south = JPanel()
        val ok = JButton(I18n.getString("termora.masterpassword.unlock.ok"))
        val cancel = JButton(I18n.getString("termora.masterpassword.unlock.cancel"))
        south.add(ok)
        south.add(cancel)

        ok.addActionListener { tryUnlock() }
        cancel.addActionListener { exitProcess(1) }
        passwordField.addActionListener { tryUnlock() }

        add(cards, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)
        pack()
        setLocationRelativeTo(owner)
    }

    /**
     * 由解锁流程驱动。先在后台线程尝试 ssh-key 解锁；成功则返回，失败则切到
     * 密码输入卡片，模态阻塞，等待 EDT 按钮回调写入 [result]。
     *
     * 后台线程只通过 [SwingUtilities.invokeLater] 触碰 UI；模态 `setVisible(true)`
     * 阻塞调用线程，结果由按钮回调（密码正确）或 ssh 成功回调写入并 `isVisible=false`。
     *
     * 返回 null 表示用户放弃，调用方应 `exitProcess`。
     */
    fun unlock(sshEnabled: Boolean): DbSecret? {
        // sshEnabled 由调用方（主线程）预读传入；本方法运行在 EDT，不做任何 DB 读
        // （EDT 上访问数据库会 hang —— 见调用方 DatabaseSecret.loadOrUnlockSecret 注释）。
        if (sshEnabled) {
            // 先显示进度卡片，再起后台线程。
            cardLayout.show(cards, CARD_SSH)

            // ssh-key 阶段：后台线程跑 loadSshEncryptedSecret()，
            // 成功→置 result 并关闭；失败→切到密码输入卡片。
            Thread({
                val sshSecret = MasterPasswordService.loadSshEncryptedSecret(database)
                SwingUtilities.invokeLater {
                    if (sshSecret != null) {
                        result = sshSecret
                        isVisible = false
                    } else {
                        switchToPasswordPanel()
                    }
                }
            }, "termora-masterpassword-sshkey").start()
        } else {
            // 未启用 ssh-key：直接进入密码阶段。
            switchToPasswordPanel()
        }

        // 模态阻塞；ssh 成功或密码正确时由回调置 isVisible=false。
        isVisible = true
        return result
    }

    /** 切到密码输入卡片并抢占焦点（仅在 EDT 调用）。 */
    private fun switchToPasswordPanel() {
        cardLayout.show(cards, CARD_PASSWORD)
        passwordField.requestFocusInWindow()
    }

    private fun tryUnlock() {
        // JPasswordField.getPassword() 返回 CharArray，直接传入即可。
        val pwd = passwordField.password
        try {
            val secret = MasterPasswordService.loadMainPasswordEncryptedSecret(database, pwd)
            if (secret != null) {
                result = secret
                isVisible = false
                return
            }
            attempts++
            if (attempts >= MAX_ATTEMPTS) {
                exitProcess(1)
            }
            errorLabel.text = I18n.getString("termora.masterpassword.unlock.wrong")
            passwordField.selectAll()
        } finally {
            // security-note: 用完立即清零，尽量缩短主密码 CharArray 在堆上的存活窗口。
            // JPasswordField 内部已 copy 一份（getPassword 每次返回新数组），这里清的是本次副本。
            java.util.Arrays.fill(pwd, 0.toChar())
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val CARD_SSH = "ssh"
        const val CARD_PASSWORD = "password"
    }
}
