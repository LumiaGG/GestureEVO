package io.github.lumiagg.gestureevo.drop

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_INSPIRE_SELECTION_VIBRATE
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** MMGG DragShareController preview adapted to EVO's themed overlay and URI bitmap ownership. */
internal class DragContentPreview(private val service: GestureService) : BaseFloatingWindow(service, service, "EVO-PressPreview") {
    private val theme = DynamicColorUtils.dynamicThemedContext(service)
    private val card = MaterialCardView(theme).apply {
        radius = 12.dp2px
        cardElevation = 4.dp2px
        preventCornerOverlap = false
        minimumWidth = 0
        minimumHeight = 0
        clipToOutline = true
        setCardBackgroundColor(DynamicColorUtils.surfaceContainerHighColor)
        GlassEffectUtils.applyGlassEdge(this)
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val image = ImageView(theme).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val params = WindowManager.LayoutParams().apply {
        configureAccessibilityOverlay(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            gravity = Gravity.TOP or Gravity.LEFT, title = "GestureEVO:ExternalDrop:ContentPreview")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
    }
    private var generation = 0
    private var active = false
    private var notified = false
    private var job: Job? = null
    private var bitmap: Bitmap? = null

    suspend fun show(content: ClipData, x: Float, y: Float, screen: Rect): Boolean {
        clear()
        if (content.itemCount == 0 || screen.isEmpty) return false
        val item = content.getItemAt(0)
        val text = item.text?.takeIf { it.isNotBlank() }
        val uri = item.uri
        if (text == null && uri == null) return false
        active = true
        val request = generation
        val margin = 16.dp2px.toInt()
        val maxWidth = minOf(200.dp2px.toInt(), (screen.width() - margin).coerceAtLeast(1))
        val maxHeight = (screen.height() - margin).coerceAtLeast(1)
        card.removeAllViews()
        if (text != null) {
            card.addView(TextView(theme).apply {
                this.text = DragContentPreviewPolicy.summary(text)
                setTextColor(DynamicColorUtils.onSurfaceColor)
                textSize = 13f
                includeFontPadding = false
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp2px.toInt(), 8.dp2px.toInt(), 12.dp2px.toInt(), 8.dp2px.toInt())
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.measure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
            )
            params.width = card.measuredWidth.coerceIn(1, maxWidth)
            params.height = card.measuredHeight.coerceIn(1, maxHeight)
        } else {
            params.width = minOf(64.dp2px.toInt(), maxWidth)
            params.height = minOf(64.dp2px.toInt(), maxHeight)
            image.setImageResource(io.github.lumiagg.gestureevo.R.drawable.baseline_image_24)
            card.addView(image, ViewGroup.LayoutParams(-1, -1))
        }
        move(x, y, screen)
        val attached = kotlinx.coroutines.CompletableDeferred<Unit>()
        if (!attachViewSafely(card, params, shouldRetry = { false }, onAttached = { attached.complete(Unit) })) {
            clear()
            return false
        }
        try {
            if (kotlinx.coroutines.withTimeoutOrNull(1500) { attached.await(); true } != true ||
                !active || generation != request) {
                if (generation == request) clear()
                return false
            }
        } catch (cancelled: CancellationException) {
            if (generation == request) clear()
            throw cancelled
        }
        job = service.launch(Dispatchers.Main) {
            var decoded: Bitmap? = null
            try {
                if (active && generation == request && !notified && service.settingsStore.readKey(KEY_INSPIRE_SELECTION_VIBRATE, true)) {
                    if (active && generation == request) {
                        notified = true
                        VibratorUtils.vibrator(service, false)
                    }
                }
                if (text == null && uri != null) {
                    withContext(Dispatchers.IO) {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        service.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                        if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext
                        options.inJustDecodeBounds = false
                        val limit = 128.dp2px.toInt().coerceAtLeast(1)
                        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize.coerceAtLeast(1) > limit) {
                            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                        }
                        decoded = service.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    }
                    if (active && request == generation && decoded != null) {
                        image.setImageBitmap(decoded)
                        bitmap = decoded
                        decoded = null
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                android.util.Log.w("EVO-PressPreview", "thumbnail_unavailable generation=$request")
            } finally { decoded?.recycle() }
        }
        return true
    }

    fun move(x: Float, y: Float, screen: Rect) {
        if (!active) return
        val safe = Rect(screen).apply { inset(8.dp2px.toInt(), 8.dp2px.toInt()) }
        val point = DragContentPreviewPolicy.position(x, y, params.width, params.height,
            DragContentPreviewPolicy.Box(safe.left, safe.top, safe.right, safe.bottom), 20.dp2px.toInt())
        params.x = point.x
        params.y = point.y
        updateViewLayoutSafely(card, params)
    }

    fun clear() {
        generation++
        active = false
        notified = false
        job?.cancel()
        job = null
        detachViewSafely(card)
        image.setImageDrawable(null)
        card.removeAllViews()
        bitmap?.recycle()
        bitmap = null
    }
}
