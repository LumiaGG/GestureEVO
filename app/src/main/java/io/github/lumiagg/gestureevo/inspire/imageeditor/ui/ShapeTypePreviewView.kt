package io.github.lumiagg.gestureevo.inspire.imageeditor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.ShapeType
import kotlin.math.max
import kotlin.math.min

class ShapeTypePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = resources.displayMetrics.density * 2.1f
        color = Color.WHITE
    }
    private val arrowPath = Path()
    private val polygonPath = Path()
    private val bounds = RectF()
    private var shapeType = ShapeType.RECTANGLE

    fun setShapePreview(type: ShapeType, color: Int) {
        shapeType = type
        shapePaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return

        val inset = shapePaint.strokeWidth
        bounds.set(left + inset, top + inset, right - inset, bottom - inset)
        when (shapeType) {
            ShapeType.RECTANGLE -> canvas.drawRect(bounds, shapePaint)
            ShapeType.ROUNDED_RECTANGLE ->
                canvas.drawRoundRect(bounds, bounds.width() * 0.16f, bounds.height() * 0.16f, shapePaint)
            ShapeType.OVAL -> canvas.drawOval(bounds, shapePaint)
            ShapeType.LINE -> canvas.drawLine(bounds.left, bounds.bottom, bounds.right, bounds.top, shapePaint)
            ShapeType.ARROW -> drawArrow(canvas, bounds)
            ShapeType.DIAMOND -> drawDiamond(canvas, bounds)
            ShapeType.TRIANGLE -> drawTriangle(canvas, bounds)
        }
    }

    private fun drawArrow(canvas: Canvas, bounds: RectF) {
        arrowPath.reset()
        val startX = bounds.left + bounds.width() * 0.06f
        val startY = bounds.bottom - bounds.height() * 0.18f
        val endX = bounds.right - bounds.width() * 0.04f
        val endY = bounds.top + bounds.height() * 0.18f
        val dx = endX - startX
        val dy = endY - startY
        val distance = kotlin.math.sqrt((dx * dx) + (dy * dy)).coerceAtLeast(1f)
        val unitX = dx / distance
        val unitY = dy / distance
        val headLength = max(min(bounds.width(), bounds.height()) * 0.34f, shapePaint.strokeWidth * 3f)
        val wingX = -unitY
        val wingY = unitX

        arrowPath.moveTo(startX, startY)
        arrowPath.lineTo(endX, endY)
        arrowPath.moveTo(endX, endY)
        arrowPath.lineTo(
            endX - (unitX * headLength) + (wingX * headLength * 0.45f),
            endY - (unitY * headLength) + (wingY * headLength * 0.45f),
        )
        arrowPath.moveTo(endX, endY)
        arrowPath.lineTo(
            endX - (unitX * headLength) - (wingX * headLength * 0.45f),
            endY - (unitY * headLength) - (wingY * headLength * 0.45f),
        )
        canvas.drawPath(arrowPath, shapePaint)
    }

    private fun drawDiamond(canvas: Canvas, bounds: RectF) {
        polygonPath.reset()
        polygonPath.moveTo(bounds.centerX(), bounds.top)
        polygonPath.lineTo(bounds.right, bounds.centerY())
        polygonPath.lineTo(bounds.centerX(), bounds.bottom)
        polygonPath.lineTo(bounds.left, bounds.centerY())
        polygonPath.close()
        canvas.drawPath(polygonPath, shapePaint)
    }

    private fun drawTriangle(canvas: Canvas, bounds: RectF) {
        polygonPath.reset()
        polygonPath.moveTo(bounds.centerX(), bounds.top)
        polygonPath.lineTo(bounds.right, bounds.bottom)
        polygonPath.lineTo(bounds.left, bounds.bottom)
        polygonPath.close()
        canvas.drawPath(polygonPath, shapePaint)
    }
}
