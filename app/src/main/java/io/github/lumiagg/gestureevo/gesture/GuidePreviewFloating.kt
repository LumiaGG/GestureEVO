package io.github.lumiagg.gestureevo.gesture

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.ui.GesturePhase
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import io.github.lumiagg.gestureevo.gesture.ui.GestureTypeEvent
import io.github.lumiagg.gestureevo.gesture.ui.TriggerCallBack
import io.github.lumiagg.gestureevo.gesture.ui.TriggerView
import io.github.lumiagg.gestureevo.settings.guide.GuidePreviewRequest
import io.github.lumiagg.gestureevo.settings.guide.GuidePreviewSession
import io.github.lumiagg.gestureevo.settings.guide.GuideWheelMode
import io.github.lumiagg.gestureevo.settings.guide.QuickSetupConfig
import io.github.lumiagg.gestureevo.settings.guide.QuickSetupRepository
import io.github.lumiagg.gestureevo.settings.guide.QuickSetupSide
import io.github.lumiagg.gestureevo.settings.guide.buildQuickSetupTriggers
import io.github.lumiagg.gestureevo.settings.guide.buildQuickSetupWheelConfig
import io.github.lumiagg.gestureevo.settings.guide.buildQuickSetupWheelItems
import io.github.lumiagg.gestureevo.settings.guide.quickGestureType
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils

