package io.github.lumiagg.gestureevo.inspire.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PathEffect
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.doOnPreDraw
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.InspireFunctionItemBinding
import io.github.lumiagg.gestureevo.gesture.ui.EdgeSide
import io.github.lumiagg.gestureevo.gesture.ui.FluidRippleView
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_INSPIRE_DOUBLE_CHECK
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class InspireView : ConstraintLayout {
    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init()
    }

    private lateinit var screenshotFab: FloatingActionButton
    private lateinit var loadingOverlay: View
    private lateinit var loadingIndicator: CircularProgressIndicator
    private lateinit var fluidRippleView: FluidRippleView
    private var functionItemBinding: InspireFunctionItemBinding? = null
    private var overlayToast: InspireOverlayToast? = null
    private var isDraggingFabTouchArea = false
    private var interactionEnabled = true
    var edgeBackEnabled = true

    val dragSelectRect: Rect = Rect()
    private val dragSelectPaint: Paint = Paint()
    private val dragSelectFillPaint: Paint = Paint()
    private val textBoxFillPaint: Paint = Paint()
    private val textBoxStrokePaint: Paint = Paint()
    private var detectedTextBoxes: List<Rect> = emptyList()
    private val dragSelectShrunkRect = RectF()
    private val detectedTextDrawRect = RectF()
    private var transitionAnimator: ValueAnimator? = null
    private var isDrawingPath = false
    private var isAdjustingEdge = false
    private var needDoubleCheck = false
    private var selectedEdge = Edge.NONE

    var inspireViewCallBack = object : InspireViewCallBack {}

    private enum class Edge {
        NONE, LEFT, TOP, RIGHT, BOTTOM,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private fun init() {
        setBackgroundColor(Color.TRANSPARENT)

        loadingOverlay = View(context).apply {
            id = generateViewId()
            setBackgroundColor(0x66000000)
            alpha = 0f
            visibility = GONE
        }
        addView(
            loadingOverlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                startToStart = LayoutParams.PARENT_ID
                endToEnd = LayoutParams.PARENT_ID
                topToTop = LayoutParams.PARENT_ID
                bottomToBottom = LayoutParams.PARENT_ID
            }
        )

        loadingIndicator = CircularProgressIndicator(context).apply {
            id = generateViewId()
            isIndeterminate = true
            indicatorSize = 44f.dp2px.toInt()
            trackThickness = 4f.dp2px.toInt()
            setIndicatorColor(DynamicColorUtils.primaryColor)
            visibility = GONE
        }
        addView(
            loadingIndicator,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                startToStart = LayoutParams.PARENT_ID
                endToEnd = LayoutParams.PARENT_ID
                topToTop = LayoutParams.PARENT_ID
                bottomToBottom = LayoutParams.PARENT_ID
            }
        )

        // Initialize FAB, pinned to bottom-right, styled in gray
        screenshotFab = FloatingActionButton(context).apply {
            id = generateViewId()
            setImageResource(android.R.drawable.ic_menu_camera)
            // Set FAB background to gray
            backgroundTintList =
                ColorStateList.valueOf(context.getColor(R.color.gray))
            // Set icon to white
            imageTintList = ColorStateList.valueOf(Color.WHITE)

            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                endToEnd = LayoutParams.PARENT_ID
                bottomToBottom = LayoutParams.PARENT_ID
                marginEnd = 16.dp2px.toInt()
                bottomMargin = 16.dp2px.toInt()
            }
        }
        addView(screenshotFab)
        setupFabTouchListener()

        fluidRippleView = FluidRippleView(context).apply {
            id = generateViewId()
            elevation = 24f.dp2px
        }
        addView(
            fluidRippleView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                startToStart = LayoutParams.PARENT_ID
                endToEnd = LayoutParams.PARENT_ID
                topToTop = LayoutParams.PARENT_ID
                bottomToBottom = LayoutParams.PARENT_ID
            }
        )

        // Dashed selection rectangle paint
        dragSelectPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = DRAG_SELECT_PAINT_WIDTH_HALF * 2
            val effects: PathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
            pathEffect = effects
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        dragSelectFillPaint.apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        textBoxFillPaint.apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        textBoxStrokePaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f.dp2px
            isAntiAlias = true
        }

        refreshDynamicColors()
    }

    fun refreshDynamicColors() {
        val applyColors = {
            val primary = DynamicColorUtils.primaryColor
            dragSelectPaint.color = primary
            dragSelectFillPaint.color = Color.argb(
                46,
                Color.red(primary),
                Color.green(primary),
                Color.blue(primary)
            )
            textBoxFillPaint.color = Color.argb(54, Color.red(primary), Color.green(primary), Color.blue(primary))
            textBoxStrokePaint.color = Color.argb(210, Color.red(primary), Color.green(primary), Color.blue(primary))
            loadingIndicator.setIndicatorColor(primary)
            functionItemBinding?.let { applyFunctionItemTheme(it) }
            overlayToast?.refreshTheme()
            if (::fluidRippleView.isInitialized) {
                fluidRippleView.applyAppearance()
            }
            invalidate()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyColors()
        } else {
            post { applyColors() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isDrawingPath) {
            dragSelectShrunkRect.apply {
                left =
                    minOf(dragSelectRect.left, dragSelectRect.right) - DRAG_SELECT_PAINT_WIDTH_HALF
                top =
                    minOf(dragSelectRect.top, dragSelectRect.bottom) - DRAG_SELECT_PAINT_WIDTH_HALF
                right =
                    maxOf(dragSelectRect.left, dragSelectRect.right) + DRAG_SELECT_PAINT_WIDTH_HALF
                bottom =
                    maxOf(dragSelectRect.top, dragSelectRect.bottom) + DRAG_SELECT_PAINT_WIDTH_HALF
            }
            canvas.drawRoundRect(dragSelectShrunkRect, 8f.dp2px, 8f.dp2px, dragSelectFillPaint)
            canvas.drawRoundRect(dragSelectShrunkRect, 8f.dp2px, 8f.dp2px, dragSelectPaint)
        }

        detectedTextBoxes.forEach { box ->
            detectedTextDrawRect.set(box)
            canvas.drawRoundRect(detectedTextDrawRect, 3f.dp2px, 3f.dp2px, textBoxFillPaint)
            canvas.drawRoundRect(detectedTextDrawRect, 3f.dp2px, 3f.dp2px, textBoxStrokePaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        overlayToast?.hide(immediate = true)
        if (::fluidRippleView.isInitialized) fluidRippleView.stopImmediate()
        stopTransitionAnimation()
    }

    fun showToast(text: CharSequence) {
        val show = {
            toastController().show(text)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            show()
        } else {
            post(show)
        }
    }

    private fun toastController(): InspireOverlayToast =
        overlayToast ?: InspireOverlayToast(this).also { overlayToast = it }

    fun startTransitionAnimation() {
        if (transitionAnimator != null) return
        loadingOverlay.visibility = VISIBLE
        loadingIndicator.visibility = VISIBLE
        loadingOverlay.bringToFront()
        loadingIndicator.bringToFront()
        loadingOverlay.alpha = 0.2f
        transitionAnimator = ValueAnimator.ofFloat(0.2f, 0.45f, 0.2f).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                loadingOverlay.alpha = animator.animatedValue as Float
            }
            start()
        }
    }

    fun stopTransitionAnimation() {
        transitionAnimator?.cancel()
        transitionAnimator = null
        loadingOverlay.alpha = 0f
        loadingOverlay.visibility = GONE
        loadingIndicator.visibility = GONE
    }

    private fun showFunctionItem() {
        val screenWidth = width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val screenHeight = height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels

        if (functionItemBinding == null) {
            functionItemBinding = InspireFunctionItemBinding.inflate(LayoutInflater.from(context))
            with(functionItemBinding!!) {
                applyFunctionItemTheme(this)
                addView(root)
                checkSelect.setOnClickListener {
                    inspireViewCallBack.onDragUp(Rect(dragSelectRect))
                }
                checkSelect.setOnLongClickListener {
                    inspireViewCallBack.onCopyImage(Rect(dragSelectRect))
                    true
                }
                cancelSelect.setOnClickListener {
                    inspireViewCallBack.onDragCancel()
                }
                saveImage.setOnClickListener {
                    inspireViewCallBack.onSaveImage(Rect(dragSelectRect), false)
                }
                saveImage.setOnLongClickListener {
                    inspireViewCallBack.onSaveImage(Rect(dragSelectRect), true)
                    true
                }
                pinImage.setOnClickListener {
                    inspireViewCallBack.onPinImage(Rect(dragSelectRect))
                }
                pinImage.setOnLongClickListener {
                    inspireViewCallBack.onPinImageLongPress(Rect(dragSelectRect))
                    true
                }
                editImage.setOnClickListener {
                    inspireViewCallBack.onEditImage(Rect(dragSelectRect))
                }
                shareImage.setOnClickListener {
                    inspireViewCallBack.onSharImage(Rect(dragSelectRect), false)
                }
                shareImage.setOnLongClickListener {
                    inspireViewCallBack.onSharImage(Rect(dragSelectRect), true)
                    true
                }
            }
        }

        // Calculate position and ensure it stays within screen bounds
        functionItemBinding?.root?.measure(
            MeasureSpec.makeMeasureSpec(screenWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(screenHeight, MeasureSpec.AT_MOST)
        )
        val itemWidth = functionItemBinding?.root?.measuredWidth?.toFloat()?.takeIf { it > 0 }
            ?: 160f.dp2px
        val itemHeight = functionItemBinding?.root?.measuredHeight?.toFloat()?.takeIf { it > 0 }
            ?: 80f.dp2px

        val gap = 10f.dp2px
        val belowY = dragSelectRect.bottom + gap
        val aboveY = dragSelectRect.top - gap - itemHeight
        val fitsBelow = belowY + itemHeight <= screenHeight - gap
        val fitsAbove = aboveY >= gap
        val placeInsideScreen = !fitsBelow && !fitsAbove

        val xPos = (if (placeInsideScreen) {
            (screenWidth - itemWidth) / 2f
        } else {
            dragSelectRect.right - itemWidth + 20f.dp2px
        }).coerceIn(0f, max(0f, screenWidth - itemWidth))
        val yPos = (if (placeInsideScreen) {
            screenHeight * FALLBACK_PANEL_CENTER_Y_RATIO - itemHeight / 2f
        } else if (fitsBelow) {
            belowY
        } else {
            aboveY
        }).coerceIn(0f, max(0f, screenHeight - itemHeight))

        // Set position and display
        functionItemBinding?.root?.apply {
            x = xPos
            y = yPos
            visibility = VISIBLE
        }
    }

    private fun applyFunctionItemTheme(binding: InspireFunctionItemBinding) {
        val surface = DynamicColorUtils.surfaceContainerHighColor
        val itemSurface = DynamicColorUtils.surfaceContainerHighestColor
        val onSurfaceVariant = DynamicColorUtils.onSurfaceVariantColor
        val outline = DynamicColorUtils.outlineColor
        val tint = ColorStateList.valueOf(onSurfaceVariant)

        binding.root.setCardBackgroundColor(surface)
        binding.functionDivider.setBackgroundColor(outline)

        val buttons = listOf(
            binding.shareImage,
            binding.pinImage,
            binding.editImage,
            binding.saveImage,
            binding.cancelSelect,
            binding.checkSelect
        )
        buttons.forEach { button ->
            button.imageTintList = tint
            button.setBackgroundColor(itemSurface)
        }
    }

    private fun hideFunctionItem() {
        functionItemBinding?.let {
            it.root.visibility = INVISIBLE
        }
    }

    private fun removeFunctionItem() {
        functionItemBinding?.let {
            it.root.visibility = GONE
            removeView(it.root)
            functionItemBinding = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFabTouchListener() {
        var fabInitialX = 0f
        var fabInitialY = 0f
        var touchInitialX = 0f
        var touchInitialY = 0f
        var longPressTriggered = false
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val longPressRunnable = Runnable {
            if (!interactionEnabled) return@Runnable
            longPressTriggered = true
            inspireViewCallBack.onSessionCaptureRequested {
                inspireViewCallBack.onSaveImage(Rect(0, 0, width, height), false)
            }
        }
        screenshotFab.setOnTouchListener { view, event ->
            if (!interactionEnabled) return@setOnTouchListener true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingFabTouchArea = true
                    longPressTriggered = false
                    view.removeCallbacks(longPressRunnable)
                    view.postDelayed(longPressRunnable, longPressTimeout)
                    touchInitialX = event.rawX
                    touchInitialY = event.rawY
                    fabInitialX = view.x
                    fabInitialY = view.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val moveThreshold = 10f
                    val dx = event.rawX - touchInitialX
                    val dy = event.rawY - touchInitialY

                    if (isDraggingFabTouchArea && (Math.abs(dx) > moveThreshold || Math.abs(dy) > moveThreshold)) {
                        view.removeCallbacks(longPressRunnable)
                        var newX = fabInitialX + dx
                        var newY = fabInitialY + dy

                        newX = newX.coerceIn(0f, width - view.width.toFloat())
                        newY = newY.coerceIn(0f, height - view.height.toFloat())

                        view.x = newX
                        view.y = newY
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPressRunnable)
                    val dx = event.rawX - touchInitialX
                    val dy = event.rawY - touchInitialY
                    val moveThreshold = 10f

                    if (abs(dx) < moveThreshold && abs(dy) < moveThreshold) {
                        if (!longPressTriggered) {
                            inspireViewCallBack.onSessionCaptureRequested {
                                clickShoot()
                            }
                        }
                    }
                    isDraggingFabTouchArea = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    isDraggingFabTouchArea = false
                    true
                }

                else -> false
            }
        }

    }

    private val touchHandler = TouchHandler()

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!interactionEnabled) return true
        if (touchHandler.isEdgeBacking) {
            return touchHandler.handleTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (isTouchInsideFunctionItem(event.x, event.y)) {
            return false
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // The delegated touch handler calls performClick before its text-box tap callback.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactionEnabled) return true
        if (isTouchInsideFunctionItem(event.x, event.y)) return true
        return touchHandler.handleTouchEvent(event)
    }

    private fun isTouchInsideFunctionItem(x: Float, y: Float): Boolean {
        val functionView = functionItemBinding?.root ?: return false
        if (functionView.visibility != VISIBLE) return false
        return x >= functionView.x &&
                x <= functionView.x + functionView.width &&
                y >= functionView.y &&
                y <= functionView.y + functionView.height
    }

    private fun detectEdge(x: Float, y: Float): Edge {
        if (dragSelectRect.isEmpty) return Edge.NONE
        val edgeDetectionThreshold = maxOf(20f.dp2px, EDGE_BACK_ZONE_DP.dp2px)
        val inVerticalRange = y >= (dragSelectRect.top - edgeDetectionThreshold) &&
                y <= (dragSelectRect.bottom + edgeDetectionThreshold)
        val inHorizontalRange = x >= (dragSelectRect.left - edgeDetectionThreshold) &&
                x <= (dragSelectRect.right + edgeDetectionThreshold)

        val nearLeft = inVerticalRange && abs(x - dragSelectRect.left) < edgeDetectionThreshold
        val nearRight = inVerticalRange && abs(x - dragSelectRect.right) < edgeDetectionThreshold
        val nearTop = inHorizontalRange && abs(y - dragSelectRect.top) < edgeDetectionThreshold
        val nearBottom = inHorizontalRange && abs(y - dragSelectRect.bottom) < edgeDetectionThreshold

        return when {
            nearLeft && nearTop -> Edge.TOP_LEFT
            nearRight && nearTop -> Edge.TOP_RIGHT
            nearLeft && nearBottom -> Edge.BOTTOM_LEFT
            nearRight && nearBottom -> Edge.BOTTOM_RIGHT
            nearLeft -> Edge.LEFT
            nearRight -> Edge.RIGHT
            nearTop -> Edge.TOP
            nearBottom -> Edge.BOTTOM
            else -> Edge.NONE
        }
    }

    private fun adjustEdge(x: Float, y: Float) {
        val newX = x.coerceIn(0f, width.toFloat())
        val newY = y.coerceIn(0f, height.toFloat())

        when (selectedEdge) {
            Edge.LEFT -> dragSelectRect.left = newX.toInt()
            Edge.RIGHT -> dragSelectRect.right = newX.toInt()
            Edge.TOP -> dragSelectRect.top = newY.toInt()
            Edge.BOTTOM -> dragSelectRect.bottom = newY.toInt()
            Edge.TOP_LEFT -> {
                dragSelectRect.left = newX.toInt()
                dragSelectRect.top = newY.toInt()
            }

            Edge.TOP_RIGHT -> {
                dragSelectRect.right = newX.toInt()
                dragSelectRect.top = newY.toInt()
            }

            Edge.BOTTOM_LEFT -> {
                dragSelectRect.left = newX.toInt()
                dragSelectRect.bottom = newY.toInt()
            }

            Edge.BOTTOM_RIGHT -> {
                dragSelectRect.right = newX.toInt()
                dragSelectRect.bottom = newY.toInt()
            }

            Edge.NONE -> {}
        }
    }

    suspend fun checkIsNeedDoubleCheck() {
        val enabled = withContext(Dispatchers.IO) {
            context.settingsStore.readKey(KEY_INSPIRE_DOUBLE_CHECK, false)
        }
        needDoubleCheck = enabled
    }

    suspend fun clear() = withContext(Dispatchers.Main) {
        touchHandler.reset()
        isDrawingPath = false
        needDoubleCheck = false
        isAdjustingEdge = false
        dragSelectRect.setEmpty()
        detectedTextBoxes = emptyList()
        overlayToast?.hide(immediate = true)
        if (::fluidRippleView.isInitialized) fluidRippleView.stopImmediate()
        removeFunctionItem()
        invalidate()
    }

    private fun clickShoot() {
        val fullScreenRect = Rect(0, 0, width, height)
        if (!needDoubleCheck) {
            inspireViewCallBack.onDragUp(fullScreenRect)
            return
        }
        touchHandler.reset()
        dragSelectRect.set(fullScreenRect)
        isDrawingPath = true
        showScreenShotFab(INVISIBLE)
        inspireViewCallBack.onSelectionStarted()
        inspireViewCallBack.onDragMove(Rect(fullScreenRect))
        showFunctionItem()
        invalidate()
    }

    fun captureFullScreen() {
        screenshotFab.visibility = INVISIBLE
        // Full-screen selection needs measured bounds, not a timed animation wait.
        doOnPreDraw {
            inspireViewCallBack.onSessionCaptureRequested {
                clickShoot()
            }
        }
    }

    fun showScreenShotFab(visibility: Int) = post {
        if (screenshotFab.visibility != visibility) {
            screenshotFab.visibility = visibility
        }
    }

    fun reSelect() {
        needDoubleCheck = true
        isDrawingPath = true
        showFunctionItem()
        invalidate()
        showScreenShotFab(VISIBLE)
    }

    fun setInteractionEnabled(enabled: Boolean, keepControlsVisible: Boolean = false) = post {
        interactionEnabled = enabled
        if (!enabled && !keepControlsVisible) {
            hideFunctionItem()
            showScreenShotFab(INVISIBLE)
        }
    }

    fun setDetectedTextBoxes(boxes: List<Rect>) {
        val update = {
            if (detectedTextBoxes != boxes) {
                detectedTextBoxes = boxes
                invalidate()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            post(update)
        }
    }

    private inner class TouchHandler {
        private var isMovingRect = false
        private var moveStartX = 0f
        private var moveStartY = 0f
        private var originalRect = Rect()
        private var downX = 0f
        private var downY = 0f
        private var touchCancel = false
        private var pendingTextBox: Rect? = null
        private var textBoxDownX = 0f
        private var textBoxDownY = 0f
        var isEdgeBacking = false
            private set
        private var edgeBackSide: EdgeSide? = null
        private var edgeBackDownX = 0f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        fun reset() {
            pendingTextBox = null
            isMovingRect = false
            isAdjustingEdge = false
            selectedEdge = Edge.NONE
            touchCancel = true
            stopEdgeBack(immediate = true)
        }

        private fun isInsideRect(x: Float, y: Float): Boolean {
            val padding = maxOf(20f.dp2px, EDGE_BACK_ZONE_DP.dp2px).toInt()
            val innerRect = Rect(
                dragSelectRect.left + padding,
                dragSelectRect.top + padding,
                dragSelectRect.right - padding,
                dragSelectRect.bottom - padding
            )
            return if (innerRect.width() > 0 && innerRect.height() > 0) {
                innerRect.contains(x.toInt(), y.toInt())
            } else {
                false
            }
        }

        private fun isTouchInSelectionArea(x: Float, y: Float): Boolean {
            if (isTouchInsideFunctionItem(x, y)) {
                return true
            }
            val touchX = x.toInt()
            val touchY = y.toInt()
            if (needDoubleCheck && detectedTextBoxes.isNotEmpty()) {
                if (detectedTextBoxes.any { it.contains(touchX, touchY) }) {
                    return true
                }
            }
            if (!dragSelectRect.isEmpty) {
                if (detectEdge(x, y) != Edge.NONE) {
                    return true
                }
                if (isInsideRect(x, y) || dragSelectRect.contains(touchX, touchY)) {
                    return true
                }
            }
            return false
        }

        fun handleTouchEvent(event: MotionEvent): Boolean {
            if (isDraggingFabTouchArea) return false

            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    touchCancel = false
                    val inSelection = isTouchInSelectionArea(event.rawX, event.rawY)
                    if (!inSelection && tryStartEdgeBack(event)) return true
                    pendingTextBox = if (needDoubleCheck) {
                        detectedTextBoxes
                            .filter { it.contains(event.rawX.toInt(), event.rawY.toInt()) }
                            .minByOrNull { it.width().toLong() * it.height() }
                            ?.let(::Rect)
                    } else {
                        null
                    }
                    textBoxDownX = event.rawX
                    textBoxDownY = event.rawY
                    if (pendingTextBox == null) startSelection(event.rawX, event.rawY)
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Detect multi-touch
                    if (event.pointerCount >= 2) {
                        if (isEdgeBacking) stopEdgeBack(immediate = true)
                        touchCancel = true
                        pendingTextBox = null
                        inspireViewCallBack.onDragCancel()
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isEdgeBacking) {
                        fluidRippleView.updatePosition(event.rawX, event.rawY)
                        return true
                    }
                    if (!touchCancel) {
                        if (pendingTextBox != null) {
                            if (!movedPastTextBoxTap(event)) return true
                            pendingTextBox = null
                            startSelection(textBoxDownX, textBoxDownY)
                        }
                        handleMove(event)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (isEdgeBacking) {
                        finishEdgeBack(event)
                        return true
                    }
                    if (!touchCancel) {
                        val textBox = pendingTextBox
                        pendingTextBox = null
                        if (textBox != null) {
                            if (!movedPastTextBoxTap(event) &&
                                textBox.contains(event.rawX.toInt(), event.rawY.toInt())
                            ) {
                                performClick()
                                inspireViewCallBack.onTextBoxClick(textBox)
                                return true
                            }
                            startSelection(textBoxDownX, textBoxDownY)
                            handleMove(event)
                        }
                        handleUp()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (isEdgeBacking) {
                        stopEdgeBack(immediate = false)
                        return true
                    }
                    pendingTextBox = null
                    isMovingRect = false
                    isAdjustingEdge = false
                    selectedEdge = Edge.NONE
                    if (needDoubleCheck && !dragSelectRect.isEmpty) {
                        showFunctionItem()
                        invalidate()
                    }
                }
            }
            return true
        }

        private fun detectBackEdge(x: Float): EdgeSide? {
            val zone = EDGE_BACK_ZONE_DP.dp2px
            val viewWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            return when {
                x <= zone -> EdgeSide.LEFT
                x >= viewWidth - zone -> EdgeSide.RIGHT
                else -> null
            }
        }

        private fun tryStartEdgeBack(event: MotionEvent): Boolean {
            if (!edgeBackEnabled) return false
            val side = detectBackEdge(event.rawX) ?: return false
            isEdgeBacking = true
            edgeBackSide = side
            edgeBackDownX = event.rawX
            fluidRippleView.applyAppearance()
            fluidRippleView.startFluidRipple(side, event.rawX, event.rawY)
            return true
        }

        private fun finishEdgeBack(event: MotionEvent) {
            val inward = when (edgeBackSide) {
                EdgeSide.LEFT -> event.rawX - edgeBackDownX
                EdgeSide.RIGHT -> edgeBackDownX - event.rawX
                else -> 0f
            }
            val committed = inward >= fluidRippleView.commitDistancePx
            stopEdgeBack(immediate = false)
            if (committed) {
                VibratorUtils.vibrator(context, true)
                inspireViewCallBack.onDragCancel()
            }
        }

        private fun stopEdgeBack(immediate: Boolean) {
            if (!isEdgeBacking) {
                if (immediate) fluidRippleView.stopImmediate()
                return
            }
            if (immediate) {
                fluidRippleView.stopImmediate()
            } else {
                fluidRippleView.stopFluidRipple()
            }
            isEdgeBacking = false
            edgeBackSide = null
        }

        private fun movedPastTextBoxTap(event: MotionEvent): Boolean =
            abs(event.rawX - textBoxDownX) > touchSlop ||
                abs(event.rawY - textBoxDownY) > touchSlop

        private fun startSelection(x: Float, y: Float) {
            isDrawingPath = true
            if (needDoubleCheck && !dragSelectRect.isEmpty) {
                selectedEdge = detectEdge(x, y)
                isAdjustingEdge = selectedEdge != Edge.NONE
                if (!isAdjustingEdge && (isInsideRect(x, y) || dragSelectRect.contains(x.toInt(), y.toInt()))) {
                    isMovingRect = true
                    moveStartX = x
                    moveStartY = y
                    originalRect.set(dragSelectRect)
                }
            }

            val isNewSelection = !isAdjustingEdge && !isMovingRect
            if (isNewSelection) {
                if (needDoubleCheck) inspireViewCallBack.onSelectionStarted()
                detectedTextBoxes = emptyList()
                downX = x
                downY = y
                dragSelectRect.set(downX.toInt(), downY.toInt(), downX.toInt(), downY.toInt())
            }
            inspireViewCallBack.onSessionCaptureRequested()
            hideFunctionItem()
            showScreenShotFab(INVISIBLE)
        }

        private fun handleMove(event: MotionEvent) {
            when {
                isMovingRect -> moveEntireRect(event)
                isAdjustingEdge -> adjustEdge(event.rawX, event.rawY)
                else -> createNewRect(event)
            }
            if (needDoubleCheck) {
                inspireViewCallBack.onDragMove(Rect(dragSelectRect))
            }
            invalidate()
        }

        private fun moveEntireRect(event: MotionEvent) {
            val dx = event.rawX - moveStartX
            val dy = event.rawY - moveStartY

            var newLeft = (originalRect.left + dx).toInt()
            var newTop = (originalRect.top + dy).toInt()
            var newRight = (originalRect.right + dx).toInt()
            var newBottom = (originalRect.bottom + dy).toInt()

            // Ensure rectangle stays within view bounds
            if (newLeft < 0) {
                newRight = originalRect.width()
                newLeft = 0
            } else if (newRight > width) {
                newLeft = width - originalRect.width()
                newRight = width
            }

            if (newTop < 0) {
                newBottom = originalRect.height()
                newTop = 0
            } else if (newBottom > height) {
                newTop = height - originalRect.height()
                newBottom = height
            }

            dragSelectRect.set(newLeft, newTop, newRight, newBottom)
        }

        private fun createNewRect(event: MotionEvent) {
            val dragSelectX = event.rawX.coerceIn(0f, width.toFloat())
            val dragSelectY = event.rawY.coerceIn(0f, height.toFloat())

            dragSelectRect.set(
                min(downX, dragSelectX).toInt(),
                min(downY, dragSelectY).toInt(),
                max(downX, dragSelectX).toInt(),
                max(downY, dragSelectY).toInt()
            )
        }

        private fun handleUp() {
            val l = minOf(dragSelectRect.left, dragSelectRect.right)
            val t = minOf(dragSelectRect.top, dragSelectRect.bottom)
            val r = maxOf(dragSelectRect.left, dragSelectRect.right)
            val b = maxOf(dragSelectRect.top, dragSelectRect.bottom)
            dragSelectRect.set(l, t, r, b)

            val tooSmallWidth = rectTooSmallWidth(dragSelectRect)
            val tooSmallHeight = rectTooSmallHeight(dragSelectRect)
            val isClick = tooSmallHeight && tooSmallWidth
            if (needDoubleCheck && !isClick) {
                isMovingRect = false
                if (isAdjustingEdge) {
                    isAdjustingEdge = false
                    selectedEdge = Edge.NONE
                }
                showFunctionItem()
                inspireViewCallBack.onDragMove(Rect(dragSelectRect))
                invalidate()
            } else {
                inspireViewCallBack.onDragUp(Rect(dragSelectRect))
            }
        }

    }

    fun rectTooSmallWidth(rect: Rect): Boolean {
        return rect.width() < DRAG_SELECT_CLICK_THRESHOLD
    }

    fun rectTooSmallHeight(rect: Rect): Boolean {
        return rect.height() < DRAG_SELECT_CLICK_THRESHOLD
    }

    companion object {
        private const val FALLBACK_PANEL_CENTER_Y_RATIO = 0.65f
        private const val EDGE_BACK_ZONE_DP = 24f
        val DRAG_SELECT_PAINT_WIDTH_HALF: Float = 3f.dp2px / 2
        val DRAG_SELECT_CLICK_THRESHOLD: Float = 20f.dp2px
    }
}

interface InspireViewCallBack {
    fun onSessionCaptureRequested(onCaptured: () -> Unit = {}) {}
    fun onSelectionStarted() {}
    fun onDragUp(dragSelectRect: Rect) {}
    fun onDragMove(dragSelectRect: Rect) {}
    fun onDragCancel() {}
    fun onCopyImage(dragSelectRect: Rect) {}
    fun onPinImage(dragSelectRect: Rect) {}
    fun onPinImageLongPress(dragSelectRect: Rect) {}
    fun onEditImage(dragSelectRect: Rect) {}
    fun onTextBoxClick(textBox: Rect) {}
    fun onSaveImage(dragSelectRect: Rect, isPress: Boolean) {}
    fun onSharImage(dragSelectRect: Rect, isPress: Boolean) {}
}
