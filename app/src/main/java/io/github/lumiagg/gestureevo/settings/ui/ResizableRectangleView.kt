package io.github.lumiagg.gestureevo.settings.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlin.math.abs
import com.google.android.material.R as MaterialR

class ResizableRectangleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnRectangleChangedListener {
        fun onRectangleChanged(rect: RectF)
        fun onHideButtonClicked()
    }

    private var rectangle = RectF(200f, 400f, 800f, 1200f)
    private var listener: OnRectangleChangedListener? = null

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.dp2px
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.dp2px
        color = Color.WHITE
    }

    private val buttonRect = RectF()
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14.dp2px
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val handleRadius = 7.dp2px
    private val minSize = 120.dp2px
    private val buttonWidth = 96.dp2px
    private val buttonHeight = 40.dp2px
    private val rectCornerRadius = 16.dp2px
    private val buttonCornerRadius = 20.dp2px

    private var currentRegion = REGION_NONE
    private var lastX = 0f
    private var lastY = 0f
    private var isButtonPressed = false

    companion object {
        const val REGION_NONE = -1
        const val REGION_CENTER = 0
        const val REGION_LEFT = 1
        const val REGION_TOP = 2
        const val REGION_RIGHT = 3
        const val REGION_BOTTOM = 4
        const val REGION_TOP_LEFT = 5
        const val REGION_TOP_RIGHT = 6
        const val REGION_BOTTOM_LEFT = 7
        const val REGION_BOTTOM_RIGHT = 8
    }

    private val touchTolerance = 40.dp2px

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        applyThemeColors()

        canvas.drawRoundRect(rectangle, rectCornerRadius, rectCornerRadius, fillPaint)
        canvas.drawRoundRect(rectangle, rectCornerRadius, rectCornerRadius, borderPaint)

        drawHandle(canvas, rectangle.left, rectangle.top)
        drawHandle(canvas, rectangle.right, rectangle.top)
        drawHandle(canvas, rectangle.left, rectangle.bottom)
        drawHandle(canvas, rectangle.right, rectangle.bottom)

        val centerX = rectangle.centerX()
        val centerY = rectangle.centerY()
        buttonRect.set(
            centerX - buttonWidth / 2,
            centerY - buttonHeight / 2,
            centerX + buttonWidth / 2,
            centerY + buttonHeight / 2
        )
        canvas.drawRoundRect(buttonRect, buttonCornerRadius, buttonCornerRadius, buttonPaint)

        val textY =
            buttonRect.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2
        canvas.drawText(
            context.getString(R.string.resizable_rect_hide),
            buttonRect.centerX(),
            textY,
            buttonTextPaint
        )
    }

    private fun applyThemeColors() {
        val primary = DynamicColorUtils.primaryColor.takeIf { it != 0 }
            ?: MaterialColors.getColor(this, android.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(this, MaterialR.attr.colorOnPrimary)
        borderPaint.color = primary
        handlePaint.color = primary
        fillPaint.color = ColorUtils.setAlphaComponent(primary, 0x24)
        buttonPaint.color = if (isButtonPressed) {
            ColorUtils.blendARGB(primary, Color.BLACK, 0.16f)
        } else {
            primary
        }
        buttonTextPaint.color = onPrimary
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y

                // Check whether button was clicked
                if (buttonRect.contains(x, y)) {
                    isButtonPressed = true
                    invalidate()
                    return true
                }

                currentRegion = getTouchedRegion(x, y)
                return currentRegion != REGION_NONE
            }

            MotionEvent.ACTION_MOVE -> {
                if (isButtonPressed) return true
                if (currentRegion == REGION_NONE) return false

                val dx = x - lastX
                val dy = y - lastY

                when (currentRegion) {
                    REGION_CENTER -> rectangle.offset(dx, dy)
                    REGION_LEFT -> rectangle.left =
                        (rectangle.left + dx).coerceAtMost(rectangle.right - minSize)

                    REGION_RIGHT -> rectangle.right =
                        (rectangle.right + dx).coerceAtLeast(rectangle.left + minSize)

                    REGION_TOP -> rectangle.top =
                        (rectangle.top + dy).coerceAtMost(rectangle.bottom - minSize)

                    REGION_BOTTOM -> rectangle.bottom =
                        (rectangle.bottom + dy).coerceAtLeast(rectangle.top + minSize)

                    REGION_TOP_LEFT -> {
                        rectangle.left =
                            (rectangle.left + dx).coerceAtMost(rectangle.right - minSize)
                        rectangle.top =
                            (rectangle.top + dy).coerceAtMost(rectangle.bottom - minSize)
                    }

                    REGION_TOP_RIGHT -> {
                        rectangle.right =
                            (rectangle.right + dx).coerceAtLeast(rectangle.left + minSize)
                        rectangle.top =
                            (rectangle.top + dy).coerceAtMost(rectangle.bottom - minSize)
                    }

                    REGION_BOTTOM_LEFT -> {
                        rectangle.left =
                            (rectangle.left + dx).coerceAtMost(rectangle.right - minSize)
                        rectangle.bottom =
                            (rectangle.bottom + dy).coerceAtLeast(rectangle.top + minSize)
                    }

                    REGION_BOTTOM_RIGHT -> {
                        rectangle.right =
                            (rectangle.right + dx).coerceAtLeast(rectangle.left + minSize)
                        rectangle.bottom =
                            (rectangle.bottom + dy).coerceAtLeast(rectangle.top + minSize)
                    }
                }

                lastX = x
                lastY = y
                invalidate()
                listener?.onRectangleChanged(rectangle)
            }

            MotionEvent.ACTION_UP -> {
                if (isButtonPressed) {
                    isButtonPressed = false
                    invalidate()
                    performClick()
                    listener?.onHideButtonClicked()
                    this.visibility = GONE // Hidden by default
                    return true
                }
                currentRegion = REGION_NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                isButtonPressed = false
                currentRegion = REGION_NONE
                invalidate()
            }
        }
        return true
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadius, handlePaint)
        canvas.drawCircle(x, y, handleRadius, handleRingPaint)
    }

    private fun getTouchedRegion(x: Float, y: Float): Int {
        val points = listOf(
            REGION_TOP_LEFT to PointF(rectangle.left, rectangle.top),
            REGION_TOP_RIGHT to PointF(rectangle.right, rectangle.top),
            REGION_BOTTOM_LEFT to PointF(rectangle.left, rectangle.bottom),
            REGION_BOTTOM_RIGHT to PointF(rectangle.right, rectangle.bottom)
        )
        for ((region, p) in points) {
            if (abs(x - p.x) <= touchTolerance && abs(y - p.y) <= touchTolerance) return region
        }
        if (abs(x - rectangle.left) <= touchTolerance && y in rectangle.top..rectangle.bottom) return REGION_LEFT
        if (abs(x - rectangle.right) <= touchTolerance && y in rectangle.top..rectangle.bottom) return REGION_RIGHT
        if (abs(y - rectangle.top) <= touchTolerance && x in rectangle.left..rectangle.right) return REGION_TOP
        if (abs(y - rectangle.bottom) <= touchTolerance && x in rectangle.left..rectangle.right) return REGION_BOTTOM
        if (rectangle.contains(x, y)) return REGION_CENTER
        return REGION_NONE
    }

    fun setOnRectangleChangedListener(listener: OnRectangleChangedListener) {
        this.listener = listener
    }

    fun setRectangle(rect: RectF) {
        rectangle.set(rect)
        invalidate()
    }

    private val Float.dp: Float
        get() = this * context.resources.displayMetrics.density
}
