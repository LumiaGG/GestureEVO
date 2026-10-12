package io.github.lumiagg.gestureevo.pin

import io.github.lumiagg.gestureevo.databinding.PinPoolDragSendBarBinding

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.PictureFloating
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Owns an attached WindowManager overlay, never an Activity. hide() releases all
// references on gesture service destruction, timeout and every completed drag.
@SuppressLint("StaticFieldLeak")
object PinDragCancelOverlay {
    private const val MAX_VISIBLE_MS = 60_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bar: View? = null
    private var windowManager: WindowManager? = null
    private var endCallback: (() -> Unit)? = null
    private val timeoutRunnable = Runnable { hide() }

    fun show(context: Context, item: PinPoolItem?, onEnded: () -> Unit) {
        hide(notify = false)
        endCallback = onEnded
        val service = CurrentPackageProvider.accessibilityService()
        val overlayContext = service ?: context.applicationContext
        val themed = DynamicColorUtils.dynamicThemedContext(overlayContext)
        val view = PinPoolDragSendBarBinding.inflate(LayoutInflater.from(themed)).root
        PinDragSend.applyBarTheme(view)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(service != null),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            title = "GestureEVO:PinDragCancel"
        }
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            hide()
        }
        PinDragSend.attachDropZone(
            view.findViewById(R.id.cancelCard),
            0xFFE5484D.toInt(),
            onEnded = { finish() },
        )
        PinDragSend.attachDropZone(
            view.findViewById(R.id.pinCard),
            0xFF5B8DEF.toInt(),
            onDropped = { repin(item) },
            onEnded = { finish() },
        )
        val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.addView(view, params) }.onFailure {
            endCallback = null
            return
        }
        windowManager = wm
        bar = view
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, MAX_VISIBLE_MS)
    }

    fun hide(notify: Boolean = true) {
        mainHandler.removeCallbacks(timeoutRunnable)
        val callback = endCallback
        endCallback = null
        val view = bar
        bar = null
        val wm = windowManager
        windowManager = null
        if (view != null) {
            runCatching { wm?.removeViewImmediate(view) }
        }
        if (notify) {
            callback?.invoke()
        }
    }

    private fun repin(item: PinPoolItem?) {
        val service = CurrentPackageProvider.accessibilityService() as? GestureService ?: return
        val target = item ?: return
        service.launch(Dispatchers.IO) {
            when (target.type) {
                PinType.TEXT -> {
                    val text = target.text?.trim().orEmpty()
                    if (text.isEmpty()) return@launch
                    withContext(Dispatchers.Main) {
                        PictureFloating.build(service, text)
                    }
                }
                PinType.IMAGE -> {
                    val path = target.imagePath ?: return@launch
                    val bitmap = BitmapFactory.decodeFile(path) ?: return@launch
                    val handle = ManagedBitmap.from(bitmap)
                    withContext(Dispatchers.Main) {
                        PictureFloating.build(service, handle)
                    }
                }
            }
        }
    }

    private fun overlayType(fromAccessibility: Boolean): Int {
        if (fromAccessibility) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }
}
