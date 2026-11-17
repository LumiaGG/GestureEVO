// 来源：LinkGo（github.com/moting1117/LinkGo，作者 mting1117/墨汀，All Rights Reserved），
// 经作者授权移植至本 GPL-3.0-only 项目；按本仓库架构适配（窗口类型、设置来源、键盘避让等）。

package io.github.lumiagg.gestureevo.clipboard

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Global IME visibility and height, sourced from the accessibility service.
 *
 * Modelled on the OctoClip KeyboardMonitor: one throttled event source, discrete
 * `(isShown, imeHeight)` output, no polling. The service binds itself here; the capsule only
 * listens, so window placement survives activity-handler timing differences.
 */
object ImeInsetsManager {
    private const val TAG = "ImeInsetsManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(isShown: Boolean, imeHeight: Int) -> Unit>()

    private var serviceRef = WeakReference<AccessibilityService>(null)

    @Volatile
    var isKeyboardShown: Boolean = false
        private set

    @Volatile
    var currentImeHeight: Int = 0
        private set

    private var lastEventTime: Long = 0L

    fun bind(service: AccessibilityService?) {
        serviceRef = WeakReference(service)
    }

    /** Registers a listener; an already-known state is delivered once on the main thread. */
    fun addListener(listener: (isShown: Boolean, imeHeight: Int) -> Unit) {
        listeners.add(listener)
        mainHandler.post { listener(isKeyboardShown, currentImeHeight) }
    }

    fun removeListener(listener: (isShown: Boolean, imeHeight: Int) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Current IME height: WindowMetrics insets first (API 30+), accessibility windows as fallback.
     */
    fun queryCurrentImeHeight(): Int {
        val service = serviceRef.get()
        var detectedHeight = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && service != null) {
            try {
                val wm = service.getSystemService(WindowManager::class.java)
                val windowInsets = wm?.currentWindowMetrics?.windowInsets
                val isVisible = windowInsets?.isVisible(WindowInsets.Type.ime()) ?: false
                if (!isVisible) return 0
                val insets = windowInsets?.getInsets(WindowInsets.Type.ime())
                val h = insets?.bottom ?: 0
                if (h > 0) detectedHeight = h
            } catch (e: Exception) {
                Log.w(TAG, "WindowMetrics IME query failed: ${e.message}")
            }
        }

        if (detectedHeight <= 0 && service != null) {
            try {
                val rect = Rect()
                val screenH = service.resources.displayMetrics.heightPixels
                for (window in service.windows ?: emptyList()) {
                    if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        window.getBoundsInScreen(rect)
                        val h = rect.height()
                        // Skip a still-visible window that is already sliding off-screen.
                        if (h > 200 && rect.top < screenH) {
                            detectedHeight = h
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility window IME query failed: ${e.message}")
            }
        }

        return if (detectedHeight > 200) detectedHeight else 0
    }

    /** Called from the accessibility event stream (TYPE_WINDOWS_CHANGED), throttled. */
    fun onAccessibilityWindowsChanged() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEventTime < 30L) return
        lastEventTime = now

        val height = queryCurrentImeHeight()
        val shown = height > 0
        if (shown == isKeyboardShown && height == currentImeHeight) return

        isKeyboardShown = shown
        currentImeHeight = height
        if (listeners.isEmpty()) return
        mainHandler.post {
            listeners.forEach { listener ->
                try {
                    listener(shown, height)
                } catch (e: Exception) {
                    Log.e(TAG, "IME listener failed", e)
                }
            }
        }
    }
}
