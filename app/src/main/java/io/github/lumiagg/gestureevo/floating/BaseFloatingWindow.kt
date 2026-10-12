package io.github.lumiagg.gestureevo.floating

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
import java.util.Collections
import java.util.IdentityHashMap

abstract class BaseFloatingWindow(
    context: Context,
    private val coroutineScope: CoroutineScope,
    private val logTag: String,
) {
    protected val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val attachRequests = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    private val attachRetryJobs = IdentityHashMap<View, Job>()

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

    protected fun attachViewSafely(
        view: View,
        layoutParams: WindowManager.LayoutParams,
        retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
        shouldRetry: () -> Boolean = { isAttachRequested(view) },
        onAttached: () -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ): Boolean {
        attachRequests.add(view)
        attachRetryJobs.remove(view)?.cancel()
        if (view.isAttachedToWindow) {
            onAttached()
            return true
        }

        return runCatching {
            windowManager.addView(view, layoutParams)
            onAttached()
        }.onFailure { err ->
            Log.d(logTag, "addView failed: $err")
            onFailure(err)
            attachRetryJobs[view] = coroutineScope.postOnMain {
                delay(retryDelayMillis)
                attachRetryJobs.remove(view)
                retryAttachView(view, layoutParams, shouldRetry, onAttached)
            }
        }.isSuccess
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
        attachRequests.remove(view)
        attachRetryJobs.remove(view)?.cancel()
        if (!view.isAttachedToWindow) {
            onDetached()
            return true
        }

        return runCatching {
            if (immediate) {
                windowManager.removeViewImmediate(view)
            } else {
                windowManager.removeView(view)
            }
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

    protected fun isAttachRequested(view: View): Boolean = attachRequests.contains(view)

    protected fun clearAttachRequest(view: View) {
        attachRequests.remove(view)
        attachRetryJobs.remove(view)?.cancel()
    }

    private fun retryAttachView(
        view: View,
        layoutParams: WindowManager.LayoutParams,
        shouldRetry: () -> Boolean,
        onAttached: () -> Unit,
    ) {
        if (view.isAttachedToWindow) {
            onAttached()
            return
        }
        if (!shouldRetry()) return

        runCatching {
            windowManager.addView(view, layoutParams)
            onAttached()
        }.onFailure { retryErr ->
            Log.d(logTag, "addView retry failed: $retryErr")
        }
    }

    private companion object {
        const val DEFAULT_RETRY_DELAY_MILLIS = 200L
    }
}
