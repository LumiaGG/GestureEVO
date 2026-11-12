package io.github.lumiagg.gestureevo.floating

import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.util.Log
import android.view.View
import android.view.WindowManager
import io.github.lumiagg.gestureevo.utils.postOnMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.IdentityHashMap

abstract class BaseFloatingWindow(
    context: Context,
    private val coroutineScope: CoroutineScope,
    private val logTag: String,
) {
    protected val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val attachments = WindowAttachmentRegistry<View>()
    private val attachRetryJobs = IdentityHashMap<View, Job>()
    private val attachListeners = IdentityHashMap<View, View.OnAttachStateChangeListener>()

    protected fun WindowManager.LayoutParams.configureAccessibilityOverlay(
        flags: Int,
        width: Int = WindowManager.LayoutParams.MATCH_PARENT,
        height: Int = WindowManager.LayoutParams.MATCH_PARENT,
        gravity: Int = Gravity.START or Gravity.TOP,
        format: Int = PixelFormat.RGBA_8888,
        x: Int = 0,
        y: Int = 0,
        allowDisplayCutout: Boolean = true,
        title: String? = null,
    ) {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        this.format = format
        this.flags = flags
        this.width = width
        this.height = height
        this.gravity = gravity
        this.x = x
        this.y = y
        title?.let(::setTitle)
        if (allowDisplayCutout && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** Returns WM request acceptance; onAttached runs only after actual View attachment. */
    protected fun attachViewSafely(
        view: View,
        layoutParams: WindowManager.LayoutParams,
        retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
        shouldRetry: () -> Boolean = { isAttachRequested(view) },
        onAttached: () -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ): Boolean {
        attachRetryJobs.remove(view)?.cancel()
        removeAttachListener(view)
        val request = attachments.request(view) {
            removeAttachListener(view)
            onAttached()
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { attachments.attached(view, request) }
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        attachListeners[view] = listener
        view.addOnAttachStateChangeListener(listener)
        return runCatching {
            submitAttach(view, layoutParams, request)
        }.onFailure { err ->
            AppLogger.event(
                LogModule.OVERLAY, "overlay_attach_failed",
                LogLevel.WARN, fields = mapOf("source" to logTag), error = err)
            Log.d(logTag, "addView failed: $err")
            onFailure(err)
            if (attachments.isCurrent(view, request)) {
                attachRetryJobs[view] = coroutineScope.postOnMain {
                    delay(retryDelayMillis)
                    if (!attachments.isCurrent(view, request)) return@postOnMain
                    attachRetryJobs.remove(view)
                    if (shouldRetry()) runCatching { submitAttach(view, layoutParams, request) }
                        .onFailure { Log.d(logTag, "addView retry failed: $it") }
                }
            }
        }.getOrDefault(false)
    }

    protected fun addViewSafely(
        view: View,
        layoutParams: WindowManager.LayoutParams,
        onAttached: () -> Unit = {},
    ): Boolean = attachViewSafely(view, layoutParams, onAttached = onAttached)

    protected fun detachViewSafely(
        view: View,
        immediate: Boolean = true,
        onDetached: () -> Unit = {},
    ): Boolean {
        attachRetryJobs.remove(view)?.cancel()
        removeAttachListener(view)
        return runCatching {
            attachments.detach(view, { view.isAttachedToWindow }) {
                if (immediate) windowManager.removeViewImmediate(view) else windowManager.removeView(view)
            }
            io.github.lumiagg.gestureevo.drop.SourceTouchRegions.detached(view)
            onDetached()
        }.onFailure { err ->
            Log.d(logTag, "removeView failed: $err")
        }.isSuccess
    }

    protected fun safeRemoveView(
        view: View,
        immediate: Boolean = true,
        onDetached: () -> Unit = {},
    ): Boolean = detachViewSafely(view, immediate, onDetached)

    protected fun updateViewLayoutSafely(
        view: View,
        layoutParams: WindowManager.LayoutParams,
    ): Boolean {
        if (!view.isAttachedToWindow) return false
        return runCatching {
            windowManager.updateViewLayout(view, layoutParams)
        }.onFailure { err ->
            Log.d(logTag, "updateViewLayout failed: $err")
        }.isSuccess
    }

    protected fun isAttachRequested(view: View): Boolean = attachments.isRequested(view)

    protected fun clearAttachRequest(view: View) {
        attachments.cancelRequest(view)
        attachRetryJobs.remove(view)?.cancel()
        removeAttachListener(view)
    }

    private fun removeAttachListener(view: View) {
        attachListeners.remove(view)?.let(view::removeOnAttachStateChangeListener)
    }

    private fun submitAttach(view: View, layoutParams: WindowManager.LayoutParams,
        request: WindowAttachmentRegistry.Request): Boolean {
        val accepted = attachments.submit(view, request, { view.isAttachedToWindow }) {
            windowManager.addView(view, layoutParams)
        }
        if (accepted) io.github.lumiagg.gestureevo.drop.SourceTouchRegions.attached(view)
        return accepted
    }

    private companion object {
        const val DEFAULT_RETRY_DELAY_MILLIS = 200L
    }
}
