package io.github.lumiagg.gestureevo.screen_search

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PathEffect
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import io.github.lumiagg.gestureevo.inspire.ui.InspireView.Companion.DRAG_SELECT_PAINT_WIDTH_HALF
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px

class ScreenSearchHighlightView(context: Context) : View(context) {
    private val paint = Paint().apply {
        color = DynamicColorUtils.primaryColor
        style = Paint.Style.STROKE
        strokeWidth = 4.5f.dp2px
        val effects: PathEffect = DashPathEffect(floatArrayOf(7f, 4f, 7f, 4f), 0f)
        pathEffect = effects
        isAntiAlias = true
    }

    private val glowPaint = Paint().apply {
        color = DynamicColorUtils.primaryColor
        style = Paint.Style.STROKE
        strokeWidth = 9f.dp2px
        alpha = 90
        isAntiAlias = true
    }

    private val rects = mutableListOf<Rect>()
    private val drawRect = RectF()

    fun refreshColors() = post {
        val color = DynamicColorUtils.primaryColor
        paint.color = color
        glowPaint.color = color
        invalidate()
    }

    fun showRects(items: List<Rect>) = post {
        rects.clear()
        rects.addAll(items)
        invalidate()
    }

    fun hideRects() = post {
        rects.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rects.isEmpty()) return
        rects.forEach { rect ->
            drawRect.apply {
                left = minOf(rect.left, rect.right) - DRAG_SELECT_PAINT_WIDTH_HALF
                top = minOf(rect.top, rect.bottom) - DRAG_SELECT_PAINT_WIDTH_HALF
                right = maxOf(rect.left, rect.right) + DRAG_SELECT_PAINT_WIDTH_HALF
                bottom = maxOf(rect.top, rect.bottom) + DRAG_SELECT_PAINT_WIDTH_HALF
            }
            canvas.drawRect(drawRect, glowPaint)
            canvas.drawRect(drawRect, paint)
        }
    }
}
