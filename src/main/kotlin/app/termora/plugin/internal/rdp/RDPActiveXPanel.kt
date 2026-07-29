package app.termora.plugin.internal.rdp

import app.termora.Application
import app.termora.Disposable
import app.termora.Host
import app.termora.I18n
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Embeds the Microsoft RDP ActiveX control hosted by a small WinForms helper.
 *
 * The helper is kept out of the JVM because mstscax.dll cannot be created with
 * SWT's OleCreate-based container. It remains a native borderless owned window
 * positioned over this panel, which preserves mstsc's native focus and keyboard
 * handling without a cross-process SetParent relationship.
 */
internal class RDPActiveXPanel(private val host: Host) : JPanel(BorderLayout()), Disposable {
    companion object {
        private val log = LoggerFactory.getLogger(RDPActiveXPanel::class.java)
        private val helperLock = Any()
        private const val HELPER_RESOURCE = "/app/termora/rdp/RdpActiveXHost.exe"
    }

    private val started = AtomicBoolean()
    private val disposed = AtomicBoolean()
    private val failureShown = AtomicBoolean()
    private val closeRequested = AtomicBoolean()
    private val processStopperStarted = AtomicBoolean()
    private val canvas = object : Canvas() {
        override fun addNotify() {
            super.addNotify()
            startHelper()
        }
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var overlayWindow: HWND? = null

    @Volatile
    private var canvasWindow: HWND? = null

    private var overlayState: OverlayState? = null
    private var overlayOwner: Long = 0

    private val overlayTimer = Timer(15) {
        syncOverlayWindow()
    }.apply {
        isRepeats = true
    }

    init {
        canvas.background = Color.BLACK
        add(canvas, BorderLayout.CENTER)
    }

    private fun startHelper() {
        if (!started.compareAndSet(false, true) || disposed.get()) return

        Thread({
            var launchedProcess: Process? = null
            var launchedWriter: BufferedWriter? = null
            try {
                val helper = extractHelper()
                val helperProcess = ProcessBuilder(helper.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val helperWriter = BufferedWriter(
                    OutputStreamWriter(helperProcess.outputStream, StandardCharsets.UTF_8)
                )
                launchedProcess = helperProcess
                launchedWriter = helperWriter
                val desktop = host.options.extras["desktop"]?.split('x')
                val width = desktop?.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(200)
                    ?: canvas.width.coerceAtLeast(1024)
                val height = desktop?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(200)
                    ?: canvas.height.coerceAtLeast(768)

                writeConfig(helperWriter, host.host)
                writeConfig(helperWriter, host.username)
                val password = host.authentication.password
                writeConfig(helperWriter, password)
                writeConfig(helperWriter, host.name)
                writeConfig(helperWriter, host.port.toString())
                writeConfig(helperWriter, width.toString())
                writeConfig(helperWriter, height.toString())
                helperWriter.flush()
                log.info("RDP ActiveX configuration sent. passwordProvided={}", password.isNotEmpty())

                process = helperProcess
                writer = helperWriter
                if (closeRequested.get() || disposed.get()) {
                    sendCommand(helperWriter, "CLOSE")
                    stopProcessAsync(helperProcess, helperWriter)
                    return@Thread
                }

                helperProcess.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        when {
                            line.startsWith("READY\t") -> {
                                val handle = line.substringAfter('\t').toLong()
                                log.info("RDP ActiveX READY. line: {}", line)
                                SwingUtilities.invokeLater { attachOverlayWindow(handle) }
                            }

                            line.startsWith("ERROR\t") -> {
                                val message = String(
                                    Base64.getDecoder().decode(line.substringAfter('\t')),
                                    StandardCharsets.UTF_8,
                                )
                                showFailure(FailureKind.STARTUP, message)
                            }

                            line.startsWith("FAILURE\t") -> {
                                val parts = line.split('\t', limit = 3)
                                if (parts.size != 3) {
                                    log.warn("Invalid RDP ActiveX failure message: {}", line)
                                    showFailure(FailureKind.CONNECTION, "The connection failed.")
                                    continue
                                }
                                val kind = runCatching { FailureKind.valueOf(parts[1]) }
                                    .getOrDefault(FailureKind.CONNECTION)
                                val message = runCatching {
                                    String(
                                        Base64.getDecoder().decode(parts[2]),
                                        StandardCharsets.UTF_8,
                                    )
                                }.getOrDefault("The connection failed.")
                                log.warn("RDP ActiveX failure. kind={}, message={}", kind, message)
                                showFailure(kind, message)
                            }

                            line.startsWith("STATE\t") -> {
                                log.info("RDP ActiveX Msg: {}", line)
                            }

                            else -> log.info("RDP ActiveX host: {}", line)
                        }
                    }
                }

                val exitCode = helperProcess.exitValue()
                log.info("RDP ActiveX helper exited. pid={}, exitCode={}", helperProcess.pid(), exitCode)
                if (!disposed.get() && !closeRequested.get() && exitCode != 0) {
                    showFailure(
                        FailureKind.FATAL,
                        "The embedded RDP client exited unexpectedly ($exitCode).",
                    )
                }
            } catch (e: Throwable) {
                log.error("Failed to start the embedded RDP client", e)
                requestClose("launcher failure")
                showFailure(FailureKind.STARTUP, e.message ?: e.javaClass.simpleName)
                launchedProcess?.takeIf { it.isAlive }?.let { helperProcess ->
                    runCatching { launchedWriter?.close() }
                    stopProcessAsync(helperProcess, launchedWriter)
                }
            } finally {
                launchedProcess?.takeUnless { it.isAlive }?.let { helperProcess ->
                    releaseProcessHandles(helperProcess, launchedWriter)
                }
            }
        }, "Termora-RDP-ActiveX-Launcher").apply {
            isDaemon = true
            start()
        }
    }

