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
import com.google.zxing.common.detector.MathUtils.distance
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
    var rect: Rect = Rect()
        private set

    private val dragSelectShrunkRect = RectF()
    private val potentialRectF = RectF()
    private var potentialRect: Rect? = null

    var isOverSize = false
    var isBackToOrigin = false

    fun setStrokeColor(color: Int) = post {
        strokePaint.color = color
        potentialStrokePaint.color = color
        fillPaint.color = Color.argb(46, Color.red(color), Color.green(color), Color.blue(color))
        potentialFillPaint.color = Color.argb(24, Color.red(color), Color.green(color), Color.blue(color))
        if (enable) invalidate()
    }

    fun showPotentialRect(rect: Rect) = post {
        potentialRect = Rect(rect)
        invalidate()
    }

    fun hidePotentialRect() = post {
        potentialRect = null
        invalidate()
    }

    fun shouldUseDragSelection(endPoint: Point): Boolean {
        return kotlin.math.abs(endPoint.x - rect.left) >= DRAG_SELECT_CLICK_THRESHOLD &&
                kotlin.math.abs(endPoint.y - rect.top) >= DRAG_SELECT_CLICK_THRESHOLD
    }

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

    fun updateRect(endPoint: Point) = post {
        val distance = distance(
            rect.left, rect.top,
            endPoint.x.toInt(), endPoint.y.toInt()
        )
        if (!isOverSize) {
            isOverSize = shouldUseDragSelection(endPoint)
        } else {
            isBackToOrigin = distance < 10.dp2px
        }

        rect.set(
            rect.left, rect.top, endPoint.x.toInt(),
            endPoint.y.toInt()
        )
        invalidate()
    }

    fun showRect(startPoint: Point) = post {
        enable = true
        isOverSize = false
        isBackToOrigin = false
        rect.set(
            startPoint.x.toInt(), startPoint.y.toInt(), startPoint.x.toInt(),
            startPoint.y.toInt()
        )
        invalidate()
    }

    fun hideRect() = post {
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
