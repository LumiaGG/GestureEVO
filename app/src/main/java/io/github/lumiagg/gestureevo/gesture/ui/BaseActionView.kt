package io.github.lumiagg.gestureevo.gesture.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnticipateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.ActionViewState
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.config.IconType
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class RingItem(var x: Float, var y: Float)
class Ring(
    val startAngle: Float,
    val sweepAngle: Float,
    val radius: Float,
    reverse: Boolean,
    itemDiameter: Float,
    itemWarpOffset: Float = 10.dp2px,
) {
    val items: ArrayList<RingItem> = ArrayList()

    init {
        calculateItemPosition(itemDiameter + itemWarpOffset)
        if (reverse) {
            items.reverse()
        }
    }

    private fun calculateItemPosition(itemDiameter: Float) {
        // Calculate radians
        val radians = 2 * asin(itemDiameter / (2 * radius))
        // Convert radians to degrees
        val itemAngle = Math.toDegrees(radians.toDouble())
        // Calculate maximum capacity of items
        val maxItemCount = (abs(sweepAngle) / itemAngle).toInt()
        // Recalculate item angle span for uniform distribution
        val radiansPerItem = abs(sweepAngle) / maxItemCount
        // Center item within each radiansPerItem angle
        val offsetRadiansPerItem = radiansPerItem / 2

        for (i in 0 until maxItemCount) {
            val currentAngle = if (sweepAngle > 0) {
                (startAngle + (offsetRadiansPerItem + i * radiansPerItem)).toDouble()
            } else {
                (startAngle - (offsetRadiansPerItem + i * radiansPerItem)).toDouble()
            }
            val x = radius * cos(Math.toRadians(currentAngle))
            val y = radius * sin(Math.toRadians(currentAngle))
            items.add(RingItem(x.toFloat(), -y.toFloat()))
        }
    }
}

