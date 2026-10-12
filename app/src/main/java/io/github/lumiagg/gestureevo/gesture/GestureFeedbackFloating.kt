package io.github.lumiagg.gestureevo.gesture

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.EdgeSide
import io.github.lumiagg.gestureevo.gesture.ui.FluidRippleView
import io.github.lumiagg.gestureevo.gesture.ui.GestureFluidAppearance
import io.github.lumiagg.gestureevo.gesture.ui.GesturePhase
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import io.github.lumiagg.gestureevo.gesture.ui.GestureTypeEvent
import io.github.lumiagg.gestureevo.utils.postOnMain
import kotlinx.coroutines.delay

class GestureFeedbackFloating(private val gestureService: GestureService) :
    BaseFloatingWindow(gestureService, gestureService, TAG) {
    companion object {
        const val TAG = "GestureFeedbackFloating"
        private val DEFAULT_PHASE_ORDER = listOf(
            GesturePhase.TD,
            GesturePhase.SP,
            GesturePhase.BACK,
            GesturePhase.RD
        )
    }

    private var rippleView: FluidRippleView? = null
    private var isViewAdded = false
    private var lastPredictedTags: List<String> = emptyList()
    private var lastPredictedGesture: GestureType? = null

    @SuppressLint("ClickableViewAccessibility")
    fun onTouchEvent(event: MotionEvent, triggerConfig: FreeTriggerConfig) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                applyFluidAppearance()
                showRippleView()
                val edgeSide = getEdgeSide(event.rawX, event.rawY)
                rippleView?.startFluidRipple(edgeSide, event.rawX, event.rawY)
                rippleView?.clearRoundImageContent()
                lastPredictedTags = emptyList()
                lastPredictedGesture = null
            }

            MotionEvent.ACTION_MOVE -> {
                rippleView?.updatePosition(event.rawX, event.rawY)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                rippleView?.stopFluidRipple()
                hideRippleView()
            }
        }
    }

    private fun applyFluidAppearance() {
        rippleView?.applyAppearance(GestureFluidAppearance.current)
    }

    fun refreshTheme() {
        applyFluidAppearance()
    }

    private fun getEdgeSide(x: Float, y: Float): EdgeSide {
        val metrics = gestureService.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        val distanceToLeft = x
        val distanceToRight = w - x
        val distanceToTop = y
        val distanceToBottom = h - y

        val minDistance = minOf(distanceToLeft, distanceToRight, distanceToTop, distanceToBottom)

        return when (minDistance) {
            distanceToLeft -> EdgeSide.LEFT
            distanceToRight -> EdgeSide.RIGHT
            distanceToTop -> EdgeSide.TOP
            distanceToBottom -> EdgeSide.BOTTOM
            else -> EdgeSide.LEFT
        }
    }

    private fun showRippleView() {
        if (isViewAdded) return

        rippleView = FluidRippleView(gestureService).apply {
            applyAppearance(GestureFluidAppearance.current)
        }
        val params = WindowManager.LayoutParams().apply {
            configureAccessibilityOverlay(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                format = PixelFormat.TRANSLUCENT,
                title = "GestureEVO:GestureFeedbackFloating",
            )
        }

        rippleView?.let { addViewSafely(it, params) { isViewAdded = true } }
    }

    private fun hideRippleView() {
        if (!isViewAdded) return

        gestureService.postOnMain {
            delay(200)
            try {
                rippleView?.let {
                    it.clearRoundImageContent()
                    detachViewSafely(it, immediate = false)
                }
                isViewAdded = false
                rippleView = null
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding ripple view", e)
            }
        }
    }

    fun forceHide() {
        lastPredictedTags = emptyList()
        lastPredictedGesture = null
        gestureService.postOnMain {
            if (!isViewAdded) return@postOnMain
            try {
                rippleView?.let {
                    it.clearRoundImageContent()
                    detachViewSafely(it, immediate = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error force hiding ripple view", e)
            } finally {
                isViewAdded = false
                rippleView = null
            }
        }
    }

    fun destroy() {
        if (isViewAdded) {
            try {
                rippleView?.let { detachViewSafely(it, immediate = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying ripple view", e)
            }
        }
        rippleView = null
        isViewAdded = false
    }

    fun updatePredictedActions(
        triggerConfig: FreeTriggerConfig,
        gestureTypeEvent: GestureTypeEvent,
        currentDirection: GestureType
    ) {
        val predictedGestureType = when (gestureTypeEvent.type) {
            GestureType.TOUCH -> currentDirection
            else -> gestureTypeEvent.type
        }

        if (predictedGestureType == GestureType.TOUCH) {
            rippleView?.clearRoundImageContent()
            lastPredictedTags = emptyList()
            lastPredictedGesture = null
            return
        }

        if (predictedGestureType == lastPredictedGesture) {
            return
        }

        val actionItems = DEFAULT_PHASE_ORDER.mapNotNull { phase ->
            triggerConfig.getGestureAction(
                "trigger_${predictedGestureType.name}_${phase.name}_action"
            )
        }.filter { it.firstStageAction != GestureActionType.NONE }
            .distinctBy { it.tag }

        val newTags = actionItems.map { it.tag }
        lastPredictedGesture = predictedGestureType
        lastPredictedTags = newTags
        rippleView?.showPredictedActions(actionItems)
    }
}
