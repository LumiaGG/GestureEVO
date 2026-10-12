package io.github.lumiagg.gestureevo.pointer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PointerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    init {
        LayoutInflater.from(context).inflate(R.layout.gesture_action_pointer_view, this, true)
    }

    private val circlePaint = Paint().apply {
        color = DynamicColorUtils.primaryColor
        style = Paint.Style.STROKE
        strokeWidth = 4f.dp2px
        isAntiAlias = true
    }

    private val progressArcPaint = Paint().apply {
        color = DynamicColorUtils.primaryColor
        style = Paint.Style.STROKE
        strokeWidth = 4f.dp2px
        isAntiAlias = true
    }

    var progress: Float = 0f
        private set
    private var showCircle = false
    private var progressJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    var progressEndCallback = {}

    var circleRadius = 12f.dp2px
        set(value) {
            field = value
            invalidate()
        }

    fun stopProgress() {
        progressJob?.cancel()
        progress = 0f
        showCircle = false
        invalidate()
    }

    fun startProgress(timeMs: Long) {
        progressJob?.cancel()
        progress = 0f
        showCircle = true
        progressJob = coroutineScope.launch {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                val currentTime = System.currentTimeMillis()
                val elapsed = currentTime - startTime
                progress = (elapsed.toFloat() / timeMs).coerceIn(0f, 1f)
                if (progress == 1f) {
                    progressEndCallback()
                }
                invalidate()

                if (elapsed >= timeMs) {
                    break
                }
                // Use small frame delay for smooth animation
                delay(16) // ~60fps
            }
        }
    }


    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        coroutineScope.cancel()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (showCircle) {
            // Calculate center point of the view
            val centerX = width / 2f
            val centerY = height / 2f

            // Draw background circle
            canvas.drawCircle(centerX, centerY, circleRadius, circlePaint)

            // Draw progress arc
            val arcRect = RectF(
                centerX - circleRadius,
                centerY - circleRadius,
                centerX + circleRadius,
                centerY + circleRadius
            )
            canvas.drawArc(
                arcRect,
                -90f,
                progress * 360f,
                false,
                progressArcPaint
            )
        }
    }
}
