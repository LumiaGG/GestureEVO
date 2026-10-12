package io.github.lumiagg.gestureevo.utils

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.annotation.RequiresApi
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

object GlassEffectUtils {
    private const val BACKGROUND_BLUR_RADIUS = 72
    private const val GLASS_CARD_ALPHA = 0.58f
    private const val LIGHT_WINDOW_GLASS_WHITE_MIX = 0.65f
    private const val LIGHT_WINDOW_GLASS_CARD_ALPHA = 0.46f
    private const val LIGHT_OVERLAY_GLASS_WHITE_MIX = 0.28f
    private const val OVERLAY_DUAL_GLASS_ALPHA = 0.86f
    private const val GLASS_SCRIM_ALPHA = 0.32f
    private const val GLASS_EDGE_ALPHA = 20

    fun applyWindowBlur(window: Window, enabled: Boolean) {
        applyWindowBlurProgress(window, if (enabled) 1f else 0f)
    }

    fun applyWindowBlurProgress(window: Window, progress: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val targetRadius = (BACKGROUND_BLUR_RADIUS * progress.coerceIn(0f, 1f)).toInt()
        applyWindowBlurRadius(window, targetRadius)
    }

    fun createWindowBlurAnimator(
        window: Window,
        fromProgress: Float,
        toProgress: Float
    ): ValueAnimator? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return ValueAnimator.ofFloat(fromProgress, toProgress).apply {
            addUpdateListener { animator ->
                applyWindowBlurProgress(window, animator.animatedValue as Float)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyWindowBlurRadius(window: Window, radius: Int) {
        if (radius > 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        val params = window.attributes
        params.blurBehindRadius = radius
        runCatching { window.attributes = params }
        if (radius == 0) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }

    fun glassCardColor(color: Int, enabled: Boolean): Int {
        if (!enabled) return color
        return ColorUtils.setAlphaComponent(color, (GLASS_CARD_ALPHA * 255).toInt())
    }

    fun glassScrimColor(color: Int, enabled: Boolean): Int {
        if (!enabled) return color
        return ColorUtils.setAlphaComponent(color, (GLASS_SCRIM_ALPHA * 255).toInt())
    }

    /**
     * Card-region blur is only for the card switch on its own.
     * With background blur also on, the card is just made transparent.
     */
    fun usesCardRegionBlur(backgroundBlur: Boolean, cardBlur: Boolean): Boolean {
        return cardBlur && !backgroundBlur
    }

    fun applyCardStyle(
        card: MaterialCardView,
        color: Int,
        glassEnabled: Boolean,
        clearForeground: Boolean = false,
        blurRegion: Boolean = true,
        backgroundBlurEnabled: Boolean = false,
        overlayOnContent: Boolean = false,
    ) {
        val isLightTheme = card.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        val dualGlass = glassEnabled && backgroundBlurEnabled
        val targetColor = when {
            dualGlass && overlayOnContent && isLightTheme -> {
                ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(color, Color.WHITE, LIGHT_OVERLAY_GLASS_WHITE_MIX),
                    (OVERLAY_DUAL_GLASS_ALPHA * 255).toInt()
                )
            }
            dualGlass && overlayOnContent -> {
                ColorUtils.setAlphaComponent(color, (OVERLAY_DUAL_GLASS_ALPHA * 255).toInt())
            }
            dualGlass && isLightTheme -> {
                ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(color, Color.WHITE, LIGHT_WINDOW_GLASS_WHITE_MIX),
                    (LIGHT_WINDOW_GLASS_CARD_ALPHA * 255).toInt()
                )
            }
            else -> glassCardColor(color, glassEnabled)
        }
        // A new ColorStateList is not equal to the current one even when the color matches,
        // and setCardBackgroundColor invalidates the card on every result rebind.
        if (card.cardBackgroundColor?.defaultColor != targetColor) {
            card.setCardBackgroundColor(targetColor)
        }
        // Avoid the dark rim painted by system elevation; chrome is styled separately.
        if (card.cardElevation != 0f || card.elevation != 0f || card.translationZ != 0f) {
            card.cardElevation = 0f
            card.elevation = 0f
            card.translationZ = 0f
        }
        if (glassEnabled && clearForeground && card.foreground != null) card.foreground = null
        CardRegionBlur.update(card, glassEnabled && blurRegion, card.radius)
    }

    fun applyGlassEdge(card: MaterialCardView) {
        val strokeWidth = 1f.dp2px.toInt().coerceAtLeast(1)
        val onSurface = DynamicColorUtils.onSurfaceColor.takeIf { it != 0 }
            ?: MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurface)
        val strokeColor = ColorUtils.setAlphaComponent(onSurface, GLASS_EDGE_ALPHA)
        if (card.strokeWidth != strokeWidth) card.strokeWidth = strokeWidth
        if (card.strokeColor != strokeColor) card.strokeColor = strokeColor
    }

