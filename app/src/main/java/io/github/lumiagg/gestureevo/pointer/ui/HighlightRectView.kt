package io.github.lumiagg.gestureevo.pointer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PathEffect
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.annotation.MainThread
import io.github.lumiagg.gestureevo.pointer.PointerDragSelection
import io.github.lumiagg.gestureevo.inspire.ui.InspireView.Companion.DRAG_SELECT_CLICK_THRESHOLD
import io.github.lumiagg.gestureevo.inspire.ui.InspireView.Companion.DRAG_SELECT_PAINT_WIDTH_HALF
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.Point
import io.github.lumiagg.gestureevo.utils.dp2px

class HighlightRectView(context: Context) : View(context) {
    private val strokePaint = Paint().apply {
        color = DynamicColorUtils.primaryColor
        style = Paint.Style.STROKE
        strokeWidth = 3f.dp2px
        val effects: PathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
        pathEffect = effects
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val potentialStrokePaint = Paint().apply {
        color = DynamicColorUtils.primaryColor
        style = Paint.Style.STROKE
        strokeWidth = 2f.dp2px
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val potentialFillPaint = Paint().apply {
        color = DynamicColorUtils.primaryColor
        style = Paint.Style.FILL
        alpha = 24
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = DynamicColorUtils.primaryColor
        style = Paint.Style.FILL
        alpha = 46
        isAntiAlias = true
    }

    var enable = false
    val rect: Rect = Rect()
    private val selection = PointerDragSelection(DRAG_SELECT_CLICK_THRESHOLD, 10.dp2px)

    private val dragSelectShrunkRect = RectF()
    private val potentialRectF = RectF()
    private var potentialRect: Rect? = null

    val isBackToOrigin: Boolean get() = selection.isBackToOrigin

    fun setStrokeColor(color: Int) = post {
        strokePaint.color = color
        potentialStrokePaint.color = color
        fillPaint.color = Color.argb(46, Color.red(color), Color.green(color), Color.blue(color))
        potentialFillPaint.color = Color.argb(24, Color.red(color), Color.green(color), Color.blue(color))
        if (enable || potentialRect != null) invalidate()
    }

    @MainThread
    fun showPotentialRect(rect: Rect) {
        potentialRect = Rect(rect)
        invalidate()
    }

    @MainThread
    fun hidePotentialRect() {
        potentialRect = null
        invalidate()
    }

    fun shouldUseDragSelection(endPoint: Point): Boolean = selection.shouldUseDragSelection(endPoint)

    fun currentSelectionRect(endPoint: Point): Rect {
        val endX = endPoint.x.toInt()
        val endY = endPoint.y.toInt()
        return Rect(
            minOf(rect.left, endX),
            minOf(rect.top, endY),
            maxOf(rect.left, endX),
            maxOf(rect.top, endY)
        )
    }

    @MainThread
    fun updateRect(endPoint: Point) {
        selection.update(endPoint)
        syncSelectionRect()
    }

    @MainThread
    fun showRect(startPoint: Point, endPoint: Point = startPoint) {
        enable = true
        selection.begin(startPoint, endPoint)
        syncSelectionRect()
    }

    private fun syncSelectionRect() {
        rect.set(selection.start.x.toInt(), selection.start.y.toInt(), selection.end.x.toInt(), selection.end.y.toInt())
        invalidate()
    }

    @MainThread
    fun hideRect() {
        enable = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        potentialRect?.let { rect ->
            potentialRectF.set(
                rect.left.toFloat(),
                rect.top.toFloat(),
                rect.right.toFloat(),
                rect.bottom.toFloat()
            )
            // A menu's vertical-only or horizontal-only preview still needs visible geometry.
            if (potentialRectF.width() == 0f) potentialRectF.inset(-DRAG_SELECT_PAINT_WIDTH_HALF, 0f)
            if (potentialRectF.height() == 0f) potentialRectF.inset(0f, -DRAG_SELECT_PAINT_WIDTH_HALF)
            canvas.drawRoundRect(potentialRectF, 8f.dp2px, 8f.dp2px, potentialFillPaint)
            canvas.drawRoundRect(potentialRectF, 8f.dp2px, 8f.dp2px, potentialStrokePaint)
        }
        if (enable) {
            // Calculate top-left and bottom-right coordinates of the rectangle
            dragSelectShrunkRect.apply {
                left = minOf(rect.left, rect.right) - DRAG_SELECT_PAINT_WIDTH_HALF
                top = minOf(rect.top, rect.bottom) - DRAG_SELECT_PAINT_WIDTH_HALF
                right = maxOf(rect.left, rect.right) + DRAG_SELECT_PAINT_WIDTH_HALF
                bottom = maxOf(rect.top, rect.bottom) + DRAG_SELECT_PAINT_WIDTH_HALF
            }
            canvas.drawRoundRect(dragSelectShrunkRect, 8f.dp2px, 8f.dp2px, fillPaint)
            canvas.drawRoundRect(dragSelectShrunkRect, 8f.dp2px, 8f.dp2px, strokePaint)
        }
    }
}
