package io.github.lumiagg.gestureevo.search

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.addListener
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnAttach
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityQuickSearchBinding
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import kotlin.math.roundToInt

internal class QuickSearchMotionController(
    private val activity: QuickSearchActivity,
    private val binding: ActivityQuickSearchBinding,
    private val window: Window,
    private val keyboardFollowAnimationEnabled: Boolean,
    private val bottomInputLayoutEnabled: Boolean,
    private val showBlur: Boolean,
    private val cardBackgroundBlurEnabled: Boolean,
    private val keepBackgroundBlurOnEnter: Boolean,
    private val searchBackgroundColor: Int
) {
    private val enterInterpolator = FastOutSlowInInterpolator()
    private val exitInterpolator = FastOutLinearInInterpolator()
    private val scrimInterpolator = LinearInterpolator()
    private val motionStrategy: LayoutMotionStrategy =
        if (bottomInputLayoutEnabled) BottomInputLayoutMotionStrategy()
        else TopInputLayoutMotionStrategy()
    private val motionTimeline = motionStrategy.createMotionTimeline()

    private val hiddenTopOffset: Float
        get() = motionTimeline.hiddenTopOffset
    private val hiddenBottomOffset: Float
        get() = motionTimeline.hiddenBottomOffset

    val keyboardInsetPixels: Int
        get() = if (keyboardFollowAnimationEnabled) keyboardInset.roundToInt() else 0

    private var visibilityProgress = 0f
    private var keyboardInset = 0f
    private var visibilityAnimator: ValueAnimator? = null
    private var pendingShowKeyboard = false
    private var keyboardRequestPosted = false
    private val keyboardRequestRunnable = Runnable {
        keyboardRequestPosted = false
        requestKeyboard()
    }
    private var lastAppliedBlurRadius = -1
    private var onExitEnd: (() -> Unit)? = null
    private var isEnteringVisibilityAnimation = true
    private var isImeAnimationRunning = false
    private var awaitingImeAnimationAfterShow = false
    private var deferredKeyboardInset: Float? = null
    private var baseBottomPadding = 0
    private val initialRootPaddingLeft = binding.root.paddingLeft
    private val initialRootPaddingTop = binding.root.paddingTop
    private val initialRootPaddingRight = binding.root.paddingRight
    private val initialRootPaddingBottom = binding.root.paddingBottom
    private val cardBackgroundColor: Int
        get() = DynamicColorUtils.surfaceContainerHighColor
    private val imeAnimationFallbackRunnable = Runnable {
        isImeAnimationRunning = false
        awaitingImeAnimationAfterShow = false
        deferredKeyboardInset?.let(::applyKeyboardInset)
        deferredKeyboardInset = null
    }

    fun bind() {
        binding.searchContainer.setBackgroundColor(Color.TRANSPARENT)
        applyStaticCardBackgrounds()
        motionStrategy.captureBaseLayout()
        baseBottomPadding = binding.root.paddingBottom
        // Intercept IME insets only when follow animation is enabled; otherwise allow native system adjustResize.
        if (keyboardFollowAnimationEnabled) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
                applySystemBarInsets(insets)
                updateKeyboardState(insets, fromAnimation = false)
                insets
            }
            ViewCompat.setWindowInsetsAnimationCallback(
                binding.root,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                            isImeAnimationRunning = true
                            awaitingImeAnimationAfterShow = false
                            binding.root.removeCallbacks(imeAnimationFallbackRunnable)
                        }
                    }

                    override fun onProgress(
                        insets: WindowInsetsCompat,
                        runningAnimations: MutableList<WindowInsetsAnimationCompat>
                    ): WindowInsetsCompat {
                        val hasImeAnimation =
                            runningAnimations.any { it.typeMask and WindowInsetsCompat.Type.ime() != 0 }
                        if (hasImeAnimation) {
                            isImeAnimationRunning = true
                            awaitingImeAnimationAfterShow = false
                            binding.root.removeCallbacks(imeAnimationFallbackRunnable)
                            applySystemBarInsets(insets)
                            updateKeyboardState(insets, fromAnimation = true)
                        }
                        return insets
                    }

                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                            isImeAnimationRunning = false
                            awaitingImeAnimationAfterShow = false
                            binding.root.removeCallbacks(imeAnimationFallbackRunnable)
                        }
                        ViewCompat.getRootWindowInsets(binding.root)?.let {
                            applySystemBarInsets(it)
                            updateKeyboardState(it, fromAnimation = false)
                        }
                    }
                }
            )
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.searchInput.doOnAttach { showKeyboardIfPossible() }
        render()
    }

    fun show() {
        pendingShowKeyboard = true
        onExitEnd = null
        animateVisibility(
            target = 1f,
            duration = motionTimeline.enterDurationMillis,
            entering = true
        )
        showKeyboardIfPossible()
    }

    fun hide(onEnd: () -> Unit) {
        pendingShowKeyboard = false
        binding.searchInput.removeCallbacks(keyboardRequestRunnable)
        keyboardRequestPosted = false
        onExitEnd = onEnd
        awaitingImeAnimationAfterShow = false
        isImeAnimationRunning = false
        deferredKeyboardInset = null
        binding.root.removeCallbacks(imeAnimationFallbackRunnable)
        WindowCompat.getInsetsController(window, binding.root).hide(WindowInsetsCompat.Type.ime())
        animateVisibility(
            target = 0f,
            duration = motionTimeline.exitDurationMillis,
            entering = false
        )
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            showKeyboardIfPossible()
        }
    }

    fun release() {
        pendingShowKeyboard = false
        binding.searchInput.removeCallbacks(keyboardRequestRunnable)
        keyboardRequestPosted = false
        visibilityAnimator?.cancel()
        binding.root.removeCallbacks(imeAnimationFallbackRunnable)
        if (keyboardFollowAnimationEnabled) {
            ViewCompat.setWindowInsetsAnimationCallback(binding.root, null)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, null)
        applyBlurProgress(0f)
    }

    private fun animateVisibility(target: Float, duration: Long, entering: Boolean) {
        visibilityAnimator?.cancel()
        isEnteringVisibilityAnimation = entering
        visibilityAnimator = ValueAnimator.ofFloat(visibilityProgress, target).apply {
            this.duration = duration
            interpolator = if (entering) enterInterpolator else exitInterpolator
            addUpdateListener { animator ->
                visibilityProgress = animator.animatedValue as Float
                render()
            }
            doOnAnimationEnd {
                if (target == 0f) {
                    onExitEnd?.invoke()
                    onExitEnd = null
                }
            }
            start()
        }
    }

    private fun updateKeyboardState(insets: WindowInsetsCompat, fromAnimation: Boolean) {
        val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        baseBottomPadding = maxOf(baseBottomPadding, binding.root.paddingBottom)
        val targetInset = (imeBottom - baseBottomPadding).coerceAtLeast(0).toFloat()
        if (fromAnimation) {
            deferredKeyboardInset = null
            applyKeyboardInset(targetInset)
            return
        }
        if (isImeAnimationRunning) {
            deferredKeyboardInset = targetInset
            return
        }
        if (awaitingImeAnimationAfterShow && targetInset > keyboardInset) {
            deferredKeyboardInset = targetInset
            return
        }
        deferredKeyboardInset = null
        applyKeyboardInset(targetInset)
    }

    private fun applyKeyboardInset(targetInset: Float) {
        if (keyboardInset == targetInset) return
        keyboardInset = targetInset
        render()
        activity.onKeyboardInsetChanged()
    }

    private fun applySystemBarInsets(insets: WindowInsetsCompat) {
        val systemBars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val left = initialRootPaddingLeft + systemBars.left
        val top = initialRootPaddingTop + systemBars.top
        val right = initialRootPaddingRight + systemBars.right
        val bottom = initialRootPaddingBottom + systemBars.bottom
        if (
            binding.root.paddingLeft != left ||
            binding.root.paddingTop != top ||
            binding.root.paddingRight != right ||
            binding.root.paddingBottom != bottom
        ) {
            binding.root.setPadding(left, top, right, bottom)
        }
    }

    private fun showKeyboardIfPossible() {
        if (keyboardRequestPosted) return
        if (!pendingShowKeyboard || activity.isFinishing || activity.isDestroyed) return
        if (!binding.searchInput.isAttachedToWindow) return
        if (!window.decorView.hasWindowFocus()) return
        // Let ViewRoot finish window-focus dispatch and establish the input connection.
        keyboardRequestPosted = binding.searchInput.post(keyboardRequestRunnable)
    }

    private fun requestKeyboard() {
        if (!pendingShowKeyboard || activity.isFinishing || activity.isDestroyed) return
        if (!binding.searchInput.isAttachedToWindow || !window.decorView.hasWindowFocus()) return
        if (!binding.searchInput.isFocused) {
            binding.searchInput.requestFocus()
        }
        awaitingImeAnimationAfterShow = true
        isImeAnimationRunning = false
        deferredKeyboardInset = null
        binding.root.removeCallbacks(imeAnimationFallbackRunnable)
        binding.root.postDelayed(imeAnimationFallbackRunnable, 220L)
        pendingShowKeyboard = false
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val shown =
            imm?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT) == true
        if (!shown) {
            WindowCompat.getInsetsController(window, binding.root)
                .show(WindowInsetsCompat.Type.ime())
        }
        activity.onKeyboardRequestDispatched()
    }

    private fun render() {
        val scrimProgress = segment(visibilityProgress, motionTimeline.scrimRange)
        val topCardProgress = segment(visibilityProgress, motionTimeline.topCardRange)
        val bottomCardProgress = segment(visibilityProgress, motionTimeline.bottomCardRange)
        val topCardAlphaProgress =
            motionStrategy.transformAlphaProgress(topCardProgress, isEnteringVisibilityAnimation)
        val bottomCardAlphaProgress =
            motionStrategy.transformAlphaProgress(bottomCardProgress, isEnteringVisibilityAnimation)
        val backgroundColor = ColorUtils.blendARGB(
            Color.TRANSPARENT,
            searchBackgroundColor,
            scrimInterpolator.getInterpolation(scrimProgress)
        )

        binding.searchContainer.setBackgroundColor(backgroundColor)
        // Keep status bar transparent in edge-to-edge mode to avoid tint stacking issues obscuring blur.
        window.statusBarColor = if (keyboardFollowAnimationEnabled) {
            Color.TRANSPARENT
        } else {
            backgroundColor
        }

        motionStrategy.applyKeyboardLinkedLayout(
            if (keyboardFollowAnimationEnabled) keyboardInset.roundToInt() else 0
        )

        if (bottomInputLayoutEnabled) {
            binding.topSearchCard.alpha = topCardAlphaProgress
            binding.topSearchCard.translationY = lerp(hiddenTopOffset, 0f, topCardProgress)
            binding.bottomEngineCard.alpha = 1f
            binding.bottomEngineCard.translationY = 0f
        } else {
            binding.topSearchCard.alpha = topCardAlphaProgress
            binding.topSearchCard.translationY = lerp(hiddenTopOffset, 0f, topCardProgress)

            binding.bottomEngineCard.alpha = bottomCardAlphaProgress
            binding.bottomEngineCard.translationY = lerp(hiddenBottomOffset, 0f, bottomCardProgress)
        }

        if (showBlur) {
            val progress = if (keepBackgroundBlurOnEnter && isEnteringVisibilityAnimation) {
                1f
            } else {
                visibilityProgress
            }
            applyBlurProgress(progress)
        }
    }

    private fun applyStaticCardBackgrounds() {
        applyCardGlassStyle(
            card = binding.topSearchCard,
            color = cardBackgroundColor,
        )
        // Install the shadow before the first animation frame so it follows the card's fade.
        GlassEffectUtils.applyFixedGlassChrome(binding.topSearchCard)
        if (!bottomInputLayoutEnabled) {
            applyCardGlassStyle(
                card = binding.bottomEngineCard,
                color = cardBackgroundColor,
            )
            GlassEffectUtils.applyFixedGlassChrome(binding.bottomEngineCard)
        }
    }

    private fun applyCardGlassStyle(card: MaterialCardView, color: Int) {
        GlassEffectUtils.applyCardStyle(
            card = card,
            color = color,
            glassEnabled = cardBackgroundBlurEnabled,
            backgroundBlurEnabled = showBlur,
            blurRegion = GlassEffectUtils.usesCardRegionBlur(showBlur, cardBackgroundBlurEnabled),
        )
    }

    private fun updateBottomMargin(view: View, bottomMargin: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.bottomMargin == bottomMargin) return
        params.bottomMargin = bottomMargin
        view.layoutParams = params
    }

    private fun applyBlurProgress(progress: Float) {
        val targetProgress = progress.coerceIn(0f, 1f)
        val targetProgressKey = (targetProgress * 1000).roundToInt()
        if (targetProgressKey == lastAppliedBlurRadius) return
        GlassEffectUtils.applyWindowBlurProgress(window, targetProgress)
        lastAppliedBlurRadius = targetProgressKey
    }

    private fun segment(value: Float, range: ProgressRange): Float {
        if (value <= range.start) return 0f
        if (value >= range.end) return 1f
        return (value - range.start) / (range.end - range.start)
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    private fun ValueAnimator.doOnAnimationEnd(block: () -> Unit) {
        addListener(
            onEnd = {
                visibilityAnimator = null
                block()
            },
            onCancel = {
                visibilityAnimator = null
            }
        )
    }

    private interface LayoutMotionStrategy {
        fun createMotionTimeline(): MotionTimeline
        fun captureBaseLayout()
        fun applyKeyboardLinkedLayout(keyboardInset: Int)
        fun transformAlphaProgress(progress: Float, entering: Boolean): Float
    }

    private abstract inner class BaseLayoutMotionStrategy : LayoutMotionStrategy {
        protected var baseBottomEngineBottomMargin = 0

        override fun captureBaseLayout() {
            val bottomParams =
                binding.bottomEngineCard.layoutParams as? ViewGroup.MarginLayoutParams
            baseBottomEngineBottomMargin = bottomParams?.bottomMargin ?: 0
        }

        override fun applyKeyboardLinkedLayout(keyboardInset: Int) {
            updateBottomMargin(
                binding.bottomEngineCard,
                baseBottomEngineBottomMargin + keyboardInset
            )
        }

        override fun transformAlphaProgress(progress: Float, entering: Boolean): Float {
            return progress
        }
    }

    private inner class TopInputLayoutMotionStrategy : BaseLayoutMotionStrategy() {
        override fun createMotionTimeline(): MotionTimeline {
            val topOffset =
                activity.resources.getDimension(R.dimen.quick_search_motion_offset_medium)
            val bottomOffset =
                activity.resources.getDimension(R.dimen.quick_search_motion_offset_large)
            return MotionTimeline(
                hiddenTopOffset = -topOffset,
                hiddenBottomOffset = bottomOffset,
                enterDurationMillis = 420L,
                exitDurationMillis = 240L,
                scrimRange = ProgressRange(0f, 0.94f),
                topCardRange = ProgressRange(0f, 0.96f),
                bottomCardRange = ProgressRange(0.16f, 1f)
            )
        }
    }

    private inner class BottomInputLayoutMotionStrategy : LayoutMotionStrategy {
        private var baseTopCardBottomMargin = 0

        override fun createMotionTimeline(): MotionTimeline {
            val bottomOffset =
                activity.resources.getDimension(R.dimen.quick_search_motion_offset_large)
            return MotionTimeline(
                hiddenTopOffset = bottomOffset,
                hiddenBottomOffset = bottomOffset,
                enterDurationMillis = 440L,
                exitDurationMillis = 240L,
                scrimRange = ProgressRange(0f, 0.96f),
                topCardRange = ProgressRange(0f, 0.98f),
                bottomCardRange = ProgressRange(0.12f, 1f)
            )
        }

        override fun captureBaseLayout() {
            val params = binding.topSearchCard.layoutParams as? ViewGroup.MarginLayoutParams
            baseTopCardBottomMargin = params?.bottomMargin ?: 0
        }

        override fun applyKeyboardLinkedLayout(keyboardInset: Int) {
            updateBottomMargin(
                binding.topSearchCard,
                baseTopCardBottomMargin + keyboardInset
            )
        }

        override fun transformAlphaProgress(progress: Float, entering: Boolean): Float {
            if (entering) return progress
            // Exit a bit faster in bottom-input mode while preserving the movement timeline.
            return progress * progress * progress
        }
    }

    private data class MotionTimeline(
        val hiddenTopOffset: Float,
        val hiddenBottomOffset: Float,
        val enterDurationMillis: Long,
        val exitDurationMillis: Long,
        val scrimRange: ProgressRange,
        val topCardRange: ProgressRange,
        val bottomCardRange: ProgressRange
    )

    private data class ProgressRange(
        val start: Float,
        val end: Float
    )
}