    /** Subtle theme-colored edge plus a shadow behind the card. */
    fun applyFixedGlassChrome(card: MaterialCardView, blurRadius: Float = 28f.dp2px) {
        applyGlassEdge(card)
        setCardShadowEnabled(card, true, blurRadius)
    }

    fun setCardShadowEnabled(
        card: MaterialCardView,
        enabled: Boolean,
        blurRadius: Float = 28f.dp2px,
    ) {
        if (enabled) {
            applyCardShadow(card, blurRadius)
            return
        }
        val parent = card.parent as? ViewGroup ?: return
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child is SoftGlassShadowView && child.tag === card) {
                child.visibility = View.GONE
            }
        }
    }

    private fun applyCardShadow(card: MaterialCardView, blurRadius: Float) {
        val parent = card.parent as? ViewGroup ?: return
        parent.clipChildren = false
        parent.clipToPadding = false
        var shadow: SoftGlassShadowView? = null
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child is SoftGlassShadowView && child.tag === card) {
                shadow = child
                break
            }
        }
        shadow?.visibility = View.VISIBLE
        val attached = shadow ?: SoftGlassShadowView(card.context, blurRadius).also { created ->
            created.tag = card
            val params = if (parent is ConstraintLayout) {
                ConstraintLayout.LayoutParams(0, 0)
            } else {
                ViewGroup.MarginLayoutParams(0, 0)
            }
            parent.addView(created, parent.indexOfChild(card), params)
            val listener = ViewTreeObserver.OnPreDrawListener {
                placePanelShadow(card, created)
                true
            }
            card.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) {
                    v.viewTreeObserver.addOnPreDrawListener(listener)
                }

                override fun onViewDetachedFromWindow(v: android.view.View) {
                    if (v.viewTreeObserver.isAlive) {
                        v.viewTreeObserver.removeOnPreDrawListener(listener)
                    }
                }
            })
            if (card.isAttachedToWindow) {
                card.viewTreeObserver.addOnPreDrawListener(listener)
            }
        }
        placePanelShadow(card, attached)
    }

    private fun placePanelShadow(card: MaterialCardView, shadow: SoftGlassShadowView) {
        if (card.visibility != View.VISIBLE) {
            if (shadow.visibility != View.GONE) shadow.visibility = View.GONE
            return
        }
        if (shadow.visibility != View.VISIBLE) shadow.visibility = View.VISIBLE
        val radius = if (card.radius > 0f) card.radius else resolvedCornerRadius(card)
        if (radius > 0f && shadow.cornerRadius != radius) shadow.cornerRadius = radius
        val cardWidth = card.width.takeIf { it > 0 } ?: card.measuredWidth
        val cardHeight = card.height.takeIf { it > 0 } ?: card.measuredHeight
        if (cardWidth <= 0 || cardHeight <= 0 || shadow.cornerRadius <= 0f) return
        val blur = shadow.blurRadius
        val width = (cardWidth + blur * 2f).toInt()
        val height = (cardHeight + blur * 2f).toInt()
        val left = (card.x - blur).toInt()
        val top = (card.y - blur).toInt()
        if (
            shadow.left == left &&
            shadow.top == top &&
            shadow.width == width &&
            shadow.height == height &&
            shadow.alpha == card.alpha
        ) {
            return
        }
        if (shadow.alpha != card.alpha) shadow.alpha = card.alpha
        shadow.layout(left, top, left + width, top + height)
    }

    private val cornerBounds = RectF()

    private fun resolvedCornerRadius(card: MaterialCardView): Float {
        if (card.radius > 0f) return card.radius
        val width = card.width.coerceAtLeast(card.measuredWidth)
        val height = card.height.coerceAtLeast(card.measuredHeight)
        if (width <= 0 || height <= 0) return 0f
        cornerBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        return card.shapeAppearanceModel.topLeftCornerSize.getCornerSize(cornerBounds)
    }
}
