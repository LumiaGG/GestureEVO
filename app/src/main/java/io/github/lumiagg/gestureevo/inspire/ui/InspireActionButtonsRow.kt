package io.github.lumiagg.gestureevo.inspire.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

class InspireActionButtonsRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface OnRotationGestureListener {
        fun canRotate(): Boolean
        fun onRotationGestureStart()
        fun onRotationGestureUpdate(deltaDegrees: Float)
        fun onRotationGestureEnd(currentDegrees: Float, velocityX: Float)
        fun onRotationGestureCancel()
    }

    var rotationGestureListener: OnRotationGestureListener? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialX = 0f
    private var initialY = 0f
    private var isDragging = false
    private var velocityTracker: VelocityTracker? = null

    private val swipeDistanceFor90Degrees: Float
        get() = 100f * resources.displayMetrics.density

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val listener = rotationGestureListener
        if (listener == null || !listener.canRotate()) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = ev.rawX
                initialY = ev.rawY
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = ev.rawX - initialX
                val dy = ev.rawY - initialY
                if (!isDragging) {
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        listener.onRotationGestureStart()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return isDragging
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // Rotation gestures are consumed here; normal taps are handled by the superclass.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val listener = rotationGestureListener
        if (listener == null || !listener.canRotate()) return super.onTouchEvent(ev)

        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = ev.rawX
                initialY = ev.rawY
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - initialX
                val dy = ev.rawY - initialY
                if (!isDragging) {
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        listener.onRotationGestureStart()
                    }
                }
                if (isDragging) {
                    val deltaDegrees = (dx / swipeDistanceFor90Degrees) * 90f
                    listener.onRotationGestureUpdate(deltaDegrees)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    val dx = ev.rawX - initialX
                    val deltaDegrees = (dx / swipeDistanceFor90Degrees) * 90f
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    listener.onRotationGestureEnd(deltaDegrees, velocityX)
                    isDragging = false
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    listener.onRotationGestureCancel()
                    isDragging = false
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return super.onTouchEvent(ev)
    }
}
