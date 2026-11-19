package io.github.lumiagg.gestureevo.drop

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import io.github.lumiagg.gestureevo.utils.OverlaySurfaceBlur
import io.github.lumiagg.gestureevo.utils.Rom
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CoroutineScope
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_SYSTEM_DRAG_BLUR
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_CARD_BACKGROUND_BLUR

/** Owns drag overlay appearance; it does not read or mutate drag content or sessions. */
internal class DropOverlayAppearance(private val context: Context, scope: CoroutineScope) :
    BaseFloatingWindow(context, scope, "ExternalDropAppearance") {
    private var internalDrag = false
    private var visible = false
    private val blurBackground = View(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val blurParams = WindowManager.LayoutParams().apply {
        configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            title = "GestureEVO:ExternalDrop:Background",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
    }
    private var backgroundBlurEnabled = false
    private var systemDragBlurEnabled = true
    private var cardBlurEnabled = false
    private var backgroundBlurRadius = 0
    private var backgroundBlurAnimator: ValueAnimator? = null
    /** Whether the full-screen blur may run for the drag in progress. */
    val backgroundBlurActive: Boolean
        get() = backgroundBlurEnabled && (internalDrag || systemDragBlurEnabled)
    private val originOsSurfaceBlur = Rom.isOriginOS()

    suspend fun configure() {
        backgroundBlurEnabled = context.settingsStore.readKey(KEY_EXTERNAL_DROP_BACKGROUND_BLUR, false) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        systemDragBlurEnabled = context.settingsStore.readKey(KEY_EXTERNAL_DROP_SYSTEM_DRAG_BLUR, true)
        cardBlurEnabled = context.settingsStore.readKey(KEY_EXTERNAL_DROP_CARD_BACKGROUND_BLUR, false)
    }

    fun show(stillCurrent: () -> Boolean) {
        if (backgroundBlurActive) {
            visible = true
            attachViewSafely(
                blurBackground,
                blurParams,
                // Retrying after the targets attach would put blur above the targets.
                shouldRetry = { false },
                onAttached = {
                    blurBackground.post {
                        if (visible && stillCurrent() && blurBackground.isAttachedToWindow) startBackgroundBlur()
                    }
                },
            )
        }
    }

    fun hide() {
        visible = false
        clearBackgroundBlur()
        detachViewSafely(blurBackground)
    }

    fun setHighlighted(card: MaterialCardView, active: Boolean) {
        styleDropTarget(card, active, if (cardBlurEnabled) 199 else 255)
    }

    private fun targetSurfaceColor(): Int = ColorUtils.setAlphaComponent(
        DynamicColorUtils.surfaceContainerHighColor, if (cardBlurEnabled) 199 else 255,
    )

    /** Applies the panel glass style from the current blur settings before each show. */
    fun applyGlassStyle(targets: View, internalDrag: Boolean) {
        this.internalDrag = internalDrag
        val panel = targets.findViewById<MaterialCardView>(R.id.externalDropPanel)
        val enginesCard = targets.findViewById<MaterialCardView>(R.id.externalDropEnginesCard)
        // The effective state keeps the glass consistent when a system drag suppresses the full-screen blur.
        GlassEffectUtils.applyCardStyle(
            card = panel,
            color = DynamicColorUtils.surfaceContainerColor,
            glassEnabled = cardBlurEnabled,
            blurRegion = GlassEffectUtils.usesCardRegionBlur(backgroundBlurActive, cardBlurEnabled),
            backgroundBlurEnabled = backgroundBlurActive,
            overlayOnContent = true,
        )
        if (cardBlurEnabled) {
            // Keep busy content behind the overlay from washing out its labels and icons.
            val color = panel.cardBackgroundColor.defaultColor
            panel.setCardBackgroundColor(ColorUtils.setAlphaComponent(color, maxOf(Color.alpha(color), 209)))
        } else {
            panel.cardElevation = PANEL_ELEVATION_DP.dp2px
        }
        panel.strokeWidth = 1.dp2px.toInt().coerceAtLeast(1)
        panel.strokeColor = ColorUtils.setAlphaComponent(DynamicColorUtils.onSurfaceColor, 48)
        enginesCard.setCardBackgroundColor(targetSurfaceColor())
        enginesCard.strokeWidth = 1.dp2px.toInt().coerceAtLeast(1)
        enginesCard.strokeColor = ColorUtils.setAlphaComponent(DynamicColorUtils.onSurfaceColor, 28)
    }

    private fun startBackgroundBlur() {
        if (!backgroundBlurEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (backgroundBlurRadius == BACKGROUND_BLUR_RADIUS) return
        val animator = ValueAnimator.ofInt(backgroundBlurRadius, BACKGROUND_BLUR_RADIUS).apply {
            duration = BLUR_ANIMATION_MILLIS
            interpolator = LinearInterpolator()
            addUpdateListener { valueAnimator ->
                applyBackgroundBlurRadius(valueAnimator.animatedValue as Int)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (backgroundBlurAnimator !== this@apply) return
                    backgroundBlurAnimator = null
                }
            })
        }
        val previous = backgroundBlurAnimator
        backgroundBlurAnimator = animator
        previous?.cancel()
        animator.start()
    }

    private fun applyBackgroundBlurRadius(radius: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        backgroundBlurRadius = radius
        if (originOsSurfaceBlur) {
            OverlaySurfaceBlur.apply(blurBackground, radius)
            return
        }
        val blurFlag = WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        blurParams.blurBehindRadius = radius
        blurParams.flags = if (radius > 0) {
            blurParams.flags or blurFlag
        } else {
            blurParams.flags and blurFlag.inv()
        }
        if (!blurBackground.isAttachedToWindow) return
        updateViewLayoutSafely(blurBackground, blurParams)
    }

    private fun clearBackgroundBlur() {
        backgroundBlurAnimator?.cancel()
        backgroundBlurAnimator = null
        applyBackgroundBlurRadius(0)
    }

    private companion object {
        const val BACKGROUND_BLUR_RADIUS = 72
        const val BLUR_ANIMATION_MILLIS = 250L
        const val PANEL_ELEVATION_DP = 2
    }
}

internal fun styleDropTarget(card: MaterialCardView, active: Boolean, surfaceAlpha: Int = 255) {
    val onSurface = DynamicColorUtils.onSurfaceColor.takeIf { it != 0 }
        ?: MaterialColors.getColor(card, MaterialR.attr.colorOnSurface)
    val stroke = if (active) {
        DynamicColorUtils.primaryColor.takeIf { it != 0 }
            ?: MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
    } else {
        ColorUtils.setAlphaComponent(onSurface, 28)
    }
    val surface = if (active) {
        DynamicColorUtils.secondaryContainerColor.takeIf { it != 0 }
            ?: MaterialColors.getColor(card, MaterialR.attr.colorSecondaryContainer)
    } else {
        val idle = DynamicColorUtils.surfaceContainerHighColor.takeIf { it != 0 }
            ?: MaterialColors.getColor(card, MaterialR.attr.colorSurfaceContainerHigh)
        ColorUtils.setAlphaComponent(idle, surfaceAlpha)
    }
    card.strokeWidth = (if (active) 2 else 1).dp2px.toInt().coerceAtLeast(1)
    card.strokeColor = stroke
    card.setCardBackgroundColor(surface)
}
