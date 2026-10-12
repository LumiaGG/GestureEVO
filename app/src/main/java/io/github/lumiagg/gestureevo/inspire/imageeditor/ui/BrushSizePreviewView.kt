package io.github.lumiagg.gestureevo.inspire.imageeditor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class BrushSizePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
        color = Color.WHITE
    }

    private var brushSizePx = resources.displayMetrics.density * 18f
    private var hollow = false

    fun setBrushPreview(sizePx: Float, color: Int, hollow: Boolean) {
        brushSizePx = sizePx
        fillPaint.color = color
        this.hollow = hollow
        invalidate()
    }

    fun setOutlineColor(color: Int) {
        outlinePaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom
        if (contentWidth <= 0 || contentHeight <= 0) return

        val maxDiameter = min(contentWidth, contentHeight).toFloat()
        val diameter = brushSizePx.coerceAtMost(maxDiameter)
        val radius = diameter / 2f
        val cx = paddingLeft + (contentWidth / 2f)
        val cy = paddingTop + (contentHeight / 2f)

        if (!hollow) {
            canvas.drawCircle(cx, cy, radius, fillPaint)
        }
        canvas.drawCircle(cx, cy, radius, outlinePaint)
    }
}
