package io.github.lumiagg.gestureevo.gesture.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.imageview.ShapeableImageView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.utils.Point
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

interface TriggerCallBack {
    fun onGesture(
        triggerView: TriggerView,
        gestureTypeEvent: GestureTypeEvent,
        currentDirection: GestureType,
        event: MotionEvent
    ) {
    }
}

// Basic gesture types
enum class GestureType {
    TOUCH,          // Basic touch
    SINGLE_CLICK,   // Single click
    DOUBLE_CLICK,   // Double click
    LONG_PRESS,     // Long press
    SWIPE_LEFT,     // Swipe left
    SWIPE_RIGHT,    // Swipe right
    SWIPE_UP,       // Swipe up
    SWIPE_DOWN,     // Swipe down
    SWIPE_OBLIQUE_UP,    // Diagonal swipe up
    SWIPE_OBLIQUE_DOWN;  // Diagonal swipe down

    fun showName(context: Context): String {
        return when (this) {
            TOUCH -> context.getString(R.string.gesture_type_touch)
            SINGLE_CLICK -> context.getString(R.string.gesture_type_single_click)
            DOUBLE_CLICK -> context.getString(R.string.gesture_type_double_click)
            LONG_PRESS -> context.getString(R.string.gesture_type_long_press)
            SWIPE_LEFT -> context.getString(R.string.gesture_type_swipe_left)
            SWIPE_RIGHT -> context.getString(R.string.gesture_type_swipe_right)
            SWIPE_UP -> context.getString(R.string.gesture_type_swipe_up)
            SWIPE_DOWN -> context.getString(R.string.gesture_type_swipe_down)
            SWIPE_OBLIQUE_UP -> context.getString(R.string.gesture_type_swipe_oblique_up)
            SWIPE_OBLIQUE_DOWN -> context.getString(R.string.gesture_type_swipe_oblique_down)
        }
    }
}

// Gesture phases
enum class GesturePhase {
    TD,       // Trigger Down - gesture triggered
    RD,       // Release Done - gesture released
    SP,
    BACK;     // Back - gesture reverse swipe

    fun showName(context: Context): String {
        return when (this) {
            TD -> context.getString(R.string.gesture_phase_trigger)
            RD -> context.getString(R.string.gesture_phase_release)
            BACK -> context.getString(R.string.gesture_phase_return)
            SP -> context.getString(R.string.gesture_phase_pause)
        }
    }
}

// Composite gesture event
data class GestureTypeEvent(
    val type: GestureType,
    val phase: GesturePhase
) {
    fun showFullName(context: Context): String {
        return "${type.showName(context)}${phase.showName(context)}"
    }

    fun toActionKey(): String {
        return toActionKey(phase)
    }

    fun toActionKey(gesturePhase: GesturePhase): String {
        return "trigger_${type.name}_${gesturePhase.name}_action"
    }
}

class TriggerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ShapeableImageView(context, attrs, defStyleAttr) {

    var triggerCallBack = object : TriggerCallBack {}
    var triggerConfig = FreeTriggerConfig()
    var enabledOrientation = true
    var enabledPackage = true
    var isMirror = false

    private val exclusionRects = mutableListOf<Rect>()
    private val gestureHandler = Handler(Looper.getMainLooper())
    private val tempLocation = IntArray(2)

    init {
        // Set layout parameters
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Set background color
        setBackgroundColor(ContextCompat.getColor(context, R.color.blue))
        setOnTouchListener(OptimizedGestureListener())

        // Initialize exclusion regions
        updateExclusionRects()
    }

    private fun updateExclusionRects() {
        exclusionRects.clear()
        exclusionRects.add(Rect(0, 0, width, height))
    }

