package io.github.lumiagg.gestureevo.drop

import android.util.Log
import android.view.View
import android.view.WindowManager
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.ToastHelper

/** Blocks new touches after Shizuku completes the source application's DOWN/CANCEL sequence. */
internal class BackgroundTouchBlocker(private val context: Context, private val scope: CoroutineScope) :
    BaseFloatingWindow(context, scope, "EVO-TouchBlock") {

    private var current: View? = null
    private var closed = false
    private var failureReported = false
    fun reportCancellationResult(success: Boolean) {
        if (!success && !failureReported) {
            failureReported = true
            ToastHelper.showInUIThread(context, context.getString(R.string.global_press_cancel_failed))
        }
    }

    fun start(stillCurrent: () -> Boolean) {
        if (closed || !stillCurrent() || current != null) return
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
        val view = current ?: return
        current = null
        detachViewSafely(view)
    }

    fun close() {
        closed = true
        stop()
    }
}
