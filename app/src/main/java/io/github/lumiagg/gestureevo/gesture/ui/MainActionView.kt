package io.github.lumiagg.gestureevo.gesture.ui

import io.github.lumiagg.gestureevo.databinding.GestureActionConfigPromptViewBinding
import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.ui.ActionTextMenuItem
import io.github.lumiagg.gestureevo.gesture.config.IconType
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_WHEEL_BACKGROUND_COLOR
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.postOnMain
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.max


class MainActionView : ActionView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    companion object {
        const val TAG = "MainActionView"
    }

    private enum class ConfigTarget {
        MAIN, SUB
    }

    private val configPromptView = ConfigPromptView()
    val subActionView = SubActionView(context)
    private val itemMenuOverlay = ActionItemMenuOverlay(this)
    private val selectedIconManager = SelectedIconManager()
    private val dragItemAction = DragItemAction()
    private val selectItemAction = SelectItemAction()
    private val moveItemAction = MoveItemAction()
    var statusCallback = object : StatusCallback {}
    var backgroundBlurCallback: (Boolean) -> Unit = {}
    var onHideRequestedCallback: () -> Unit = {}
    var onHiddenCallback: () -> Unit = {}
    var keepBackgroundBlurOnNextHide = false
    private var wheelBackgroundColor = DynamicColorUtils.surfaceContainerHighestColor
    private var wheelBackgroundFollowsTheme = true
    private var latestShowAnimEnabled = true
    private val dragTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val dragLongPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var configTarget = ConfigTarget.MAIN

    private val isConfigMode: Boolean
        get() = workMode.isConfig

    private val isSubConfigMode: Boolean
        get() = isConfigMode && configTarget == ConfigTarget.SUB

    override var workMode = WorkMode.ACTIVE
        set(value) {
            configTarget = if (value.targetsSubConfig) ConfigTarget.SUB else ConfigTarget.MAIN
            field = if (value.isActive) WorkMode.ACTIVE else WorkMode.CONFIG_MAIN
            resetModeDependentUi()
            refreshItemState()
        }

    init {
        visibility = GONE
        selectedIconManager.initSelectedHintIcon()
        initSubActionView()
    }

    private fun resetModeDependentUi() {
        itemMenuOverlay.hide()
        subActionView.hideItemMenu()
        syncSubActionWorkMode()
        updateConfigPromptVisibility()
    }

    private fun syncSubActionWorkMode(isSubVisible: Boolean = subActionView.isShowing) {
        subActionView.workMode = when {
            !isConfigMode -> WorkMode.ACTIVE
            isSubVisible -> WorkMode.CONFIG_SUB
            else -> WorkMode.CONFIG_MAIN
        }
    }

    private fun updateConfigPromptVisibility() {
        if (isConfigMode) {
            configPromptView.showMovePrompt()
        } else {
            configPromptView.hidePrompt()
        }
    }

    fun prepareSubActionViewForDisplay() {
        syncSubActionWorkMode(isSubVisible = true)
    }

    private fun buildItemView(): RoundImageTextView {
        val item = obtainView()
        val layoutParams = LayoutParams(
            config.itemDiameter.toInt(),
            config.itemDiameter.toInt()
        )
        item.elevation = 10F
        item.setCornerRadius(config.iconCornerRadius)
        item.setTransparentBackground(config.transparentIconBackground)
        item.setImageRoundPercent(config.iconImageRoundPercent)
        item.applyGlobalImageRoundPercent = false
        item.layoutParams = layoutParams
        item.setImagePadding(config.iconPadding.toInt())
        item.setText(context.getString(R.string.action_icon_placeholder))
        return item
    }

    fun setModeList(itemList: List<GestureActionItem>) {
        modelList = ArrayList<ModelItem>().apply {
            itemList.forEach {
                val view = buildItemView()
                val modelItem = ModelItem(
                    view = view,
                    model = it,
                    type = if (it.iconType == IconType.INVISIBLE) ItemType.INVISIBLE else ItemType.NORMAL
                )
                setIcon(modelItem)
                add(modelItem)
            }
            val item = buildItemView()
            item.setOnClickListener {
                statusCallback.onAddItem(ActionViewType.MAIN, state.itemsTag)
            }
            item.setImage(R.drawable.baseline_add_24)
            val modelItem = ModelItem(
                view = item,
                model = GestureActionItem(),
                type = ItemType.WHITEBOARD
            )
            add(modelItem)
        }
    }

    private fun initSubActionView() {
        subActionView.configPromptView = configPromptView
        subActionView.menuLayerCallback = object : SubActionView.MenuLayerCallback {
            override fun onMenuShown() {
                bringChildToFront(subActionView)
                subActionView.elevation = 1000f
                subActionView.translationZ = 1000f
            }

            override fun onMenuHidden() {
                subActionView.elevation = 30f
                subActionView.translationZ = 0f
                configPromptView.bringToFront()
            }
        }
        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        subActionView.elevation = 30F
        subActionView.visibility = GONE
        subActionView.statusCallback = object : SubActionView.StatusCallback {
            override fun onShow(attachView: View?) {
                itemMenuOverlay.hide()
                itemVisibility(false, attachView)
                if (isSubConfigMode) {
                    configPromptView.showMovePrompt()
                }
            }

            override fun onHide() {
                if (isSubConfigMode) {
                    configTarget = ConfigTarget.MAIN
                }
                syncSubActionWorkMode(isSubVisible = false)
                updateConfigPromptVisibility()
                itemVisibility(true)
            }

            override fun onDragCompleted(tag: String, itemList: ArrayList<ModelItem>) {
                statusCallback.onDragCompleted(tag, itemList)
            }

            override fun onRemovedItem(tag: String, itemList: ArrayList<ModelItem>) {
                statusCallback.onRemovedItem(tag, itemList)
            }

            override fun onAddItem(tag: String) {
                statusCallback.onAddItem(ActionViewType.SUB, tag)
            }

            override fun onEditItem(source: ActionViewType, tag: String, item: GestureActionItem) {
                statusCallback.onEditItem(ActionViewType.SUB, tag, item)
            }

            override fun executeAction(
                modelItem: ModelItem,
                isPress: Boolean,
                isUp: Boolean
            ): Boolean {
                val consumed = statusCallback.executeAction(modelItem, isPress, isUp)
                if (consumed) {
                    selectItemAction.actionExecuted = true
                    hide()
                }
                return consumed
            }

            override fun onSelectChanged(modelItem: ModelItem?) {
                selectedIconManager.updateSelectedIconView(modelItem)
            }
        }
        addView(subActionView, layoutParams)
    }

    private fun distributeItemsInRing(animate: Boolean = false) {
        if (config.rectangleLayoutEnabled) {
            ringsFlatten.clear()
            ringsFlatten.addAll(buildGridPositions(modelList.size))
            applyRingItemPosition(animate)
            return
        }
        val rings: ArrayList<Ring> = ArrayList()
        var ringRadius = config.initRadius
        var remainItem = modelList.size - 1
        var reverse = false
        for (i in 0 until 10) {
            if (remainItem <= 0) {
                break
            }
            val ring = Ring(
                state.startAngle,
                state.sweepAngle,
                radius = ringRadius,
                itemDiameter = config.itemDiameter,
                reverse = reverse,
                itemWarpOffset = config.itemWarpOffset
            )
            rings.add(ring)
            reverse = !reverse
            remainItem -= ring.items.size
            ringRadius += (config.itemDiameter * 1.3).toFloat()
        }

        ringsFlatten.clear()
        ringsFlatten.add(
            RingItem(
                state.centerX - config.initRadius / 2,
                state.centerY - config.initRadius / 2
            )
        )

        rings.forEach {
            if (state.mirror) {
                it.items.reverse()
            }
            it.items.forEach { item ->
                item.x += state.centerX - config.itemDiameter / 2
                item.y += state.centerY - config.itemDiameter / 2
                ringsFlatten.add(item)
            }
        }

        applyRingItemPosition(animate)
    }

    fun calculateMaxOuterRadius(): Pair<Float, Int> {
        if (config.rectangleLayoutEnabled) {
            val columns = config.rectangleColumns.coerceAtLeast(1)
            val rows = ((modelList.size + columns - 1) / columns).coerceAtLeast(1)
            val cellSize = config.itemDiameter + config.itemWarpOffset
            val gridWidth =
                if (columns == 1) config.itemDiameter else config.itemDiameter + (columns - 1) * cellSize
            val gridHeight =
                if (rows == 1) config.itemDiameter else config.itemDiameter + (rows - 1) * cellSize
            val maxDimension = max(gridWidth, gridHeight)
            return Pair(maxDimension, rows)
        }
        var ringRadius = config.initRadius
        var ringsCount = 0
        var remainItem = modelList.size - 1 // Subtract center item

        // Calculate max radius across all rings
        for (i in 0 until 10) { // Max 10 rings as per original code
            if (remainItem <= 0) break

            val ring = Ring(
                0F,
                90F,
                radius = ringRadius,
                itemDiameter = config.itemDiameter,
                reverse = (i % 2 != 0),
                itemWarpOffset = config.itemWarpOffset
            )

            remainItem -= ring.items.size
            ringsCount++
            ringRadius += (config.itemDiameter * 1.3).toFloat()
        }

        // Add half of item diameter to account for the outer edge items
        val maxRadius = ringRadius + (config.itemDiameter / 2)

        return Pair(maxRadius, ringsCount)
    }

    fun touchInMinRadius(touchX: Float, touchY: Float): Boolean {
        if (config.rectangleLayoutEnabled) return false
        val centerX = state.centerX.toFloat()
        val centerY = state.centerY.toFloat()
        val distance = twoPointDis(touchX, touchY, centerX, centerY)
        val innerRingRadius = config.initRadius
        return distance <= (innerRingRadius)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only DOWN event is dispatched to child views
        when {
            !isConfigMode -> {
                subActionView.onTouchEvent(event)
                selectItemAction.onTouchEvent(event)
            }

            isSubConfigMode -> {
                subActionView.onTouchEvent(event)
                selectItemAction.onTouchEvent(event)
                moveItemAction.onTouchEvent(event)
            }

            subActionView.isShowing -> {
                subActionView.onTouchEvent(event)
            }

            else -> {
                dragItemAction.onTouchEvent(event)
                moveItemAction.onTouchEvent(event)
            }
        }
        return false
    }

    private fun refreshItemState() {
        val hideCenterItem = !isConfigMode && !config.rectangleLayoutEnabled
        modelList.firstOrNull()?.view?.visibility =
            if (hideCenterItem) INVISIBLE else VISIBLE
        modelList.forEachIndexed { index, it ->
            if (index == 0) {
                val targetSize = if (config.rectangleLayoutEnabled) {
                    config.itemDiameter
                } else {
                    config.initRadius
                }
                dragItemAction.updateItemSize(it, targetSize, showAnim = false)
            }

            it.view.setIsSelected(false)

            if (it.type == ItemType.WHITEBOARD)
                it.view.visibility = if (isConfigMode) VISIBLE else GONE

            if (it.type == ItemType.INVISIBLE)
                it.view.visibility = if (isConfigMode) VISIBLE else GONE
        }
    }

    fun relayoutConfigView() {
        configPromptView.relayout()
        dragItemAction.updateRingItemsSize(false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun show(
        showAnim: Boolean = true,
        gestureType: GestureTypeEvent? = null,
        onLayoutComplete: (() -> Unit)? = null
    ) = withContext(Dispatchers.Main) {
        try {
            cancelPositionAnimation()
            itemMenuOverlay.hide()
            subActionView.hideItemMenu()
            latestShowAnimEnabled = showAnim
            alpha = 0F
            visibility = VISIBLE
            Log.d(TAG, "show: ")
            suspendCancellableCoroutine { continuation ->
                doOnNextLayout {
                    CoroutineScope(Dispatchers.Main).launch {
                        onLayoutComplete?.invoke()
                        distributeItemsInRing()
                        playShowAnim(showAnim)
                        applyWheelBackgroundColor(resolveWheelBackgroundColor())
                        gestureType?.let {
                            selectItemAction.checkInSelecting(it)
                        }
                        continuation.resume(Unit) { _, _, _ -> }
                    }
                }
            }
            refreshItemState()
            backgroundBlurCallback.invoke(true)
            clearViewBoundsCache()
        } catch (e: CancellationException) {
            // Avoid leaving a transparent, visible view if show() is cancelled.
            visibility = GONE
            alpha = 1F
            backgroundBlurCallback.invoke(false)
            clearViewBoundsCache()
            throw e
        }
    }

    fun hide(force: Boolean = false) {
        postOnMain {
            if (!force && isConfigMode) return@postOnMain
            onHideRequestedCallback.invoke()
            val keepBackgroundBlur = keepBackgroundBlurOnNextHide
            keepBackgroundBlurOnNextHide = false
            cancelPositionAnimation()
            itemMenuOverlay.hide()
            subActionView.hideItemMenu()
            subActionView.hideShrink()
            if (isVisible) {
                if (!keepBackgroundBlur) {
                    backgroundBlurCallback.invoke(false)
                }
                playHideAnim(useShrinkAnim = latestShowAnimEnabled)
            } else {
                onHiddenCallback.invoke()
            }
        }
    }

    fun release() {
        cancelPositionAnimation()
        animate().cancel()
        itemMenuOverlay.hide()
        subActionView.hideItemMenu()
        subActionView.hideShrink()
        keepBackgroundBlurOnNextHide = false
        visibility = GONE
        alpha = 1F
        clearViewBoundsCache()
    }

    override fun onHideAnimationCompleted() {
        super.onHideAnimationCompleted()
        onHiddenCallback.invoke()
    }

    private fun itemVisibility(isVisible: Boolean, skipView: View? = null) {
        Log.d(
            TAG,
            "itemVisibility: $isVisible mainMode ${workMode} subMode ${subActionView.workMode}"
        )
        val target = if (isVisible) 1F else 0.3F
        val src = if (isVisible) 0.3F else 1F
        ValueAnimator.ofFloat(src, target).apply {
            duration = 150
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val alpha = animator.animatedValue as Float
                modelList.forEach {
                    if (it.view != skipView) it.view.alpha = alpha
                }
            }
            start()
        }
    }

    override fun refreshTheme() {
        super.refreshTheme()
        itemMenuOverlay.refreshTheme()
        selectedIconManager.selectedIconView.refreshTheme()
        subActionView.refreshTheme()
    }

    fun applyWheelBackgroundColor(color: Int, followsTheme: Boolean = wheelBackgroundFollowsTheme) {
        wheelBackgroundFollowsTheme = followsTheme
        wheelBackgroundColor = color
        updateBackgroundGradient()
    }

    fun refreshWheelBackgroundForTheme() {
        if (!wheelBackgroundFollowsTheme) return
        applyWheelBackgroundColor(DynamicColorUtils.surfaceContainerHighestColor, followsTheme = true)
    }

    fun updateBackgroundGradient() {
        val gradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(
                wheelBackgroundColor,
                "#00000000".toColorInt()  // Fully transparent (edge)
            )
            gradientRadius = max(width, height).toFloat()
            setGradientCenter(
                this@MainActionView.state.centerX.toFloat() / width,
                this@MainActionView.state.centerY.toFloat() / height
            )
        }

        background = gradientDrawable
    }

    private suspend fun resolveWheelBackgroundColor(): Int = withContext(Dispatchers.IO) {
        val savedColor = context.settingsStore.readKey(KEY_WHEEL_BACKGROUND_COLOR, 0)
        wheelBackgroundFollowsTheme = savedColor == 0
        if (savedColor != 0) savedColor else DynamicColorUtils.surfaceContainerHighestColor
    }

    fun relayoutSelectedIcon(downY: Float = height.toFloat(), enable: Boolean = true) {
        selectedIconManager.relayoutSelectedIcon(downY, enable)
    }

    private fun showMainItemMenu(modelItem: ModelItem) {
        if (modelItem.type == ItemType.WHITEBOARD) return
        itemMenuOverlay.show(
            modelItem.view,
            listOf(
                ActionTextMenuItem(context.getString(R.string.action_view_item_menu_edit)) {
                    statusCallback.onEditItem(ActionViewType.MAIN, state.itemsTag, modelItem.model)
                },
                ActionTextMenuItem(
                    context.getString(R.string.action_view_item_menu_delete),
                    destructive = true
                ) {
                    removeMainItem(modelItem)
                },
                ActionTextMenuItem(context.getString(R.string.action_view_item_menu_submenu)) {
                    openSubMenuConfig(modelItem)
                }
            )
        )
    }

    private fun removeMainItem(modelItem: ModelItem) {
        removeView(modelItem.view)
        modelList.remove(modelItem)
        distributeItemsInRing(animate = true)
        refreshItemState()
        clearViewBoundsCache()
        statusCallback.onRemovedItem(state.itemsTag, modelList)
    }

    private fun openSubMenuConfig(modelItem: ModelItem) {
        if (!isConfigMode) return
        configTarget = ConfigTarget.SUB
        prepareSubActionViewForDisplay()
        statusCallback.onShowSubView(modelItem)
    }

    inner class DragItemAction {
        private var draggedModelItem: ModelItem? = null
        private var dragOuted = false
        private var draggedIndex = -1
        private var draggedCenterItemChangedSize = false
        private var longPressTriggered = false
        private var longPressRunnable: Runnable? = null
        private var lastX = 0f
        private var lastY = 0f
        private var firstX = 0f
        private var firstY = 0f
        private var downTime = 0L

        fun onTouchEvent(event: MotionEvent) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> handleActionDown(event)
                MotionEvent.ACTION_MOVE -> handleActionMove(event)
                MotionEvent.ACTION_UP -> handleActionUp(event)
                MotionEvent.ACTION_CANCEL -> handleActionCancel()
            }
        }

        private fun handleActionDown(event: MotionEvent) {
            if (itemMenuOverlay.dismissIfOutside(event.x, event.y)) {
                return
            }
            if (configPromptView.withinCenterView(event.x, event.y)) {
                return
            }

            draggedModelItem = findChildViewUnder(event.x, event.y)
            if (draggedModelItem != null) {
                draggedIndex = modelList.indexOf(draggedModelItem)
                lastX = event.x
                lastY = event.y
                firstX = lastX
                firstY = lastY
                downTime = System.currentTimeMillis()
                longPressTriggered = false
                scheduleLongPress()
            }
        }

        private fun handleActionMove(event: MotionEvent) {
            if (draggedModelItem?.type == ItemType.WHITEBOARD) return

            val deltaX = event.x - lastX
            val deltaY = event.y - lastY
            lastX = event.x
            lastY = event.y

            if (!dragOuted) {
                if (!longPressTriggered) {
                    return
                }
                startDragging()
            }

            draggedModelItem?.view?.let { it.translationX += deltaX }
            draggedModelItem?.view?.let { it.translationY += deltaY }
            clearViewBoundsCache()
            invalidate()
        }

        private fun handleActionUp(event: MotionEvent) {
            cancelLongPress()
            val shouldAnimate = dragOuted
            Log.d(TAG, "handleActionUp: draggedModelItem:${draggedModelItem?.type}")
            if (draggedModelItem?.type == ItemType.WHITEBOARD) {
                findChildViewUnder(event.x, event.y).let {
                    if (it?.type == ItemType.WHITEBOARD)
                        it.view.performClick()
                }
            } else if (draggedModelItem != null && draggedModelItem?.type != ItemType.WHITEBOARD) {
                if (!dragOuted && isTap(event)) {
                    draggedModelItem?.let { showMainItemMenu(it) }
                } else if (dragOuted) {
                    val targetIndex = findDragInsertionIndex(event.x, event.y)
                    dragInItem(draggedModelItem, if (targetIndex >= 0) targetIndex else draggedIndex)
                }
            }
            restoreDraggedItemIfMissing(draggedModelItem, dragOuted, draggedIndex)
            configPromptView.showMovePrompt()
            dragOuted = false
            draggedModelItem = null
            draggedCenterItemChangedSize = false
            draggedIndex = -1
            longPressTriggered = false

            updateRingItemsSize()
            distributeItemsInRing(animate = shouldAnimate)
            clearViewBoundsCache()

            statusCallback.onDragCompleted(state.itemsTag, modelList)
        }

        private fun handleActionCancel() {
            cancelLongPress()
            val shouldAnimate = dragOuted
            if (draggedModelItem != null && dragOuted) {
                dragInItem(draggedModelItem, draggedIndex)
            }
            restoreDraggedItemIfMissing(draggedModelItem, dragOuted, draggedIndex)
            dragOuted = false
            draggedModelItem = null
            draggedCenterItemChangedSize = false
            draggedIndex = -1
            longPressTriggered = false
            updateRingItemsSize()
            distributeItemsInRing(animate = shouldAnimate)
            clearViewBoundsCache()
        }

        private fun scheduleLongPress() {
            val modelItem = draggedModelItem ?: return
            if (modelItem.type == ItemType.WHITEBOARD) return
            cancelLongPress()
            longPressRunnable = Runnable {
                if (draggedModelItem == modelItem && !dragOuted) {
                    longPressTriggered = true
                    startDragging()
                }
            }.also {
                postDelayed(it, dragLongPressTimeout)
            }
        }

        private fun cancelLongPress() {
            longPressRunnable?.let { removeCallbacks(it) }
            longPressRunnable = null
        }

        private fun startDragging() {
            if (dragOuted || draggedModelItem == null) return
            cancelPositionAnimation()
            itemMenuOverlay.hide()
            dragOuted = true
            VibratorUtils.vibrator(context, false)
            configPromptView.showMovePrompt()
            if (!config.rectangleLayoutEnabled &&
                draggedIndex == 0 &&
                !draggedCenterItemChangedSize
            ) {
                draggedCenterItemChangedSize = true
                draggedModelItem?.view?.let { it.translationX += config.initRadius / 2 }
                draggedModelItem?.view?.let { it.translationY += config.initRadius / 2 }
            }
            dragOutItem(draggedModelItem)
            updateRingItemsSize()
            distributeItemsInRing(animate = true)
        }

        private fun findDragInsertionIndex(x: Float, y: Float): Int {
            findChildViewUnder(x, y)?.let { targetItem ->
                if (targetItem in modelList) {
                    return modelList.indexOf(targetItem)
                }
            }

            val whiteboardIndex = modelList.indexOfFirst { it.type == ItemType.WHITEBOARD }
            val maxInsertIndex = if (whiteboardIndex >= 0) whiteboardIndex else modelList.lastIndex
            return findNearestInsertionIndex(
                x = x,
                y = y,
                positions = ringsFlatten,
                maxInsertIndex = maxInsertIndex
            ) { index ->
                if (!config.rectangleLayoutEnabled && index == 0) config.initRadius else config.itemDiameter
            }
        }

        private fun isTap(event: MotionEvent): Boolean {
            val tapThreshold = max(dragTouchSlop * 2, config.itemDiameter * 0.2f)
            return twoPointDis(firstX, firstY, event.x, event.y) <= tapThreshold &&
                    System.currentTimeMillis() - downTime < dragLongPressTimeout
        }

        fun updateItemSize(modelItem: ModelItem, newSize: Float, showAnim: Boolean) {
            val oldSize = modelItem.view.width.toFloat()
            if (newSize != oldSize) {
                if (showAnim) {
                    ValueAnimator.ofFloat(oldSize, newSize).apply {
                        duration = 150
                        interpolator = LinearInterpolator()
                        addUpdateListener { animator ->
                            val size = (animator.animatedValue as Float).toInt()
                            modelItem.view.updateLayoutParams {
                                width = size
                                height = size
                            }
                        }
                        start()
                    }
                } else {
                    modelItem.view.updateLayoutParams {
                        width = newSize.toInt()
                        height = newSize.toInt()
                    }
                }
            }
        }

        fun updateRingItemsSize(showAnim: Boolean = true) {
            if (draggedModelItem != null) {
                updateItemSize(draggedModelItem!!, config.itemDiameter, showAnim)
            }
            for ((index, modelItem) in modelList.withIndex()) {
                val newSize = if (config.rectangleLayoutEnabled) {
                    config.itemDiameter
                } else if (index == 0) {
                    config.initRadius
                } else {
                    config.itemDiameter
                }
                updateItemSize(modelItem, newSize, showAnim)
            }
        }

    }

    /*
    // Note: SelectItemAction touch events usually come from trigger area; do not rely directly on event.x/y
     */
    inner class SelectItemAction {
        private var selectedModelItem: ModelItem? = null
        private var selectedTime = 0L
        private var isPress = false
        var actionExecuted = false
        private var selecting = false
        private var lastTouchEvent: MotionEvent? = null

        fun onTouchEvent(event: MotionEvent) {
            lastTouchEvent = event
            when (event.action) {
                MotionEvent.ACTION_DOWN -> handleActionDown(event)
                MotionEvent.ACTION_MOVE -> handleActionMove(event)
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> handleActionUp(event)
            }
        }

        fun startNewTouch() {
            selecting = true
            actionExecuted = false
            isPress = false
            selectedModelItem = null
            subActionView.startNewTouch()
        }

        private fun handleActionDown(event: MotionEvent) {
            if (itemMenuOverlay.dismissIfOutside(event.rawX, event.rawY)) {
                return
            }
            startNewTouch()
            handleActionMove(event)
        }

        private fun handleActionMove(event: MotionEvent) {
            if (!isShown || subActionView.isShown || actionExecuted) return

            val modelItem = findChildViewUnder(event.rawX, event.rawY)?.let {
                if (!isConfigMode && it.type == ItemType.INVISIBLE) null else it
            }
            if (modelItem != null && selectedModelItem == modelItem) return
            if (modelItem?.type == ItemType.WHITEBOARD) return

            selectedTime = System.currentTimeMillis()
            selectedModelItem = modelItem
            isPress = false

            selectedIconManager.updateSelectedIconView(selectedModelItem)

            if (!isConfigMode) {
                selectedModelItem?.let {
                    postDelayed({
                        if (!actionExecuted && isShown && selectedModelItem == modelItem && System.currentTimeMillis() - selectedTime > 450) {
                            isPress = true
                            if (!disableAllWheelVibrate) {
                                VibratorUtils.vibrator(context, true)
                            }
                            if (executeAction(isPress, isUp = false)) {
                                hideView()
                            } else {
                                selectedModelItem?.let {
                                    statusCallback.onShowSubView(it)
                                }
                            }
                        }
                    }, 500)
                }
            }

            selectedModelItem?.let {
                it.view.setIsSelected(true)
                if (!disableAllWheelVibrate &&
                    (config.rectangleLayoutEnabled || modelItem != modelList.first())
                ) {
                    VibratorUtils.vibrator(context, false)
                }
                if (executeAction(isPress, isUp = false)) {
                    hideView()
                }
            }

            for (item in modelList) {
                if (item.view.isSelected && selectedModelItem != item) {
                    item.view.setIsSelected(false)
                }
            }
        }

        private fun handleActionUp(event: MotionEvent) {
            selecting = false
            if (!isShown || actionExecuted) return
            if (isSubConfigMode && !subActionView.isShown) {
                selectedModelItem?.let {
                    showMainItemMenu(it)
                }
                clearSelection()
                return
            }
            selectedCompleted(event)
            executeAction(isPress, isUp = true)
            hideView()
        }

        private fun hideView() {
            clearSelection()
            selectedModelItem = null
            selectedTime = 0L
            hide()
        }

        private fun clearSelection() {
            modelList.forEach { it.view.setIsSelected(false) }
            selectedIconManager.updateSelectedIconView(null)
            selectedModelItem = null
            selectedTime = 0L
        }

        private fun executeAction(isPress: Boolean, isUp: Boolean): Boolean {
            if (!isConfigMode && !subActionView.hasSelectAction()) {
                selectedModelItem?.let {
                    return statusCallback.executeAction(it, isPress, isUp).also {
                        actionExecuted = it
                    }
                }
            }
            return false
        }

        fun selectedCompleted(event: MotionEvent) {
            statusCallback.onSelectedCompleted(selectedModelItem, event.rawX, event.rawY, isPress)
        }

        /*
        // In rare cases touch ends before view displays, preventing large icon trigger
         */
        fun checkInSelecting(gestureType: GestureTypeEvent): Boolean {
            if (!selecting &&
                gestureType.type != GestureType.SINGLE_CLICK &&
                gestureType.type != GestureType.DOUBLE_CLICK &&
                gestureType.phase == GesturePhase.TD
            ) {
                lastTouchEvent?.let {
                    selectedCompleted(it)
                }
                hideView()
            }
            return selecting
        }
    }

    inner class MoveItemAction {
        private var lastX = 0
        private var lastY = 0
        fun onTouchEvent(event: MotionEvent) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> handleActionDown(event)
                MotionEvent.ACTION_MOVE -> handleActionMove(event)
                MotionEvent.ACTION_UP -> handleActionUp(event)
            }
        }

        private fun handleActionDown(event: MotionEvent) {
            if (configPromptView.withinCenterView(event.x, event.y)) {
                lastX = event.x.toInt()
                lastY = event.y.toInt()
            } else {
                lastX = -1
                lastY = -1
            }
        }

        private fun handleActionMove(event: MotionEvent) {
            if (lastX == -1) return
            val nowX = event.x.toInt()
            val nowY = event.y.toInt()
            val movedX = nowX - lastX
            val movedY = nowY - lastY
            lastX = nowX
            lastY = nowY
            state.centerX += movedX
            state.centerY += movedY
            distributeItemsInRing()

            configPromptView.showMovePrompt()
        }

        private fun handleActionUp(event: MotionEvent) {
        }

    }

    inner class ConfigPromptView {
        private val configPrompt = GestureActionConfigPromptViewBinding.inflate(LayoutInflater.from(context)).root
        private val centerPrompt = configPrompt.findViewById<ImageFilterView>(R.id.centerPrompt)

        init {
            val layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )

            layoutParams.startToStart = LayoutParams.PARENT_ID
            layoutParams.endToEnd = LayoutParams.PARENT_ID

            configPrompt.findViewById<ImageFilterView>(R.id.checkPrompt).setOnClickListener {
                configPrompt.visibility = GONE
                this@MainActionView.hide(true)
                workMode = WorkMode.ACTIVE
            }

            configPrompt.visibility = GONE
            configPrompt.translationY = 100.dp2px
            addView(configPrompt, layoutParams)
        }

        fun showMovePrompt() {
            configPrompt.isEnabled = true
            centerPrompt.setBackgroundColor(ContextCompat.getColor(context, R.color.white))
            centerPrompt.setImageResource(R.drawable.baseline_unfold_more_24)
            configPrompt.elevation = 30F
            relayout()
            if (configPrompt.visibility != VISIBLE) {
                configPrompt.visibility = VISIBLE
            }
        }

        fun hidePrompt() {
            if (configPrompt.isVisible) {
                configPrompt.visibility = GONE
            }
        }

        fun bringToFront() {
            configPrompt.bringToFront()
            configPrompt.elevation = 100f
            configPrompt.translationZ = 100f
        }

        fun relayout() {
            var maxY = Float.MAX_VALUE
            ringsFlatten.forEach {
                if (it.y < maxY) maxY = it.y
            }

            if (isSubConfigMode && subActionView.isShowing) {
                subActionView.ringsFlatten.forEach {
                    if (it.y < maxY) maxY = it.y
                }
            }

            var targetY = maxY - 100.dp2px
            if (targetY < 0) {
                targetY = 0F
            } else if (targetY + configPrompt.height > this@MainActionView.height) {
                targetY = (this@MainActionView.height - configPrompt.height).toFloat()
            }
            configPrompt.translationY = targetY

        }

        fun withinCenterView(x: Float, y: Float): Boolean {
            val rect = Rect()
            val selectRect = Rect(x.toInt(), y.toInt(), x.toInt() + 1, y.toInt() + 1)
            rect.apply {
                left = (configPrompt.x + centerPrompt.x).toInt()
                top = (configPrompt.y + centerPrompt.y).toInt()
                right = left + centerPrompt.width
                bottom = top + centerPrompt.height
            }

            return rect.intersect(selectRect)
        }
    }

    inner class SelectedIconManager {
        val selectedIconView = RoundImageTextView(context)
        var selectedIconAnimator: ViewPropertyAnimator? = null
        var currentSelectedItem: ModelItem? = null
        var animRunning = false

        fun initSelectedHintIcon() {
            selectedIconView.apply {
                alpha = 0f
                layoutParams = LayoutParams(
                    100f.dp2px.toInt(), 100f.dp2px.toInt()
                ).apply {
                    startToStart = LayoutParams.PARENT_ID
                    endToEnd = LayoutParams.PARENT_ID
                }
                applyGlobalImageRoundPercent = false
                setCornerRadius(config.iconCornerRadius)
                setImageRoundPercent(config.iconImageRoundPercent)
                setTransparentBackground(true)
            }
            addView(selectedIconView)
        }

        fun relayoutSelectedIcon(downY: Float = height.toFloat(), enable: Boolean = true) {
            if (downY > height / 2) {
                selectedIconView.y = height / 3f
            } else {
                selectedIconView.y = 2 * height / 3f
            }
            selectedIconView.alpha = 0f
            selectedIconView.isEnabled = enable
        }

        fun updateSelectedIconView(modelItem: ModelItem?) {
            if (isConfigMode || !selectedIconView.isEnabled) {
                // If not in active mode or disabled, hide immediately and clear animations
                selectedIconAnimator?.cancel()
                selectedIconView.alpha = 0f
                currentSelectedItem = null
                return
            }

            // Check whether icon content needs updating
            val needUpdateIcon = modelItem != currentSelectedItem

            // Ignore update if animating and icon content is unchanged
            if (animRunning && !needUpdateIcon) {
                return
            }

            // Cancel current animation if icon content needs update
            if (needUpdateIcon) {
                selectedIconAnimator?.cancel()
                currentSelectedItem = modelItem

                // Update icon content
                if (modelItem != null) {
                    if (modelItem.model.iconType == IconType.TEXT) {
                        selectedIconView.setText(modelItem.view.textView.text)
                    } else {
                        selectedIconView.copyImagePresentationFrom(modelItem.view)
                    }
                }
            }

            val targetAlpha = if (modelItem == null) 0f else 1f
            val currentAlpha = selectedIconView.alpha

            // Skip animation if target alpha matches current alpha and icon is unchanged
            if (targetAlpha == currentAlpha && !needUpdateIcon) {
                return
            }

            // Create new animation
            selectedIconAnimator = selectedIconView.animate()
                .alpha(targetAlpha)
                .setDuration(150)
                .withStartAction {
                    animRunning = true
                }
                .withEndAction {
                    animRunning = false
                    selectedIconAnimator = null
                    // Clear current selection if target item is null on animation end
                    if (modelItem == null) {
                        currentSelectedItem = null
                    }
                }.apply { start() }
        }

    }

    interface StatusCallback {
        fun onRemovedItem(tag: String, itemList: ArrayList<ModelItem>) {
            println("onRemoveItem")
        }

        fun onSelectedCompleted(
            modelItem: ModelItem?,
            touchX: Float,
            touchY: Float,
            isPress: Boolean
        ) {
        }

        fun onDragCompleted(tag: String, itemList: ArrayList<ModelItem>) {}

        fun onAddItem(source: ActionViewType, tag: String) {}

        fun onEditItem(source: ActionViewType, tag: String, item: GestureActionItem) {}

        fun onShowSubView(modelItem: ModelItem) {}

        fun executeAction(modelItem: ModelItem, isPress: Boolean, isUp: Boolean): Boolean {
            return true
        }
    }
}
