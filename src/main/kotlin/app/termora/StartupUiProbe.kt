package app.termora

import java.awt.EventQueue
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Swing-only part of the startup probe, deliberately loaded after AWT has already initialized. */
internal object StartupUiProbe {
    private val firstPaintWindow = AtomicReference<Window>()
    private val readinessGate = StartupReadinessGate(
        isReady = {
            val window = firstPaintWindow.get()
            window != null &&
                window.isShowing &&
                StartupProbe.hasMarked(StartupProbe.HOST_MODEL_READY) &&
                StartupProbe.hasMarked(StartupProbe.KEYMAP_READY)
        },
        canContinue = { firstPaintWindow.get()?.isShowing == true },
        post = { action -> EventQueue.invokeLater(action) },
        onFence = { round ->
            StartupProbe.mark(
                when (round) {
                    1 -> StartupProbe.EDT_FENCE_1
                    2 -> StartupProbe.EDT_FENCE_2
                    else -> StartupProbe.EDT_FENCE_3
                }
            )
        },
        onInteractive = {
            StartupProbe.mark(StartupProbe.INTERACTIVE)
            StartupProbe.flushAsync()
        },
    )

    fun onFramePainted(window: Window) {
        if (!StartupProbe.isEnabled || !window.isShowing || window.width <= 0 || window.height <= 0) return
        if (!StartupProbe.mark(StartupProbe.FIRST_PAINT)) return
        firstPaintWindow.compareAndSet(null, window)
        readinessGate.tryStart()
    }

    fun onContentReadinessChanged() {
        readinessGate.tryStart()
    }
}

/** Coordinates content readiness, first paint, and deterministic EventQueue fences without depending on Swing state. */
internal class StartupReadinessGate(
    private val fenceCount: Int = 3,
    private val isReady: () -> Boolean,
    private val canContinue: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
    private val onFence: (Int) -> Unit,
    private val onInteractive: () -> Unit,
) {
    private val started = AtomicBoolean(false)

    init {
        require(fenceCount > 0)
    }

    fun tryStart(): Boolean {
        if (!isReady() || !started.compareAndSet(false, true)) return false
        postFence(1)
        return true
    }

    private fun postFence(round: Int) {
        post {
            if (canContinue()) {
                onFence(round)
                if (round < fenceCount) {
                    postFence(round + 1)
                } else {
                    onInteractive()
                }
            }
        }
    }
}