    private fun attachOverlayWindow(handle: Long) {
        if (disposed.get()) return
        try {
            val overlay = HWND(Pointer.createConstant(handle))
            val nativeCanvas = HWND(Native.getComponentPointer(canvas))
            val owner = User32.INSTANCE.GetAncestor(nativeCanvas, WinUser.GA_ROOT)
                ?: error("Unable to find the Termora owner window")

            overlayWindow = overlay
            canvasWindow = nativeCanvas
            overlayOwner = Pointer.nativeValue(owner.pointer)
            sendCommand("OWNER\t$overlayOwner")
            syncOverlayWindow(force = true)
            overlayTimer.start()
            log.info(
                "RDP ActiveX overlay attached. window={}, owner={}, logicalSize={}x{}",
                handle,
                overlayOwner,
                canvas.width,
                canvas.height,
            )
            sendCommand("CONNECT")
        } catch (e: Throwable) {
            log.error("Failed to attach the RDP ActiveX overlay window", e)
            showFailure(FailureKind.STARTUP, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun syncOverlayWindow(force: Boolean = false) {
        if (disposed.get()) return
        val nativeCanvas = canvasWindow ?: return
        val overlay = overlayWindow ?: return
        val rectangle = RECT()
        val hasBounds = User32.INSTANCE.GetWindowRect(nativeCanvas, rectangle)
        val visible = canvas.isShowing && hasBounds &&
            rectangle.right > rectangle.left && rectangle.bottom > rectangle.top
        val previous = overlayState
        val state = if (hasBounds) {
            OverlayState(
                rectangle.left,
                rectangle.top,
                rectangle.right - rectangle.left,
                rectangle.bottom - rectangle.top,
                visible,
            )
        } else {
            previous?.copy(visible = false) ?: OverlayState(0, 0, 1, 1, false)
        }

        val currentOwner = User32.INSTANCE.GetAncestor(nativeCanvas, WinUser.GA_ROOT)
        val currentOwnerValue = Pointer.nativeValue(currentOwner?.pointer)
        if (currentOwnerValue != 0L && currentOwnerValue != overlayOwner) {
            overlayOwner = currentOwnerValue
            sendCommand("OWNER\t$overlayOwner")
        }

        if (force || state != previous) {
            overlayState = state
            sendCommand(
                "BOUNDS\t${state.left}\t${state.top}\t${state.width}\t${state.height}\t" +
                    if (state.visible) "1" else "0",
            )
        }

        if (!User32.INSTANCE.IsWindow(overlay)) {
            overlayTimer.stop()
            log.warn("RDP ActiveX overlay window is no longer valid")
        }
    }

    fun requestRdpFocus() {
        sendCommand("FOCUS")
    }

    fun disconnect() {
        requestClose("tab close")
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        overlayTimer.stop()
        log.info("Disposing RDP ActiveX panel.")
        requestClose("dispose")
        overlayWindow = null
        canvasWindow = null
        overlayState = null

        process?.let { helperProcess ->
            stopProcessAsync(helperProcess, writer)
        }
    }

    private fun sendCommand(command: String) {
        val current = writer ?: return
        log.info("> RDP Command: {}", command)
        sendCommand(current, command)
    }

    private fun sendCommand(current: BufferedWriter, command: String) {
        synchronized(current) {
            runCatching {
                current.write(command)
                current.newLine()
                current.flush()
            }.onFailure {
                if (!disposed.get()) log.warn("Failed to send {} to the RDP ActiveX host", command, it)
            }
        }
    }

    private fun requestClose(reason: String) {
        if (!closeRequested.compareAndSet(false, true)) return
        val helperProcess = process
        log.info(
            "RDP ActiveX close requested. reason={}, pid={}",
            reason,
            helperProcess?.pid()?.toString() ?: "pending",
        )
        writer?.let { sendCommand(it, "CLOSE") }
    }

    private fun stopProcessAsync(helperProcess: Process, helperWriter: BufferedWriter?) {
        if (!processStopperStarted.compareAndSet(false, true)) return
        Thread({
            try {
                if (!helperProcess.waitFor(2, TimeUnit.SECONDS)) {
                    log.warn(
                        "RDP ActiveX helper did not exit after CLOSE; terminating it. pid={}",
                        helperProcess.pid(),
                    )
                    helperProcess.destroy()
                    if (!helperProcess.waitFor(1, TimeUnit.SECONDS)) {
                        log.warn(
                            "RDP ActiveX helper did not terminate gracefully; forcing termination. pid={}",
                            helperProcess.pid(),
                        )
                        helperProcess.destroyForcibly()
                        helperProcess.waitFor(2, TimeUnit.SECONDS)
                    }
                }
                if (helperProcess.isAlive) {
                    log.error("RDP ActiveX helper is still running after cleanup. pid={}", helperProcess.pid())
                } else {
                    log.info(
                        "RDP ActiveX resources released. pid={}, exitCode={}",
                        helperProcess.pid(),
                        helperProcess.exitValue(),
                    )
                }
            } catch (e: Throwable) {
                log.warn("Failed while stopping the RDP ActiveX helper. pid={}", helperProcess.pid(), e)
            } finally {
                releaseProcessHandles(helperProcess, helperWriter)
            }
        }, "Termora-RDP-ActiveX-Stopper").apply {
            isDaemon = true
            start()
        }
    }

    private fun releaseProcessHandles(helperProcess: Process, helperWriter: BufferedWriter?) {
        runCatching { helperWriter?.close() }
        runCatching { helperProcess.inputStream.close() }
        runCatching { helperProcess.errorStream.close() }
        runCatching { helperProcess.outputStream.close() }
        if (process === helperProcess) process = null
        if (writer === helperWriter) writer = null
    }

    private fun extractHelper(): File {
        synchronized(helperLock) {
            val bytes = RDPActiveXPanel::class.java.getResourceAsStream(HELPER_RESOURCE)
                ?.use { it.readBytes() }
                ?: error("RDP ActiveX host executable is missing from the application")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .take(8)
                .joinToString("") { "%02x".format(it) }
            val target = FileUtils.getFile(
                Application.getTemporaryDir(),
                "rdp",
                "RdpActiveXHost-$digest.exe",
            )
            if (target.isFile) return target

            FileUtils.forceMkdirParent(target)
            FileUtils.writeByteArrayToFile(target, bytes)
            return target
        }
    }

    private fun writeConfig(target: BufferedWriter, value: String) {
        target.write(Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8)))
        target.newLine()
    }

    private fun showFailure(kind: FailureKind, message: String) {
        if (!failureShown.compareAndSet(false, true)) return
        SwingUtilities.invokeLater {
            if (disposed.get()) return@invokeLater
            overlayTimer.stop()
            overlayWindow?.let { User32.INSTANCE.ShowWindow(it, WinUser.SW_HIDE) }
            requestClose("connection failure")
            removeAll()
            add(
                JLabel(
                    """
                    <html>
                    <div style='text-align:center'>
                    <b>${I18n.getString(kind.titleKey)}</b><br><br>
                    ${escapeHtml(message)}
                    </div>
                    </html>
                    """.trimIndent(),
                    SwingConstants.CENTER,
                ),
                BorderLayout.CENTER,
            )
            revalidate()
            repaint()
        }
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace("\r\n", "<br>")
        .replace("\n", "<br>")

    private data class OverlayState(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val visible: Boolean,
    )

    private enum class FailureKind(val titleKey: String) {
        STARTUP("termora.rdp.error.startup"),
        LOGIN("termora.rdp.error.login"),
        CONNECTION("termora.rdp.error.connection"),
        DISCONNECTED("termora.rdp.error.disconnected"),
        FATAL("termora.rdp.error.fatal"),
    }
}
