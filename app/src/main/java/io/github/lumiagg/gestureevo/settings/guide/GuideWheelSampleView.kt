package io.github.lumiagg.gestureevo.settings.guide

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.ui.FittedWheelLayout
import io.github.lumiagg.gestureevo.gesture.ui.calculateFittedWheelLayout
import io.github.lumiagg.gestureevo.ui.ActionTextMenuItem
import io.github.lumiagg.gestureevo.ui.ActionTextMenuPopup
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px

/**
 * Built-in interactive wheel sample view that matches the real wheel's internal layout:
 * - Center item (slot 0) positioned at the quadrant origin
 * - Concentric radial rings for subsequent items (Ring 1, Ring 2, etc.) matching MainActionView
 * - Automatic scale-to-fit within the section card
 * - Drag-to-reorder, tap to edit/delete, and add action icon at the end
 */
class GuideWheelSampleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var config: QuickSetupConfig = QuickSetupConfig.default()
    private var actionConfig: ActionConfig = ActionConfig()
    private var onEditItem: ((Int) -> Unit)? = null
    private var onRemoveItem: ((Int) -> Unit)? = null
    private var onAddItem: (() -> Unit)? = null
    private var onReorderItems: ((List<QuickAction>) -> Unit)? = null

    // For BOTH sides, allows toggling preview between left and right
    private var previewSideOverride: Boolean? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp2px
    }

    private val triggerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val cardRect = RectF()

    private val sideToggleChip: TextView
    private val promptView: TextView

    // Action item views + optional Add button view at the end
    private val actionViews = mutableListOf<View>()
    private var addView: View? = null

    // Cached layout geometry
    private var currentLayout: FittedWheelLayout? = null

    // Drag-to-reorder state
    private var activeTouchIndex = -1
    private var activeTouchView: View? = null
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeout = 260L
    private var longPressRunnable: Runnable? = null

    init {
        setWillNotDraw(false)
        clipChildren = false

        // Prompt / tooltip at the corner
        promptView = TextView(context).apply {
            text = context.getString(R.string.quick_setup_wheel_sample_hint)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setTextColor(DynamicColorUtils.onSurfaceVariantColor)
            alpha = 0.9f
            val padH = 14f.dp2px.toInt()
            val padV = 10f.dp2px.toInt()
            setPadding(padH, padV, padH, padV)
        }
        addView(promptView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // Side toggle chip for BOTH mode
        sideToggleChip = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
            setTextColor(DynamicColorUtils.primaryColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f.dp2px
                setColor(DynamicColorUtils.secondaryContainerColor)
            }
            val padH = 10f.dp2px.toInt()
            val padV = 5f.dp2px.toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
            visibility = GONE
            setOnClickListener {
                previewSideOverride = !(previewSideOverride ?: false)
                updateToggleText()
                rebuildViews(animate = true)
                invalidate()
            }
        }
        addView(sideToggleChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 10f.dp2px.toInt()
        })
    }

    fun setActionConfig(actionConfig: ActionConfig) {
        this.actionConfig = actionConfig
        rebuildViews(animate = false)
        invalidate()
    }

    fun updateTriggerConfig(newConfig: QuickSetupConfig) {
        val oldConfig = this.config
        this.config = newConfig
        if (config.side != QuickSetupSide.BOTH) {
            previewSideOverride = null
            sideToggleChip.visibility = GONE
        } else {
            sideToggleChip.visibility = VISIBLE
            updateToggleText()
        }

        val w = width.toFloat().takeIf { it > 0 } ?: return
        val h = height.toFloat().takeIf { it > 0 } ?: return
        val views = allDisplayViews()
        val oldLayout = currentLayout
        val newLayout = calculateWheelLayout(w, h, views.size)
        currentLayout = newLayout

        val shapeChanged = oldLayout == null ||
            oldLayout.startAngle != newLayout.startAngle ||
            oldLayout.sweepAngle != newLayout.sweepAngle ||
            oldLayout.isLeft != newLayout.isLeft

        positionItemViews(animate = shapeChanged)
        updatePromptPosition()
        invalidate()
    }

    fun setConfig(
        config: QuickSetupConfig,
        onEditItem: (Int) -> Unit,
        onRemoveItem: (Int) -> Unit,
        onAddItem: () -> Unit,
        onReorderItems: (List<QuickAction>) -> Unit
    ) {
        val oldConfig = this.config
        this.config = config
        this.onEditItem = onEditItem
        this.onRemoveItem = onRemoveItem
        this.onAddItem = onAddItem
        this.onReorderItems = onReorderItems

        // Reset override if side changed from BOTH
        if (config.side != QuickSetupSide.BOTH) {
            previewSideOverride = null
            sideToggleChip.visibility = GONE
        } else {
            sideToggleChip.visibility = VISIBLE
            updateToggleText()
        }

        val animate = oldConfig.side != config.side ||
            (oldConfig.verticalPercent < 50) != (config.verticalPercent < 50) ||
            oldConfig.wheelItems.size != config.wheelItems.size

        rebuildViews(animate = animate)
        invalidate()
    }

    private fun updateToggleText() {
        val isLeft = previewSideOverride ?: false
        sideToggleChip.text = if (isLeft) {
            context.getString(R.string.quick_setup_wheel_sample_side_left)
        } else {
            context.getString(R.string.quick_setup_wheel_sample_side_right)
        }
    }

    private fun isEffectiveLeft(): Boolean = when (config.side) {
        QuickSetupSide.LEFT -> true
        QuickSetupSide.RIGHT -> false
        QuickSetupSide.BOTH -> previewSideOverride ?: false
    }

    private fun allDisplayViews(): List<View> {
        val list = mutableListOf<View>()
        list.addAll(actionViews)
        addView?.let { list.add(it) }
        return list
    }

    /**
     * Calculates the wheel layout matching MainActionView:
     * - Slot 0: Center item at quadrant origin
     * - Slots 1..N: Concentric rings (Ring 1, Ring 2, etc.) via GuideWheelGeometry
     * - Auto-scales to fit comfortably inside view bounds
     */
    private fun calculateWheelLayout(w: Float, h: Float, totalItems: Int): FittedWheelLayout {
        return calculateFittedWheelLayout(
            width = w,
            height = h,
            itemCount = totalItems,
            isLeft = isEffectiveLeft(),
            verticalPercent = config.verticalPercent,
            baseRadius = actionConfig.initRadius,
            baseItemDiameter = actionConfig.itemDiameter,
            baseItemWarpOffset = actionConfig.itemWarpOffset,
            margin = 16f.dp2px
        )
    }

    private fun rebuildViews(animate: Boolean) {
        actionViews.forEach { removeView(it) }
        actionViews.clear()
        addView?.let { removeView(it) }
        addView = null

        val totalCount = config.wheelItems.size + 1
        val w = width.toFloat().takeIf { it > 0 } ?: 360f.dp2px
        val h = height.toFloat().takeIf { it > 0 } ?: 280f.dp2px
        val layout = calculateWheelLayout(w, h, totalCount)
        currentLayout = layout

        val baseSize = actionConfig.itemDiameter * layout.scale
        val centerSize = actionConfig.initRadius * layout.scale

        // 1. Build action item views (Center item + Ring items)
        config.wheelItems.forEachIndexed { index, action ->
            val isCenter = (index == 0)
            val defaultSize = if (isCenter) centerSize else baseSize
            val size = if (index < layout.slots.size) layout.slots[index].size else defaultSize
            val itemView = createActionItemView(index, action, isCenter, size)
            actionViews.add(itemView)
            addView(itemView)
        }

        // 2. Build Add item ("+") at the end of the outer ring
        val addSize = layout.slots.lastOrNull()?.size ?: baseSize
        val addBtn = createAddButtonView(addSize)
        addView = addBtn
        addView(addBtn)

        positionItemViews(animate)
        updatePromptPosition()
    }

    private fun createActionItemView(index: Int, action: QuickAction, isCenterItem: Boolean, size: Float): View {
        val sizeInt = size.toInt().coerceAtLeast(1)
        val corner = (actionConfig.iconCornerRadius * (size / actionConfig.itemDiameter.coerceAtLeast(1f)))
            .coerceAtMost(size / 2f)
        val pad = (actionConfig.iconPadding * (size / actionConfig.itemDiameter.coerceAtLeast(1f)))
            .toInt().coerceAtLeast(4f.dp2px.toInt())
        return ImageView(context).apply {
            layoutParams = LayoutParams(sizeInt, sizeInt)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = corner
                setColor(
                    if (isCenterItem) DynamicColorUtils.primaryFixedColor
                    else DynamicColorUtils.secondaryContainerColor
                )
            }
            elevation = if (isCenterItem) 4f.dp2px else 3f.dp2px
            setImageDrawable(action.icon(context))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (action.type != QuickActionType.OPEN_APP || action.packageName == null) {
                imageTintList = ColorStateList.valueOf(
                    if (isCenterItem) DynamicColorUtils.onPrimaryContainerColor
                    else DynamicColorUtils.primaryColor
                )
            }
        }
    }

    private fun createAddButtonView(size: Float): View {
        val sizeInt = size.toInt().coerceAtLeast(1)
        val corner = (actionConfig.iconCornerRadius * (size / actionConfig.itemDiameter.coerceAtLeast(1f)))
            .coerceAtMost(size / 2f)
        val pad = (actionConfig.iconPadding * (size / actionConfig.itemDiameter.coerceAtLeast(1f)))
            .toInt().coerceAtLeast(4f.dp2px.toInt())
        return ImageView(context).apply {
            layoutParams = LayoutParams(sizeInt, sizeInt)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = corner
                setColor(DynamicColorUtils.surfaceContainerHighColor)
                setStroke(1f.dp2px.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(DynamicColorUtils.primaryColor, 120))
            }
            elevation = 2f.dp2px
            setImageResource(R.drawable.baseline_add_24)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
        }
    }

    private fun positionItemViews(animate: Boolean) {
        val w = width.toFloat().takeIf { it > 0 } ?: return
        val h = height.toFloat().takeIf { it > 0 } ?: return
        val views = allDisplayViews()
        val layout = calculateWheelLayout(w, h, views.size)
        currentLayout = layout

        val animators = mutableListOf<ObjectAnimator>()

        views.forEachIndexed { i, view ->
            if (i >= layout.slots.size) return@forEachIndexed
            if (isDragging && view == activeTouchView) return@forEachIndexed

            val slot = layout.slots[i]
            val slotSizeInt = slot.size.toInt().coerceAtLeast(1)
            val corner = (actionConfig.iconCornerRadius * (slot.size / actionConfig.itemDiameter.coerceAtLeast(1f)))
                .coerceAtMost(slot.size / 2f)
            val pad = (actionConfig.iconPadding * (slot.size / actionConfig.itemDiameter.coerceAtLeast(1f)))
                .toInt().coerceAtLeast(4f.dp2px.toInt())

            (view.background as? GradientDrawable)?.cornerRadius = corner
            view.setPadding(pad, pad, pad, pad)

            if (view.layoutParams.width != slotSizeInt || view.layoutParams.height != slotSizeInt) {
                view.layoutParams.width = slotSizeInt
                view.layoutParams.height = slotSizeInt
                view.requestLayout()
            }

            val targetX = slot.x
            val targetY = slot.y

            if (animate && view.x != 0f && view.y != 0f) {
                animators.add(ObjectAnimator.ofFloat(view, "x", view.x, targetX))
                animators.add(ObjectAnimator.ofFloat(view, "y", view.y, targetY))
            } else {
                view.x = targetX
                view.y = targetY
            }
        }

        if (animators.isNotEmpty()) {
            AnimatorSet().apply {
                playTogether(animators as Collection<android.animation.Animator>)
                duration = 200
                interpolator = OvershootInterpolator(1.1f)
                start()
            }
        }
    }

    private fun updatePromptPosition() {
        val isLeft = isEffectiveLeft()
        val isUpper = config.verticalPercent < 50
        val lp = promptView.layoutParams as LayoutParams

        // Position prompt in the corner opposite to the origin
        val gravityH = if (isLeft) Gravity.END else Gravity.START
        val gravityV = if (isUpper) Gravity.BOTTOM else Gravity.TOP
        lp.gravity = gravityH or gravityV
        promptView.layoutParams = lp

        // Side toggle chip positioned away from the active quadrant corner
        val chipLp = sideToggleChip.layoutParams as LayoutParams
        chipLp.gravity = (if (isUpper) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
        chipLp.bottomMargin = if (isUpper) 10f.dp2px.toInt() else 0
        chipLp.topMargin = if (!isUpper) 10f.dp2px.toInt() else 0
        sideToggleChip.layoutParams = chipLp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        positionItemViews(animate = false)
        updatePromptPosition()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Intercept touch when dragging so parent ScrollView doesn't steal touches
        return isDragging || super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(event)
            MotionEvent.ACTION_MOVE -> handleActionMove(event)
            MotionEvent.ACTION_UP -> handleActionUp(event)
            MotionEvent.ACTION_CANCEL -> handleActionCancel()
        }
        return true
    }

    private fun handleActionDown(event: MotionEvent) {
        cancelLongPress()
        downX = event.x
        downY = event.y

        val views = allDisplayViews()
        val layout = currentLayout ?: calculateWheelLayout(width.toFloat(), height.toFloat(), views.size)
        activeTouchIndex = -1
        activeTouchView = null

        views.forEachIndexed { i, view ->
            if (i >= layout.slots.size) return@forEachIndexed
            val slot = layout.slots[i]
            val dx = event.x - slot.centerX
            val dy = event.y - slot.centerY
            val hitRadius = slot.size / 2f + 10f.dp2px
            if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                activeTouchIndex = i
                activeTouchView = view
                return@forEachIndexed
            }
        }

        val touchedView = activeTouchView ?: return
        val isAddButton = (touchedView == addView)

        if (!isAddButton && activeTouchIndex in config.wheelItems.indices) {
            // Update prompt to show current action label
            promptView.text = config.wheelItems[activeTouchIndex].label(context)
            promptView.setTextColor(DynamicColorUtils.primaryColor)

            // Schedule long press to begin dragging
            longPressRunnable = Runnable {
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                VibratorUtils.vibrator(context, false)
                touchedView.animate().scaleX(1.25f).scaleY(1.25f).translationZ(16f.dp2px).setDuration(120).start()
                bringChildToFront(touchedView)
                promptView.text = context.getString(R.string.quick_setup_wheel_sample_drag_hint)
            }
            postDelayed(longPressRunnable, longPressTimeout)
        }
    }

    private fun handleActionMove(event: MotionEvent) {
        val dx = event.x - downX
        val dy = event.y - downY

        if (!isDragging) {
            if (dx * dx + dy * dy > touchSlop * touchSlop) {
                cancelLongPress()
            }
            return
        }

        // Dragging in progress: follow finger
        parent?.requestDisallowInterceptTouchEvent(true)
        activeTouchView?.let { view ->
            view.x = event.x - view.width / 2f
            view.y = event.y - view.height / 2f
        }
    }

    private fun handleActionUp(event: MotionEvent) {
        cancelLongPress()
        val touchedView = activeTouchView
        val touchedIdx = activeTouchIndex
        val wasDragging = isDragging

        isDragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        resetPrompt()

        if (touchedView == null) return

        touchedView.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()

        if (wasDragging && touchedIdx in config.wheelItems.indices) {
            val views = allDisplayViews()
            val layout = currentLayout ?: calculateWheelLayout(width.toFloat(), height.toFloat(), views.size)

            // Find nearest slot among ACTION slots (excluding Add button)
            var targetSlot = touchedIdx
            var minDistSq = Float.MAX_VALUE
            for (i in 0 until config.wheelItems.size) {
                if (i >= layout.slots.size) break
                val slot = layout.slots[i]
                val dx = event.x - slot.centerX
                val dy = event.y - slot.centerY
                val distSq = dx * dx + dy * dy
                if (distSq < minDistSq) {
                    minDistSq = distSq
                    targetSlot = i
                }
            }

            if (targetSlot in config.wheelItems.indices && targetSlot != touchedIdx) {
                val newItems = config.wheelItems.toMutableList()
                val moved = newItems.removeAt(touchedIdx)
                newItems.add(targetSlot.coerceIn(0, newItems.size), moved)
                VibratorUtils.vibrator(context, true)
                onReorderItems?.invoke(newItems)
            } else {
                positionItemViews(animate = true)
            }
        } else {
            // Tap!
            val dx = event.x - downX
            val dy = event.y - downY
            if (dx * dx + dy * dy <= touchSlop * touchSlop * 4) {
                if (touchedView == addView) {
                    VibratorUtils.vibrator(context, false)
                    onAddItem?.invoke()
                } else if (touchedIdx in config.wheelItems.indices) {
                    VibratorUtils.vibrator(context, false)
                    showItemMenu(touchedView, touchedIdx)
                }
            } else {
                positionItemViews(animate = true)
            }
        }

        activeTouchView = null
        activeTouchIndex = -1
    }

    private fun handleActionCancel() {
        cancelLongPress()
        if (isDragging) {
            isDragging = false
            parent?.requestDisallowInterceptTouchEvent(false)
            activeTouchView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.translationZ(0f)?.setDuration(150)?.start()
            positionItemViews(animate = true)
        }
        resetPrompt()
        activeTouchView = null
        activeTouchIndex = -1
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
    }

    override fun onDetachedFromWindow() {
        cancelLongPress()
        if (isDragging) {
            isDragging = false
            activeTouchView?.scaleX = 1.0f
            activeTouchView?.scaleY = 1.0f
            activeTouchView?.translationZ = 0f
            activeTouchView = null
            activeTouchIndex = -1
        }
        actionViews.forEach { it.animate().cancel() }
        addView?.animate()?.cancel()
        super.onDetachedFromWindow()
    }

    private fun resetPrompt() {
        promptView.text = context.getString(R.string.quick_setup_wheel_sample_hint)
        promptView.setTextColor(DynamicColorUtils.onSurfaceVariantColor)
    }

    private fun showItemMenu(anchorView: View, index: Int) {
        val popup = ActionTextMenuPopup(context, hostView = this)
        val menuItems = mutableListOf<ActionTextMenuItem>()
        val action = config.wheelItems[index]

        // Action title header
        menuItems.add(ActionTextMenuItem(action.label(context)) {})

        menuItems.add(ActionTextMenuItem(context.getString(R.string.action_view_item_menu_edit)) {
            onEditItem?.invoke(index)
        })

        if (config.wheelItems.size > 2) {
            menuItems.add(ActionTextMenuItem(
                context.getString(R.string.action_view_item_menu_delete),
                destructive = true
            ) {
                onRemoveItem?.invoke(index)
            })
        }

        popup.show(anchorView, menuItems)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw rounded card border & background
        val corner = 16f.dp2px
        cardRect.set(1f, 1f, w - 1f, h - 1f)

        bgPaint.color = DynamicColorUtils.surfaceContainerColor
        canvas.drawRoundRect(cardRect, corner, corner, bgPaint)

        borderPaint.color = ColorUtils.setAlphaComponent(DynamicColorUtils.outlineColor, 60)
        canvas.drawRoundRect(cardRect, corner, corner, borderPaint)

        val layout = currentLayout ?: calculateWheelLayout(w, h, allDisplayViews().size)
        val cy = layout.cy

        // 2. Draw trigger edge indicator pill at the edge
        val triggerWidth = 5f.dp2px
        val triggerHeight = (h * (config.triggerHeightDp.toFloat() / 700f)).coerceIn(24f.dp2px, 80f.dp2px)
        val triggerLeft = if (layout.isLeft) 4f.dp2px else w - 4f.dp2px - triggerWidth
        val triggerCenterY = if (layout.sweepAngle < 180f) layout.cy else (h * (config.verticalPercent / 100f))
        val triggerTop = (triggerCenterY - triggerHeight / 2f).coerceIn(4f.dp2px, h - triggerHeight - 4f.dp2px)
        val triggerRect = RectF(triggerLeft, triggerTop, triggerLeft + triggerWidth, triggerTop + triggerHeight)

        triggerPaint.color = DynamicColorUtils.primaryColor
        canvas.drawRoundRect(triggerRect, triggerWidth / 2f, triggerWidth / 2f, triggerPaint)
    }
}
