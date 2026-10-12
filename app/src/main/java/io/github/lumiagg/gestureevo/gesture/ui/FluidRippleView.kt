package io.github.lumiagg.gestureevo.gesture.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.RelativeLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class EdgeSide { LEFT, RIGHT, TOP, BOTTOM }

class FluidRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {
    var edgeColor: Int = DynamicColorUtils.secondaryContainerColor
        set(value) {
            field = value
            invalidate()
        }

    private var fluidConfig: GestureFluidConfig = GestureFluidConfig.DEFAULT
    val commitDistancePx: Float
        get() = fluidConfig.maxTravelPx

    private var edgeSide = EdgeSide.LEFT
    private var baseX = 0f
    private var baseY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isActive = false
    private var showProgress = 0f
    private var hideProgress = 0f
    private var isShowing = false
    private var isHiding = false
    private var showAnimator: ValueAnimator? = null
    private var hideAnimator: ValueAnimator? = null

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val classicPath = Path()
    private val capsuleRect = RectF()

    fun applyAppearance(config: GestureFluidConfig = GestureFluidAppearance.current) {
        fluidConfig = config
        edgeColor = config.resolvedColor
        strokePaint.strokeWidth = config.strokeWidthPx
        strokePaint.color = config.resolvedStrokeColor
        invalidate()
    }

    private val roundImageTextViews = mutableListOf<RoundImageTextView>()
    private var activeIconCount = 0

