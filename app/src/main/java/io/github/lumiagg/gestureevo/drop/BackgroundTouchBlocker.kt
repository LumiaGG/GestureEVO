package io.github.lumiagg.gestureevo.drop

import android.util.Log
import android.view.View
import android.view.WindowManager
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.GestureService

/**
 * Full-screen transparent sink attached while a press drag is shown. Window touch targets are
 * fixed at DOWN, so the shield cannot recall the stream already in flight; it only swallows
 * touches that START while it is up (secondary fingers, stray taps). No synthetic gesture is
 * dispatched here: injected streams neither survive real touch events (MotionEventInjector
 * cancels them) nor end the app's own stream - on-device testing (OnePlus PJA110, API 36) showed
 * shield-targeted strokes landing without stopping the background scroll, and the retired 1ms
 * probe only "worked" when it fell through to the app and briefly handed it a second pointer.
 * The in-flight background scroll stays a known limitation without root; see
 * "全局拖拽非 Root 背景触摸遮罩" in local-docs/development-notes.md.
 */
internal class BackgroundTouchBlocker(private val service: GestureService) :
    BaseFloatingWindow(service, service, "EVO-TouchBlock") {

    private var current: View? = null
    private var closed = false

    fun start(stillCurrent: () -> Boolean) {
        if (closed || !stillCurrent()) return
        if (current != null) return
        val view = View(service).apply {
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