open class ActionView : ConstraintLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    )

    var config = ActionConfig()
    var state = ActionViewState()
    var disableAllWheelVibrate = false

    var modelList: ArrayList<ModelItem> = ArrayList()
        set(value) {
            // Recycle current view back into pool
            for (item in field) {
                removeView(item.view)
            }

            // Update fields
            field = value
            clearViewBoundsCache()

            // Add new view
            for (item in field) {
                addView(item.view)
            }
        }

    var ringsFlatten: ArrayList<RingItem> = ArrayList()
    private var positionAnimator: AnimatorSet? = null

    open var workMode = WorkMode.ACTIVE

    enum class ItemType {
        NORMAL, WHITEBOARD, INVISIBLE
    }

    enum class WorkMode {
        CONFIG_MAIN, CONFIG_SUB, ACTIVE;

        val isActive: Boolean
            get() = this == ACTIVE

        val isConfig: Boolean
            get() = !isActive

        val targetsSubConfig: Boolean
            get() = this == CONFIG_SUB
    }

    enum class ActionViewType {
        MAIN, SUB,
    }

    data class ModelItem(
        val view: RoundImageTextView,
        val model: GestureActionItem,
        val type: ItemType = ItemType.NORMAL
    )

    protected fun obtainView(): RoundImageTextView = RoundImageTextView(context)

    open fun refreshTheme() {
        modelList.forEach { it.view.refreshTheme() }
    }

    fun updateRingItemPosition() {
        if (ringsFlatten.size < modelList.size) return
        modelList.forEachIndexed { index, modelItem ->
            val ringItem = ringsFlatten[index]
            modelItem.view.x = ringItem.x
            modelItem.view.y = ringItem.y
        }
    }

    protected fun applyRingItemPosition(animate: Boolean) {
        if (ringsFlatten.size < modelList.size) return
        cancelPositionAnimation()
        if (!animate) {
            updateRingItemPosition()
            return
        }

        val animators = ArrayList<Animator>()
        modelList.forEachIndexed { index, modelItem ->
            val ringItem = ringsFlatten[index]
            if (modelItem.view.x != ringItem.x) {
                animators.add(
                    ObjectAnimator.ofFloat(
                        modelItem.view,
                        X,
                        modelItem.view.x,
                        ringItem.x
                    )
                )
            }
            if (modelItem.view.y != ringItem.y) {
                animators.add(
                    ObjectAnimator.ofFloat(
                        modelItem.view,
                        Y,
                        modelItem.view.y,
                        ringItem.y
                    )
                )
            }
        }

        if (animators.isEmpty()) {
            updateRingItemPosition()
            return
        }

        positionAnimator = AnimatorSet().apply {
            playTogether(animators)
            duration = 150L
            start()
        }
    }

    protected fun cancelPositionAnimation() {
        positionAnimator?.cancel()
        positionAnimator = null
    }

    protected fun dragOutItem(draggedModelItem: ModelItem?) {
        if (draggedModelItem == null) return
        modelList.remove(draggedModelItem)
        clearViewBoundsCache()
    }

    protected fun dragInItem(draggedModelItem: ModelItem?, targetIndex: Int) {
        if (draggedModelItem == null) return
        if (targetIndex < 0) return

        modelList.remove(draggedModelItem)
        modelList.add(targetIndex.coerceIn(0, modelList.size), draggedModelItem)
        clearViewBoundsCache()
    }

    protected fun restoreDraggedItemIfMissing(
        draggedModelItem: ModelItem?,
        dragOuted: Boolean,
        draggedIndex: Int
    ) {
        if (!dragOuted || draggedModelItem == null || draggedModelItem in modelList) return
        val safeIndex = draggedIndex.coerceIn(0, modelList.size)
        modelList.add(safeIndex, draggedModelItem)
        clearViewBoundsCache()
    }

    fun twoPointDis(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val deltaX = x2 - x1
        val deltaY = y2 - y1
        return sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()
    }

    private data class ViewBoundsCache(
        val rect: Rect,
        val modelItem: ModelItem
    )

    private var lastHitCache: ViewBoundsCache? = null

    fun findChildViewUnder(x: Float, y: Float): ModelItem? {
        if (ringsFlatten.size < modelList.size) return null

        // Check if cache hits
        lastHitCache?.let { cache ->
            if (cache.modelItem in modelList) {
                if (cache.rect.contains(x.toInt(), y.toInt())) {
                    return cache.modelItem
                }
            }
            // Invalidate and clear cache on miss
            lastHitCache = null
        }
        val findChildViewUseRingItemPos = workMode == WorkMode.ACTIVE
        val rect = Rect()
        for ((i, item) in modelList.withIndex()) {
            val view = item.view
            val ringItem = ringsFlatten[i]
            val viewX = if (findChildViewUseRingItemPos) ringItem.x else view.x
            val viewY = if (findChildViewUseRingItemPos) ringItem.y else view.y
            rect.apply {
                left = viewX.toInt()
                top = viewY.toInt()
                right = left + view.width
                bottom = top + view.height
            }

            if (rect.contains(x.toInt(), y.toInt())) {
                // Cache hit result
                lastHitCache = ViewBoundsCache(Rect(rect), item)
                return item
            }
        }

        return null
    }

    fun clearViewBoundsCache() {
        lastHitCache = null
    }

    protected fun findNearestInsertionIndex(
        x: Float,
        y: Float,
        positions: List<RingItem>,
        maxInsertIndex: Int = positions.lastIndex,
        slotSizeAt: (Int) -> Float = { config.itemDiameter }
    ): Int {
        if (positions.isEmpty() || maxInsertIndex < 0) return -1

        val lastIndex = maxInsertIndex.coerceAtMost(positions.lastIndex)
        var nearestIndex = -1
        var nearestDistance = Float.MAX_VALUE
        var nearestThreshold = 0f

        for (index in 0..lastIndex) {
            val position = positions[index]
            val slotSize = slotSizeAt(index)
            val centerX = position.x + slotSize / 2
            val centerY = position.y + slotSize / 2
            val distance = twoPointDis(x, y, centerX, centerY)
            if (distance < nearestDistance) {
                nearestIndex = index
                nearestDistance = distance
                nearestThreshold = max(
                    max(config.itemDiameter + config.itemWarpOffset, config.itemDiameter * 1.35f),
                    slotSize
                )
            }
        }

        return if (nearestDistance <= nearestThreshold) nearestIndex else -1
    }

    protected fun buildGridPositions(itemCount: Int): ArrayList<RingItem> = wheelGridPositions(
        itemCount, config.rectangleColumns, config.itemDiameter, config.itemWarpOffset,
        state.centerX.toFloat(), state.centerY.toFloat(), width.toFloat(), height.toFloat(), state.mirror
    )
    internal fun playShowAnim(showSpreadAnim: Boolean) {
        val alphaAnimator = ObjectAnimator.ofFloat(this, ALPHA, alpha, 1f)
        alphaAnimator.duration = 250
        alphaAnimator.start()
        if (showSpreadAnim) {
            playSpreadAnim()
        }
    }

    internal fun playHideAnim(useShrinkAnim: Boolean = true) {
        if (useShrinkAnim) {
            playShrinkAnim()
        }
        val alphaAnimator = ObjectAnimator.ofFloat(this, ALPHA, alpha, 0.0f)
        alphaAnimator.duration = 250
        if (!useShrinkAnim) {
            alphaAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onHideAnimationCompleted()
                }
            })
        }
        alphaAnimator.start()
    }

    internal fun playSpreadAnim() {
        if (ringsFlatten.size < modelList.size) return
        visibility = INVISIBLE

        // Initialize all view positions to center point
        modelList.forEachIndexed { index, modelItem ->
            modelItem.view.translationX = state.centerX.toFloat() - config.itemDiameter / 2
            modelItem.view.translationY = state.centerY.toFloat() - config.itemDiameter / 2
        }
        visibility = VISIBLE

        // Create master AnimatorSet to coordinate animations
        val mainAnimatorSet = AnimatorSet()
        val allAnimators = ArrayList<Animator>()

        modelList.forEachIndexed { index, modelItem ->
            val ringItem = ringsFlatten[index]

            // Position animation
            val animatorX = ObjectAnimator.ofFloat(
                modelItem.view,
                "x",
                modelItem.view.x,
                ringItem.x
            )
            val animatorY = ObjectAnimator.ofFloat(
                modelItem.view,
                "y",
                modelItem.view.y,
                ringItem.y
            )

            // Create dedicated AnimatorSet for each item
            val itemAnimatorSet = AnimatorSet()
            itemAnimatorSet.playTogether(
                animatorX,
                animatorY,
            )

            // Add staggered delay for staggered animation effect
            itemAnimatorSet.startDelay = (index * 15).toLong().coerceAtMost(100)

            allAnimators.add(itemAnimatorSet)
        }

        mainAnimatorSet.playTogether(allAnimators)
        mainAnimatorSet.duration = 250
        mainAnimatorSet.interpolator = OvershootInterpolator(1.3f)
        mainAnimatorSet.start()
    }

    internal fun playShrinkAnim() {
        if (ringsFlatten.size < modelList.size) return
        // Create master AnimatorSet to coordinate animations
        val mainAnimatorSet = AnimatorSet()
        val allAnimators = ArrayList<Animator>()

        modelList.forEachIndexed { index, modelItem ->
            // Position animation
            val animatorX = ObjectAnimator.ofFloat(
                modelItem.view,
                "x",
                modelItem.view.x,
                state.centerX.toFloat() - config.itemDiameter / 2
            )
            val animatorY = ObjectAnimator.ofFloat(
                modelItem.view,
                "y",
                modelItem.view.y,
                state.centerY.toFloat() - config.itemDiameter / 2
            )

            // Create dedicated AnimatorSet for each item
            val itemAnimatorSet = AnimatorSet()
            itemAnimatorSet.playTogether(
                animatorX,
                animatorY,
            )

            // Staggered delay pattern for collapse - outside to inside
            itemAnimatorSet.startDelay =
                ((modelList.size - index) * 10).toLong().coerceAtMost(80)

            allAnimators.add(itemAnimatorSet)
        }

        mainAnimatorSet.playTogether(allAnimators)
        mainAnimatorSet.duration = 200
        mainAnimatorSet.interpolator = AnticipateInterpolator(1.3f)

        mainAnimatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onHideAnimationCompleted()
            }
        })

        mainAnimatorSet.start()
    }

    protected open fun onHideAnimationCompleted() {
        visibility = GONE
        modelList.forEach { modelItem ->
            modelItem.view.scaleY = 1f
            modelItem.view.scaleX = 1f
            modelItem.view.alpha = 1f
        }
    }

    fun setIcon(item: ModelItem) {
        gestureActionIcon(context, item.view, item.model)
    }
}

