package io.github.lumiagg.gestureevo.inspire

import io.github.lumiagg.gestureevo.databinding.PinDragCancelBarBinding

import androidx.core.graphics.scale

import android.animation.AnimatorSet
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.DragEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.cardview.widget.CardView
import androidx.core.animation.addListener
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.PinFloatingControlPanelBinding
import io.github.lumiagg.gestureevo.databinding.PinFloatingViewBinding
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.pin.PinDragSend
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.pin.PinPoolFloating
import io.github.lumiagg.gestureevo.pin.PinPoolReminderManager
import io.github.lumiagg.gestureevo.pin.PinPoolStore
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.NotificationPermissionUtils
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.postOnMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

class PictureFloating(
    private val context: GestureService,
) : BaseFloatingWindow(context, context, TAG) {
    private val contentPanelParams: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private val controlPanelParams: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private val panelRootParams: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private val dropTargetParams: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private val notificationDropTargetParams: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private val deleteDropTargetParams: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private val dropTargetOverlayParams: WindowManager.LayoutParams = WindowManager.LayoutParams()

    private lateinit var panelRootView: FrameLayout
    private lateinit var dropTargetOverlayView: FrameLayout
    private lateinit var contentBinding: PinFloatingViewBinding
    private lateinit var controlBinding: PinFloatingControlPanelBinding
    private lateinit var contentView: MaterialCardView
    private lateinit var controlPanelView: CardView
    private lateinit var dropTargetView: View
    private lateinit var notificationDropTargetView: View
    private lateinit var deleteDropTargetView: View
    private var content: Any? = null
    private var contentType: ContentType = ContentType.NONE
    private var dropTargetVisible = false
    private var dropTargetActive = false
    private var notificationDropTargetActive = false
    private var deleteDropTargetActive = false
    private var dropTargetBaseWidth = 0
    private var dropTargetBaseHeight = 0
    private var notificationDropTargetBaseWidth = 0
    private var notificationDropTargetBaseHeight = 0
    private var deleteDropTargetBaseWidth = 0
    private var deleteDropTargetBaseHeight = 0
    private var dropTargetAnimator: ValueAnimator? = null
    private var notificationDropTargetAnimator: ValueAnimator? = null
    private var deleteDropTargetAnimator: ValueAnimator? = null
    private val dropTargetIdleAlpha = 0.6f
    private val dropTargetActiveAlpha = 1f
    private val dropTargetActiveSize = 1.1f

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isMinimized = false
    private var dockedToStart = false
    private var dockedYRatio: Float = 0.5f
    private var lastClickTime = 0L
    private var initialWidth = 0
    private var initialHeight = 0
    private var restoredWidth = 0
    private var restoredHeight = 0
    private var dockedColor: Int = 0
    private var expandedCardColor: ColorStateList? = null
    var triggerSide: String? = null
    private var inScaleEvent = false
    private var inCornerScaleEvent = false
    private var scaleRatio = 1f
    private var scaleCorner = ScaleCorner.NONE
    private var panelRootLayoutRetryPosted = false
    private var dismissAnimator: ValueAnimator? = null
    private var isRemoving = false
    private var dragSending = false
    private var dragSendDroppedOnCancel = false
    private var dragSendGeneration = 0
    private var pinAlphaBeforeDrag = 1f
    private lateinit var sendCancelBar: View
    private val sendCancelParams = WindowManager.LayoutParams()
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private val longPressRunnable = Runnable { armDragSend() }
    private val sendCancelTimeoutRunnable = Runnable { endDragSend(sent = false) }

    private val screenWidth: Int
        get() = ScreenUtil.getScreenWidth(context)

    private val screenHeight: Int
        get() = ScreenUtil.getScreenHeight(context)

    private val statusBarHeight: Int
        get() = ScreenUtil.getStatusBarHeight(context)

    private val contentHeight: Int
        get() = contentView.height.takeIf { it > 0 }
            ?: contentPanelParams.height.takeIf { it > 0 }
            ?: initialHeight.takeIf { it > 0 }
            ?: 30f.dp2px.toInt()

    private val visibleScreenBottom: Int
        get() {
            val candidates = mutableListOf<Int>()
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            if (displayMetrics.heightPixels > 0) {
                candidates += displayMetrics.heightPixels
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.currentWindowMetrics
                val bottomInset = metrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                    .bottom
                candidates += metrics.bounds.bottom - bottomInset
            }
            if (::contentView.isInitialized && panelRootView.isAttachedToWindow) {
                val visibleFrame = Rect()
                contentView.getWindowVisibleDisplayFrame(visibleFrame)
                if (visibleFrame.bottom > visibleFrame.top) {
                    candidates += visibleFrame.bottom
                }
            }
            candidates += screenHeight
            return candidates.filter { it > 0 }.minOrNull() ?: screenHeight
        }

    enum class ContentType {
        TEXT,
        IMAGE,
        NONE
    }

    enum class ScaleCorner {
        NONE,
        BOTTOM_RIGHT,
        BOTTOM_LEFT
    }

    private val scaleGestureDetector =
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newWidth = (contentPanelParams.width * detector.scaleFactor).roundToInt()
                updateWindowSize(newWidth)
                return true
            }
        })

    init {
        activeInstances.add(this)
        initView()
        initFloatingWindow()
        initDropTargetOverlayWindow()
        initDropTarget()
        initNotificationDropTarget()
        initDeleteDropTarget()
        initSendCancelBar()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        panelRootView = object : FrameLayout(context) {
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                this@PictureFloating.onOrientationChanged()
            }
        }
        dropTargetOverlayView = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        contentBinding = PinFloatingViewBinding.inflate(LayoutInflater.from(context))
        contentView = contentBinding.root
        controlBinding =
            PinFloatingControlPanelBinding.inflate(LayoutInflater.from(context))
        controlPanelView = controlBinding.root
        controlPanelView.clipToOutline = false
        panelRootView.addView(contentView)
        dropTargetOverlayView.addView(controlPanelView)
        dropTargetOverlayView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && controlPanelView.isVisible) {
                val controlPanelBounds = Rect()
                controlPanelView.getHitRect(controlPanelBounds)
                if (!controlPanelBounds.contains(event.x.toInt(), event.y.toInt())) {
                    setControlPanelVisibility(false)
                    return@setOnTouchListener true
                }
            }
            false
        }

        setBorderColor(DynamicColorUtils.primaryColor)
        applyControlPanelTheme()

        controlBinding.transparencySlider.addOnChangeListener { _, value, _ ->
            contentView.alpha = value
        }

        controlBinding.rotateButton.setOnClickListener {
            rotateContent()
        }

        controlBinding.deleteButton.setOnClickListener {
            remove()
        }
        controlBinding.copyButton.setOnClickListener {
            if (contentType == ContentType.IMAGE) {
                (content as? ManagedBitmap)?.acquire()?.let { bitmapHandle ->
                    context.launch {
                        bitmapHandle.use {
                            InspireHelperUtils.copyBitmapToClipboard(context, it)
                        }
                    }
                }
            } else {
                (content as? String)?.let {
                    context.launch { ClipboardUtils.copyText(context, it) }
                }
            }
            setControlPanelVisibility(false)
        }

        contentView.setOnTouchListener { view, event ->
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    syncContentPanelFromRootIfNeeded()
                    initialX = contentPanelParams.x
                    initialY = contentPanelParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    inScaleEvent = false
                    inCornerScaleEvent = detectCornerResize(event.x, event.y)
                    if (inCornerScaleEvent) {
                        showResizeIndicator()
                    } else if (!isMinimized) {
                        contentView.removeCallbacks(longPressRunnable)
                        contentView.postDelayed(
                            longPressRunnable,
                            ViewConfiguration.getLongPressTimeout().toLong()
                        )
                    }
                    lastClickTime = System.currentTimeMillis()
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelPendingDragSend()
                    inScaleEvent = true
                    showResizeIndicator()
                    scaleGestureDetector.onTouchEvent(event)
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    hideResizeIndicator()
                }

                MotionEvent.ACTION_MOVE -> {
                    val distance = hypot(
                        (event.rawX - initialTouchX).toDouble(),
                        (event.rawY - initialTouchY).toDouble()
                    )
                    if (!dragSending && distance > touchSlop) {
                        contentView.removeCallbacks(longPressRunnable)
                    }
                    when {
                        dragSending -> {}
                        inScaleEvent && !isMinimized -> scaleGestureDetector.onTouchEvent(event)
                        inCornerScaleEvent && !isMinimized -> handleCornerResize(event)
                        else -> handleMove(event)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelPendingDragSend()
                    if (!inScaleEvent && !dragSending) {
                        val dropped = handleDropIfNeeded(event.rawX, event.rawY)
                        if (!dropped) {
                            handleClick(event)
                        }
                    }
                    hideDropTarget()
                    hideResizeIndicator()
                }
            }
            true
        }
    }

    private fun initFloatingWindow() {
        panelRootParams.apply {
            configureAccessibilityOverlay(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                width = screenWidth / 2,
                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                x = screenWidth / 4,
                y = screenHeight / 2,
                allowDisplayCutout = false,
                title = "GestureEVO:PictureFloating:Panel",
            )
        }
        contentPanelParams.apply {
            width = panelRootParams.width
            height = panelRootParams.height
            gravity = panelRootParams.gravity
            x = panelRootParams.x
            y = panelRootParams.y
        }

        addViewSafely(panelRootView, panelRootParams)
        updatePanelRootLayout()
        controlPanelView.visibility = View.GONE
    }

    private fun initDropTargetOverlayWindow() {
        dropTargetOverlayParams.apply {
            configureAccessibilityOverlay(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = screenHeight,
                allowDisplayCutout = false,
                title = "GestureEVO:PictureFloating:DropTargets",
            )
        }
    }

    private fun applyControlPanelTheme() {
        val surface = DynamicColorUtils.surfaceContainerHighColor
        val itemSurface = DynamicColorUtils.surfaceContainerHighestColor
        val onSurfaceVariant = DynamicColorUtils.onSurfaceVariantColor
        val primary = DynamicColorUtils.primaryColor
        val outline = DynamicColorUtils.outlineColor

        controlPanelView.setCardBackgroundColor(surface)
        controlBinding.controlDivider.setBackgroundColor(outline)

        val buttonTint = ColorStateList.valueOf(onSurfaceVariant)
        val sliderPrimaryTint = ColorStateList.valueOf(primary)
        val sliderInactiveTint = ColorStateList.valueOf(outline)

        listOf(
            controlBinding.rotateButton,
            controlBinding.copyButton,
            controlBinding.deleteButton
        ).forEach { button ->
            button.imageTintList = buttonTint
            button.setBackgroundColor(itemSurface)
        }

        controlBinding.transparencySlider.apply {
            thumbTintList = sliderPrimaryTint
            trackActiveTintList = sliderPrimaryTint
            trackInactiveTintList = sliderInactiveTint
            tickActiveTintList = sliderPrimaryTint
            tickInactiveTintList = sliderInactiveTint
            haloTintList = sliderPrimaryTint
        }
    }

    private fun resolveContentHeight(): Int = when (contentPanelParams.height) {
        ViewGroup.LayoutParams.WRAP_CONTENT -> contentView.height.takeIf { it > 0 }
            ?: contentPanelParams.height
        else -> contentPanelParams.height
    }

    private fun updatePanelRootLayout(): Boolean {
        val contentHeight = resolveContentHeight()
        val contentBoundsHeight = contentHeight.takeIf { it > 0 }
            ?: contentView.height.takeIf { it > 0 }
            ?: 0
        panelRootParams.x = contentPanelParams.x
        panelRootParams.y = contentPanelParams.y
        panelRootParams.width = contentPanelParams.width
        panelRootParams.height = if (contentPanelParams.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            maxOf(1, contentBoundsHeight)
        }
        updateChildLayout(
            contentView,
            width = contentPanelParams.width,
            height = contentPanelParams.height,
            left = 0,
            top = 0,
        )
        if (panelRootView.clipChildren || panelRootView.clipToPadding) {
            panelRootView.clipChildren = false
            panelRootView.clipToPadding = false
        }
        val updated = updateViewLayoutSafely(panelRootView, panelRootParams)
        if (!updated) {
            postPanelRootLayoutRetry()
        }
        return updated
    }

    private fun postPanelRootLayoutRetry() {
        if (panelRootLayoutRetryPosted) return
        panelRootLayoutRetryPosted = true
        if (panelRootView.isAttachedToWindow) {
            panelRootView.post {
                panelRootLayoutRetryPosted = false
                updatePanelRootLayout()
            }
        } else {
            panelRootView.doOnAttach {
                it.post {
                    panelRootLayoutRetryPosted = false
                    updatePanelRootLayout()
                }
            }
        }
    }

    private fun syncContentPanelFromRootIfNeeded() {
        if (!panelRootView.isAttachedToWindow) return
        if (panelRootParams.x != contentPanelParams.x || panelRootParams.y != contentPanelParams.y) {
            contentPanelParams.x = panelRootParams.x
            contentPanelParams.y = panelRootParams.y
        }
        if (panelRootParams.width > 0 && panelRootParams.width != contentPanelParams.width) {
            contentPanelParams.width = panelRootParams.width
        }
        if (contentPanelParams.height != ViewGroup.LayoutParams.WRAP_CONTENT &&
            panelRootParams.height > 0 &&
            panelRootParams.height != contentPanelParams.height
        ) {
            contentPanelParams.height = panelRootParams.height
        }
    }

    private fun updateDropTargetLayout() {
        updateChildLayout(
            dropTargetView,
            width = dropTargetParams.width,
            height = dropTargetParams.height,
            left = screenWidth - dropTargetParams.x - dropTargetParams.width,
            top = (screenHeight - dropTargetParams.height) / 2 + dropTargetParams.y,
        )
    }

    private fun resolveControlPanelWidth(): Int {
        measureControlPanelIfNeeded()
        return controlPanelView.measuredWidth.takeIf { it > 0 }
            ?: controlPanelView.width.takeIf { it > 0 }
            ?: controlPanelParams.width.takeIf { it > 0 }
            ?: 0
    }

    private fun resolveControlPanelHeight(): Int {
        measureControlPanelIfNeeded()
        return controlPanelView.measuredHeight.takeIf { it > 0 }
            ?: controlPanelView.height.takeIf { it > 0 }
            ?: controlPanelParams.height.takeIf { it > 0 }
            ?: 0
    }

    private fun measureControlPanelIfNeeded() {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST)
        controlPanelView.measure(widthSpec, heightSpec)
    }

    private fun updateNotificationDropTargetLayout() {
        updateChildLayout(
            notificationDropTargetView,
            width = notificationDropTargetParams.width,
            height = notificationDropTargetParams.height,
            left = (screenWidth - notificationDropTargetParams.width) / 2 + notificationDropTargetParams.x,
            top = notificationDropTargetParams.y,
        )
    }

    private fun updateDeleteDropTargetLayout() {
        updateChildLayout(
            deleteDropTargetView,
            width = deleteDropTargetParams.width,
            height = deleteDropTargetParams.height,
            left = (screenWidth - deleteDropTargetParams.width) / 2 + deleteDropTargetParams.x,
            top = visibleScreenBottom - deleteDropTargetParams.height - deleteDropTargetParams.y,
        )
    }

    private fun updateChildLayout(
        view: View,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
    ) {
        val params = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(width, height)
        params.width = width
        params.height = height
        params.leftMargin = left
        params.topMargin = top
        view.layoutParams = params
    }

    private fun postUntilAttached(
        view: View,
        shouldRun: () -> Boolean,
        action: () -> Unit,
    ): Boolean {
        if (view.isAttachedToWindow) return false
        view.post {
            if (shouldRun()) action()
        }
        return true
    }

    private fun ensureDropTargetOverlayAttached() {
        if (dropTargetOverlayView.isAttachedToWindow) return
        addViewSafely(dropTargetOverlayView, dropTargetOverlayParams)
    }

    private fun hideDropTargetOverlayIfIdle() {
        if (!dropTargetVisible &&
            dropTargetView.visibility != View.VISIBLE &&
            notificationDropTargetView.visibility != View.VISIBLE &&
            deleteDropTargetView.visibility != View.VISIBLE &&
            controlPanelView.visibility != View.VISIBLE
        ) {
            dropTargetOverlayView.visibility = View.GONE
        }
    }

    private fun resetDropTargetActiveStates() {
        dropTargetActive = false
        notificationDropTargetActive = false
        deleteDropTargetActive = false
    }

    private fun cancelDropTargetAnimators() {
        dropTargetAnimator?.cancel()
        dropTargetAnimator = null
        notificationDropTargetAnimator?.cancel()
        notificationDropTargetAnimator = null
        deleteDropTargetAnimator?.cancel()
        deleteDropTargetAnimator = null
    }

    private fun hideDropTargetsImmediately() {
        dropTargetView.visibility = View.GONE
        dropTargetView.translationX = 0f
        dropTargetView.alpha = dropTargetIdleAlpha
        notificationDropTargetView.visibility = View.GONE
        notificationDropTargetView.translationY = 0f
        notificationDropTargetView.alpha = dropTargetIdleAlpha
        deleteDropTargetView.visibility = View.GONE
        deleteDropTargetView.translationY = 0f
        deleteDropTargetView.alpha = dropTargetIdleAlpha
        dropTargetOverlayView.visibility = View.GONE
    }

    private fun setDropTargetOverlayTouchable(touchable: Boolean) {
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val hasFlag = dropTargetOverlayParams.flags and notTouchable != 0
        if (touchable && hasFlag) {
            dropTargetOverlayParams.flags = dropTargetOverlayParams.flags and notTouchable.inv()
        } else if (!touchable && !hasFlag) {
            dropTargetOverlayParams.flags = dropTargetOverlayParams.flags or notTouchable
        } else {
            return
        }
        if (!updateViewLayoutSafely(dropTargetOverlayView, dropTargetOverlayParams)) {
            dropTargetOverlayView.doOnAttach {
                it.post {
                    updateViewLayoutSafely(dropTargetOverlayView, dropTargetOverlayParams)
                }
            }
        }
    }

    private fun initDropTarget() {
        dropTargetView = buildDropTargetView()
        val dropWidth = 72f.dp2px.toInt()
        val dropHeight = 104f.dp2px.toInt()
        dropTargetParams.apply {
            width = dropWidth
            height = dropHeight
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = -(dropWidth / 2)
        }
        dropTargetBaseWidth = dropWidth
        dropTargetBaseHeight = dropHeight
        dropTargetOverlayView.addView(dropTargetView)
        updateDropTargetLayout()
        dropTargetView.visibility = View.GONE
    }

    private fun initNotificationDropTarget() {
        notificationDropTargetView = buildNotificationDropTargetView()
        val dropWidth = 160f.dp2px.toInt()
        val dropHeight = 72f.dp2px.toInt()
        notificationDropTargetParams.apply {
            width = dropWidth
            height = dropHeight
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = -(dropHeight / 2)
        }
        notificationDropTargetBaseWidth = dropWidth
        notificationDropTargetBaseHeight = dropHeight
        dropTargetOverlayView.addView(notificationDropTargetView)
        updateNotificationDropTargetLayout()
        notificationDropTargetView.visibility = View.GONE
    }

    private fun initDeleteDropTarget() {
        deleteDropTargetView = buildDeleteDropTargetView()
        val dropWidth = 180f.dp2px.toInt()
        val dropHeight = 72f.dp2px.toInt()
        deleteDropTargetParams.apply {
            width = dropWidth
            height = dropHeight
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = -(dropHeight / 2)
        }
        deleteDropTargetBaseWidth = dropWidth
        deleteDropTargetBaseHeight = dropHeight
        dropTargetOverlayView.addView(deleteDropTargetView)
        updateDeleteDropTargetLayout()
        deleteDropTargetView.visibility = View.GONE
    }

    private fun initSendCancelBar() {
        sendCancelBar = PinDragCancelBarBinding.inflate(LayoutInflater.from(DynamicColorUtils.dynamicThemedContext(context))).root
        PinDragSend.applyBarTheme(sendCancelBar)
        sendCancelParams.apply {
            configureAccessibilityOverlay(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.WRAP_CONTENT,
                gravity = Gravity.BOTTOM,
                allowDisplayCutout = false,
                title = "GestureEVO:PictureFloating:DragCancel",
            )
        }
        PinDragSend.attachCancelTarget(
            sendCancelBar,
            onDropped = { dragSendDroppedOnCancel = true },
        ) { droppedOnCancel, accepted ->
            endDragSend(sent = accepted && !droppedOnCancel)
        }
        contentView.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENDED -> {
                    endDragSend(sent = event.result && !dragSendDroppedOnCancel)
                    true
                }
                else -> false
            }
        }
    }

    private fun cancelPendingDragSend() {
        contentView.removeCallbacks(longPressRunnable)
        dragSendGeneration += 1
    }

    private fun armDragSend() {
        if (inScaleEvent || inCornerScaleEvent || isMinimized || isRemoving || dragSending) return
        val generation = ++dragSendGeneration
        dragSending = true
        VibratorUtils.vibrator(context, true)
        context.launch(Dispatchers.IO) {
            val clip = buildDragClip()
            withContext(Dispatchers.Main) {
                if (generation != dragSendGeneration || isRemoving) {
                    dragSending = false
                    return@withContext
                }
                if (clip == null || !contentView.isAttachedToWindow) {
                    failDragSendStart()
                    return@withContext
                }
                showSendCancelBar()
                if (!PinDragSend.start(contentView, clip)) {
                    hideSendCancelBar()
                    failDragSendStart()
                    return@withContext
                }
                hidePinWhileDragging()
            }
        }
    }

    private fun buildDragClip(): ClipData? {
        return when (contentType) {
            ContentType.TEXT -> {
                val text = (content as? String)?.trim().orEmpty()
                if (text.isEmpty()) null else PinDragSend.clipForText(text)
            }
            ContentType.IMAGE -> {
                val bitmap = (content as? ManagedBitmap)?.bitmapOrNull ?: return null
                val uri = PinDragSend.publishBitmap(context, bitmap) ?: return null
                PinDragSend.clipForImage(context, uri)
            }
            ContentType.NONE -> null
        }
    }

    private fun showSendCancelBar() {
        PinDragSend.applyBarTheme(sendCancelBar)
        sendCancelBar.removeCallbacks(sendCancelTimeoutRunnable)
        sendCancelBar.visibility = View.VISIBLE
        if (!sendCancelBar.isAttachedToWindow) {
            addViewSafely(sendCancelBar, sendCancelParams)
        } else {
            updateViewLayoutSafely(sendCancelBar, sendCancelParams)
        }
        sendCancelBar.postDelayed(sendCancelTimeoutRunnable, SEND_CANCEL_TIMEOUT_MS)
    }

    private fun hidePinWhileDragging() {
        setControlPanelVisibility(false)
        pinAlphaBeforeDrag = panelRootView.alpha
        panelRootView.alpha = 0f
        setPinWindowTouchable(false)
    }

    private fun restorePinAfterDrag() {
        if (isRemoving || !::panelRootView.isInitialized) return
        panelRootView.alpha = pinAlphaBeforeDrag.coerceIn(0f, 1f)
        setPinWindowTouchable(true)
    }

    private fun setPinWindowTouchable(touchable: Boolean) {
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val hasFlag = panelRootParams.flags and notTouchable != 0
        if (touchable && hasFlag) {
            panelRootParams.flags = panelRootParams.flags and notTouchable.inv()
        } else if (!touchable && !hasFlag) {
            panelRootParams.flags = panelRootParams.flags or notTouchable
        } else {
            return
        }
        if (panelRootView.isAttachedToWindow) {
            updateViewLayoutSafely(panelRootView, panelRootParams)
        }
    }

    private fun failDragSendStart() {
        dragSending = false
        ToastHelper.showInUIThread(
            context,
            context.getString(R.string.pin_drag_send_failed)
        )
    }

    private fun endDragSend(sent: Boolean) {
        if (!dragSending) return
        dragSending = false
        dragSendDroppedOnCancel = false
        hideSendCancelBar()
        if (sent) {
            remove()
        } else {
            restorePinAfterDrag()
        }
    }

    private fun hideSendCancelBar() {
        if (!::sendCancelBar.isInitialized) return
        sendCancelBar.removeCallbacks(sendCancelTimeoutRunnable)
        sendCancelBar.visibility = View.GONE
        if (sendCancelBar.isAttachedToWindow) {
            detachViewSafely(sendCancelBar, immediate = true)
        }
    }

    private fun buildDropTargetView(): View {
        val radius = 18f.dp2px
        return buildLabeledTargetView(
            text = context.getString(R.string.pin_pool_title),
            textSize = 12f,
            textColor = DynamicColorUtils.onSurfaceColor,
            fillColor = DynamicColorUtils.surfaceContainerHighColor,
            strokeColor = DynamicColorUtils.primaryColor,
            cornerRadii = floatArrayOf(
                radius, radius,
                0f, 0f,
                0f, 0f,
                radius, radius,
            ),
        )
    }

    private fun buildNotificationDropTargetView(): View {
        val radius = 18f.dp2px
        return buildLabeledTargetView(
            text = context.getString(R.string.pin_image_notification_drop_target),
            textSize = 12f,
            textColor = DynamicColorUtils.onSurfaceColor,
            fillColor = DynamicColorUtils.surfaceContainerHighColor,
            strokeColor = DynamicColorUtils.primaryColor,
            cornerRadii = floatArrayOf(
                0f, 0f,
                0f, 0f,
                radius, radius,
                radius, radius,
            ),
        )
    }

    private fun buildDeleteDropTargetView(): View {
        val radius = 18f.dp2px
        return buildLabeledTargetView(
            text = context.getString(R.string.pin_pool_action_delete),
            textSize = 13f,
            textColor = 0xFFFFFFFF.toInt(),
            fillColor = 0xFFE5484D.toInt(),
            strokeColor = 0xFFFFDAD6.toInt(),
            cornerRadii = floatArrayOf(
                radius, radius,
                radius, radius,
                0f, 0f,
                0f, 0f,
            ),
        )
    }

    private fun buildLabeledTargetView(
        text: String,
        textSize: Float,
        textColor: Int,
        fillColor: Int,
        strokeColor: Int,
        cornerRadii: FloatArray,
    ): View {
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadii = cornerRadii
                setColor(fillColor)
                setStroke(2f.dp2px.toInt(), strokeColor)
            }
            addView(
                TextView(context).apply {
                    this.text = text
                    setTextColor(textColor)
                    this.textSize = textSize
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
            alpha = dropTargetIdleAlpha
        }
    }

    private fun snapToEdge(toStart: Boolean) {
        val dockWidth = DOCK_WIDTH_DP.dp2px.toInt()
        val dockHeight = DOCK_HEIGHT_DP.dp2px.toInt().coerceAtMost(screenHeight)
        val halfWidth = dockWidth / 2
        if (!isMinimized) {
            restoredWidth = contentPanelParams.width
            restoredHeight = contentPanelParams.height
            dockedColor = imageDominantColor()
        }
        dockedToStart = toStart
        val centerY = contentPanelParams.y + contentPanelParams.height / 2
        dockedYRatio = (centerY.toFloat() / screenHeight.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)
        contentPanelParams.width = dockWidth
        contentPanelParams.height = dockHeight
        contentPanelParams.x = if (toStart) -halfWidth else screenWidth - halfWidth
        contentPanelParams.y = (centerY - dockHeight / 2)
            .coerceIn(0, (screenHeight - dockHeight).coerceAtLeast(0))
        isMinimized = true
        applyDockedChrome(true)
        setControlPanelVisibility(false)
        updatePanelRootLayout()
    }

    private fun expandFromCenter(anchorX: Float? = null, anchorY: Float? = null) {
        val maxAllowedWidth = (screenWidth * 0.9f).toInt()
        val width = (restoredWidth.takeIf { it > 0 } ?: initialWidth).coerceAtMost(maxAllowedWidth)
        val height = when (val saved = restoredHeight) {
            ViewGroup.LayoutParams.WRAP_CONTENT -> saved
            else -> {
                val base = saved.takeIf { it > 0 } ?: initialHeight
                if (contentType == ContentType.IMAGE && scaleRatio > 0f) {
                    (width / scaleRatio).toInt().coerceAtMost((screenHeight * 0.9f).toInt())
                } else {
                    base.coerceAtMost((screenHeight * 0.9f).toInt())
                }
            }
        }
        val laidOutHeight = if (height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            contentView.height.takeIf { it > 0 } ?: 100
        } else {
            height
        }
        val maxX = (screenWidth - width).coerceAtLeast(0)
        val maxY = (screenHeight - laidOutHeight).coerceAtLeast(0)
        val centerX = anchorX ?: contentPanelParams.x + contentPanelParams.width / 2f
        val centerY = anchorY ?: contentPanelParams.y + contentPanelParams.height / 2f
        contentView.animate().cancel()
        contentView.alpha = 0f
        contentPanelParams.width = width
        contentPanelParams.height = height
        contentPanelParams.x = (centerX - width / 2f).roundToInt().coerceIn(0, maxX)
        contentPanelParams.y = (centerY - laidOutHeight / 2f).roundToInt().coerceIn(0, maxY)
        isMinimized = false
        applyDockedChrome(false)
        updatePanelRootLayout()
        contentView.scaleX = 1f
        contentView.scaleY = 1f
        contentView.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()
    }

    private fun releaseDockedCapsule() {
        val centerX = contentPanelParams.x + contentPanelParams.width / 2f
        val distanceToEdge = minOf(centerX, screenWidth - centerX)
        if (distanceToEdge > DOCK_RELEASE_EXPAND_DP.dp2px) {
            expandFromCenter()
        } else {
            snapToEdge(centerX < screenWidth / 2f)
        }
    }

    private fun moveDockedCapsule(event: MotionEvent) {
        val deltaX = (event.rawX - initialTouchX).roundToInt()
        val deltaY = (event.rawY - initialTouchY).roundToInt()
        val maxY = (screenHeight - contentPanelParams.height).coerceAtLeast(0)
        contentPanelParams.x = initialX + deltaX
        contentPanelParams.y = (initialY + deltaY).coerceIn(0, maxY)
        dockedYRatio = ((contentPanelParams.y + contentPanelParams.height / 2f) / screenHeight.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)
        updatePanelRootLayout()
    }

    fun onOrientationChanged() {
        repositionToCurrentScreen()
        panelRootView.postDelayed({
            repositionToCurrentScreen()
        }, 150L)
    }

    private fun repositionToCurrentScreen() {
        if (isRemoving || !panelRootView.isAttachedToWindow) return
        val currentScreenWidth = screenWidth
        val currentScreenHeight = screenHeight

        if (dropTargetOverlayView.isAttachedToWindow) {
            dropTargetOverlayParams.width = WindowManager.LayoutParams.MATCH_PARENT
            dropTargetOverlayParams.height = currentScreenHeight
            updateViewLayoutSafely(dropTargetOverlayView, dropTargetOverlayParams)
            updateDropTargetLayout()
            updateNotificationDropTargetLayout()
            updateDeleteDropTargetLayout()
        }

        if (isMinimized) {
            val dockWidth = DOCK_WIDTH_DP.dp2px.toInt()
            val dockHeight = DOCK_HEIGHT_DP.dp2px.toInt().coerceAtMost(currentScreenHeight)
            val halfWidth = dockWidth / 2
            contentPanelParams.width = dockWidth
            contentPanelParams.height = dockHeight
            contentPanelParams.x = if (dockedToStart) -halfWidth else currentScreenWidth - halfWidth
            val targetCenterY = dockedYRatio * currentScreenHeight
            contentPanelParams.y = (targetCenterY - dockHeight / 2f).roundToInt()
                .coerceIn(0, (currentScreenHeight - dockHeight).coerceAtLeast(0))
            updatePanelRootLayout()
        } else {
            val maxAllowedWidth = (currentScreenWidth * 0.9f).toInt()
            if (contentPanelParams.width > maxAllowedWidth) {
                contentPanelParams.width = maxAllowedWidth
                if (scaleRatio > 0f && contentType == ContentType.IMAGE) {
                    contentPanelParams.height = (maxAllowedWidth / scaleRatio).toInt()
                }
            }
            val maxX = (currentScreenWidth - contentPanelParams.width).coerceAtLeast(0)
            val maxY = (currentScreenHeight - contentHeight).coerceAtLeast(0)
            contentPanelParams.x = contentPanelParams.x.coerceIn(0, maxX)
            contentPanelParams.y = contentPanelParams.y.coerceIn(0, maxY)
            updatePanelRootLayout()
            if (controlPanelView.isVisible) {
                updateControlPanelPosition()
            }
        }
    }

    private fun checkEdgeSnap() {
        val distanceFromLeft = contentPanelParams.x
        val distanceFromRight = screenWidth - (contentPanelParams.x + contentPanelParams.width)
        val edgeThreshold = -contentPanelParams.width / 3
        when {
            distanceFromLeft < edgeThreshold -> snapToEdge(true)
            distanceFromRight < edgeThreshold -> snapToEdge(false)
        }
    }

    private fun imageDominantColor(): Int {
        val bitmap = (content as? ManagedBitmap)?.bitmapOrNull
            ?: return DynamicColorUtils.primaryColor
        return runCatching {
            val size = 12
            val scaled = bitmap.scale(size, size, true)
            val pixels = IntArray(size * size)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            if (scaled !== bitmap) scaled.recycle()
            val counts = HashMap<Int, Int>()
            for (pixel in pixels) {
                if ((pixel ushr 24) < 128) continue
                val key = (pixel and 0x00F0F0F0.toInt()) or 0xFF000000.toInt()
                counts[key] = (counts[key] ?: 0) + 1
            }
            counts.maxByOrNull { it.value }?.key ?: DynamicColorUtils.primaryColor
        }.getOrDefault(DynamicColorUtils.primaryColor)
    }

    private fun applyDockedChrome(docked: Boolean) {
        val card = contentView as? MaterialCardView ?: return
        card.clipToOutline = true
        if (expandedCardColor == null) {
            expandedCardColor = card.cardBackgroundColor
        }
        if (docked) {
            contentView.alpha = 1f
            card.setCardBackgroundColor((dockedColor and 0x00FFFFFF) or 0xB4000000.toInt())
            val radius = minOf(contentPanelParams.width, contentPanelParams.height) / 2f
            card.shapeAppearanceModel = card.shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(radius)
                .build()
            contentBinding.imageView.visibility = View.GONE
            contentBinding.textView.visibility = View.GONE
        } else {
            expandedCardColor?.let { card.setCardBackgroundColor(it) }
            card.shapeAppearanceModel = card.shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(15f.dp2px)
                .build()
            contentBinding.imageView.visibility =
                if (contentType == ContentType.IMAGE) View.VISIBLE else View.GONE
            contentBinding.textView.visibility =
                if (contentType == ContentType.TEXT) View.VISIBLE else View.GONE
        }
    }

    private fun detectCornerResize(x: Float, y: Float): Boolean {
        val width = contentView.width
        val height = contentView.height
        if (width <= 0 || height <= 0) {
            scaleCorner = ScaleCorner.NONE
            return false
        }
        val cornerTouchArea = resizeCornerTouchArea(width, height)
        return when {
            x >= width - cornerTouchArea && y >= height - cornerTouchArea -> {
                scaleCorner = ScaleCorner.BOTTOM_RIGHT
                true
            }

            x <= cornerTouchArea && y >= height - cornerTouchArea -> {
                scaleCorner = ScaleCorner.BOTTOM_LEFT
                true
            }

            else -> {
                scaleCorner = ScaleCorner.NONE
                false
            }
        }
    }

    private fun resizeCornerTouchArea(width: Int, height: Int): Float {
        val maxTouchArea = 40f.dp2px
        val minTouchArea = 12f.dp2px
        val adaptiveTouchArea = minOf(width, height) * 0.28f
        return adaptiveTouchArea.coerceIn(minTouchArea, maxTouchArea)
    }

    private fun handleCornerResize(event: MotionEvent) {
        when (contentType) {
            ContentType.TEXT -> handleTextResize(event)
            ContentType.IMAGE -> handleImageResize(event)
            else -> {}
        }
    }

    private fun handleTextResize(event: MotionEvent) {
        val deltaX = event.rawX - lastTouchX
        val deltaY = event.rawY - lastTouchY
        lastTouchX = event.rawX
        lastTouchY = event.rawY

        var newWidth = contentPanelParams.width
        var newHeight = contentPanelParams.height

        when (scaleCorner) {
            ScaleCorner.BOTTOM_RIGHT -> {
                newWidth = (contentPanelParams.width + deltaX).roundToInt()
                if (contentPanelParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    newHeight = (contentPanelParams.height + deltaY).roundToInt()
                }
            }

            ScaleCorner.BOTTOM_LEFT -> {
                newWidth = (contentPanelParams.width - deltaX).roundToInt()
                if (contentPanelParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    newHeight = (contentPanelParams.height + deltaY).roundToInt()
                }
                contentPanelParams.x = (contentPanelParams.x + deltaX).roundToInt()
            }

            ScaleCorner.NONE -> return
        }

        newWidth = newWidth.coerceIn(50f.dp2px.toInt(), (screenWidth * 0.9f).toInt())
        if (contentPanelParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            newHeight = newHeight.coerceIn(50f.dp2px.toInt(), (screenHeight * 0.9f).toInt())
        }

        contentPanelParams.width = newWidth
        if (contentPanelParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            contentPanelParams.height = newHeight
        }

        initialWidth = contentPanelParams.width
        initialHeight = contentPanelParams.height

        updatePanelRootLayout()
    }

    private fun handleImageResize(event: MotionEvent) {
        val deltaX = event.rawX - lastTouchX
        val deltaY = event.rawY - lastTouchY
        lastTouchX = event.rawX
        lastTouchY = event.rawY

        val delta = sqrt(deltaX * deltaX + deltaY * deltaY) * 0.5 * if (deltaY > 0) 1 else -1
        val newWidth = (initialWidth + delta).toInt()
        updateWindowSize(newWidth)
    }

    private fun handleMove(event: MotionEvent) {
        val moveDistance = hypot(
            (event.rawX - initialTouchX).toDouble(),
            (event.rawY - initialTouchY).toDouble()
        )
        if (dragSending) return
        if (isMinimized) {
            if (moveDistance > 4f.dp2px) {
                moveDockedCapsule(event)
            }
            return
        }
        if (moveDistance > 4f.dp2px) {
            val deltaX = (event.rawX - initialTouchX).toInt()
            val deltaY = (event.rawY - initialTouchY).toInt()

            val minVisibleHeight = minOf(contentHeight, 60f.dp2px.toInt())
            contentPanelParams.x = initialX + deltaX
            contentPanelParams.y = minOf(initialY + deltaY, visibleScreenBottom - minVisibleHeight)

            updatePanelRootLayout()
            showDropTarget()
            updateDropTargetState(event.rawX, event.rawY)
        }
    }

    private fun handleClick(event: MotionEvent) {
        val clickDuration = System.currentTimeMillis() - lastClickTime
        val distance = hypot(
            (event.rawX - initialTouchX).toDouble(),
            (event.rawY - initialTouchY).toDouble()
        )
        val isClickEvent = clickDuration < 200 && distance < 4f.dp2px
        if (isClickEvent && isMinimized) {
            expandFromCenter(screenWidth / 2f, screenHeight / 2f)
        } else if (isClickEvent && !isMinimized) {
            setControlPanelVisibility(!controlPanelView.isVisible)
        } else if (isMinimized && distance > 10f.dp2px) {
            releaseDockedCapsule()
        } else if (!isMinimized && distance > 10f.dp2px) {
            checkEdgeSnap()
        }
    }

    private fun updateWindowSize(newWidth: Int) {
        // Record previous dimensions
        val oldWidth = contentPanelParams.width
        val oldHeight = contentPanelParams.height

        // Update width
        val newConstrainedWidth = newWidth.coerceIn(
            50f.dp2px.toInt(),
            (screenWidth).toInt()
        )

        // Compute new height
        val newHeight = when (contentType) {
            ContentType.TEXT -> oldHeight // Keep current height unchanged
            ContentType.IMAGE -> (newConstrainedWidth / scaleRatio).toInt().coerceIn(
                50f.dp2px.toInt(),
                (screenHeight).toInt()
            )

            ContentType.NONE -> oldHeight
        }

        // Compute dimension delta
        val widthDiff = newConstrainedWidth - oldWidth
        val heightDiff = newHeight - oldHeight

        // Compensate position so window scales relative to its center point
        contentPanelParams.x -= widthDiff / 2
        contentPanelParams.y -= heightDiff / 2

        // Ensure window stays within screen bounds
        contentPanelParams.x = contentPanelParams.x.coerceIn(
            0,
            screenWidth - newConstrainedWidth
        )
        contentPanelParams.y = contentPanelParams.y.coerceIn(
            0,
            screenHeight - newHeight
        )

        // Update dimensions
        contentPanelParams.width = newConstrainedWidth
        contentPanelParams.height = newHeight

        // Update initial dimension records
        initialHeight = contentPanelParams.height
        initialWidth = contentPanelParams.width

        // Apply update
        updatePanelRootLayout()
    }

    private fun setupInitialSize(width: Int, height: Int) {
        contentPanelParams.width = width.coerceIn(
            50f.dp2px.toInt(),
            (screenWidth * 0.9f).toInt()
        )

        contentPanelParams.height = when (height) {
            ViewGroup.LayoutParams.WRAP_CONTENT -> height
            else -> when (contentType) {
                ContentType.TEXT -> height.coerceIn(
                    50f.dp2px.toInt(),
                    (screenHeight * 0.9f).toInt()
                )

                ContentType.IMAGE -> (contentPanelParams.width / scaleRatio).toInt().coerceIn(
                    50f.dp2px.toInt(),
                    (screenHeight * 0.9f).toInt()
                )

                ContentType.NONE -> height
            }
        }

        contentPanelParams.x = screenWidth / 2 - contentPanelParams.width / 2
        contentPanelParams.y =
            screenHeight / 2 - (if (height == ViewGroup.LayoutParams.WRAP_CONTENT) 100 else contentPanelParams.height / 2)

        initialHeight = contentPanelParams.height
        initialWidth = contentPanelParams.width
        updatePanelRootLayout()
    }

    private fun setControlPanelVisibility(isShow: Boolean) {
        if (isShow) {
            controlBinding.rotateButton.isVisible = contentType == ContentType.IMAGE
            setDropTargetOverlayTouchable(true)
            ensureDropTargetOverlayAttached()
            dropTargetOverlayView.visibility = View.VISIBLE
            updateControlPanelPosition()
            controlPanelView.visibility = View.VISIBLE
        } else {
            controlPanelView.visibility = View.GONE
            setDropTargetOverlayTouchable(false)
            hideDropTargetOverlayIfIdle()
        }
    }

    private fun rotateContent() {
        if (contentType != ContentType.IMAGE || isMinimized) return
        val currentManaged = content as? ManagedBitmap ?: return
        val source = currentManaged.bitmapOrNull ?: return

        val matrix = Matrix().apply { postRotate(90f) }
        val rotated = try {
            source.useSoftwareBitmap { safeBitmap ->
                Bitmap.createBitmap(safeBitmap, 0, 0, safeBitmap.width, safeBitmap.height, matrix, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to rotate bitmap", t)
            return
        }

        val newManaged = ManagedBitmap.from(rotated)
        contentBinding.imageView.setImageBitmap(rotated)
        content = newManaged
        currentManaged.close()

        scaleRatio = rotated.width.toFloat() / rotated.height.toFloat()

        syncContentPanelFromRootIfNeeded()
        val oldWidth = contentPanelParams.width
        val oldHeight = contentPanelParams.height
        val centerX = contentPanelParams.x + oldWidth / 2f
        val centerY = contentPanelParams.y + oldHeight / 2f

        val maxAllowedWidth = (screenWidth * 0.95f).toInt()
        val maxAllowedHeight = (visibleScreenBottom * 0.95f).toInt()
        val minAllowedSize = 50f.dp2px.toInt()

        var targetWidth = oldHeight.coerceAtLeast(minAllowedSize)
        var targetHeight = (targetWidth / scaleRatio).roundToInt()

        if (targetWidth > maxAllowedWidth) {
            targetWidth = maxAllowedWidth
            targetHeight = (targetWidth / scaleRatio).roundToInt()
        }
        if (targetHeight > maxAllowedHeight) {
            targetHeight = maxAllowedHeight
            targetWidth = (targetHeight * scaleRatio).roundToInt()
        }
        targetWidth = targetWidth.coerceIn(minAllowedSize, maxAllowedWidth)
        targetHeight = (targetWidth / scaleRatio).roundToInt().coerceIn(minAllowedSize, maxAllowedHeight)

        val newX = (centerX - targetWidth / 2f).roundToInt()
            .coerceIn(0, (screenWidth - targetWidth).coerceAtLeast(0))
        val newY = (centerY - targetHeight / 2f).roundToInt()
            .coerceIn(0, (visibleScreenBottom - targetHeight).coerceAtLeast(0))

        contentPanelParams.width = targetWidth
        contentPanelParams.height = targetHeight
        contentPanelParams.x = newX
        contentPanelParams.y = newY
        initialWidth = targetWidth
        initialHeight = targetHeight
        restoredWidth = targetWidth
        restoredHeight = targetHeight

        updatePanelRootLayout()
        updateControlPanelPosition()
    }

    private fun updateControlPanelPosition() {
        val controlPanelWidth = resolveControlPanelWidth()
        val controlPanelHeight = resolveControlPanelHeight()
        if (controlPanelWidth <= 0 || controlPanelHeight <= 0) return
        val bottomMargin = CONTROL_PANEL_BOTTOM_MARGIN_DP.dp2px.toInt()
        val finalX = ((screenWidth - controlPanelWidth) / 2).coerceAtLeast(0)
        val finalY = (visibleScreenBottom - controlPanelHeight - bottomMargin)
            .coerceIn(0, screenHeight - controlPanelHeight)
        controlPanelParams.width = controlPanelWidth
        controlPanelParams.height = controlPanelHeight
        controlPanelParams.x = finalX
        controlPanelParams.y = finalY

        updateChildLayout(
            controlPanelView,
            width = controlPanelParams.width,
            height = controlPanelParams.height,
            left = controlPanelParams.x,
            top = controlPanelParams.y,
        )
    }

    private fun showResizeIndicator() {
        if (isMinimized) return
        contentBinding.resizeIndicator.visibility = View.VISIBLE
    }

    private fun hideResizeIndicator() {
        contentBinding.resizeIndicator.visibility = View.GONE
    }

    private fun showDropTarget() {
        if (!dropTargetVisible) {
            dropTargetVisible = true
            resetDropTargetActiveStates()
            ensureDropTargetOverlayAttached()
            dropTargetOverlayView.visibility = View.VISIBLE
            dropTargetAnimator?.cancel()
            dropTargetView.translationX = 16f.dp2px
            dropTargetView.alpha = 0f
            dropTargetView.scaleX = 1f
            dropTargetView.scaleY = 1f
            dropTargetView.visibility = View.VISIBLE
            startDropTargetShowAnimation()
            showDeleteDropTarget()
        }
        showNotificationDropTarget()
    }

    private fun startDropTargetShowAnimation() {
        if (postUntilAttached(
            dropTargetView,
            shouldRun = { dropTargetVisible && dropTargetView.isVisible },
            action = ::startDropTargetShowAnimation,
        )) return
        startTargetShowAnimation(
            view = dropTargetView,
            startTranslation = 16f.dp2px,
            setTranslation = { dropTargetView.translationX = it },
            setAnimator = { dropTargetAnimator = it },
        )
    }

    private fun showNotificationDropTarget() {
        if (contentType != ContentType.IMAGE) return
        ensureDropTargetOverlayAttached()
        if (notificationDropTargetView.isVisible) return
        notificationDropTargetAnimator?.cancel()
        notificationDropTargetParams.width = notificationDropTargetBaseWidth
        notificationDropTargetParams.height = notificationDropTargetBaseHeight
        notificationDropTargetParams.y = -(notificationDropTargetBaseHeight / 2)
        updateNotificationDropTargetLayout()
        notificationDropTargetView.translationY = (-16f).dp2px
        notificationDropTargetView.alpha = 0f
        notificationDropTargetView.visibility = View.VISIBLE
        if (postUntilAttached(
            notificationDropTargetView,
            shouldRun = { notificationDropTargetView.isVisible },
            action = ::startNotificationDropTargetShowAnimation,
        )) return
        startNotificationDropTargetShowAnimation()
    }

    private fun startNotificationDropTargetShowAnimation() {
        if (!notificationDropTargetView.isAttachedToWindow) return
        startTargetShowAnimation(
            view = notificationDropTargetView,
            startTranslation = (-16f).dp2px,
            setTranslation = { notificationDropTargetView.translationY = it },
            setAnimator = { notificationDropTargetAnimator = it },
        )
    }

    private fun showDeleteDropTarget() {
        ensureDropTargetOverlayAttached()
        deleteDropTargetAnimator?.cancel()
        deleteDropTargetParams.width = deleteDropTargetBaseWidth
        deleteDropTargetParams.height = deleteDropTargetBaseHeight
        deleteDropTargetParams.y = -(deleteDropTargetBaseHeight / 2)
        updateDeleteDropTargetLayout()
        deleteDropTargetView.translationY = 16f.dp2px
        deleteDropTargetView.alpha = 0f
        deleteDropTargetView.scaleX = 1f
        deleteDropTargetView.scaleY = 1f
        deleteDropTargetView.visibility = View.VISIBLE
        if (postUntilAttached(
            deleteDropTargetView,
            shouldRun = { deleteDropTargetView.isVisible },
            action = ::startDeleteDropTargetShowAnimation,
        )) return
        startDeleteDropTargetShowAnimation()
    }

    private fun startDeleteDropTargetShowAnimation() {
        if (!deleteDropTargetView.isAttachedToWindow) return
        startTargetShowAnimation(
            view = deleteDropTargetView,
            startTranslation = 16f.dp2px,
            setTranslation = { deleteDropTargetView.translationY = it },
            setAnimator = { deleteDropTargetAnimator = it },
        )
    }

    private fun startTargetShowAnimation(
        view: View,
        startTranslation: Float,
        setTranslation: (Float) -> Unit,
        setAnimator: (ValueAnimator) -> Unit,
    ) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                setTranslation(startTranslation * (1f - fraction))
                view.alpha = dropTargetIdleAlpha * fraction
            }
        }
        setAnimator(animator)
        animator.start()
    }

    private fun hideDropTarget() {
        if (dropTargetVisible) {
            dropTargetVisible = false
            resetDropTargetActiveStates()
            dropTargetAnimator?.cancel()
            if (!dropTargetView.isAttachedToWindow) {
                hideDropTargetsImmediately()
                hideNotificationDropTarget()
                hideDeleteDropTarget()
                return
            }
            val startAlpha = dropTargetView.alpha
            val startTranslation = dropTargetView.translationX
            dropTargetAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 140
                interpolator = DecelerateInterpolator()
                addUpdateListener { animation ->
                    if (!dropTargetView.isAttachedToWindow) {
                        cancel()
                        return@addUpdateListener
                    }
                    val fraction = animation.animatedFraction
                    dropTargetView.translationX =
                        startTranslation + (16f.dp2px - startTranslation) * fraction
                    dropTargetView.alpha = startAlpha * (1f - fraction)
                }
                addListener(onEnd = {
                    dropTargetView.visibility = View.GONE
                    dropTargetView.translationX = 0f
                    dropTargetView.alpha = dropTargetIdleAlpha
                    hideDropTargetOverlayIfIdle()
                })
                start()
            }
        }
        hideNotificationDropTarget()
        hideDeleteDropTarget()
    }

    private fun hideNotificationDropTarget() {
        if (!notificationDropTargetView.isAttachedToWindow ||
            notificationDropTargetView.visibility != View.VISIBLE
        ) {
            return
        }
        notificationDropTargetAnimator?.cancel()
        val startAlpha = notificationDropTargetView.alpha
        val startTranslation = notificationDropTargetView.translationY
        notificationDropTargetAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                if (!notificationDropTargetView.isAttachedToWindow) {
                    cancel()
                    return@addUpdateListener
                }
                val fraction = animation.animatedFraction
                notificationDropTargetView.translationY =
                    startTranslation + ((-16f).dp2px - startTranslation) * fraction
                notificationDropTargetView.alpha = startAlpha * (1f - fraction)
            }
            addListener(onEnd = {
                notificationDropTargetView.visibility = View.GONE
                notificationDropTargetView.translationY = 0f
                notificationDropTargetView.alpha = dropTargetIdleAlpha
                notificationDropTargetParams.width = notificationDropTargetBaseWidth
                notificationDropTargetParams.height = notificationDropTargetBaseHeight
                notificationDropTargetParams.y = -(notificationDropTargetBaseHeight / 2)
                updateNotificationDropTargetLayout()
                hideDropTargetOverlayIfIdle()
            })
            start()
        }
    }

    private fun hideDeleteDropTarget() {
        if (!deleteDropTargetView.isAttachedToWindow ||
            deleteDropTargetView.visibility != View.VISIBLE
        ) {
            return
        }
        deleteDropTargetAnimator?.cancel()
        val startAlpha = deleteDropTargetView.alpha
        val startTranslation = deleteDropTargetView.translationY
        deleteDropTargetAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                if (!deleteDropTargetView.isAttachedToWindow) {
                    cancel()
                    return@addUpdateListener
                }
                val fraction = animation.animatedFraction
                deleteDropTargetView.translationY =
                    startTranslation + (16f.dp2px - startTranslation) * fraction
                deleteDropTargetView.alpha = startAlpha * (1f - fraction)
            }
            addListener(onEnd = {
                deleteDropTargetView.visibility = View.GONE
                deleteDropTargetView.translationY = 0f
                deleteDropTargetView.alpha = dropTargetIdleAlpha
                deleteDropTargetView.scaleX = 1f
                deleteDropTargetView.scaleY = 1f
                deleteDropTargetParams.width = deleteDropTargetBaseWidth
                deleteDropTargetParams.height = deleteDropTargetBaseHeight
                deleteDropTargetParams.y = -(deleteDropTargetBaseHeight / 2)
                updateDeleteDropTargetLayout()
                hideDropTargetOverlayIfIdle()
            })
            start()
        }
    }

    private fun updateDropTargetState(rawX: Float, rawY: Float) {
        if (!dropTargetVisible) return
        val isActive = isPointInDropTarget(rawX, rawY)
        if (isActive != dropTargetActive) {
            setDropTargetActive(isActive)
        }
        val isNotificationActive = isPointInNotificationDropTarget(rawX, rawY)
        if (isNotificationActive != notificationDropTargetActive) {
            setNotificationDropTargetActive(isNotificationActive)
        }
        val isDeleteActive = isPointInDeleteDropTarget(rawX, rawY)
        if (isDeleteActive != deleteDropTargetActive) {
            setDeleteDropTargetActive(isDeleteActive)
        }
    }

    private fun setDropTargetActive(active: Boolean) {
        if (!dropTargetView.isAttachedToWindow) {
            dropTargetAnimator?.cancel()
            return
        }
        dropTargetActive = active
        val startAlpha = dropTargetView.alpha
        val startTranslation = dropTargetView.translationX
        val startWidth = dropTargetParams.width
        val startHeight = dropTargetParams.height
        val endAlpha = if (active) dropTargetActiveAlpha else dropTargetIdleAlpha
        val endTranslation = 0f
        val endWidth =
            if (active) (dropTargetBaseWidth * dropTargetActiveSize).toInt()
            else dropTargetBaseWidth
        val endHeight =
            if (active) (dropTargetBaseHeight * dropTargetActiveSize).toInt()
            else dropTargetBaseHeight

        if (startAlpha == endAlpha &&
            startTranslation == endTranslation &&
            startWidth == endWidth &&
            startHeight == endHeight
        ) {
            return
        }

        dropTargetAnimator?.cancel()
        dropTargetAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                if (!dropTargetView.isAttachedToWindow) {
                    cancel()
                    return@addUpdateListener
                }
                val fraction = animation.animatedFraction
                dropTargetView.alpha = startAlpha + (endAlpha - startAlpha) * fraction
                dropTargetView.translationX =
                    startTranslation + (endTranslation - startTranslation) * fraction
                val width = (startWidth + (endWidth - startWidth) * fraction).toInt()
                val height = (startHeight + (endHeight - startHeight) * fraction).toInt()
                dropTargetParams.width = width
                dropTargetParams.height = height
                dropTargetParams.x = -(width / 2)
                updateDropTargetLayout()
            }
            start()
        }
    }

    private fun handleDropIfNeeded(rawX: Float, rawY: Float): Boolean {
        if (!dropTargetVisible) return false
        return when {
            isPointInDeleteDropTarget(rawX, rawY) -> {
                remove()
                true
            }

            isPointInNotificationDropTarget(rawX, rawY) -> {
                postCurrentImageToNotification()
                true
            }

            isPointInDropTarget(rawX, rawY) -> {
                pinCurrentContent()
                true
            }

            else -> false
        }
    }

    private fun isPointInDropTarget(rawX: Float, rawY: Float): Boolean {
        return isPointInTarget(rawX, rawY, dropTargetView) { targetWidth, targetHeight ->
            Rect(
                screenWidth - dropTargetParams.x - targetWidth,
                (screenHeight - targetHeight) / 2 + dropTargetParams.y,
                screenWidth - dropTargetParams.x,
                (screenHeight - targetHeight) / 2 + dropTargetParams.y + targetHeight,
            )
        }
    }

    private fun isPointInDeleteDropTarget(rawX: Float, rawY: Float): Boolean {
        return isPointInTarget(rawX, rawY, deleteDropTargetView) { targetWidth, targetHeight ->
            val left = (screenWidth - targetWidth) / 2 + deleteDropTargetParams.x
            val top = visibleScreenBottom - targetHeight - deleteDropTargetParams.y
            Rect(left, top, left + targetWidth, top + targetHeight)
        }
    }

    private fun isPointInNotificationDropTarget(rawX: Float, rawY: Float): Boolean {
        return contentType == ContentType.IMAGE &&
            isPointInTarget(rawX, rawY, notificationDropTargetView) { targetWidth, targetHeight ->
                val left = (screenWidth - targetWidth) / 2 + notificationDropTargetParams.x
                Rect(
                    left,
                    notificationDropTargetParams.y,
                    left + targetWidth,
                    notificationDropTargetParams.y + targetHeight,
                )
            }
    }

    private fun isPointInTarget(
        rawX: Float,
        rawY: Float,
        targetView: View,
        boundsForSize: (width: Int, height: Int) -> Rect,
    ): Boolean {
        if (!targetView.isAttachedToWindow || targetView.visibility != View.VISIBLE) return false
        val layoutParams = targetView.layoutParams
        val targetWidth = targetView.width.takeIf { it > 0 }
            ?: layoutParams.width.takeIf { it > 0 }
            ?: return false
        val targetHeight = targetView.height.takeIf { it > 0 }
            ?: layoutParams.height.takeIf { it > 0 }
            ?: return false
        val bounds = boundsForSize(targetWidth, targetHeight)
        return rawX >= bounds.left && rawX <= bounds.right &&
            rawY >= bounds.top && rawY <= bounds.bottom
    }

    private fun pinCurrentContent() {
        val currentContent = content ?: return
        context.launch(Dispatchers.IO) {
            when (contentType) {
                ContentType.TEXT -> {
                    PinPoolStore.addTextPin(context, currentContent as String)
                }

                ContentType.IMAGE -> {
                    (currentContent as ManagedBitmap).acquire()?.use { bitmapHandle ->
                        PinPoolStore.addImagePin(context, bitmapHandle.requireBitmap())
                    }
                }

                ContentType.NONE -> return@launch
            }
            context.postOnMain {
                val sideToUse = triggerSide ?: TriggerSide.RIGHT
                PinPoolFloating.start(context, side = sideToUse)
                remove()
            }
        }
    }

    private fun postCurrentImageToNotification() {
        val currentContent = content as? ManagedBitmap ?: return
        context.launch(Dispatchers.IO) {
            val posted = currentContent.acquire()?.use { bitmapHandle ->
                PinPoolReminderManager.showSilentImageNotification(
                    context,
                    bitmapHandle.requireBitmap()
                )
            } ?: false
            context.postOnMain {
                if (posted) {
                    remove()
                } else {
                    NotificationPermissionUtils.showDisabledPromptIfNeeded(context)
                }
            }
        }
    }

    fun setBorderColor(@ColorInt color: Int) {
        val border = contentBinding.resizeIndicator.background as GradientDrawable
        border.setStroke(3f.dp2px.toInt(), color)
    }

    fun refreshTheme() = context.postOnMain {
        if (!::controlPanelView.isInitialized || !::contentBinding.isInitialized) return@postOnMain
        applyControlPanelTheme()
        setBorderColor(DynamicColorUtils.primaryColor)
        if (::sendCancelBar.isInitialized) PinDragSend.applyBarTheme(sendCancelBar)
    }

    private fun releaseContent() {
        contentBinding.imageView.setImageBitmap(null)
        contentBinding.imageView.visibility = View.GONE
        contentBinding.textView.text = null
        contentBinding.textView.visibility = View.GONE
        if (contentType == ContentType.IMAGE) {
            (content as? ManagedBitmap)?.close()
        }
        content = null
        contentType = ContentType.NONE
    }

    fun remove() = context.postOnMain {
        if (isRemoving) return@postOnMain
        isRemoving = true
        cancelDropTargetAnimators()
        dismissAnimator?.cancel()
        dismissAnimator = null

        setControlPanelVisibility(false)
        hideDropTargetsImmediately()

        if (!panelRootView.isAttachedToWindow) {
            detachAndRelease()
            return@postOnMain
        }

        panelRootView.isEnabled = false
        contentView.isEnabled = false
        val startAlpha = panelRootView.alpha
        val startScaleX = panelRootView.scaleX
        val startScaleY = panelRootView.scaleY
        dismissAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DISMISS_ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val scale = 1f - 0.06f * fraction
                panelRootView.alpha = startAlpha * (1f - fraction)
                panelRootView.scaleX = startScaleX * scale
                panelRootView.scaleY = startScaleY * scale
            }
            addListener(onEnd = {
                detachAndRelease()
            })
            start()
        }
    }

    private fun detachAndRelease() {
        dismissAnimator = null
        hideSendCancelBar()
        detachViewSafely(panelRootView, immediate = true)
        detachViewSafely(dropTargetOverlayView, immediate = true)
        releaseContent()
        activeInstances.remove(this@PictureFloating)
    }

    fun destroy() = context.postOnMain {
        isRemoving = true
        cancelDropTargetAnimators()
        dismissAnimator?.cancel()
        dismissAnimator = null
        setControlPanelVisibility(false)
        hideDropTargetsImmediately()
        hideSendCancelBar()
        if (::panelRootView.isInitialized) {
            panelRootView.animate().cancel()
            panelRootView.visibility = View.GONE
            detachViewSafely(panelRootView, immediate = true)
        }
        if (::dropTargetOverlayView.isInitialized) {
            dropTargetOverlayView.visibility = View.GONE
            detachViewSafely(dropTargetOverlayView, immediate = true)
        }
        if (::contentBinding.isInitialized) {
            releaseContent()
        }
        activeInstances.remove(this@PictureFloating)
    }

    private fun setNotificationDropTargetActive(active: Boolean) {
        if (!notificationDropTargetView.isAttachedToWindow) {
            notificationDropTargetAnimator?.cancel()
            return
        }
        notificationDropTargetActive = active
        val startAlpha = notificationDropTargetView.alpha
        val startTranslation = notificationDropTargetView.translationY
        val startWidth = notificationDropTargetParams.width
        val startHeight = notificationDropTargetParams.height
        val endAlpha = if (active) dropTargetActiveAlpha else dropTargetIdleAlpha
        val endTranslation = 0f
        val endWidth =
            if (active) (notificationDropTargetBaseWidth * dropTargetActiveSize).toInt()
            else notificationDropTargetBaseWidth
        val endHeight =
            if (active) (notificationDropTargetBaseHeight * dropTargetActiveSize).toInt()
            else notificationDropTargetBaseHeight

        if (startAlpha == endAlpha &&
            startTranslation == endTranslation &&
            startWidth == endWidth &&
            startHeight == endHeight
        ) {
            return
        }

        notificationDropTargetAnimator?.cancel()
        notificationDropTargetAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                if (!notificationDropTargetView.isAttachedToWindow) {
                    cancel()
                    return@addUpdateListener
                }
                val fraction = animation.animatedFraction
                notificationDropTargetView.alpha = startAlpha + (endAlpha - startAlpha) * fraction
                notificationDropTargetView.translationY =
                    startTranslation + (endTranslation - startTranslation) * fraction
                notificationDropTargetParams.width =
                    (startWidth + (endWidth - startWidth) * fraction).toInt()
                notificationDropTargetParams.height =
                    (startHeight + (endHeight - startHeight) * fraction).toInt()
                notificationDropTargetParams.y = -(notificationDropTargetParams.height / 2)
                updateNotificationDropTargetLayout()
            }
            start()
        }
    }

    private fun setDeleteDropTargetActive(active: Boolean) {
        if (!deleteDropTargetView.isAttachedToWindow) {
            deleteDropTargetAnimator?.cancel()
            return
        }
        deleteDropTargetActive = active
        val startAlpha = deleteDropTargetView.alpha
        val startTranslation = deleteDropTargetView.translationY
        val startWidth = deleteDropTargetParams.width
        val startHeight = deleteDropTargetParams.height
        val startScaleX = deleteDropTargetView.scaleX
        val startScaleY = deleteDropTargetView.scaleY
        val endAlpha = if (active) dropTargetActiveAlpha else dropTargetIdleAlpha
        val endTranslation = 0f
        val endWidth =
            if (active) (deleteDropTargetBaseWidth * dropTargetActiveSize).toInt()
            else deleteDropTargetBaseWidth
        val endHeight =
            if (active) (deleteDropTargetBaseHeight * dropTargetActiveSize).toInt()
            else deleteDropTargetBaseHeight
        val endScale = if (active) 1.04f else 1f

        if (startAlpha == endAlpha &&
            startTranslation == endTranslation &&
            startWidth == endWidth &&
            startHeight == endHeight &&
            startScaleX == endScale &&
            startScaleY == endScale
        ) {
            return
        }

        deleteDropTargetAnimator?.cancel()
        deleteDropTargetAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                if (!deleteDropTargetView.isAttachedToWindow) {
                    cancel()
                    return@addUpdateListener
                }
                val fraction = animation.animatedFraction
                deleteDropTargetView.alpha = startAlpha + (endAlpha - startAlpha) * fraction
                deleteDropTargetView.translationY =
                    startTranslation + (endTranslation - startTranslation) * fraction
                deleteDropTargetView.scaleX = startScaleX + (endScale - startScaleX) * fraction
                deleteDropTargetView.scaleY = startScaleY + (endScale - startScaleY) * fraction
                deleteDropTargetParams.width =
                    (startWidth + (endWidth - startWidth) * fraction).toInt()
                deleteDropTargetParams.height =
                    (startHeight + (endHeight - startHeight) * fraction).toInt()
                deleteDropTargetParams.y = -(deleteDropTargetParams.height / 2)
                updateDeleteDropTargetLayout()
            }
            start()
        }
    }

    companion object {
        const val TAG = "PictureFloating"
        private const val CONTROL_PANEL_BOTTOM_MARGIN_DP = 24f
        private const val DISMISS_ANIMATION_DURATION_MS = 140L
        private const val DOCK_WIDTH_DP = 40f
        private const val DOCK_HEIGHT_DP = 76f
        private const val DOCK_RELEASE_EXPAND_DP = 72f
        private const val SEND_CANCEL_TIMEOUT_MS = 60_000L
        private val activeInstances = linkedSetOf<PictureFloating>()

        fun build(service: GestureService, image: ManagedBitmap, side: String? = null) {
            service.postOnMain {
                val pictureFloating = PictureFloating(service)
                val bitmap = image.bitmapOrNull
                if (bitmap == null) {
                    image.close()
                    pictureFloating.remove()
                    return@postOnMain
                }
                pictureFloating.triggerSide = side
                pictureFloating.content = image
                pictureFloating.contentType = ContentType.IMAGE
                pictureFloating.contentBinding.imageView.visibility = View.VISIBLE
                pictureFloating.contentBinding.textView.visibility = View.GONE
                pictureFloating.contentBinding.imageView.setImageBitmap(bitmap)
                pictureFloating.scaleRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                pictureFloating.setupInitialSize(bitmap.width, bitmap.height)
            }
        }

        fun build(service: GestureService, text: String, side: String? = null) {
            service.postOnMain {
                PictureFloating(service).apply {
                    triggerSide = side
                    content = text
                    contentType = ContentType.TEXT
                    contentBinding.imageView.visibility = View.GONE
                    contentBinding.textView.visibility = View.VISIBLE
                    contentBinding.textView.text = text

                    val defaultWidth = (service.resources.displayMetrics.widthPixels * 0.8f).toInt()
                    setupInitialSize(defaultWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                    scaleRatio = 1f
                }
            }
        }

        fun destroyAll() {
            activeInstances.toList().forEach { it.destroy() }
        }

        fun refreshAllThemes() {
            activeInstances.forEach { it.refreshTheme() }
        }

        fun onOrientationChanged() {
            activeInstances.toList().forEach { it.onOrientationChanged() }
        }
    }
}
