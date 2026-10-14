package io.github.lumiagg.gestureevo.settings.guide

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
import io.github.lumiagg.gestureevo.ui.ActionTextMenuItem
import io.github.lumiagg.gestureevo.ui.ActionTextMenuPopup
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Built-in interactive wheel sample view that matches the real wheel's adaptive shape
 * (quadrants 0..3 based on trigger side and vertical position), drag-to-reorder, and add icon.
 */
class GuideWheelSampleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var config: QuickSetupConfig = QuickSetupConfig.default()
    private var onEditItem: ((Int) -> Unit)? = null
    private var onRemoveItem: ((Int) -> Unit)? = null
    private var onAddItem: (() -> Unit)? = null
    private var onReorderItems: ((List<QuickAction>) -> Unit)? = null

    // For BOTH sides, allows toggling preview between left and right
    private var previewSideOverride: Boolean? = null

    private val originMargin = 16f.dp2px
    private val itemRadius = 185f.dp2px
    private val fanRadius = 225f.dp2px
    private var currentBubbleSize = 42f.dp2px.toInt()

    private fun getEffectiveBubbleSize(itemCount: Int): Int {
        val count = itemCount.coerceAtLeast(1)
        val sweepAngle = 90.0
        val stepAngle = sweepAngle / count
        // Chord distance between adjacent items on the arc
        val chordDistance = 2.0 * itemRadius * sin(Math.toRadians(stepAngle / 2.0))
        // Leave at least 4dp gap between bubbles, clamp between 24dp and 42dp
        val desiredSize = (chordDistance - 4f.dp2px).toFloat()
        return desiredSize.coerceIn(24f.dp2px, 42f.dp2px).toInt()
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp2px
    }

    private val guideArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f.dp2px
        pathEffect = DashPathEffect(floatArrayOf(6f.dp2px, 6f.dp2px), 0f)
    }

    private val triggerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val fanPath = Path()
    private val fanRect = RectF()
    private val cardRect = RectF()

    private val sideToggleChip: TextView
    private val promptView: TextView

    // Action item views + optional Add button view at the end
    private val actionViews = mutableListOf<View>()
    private var addView: View? = null

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

    private fun getOriginAndAngles(w: Float, h: Float): OriginAngles {
        val isLeft = isEffectiveLeft()
        val isUpper = config.verticalPercent < 50
        val cx = if (isLeft) originMargin else w - originMargin
        val cy = if (isUpper) originMargin else h - originMargin

        // Android screen coordinate angles (0° = Right, 90° = Down, 180° = Left, 270° = Up)
        val (startAngle, sweepAngle) = when {
            isLeft && isUpper -> 0f to 90f      // Quadrant 3: Right to Down
            isLeft && !isUpper -> 270f to 90f   // Quadrant 0: Up to Right
            !isLeft && isUpper -> 90f to 90f    // Quadrant 2: Down to Left
            else -> 180f to 90f                 // Quadrant 1: Left to Up
        }
        return OriginAngles(cx, cy, startAngle, sweepAngle, isLeft)
    }

    private data class OriginAngles(
        val cx: Float,
        val cy: Float,
        val startAngle: Float,
        val sweepAngle: Float,
        val mirror: Boolean
    )

    private fun rebuildViews(animate: Boolean) {
        actionViews.forEach { removeView(it) }
        actionViews.clear()
        addView?.let { removeView(it) }
        addView = null

        val totalCount = config.wheelItems.size + 1
        currentBubbleSize = getEffectiveBubbleSize(totalCount)

        // 1. Build action item bubble views (clean circular bubbles, no text underneath)
        config.wheelItems.forEachIndexed { index, action ->
            val itemView = createActionItemView(index, action)
            actionViews.add(itemView)
            addView(itemView)
        }

        // 2. Build Add item ("+") - always available at the end (matches real wheel edit mode)
        val addBtn = createAddButtonView()
        addView = addBtn
        addView(addBtn)

        positionItemViews(animate)
        updatePromptPosition()
    }

    private fun createActionItemView(index: Int, action: QuickAction): View {
        return ImageView(context).apply {
            layoutParams = LayoutParams(currentBubbleSize, currentBubbleSize)
            val pad = (currentBubbleSize * 0.22f).toInt().coerceAtLeast(4f.dp2px.toInt())
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(DynamicColorUtils.secondaryContainerColor)
            }
            elevation = 3f.dp2px
            setImageDrawable(action.icon(context))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (action.type != QuickActionType.OPEN_APP || action.packageName == null) {
                imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
            }
        }
    }

    private fun createAddButtonView(): View {
        return ImageView(context).apply {
            layoutParams = LayoutParams(currentBubbleSize, currentBubbleSize)
            val pad = (currentBubbleSize * 0.22f).toInt().coerceAtLeast(4f.dp2px.toInt())
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(DynamicColorUtils.surfaceContainerHighColor)
                setStroke(1f.dp2px.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(DynamicColorUtils.primaryColor, 120))
            }
            elevation = 2f.dp2px
            setImageResource(R.drawable.baseline_add_24)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
        }
    }

    private fun allDisplayViews(): List<View> {
        val list = mutableListOf<View>()
        list.addAll(actionViews)
        addView?.let { list.add(it) }
        return list
    }

    private fun calculateSlotAngles(w: Float, h: Float): List<Float> {
        val (cx, cy, startAngle, sweepAngle, mirror) = getOriginAndAngles(w, h)
        val count = allDisplayViews().size.coerceAtLeast(1)
        val step = sweepAngle / count
        val offset = step / 2f
        return (0 until count).map { i ->
            val sliceIndex = if (mirror) (count - 1 - i) else i
            startAngle + offset + sliceIndex * step
        }
    }

    private fun positionItemViews(animate: Boolean) {
        val w = width.toFloat().takeIf { it > 0 } ?: return
        val h = height.toFloat().takeIf { it > 0 } ?: return
        val (cx, cy) = getOriginAndAngles(w, h)
        val angles = calculateSlotAngles(w, h)
        val views = allDisplayViews()

        val animators = mutableListOf<ObjectAnimator>()

        views.forEachIndexed { i, view ->
            if (i >= angles.size) return@forEachIndexed
            if (isDragging && view == activeTouchView) return@forEachIndexed

            val angle = angles[i]
            val rad = Math.toRadians(angle.toDouble())

            val targetCenterX = cx + itemRadius * cos(rad).toFloat()
            val targetCenterY = cy + itemRadius * sin(rad).toFloat()

            val targetX = targetCenterX - currentBubbleSize / 2f
            val targetY = targetCenterY - currentBubbleSize / 2f

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
        val hitRadius = currentBubbleSize / 2f + 12f.dp2px
        activeTouchIndex = -1
        activeTouchView = null

        views.forEachIndexed { i, view ->
            val vcx = view.x + currentBubbleSize / 2f
            val vcy = view.y + currentBubbleSize / 2f
            val dx = event.x - vcx
            val dy = event.y - vcy
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
            view.x = event.x - currentBubbleSize / 2f
            view.y = event.y - currentBubbleSize / 2f
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
            // Calculate nearest slot among action items (exclude Add button)
            val targetSlot = findNearestSlotIndex(event.x, event.y, maxSlot = config.wheelItems.size - 1)
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

    private fun resetPrompt() {
        promptView.text = context.getString(R.string.quick_setup_wheel_sample_hint)
        promptView.setTextColor(DynamicColorUtils.onSurfaceVariantColor)
    }

    private fun findNearestSlotIndex(touchX: Float, touchY: Float, maxSlot: Int): Int {
        val w = width.toFloat().takeIf { it > 0 } ?: return 0
        val h = height.toFloat().takeIf { it > 0 } ?: return 0
        val (cx, cy) = getOriginAndAngles(w, h)
        val angles = calculateSlotAngles(w, h)
        if (angles.isEmpty()) return 0

        val touchAngleRad = atan2((touchY - cy).toDouble(), (touchX - cx).toDouble())
        var touchAngleDeg = Math.toDegrees(touchAngleRad).toFloat()
        if (touchAngleDeg < 0) touchAngleDeg += 360f

        var nearestIndex = 0
        var minDiff = Float.MAX_VALUE

        val limit = maxSlot.coerceAtMost(angles.lastIndex)
        for (i in 0..limit) {
            var diff = abs(touchAngleDeg - angles[i]) % 360f
            if (diff > 180f) diff = 360f - diff
            if (diff < minDiff) {
                minDiff = diff
                nearestIndex = i
            }
        }
        return nearestIndex
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

        // 2. Calculate origin & angles
        val (cx, cy, startAngle, sweepAngle, isLeft) = getOriginAndAngles(w, h)

        // 3. Draw fan sector background
        fanRect.set(cx - fanRadius, cy - fanRadius, cx + fanRadius, cy + fanRadius)
        fanPath.reset()
        fanPath.moveTo(cx, cy)
        fanPath.arcTo(fanRect, startAngle, sweepAngle)
        fanPath.close()

        val primaryColor = DynamicColorUtils.primaryColor
        val fanGradient = RadialGradient(
            cx, cy, fanRadius,
            intArrayOf(
                ColorUtils.setAlphaComponent(primaryColor, 45),
                ColorUtils.setAlphaComponent(primaryColor, 15),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        bgPaint.shader = fanGradient
        canvas.drawPath(fanPath, bgPaint)
        bgPaint.shader = null

        // 4. Draw guide arc guideline
        guideArcPaint.color = ColorUtils.setAlphaComponent(primaryColor, 55)
        val arcRect = RectF(cx - itemRadius, cy - itemRadius, cx + itemRadius, cy + itemRadius)
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, guideArcPaint)

        // 5. Draw trigger edge indicator pill at (cx, cy)
        val triggerWidth = 5f.dp2px
        val triggerHeight = 44f.dp2px
        val triggerLeft = if (isLeft) 4f.dp2px else w - 4f.dp2px - triggerWidth
        val triggerTop = (cy - triggerHeight / 2f).coerceIn(4f.dp2px, h - triggerHeight - 4f.dp2px)
        val triggerRect = RectF(triggerLeft, triggerTop, triggerLeft + triggerWidth, triggerTop + triggerHeight)

        triggerPaint.color = primaryColor
        canvas.drawRoundRect(triggerRect, triggerWidth / 2f, triggerWidth / 2f, triggerPaint)
    }
}
