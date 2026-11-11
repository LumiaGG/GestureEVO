package io.github.lumiagg.gestureevo.drop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.GestureService

internal class BackgroundTouchBlocker(private val service: GestureService) :
    BaseFloatingWindow(service, service, "EVO-TouchBlock") {
    private class Session(val view: View, val stillCurrent: () -> Boolean) {
        private var stopped = false
        private var injecting = false
        private var confirmed = false
        val canRemove: Boolean get() = stopped && !injecting
        val canRetry: Boolean get() = !stopped && !injecting && !confirmed && attempt < 3

        fun beginInjection(): Boolean {
            if (!canRetry) return false
            attempt++
            injecting = true
            return true
        }

        fun injectionFinished(consumed: Boolean) {
            injecting = false
            confirmed = confirmed || consumed
        }

        fun stop() { stopped = true }

        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        var timeout: Runnable? = null
        var settle: Runnable? = null
        var touchStarted = false
        var touchEnded = false
        var attempt = 0
        val startedAt = SystemClock.uptimeMillis()
    }

    private val main = Handler(Looper.getMainLooper())
    private var current: Session? = null
    private var closed = false
    private var pending: (() -> Boolean)? = null

    fun start(stillCurrent: () -> Boolean) {
        if (closed || !stillCurrent()) return
        if (current != null) {
            pending = stillCurrent
            DropDiagnostics.event("drop_shield_queued", "internal", 0)
            return
        }
        val view = View(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val session = Session(view, stillCurrent)
        current = session
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) session.touchStarted = true
            if (session.touchStarted && (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL)) session.touchEnded = true
            true
        }
        val params = WindowManager.LayoutParams().apply {
            configureAccessibilityOverlay(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                title = "GestureEVO drag touch shield",
            )
        }
        session.preDraw = ViewTreeObserver.OnPreDrawListener {
            if (view.isAttachedToWindow && view.width > 0 && view.height > 0) {
                removePreDraw(session)
                if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                    view.viewTreeObserver.registerFrameCommitCallback { view.post { inject(session) } }
                } else {
                    view.postOnAnimation { view.postOnAnimation { inject(session) } }
                }
            }
            true
        }.also(view.viewTreeObserver::addOnPreDrawListener)
        session.timeout = Runnable {
            if (current === session) {
                Log.w("EVO-TouchBlock", "shield_timeout")
                DropDiagnostics.event("drop_shield_failed", "internal", session.attempt, reason = "layout_timeout")
                session.stop()
                removeIfFinished(session)
            }
        }.also { main.postDelayed(it, 1500) }
        if (!attachViewSafely(view, params, shouldRetry = { false })) stop()
    }

    private fun inject(session: Session) {
        if (current !== session) return
        if (closed || !session.stillCurrent() || !session.view.isAttachedToWindow) {
            session.stop()
            removeIfFinished(session)
            return
        }
        if (!session.beginInjection()) return
        session.touchStarted = false
        session.touchEnded = false
        DropDiagnostics.event("drop_shield_inject", "internal", session.attempt,
            reason = "elapsed_${SystemClock.uptimeMillis() - session.startedAt}",
            width = session.view.width, height = session.view.height)
        session.timeout?.let(main::removeCallbacks)
        session.timeout = Runnable {
            if (current === session) {
                // Keep the sink until the system replies; a queued gesture must not outlive it.
                Log.w("EVO-TouchBlock", "gesture_result_delayed")
                DropDiagnostics.event("drop_shield_failed", "internal", session.attempt, reason = "result_delayed")
            }
        }.also { main.postDelayed(it, 1500) }
        val position = IntArray(2)
        session.view.getLocationOnScreen(position)
        val x = position[0] + session.view.width / 2f
        val y = position[1] + session.view.height / 2f
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 1),
        ).build()
        val accepted = try {
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = settle(session, "completed")
                override fun onCancelled(gestureDescription: GestureDescription?) = settle(session, "cancelled")
            }, main)
        } catch (error: Exception) {
            Log.w("EVO-TouchBlock", "gesture_dispatch_failed", error)
            false
        }
        if (!accepted) settle(session, "rejected")
    }

    private fun settle(session: Session, result: String) {
        if (current !== session) return
        session.timeout?.let(main::removeCallbacks)
        Log.i("EVO-TouchBlock", "gesture_$result consumed=${session.touchEnded}")
        // shortcut: allow 100ms for an unobserved terminal event; revisit if device traces show slower delivery.
        session.settle = Runnable {
            val consumed = session.touchStarted && session.touchEnded
            session.injectionFinished(consumed)
            DropDiagnostics.event("drop_shield_result", "internal", session.attempt,
                reason = result, flags = if (consumed) 1 else 0)
            if (session.canRetry) inject(session)
            else {
                if (!consumed) Log.w("EVO-TouchBlock", "shield_unconfirmed attempts=${session.attempt}")
                removeIfFinished(session)
            }
        }.also { main.postDelayed(it, if (session.touchEnded) 0 else 100) }
    }

    fun stop() {
        pending = null
        current?.let { session ->
            session.stop()
            removeIfFinished(session)
        }
    }

    private fun removeIfFinished(session: Session) {
        if (current !== session || !session.canRemove) return
        session.timeout?.let(main::removeCallbacks)
        session.settle?.let(main::removeCallbacks)
        removePreDraw(session)
        detachViewSafely(session.view)
        current = null
        val next = pending
        pending = null
        if (next != null) start(next)
    }

    private fun removePreDraw(session: Session) {
        session.preDraw?.let { listener ->
            if (session.view.viewTreeObserver.isAlive) session.view.viewTreeObserver.removeOnPreDrawListener(listener)
        }
        session.preDraw = null
    }

    fun close() {
        closed = true
        stop()
    }
}
