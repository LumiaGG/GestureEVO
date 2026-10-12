package io.github.lumiagg.gestureevo.pointer

import io.github.lumiagg.gestureevo.databinding.PointerFloatingTouchViewBinding

import io.github.lumiagg.gestureevo.databinding.GestureActionPointerViewBinding

import io.github.lumiagg.gestureevo.databinding.PointerFloatingViewBinding

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
import androidx.core.view.doOnNextLayout
import com.google.android.material.card.MaterialCardView
import com.google.zxing.common.detector.MathUtils.distance
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceInspireEvent
import io.github.lumiagg.gestureevo.gesture.GestureManager.InteractionMode
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.gesture.InspireEvent
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.inspire.SmartSegmenter
import io.github.lumiagg.gestureevo.inspire.getScreenView
import io.github.lumiagg.gestureevo.pointer.ui.HighlightRectView
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_POINTER_COLOR
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_ALWAYS_SHOW_POINTER_TOUCH_AREA
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_ENABLE_POINTER_DRAG_MODE
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_POINTER_TOUCH_AREA_OFFSET_DP
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_SHOW_POINTER_TRAIL
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.MlKitOcr
import io.github.lumiagg.gestureevo.utils.Point
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.postOnMain
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.LinkedList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min


class PointerFloating(
    private val context: GestureService,
) : BaseFloatingWindow(context, context, "PointerFloating") {
    private val pointerFloatingView: ConstraintLayout by lazy {
        PointerFloatingViewBinding.inflate(LayoutInflater.from(context)).root
    }
    private val touchView: MaterialCardView by lazy {
        PointerFloatingTouchViewBinding.inflate(LayoutInflater.from(context)).root
    }
    private val pointerLayoutParams = WindowManager.LayoutParams()
    private val touchLayoutParams = WindowManager.LayoutParams()
    private var touchViewScale = 0.35
    var screenStatusHeight = ScreenUtil.getStatusBarHeight(context)
    private val trailView = TrailView(context)
    private val highlightRectView = HighlightRectView(context)
    val pointerManger = PointerManger()
    var needShowTouchView = false
    private var alwaysShowTouchArea = false
    private var touchAreaOffsetDp = 0
    private var pointerVisibleRequested = false
    var triggerSide: String? = null

    init {
        initView()
        initFloatingWindow()
        initTouchView()
        addTrailView()
        addHighlightRectView()
    }

    private fun addHighlightRectView() {
        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        pointerFloatingView.addView(
            highlightRectView,
            1, // Add above trail view but below pointer
            layoutParams
        )
    }

    private fun addTrailView() {
        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        pointerFloatingView.addView(
            trailView, 0, layoutParams
        ) // Add at index 0 to be behind pointer
    }

    private fun initView() {
        pointerFloatingView.visibility = View.GONE
    }

    fun refreshTheme() {
        trailView.refreshTheme()
        highlightRectView.setStrokeColor(pointerManger.getPointerColor())
        pointerManger.refreshTheme()
    }

    private fun initFloatingWindow() {
        pointerLayoutParams.configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            title = "GestureEVO:PointerFloating:Pointer",
        )
        pointerFloatingView.visibility = View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchView() {
        touchLayoutParams.configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            width = ViewGroup.LayoutParams.WRAP_CONTENT,
            height = ViewGroup.LayoutParams.WRAP_CONTENT,
            allowDisplayCutout = false,
            title = "GestureEVO:PointerFloating:Touch",
        )
        touchView.setOnTouchListener { view, event ->
            pointerManger.onTouchEvent(event, fromTouchView = true)
            return@setOnTouchListener false
        }
        touchView.visibility = View.GONE
    }

    private fun updateTouchView() {
        pointerFloatingView.post {
            if (!pointerVisibleRequested) {
                return@post
            }
            if (!pointerFloatingView.isAttachedToWindow || !touchView.isAttachedToWindow) {
                return@post
            }
            pointerFloatingView.visibility = View.VISIBLE
            if (pointerFloatingView.width > 0 && pointerFloatingView.height > 0) {
                updateTouchViewLayout()
            } else {
                pointerFloatingView.doOnNextLayout {
                    updateTouchViewLayout()
                }
            }
        }
    }

    private fun updateTouchViewLayout() {
        if (!pointerVisibleRequested) {
            return
        }
        if (!pointerFloatingView.isAttachedToWindow || !touchView.isAttachedToWindow) {
            return
        }
        val screenWidth = pointerFloatingView.width
        val screenHeight = pointerFloatingView.height
        if (screenWidth <= 0 || screenHeight <= 0) return

        val layoutParams = touchView.layoutParams as WindowManager.LayoutParams
        layoutParams.width = (screenWidth * touchViewScale + 20f.dp2px).toInt()
        layoutParams.height = ((screenHeight + screenStatusHeight) * touchViewScale).toInt()

        val minDistanceFromEdge = 10f.dp2px
        val minDistanceFromBottom = 20f.dp2px

        val minX = minDistanceFromEdge
        val maxX = screenWidth - layoutParams.width - minDistanceFromEdge
        val minY = minDistanceFromBottom
        val maxY = screenHeight - layoutParams.height - minDistanceFromBottom

        val touchYRatio = (pointerManger.touchViewY / screenHeight).coerceIn(0f, 1f)
        val amplifiedTouchYRatio = ((touchYRatio - 0.5f) * 1.5f + 0.5f).coerceIn(0f, 1f)

        layoutParams.x = (pointerManger.touchViewX - layoutParams.width / 2).toInt()
            .coerceIn(minX.toInt(), maxX.toInt())
        layoutParams.y = (minY + (maxY - minY) * amplifiedTouchYRatio + touchAreaOffsetDp.dp2px).toInt()
            .coerceIn(minY.toInt(), maxY.toInt())

        updateViewLayoutSafely(touchView, layoutParams)
        touchView.alpha = if (shouldShowTouchArea()) 1f else 0f
        touchView.visibility = View.VISIBLE
    }

    private fun shouldShowTouchArea() = needShowTouchView || alwaysShowTouchArea

    private companion object {
        const val POINTER_SHOW_ANIMATION_DURATION = 140L
        const val POINTER_HIDE_ANIMATION_DURATION = 110L
        const val POINTER_ANIMATION_START_SCALE = 0.82f
    }

    var enable = false
        set(value) {
            if (value) {
                show()
            } else {
                hide()
            }
            field = value
        }

    fun show() = context.postOnMain {
        pointerVisibleRequested = true
        ensureViewsAttached()
        updateTouchAreaConfig()
        pointerManger.updatePointerColorConfig()
        highlightRectView.setStrokeColor(pointerManger.getPointerColor())
        pointerFloatingView.visibility = View.VISIBLE
        updateTouchView()
        pointerManger.showPointer()
        pointerManger.viewPositionMonitor.start()
        trailView.updateTrailConfig()
    }

    fun hide() = context.postOnMain {
        pointerVisibleRequested = false
        pointerManger.viewPositionMonitor.stop()
        if (pointerManger.isDragMode) {
            pointerManger.exitDragMode()
        }
        if (!pointerFloatingView.isAttachedToWindow && !touchView.isAttachedToWindow) {
            needShowTouchView = false
            pointerManger.initPosition()
            trailView.clearTrail()
            return@postOnMain
        }
        touchView.animate().cancel()
        touchView.visibility = View.GONE
        needShowTouchView = false
        trailView.clearTrail()
        pointerManger.hidePointer {
            if (!pointerVisibleRequested) {
                pointerFloatingView.visibility = View.GONE
                pointerManger.initPosition()
                detachViewsIfNeeded()
            }
        }
    }

    private fun ensureViewsAttached() {
        val updateWhenReady = {
            if (pointerFloatingView.isAttachedToWindow && touchView.isAttachedToWindow) {
                updateTouchView()
            }
        }
        attachViewSafely(
            pointerFloatingView,
            pointerLayoutParams,
            shouldRetry = { pointerVisibleRequested },
            onAttached = updateWhenReady
        )
        attachViewSafely(
            touchView,
            touchLayoutParams,
            shouldRetry = { pointerVisibleRequested },
            onAttached = updateWhenReady
        )
    }

    private fun detachViewsIfNeeded() {
        detachViewSafely(pointerFloatingView)
        detachViewSafely(touchView)
    }

    fun destroy() = context.postOnMain {
        pointerVisibleRequested = false
        pointerManger.viewPositionMonitor.stop()
        if (pointerManger.isDragMode) {
            pointerManger.exitDragMode()
        }
        pointerFloatingView.animate().cancel()
        touchView.animate().cancel()
        pointerFloatingView.visibility = View.GONE
        touchView.visibility = View.GONE
        needShowTouchView = false
        pointerManger.initPosition()
        trailView.clearTrail()
        detachViewsIfNeeded()
    }

    private suspend fun updateTouchAreaConfig() = withContext(Dispatchers.IO) {
        alwaysShowTouchArea = context.settingsStore.readKey(
            KEY_ALWAYS_SHOW_POINTER_TOUCH_AREA,
            false
        )
        touchAreaOffsetDp = context.settingsStore.readKey(
            KEY_POINTER_TOUCH_AREA_OFFSET_DP,
            0
        ).coerceIn(-100, 100)
    }

    // Trail View class to draw the pointer's trail
    inner class TrailView(context: Context) : View(context) {
        var enable = false
        private val MAX_TRAIL_POINTS = 10
        private val trailPoints = LinkedList<Pair<Float, Float>>()
        private val trailPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = DynamicColorUtils.primaryFixedColor
        }

        fun addPoint(x: Float, y: Float) {
            if (enable) {
                trailPoints.add(Pair(x, y))
                if (trailPoints.size > MAX_TRAIL_POINTS) {
                    trailPoints.removeFirst()
                }
                invalidate()
            }
        }

        suspend fun updateTrailConfig() {
            enable = context.settingsStore.readKey(KEY_SHOW_POINTER_TRAIL, false)
            pointerManger.showViewCenter(!enable)
            withContext(Dispatchers.Main) {
                refreshTheme()
            }
        }

        fun refreshTheme() {
            trailPaint.color = pointerManger.getPointerColor()
            invalidate()
        }

        fun clearTrail() {
            trailPoints.clear()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (trailPoints.size < 2) return

            // Draw dots with decreasing size and opacity
            trailPoints.forEachIndexed { index, point ->
                val alpha = (255 * (index.toFloat() / trailPoints.size)).toInt()
                val radius = (3 * (index.toFloat() / trailPoints.size) + 1f.dp2px).dp2px

                trailPaint.alpha = alpha
                canvas.drawCircle(point.first, point.second, radius, trailPaint)
            }
        }
    }

    inner class PointerManger() {
        private val view = GestureActionPointerViewBinding.inflate(LayoutInflater.from(context)).root
        private val pointerIcon: ImageFilterView = view.findViewById(R.id.pointer)
        private var pointerColor: Int = 0

        var touchViewX = 0f
        var touchViewY = 0f
        var isDragMode = false
        private var isHoverLongPressActive = false
        private var hoverLongPressJob: Job? = null
        private var pendingHoverLongPress = false
        private var pendingHoverPoint: Point? = null
        private var isLongPressRunning = false
        private var potentialDragSelectRect: Rect? = null
        private var potentialDragSelectJob: Job? = null
        private var potentialDragSelectSourceRect: Rect? = null
        private var pendingPotentialDragSelectRect: Rect? = null
        private var hasStartedDragSelection = false
        private var lastPotentialThinRect: Rect? = null
        private val moveTouchHandler = MoveTouchHandler()
        val viewPositionMonitor = ViewPositionMonitor()

        init {
            val layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            )

            // Constrain all four sides to parent container
            layoutParams.topToTop = LayoutParams.PARENT_ID
            layoutParams.bottomToBottom = LayoutParams.PARENT_ID
            layoutParams.startToStart = LayoutParams.PARENT_ID
            layoutParams.endToEnd = LayoutParams.PARENT_ID

            pointerFloatingView.addView(view, layoutParams)
        }

        fun initPosition() {
            view.animate().cancel()
            view.x = -100f
            view.y = -100f
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            trailView.clearTrail()
        }

        fun showPointer() {
            view.animate().cancel()
            view.alpha = 0f
            view.scaleX = POINTER_ANIMATION_START_SCALE
            view.scaleY = POINTER_ANIMATION_START_SCALE
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(POINTER_SHOW_ANIMATION_DURATION)
                .start()
        }

        fun hidePointer(onEnd: () -> Unit) {
            view.animate().cancel()
            if (view.visibility != View.VISIBLE) {
                onEnd()
                return
            }
            view.animate()
                .alpha(0f)
                .scaleX(POINTER_ANIMATION_START_SCALE)
                .scaleY(POINTER_ANIMATION_START_SCALE)
                .setDuration(POINTER_HIDE_ANIMATION_DURATION)
                .withEndAction {
                    view.visibility = View.INVISIBLE
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    onEnd()
                }
                .start()
        }

        fun onTouchEvent(event: MotionEvent, fromTouchView: Boolean = false) {
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    pendingHoverLongPress = false
                    pendingHoverPoint = null
                    if (enable && fromTouchView && touchView.alpha == 1f && !alwaysShowTouchArea) {
                        touchView.alpha = 0f
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    touchViewX = event.rawX
                    touchViewY = event.rawY
                    if (enable) {
                        moveTouchHandler.handleActionMove(event)
                    }
                }

                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    touchViewX = event.rawX
                    touchViewY = event.rawY
                    if (enable) {
                        moveTouchHandler.handleActionUp(event)
                    }
                    enable = false
                }
            }
        }

        suspend fun updatePointerColorConfig() = withContext(Dispatchers.IO) {
            pointerColor = context.settingsStore.readKey(KEY_POINTER_COLOR, 0)
        }

        fun getPointerColor(): Int {
            return if (pointerColor == 0) DynamicColorUtils.primaryColor else pointerColor
        }

        private fun applyPointerTheme(target: ImageFilterView) {
            val color = getPointerColor()
            target.imageTintList = ColorStateList.valueOf(color)
            target.backgroundTintList = ColorStateList.valueOf(color)
        }

        fun refreshTheme() {
            applyPointerTheme(pointerIcon)
        }

        suspend fun showViewCenter(boolean: Boolean) = withContext(Dispatchers.Main) {
            if (boolean) {
                pointerIcon.apply {
                    setImageResource(R.drawable.baseline_circle_24)
                    applyPointerTheme(this)
                }
            } else {
                pointerIcon.setImageDrawable(null)
            }
        }

        fun enterDragMode() {
            isDragMode = true
            potentialDragSelectJob?.cancel()
            potentialDragSelectRect = null
            potentialDragSelectSourceRect = null
            pendingPotentialDragSelectRect = null
            hasStartedDragSelection = false
            lastPotentialThinRect = null
            context.gestureManager?.setInteractionMode(InteractionMode.POINTER_DRAG_SELECT)
            context.launch(Dispatchers.IO) {
                MlKitOcr.preload()
                SmartSegmenter.prewarm(context.applicationContext)
            }
            VibratorUtils.vibrator(context, true)
            context.postOnMain {
                trailView.enable = false
                trailView.clearTrail()
                pointerIcon.apply {
                    setImageResource(R.drawable.baseline_add_24)
                    applyPointerTheme(this)
                    background = null
                    setPadding(0, 0, 0, 0)
                }
                val halfPointerWidth = view.width / 2
                val startPoint = Point(
                    view.x + halfPointerWidth,
                    view.y + halfPointerWidth
                )
                highlightRectView.showRect(startPoint)
                loadPotentialDragSelectRect(startPoint)
            }
        }

        fun exitDragMode() {
            isDragMode = false
            potentialDragSelectJob?.cancel()
            potentialDragSelectJob = null
            potentialDragSelectRect = null
            potentialDragSelectSourceRect = null
            pendingPotentialDragSelectRect = null
            hasStartedDragSelection = false
            lastPotentialThinRect = null
            context.gestureManager?.setInteractionMode(InteractionMode.NORMAL)
            context.postOnMain {
                pointerIcon.apply {
                    setImageDrawable(null)
                    setBackgroundResource(R.drawable.pointer)
                    applyPointerTheme(this)
                    val padding = 10f.dp2px.toInt()
                    setPadding(padding, padding, padding, padding)
                }
                trailView.updateTrailConfig()
                highlightRectView.hideRect()
                highlightRectView.hidePotentialRect()
            }
        }

        private fun loadPotentialDragSelectRect(startPoint: Point) {
            val centerX = startPoint.x.toInt()
            val centerY = startPoint.y.toInt()
            val pointRect = Rect(
                centerX - 1,
                centerY - 1,
                centerX + 1,
                centerY + 1
            )
            val sourceRect = Rect(centerX, centerY, centerX, centerY)
            potentialDragSelectJob?.cancel()
            potentialDragSelectSourceRect = Rect(sourceRect)
            potentialDragSelectJob = context.launch(Dispatchers.Default) {
                val targetRect = getScreenView(context, pointRect, clickMode = true)
                    .filter { it.area > 0 }
                    .minByOrNull { it.area }
                    ?.bound
                if (potentialDragSelectSourceRect != sourceRect) return@launch
                potentialDragSelectRect = targetRect
                withContext(Dispatchers.Main) {
                    if (!isDragMode || hasStartedDragSelection) return@withContext
                    if (targetRect == null) {
                        highlightRectView.hidePotentialRect()
                    } else {
                        highlightRectView.showPotentialRect(targetRect)
                    }
                }
            }
        }

        private fun updatePotentialDragSelectRect(selectionRect: Rect, endPoint: Point) {
            if (highlightRectView.shouldUseDragSelection(endPoint)) {
                return
            }
            if (selectionRect == lastPotentialThinRect) {
                return
            }
            lastPotentialThinRect = Rect(selectionRect)
            pendingPotentialDragSelectRect = Rect(selectionRect)
            if (potentialDragSelectJob?.isActive == true) {
                return
            }
            potentialDragSelectJob = context.launch(Dispatchers.Default) {
                while (isDragMode && !hasStartedDragSelection) {
                    val sourceRect = pendingPotentialDragSelectRect ?: break
                    pendingPotentialDragSelectRect = null
                    val targetRect = resolvePotentialDragSelectRect(sourceRect)
                    potentialDragSelectSourceRect = Rect(sourceRect)
                    potentialDragSelectRect = targetRect
                    withContext(Dispatchers.Main) {
                        if (!isDragMode || hasStartedDragSelection) return@withContext
                        if (targetRect == null) {
                            highlightRectView.hidePotentialRect()
                        } else {
                            highlightRectView.showPotentialRect(targetRect)
                        }
                    }
                }
            }
        }

        private suspend fun resolvePotentialDragSelectRect(selectionRect: Rect): Rect? {
            val tooSmallWidth = selectionRect.width() < io.github.lumiagg.gestureevo.inspire.ui.InspireView.DRAG_SELECT_CLICK_THRESHOLD
            val tooSmallHeight = selectionRect.height() < io.github.lumiagg.gestureevo.inspire.ui.InspireView.DRAG_SELECT_CLICK_THRESHOLD
            return when {
                tooSmallWidth && tooSmallHeight -> getScreenView(context, selectionRect, clickMode = true)
                    .filter { it.area > 0 }
                    .minByOrNull { it.area }
                    ?.bound

                tooSmallWidth || tooSmallHeight -> {
                    val views = getScreenView(context, selectionRect, clickMode = false)
                    if (views.isEmpty()) {
                        null
                    } else {
                        Rect().also { expandedRect ->
                            views.forEachIndexed { index, view ->
                                if (index == 0) {
                                    expandedRect.set(view.bound)
                                } else {
                                    expandedRect.union(view.bound)
                                }
                            }
                        }
                    }
                }

                else -> null
            }
        }

        private fun showHoverLongPressHint() {
            if (isDragMode || isHoverLongPressActive) return
            isHoverLongPressActive = true
            pointerIcon.animate().cancel()
            pointerIcon.scaleX = 1f
            pointerIcon.scaleY = 1f
            pointerIcon.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(90)
                .withEndAction {
                    pointerIcon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(110)
                        .start()
                }
                .start()
        }

        private fun resetHoverLongPressHint() {
            if (!isHoverLongPressActive) return
            isHoverLongPressActive = false
            pointerIcon.animate().cancel()
            pointerIcon.scaleX = 1f
            pointerIcon.scaleY = 1f
            pendingHoverLongPress = false
            pendingHoverPoint = null
        }

        private fun prepareHoverLongPress(point: Point) {
            if (isDragMode) return
            showHoverLongPressHint()
            pendingHoverLongPress = true
            pendingHoverPoint = point
        }

        private inner class MoveTouchHandler() {

            fun computePosition(): Point {
                val screenWidth = pointerFloatingView.width
                val screenHeight = pointerFloatingView.height
                val touchWidth = touchView.width
                val touchHeight = touchView.height
                if (screenWidth <= 0 || screenHeight <= 0 || touchWidth <= 0 || touchHeight <= 0) {
                    return Point(
                        (view.x + view.width / 2).coerceAtLeast(0f),
                        (view.y + view.height / 2).coerceAtLeast(0f)
                    )
                }

                val touchViewLocation = IntArray(2)
                touchView.getLocationOnScreen(touchViewLocation)
                val touchViewLeft = touchViewLocation[0]
                val touchViewTop = touchViewLocation[1]
                // Calculate relative position within touchView (clamped to 0..1)
                val relativeX = ((touchViewX - touchViewLeft) / touchWidth).coerceIn(0f, 1f)
                val relativeY = ((touchViewY - touchViewTop) / touchHeight).coerceIn(0f, 1f)

                // Map target position to full screen coordinate space
                val targetX = (relativeX * screenWidth).coerceIn(0f, screenWidth.toFloat())
                val targetY = (relativeY * screenHeight).coerceIn(0f, screenHeight.toFloat())
                return Point(targetX, targetY)
            }

            fun handleActionMove(event: MotionEvent) {
                // Offset by pointer half-dimensions so pointer tip aligns accurately
                val halfPointerWidth = view.width / 2
                val halfPointerHeight = view.height / 2

                val targetPoint = computePosition()

                // Apply position accounting for view size
                view.x = targetPoint.x - halfPointerWidth
                view.y = targetPoint.y - halfPointerHeight

                if (isDragMode) {
                    val useDragSelection = highlightRectView.shouldUseDragSelection(targetPoint)
                    if (useDragSelection) {
                        if (!hasStartedDragSelection) {
                            hasStartedDragSelection = true
                            potentialDragSelectJob?.cancel()
                            potentialDragSelectJob = null
                            potentialDragSelectRect = null
                            potentialDragSelectSourceRect = null
                            pendingPotentialDragSelectRect = null
                            lastPotentialThinRect = null
                            highlightRectView.hidePotentialRect()
                        }
                    } else {
                        if (hasStartedDragSelection) {
                            hasStartedDragSelection = false
                            lastPotentialThinRect = null
                        }
                        updatePotentialDragSelectRect(
                            highlightRectView.currentSelectionRect(targetPoint),
                            targetPoint
                        )
                    }
                    highlightRectView.updateRect(Point(targetPoint.x, targetPoint.y))
                    if (highlightRectView.isBackToOrigin) {
                        exitDragMode()
                    }
                }

                // Add a point to the trail at the center of the pointer
                trailView.addPoint(targetPoint.x, targetPoint.y)
            }

            fun handleActionUp(event: MotionEvent) {
                if (!isLongPressRunning) {
                    hoverLongPressJob?.cancel()
                    hoverLongPressJob = null
                }
                if (isDragMode) {
                    resetHoverLongPressHint()
                    view.visibility = View.INVISIBLE
                    val currentSelectionRect = highlightRectView.currentSelectionRect(computePosition())
                    val previewSelectionRect = potentialDragSelectRect
                        ?.takeIf { potentialDragSelectSourceRect == currentSelectionRect }
                        ?.let { Rect(it) }
                    potentialDragSelectJob?.cancel()
                    potentialDragSelectJob = null
                    potentialDragSelectRect = null
                    potentialDragSelectSourceRect = null
                    pendingPotentialDragSelectRect = null
                    highlightRectView.hidePotentialRect()
                    context.launch {
                        val resolvedSelectionRect = if (!hasStartedDragSelection) {
                            previewSelectionRect ?: resolvePotentialDragSelectRect(currentSelectionRect)
                        } else {
                            null
                        }
                        val newRect = if (resolvedSelectionRect != null) {
                            resolvedSelectionRect
                        } else currentSelectionRect.let {
                            Rect(
                                min(it.left, it.right),
                                min(it.top, it.bottom),
                                max(it.left, it.right),
                                max(it.top, it.bottom)
                            )
                        }
                        val effectiveSide = triggerSide
                            ?: (if (newRect.centerX() < context.resources.displayMetrics.widthPixels / 2f) TriggerSide.LEFT else TriggerSide.RIGHT)
                        produceInspireEvent(
                            InspireEvent(rect = newRect, side = effectiveSide)
                        )
                    }
                } else if (pendingHoverLongPress) {
                    val targetPoint = pendingHoverPoint ?: computePosition()
                    pendingHoverLongPress = false
                    pendingHoverPoint = null
                    hoverLongPressJob = context.launch {
                        isLongPressRunning = true
                        try {
                            performPointerClick(targetPoint.x, targetPoint.y, 600L)
                        } finally {
                            isLongPressRunning = false
                            withContext(Dispatchers.Main) {
                                resetHoverLongPressHint()
                            }
                            hoverLongPressJob = null
                        }
                    }
                    trailView.clearTrail()
                } else {
                    val targetPoint = computePosition()
                    trailView.clearTrail()
                    Log.d(
                        "PointerFloating",
                        "handleActionUp: ${targetPoint.x}, ${targetPoint.y}"
                    )
                    context.launch {
                        delay(20)
                        performPointerClick(targetPoint.x, targetPoint.y)
                    }
                }
            }
        }

        inner class ViewPositionMonitor() {
            var enable = false
                private set

            // Thread-safe point history list
            private val historyPoints = Collections.synchronizedList(LinkedList<Point>())
            private var monitorJob: Job? = null
            private var checkJob: Job? = null

            // Hold weak reference to avoid memory leaks
            private val viewRef = WeakReference(view)

            // Coroutine exception handler
            private val errorHandler = CoroutineExceptionHandler { _, exception ->
                Log.e("ViewPositionMonitor", "Coroutine error: ${exception.message}", exception)
                stop()
            }

            private fun onStableChange(isStable: Boolean, stablePoint: Point) {
                // Dispatch UI operations to the main thread
                context.postOnMain {
                    if (isStable && !isDragMode) {
                        if (dragModeEnabled) {
                            enterDragMode()
                        } else {
                            prepareHoverLongPress(stablePoint)
                        }
                    }
                }
            }

            private fun analyzePosition() {
                synchronized(historyPoints) {
                    if (historyPoints.size < 2) return

                    // Calculate maximum displacement across recent samples
                    var maxDistance = 0f
                    val referencePosition = historyPoints.first()

                    for (position in historyPoints) {
                        val distance = distance(
                            referencePosition.x, referencePosition.y,
                            position.x, position.y
                        )
                        maxDistance = max(maxDistance, distance)
                    }

                    val isPositionStable = maxDistance < 10.dp2px

                    // Update checkJob atomically
                    val currentCheckJob = checkJob
                    if (isPositionStable && !isDragMode) {
                        if (currentCheckJob == null || !currentCheckJob.isActive) {
                            // Cancel previous check job and schedule a new check
                            currentCheckJob?.cancel()
                            checkJob = context.launch(Dispatchers.Default + errorHandler) {
                                try {
                                    Log.d("ViewPositionMonitor", "Starting stability check")
                                    val currentPoint = historyPoints.lastOrNull() ?: return@launch

                                    try {
                                        withTimeout(500) {  // Timeout guard against stalled execution
                                            delay(400)
                                        }
                                    } catch (e: TimeoutCancellationException) {
                                        Log.d("ViewPositionMonitor", "Stability check timed out")
                                        return@launch
                                    }

                                    // Verify coroutine is still active
                                    ensureActive()

                                    // Read current view coordinates to avoid stale data
                                    val newView = viewRef.get() ?: return@launch
                                    val distance = distance(
                                        currentPoint.x,
                                        currentPoint.y,
                                        newView.x + newView.width / 2,
                                        newView.y + newView.height / 2
                                    )

                                    val isStable = distance < 5.dp2px
                                    if (!isStable && !dragModeEnabled) {
                                        context.postOnMain {
                                            if (!isLongPressRunning) {
                                                hoverLongPressJob?.cancel()
                                                hoverLongPressJob = null
                                            }
                                            resetHoverLongPressHint()
                                        }
                                    }
                                    onStableChange(
                                        isStable,
                                        Point(
                                            newView.x + newView.width / 2,
                                            newView.y + newView.height / 2
                                        )
                                    )
                                } finally {
                                    // Reset job state upon completion
                                    if (checkJob?.isActive == true && checkJob == currentCheckJob) {
                                        checkJob = null
                                    }
                                }
                            }
                        }
                    } else {
                        currentCheckJob?.cancel()
                        checkJob = null
                        if (!dragModeEnabled) {
                            context.postOnMain {
                                if (!isLongPressRunning) {
                                    hoverLongPressJob?.cancel()
                                    hoverLongPressJob = null
                                }
                                resetHoverLongPressHint()
                            }
                        }
                    }
                }
            }

            private var dragModeEnabled = false

            fun start() {
                context.launch(Dispatchers.Default + errorHandler) {
                    try {
                        // Stop existing tasks and ensure clean state
                        // Cancel all background jobs
                        monitorJob?.cancel()
                        checkJob?.cancel()
                        // Clear historical points
                        synchronized(historyPoints) {
                            historyPoints.clear()
                        }

                        dragModeEnabled =
                            context.settingsStore.readKey(KEY_ENABLE_POINTER_DRAG_MODE, false)
                        enable = true

                        // Start new monitoring loop
                        monitorJob = context.launch(Dispatchers.Default + errorHandler) {
                            try {
                                while (isActive) {
                                    delay(30)

                                    // Exit if view reference has been garbage-collected
                                    val currentView = viewRef.get()
                                    if (currentView == null) {
                                        Log.d(
                                            "ViewPositionMonitor",
                                            "View reference lost, stopping monitor"
                                        )
                                        cancel()
                                        break
                                    }

                                    // Safely append position to history list
                                    val x: Float
                                    val y: Float

                                    // Read view coordinates on main thread
                                    withContext(Dispatchers.Main) {
                                        x = currentView.x + currentView.width / 2
                                        y = currentView.y + currentView.height / 2
                                    }
                                    if (x >= 0 && y >= 0) {
                                        synchronized(historyPoints) {
                                            historyPoints.add(Point(x, y))
                                            if (historyPoints.size > 10) {
                                                historyPoints.removeFirstOrNull()
                                            }
                                        }
                                    }
                                    // Analyze position stability
                                    analyzePosition()
                                }
                            } catch (e: CancellationException) {
                                // Normal cancellation, no action required
                                Log.d("ViewPositionMonitor", "Monitor job cancelled")
                            } catch (e: Exception) {
                                Log.e("ViewPositionMonitor", "Error in monitor job", e)
                                throw e  // Re-throw to error handler
                            }
                        }

                        Log.d("ViewPositionMonitor", "Monitor started")
                    } catch (e: Exception) {
                        Log.e("ViewPositionMonitor", "Failed to start monitor", e)
                        stop()  // Ensure resources are cleaned up
                    }
                }
            }

            fun stop() {
                // Execute stop operations on main thread
                context.postOnMain {
                    try {
                        // Cancel coroutine jobs
                        monitorJob?.cancel()
                        checkJob?.cancel()
                        if (!isLongPressRunning) {
                            hoverLongPressJob?.cancel()
                            hoverLongPressJob = null
                        }
                        resetHoverLongPressHint()
                        enable = false

                        // Reset state
                        monitorJob = null
                        checkJob = null

                        // Clear historical points
                        synchronized(historyPoints) {
                            historyPoints.clear()
                        }

                        Log.d("ViewPositionMonitor", "Monitor stopped")
                    } catch (e: Exception) {
                        Log.e("ViewPositionMonitor", "Error while stopping monitor", e)
                    }
                }
            }

            // Called when the view lifecycle ends
            fun dispose() {
                stop()
                // Clear all references
                historyPoints.clear()
            }
        }
    }

    private suspend fun performPointerClick(
        x: Float,
        y: Float,
        duration: Long = 100L
    ): Boolean {
        context.setActionViewTouchEnabled(false)
        delay(50)
        return try {
            context.performClickAsync(x, y, duration)
        } finally {
            context.setActionViewTouchEnabled(true)
        }
    }
}
