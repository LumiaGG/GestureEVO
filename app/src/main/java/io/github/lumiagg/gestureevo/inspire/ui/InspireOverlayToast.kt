package io.github.lumiagg.gestureevo.inspire.ui

import androidx.core.view.isVisible

import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.lumiagg.gestureevo.databinding.InspireOverlayToastBinding
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.dp2px

internal class InspireOverlayToast(
    private val host: ConstraintLayout,
) {
    private var binding: InspireOverlayToastBinding? = null
    private val hideRunnable = Runnable { hide() }

    fun show(text: CharSequence, durationMs: Long = DURATION_SHORT_MS) {
        val toast = ensureToast()
        toast.message.text = text
        applyTheme(toast)
        layoutToast(toast)
        toast.root.removeCallbacks(hideRunnable)
        toast.root.bringToFront()
        val alreadyVisible = toast.root.isVisible && toast.root.alpha >= 0.95f
        if (!alreadyVisible) {
            toast.root.animate().cancel()
            toast.root.alpha = 0f
            toast.root.translationY = -ENTER_OFFSET_DP.dp2px
            toast.root.visibility = View.VISIBLE
            toast.root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction(null)
                .start()
        }
        toast.root.announceForAccessibility(text)
        toast.root.postDelayed(hideRunnable, durationMs)
    }

    fun hide(immediate: Boolean = false) {
        val toast = binding ?: return
        toast.root.removeCallbacks(hideRunnable)
        toast.root.animate().cancel()
        if (immediate || toast.root.visibility != View.VISIBLE) {
            toast.root.alpha = 0f
            toast.root.translationY = 0f
            toast.root.visibility = View.GONE
            return
        }
        toast.root.animate()
            .alpha(0f)
            .translationY(-ENTER_OFFSET_DP.dp2px)
            .setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                toast.root.visibility = View.GONE
                toast.root.translationY = 0f
            }
            .start()
    }

    fun refreshTheme() {
        binding?.let(::applyTheme)
    }

    private fun ensureToast(): InspireOverlayToastBinding {
        binding?.let { return it }
        val inflated = InspireOverlayToastBinding.inflate(
            LayoutInflater.from(host.context),
            host,
            false
        )
        inflated.root.isClickable = false
        inflated.root.isLongClickable = false
        inflated.root.isFocusable = false
        inflated.root.isFocusableInTouchMode = false
        host.addView(
            inflated.root,
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                constrainedWidth = true
                horizontalBias = 0.5f
            }
        )
        binding = inflated
        return inflated
    }

    private fun layoutToast(toast: InspireOverlayToastBinding) {
        val params = toast.root.layoutParams as ConstraintLayout.LayoutParams
        val horizontal = HORIZONTAL_MARGIN_DP.dp2px.toInt()
        val hostWidth = host.width.takeIf { it > 0 } ?: host.resources.displayMetrics.widthPixels
        val maxWidth = (hostWidth - horizontal * 2).coerceAtLeast(0)
        val horizontalPadding = toast.message.paddingStart + toast.message.paddingEnd
        toast.message.maxWidth = (maxWidth - horizontalPadding).coerceAtLeast(0)
        params.marginStart = horizontal
        params.marginEnd = horizontal
        params.topMargin = TOP_MARGIN_DP.dp2px.toInt() + statusBarInset()
        params.bottomMargin = 0
        params.matchConstraintMaxWidth = maxWidth
        toast.root.layoutParams = params
    }

    private fun applyTheme(toast: InspireOverlayToastBinding) {
        val surface = DynamicColorUtils.surfaceContainerHighColor
        val onSurface = DynamicColorUtils.onSurfaceColor
        if (surface != 0) {
            toast.root.setCardBackgroundColor(surface)
        }
        if (onSurface != 0) {
            toast.message.setTextColor(onSurface)
        }
    }

    private fun statusBarInset(): Int {
        val inset = ViewCompat.getRootWindowInsets(host)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())
            ?.top
            ?: 0
        if (inset > 0) return inset
        return ScreenUtil.getStatusBarHeight(host.context).coerceAtLeast(0)
    }

    private companion object {
        const val DURATION_SHORT_MS = 2000L
        private const val ANIM_MS = 180L
        private const val ENTER_OFFSET_DP = 12f
        private const val HORIZONTAL_MARGIN_DP = 24
        private const val TOP_MARGIN_DP = 16
    }
}