    data class GestureState(
        var startX: Float = 0f,
        var startY: Float = 0f,
        var lastX: Float = 0f,
        var lastY: Float = 0f,
        var pauseAnchorX: Float = 0f,
        var pauseAnchorY: Float = 0f,
        var currentGestureType: GestureType = GestureType.TOUCH,
        var hasSwipeTriggered: Boolean = false,
        var hasBackTriggered: Boolean = false,
        var isLongPressActive: Boolean = false,
        var hasPauseTriggered: Boolean = false,
        var directionReversalDetected: Boolean = false,
        var reversalStartX: Float = 0f,
        var reversalStartY: Float = 0f,
        var gestureStartTime: Long = 0L,
        var activePointerId: Int = MotionEvent.INVALID_POINTER_ID,
        var initialDxSign: Int = 0,
        var initialDySign: Int = 0,
        var hasExceededSwipeThreshold: Boolean = false,
        var hasRearmedSwipe: Boolean = false,
        var awaitingDoubleTap: Boolean = false,
        var lastTapUpTime: Long = 0L,
        var lastTapUpX: Float = 0f,
        var lastTapUpY: Float = 0f
    )

    inner class OptimizedGestureListener : OnTouchListener {
        // Gesture state management
        private val gestureState = GestureState()

        // Optimized gesture parameters
        private val backResetThreshold = 35f.dp2px
        private val reversalDetectThreshold = 6f.dp2px
        private val rearmThreshold = 28f.dp2px
        private val angleThreshold = 30f  // Angle threshold in degrees
        private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
        private val longPressCancelThreshold = 35.dp2px
        private val pauseMovementThreshold = 8f.dp2px
        private var pauseScheduled = false

        private var cachedLongPressTimeout = 0L
        private var cachedDoubleTapTimeout = 0L
        private var cachedPauseTimeout = 0L
        private var cachedSwipeThreshold = 0f
        private var cachedBackThreshold = 0f

        init {
            updateTimingAndThresholds()
        }

        fun updateTimingAndThresholds() {
            val timing = triggerConfig.gestureTimingConfig
            cachedLongPressTimeout = timing.resolvedLongPressTimeoutMs()
            cachedDoubleTapTimeout = timing.resolvedDoubleTapTimeoutMs()
            cachedPauseTimeout = timing.resolvedPauseTimeoutMs()
            cachedSwipeThreshold = timing.resolvedSwipeThresholdPx()
            cachedBackThreshold = timing.resolvedBackThresholdPx()
        }

        private fun longPressTimeout(): Long = cachedLongPressTimeout

        private fun doubleTapTimeout(): Long = cachedDoubleTapTimeout

        private fun pauseTimeout(): Long = cachedPauseTimeout

        private fun swipeThreshold(): Float = cachedSwipeThreshold

        private fun backThreshold(): Float = cachedBackThreshold

        // Long press detection runnable
        private val longPressRunnable = Runnable {
            if (!gestureState.hasSwipeTriggered && !gestureState.hasBackTriggered &&
                !gestureState.isLongPressActive
            ) {
                gestureState.isLongPressActive = true
                val gestureTypeEvent = GestureTypeEvent(GestureType.LONG_PRESS, GesturePhase.TD)
                triggerCallBack.onGesture(
                    this@TriggerView,
                    gestureTypeEvent,
                    gestureState.currentGestureType,
                    createMotionEvent(gestureState.startX, gestureState.startY)
                    )
            }
        }

        private val pauseRunnable = Runnable {
            if (!gestureState.hasPauseTriggered &&
                gestureState.hasSwipeTriggered &&
                !gestureState.hasBackTriggered &&
                !gestureState.isLongPressActive
            ) {
                gestureState.hasPauseTriggered = true
                val pauseEvent = GestureTypeEvent(
                    gestureState.currentGestureType,
                    GesturePhase.SP
                )
                triggerCallBack.onGesture(
                    this@TriggerView,
                    pauseEvent,
                    gestureState.currentGestureType,
                    createMotionEvent(
                        gestureState.lastX,
                        gestureState.lastY,
                        MotionEvent.ACTION_MOVE
                    )
                )
                pauseScheduled = false
            }
        }

        private val singleClickRunnable = Runnable {
            val clickEvent = GestureTypeEvent(GestureType.SINGLE_CLICK, GesturePhase.TD)
            triggerCallBack.onGesture(
                this@TriggerView,
                clickEvent,
                gestureState.currentGestureType,
                createMotionEvent(
                    gestureState.lastTapUpX,
                    gestureState.lastTapUpY,
                    MotionEvent.ACTION_UP
                )
            )
            gestureState.awaitingDoubleTap = false
        }

        // Helper to construct MotionEvent
        private fun createMotionEvent(x: Float, y: Float, action: Int = MotionEvent.ACTION_DOWN): MotionEvent {
            val downTime = System.currentTimeMillis()
            getLocationOnScreen(tempLocation)
            val screenX = tempLocation[0] + x
            val screenY = tempLocation[1] + y
            val event = MotionEvent.obtain(downTime, downTime, action, screenX, screenY, 0)
            event.offsetLocation(-tempLocation[0].toFloat(), -tempLocation[1].toFloat())
            return event
        }

        private fun schedulePauseIfNeeded(currentX: Float, currentY: Float) {
            if (!gestureState.hasSwipeTriggered || gestureState.hasBackTriggered || gestureState.hasPauseTriggered) {
                return
            }
            val deltaX = currentX - gestureState.pauseAnchorX
            val deltaY = currentY - gestureState.pauseAnchorY
            val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
            val shouldRestart = !pauseScheduled || distance > pauseMovementThreshold
            if (!shouldRestart) {
                return
            }
            gestureHandler.removeCallbacks(pauseRunnable)
            gestureHandler.postDelayed(pauseRunnable, pauseTimeout())
            pauseScheduled = true
            gestureState.pauseAnchorX = currentX
            gestureState.pauseAnchorY = currentY
        }

        // Direction detection algorithm
        private fun detectSwipeDirection(deltaX: Float, deltaY: Float): GestureType {
            val swipeThreshold = swipeThreshold()
            val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
            if (distance < swipeThreshold) {
                return GestureType.TOUCH
            }

            val angle = Math.toDegrees(atan2(-deltaY.toDouble(), deltaX.toDouble())).toFloat()
            val normalizedAngle = if (angle < 0) angle + 360 else angle

            if (triggerConfig.gestureAngleMap.isNotEmpty()) {
                for ((type, angleRange) in triggerConfig.gestureAngleMap) {
                    when (type) {
                        GestureType.SWIPE_RIGHT -> {
                            if (normalizedAngle <= angleRange.e || normalizedAngle >= angleRange.s) {
                                return type
                            }
                        }

                        GestureType.SWIPE_OBLIQUE_UP, GestureType.SWIPE_OBLIQUE_DOWN -> {
                            if (normalizedAngle >= angleRange.s && normalizedAngle <= angleRange.e) {
                                return type
                            }
                            var symmetricStartAngle = 180f - angleRange.e
                            var symmetricEndAngle = 180f - angleRange.s
                            if (symmetricStartAngle < 0) {
                                symmetricStartAngle += 360
                            }
                            if (symmetricEndAngle < 0) {
                                symmetricEndAngle += 360
                            }
                            if (normalizedAngle >= symmetricStartAngle && normalizedAngle <= symmetricEndAngle) {
                                return type
                            }
                        }

                        else -> {
                            if (normalizedAngle >= angleRange.s && normalizedAngle <= angleRange.e) {
                                return type
                            }
                        }
                    }
                }
            } else {
                val defaultDirection = when {
                    (normalizedAngle <= angleThreshold) || (normalizedAngle >= 360 - angleThreshold) ->
                        GestureType.SWIPE_RIGHT
                    normalizedAngle >= 90 - angleThreshold && normalizedAngle <= 90 + angleThreshold ->
                        GestureType.SWIPE_UP
                    normalizedAngle >= 180 - angleThreshold && normalizedAngle <= 180 + angleThreshold ->
                        GestureType.SWIPE_LEFT
                    normalizedAngle >= 270 - angleThreshold && normalizedAngle <= 270 + angleThreshold ->
                        GestureType.SWIPE_DOWN
                    normalizedAngle > angleThreshold && normalizedAngle < 180 - angleThreshold ->
                        GestureType.SWIPE_OBLIQUE_UP
                    else -> GestureType.SWIPE_OBLIQUE_DOWN
                }
                return defaultDirection
            }

            val absX = abs(deltaX)
            val absY = abs(deltaY)
            val axisDominant = absX >= absY * 1.25f || absY >= absX * 1.25f
            return when {
                axisDominant && absX >= absY -> if (deltaX >= 0) GestureType.SWIPE_RIGHT else GestureType.SWIPE_LEFT
                axisDominant -> if (deltaY <= 0) GestureType.SWIPE_UP else GestureType.SWIPE_DOWN
                else -> if (deltaY <= 0) GestureType.SWIPE_OBLIQUE_UP else GestureType.SWIPE_OBLIQUE_DOWN
            }
        }

        // Detect direction reversal
        private fun detectDirectionReversal(currentX: Float, currentY: Float): Boolean {
            if (!gestureState.hasSwipeTriggered || gestureState.directionReversalDetected) {
                return false
            }

            val deltaX = currentX - gestureState.lastX
            val deltaY = currentY - gestureState.lastY
            val moved = sqrt(deltaX * deltaX + deltaY * deltaY)
            if (moved < reversalDetectThreshold) {
                return false
            }

            val dxSign = deltaX.compareTo(0f)
            val dySign = deltaY.compareTo(0f)

            val isReversed = when (gestureState.currentGestureType) {
                GestureType.SWIPE_LEFT, GestureType.SWIPE_RIGHT -> dxSign != 0 && dxSign != gestureState.initialDxSign
                GestureType.SWIPE_UP, GestureType.SWIPE_DOWN -> dySign != 0 && dySign != gestureState.initialDySign
                GestureType.SWIPE_OBLIQUE_UP, GestureType.SWIPE_OBLIQUE_DOWN -> dySign != 0 && dySign != gestureState.initialDySign
                else -> false
            }

            if (isReversed) {
                gestureState.directionReversalDetected = true
                gestureState.reversalStartX = currentX
                gestureState.reversalStartY = currentY
            }

            return isReversed
        }

        // Detect back gesture
        private fun detectBackGesture(currentX: Float, currentY: Float): Boolean {
            if (!gestureState.hasSwipeTriggered || gestureState.hasBackTriggered ||
                !gestureState.directionReversalDetected
            ) {
                return false
            }

            val deltaX = abs(currentX - gestureState.reversalStartX)
            val deltaY = abs(currentY - gestureState.reversalStartY)
            val threshold = backThreshold()

            return when (gestureState.currentGestureType) {
                GestureType.SWIPE_LEFT, GestureType.SWIPE_RIGHT -> deltaX > threshold
                GestureType.SWIPE_UP, GestureType.SWIPE_DOWN,
                GestureType.SWIPE_OBLIQUE_UP, GestureType.SWIPE_OBLIQUE_DOWN -> deltaY > threshold

                else -> false
            }
        }

        // Reset gesture state
        private fun resetGestureState(
            clearPendingSingleClick: Boolean = false,
            clearDoubleTapState: Boolean = false
        ) {
            gestureState.apply {
                hasSwipeTriggered = false
                hasBackTriggered = false
                isLongPressActive = false
                hasPauseTriggered = false
                directionReversalDetected = false
                currentGestureType = GestureType.TOUCH
                gestureStartTime = 0L
                pauseAnchorX = 0f
                pauseAnchorY = 0f
                activePointerId = MotionEvent.INVALID_POINTER_ID
                initialDxSign = 0
                initialDySign = 0
                hasExceededSwipeThreshold = false
                hasRearmedSwipe = false
                if (clearDoubleTapState) {
                    awaitingDoubleTap = false
                    lastTapUpTime = 0L
                    lastTapUpX = 0f
                    lastTapUpY = 0f
                }
            }
            gestureHandler.removeCallbacks(longPressRunnable)
            gestureHandler.removeCallbacks(pauseRunnable)
            if (clearPendingSingleClick) {
                gestureHandler.removeCallbacks(singleClickRunnable)
            }
            pauseScheduled = false
        }

        private fun hasDoubleClickActionConfigured(): Boolean {
            val key = GestureTypeEvent(GestureType.DOUBLE_CLICK, GesturePhase.TD).toActionKey()
            val action = triggerConfig.getGestureAction(key)
            return action?.firstStageAction != null && action.firstStageAction != GestureActionType.NONE
        }

        private fun withinDoubleTapWindow(event: MotionEvent): Boolean {
            if (!gestureState.awaitingDoubleTap) return false
            if (event.eventTime - gestureState.lastTapUpTime > doubleTapTimeout()) return false
            val dx = event.x - gestureState.lastTapUpX
            val dy = event.y - gestureState.lastTapUpY
            return sqrt(dx * dx + dy * dy) <= doubleTapSlop
        }

        private fun resetForNewGesture(currentX: Float, currentY: Float): Boolean {
            if (!gestureState.hasBackTriggered) {
                return false
            }
            val deltaX = currentX - gestureState.startX
            val deltaY = currentY - gestureState.startY
            val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
            if (distance > backResetThreshold) {
                return false
            }
            val activePointerId = gestureState.activePointerId
            resetGestureState()
            gestureState.apply {
                startX = currentX
                startY = currentY
                lastX = currentX
                lastY = currentY
                gestureStartTime = System.currentTimeMillis()
                this.activePointerId = activePointerId
            }
            gestureHandler.postDelayed(longPressRunnable, longPressTimeout())
            return true
        }

        private fun maybeRearmSwipe(currentX: Float, currentY: Float, distanceFromStart: Float): Boolean {
            if (!gestureState.hasSwipeTriggered ||
                gestureState.hasBackTriggered ||
                gestureState.isLongPressActive ||
                gestureState.hasRearmedSwipe
            ) {
                return false
            }
            if (!gestureState.hasExceededSwipeThreshold) return false
            if (distanceFromStart > rearmThreshold) {
                return false
            }

            gestureState.apply {
                hasSwipeTriggered = false
                directionReversalDetected = false
                hasPauseTriggered = false
                currentGestureType = GestureType.TOUCH
                initialDxSign = 0
                initialDySign = 0
                startX = currentX
                startY = currentY
                lastX = currentX
                lastY = currentY
                pauseAnchorX = currentX
                pauseAnchorY = currentY
                hasRearmedSwipe = true
            }
            gestureHandler.removeCallbacks(pauseRunnable)
            pauseScheduled = false
            return true
        }

        private fun resolveActivePointerIndex(event: MotionEvent): Int {
            val activeId = gestureState.activePointerId
            val index = if (activeId != MotionEvent.INVALID_POINTER_ID) {
                event.findPointerIndex(activeId)
            } else {
                -1
            }
            return if (index >= 0) index else 0
        }

        private fun setActivePointerFromEvent(event: MotionEvent, index: Int) {
            gestureState.activePointerId = event.getPointerId(index)
            gestureState.lastX = event.getX(index)
            gestureState.lastY = event.getY(index)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (!isActivated) return false

            // Always report raw touch event
            triggerCallBack.onGesture(
                this@TriggerView,
                GestureTypeEvent(GestureType.TOUCH, GesturePhase.TD),
                gestureState.currentGestureType,
                event
            )

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    updateTimingAndThresholds()
                    resetGestureState(clearPendingSingleClick = false, clearDoubleTapState = false)
                    val index = event.actionIndex
                    setActivePointerFromEvent(event, index)
                    gestureState.startX = event.getX(index)
                    gestureState.startY = event.getY(index)
                    gestureState.gestureStartTime = event.eventTime

                    // Start long press detection
                    gestureHandler.postDelayed(longPressRunnable, longPressTimeout())
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (gestureState.activePointerId == MotionEvent.INVALID_POINTER_ID) {
                        setActivePointerFromEvent(event, event.actionIndex)
                        gestureState.startX = gestureState.lastX
                        gestureState.startY = gestureState.lastY
                        gestureState.gestureStartTime = event.eventTime
                        gestureHandler.postDelayed(longPressRunnable, longPressTimeout())
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val index = resolveActivePointerIndex(event)
                    val currentX = event.getX(index)
                    val currentY = event.getY(index)
                    val deltaX = currentX - gestureState.startX
                    val deltaY = currentY - gestureState.startY
                    val swipeThreshold = swipeThreshold()
                    val distance = sqrt(deltaX * deltaX + deltaY * deltaY)

                    // Cancel long press detection if displacement exceeds threshold
                    if (!gestureState.isLongPressActive &&
                        !gestureState.hasSwipeTriggered &&
                        distance > longPressCancelThreshold
                    ) {
                        gestureHandler.removeCallbacks(longPressRunnable)
                    }
                    if (distance > swipeThreshold) {
                        gestureState.hasExceededSwipeThreshold = true
                    }

                    // Detect direction reversal
                    detectDirectionReversal(currentX, currentY)

                    // Detect swipe gesture
                    if (!gestureState.hasSwipeTriggered && distance > swipeThreshold) {
                        gestureState.currentGestureType = detectSwipeDirection(deltaX, deltaY)

                        if (gestureState.currentGestureType != GestureType.TOUCH) {
                            gestureState.hasSwipeTriggered = true
                            val gestureTypeEvent = GestureTypeEvent(
                                gestureState.currentGestureType,
                                GesturePhase.TD
                            )
                            triggerCallBack.onGesture(
                                this@TriggerView,
                                gestureTypeEvent,
                                gestureState.currentGestureType,
                                event
                            )
                        }
                        gestureState.initialDxSign = deltaX.compareTo(0f)
                        gestureState.initialDySign = deltaY.compareTo(0f)
                    }

                    // Detect back gesture
                    if (detectBackGesture(currentX, currentY)) {
                        gestureState.hasBackTriggered = true
                        val backEvent = GestureTypeEvent(
                            gestureState.currentGestureType,
                            GesturePhase.BACK
                        )
                        triggerCallBack.onGesture(
                            this@TriggerView,
                            backEvent,
                            gestureState.currentGestureType,
                            event
                        )
                        gestureHandler.removeCallbacks(pauseRunnable)
                        pauseScheduled = false
                    }

                    if (maybeRearmSwipe(currentX, currentY, distance)) {
                        return true
                    }

                    if (resetForNewGesture(currentX, currentY)) {
                        return true
                    }

                    schedulePauseIfNeeded(currentX, currentY)

                    gestureState.lastX = currentX
                    gestureState.lastY = currentY
                }

                MotionEvent.ACTION_UP -> {
                    if (gestureState.activePointerId != MotionEvent.INVALID_POINTER_ID &&
                        event.getPointerId(event.actionIndex) != gestureState.activePointerId
                    ) {
                        return true
                    }

                    gestureHandler.removeCallbacks(longPressRunnable)
                    gestureHandler.removeCallbacks(pauseRunnable)
                    pauseScheduled = false

                    // Detect single click if no other gestures were triggered
                    if (!gestureState.hasSwipeTriggered &&
                        !gestureState.isLongPressActive &&
                        !gestureState.hasExceededSwipeThreshold
                    ) {
                        val timeDiff = event.eventTime - gestureState.gestureStartTime
                        val swipeThreshold = swipeThreshold()
                        val distance = sqrt(
                            (event.x - gestureState.startX) * (event.x - gestureState.startX) +
                                    (event.y - gestureState.startY) * (event.y - gestureState.startY)
                        )

                        if (timeDiff < longPressTimeout() && distance < swipeThreshold) {
                            if (hasDoubleClickActionConfigured()) {
                                if (withinDoubleTapWindow(event)) {
                                    gestureHandler.removeCallbacks(singleClickRunnable)
                                    val gestureTypeEvent = GestureTypeEvent(
                                        GestureType.DOUBLE_CLICK,
                                        GesturePhase.TD
                                    )
                                    triggerCallBack.onGesture(
                                        this@TriggerView,
                                        gestureTypeEvent,
                                        gestureState.currentGestureType,
                                        event
                                    )
                                    gestureState.awaitingDoubleTap = false
                                } else {
                                    gestureState.awaitingDoubleTap = true
                                    gestureState.lastTapUpTime = event.eventTime
                                    gestureState.lastTapUpX = event.x
                                    gestureState.lastTapUpY = event.y
                                    gestureHandler.removeCallbacks(singleClickRunnable)
                                    gestureHandler.postDelayed(singleClickRunnable, doubleTapTimeout())
                                }
                            } else {
                                val gestureTypeEvent = GestureTypeEvent(
                                    GestureType.SINGLE_CLICK,
                                    GesturePhase.TD
                                )
                                triggerCallBack.onGesture(
                                    this@TriggerView,
                                    gestureTypeEvent,
                                    gestureState.currentGestureType,
                                    event
                                )
                            }
                        }
                    }

                    // Fire corresponding release event
                    when {
                        gestureState.isLongPressActive -> {
                            val releaseEvent = GestureTypeEvent(
                                GestureType.LONG_PRESS,
                                GesturePhase.RD
                            )
                            triggerCallBack.onGesture(
                                this@TriggerView,
                                releaseEvent,
                                gestureState.currentGestureType,
                                event
                            )
                        }

                        gestureState.hasSwipeTriggered && !gestureState.hasBackTriggered -> {
                            val releaseEvent = GestureTypeEvent(
                                gestureState.currentGestureType,
                                GesturePhase.RD
                            )
                            triggerCallBack.onGesture(
                                this@TriggerView,
                                releaseEvent,
                                gestureState.currentGestureType,
                                event
                            )
                        }
                    }

                    resetGestureState(
                        clearPendingSingleClick = false,
                        clearDoubleTapState = !gestureState.awaitingDoubleTap
                    )
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerId = event.getPointerId(event.actionIndex)
                    if (pointerId == gestureState.activePointerId) {
                        val newIndex = if (event.pointerCount > 1) {
                            if (event.actionIndex == 0) 1 else 0
                        } else {
                            -1
                        }
                        if (newIndex >= 0) {
                            setActivePointerFromEvent(event, newIndex)
                        } else {
                            resetGestureState(
                                clearPendingSingleClick = false,
                                clearDoubleTapState = !gestureState.awaitingDoubleTap
                            )
                        }
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    gestureHandler.removeCallbacks(longPressRunnable)
                    gestureHandler.removeCallbacks(pauseRunnable)
                    gestureHandler.removeCallbacks(singleClickRunnable)
                    pauseScheduled = false
                    resetGestureState(
                        clearPendingSingleClick = true,
                        clearDoubleTapState = true
                    )
                }
            }

            return true
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            updateExclusionRects()
            updateSystemGestureExclusion()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateSystemGestureExclusion()
    }

    private fun updateSystemGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exclusionRects.isNotEmpty()) {
            exclusionRects[0].set(0, 0, width, height)
            systemGestureExclusionRects = exclusionRects
        }
    }

    fun getViewCenterPoint(): Point {
        getLocationOnScreen(tempLocation)
        return Point(
            (tempLocation[0] + width / 2).toFloat(),
            (tempLocation[1] + height / 2).toFloat()
        )
    }

    fun getTouchPointOnScreen(event: MotionEvent): Point {
        getLocationOnScreen(tempLocation)
        val localX = event.x.coerceIn(0f, width.toFloat())
        val localY = event.y.coerceIn(0f, height.toFloat())
        return Point(tempLocation[0] + localX, tempLocation[1] + localY)
    }
}
