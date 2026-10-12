package io.github.lumiagg.gestureevo.inspire.imageeditor.utils

import androidx.core.graphics.withClip
import androidx.core.graphics.createBitmap

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import io.github.lumiagg.gestureevo.inspire.imageeditor.ui.InspireImageEditorView
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import kotlin.math.max

object InAppCardBlur {

    fun apply(card: MaterialCardView, sourceView: InspireImageEditorView) {
        card.cardElevation = 0f
        card.elevation = 0f
        card.translationZ = 0f
        card.setCardBackgroundColor(Color.TRANSPARENT)

        GlassEffectUtils.applyFixedGlassChrome(card)

        // Remove any existing host
        for (i in 0 until card.childCount) {
            val child = card.getChildAt(i)
            if (child is InAppBlurHostView) {
                card.removeViewAt(i)
                break
            }
        }

        val host = InAppBlurHostView(card.context, card, sourceView)
        card.addView(
            host,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        card.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (host.width != w || host.height != h)) {
                host.layout(0, 0, w, h)
            }
        }

        sourceView.addOnContentChangedListener {
            host.markDirty()
        }
    }

    private class InAppBlurHostView(
        context: Context,
        private val card: MaterialCardView,
        private val sourceView: InspireImageEditorView,
    ) : View(context) {

        private val clipPath = Path()
        private val roundRect = RectF()
        private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val scrimPaint = Paint().apply { style = Paint.Style.FILL }

        private var blurBitmap: Bitmap? = null
        private val lastSampleBounds = Rect()
        private val cardLoc = IntArray(2)
        private val sourceLoc = IntArray(2)
        private val cardBoundsInSource = Rect()
        private var dirty = true

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Must NOT contribute to parent's wrap_content measurement pass!
            setMeasuredDimension(0, 0)
        }

        override fun layout(l: Int, t: Int, r: Int, b: Int) {
            val targetW = card.width.takeIf { it > 0 } ?: (r - l)
            val targetH = card.height.takeIf { it > 0 } ?: (b - t)
            super.layout(0, 0, targetW, targetH)
        }

        fun markDirty() {
            dirty = true
            postInvalidateOnAnimation()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            dirty = true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return

            val radius = card.radius
            roundRect.set(0f, 0f, w.toFloat(), h.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(roundRect, radius, radius, Path.Direction.CW)
            canvas.withClip(clipPath) {
                val sampleW = max(1, w / 3)
                val sampleH = max(1, h / 3)

                card.getLocationOnScreen(cardLoc)
                sourceView.getLocationOnScreen(sourceLoc)
                val relLeft = cardLoc[0] - sourceLoc[0]
                val relTop = cardLoc[1] - sourceLoc[1]
                cardBoundsInSource.set(relLeft, relTop, relLeft + w, relTop + h)

                if (dirty || blurBitmap == null || blurBitmap?.width != sampleW || blurBitmap?.height != sampleH || cardBoundsInSource != lastSampleBounds) {
                    lastSampleBounds.set(cardBoundsInSource)
                    updateBlurBitmap(sampleW, sampleH, cardBoundsInSource)
                    dirty = false
                }

                val bmp = blurBitmap
                if (bmp != null && !bmp.isRecycled) {
                    canvas.drawBitmap(bmp, null, roundRect, filterPaint)
                }

                // Glass scrim overlay
                val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val surfaceColor = DynamicColorUtils.surfaceContainerColor.takeIf { it != 0 }
                    ?: MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurface, Color.DKGRAY)

                val scrimAlpha = if (isDark) 0.30f else 0.22f
                val targetColor = if (!isDark) {
                    ColorUtils.setAlphaComponent(
                        ColorUtils.blendARGB(surfaceColor, Color.WHITE, 0.40f),
                        (scrimAlpha * 255).toInt()
                    )
                } else {
                    ColorUtils.setAlphaComponent(surfaceColor, (scrimAlpha * 255).toInt())
                }
                scrimPaint.color = targetColor
                canvas.drawRect(roundRect, scrimPaint)
            }
        }

        private fun updateBlurBitmap(sampleW: Int, sampleH: Int, bounds: Rect) {
            if (blurBitmap == null || blurBitmap?.width != sampleW || blurBitmap?.height != sampleH) {
                blurBitmap?.takeIf { !it.isRecycled }?.recycle()
                blurBitmap = createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
            }
            val bmp = blurBitmap ?: return

            sourceView.renderSubRegionToBitmap(bounds, bmp)
            FastBlurHelper.blurInPlace(bmp, radius = 5, passes = 2)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            blurBitmap?.takeIf { !it.isRecycled }?.recycle()
            blurBitmap = null
        }
    }
}