    private val bulgeDistance: Float
        get() = sqrt((currentX - baseX).pow(2) + (currentY - baseY).pow(2))

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        ensureIconViews(1)
    }

    private fun ensureIconViews(count: Int) {
        while (roundImageTextViews.size < count) {
            val view = RoundImageTextView(context).apply {
                layoutParams = LayoutParams(
                    30.dp2px.toInt(),
                    30.dp2px.toInt()
                )
                isVisible = false
            }
            addView(view)
            roundImageTextViews.add(view)
        }
    }

    fun startFluidRipple(side: EdgeSide, x: Float, y: Float) {
        cancelAnimators()
        edgeSide = side
        baseX = x
        baseY = y
        currentX = x
        currentY = y
        isActive = true
        isShowing = true
        isHiding = false
        showProgress = 0f
        hideProgress = 0f
        startShowAnimation()
        invalidate()
    }

    fun updatePosition(x: Float, y: Float) {
        if (!isActive) return

        val dx = x - baseX
        val dy = y - baseY
        val distance = sqrt(dx * dx + dy * dy)
        val maxDistance = fluidConfig.maxTravelPx

        if (distance <= maxDistance) {
            currentX = x
            currentY = y
        } else {
            val ratio = maxDistance / distance
            currentX = baseX + dx * ratio
            currentY = baseY + dy * ratio
        }
        invalidate()
    }

    fun stopFluidRipple() {
        if (!isActive || isHiding) return
        cancelAnimators()
        isHiding = true
        isShowing = false
        hideProgress = 0f
        startHideAnimation()
    }

    fun stopImmediate() {
        cancelAnimators()
        isActive = false
        isShowing = false
        isHiding = false
        showProgress = 0f
        hideProgress = 0f
        clearRoundImageContent()
        invalidate()
    }

    private fun startShowAnimation() {
        showAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                showProgress = animation.animatedValue as Float
                if (isShowing) invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isShowing = false
                    showProgress = 1f
                }
            })
            start()
        }
    }

    private fun startHideAnimation() {
        hideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            interpolator = AccelerateInterpolator()
            addUpdateListener { animation ->
                hideProgress = animation.animatedValue as Float
                updateRoundImageViewsVisibility()
                if (isHiding) invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isActive = false
                    isHiding = false
                    hideProgress = 1f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isActive && !isHiding) return

        drawFluidRipple(canvas)
    }

    private fun drawFluidRipple(canvas: Canvas) {
        val animationAlpha = when {
            isShowing -> showProgress
            isHiding -> 1f - hideProgress
            else -> 1f
        }
        when (fluidConfig.style) {
            GestureFluidConfig.Style.CAPSULE -> drawCapsule(canvas, animationAlpha)
            GestureFluidConfig.Style.ARC -> drawArc(canvas, animationAlpha)
            GestureFluidConfig.Style.CLASSIC -> drawClassic(canvas, animationAlpha)
        }
    }

    private fun drawClassic(canvas: Canvas, animationAlpha: Float) {
        val baseRadius = fluidConfig.baseRadiusPx
        val bulgeDist = bulgeDistance
        val bulgeRadius = baseRadius + bulgeDist * 0.3f
        val controlOffsetPeek = bulgeDist * fluidConfig.peekFactor * animationAlpha
        val controlOffsetXY = baseRadius * fluidConfig.controlFactor
        val path = classicPath
        path.reset()

        when (edgeSide) {
            EdgeSide.LEFT -> {
                val topY = min(baseY - baseRadius, currentY - baseRadius)
                val bottomY = max(baseY + baseRadius, currentY + baseRadius)
                path.moveTo(0f, topY)
                path.cubicTo(
                    0f, currentY - controlOffsetXY,
                    controlOffsetPeek, currentY - controlOffsetXY,
                    controlOffsetPeek, currentY
                )
                path.cubicTo(
                    controlOffsetPeek, currentY + controlOffsetXY,
                    0f, currentY + controlOffsetXY,
                    0f, bottomY
                )
                path.close()
                updateRoundImagePositions(controlOffsetPeek, currentY)
            }

            EdgeSide.RIGHT -> {
                val edge = width.toFloat()
                val topY = min(baseY - baseRadius, currentY - baseRadius)
                val bottomY = max(baseY + baseRadius, currentY + baseRadius)
                path.moveTo(edge, topY)
                path.cubicTo(
                    edge, currentY - controlOffsetXY,
                    edge - controlOffsetPeek, currentY - controlOffsetXY,
                    edge - controlOffsetPeek, currentY
                )
                path.cubicTo(
                    edge - controlOffsetPeek, currentY + controlOffsetXY,
                    edge, currentY + controlOffsetXY,
                    edge, bottomY
                )
                path.close()
                updateRoundImagePositions(edge - controlOffsetPeek, currentY)
            }

            EdgeSide.TOP -> {
                val leftX = min(baseX - baseRadius, currentX - baseRadius)
                val rightX = max(baseX + baseRadius, currentX + baseRadius)
                path.moveTo(leftX, 0f)
                path.cubicTo(
                    currentX - controlOffsetXY, 0f,
                    currentX - controlOffsetXY, controlOffsetPeek,
                    currentX, controlOffsetPeek
                )
                path.cubicTo(
                    currentX + controlOffsetXY, controlOffsetPeek,
                    currentX + controlOffsetXY, 0f,
                    rightX, 0f
                )
                path.close()
                updateRoundImagePositions(currentX, controlOffsetPeek)
            }

            EdgeSide.BOTTOM -> {
                val edge = height.toFloat()
                val leftX = min(baseX - baseRadius, currentX - baseRadius)
                val rightX = max(baseX + baseRadius, currentX + baseRadius)
                path.moveTo(leftX, edge)
                path.cubicTo(
                    currentX - controlOffsetXY, edge,
                    currentX - controlOffsetXY, edge - controlOffsetPeek,
                    currentX, edge - controlOffsetPeek
                )
                path.cubicTo(
                    currentX + controlOffsetXY, edge - controlOffsetPeek,
                    currentX + controlOffsetXY, edge,
                    rightX, edge
                )
                path.close()
                updateRoundImagePositions(currentX, edge - controlOffsetPeek / 2)
            }
        }

        edgePaint.shader = createEdgeGradient(animationAlpha, bulgeRadius)
        canvas.drawPath(path, edgePaint)
        drawStroke(canvas, path, animationAlpha)
    }

    private fun drawCapsule(canvas: Canvas, animationAlpha: Float) {
        val barSize = fluidConfig.baseRadiusPx * 1.15f
        val peek = 18f.dp2px * fluidConfig.sizeScale +
            bulgeDistance * fluidConfig.peekFactor * animationAlpha
        val radius = barSize / 2f
        val rect = capsuleRect
        when (edgeSide) {
            EdgeSide.LEFT -> {
                rect.set(0f, currentY - radius, peek, currentY + radius)
                edgePaint.shader = linearFade(0f, currentY, peek, currentY, animationAlpha, 0.4f)
                updateRoundImagePositions(peek * 0.45f, currentY)
            }

            EdgeSide.RIGHT -> {
                val edge = width.toFloat()
                rect.set(edge - peek, currentY - radius, edge, currentY + radius)
                edgePaint.shader = linearFade(edge, currentY, edge - peek, currentY, animationAlpha, 0.4f)
                updateRoundImagePositions(edge - peek * 0.45f, currentY)
            }

            EdgeSide.TOP -> {
                rect.set(currentX - radius, 0f, currentX + radius, peek)
                edgePaint.shader = linearFade(currentX, 0f, currentX, peek, animationAlpha, 0.4f)
                updateRoundImagePositions(currentX, peek * 0.45f)
            }

            EdgeSide.BOTTOM -> {
                val edge = height.toFloat()
                rect.set(currentX - radius, edge - peek, currentX + radius, edge)
                edgePaint.shader = linearFade(currentX, edge, currentX, edge - peek, animationAlpha, 0.4f)
                updateRoundImagePositions(currentX, edge - peek * 0.45f)
            }
        }
        canvas.drawRoundRect(rect, radius, radius, edgePaint)
        drawStroke(canvas, rect, radius, animationAlpha)
    }

    private fun drawArc(canvas: Canvas, animationAlpha: Float) {
        val radius = fluidConfig.baseRadiusPx + bulgeDistance * 0.22f
        val peek = (bulgeDistance * fluidConfig.peekFactor * animationAlpha)
            .coerceAtLeast(8f.dp2px * fluidConfig.sizeScale)
        val cx: Float
        val cy: Float
        when (edgeSide) {
            EdgeSide.LEFT -> {
                cx = peek - radius
                cy = currentY
                updateRoundImagePositions(peek * 0.4f, currentY)
            }

            EdgeSide.RIGHT -> {
                cx = width - peek + radius
                cy = currentY
                updateRoundImagePositions(width - peek * 0.4f, currentY)
            }

            EdgeSide.TOP -> {
                cx = currentX
                cy = peek - radius
                updateRoundImagePositions(currentX, peek * 0.4f)
            }

            EdgeSide.BOTTOM -> {
                cx = currentX
                cy = height - peek + radius
                updateRoundImagePositions(currentX, height - peek * 0.4f)
            }
        }
        edgePaint.shader = RadialGradient(
            when (edgeSide) {
                EdgeSide.LEFT -> 0f
                EdgeSide.RIGHT -> width.toFloat()
                else -> currentX
            },
            when (edgeSide) {
                EdgeSide.TOP -> 0f
                EdgeSide.BOTTOM -> height.toFloat()
                else -> currentY
            },
            radius,
            modulatedColor(animationAlpha),
            modulatedColor(0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, edgePaint)
        if (fluidConfig.strokeWidthPx > 0f) {
            strokePaint.shader = null
            strokePaint.color = modulatedStrokeColor(animationAlpha)
            strokePaint.strokeWidth = fluidConfig.strokeWidthPx
            canvas.drawCircle(cx, cy, radius, strokePaint)
        }
    }

    private fun drawStroke(canvas: Canvas, path: Path, animationAlpha: Float) {
        if (fluidConfig.strokeWidthPx <= 0f) return
        strokePaint.shader = null
        strokePaint.color = modulatedStrokeColor(animationAlpha)
        strokePaint.strokeWidth = fluidConfig.strokeWidthPx
        canvas.drawPath(path, strokePaint)
    }

    private fun drawStroke(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        animationAlpha: Float,
    ) {
        if (fluidConfig.strokeWidthPx <= 0f) return
        strokePaint.shader = null
        strokePaint.color = modulatedStrokeColor(animationAlpha)
        strokePaint.strokeWidth = fluidConfig.strokeWidthPx
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    private fun updateRoundImagePositions(
        centerX: Float,
        centerY: Float,
    ) {
        if (activeIconCount <= 0) return

        val iconSize = if (activeIconCount <= 1) 30.dp2px else 24.dp2px
        val spacing = 6.dp2px
        val totalLength =
            activeIconCount * iconSize + (activeIconCount - 1) * spacing

        val bulgeDepth = bulgeDistance * 0.4f

        when (edgeSide) {
            EdgeSide.LEFT -> {
                val iconX = bulgeDepth * 0.6f - iconSize / 2
                val startY = centerY - totalLength / 2
                roundImageTextViews.take(activeIconCount).forEachIndexed { index, view ->
                    updateIconSize(view, iconSize)
                    view.x = iconX
                    view.y = startY + index * (iconSize + spacing)
                }
            }

            EdgeSide.RIGHT -> {
                val iconX = width - bulgeDepth * 0.6f - iconSize / 2
                val startY = centerY - totalLength / 2
                roundImageTextViews.take(activeIconCount).forEachIndexed { index, view ->
                    updateIconSize(view, iconSize)
                    view.x = iconX
                    view.y = startY + index * (iconSize + spacing)
                }
            }

            EdgeSide.TOP -> {
                val iconY = bulgeDepth * 0.6f - iconSize / 2
                val startX = centerX - totalLength / 2
                roundImageTextViews.take(activeIconCount).forEachIndexed { index, view ->
                    updateIconSize(view, iconSize)
                    view.x = startX + index * (iconSize + spacing)
                    view.y = iconY
                }
            }

            EdgeSide.BOTTOM -> {
                val iconY = height - bulgeDepth * 0.6f - iconSize / 2
                val startX = centerX - totalLength / 2
                roundImageTextViews.take(activeIconCount).forEachIndexed { index, view ->
                    updateIconSize(view, iconSize)
                    view.x = startX + index * (iconSize + spacing)
                    view.y = iconY
                }
            }
        }
    }

    fun showPredictedActions(actionItems: List<GestureActionItem>) {
        if (actionItems.isEmpty()) {
            clearRoundImageContent()
            return
        }

        ensureIconViews(actionItems.size)
        activeIconCount = actionItems.size
        roundImageTextViews.forEachIndexed { index, view ->
            if (index < actionItems.size) {
                view.alpha = 1f
                view.isVisible = true
                try {
                    gestureActionIcon(context, view, actionItems[index])
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting predicted action", e)
                }
            } else {
                view.isVisible = false
                view.setImage(null)
            }
        }
    }

    fun clearRoundImageContent() {
        activeIconCount = 0
        roundImageTextViews.forEach { view ->
            view.isVisible = false
            view.setImage(null)
        }
    }

    private fun updateRoundImageViewsVisibility() {
        val animationAlpha = when {
            isShowing -> showProgress
            isHiding -> 1f - hideProgress
            else -> if (isActive) 1f else 0f
        }

        roundImageTextViews.forEach { roundView ->
            if (animationAlpha > 0f) {
                roundView.alpha = animationAlpha
            }
        }
    }

    private fun modulatedColor(factor: Float): Int = modulate(edgeColor, factor)

    private fun modulatedStrokeColor(factor: Float): Int =
        modulate(fluidConfig.resolvedStrokeColor, factor)

    private fun modulate(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return ColorUtils.setAlphaComponent(color, alpha)
    }

    private fun linearFade(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        animationAlpha: Float,
        endFactor: Float,
    ): Shader {
        return LinearGradient(
            x0, y0, x1, y1,
            modulatedColor(animationAlpha),
            modulatedColor(animationAlpha * endFactor),
            Shader.TileMode.CLAMP
        )
    }

    private fun createEdgeGradient(animationAlpha: Float, bulgeRadius: Float): Shader {
        return when (edgeSide) {
            EdgeSide.LEFT -> LinearGradient(
                0f, baseY,
                bulgeRadius, baseY,
                modulatedColor(animationAlpha),
                modulatedColor(0f),
                Shader.TileMode.CLAMP
            )

            EdgeSide.RIGHT -> LinearGradient(
                width.toFloat(), baseY,
                width - bulgeRadius, baseY,
                modulatedColor(animationAlpha),
                modulatedColor(0f),
                Shader.TileMode.CLAMP
            )

            EdgeSide.TOP -> LinearGradient(
                baseX, 0f,
                baseX, bulgeRadius,
                modulatedColor(animationAlpha),
                modulatedColor(0f),
                Shader.TileMode.CLAMP
            )

            EdgeSide.BOTTOM -> LinearGradient(
                baseX, height.toFloat(),
                baseX, height - bulgeRadius,
                modulatedColor(animationAlpha),
                modulatedColor(0f),
                Shader.TileMode.CLAMP
            )
        }
    }

    private fun updateIconSize(view: RoundImageTextView, iconSize: Float) {
        val params = view.layoutParams
        if (params.width != iconSize.toInt() || params.height != iconSize.toInt()) {
            params.width = iconSize.toInt()
            params.height = iconSize.toInt()
            view.layoutParams = params
        }
    }

    private fun cancelAnimators() {
        showAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        showAnimator = null
        hideAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        hideAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopImmediate()
        super.onDetachedFromWindow()
    }

    companion object {
        const val TAG = "FluidRippleView"
        const val MAX_BULGE_DISTANCE_DP = 60f
        val maxBulgeDistancePx: Float
            get() = MAX_BULGE_DISTANCE_DP.dp2px
    }
}
