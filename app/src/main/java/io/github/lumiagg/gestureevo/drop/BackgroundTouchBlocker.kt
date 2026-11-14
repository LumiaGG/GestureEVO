package io.github.lumiagg.gestureevo.drop

import android.util.Log
import android.view.View
import android.view.WindowManager
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.ToastHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen transparent sink attached while a press drag is shown. Window touch targets are
 * fixed at DOWN, so the shield cannot recall the stream already in flight; it only swallows
 * touches that START while it is up (secondary fingers, stray taps). Standard mode attempts
 * Shizuku DOWN/CANCEL before this shield attaches. Root mode keeps its direct cancellation
 * helper. Injecting onto the shield itself does not reliably end the source application's stream.
 */
internal class BackgroundTouchBlocker(private val context: Context, private val scope: CoroutineScope) :
    BaseFloatingWindow(context, scope, "EVO-TouchBlock") {

    private var current: View? = null
    private var closed = false
    private var failureReported = false
    private var rootRequested = false
    val hasRootRequest: Boolean get() = rootRequested
    private var rootSucceeded = false
    @Volatile private var generation = 0L
    private val rootCanceller = RootTouchCanceller(context.applicationInfo.sourceDir, result = { success ->
        val request = generation
        scope.launch(Dispatchers.Main) {
            if (!closed && rootRequested && generation == request) {
                rootSucceeded = success
                reportCancellationResult(success)
            }
        }
    })

    fun reportCancellationResult(success: Boolean) {
        if (!success && !failureReported) {
            failureReported = true
            ToastHelper.showInUIThread(context, context.getString(R.string.global_press_cancel_failed))
        }
    }

    fun protectLongPress(stillCurrent: () -> Boolean) {
        if (closed || rootRequested || !stillCurrent()) return
        rootRequested = true
        rootCanceller.start(stillCurrent)
    }

    fun start(rootEnabled: Boolean = false, stillCurrent: () -> Boolean) {
        if (closed || !stillCurrent()) return
        if (current != null) return
        if (rootEnabled) {
            if (rootRequested && !rootSucceeded) {
                generation++
                rootCanceller.stop()
                rootRequested = false
            }
            protectLongPress(stillCurrent)
        }
        val view = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // Consume every touch that starts on the shield, including secondary fingers.
            setOnTouchListener { _, _ -> true }
        }
        val params = WindowManager.LayoutParams().apply {
            configureAccessibilityOverlay(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                title = "GestureEVO drag touch shield",
            )
        }
        val accepted = attachViewSafely(view, params, shouldRetry = { false })
        if (accepted) {
            current = view
            DropDiagnostics.event("drop_shield_attached", "internal", 0)
        } else {
            Log.w("EVO-TouchBlock", "shield_rejected")
            DropDiagnostics.event("drop_shield_failed", "internal", 0, reason = "attach_rejected")
        }
    }

    fun stop() {
        generation++
        rootRequested = false
        rootSucceeded = false
        rootCanceller.stop()
        val view = current ?: return
        current = null
        detachViewSafely(view)
    }

    fun close() {
        closed = true
        stop()
        rootCanceller.close()
    }
}