/** Uses the same screen bounds, recognizer, and wheel layout as saved triggers. */
class GuidePreviewFloating(private val context: GestureService) :
    BaseFloatingWindow(context, context, "GuidePreviewFloating") {

    private val triggerViews = mutableMapOf<String, TriggerView>()
    private val wheels = mutableMapOf<String, GestureMainFloating>()
    private val wheelTouchViews = mutableMapOf<String, MutableList<View>>()
    private var request: GuidePreviewRequest? = null
    private var reportedPracticeId: String? = null
    private var reportedWheelPracticeId: String? = null
    private var wheelSide = QuickSetupSide.RIGHT

    private val triggerCallback = object : TriggerCallBack {
        override fun onGesture(
            triggerView: TriggerView,
            gestureTypeEvent: GestureTypeEvent,
            currentDirection: GestureType,
            event: MotionEvent,
        ) {
            val current = request ?: return
            if (GuidePreviewSession.current.value != current) return
            val side = if (triggerView.triggerConfig.tag == QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG) {
                QuickSetupSide.LEFT
            } else {
                QuickSetupSide.RIGHT
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN && side != wheelSide) {
                wheelSide = side
                show(current)
            }
            if (gestureTypeEvent.type == GestureType.TOUCH && current.wheelSelection &&
                reportedWheelPracticeId != current.practiceId
            ) {
                wheels[triggerView.triggerConfig.tag]?.actionView?.onTouchEvent(event)
                return
            }
            val practice = current.practice ?: return
            val practiceId = current.practiceId ?: return
            if (reportedPracticeId == practiceId || gestureTypeEvent.phase != GesturePhase.TD) return
            if (gestureTypeEvent.type == quickGestureType(practice, side)) {
                if (GuidePreviewSession.reportGesture(current, practice)) reportedPracticeId = practiceId
            }
        }
    }

    fun show(request: GuidePreviewRequest) {
        if (this.request?.owner != request.owner) {
            wheelSide = if (request.config.side == QuickSetupSide.LEFT) QuickSetupSide.LEFT else QuickSetupSide.RIGHT
        } else if (request.config.side != QuickSetupSide.BOTH) {
            wheelSide = request.config.side
        }
        if (this.request?.owner != request.owner || this.request?.practiceId != request.practiceId) {
            reportedPracticeId = null
            reportedWheelPracticeId = null
        }
        this.request = request
        val triggers = buildQuickSetupTriggers(request.config, context.getString(R.string.guide_title))
        val expectedTags = triggers.map { it.tag }.toSet()
        val wheelTag = if (wheelSide == QuickSetupSide.LEFT) QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG
            else QuickSetupRepository.PREVIEW_RIGHT_TRIGGER_TAG
        val expectedWheels = if (request.showsWheel) setOf(wheelTag) else emptySet()
        (wheels.keys - expectedWheels).toList().forEach { tag ->
            removeWheelTouchViews(tag)
            wheels.remove(tag)?.destroyGuidePreview()
        }
        (triggerViews.keys - expectedTags).forEach { tag ->
            triggerViews.remove(tag)?.let { detachViewSafely(it) }
            removeWheelTouchViews(tag)
            wheels.remove(tag)?.destroyGuidePreview()
        }
        triggers.forEach { config ->
            val view = triggerViews.getOrPut(config.tag) {
                TriggerView(context).apply {
                    tag = "guide_preview_${config.tag}"
                    triggerCallBack = triggerCallback
                    isActivated = true
                }
            }
            view.triggerConfig = config
            view.isMirror = request.config.side == QuickSetupSide.BOTH && config.tag == QuickSetupRepository.PREVIEW_RIGHT_TRIGGER_TAG
            view.setBackgroundColor(ColorUtils.setAlphaComponent(DynamicColorUtils.primaryColor, 180))
            val params = triggerLayoutParams(config, view.isMirror)
            if (view.isAttachedToWindow) {
                updateViewLayoutSafely(view, params)
            } else {
                addViewSafely(view, params)
            }
            removeWheelTouchViews(config.tag)
            if (config.tag !in expectedWheels) return@forEach
            val wheel = wheels.getOrPut(config.tag) { GestureMainFloating(context) }
            val baseConfig = request.actionConfig ?: ActionConfig()
            wheel.showGuidePreview(
                buildQuickSetupWheelConfig(baseConfig),
                buildQuickSetupWheelItems(request.config),
                config,
                isMirror = view.isMirror,
                onWheelSelection = if (request.wheelSelection) {
                    { index -> reportWheelSelection(request, index) }
                } else {
                    null
                },
                onLayout = {
                    if (this.request == request && request.wheelSelection && reportedWheelPracticeId != request.practiceId) {
                        addWheelTouchViews(config.tag, wheel, request)
                    }
                    wheel.actionView.alpha = if (request.wheelMode == GuideWheelMode.PREVIEW) 0.65f else 1f
                },
            )
        }
    }

    fun refresh() {
        request?.let(::show)
        wheels.values.forEach { it.refreshTheme() }
    }

    fun hide() {
        request = null
        reportedPracticeId = null
        reportedWheelPracticeId = null
        triggerViews.values.forEach { detachViewSafely(it) }
        triggerViews.clear()
        wheelTouchViews.keys.toList().forEach(::removeWheelTouchViews)
        wheels.values.forEach { it.destroyGuidePreview() }
        wheels.clear()
    }

    fun destroy() = hide()

    private fun reportWheelSelection(request: GuidePreviewRequest, index: Int) {
        if (this.request != request || GuidePreviewSession.current.value != request ||
            reportedWheelPracticeId == request.practiceId
        ) return
        if (!GuidePreviewSession.reportWheelSelection(request, index)) return
        reportedWheelPracticeId = request.practiceId
        wheelTouchViews.keys.toList().forEach(::removeWheelTouchViews)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addWheelTouchViews(tag: String, wheel: GestureMainFloating, current: GuidePreviewRequest) {
        removeWheelTouchViews(tag)
        val views = mutableListOf<View>()
        wheel.actionView.modelList.forEachIndexed { index, item ->
            if (!item.view.isVisible || index !in wheel.actionView.ringsFlatten.indices) return@forEachIndexed
            val position = wheel.actionView.ringsFlatten[index]
            val params = WindowManager.LayoutParams().apply {
                configureAccessibilityOverlay(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    width = item.view.layoutParams.width,
                    height = item.view.layoutParams.height,
                    x = position.x.toInt(),
                    y = position.y.toInt(),
                    title = "GestureEVO:GuidePreviewWheelTouch:$tag:$index",
                )
            }
            // Route only the visible item bounds to the existing wheel selection logic.
            val view = View(context).apply {
                setOnTouchListener { _, event ->
                    if (request == current && current.wheelSelection && reportedWheelPracticeId != current.practiceId) {
                        wheel.actionView.onTouchEvent(event)
                    }
                    true
                }
            }
            views.add(view)
            addViewSafely(view, params)
        }
        wheelTouchViews[tag] = views
    }

    private fun removeWheelTouchViews(tag: String) {
        wheelTouchViews.remove(tag)?.forEach { detachViewSafely(it) }
    }

    private fun triggerLayoutParams(config: FreeTriggerConfig, isMirror: Boolean): WindowManager.LayoutParams {
        val bounds = freeTriggerBounds(context, config, isMirror)
        return WindowManager.LayoutParams().apply {
            configureAccessibilityOverlay(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                width = bounds.width(),
                height = bounds.height(),
                x = bounds.left,
                y = bounds.top,
                title = "GestureEVO:GuidePreviewTrigger:${config.tag}",
            )
        }
    }
}
