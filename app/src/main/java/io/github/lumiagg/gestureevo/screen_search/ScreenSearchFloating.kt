package io.github.lumiagg.gestureevo.screen_search

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ScreenSearchFloatingViewBinding
import io.github.lumiagg.gestureevo.databinding.ScreenSearchIndicatorViewBinding
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.cropByRect
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.MlKitOcr
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.postOnMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenSearchFloating(private val context: GestureService) :
    BaseFloatingWindow(context, context, "ScreenSearchFloating") {
    private enum class ScrollDirection {
        UP, DOWN
    }

    private data class CaptureResult(
        val matches: List<Rect>,
        val failed: Boolean
    )

    private val binding: ScreenSearchFloatingViewBinding by lazy {
        val themeContext: Context = ContextThemeWrapper(context, R.style.Theme_GestureEVO)
        ScreenSearchFloatingViewBinding.inflate(LayoutInflater.from(themeContext))
    }

    private val layoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private val indicatorLayoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private val highlightLayoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private var panelAttached = false
    private var indicatorAttached = false
    private var highlightAttached = false

    private val indicatorBinding: ScreenSearchIndicatorViewBinding by lazy {
        val themeContext: Context = ContextThemeWrapper(context, R.style.Theme_GestureEVO)
        ScreenSearchIndicatorViewBinding.inflate(LayoutInflater.from(themeContext))
    }

    private val highlightView: ScreenSearchHighlightView by lazy {
        ScreenSearchHighlightView(context)
    }

    private var searchJob: Job? = null
    private var scrollDirection: ScrollDirection = ScrollDirection.DOWN
    private val maxAttempts = 60
    private val maxCaptureFailures = 3
    private val preStartDelayMs = 250L
    private val scrollSettleDelayMs = 220L
    private val revealScanRatio = 0.65f

    init {
        initFloatingWindow()
        initIndicatorWindow()
        initHighlightWindow()
        initView()
    }

    private fun initFloatingWindow() {
        layoutParams.configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            y = 24.dp2px.toInt(),
            title = "GestureEVO:ScreenSearchFloating:Panel",
        )
        layoutParams.softInputMode =
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
    }

    private fun initIndicatorWindow() {
        indicatorLayoutParams.configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            width = 48.dp2px.toInt(),
            height = 48.dp2px.toInt(),
            gravity = Gravity.END or Gravity.CENTER_VERTICAL,
            x = 24,
            title = "GestureEVO:ScreenSearchFloating:Indicator",
        )
        indicatorBinding.root.visibility = View.GONE
        // Clicking indicator pauses search and restores control panel.
        indicatorBinding.root.setOnClickListener {
            stopSearchAndShowPanel()
        }
    }

    private fun initHighlightWindow() {
        highlightLayoutParams.configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            title = "GestureEVO:ScreenSearchFloating:Highlight",
        )
        highlightView.visibility = View.GONE
    }

    private fun initView() {
        binding.root.visibility = View.GONE
        binding.btnClose.setOnClickListener { hide() }
        binding.switchAccessibilityMode.isChecked = true

        binding.directionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            scrollDirection = if (checkedId == R.id.btn_direction_up) {
                ScrollDirection.UP
            } else {
                ScrollDirection.DOWN
            }
            startSearch()
            binding.btnDirectionDown.isChecked = false
            binding.btnDirectionUp.isChecked = false
        }

        binding.keywordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch()
                true
            } else {
                false
            }
        }

        applyThemeColors()
        updateUiSearching(false)
    }

    fun show() = context.postOnMain {
        ensureViewsAttached()
        applyThemeColors()
        binding.root.visibility = View.VISIBLE
        layoutParams.x = 0
        updateViewLayoutSafely(binding.root, layoutParams)
        updateUiSearching(false)
        indicatorBinding.root.visibility = View.GONE
        updateWindowFocus(true)
        binding.keywordInput.setText("")
        updateIndicatorSearching(false)
    }

    fun hide() = context.postOnMain {
        stopSearch()
        highlightView.hideRects()
        highlightView.visibility = View.GONE
        binding.root.visibility = View.GONE
        indicatorBinding.root.visibility = View.GONE
        detachViewsIfNeeded()
    }

    fun destroy() = context.postOnMain {
        stopSearch()
        highlightView.hideRects()
        highlightView.visibility = View.GONE
        binding.root.visibility = View.GONE
        indicatorBinding.root.visibility = View.GONE
        detachViewsIfNeeded()
    }

    private fun startSearch() {
        val keyword = binding.keywordInput.text?.toString()?.trim().orEmpty()
        if (keyword.isEmpty()) {
            ToastHelper.showInUIThread(context, R.string.screen_search_empty_keyword)
            return
        }

        // Hide control panel and show indicator when starting search.
        context.postOnMain {
            binding.root.visibility = View.GONE
            indicatorBinding.root.visibility = View.VISIBLE
            updateWindowFocus(false)
        }

        highlightView.hideRects()
        updateUiSearching(true)
        updateIndicatorSearching(true)

        searchJob?.cancel()
        searchJob = context.launch {
            try {
                var currentStep = 0
                var captureFailures = 0
                var forceFullScan = true
                delay(preStartDelayMs)
                while (currentStep < maxAttempts) {
                    currentStep++

                    val result = if (binding.switchAccessibilityMode.isChecked) {
                        captureAndMatchAccessibility(keyword, !forceFullScan)
                    } else {
                        captureAndMatch(keyword, !forceFullScan)
                    }

                    if (result.failed) {
                        captureFailures++
                        forceFullScan = true
                        if (captureFailures >= maxCaptureFailures) {
                            break
                        }
                        delay(scrollSettleDelayMs)
                        continue
                    }
                    captureFailures = 0
                    forceFullScan = false

                    if (result.matches.isNotEmpty()) {
                        showFound(result.matches, keyword, currentStep)
                        break
                    }

                    val scrolled = performScroll(scrollDirection)
                    if (!scrolled) {
                        break
                    }
                }

                if (currentStep >= maxAttempts) {
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                updateUiSearching(false)
                updateIndicatorSearching(false)
            }
        }
    }

    private fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        updateUiSearching(false)
        updateIndicatorSearching(false)
    }

    /**
     * Stops search and displays the control panel.
     * Called when the user clicks the floating indicator.
     */
    private fun stopSearchAndShowPanel() {
        stopSearch()
            context.postOnMain {
            ensureViewsAttached()
            binding.root.visibility = View.VISIBLE
            indicatorBinding.root.visibility = View.GONE
            updateWindowFocus(true)
        }
    }

    private suspend fun captureAndMatchAccessibility(
        keyword: String,
        reducedScope: Boolean
    ): CaptureResult = withContext(Dispatchers.Main) {
        val root = context.rootInActiveWindow
            ?: return@withContext CaptureResult(matches = emptyList(), failed = true)
        val metrics = context.resources.displayMetrics
        val cropRect = if (reducedScope) {
            buildRevealCropRect(metrics.widthPixels, metrics.heightPixels, scrollDirection)
        } else {
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }

        val matches = mutableListOf<Rect>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        try {
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                try {
                    if (node.isVisibleToUser) {
                        val text = node.text ?: node.contentDescription
                        val content = text?.toString()?.trim().orEmpty()
                        if (content.contains(keyword, ignoreCase = true)) {
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            if (Rect.intersects(rect, cropRect)) {
                                matches.add(rect)
                            }
                        }
                    }
                    val childCount = node.childCount
                    for (i in 0 until childCount) {
                        node.getChild(i)?.let { child ->
                            stack.add(child)
                        }
                    }
                } finally {
                    if (node !== root) {
                        node.recycle()
                    }
                }
            }
        } finally {
            root.recycle()
        }

        CaptureResult(matches = matches, failed = false)
    }

    private suspend fun captureAndMatch(keyword: String, reducedScope: Boolean): CaptureResult {
        val bitmapHandle = try {
            context.takeManagedScreenshot() ?: return CaptureResult(matches = emptyList(), failed = true)
        } catch (e: Exception) {
            e.printStackTrace()
            return CaptureResult(matches = emptyList(), failed = true)
        }

        return bitmapHandle.use { fullBitmapHandle ->
            val (ocrBitmapHandle, offsetX, offsetY) = if (reducedScope) {
                val fullBitmap = fullBitmapHandle.requireBitmap()
                val cropRect = buildRevealCropRect(
                    fullBitmap.width,
                    fullBitmap.height,
                    scrollDirection
                )
                val cropped = fullBitmapHandle.cropByRect(cropRect)
                    ?: return CaptureResult(matches = emptyList(), failed = true)
                Triple(cropped, cropRect.left, cropRect.top)
            } else {
                val retained = fullBitmapHandle.acquire()
                    ?: return CaptureResult(matches = emptyList(), failed = true)
                Triple(retained, 0, 0)
            }

            val ocrResult = try {
                ocrBitmapHandle.use { handle ->
                    MlKitOcr.detect(handle.requireBitmap())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return CaptureResult(matches = emptyList(), failed = true)
            }

            val matched = ocrResult.filter { item ->
                val text = item.content?.trim() ?: ""
                if (!text.contains(keyword, ignoreCase = true)) {
                    return@filter false
                }
                true
            }

            val shifted = matched.map { item ->
                val rect = item.bound
                Rect(
                    rect.left + offsetX,
                    rect.top + offsetY,
                    rect.right + offsetX,
                    rect.bottom + offsetY
                )
            }

            CaptureResult(matches = shifted, failed = false)
        }
    }

    private suspend fun performScroll(direction: ScrollDirection): Boolean {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        // Swipe vertically along horizontal center of screen
        val x = screenWidth * 0.5f

        val startY: Float
        val endY: Float
        if (direction == ScrollDirection.DOWN) {
            startY = screenHeight * 0.80f
            endY = screenHeight * 0.20f
        } else {
            startY = screenHeight * 0.20f
            endY = screenHeight * 0.80f
        }

        // Use distinct swipe parameters to avoid being recognized as tap gestures
        val swipeDuration = 200L
        val holdDuration = 10L

        // Execute swipe: do not pause at end position to minimize tap detection risk
        val scrolled = context.performSwipeAsync(
            x, startY, x, endY,
            duration = swipeDuration,
            holdDuration = holdDuration,
            useDecelerationCurve = false
        )

        return scrolled
    }

    private fun buildRevealCropRect(
        width: Int,
        height: Int,
        direction: ScrollDirection
    ): Rect {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val revealHeight =
            (safeHeight * revealScanRatio).toInt().coerceIn(32, safeHeight)
        val top = if (direction == ScrollDirection.DOWN) {
            (safeHeight - revealHeight).coerceAtLeast(0)
        } else {
            0
        }
        val bottom = (top + revealHeight).coerceAtMost(safeHeight)
        return Rect(0, top, safeWidth, bottom)
    }

    private suspend fun showFound(rects: List<Rect>, keyword: String, step: Int) {
        rects.first()
        withContext(Dispatchers.Main) {
            highlightView.visibility = View.VISIBLE
            highlightView.showRects(rects)
        }
        ToastHelper.showInUIThread(context, R.string.screen_search_found_toast)
    }

    private fun updateWindowFocus(panelVisible: Boolean) {
        val newFlags = if (panelVisible) {
            layoutParams.flags and
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            layoutParams.flags or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (newFlags != layoutParams.flags) {
            layoutParams.flags = newFlags
            updateViewLayoutSafely(binding.root, layoutParams)
        }
    }

    private fun updateUiSearching(searching: Boolean) = context.postOnMain {
        // Stop button removed from panel; update remaining control states
        binding.directionGroup.isEnabled = !searching
        for (i in 0 until binding.directionGroup.childCount) {
            binding.directionGroup.getChildAt(i).isEnabled = !searching
        }
        binding.keywordInput.isEnabled = !searching
        binding.switchAccessibilityMode.isEnabled = !searching
    }

    private fun updateIndicatorSearching(searching: Boolean) = context.postOnMain {
        indicatorBinding.indicatorSpinner.visibility = if (searching) {
            View.VISIBLE
        } else {
            View.GONE
        }
        indicatorBinding.indicatorOk.visibility = if (searching) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    fun refreshTheme() = applyThemeColors()

    private fun applyThemeColors() {
        val primary = DynamicColorUtils.primaryColor
        val onSurfaceColor = DynamicColorUtils.onSurfaceColor
        DynamicColorUtils.onSurfaceVariantColor
        val surfaceHigh = DynamicColorUtils.surfaceContainerHighColor
        val outlineColor = DynamicColorUtils.outlineColor

        binding.panelCard.setCardBackgroundColor(surfaceHigh)
        binding.panelCard.strokeColor = outlineColor
        binding.titleText.setTextColor(onSurfaceColor)
        binding.keywordInput.setTextColor(onSurfaceColor)
        binding.keywordInput.setHintTextColor(primary)
        binding.switchAccessibilityMode.setTextColor(onSurfaceColor)

        val primaryTint = ColorStateList.valueOf(primary)
        binding.btnClose.setTextColor(primary)
        binding.btnClose.strokeColor = primaryTint
        binding.btnDirectionUp.setTextColor(primary)
        binding.btnDirectionUp.strokeColor = primaryTint
        binding.btnDirectionDown.setTextColor(primary)
        binding.btnDirectionDown.strokeColor = primaryTint

        indicatorBinding.root.setCardBackgroundColor(surfaceHigh)
        indicatorBinding.indicatorSpinner.indeterminateTintList = primaryTint
        indicatorBinding.indicatorOk.imageTintList = primaryTint

        highlightView.refreshColors()
    }

    private fun ensureViewsAttached() {
        attachPanelIfNeeded()
        attachIndicatorIfNeeded()
        attachHighlightIfNeeded()
    }

    private fun attachPanelIfNeeded() {
        if (panelAttached) return
        addViewSafely(binding.root, layoutParams) { panelAttached = true }
    }

    private fun attachIndicatorIfNeeded() {
        if (indicatorAttached) return
        addViewSafely(indicatorBinding.root, indicatorLayoutParams) { indicatorAttached = true }
    }

    private fun attachHighlightIfNeeded() {
        if (highlightAttached) return
        addViewSafely(highlightView, highlightLayoutParams) { highlightAttached = true }
    }

    private fun detachViewsIfNeeded() {
        if (panelAttached) {
            safeRemoveView(binding.root)
            panelAttached = false
        }
        if (indicatorAttached) {
            safeRemoveView(indicatorBinding.root)
            indicatorAttached = false
        }
        if (highlightAttached) {
            safeRemoveView(highlightView)
            highlightAttached = false
        }
    }

}
