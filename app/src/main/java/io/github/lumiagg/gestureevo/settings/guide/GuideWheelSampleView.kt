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
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.ui.ActionTextMenuItem
import io.github.lumiagg.gestureevo.ui.ActionTextMenuPopup
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlin.math.cos
import kotlin.math.sin

/**
 * Built-in interactive wheel sample view that matches the real wheel's adaptive shape
 * (quadrants 0..3 based on trigger side and vertical position) and editing interaction
 * (tap item to pop up edit/delete menu, without secondary submenu).
 */
class GuideWheelSampleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var config: QuickSetupConfig = QuickSetupConfig.default()
    private var onEditItem: ((Int) -> Unit)? = null
    private var onRemoveItem: ((Int) -> Unit)? = null

    // For BOTH sides, allows toggling preview between left and right
    private var previewSideOverride: Boolean? = null

    private val originMargin = 20f.dp2px
    private val itemRadius = 145f.dp2px
    private val fanRadius = 190f.dp2px
    private val itemSize = 52f.dp2px.toInt()
    private val iconSize = 40f.dp2px.toInt()

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
    private val hintView: TextView

    private val itemViews = mutableListOf<View>()

    init {
        setWillNotDraw(false)
        clipChildren = false

        // Hint label in the corner
        hintView = TextView(context).apply {
            text = context.getString(R.string.quick_setup_wheel_sample_hint)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(DynamicColorUtils.onSurfaceVariantColor)
            alpha = 0.85f
            val pad = 12f.dp2px.toInt()
            setPadding(pad, pad, pad, pad)
        }
        addView(hintView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

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
        onRemoveItem: (Int) -> Unit
    ) {
        val oldConfig = this.config
        this.config = config
        this.onEditItem = onEditItem
        this.onRemoveItem = onRemoveItem

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

    private fun calculateQuadrant(): Int {
        val isLeft = isEffectiveLeft()
        val isUpper = config.verticalPercent < 50
        return when {
            isLeft && isUpper -> 3   // Top-Left -> fans Bottom-Right
            isLeft && !isUpper -> 0  // Bottom-Left -> fans Top-Right
            !isLeft && isUpper -> 2  // Top-Right -> fans Bottom-Left
            else -> 1                // Bottom-Right -> fans Top-Left
        }
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
        itemViews.forEach { removeView(it) }
        itemViews.clear()

        val items = config.wheelItems
        items.forEachIndexed { index, action ->
            val itemView = createWheelItemView(index, action)
            itemViews.add(itemView)
            addView(itemView)
        }

        positionItemViews(animate)
        updateHintPosition()
    }

    private fun createWheelItemView(index: Int, action: QuickAction): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(itemSize, LayoutParams.WRAP_CONTENT)
        }

        // Circular icon badge
        val iconBadge = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            val pad = 8f.dp2px.toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(DynamicColorUtils.secondaryContainerColor)
            }
            elevation = 4f.dp2px
            setImageDrawable(action.icon(context))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (action.type != QuickActionType.OPEN_APP || action.packageName == null) {
                imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
            }
        }
        root.addView(iconBadge)

        // Label
        val labelView = TextView(context).apply {
            text = action.label(context)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
            setTextColor(DynamicColorUtils.onSurfaceColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(itemSize, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 2f.dp2px.toInt()
            }
        }
        root.addView(labelView)

        // Tap item -> show ActionTextMenuPopup (Edit / Delete)
        root.setOnClickListener { v ->
            VibratorUtils.vibrator(context, false)
            val popup = ActionTextMenuPopup(context, hostView = this)
            val menuItems = mutableListOf<ActionTextMenuItem>()

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

            popup.show(iconBadge, menuItems)
        }

        return root
    }

    private fun positionItemViews(animate: Boolean) {
        val w = width.toFloat().takeIf { it > 0 } ?: return
        val h = height.toFloat().takeIf { it > 0 } ?: return
        val (cx, cy, startAngle, sweepAngle, mirror) = getOriginAndAngles(w, h)

        val count = itemViews.size
        if (count == 0) return

        val step = sweepAngle / count
        val offset = step / 2f

        val animators = mutableListOf<ObjectAnimator>()

        itemViews.forEachIndexed { i, view ->
            val sliceIndex = if (mirror) (count - 1 - i) else i
            val angle = startAngle + offset + sliceIndex * step
            val rad = Math.toRadians(angle.toDouble())

            val targetCenterX = cx + itemRadius * cos(rad).toFloat()
            val targetCenterY = cy + itemRadius * sin(rad).toFloat()

            val targetX = targetCenterX - itemSize / 2f
            val targetY = targetCenterY - (iconSize / 2f)

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
                duration = 220
                interpolator = OvershootInterpolator(1.1f)
                start()
            }
        }
    }

    private fun updateHintPosition() {
        val isLeft = isEffectiveLeft()
        val isUpper = config.verticalPercent < 50
        val lp = hintView.layoutParams as LayoutParams

        // Position hint in the corner opposite to the origin
        val gravityH = if (isLeft) Gravity.END else Gravity.START
        val gravityV = if (isUpper) Gravity.BOTTOM else Gravity.TOP
        lp.gravity = gravityH or gravityV
        hintView.layoutParams = lp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        positionItemViews(animate = false)
        updateHintPosition()
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
