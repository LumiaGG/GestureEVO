package io.github.lumiagg.gestureevo.settings.guide

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils

class GuideGestureDemoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drawingBounds = RectF()
    private var gesture = QuickGesture.SWIPE_IN
    private var side = QuickSetupSide.RIGHT
    private var fraction = 0f
    private var animator: ValueAnimator? = null

    fun show(gesture: QuickGesture, side: QuickSetupSide) {
        this.gesture = gesture
        this.side = side
        invalidate()
    }

    fun startPoint(): PointF {
        val scale = minOf(width / 80f, height / 128f)
        return PointF(
            (width - 80f * scale) / 2f + (if (side == QuickSetupSide.LEFT) 16f else 64f) * scale,
            (height - 128f * scale) / 2f + 70f * scale
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 80f, height / 128f)
        canvas.save()
        canvas.translate((width - 80f * scale) / 2f, (height - 128f * scale) / 2f)
        canvas.scale(scale, scale)
        paint.color = DynamicColorUtils.surfaceContainerHighColor
        paint.style = Paint.Style.FILL
        drawingBounds.set(8f, 3f, 72f, 125f)
        canvas.drawRoundRect(drawingBounds, 12f, 12f, paint)
        paint.color = DynamicColorUtils.outlineColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(drawingBounds, 12f, 12f, paint)
        paint.style = Paint.Style.FILL
        paint.color = DynamicColorUtils.surfaceContainerColor
        drawingBounds.set(15f, 19f, 65f, 108f)
        canvas.drawRoundRect(drawingBounds, 5f, 5f, paint)
        paint.color = DynamicColorUtils.primaryColor
        val left = side == QuickSetupSide.LEFT
        val startX = if (left) 16f else 64f
        paint.alpha = 75
        drawingBounds.set(startX - 3f, 52f, startX + 3f, 88f)
        canvas.drawRoundRect(drawingBounds, 3f, 3f, paint)
        val t = (fraction / 0.72f).coerceIn(0f, 1f)
        val endX = if (gesture == QuickGesture.SWIPE_IN) 40f else startX
        val endY = when (gesture) {
            QuickGesture.SWIPE_UP -> 33f
            QuickGesture.SWIPE_DOWN -> 104f
            else -> 70f
        }
        val x = startX + (endX - startX) * t
        val y = 70f + (endY - 70f) * t
        paint.strokeWidth = 6f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(startX, 70f, x, y, paint)
        paint.alpha = 255
        canvas.drawCircle(x, y, 4.5f, paint)
        if (gesture == QuickGesture.TAP || gesture == QuickGesture.LONG_PRESS) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.alpha = ((1f - fraction) * 180).toInt()
            canvas.drawCircle(x, y, 5f + fraction * 8f, paint)
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        canvas.restore()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1800L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (!isVisible) {
            animator?.cancel()
            animator = null
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
