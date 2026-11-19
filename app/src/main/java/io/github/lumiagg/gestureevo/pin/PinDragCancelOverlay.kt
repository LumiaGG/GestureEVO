package io.github.lumiagg.gestureevo.pin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.PictureFloating
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("StaticFieldLeak")
object PinDragCancelOverlay {
    private const val MAX_VISIBLE_MS = 60_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var targets: PinDragTargets? = null
    private var endCallback: (() -> Unit)? = null
    private val timeoutRunnable = Runnable { hide() }

    fun show(
        context: Context,
        scope: CoroutineScope,
        position: String,
        item: PinPoolItem,
        onEnded: () -> Unit,
    ) {
        hide(notify = false)
        val service = CurrentPackageProvider.accessibilityService()
        val overlayContext = service ?: context.applicationContext
        endCallback = onEnded
        val created = PinDragTargets(
            context = overlayContext,
            scope = scope,
            position = position,
            windowType = overlayType(service != null),
            title = "GestureEVO:PinDragCancel",
            onRepin = { repin(item) },
            onEnded = { hide() },
        )
        if (created.show()) {
            targets = created
        }
        mainHandler.postDelayed(timeoutRunnable, MAX_VISIBLE_MS)
    }

    fun hide(notify: Boolean = true) {
        mainHandler.removeCallbacks(timeoutRunnable)
        val callback = endCallback
        endCallback = null
        targets?.hide()
        targets = null
        if (notify) callback?.invoke()
    }

    private fun repin(item: PinPoolItem) {
        val service = CurrentPackageProvider.accessibilityService() as? GestureService ?: return
        service.launch(Dispatchers.IO) {
            when (item.type) {
                PinType.TEXT -> {
                    val text = item.text?.trim().orEmpty()
                    if (text.isEmpty()) return@launch
                    withContext(Dispatchers.Main) {
                        PictureFloating.build(service, text)
                    }
                }
                PinType.IMAGE -> {
                    val path = item.imagePath ?: return@launch
                    val bitmap = BitmapFactory.decodeFile(path) ?: return@launch
                    val handle = ManagedBitmap.from(bitmap)
                    withContext(Dispatchers.Main) {
                        PictureFloating.build(service, handle)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun overlayType(fromAccessibility: Boolean): Int = when {
        fromAccessibility -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else -> WindowManager.LayoutParams.TYPE_PHONE
    }
}
