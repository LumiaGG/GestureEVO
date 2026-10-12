package io.github.lumiagg.gestureevo.gesture.ui
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.ui.ActionTextMenuItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.IconType
import io.github.lumiagg.gestureevo.utils.Point
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.computeConvexHull
import io.github.lumiagg.gestureevo.utils.isPointInPolygon
import kotlin.math.max


class SubActionView : ActionView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    companion object {
        const val TAG = "SubActionView"
    }

    var configPromptView: MainActionView.ConfigPromptView? = null

    var menuLayerCallback = object : MenuLayerCallback {}
    private val itemMenuOverlay = ActionItemMenuOverlay(
        hostView = this,
        onMenuShown = { menuLayerCallback.onMenuShown() },
        onMenuHidden = { menuLayerCallback.onMenuHidden() }
    )
    private val dragItemAction = DragItemAction()
    private val selectItemAction = SelectItemAction()
    private var maxRingRadius: Float = 0F
    var statusCallback = object : StatusCallback {}
    var isShowing = false
        private set
    private val dragTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val dragLongPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var attachedView: View? = null

    override var workMode = WorkMode.ACTIVE
        set(value) {
            field = value
            refreshItemState()
        }

    suspend fun resetConfig(context: Context, mainActionConfig: ActionConfig) {
        config = GestureActionDataManager.readActionConfig(context, mainActionConfig.tag, true)
        if (config.subActionFollowConfig) {
            config.itemDiameter = mainActionConfig.itemDiameter * 0.8f
            config.itemWarpOffset = mainActionConfig.itemWarpOffset / 2
            config.initRadius = mainActionConfig.itemDiameter
            config.iconPadding = mainActionConfig.iconPadding * 0.8f
            config.iconCornerRadius = mainActionConfig.iconCornerRadius * 0.8f
        }
    }

    fun buildItemView(): RoundImageTextView {
        val item = obtainView()
        val layoutParams = LayoutParams(
            config.itemDiameter.toInt(),
            config.itemDiameter.toInt()
        )
        item.elevation = 20F
        item.setCornerRadius(config.iconCornerRadius)
        item.setTransparentBackground(config.transparentIconBackground)
        item.setImageRoundPercent(config.iconImageRoundPercent)
        item.applyGlobalImageRoundPercent = false
        item.layoutParams = layoutParams
        item.setImagePadding(config.iconPadding.toInt())
        item.setText(context.getString(R.string.action_icon_placeholder))
        return item
    }

    fun setModeList(tag: String, itemList: List<GestureActionItem>) {
        state.itemsTag = tag
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
            item.setImage(R.drawable.baseline_add_24)
            item.setOnClickListener {
                statusCallback.onAddItem(tag)
            }
            val modelItem = ModelItem(
                view = item,
                model = GestureActionItem(),
                type = ItemType.WHITEBOARD
            )
            add(modelItem)
        }
    }

    private fun distributeItemsInRing(animate: Boolean = false) {
        if (config.rectangleLayoutEnabled) {
            ringsFlatten.clear()
            ringsFlatten.addAll(buildGridPositionsCenteredAtAnchor(modelList.size))
            applyRingItemPosition(animate)
            return
        }
        val rings: ArrayList<Ring> = ArrayList()
        var ringRadius = config.initRadius
        var remainItem = modelList.size
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
        maxRingRadius = if (rings.isNotEmpty()) {
            rings.last().radius
        } else {
            config.initRadius
        }

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

    private fun buildGridPositionsCenteredAtAnchor(itemCount: Int): ArrayList<RingItem> {
        val columns = config.rectangleColumns.coerceAtLeast(1)
        val rows = ((itemCount + columns - 1) / columns).coerceAtLeast(1)
        val cellSize = config.itemDiameter + config.itemWarpOffset
        val gridWidth =
            if (columns == 1) config.itemDiameter else config.itemDiameter + (columns - 1) * cellSize
        val gridHeight =
            if (rows == 1) config.itemDiameter else config.itemDiameter + (rows - 1) * cellSize
        var startX = state.centerX - gridWidth / 2
        var startY = state.centerY - gridHeight / 2
        var anchorBottom = false
        if (width > 0 && height > 0) {
            anchorBottom = state.centerY > height / 2
            startY = if (anchorBottom) state.centerY - gridHeight else state.centerY.toFloat()
        }
        if (width > 0) {
            val maxStartX = (width - gridWidth).coerceAtLeast(0f)
            startX = startX.coerceIn(0f, maxStartX)
        }
        if (height > 0) {
            val maxStartY = (height - gridHeight).coerceAtLeast(0f)
            startY = startY.coerceIn(0f, maxStartY)
        }
        val positions = ArrayList<RingItem>(itemCount)
        for (i in 0 until itemCount) {
            val row = i / columns
            val col = i % columns
            val colIndex = if (state.mirror) col else columns - 1 - col
            val rowIndex = if (anchorBottom) rows - 1 - row else row
            positions.add(
                RingItem(
                    startX + colIndex * cellSize,
                    startY + rowIndex * cellSize
                )
            )
        }
        return positions
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isShowing) {
            return false
        }
        when (workMode) {
            WorkMode.CONFIG_SUB -> dragItemAction.onTouchEvent(event)
            WorkMode.ACTIVE -> selectItemAction.onTouchEvent(event)
            WorkMode.CONFIG_MAIN -> {}
        }
        return false
    }

    private fun refreshItemState() {
        modelList.forEach {
            it.view.setIsSelected(false)
            if (it.type == ItemType.WHITEBOARD)
                it.view.visibility = if (workMode == WorkMode.ACTIVE) GONE else VISIBLE

            if (it.type == ItemType.INVISIBLE)
                it.view.visibility = if (workMode == WorkMode.ACTIVE) GONE else VISIBLE
        }
    }

    override fun refreshTheme() {
        super.refreshTheme()
        itemMenuOverlay.refreshTheme()
    }

    fun hideItemMenu() {
        itemMenuOverlay.hide()
    }

    private fun showItemMenu(modelItem: ModelItem) {
        if (modelItem.type == ItemType.WHITEBOARD) return
        itemMenuOverlay.show(
            modelItem.view,
            listOf(
                ActionTextMenuItem(context.getString(R.string.action_view_item_menu_edit)) {
                    statusCallback.onEditItem(ActionViewType.SUB, state.itemsTag, modelItem.model)
                },
                ActionTextMenuItem(
                    context.getString(R.string.action_view_item_menu_delete),
                    destructive = true
                ) {
                    removeItem(modelItem)
                }
            )
        )
    }

    private fun removeItem(modelItem: ModelItem) {
        removeView(modelItem.view)
        modelList.remove(modelItem)
        distributeItemsInRing(animate = true)
        refreshItemState()
        clearViewBoundsCache()
        statusCallback.onRemovedItem(state.itemsTag, modelList)
    }

    fun showSpread(attachView: View?, mirrorItem: Boolean? = null) {
        if (isShowing) return
        cancelPositionAnimation()
        itemMenuOverlay.hide()
        isShowing = true
        if (attachView != null) {
            attachedView = attachView
            state.centerX = attachView.x.toInt() + attachView.width / 2
            state.centerY = attachView.y.toInt() + attachView.height / 2
        }
        mirrorItem?.let {
            state.mirror = mirrorItem
            if (mirrorItem) {
                // Must differ by 180 degrees
                state.startAngle = 180F
            } else {
                state.startAngle = 0F
            }
            state.sweepAngle = 360F
        }
        distributeItemsInRing()
        refreshItemState()
        statusCallback.onShow(attachView)
        playShowAnim(true)
        clearViewBoundsCache()
    }

    fun hideShrink() {
        if (isShowing) {
            cancelPositionAnimation()
            itemMenuOverlay.hide()
            isShowing = false
            statusCallback.onHide()
            playHideAnim()
        }
    }

    fun hasSelectAction(): Boolean {
        if (!isShowing) return false
        return selectItemAction.hasSelectedItem
    }

    fun startNewTouch() {
        selectItemAction.actionExecuted = false
        selectItemAction.hasSelectedItem = false
    }

    inner class DragItemAction {
        private var draggedModelItem: ModelItem? = null
        private var dragOuted = false
        private var draggedIndex = -1
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
            } else {
                hideShrink()
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

            draggedModelItem?.view?.let {
                it.x += deltaX
                it.y += deltaY
            }
            clearViewBoundsCache()
            invalidate()
        }

        private fun handleActionUp(event: MotionEvent) {
            cancelLongPress()
            val shouldAnimate = dragOuted
            if (draggedModelItem?.type == ItemType.WHITEBOARD) {
                findChildViewUnder(event.x, event.y).let {
                    if (it?.type == ItemType.WHITEBOARD)
                        it.view.performClick()
                }
            } else if (draggedModelItem != null && draggedModelItem?.type != ItemType.WHITEBOARD) {
                if (!dragOuted && isTap(event)) {
                    draggedModelItem?.let { showItemMenu(it) }
                } else if (dragOuted) {
                    val targetIndex = findDragInsertionIndex(event.x, event.y)
                    dragInItem(draggedModelItem, if (targetIndex >= 0) targetIndex else draggedIndex)
                }
            }
            restoreDraggedItemIfMissing(draggedModelItem, dragOuted, draggedIndex)
            dragOuted = false
            draggedModelItem = null
            draggedIndex = -1
            longPressTriggered = false
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
            draggedIndex = -1
            longPressTriggered = false
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
            configPromptView?.showMovePrompt()
            draggedModelItem?.view?.let {
                it.bringToFront()
            }
            dragOutItem(draggedModelItem)
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
            )
        }

        private fun isTap(event: MotionEvent): Boolean {
            val tapThreshold = max(dragTouchSlop * 2, config.itemDiameter * 0.2f)
            return twoPointDis(firstX, firstY, event.x, event.y) <= tapThreshold &&
                    System.currentTimeMillis() - downTime < dragLongPressTimeout
        }

    }

    inner class SelectItemAction {
        private var selectedModelItem: ModelItem? = null
        private var selectedTime = 0L
        private var isPress = false
        var hasSelectedItem = false
        var actionExecuted = false

        fun onTouchEvent(event: MotionEvent) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> handleActionDown(event)
                MotionEvent.ACTION_MOVE -> handleActionMove(event)
                MotionEvent.ACTION_UP -> handleActionUp(event)
            }
        }

        private fun handleActionDown(event: MotionEvent) {
            hasSelectedItem = false
            val modelItem = findChildViewUnder(event.rawX, event.rawY)
            if (modelItem == null) {
                hideShrink()
            } else {
                handleActionMove(event)
            }
        }

        private fun handleActionMove(event: MotionEvent) {
            if (!isShowing || actionExecuted) return

            if (!withinRing(event)) {
                hideShrink()
            }

            val modelItem = findChildViewUnder(event.rawX, event.rawY)?.let {
                if (it.type == ItemType.INVISIBLE) null else it
            }
            if (modelItem != null && selectedModelItem == modelItem) return
            if (modelItem?.type == ItemType.WHITEBOARD && workMode == WorkMode.ACTIVE) return


            selectedTime = System.currentTimeMillis()
            selectedModelItem = modelItem
            isPress = false

            statusCallback.onSelectChanged(selectedModelItem)

            selectedModelItem?.let {
                postDelayed({
                    if (!actionExecuted && isShowing && selectedModelItem == modelItem && System.currentTimeMillis() - selectedTime > 450) {
                        isPress = true
                        if (!disableAllWheelVibrate) {
                            VibratorUtils.vibrator(context, true)
                        }
                        executeAction(isPress, isUp = false)
                    }
                }, 500)
            }

            selectedModelItem?.let {
                if (!it.view.isSelected) {
                    it.view.setIsSelected(true)
                    if (!disableAllWheelVibrate) {
                        VibratorUtils.vibrator(context, false)
                    }
                    if (executeAction(isPress, isUp = false)) {
                        hideShrink()
                    }
                }
            }

            for (item in modelList) {
                if (item.view.isSelected && selectedModelItem != item) {
                    item.view.setIsSelected(false)
                }
            }
        }

        private fun handleActionUp(event: MotionEvent) {
            if (!isShown || actionExecuted) return
            executeAction(isPress, isUp = true)
            hideShrink()
        }

        private fun withinRing(event: MotionEvent): Boolean {
            val points = ArrayList<Point>()
            val location = IntArray(2)
            modelList.forEach {
                if (it.type == ItemType.NORMAL) {
                    val view = it.view
                    val left = view.x - config.itemDiameter / 2
                    val top = view.y - config.itemDiameter / 2
                    val right = left + view.layoutParams.width + config.itemDiameter
                    val bottom = top + view.layoutParams.height + config.itemDiameter
                    points.add(Point(left, top))
                    points.add(Point(left, bottom))
                    points.add(Point(right, top))
                    points.add(Point(right, bottom))
                }
            }
            attachedView?.let {
                it.getLocationOnScreen(location)
                val left = it.x - config.initRadius / 2
                val top = it.y - config.initRadius / 2
                val right = left + it.layoutParams.width + config.initRadius
                val bottom = top + it.layoutParams.height + config.initRadius
                points.add(Point(left, top))
                points.add(Point(left, bottom))
                points.add(Point(right, top))
                points.add(Point(right, bottom))
            }
            val convexHull = computeConvexHull(points)
            val isInside =
                isPointInPolygon(
                    Point(
                        event.rawX,
                        event.rawY
                    ), convexHull
                )
            return isInside
        }

        private fun executeAction(isPress: Boolean, isUp: Boolean): Boolean {
            hasSelectedItem = selectedModelItem != null
            if (workMode == WorkMode.ACTIVE) {
                Log.d(
                    TAG,
                    "handleActionUp: executeAction:${selectedModelItem?.model?.firstStageLink}"
                )
                selectedModelItem?.let {
                    return statusCallback.executeAction(it, isPress, isUp).also {
                        actionExecuted = it
                    }
                }
            }

            selectedModelItem = null
            return false
        }
    }

    interface StatusCallback {
        fun onRemovedItem(tag: String, itemList: ArrayList<ModelItem>) {}

        fun onDragCompleted(tag: String, itemList: ArrayList<ModelItem>) {}

        fun onSelectChanged(modelItem: ModelItem?) {}

        fun onHide() {}

        fun onShow(attachView: View?) {}

        fun onAddItem(tag: String) {}

        fun onEditItem(source: ActionViewType, tag: String, item: GestureActionItem) {}

        fun executeAction(modelItem: ModelItem, isPress: Boolean, isUp: Boolean): Boolean {
            return false
        }
    }

    interface MenuLayerCallback {
        fun onMenuShown() {}
        fun onMenuHidden() {}
    }
}