fun gestureActionIcon(context: Context, view: RoundImageTextView, item: GestureActionItem) {
    val action = item.firstStageAction
    when (item.iconType) {
        IconType.INVISIBLE -> {
            view.setText(context.getString(R.string.action_icon_invisible))
        }

        IconType.URI -> {
            val icon = item.icon?.let { IconHelper.getIconBitmap(it) }
            if (icon != null) {
                view.setImage(icon)
            } else {
                view.setText(item.name ?: "")
            }
        }

        IconType.TEXT -> view.setText(item.name ?: "")

        IconType.OTHER -> {
            when (action) {
                GestureActionType.OPEN_APP, GestureActionType.RECENT_APP, GestureActionType.OPEN_ACTIVITY, GestureActionType.FREEZE_APP -> view.setImage(
                    IconHelper.getIcon(
                        context, item.firstStagePkg ?: context.packageName
                    )
                )

                GestureActionType.ACC_ACTION -> {
                    val actionId = item.firstStageUri?.getQueryParameter("accAction")?.toIntOrNull()
                    val iconRes = actionId?.let { getAccActionIconRes(it) }
                    if (iconRes != null) {
                        view.setImageWithBackplate(iconRes)
                    }
                }

                GestureActionType.CLIPBOARD_ACTION -> {
                    val actionId = item.firstStageUri?.getQueryParameter("clipAction")
                    val iconRes = actionId?.let { getClipboardActionIconRes(it) }
                    if (iconRes != null) {
                        view.setImageWithBackplate(iconRes)
                    }
                }

                GestureActionType.SCREEN_CONTENT -> view.setImageWithBackplate(R.drawable.outline_center_focus_weak_24)
                GestureActionType.SCREEN_CONTENT_FULLSCREEN -> view.setImageWithBackplate(R.drawable.ic_screen_content_fullscreen_text_24)
                GestureActionType.SCREEN_SEARCH -> view.setImageWithBackplate(R.drawable.search_24px)
                GestureActionType.FREEFORM_FOREGROUND -> view.setImageWithBackplate(R.drawable.ic_little_window)
                GestureActionType.KEYBOARD -> view.setImageWithBackplate(R.drawable.baseline_keyboard_24)
                GestureActionType.SEARCH -> view.setImageWithBackplate(R.drawable.search_24px)
                GestureActionType.POINTER -> view.setImageWithBackplate(R.drawable.baseline_control_point_24)
                GestureActionType.PIN_POOL -> view.setImageWithBackplate(R.drawable.pinboard_24px)
                GestureActionType.TEMP_DND -> view.setImageWithBackplate(R.drawable.do_not_disturb_on_24px)
                GestureActionType.PAUSE_GESTURE -> view.setImageWithBackplate(R.drawable.baseline_gesture_24)
                else -> {}
            }
        }
    }
}

private fun getAccActionIconRes(actionId: Int): Int? {
    return when (actionId) {
        1 -> R.drawable.baseline_arrow_back_24
        2 -> R.drawable.baseline_home_24
        3 -> R.drawable.baseline_menu_24
        4 -> R.drawable.baseline_circle_notifications_24
        5 -> R.drawable.baseline_settings_24
        6 -> R.drawable.baseline_settings_24
        8 -> R.drawable.icon_lock
        9 -> R.drawable.baseline_screenshot_24
        else -> null
    }
}

private fun getClipboardActionIconRes(actionId: String): Int? {
    return when (actionId) {
        "pin" -> R.drawable.baseline_push_pin_24
        "search" -> R.drawable.search_24px
        "translate" -> R.drawable.ic_baseline_g_translate_24
        "inspire" -> R.drawable.outline_center_focus_weak_24
        "saveImage" -> R.drawable.baseline_save_24
        else -> null
    }
}
