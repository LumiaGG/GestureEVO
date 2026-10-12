package io.github.lumiagg.gestureevo.search

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityQuickSearchBinding
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.math.abs

class QuickSearchPositionManager(
    private val activity: QuickSearchActivity,
    private val binding: ActivityQuickSearchBinding,
    private val initialBias: Float
) {
    companion object {
        const val KEY_QUICK_SEARCH_HORIZONTAL_BIAS = "key_quick_search_horizontal_bias"
        const val BIAS_FOLLOW_TRIGGER = -1.0f
        const val BIAS_LEFT = 0.0f
        const val BIAS_CENTER = 0.5f
        const val BIAS_RIGHT = 1.0f

        fun getBiasKeyForSide(triggerSide: String?): String {
            return when (triggerSide) {
                TriggerSide.LEFT -> "${KEY_QUICK_SEARCH_HORIZONTAL_BIAS}_left"
                TriggerSide.RIGHT -> "${KEY_QUICK_SEARCH_HORIZONTAL_BIAS}_right"
                else -> "${KEY_QUICK_SEARCH_HORIZONTAL_BIAS}_default"
            }
        }

        suspend fun getInitialBias(activity: QuickSearchActivity): Float {
            return getSavedOrResolvedBias(activity, activity.triggerSide)
        }

        suspend fun getSavedOrResolvedBias(activity: QuickSearchActivity, triggerSide: String?): Float {
            val key = getBiasKeyForSide(triggerSide)
            val savedBias = activity.settingsStore.readKey(key, BIAS_FOLLOW_TRIGGER)
            return resolveBias(savedBias, triggerSide)
        }

        fun resolveBias(savedBias: Float, triggerSide: String?): Float {
            return if (savedBias in 0f..1f) {
                savedBias
            } else {
                when (triggerSide) {
                    TriggerSide.LEFT -> BIAS_LEFT
                    TriggerSide.RIGHT -> BIAS_RIGHT
                    else -> BIAS_CENTER
                }
            }
        }
    }

    private val dragBarContainer: View? by lazy {
        binding.root.findViewById(R.id.drag_bar_container)
    }

    private var currentBias = initialBias
    private var biasAnimator: ValueAnimator? = null
    private var pendingBiasRead: Job? = null
    private var isDragging = false
    private var dragStartBias = initialBias
    private var dragStartX = 0f
    private var isWideScreen = false

    fun setup() {
        applyInitialBias()
        val screenWidth = activity.resources.displayMetrics.widthPixels
        updateWideScreenState(screenWidth, force = true)

        binding.root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0) {
                updateWideScreenState(width)
            }
        }

        setupDragBar()
    }

    fun applyInitialBias() {
        applyBias(initialBias)
    }

    fun onTriggerSideChanged() {
        pendingBiasRead?.cancel()
        if (!isWideScreen) return
        val side = activity.triggerSide
        pendingBiasRead = activity.lifecycleScope.launch(Dispatchers.Main.immediate) {
            val targetBias = getSavedOrResolvedBias(activity, side)
            if (isWideScreen && activity.triggerSide == side) animateToBias(targetBias)
        }
    }

    private fun updateWideScreenState(containerWidth: Int, force: Boolean = false) {
        val maxCardWidthPx = activity.resources.getDimensionPixelSize(R.dimen.quick_search_card_max_width)
        val horizontalGapPx = activity.resources.getDimensionPixelSize(R.dimen.quick_search_card_gap) * 2
        // Sufficient motion clearance: card width (440dp) + side margins (16dp) + at least 140dp travel distance (~596dp-600dp total)
        val minFreeSpacePx = (activity.resources.displayMetrics.density * 140).toInt()
        val hasSufficientSpace = containerWidth >= (maxCardWidthPx + horizontalGapPx + minFreeSpacePx)
        val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val wide = isLandscape || hasSufficientSpace

        if (force || isWideScreen != wide) {
            isWideScreen = wide
            dragBarContainer?.isVisible = wide
            updateCardMaxWidth(wide, maxCardWidthPx)
            if (!wide) {
                // In narrow mode (portrait phone), reset bias to center so it occupies full width
                applyBias(BIAS_CENTER)
            } else {
                applyBias(currentBias)
            }
        }
    }

    private fun updateCardMaxWidth(wide: Boolean, maxCardWidthPx: Int) {
        val targetMaxWidth = if (wide) maxCardWidthPx else 0
        (binding.topSearchCard.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
            if (params.matchConstraintMaxWidth != targetMaxWidth) {
                params.matchConstraintMaxWidth = targetMaxWidth
                binding.topSearchCard.layoutParams = params
            }
        }
        (binding.bottomEngineCard.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
            if (params.matchConstraintMaxWidth != targetMaxWidth) {
                params.matchConstraintMaxWidth = targetMaxWidth
                binding.bottomEngineCard.layoutParams = params
            }
        }
    }

    private fun applyBias(bias: Float) {
        currentBias = bias.coerceIn(0f, 1f)
        (binding.topSearchCard.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
            if (params.horizontalBias != currentBias) {
                params.horizontalBias = currentBias
                binding.topSearchCard.layoutParams = params
            }
        }
        (binding.bottomEngineCard.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
            if (params.horizontalBias != currentBias) {
                params.horizontalBias = currentBias
                binding.bottomEngineCard.layoutParams = params
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragBar() {
        val container = dragBarContainer ?: return

        val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                biasAnimator?.cancel()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isWideScreen) return false
                cyclePosition()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isWideScreen) return false
                resetToFollowTrigger()
                return true
            }
        })

        container.setOnTouchListener { v, event ->
            if (!isWideScreen) return@setOnTouchListener false

            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pendingBiasRead?.cancel()
                    isDragging = false
                    dragStartX = event.rawX
                    dragStartBias = currentBias
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - dragStartX
                    val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
                    if (!isDragging && abs(deltaX) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val cardWidth = binding.topSearchCard.width
                        val parentWidth = binding.root.width
                        val margins = (binding.topSearchCard.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                            it.leftMargin + it.rightMargin
                        } ?: 0
                        val freeSpace = (parentWidth - cardWidth - margins).toFloat()
                        if (freeSpace > 0) {
                            val newBias = (dragStartBias + deltaX / freeSpace).coerceIn(0f, 1f)
                            applyBias(newBias)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        val snapBias = getNearestBias(currentBias)
                        animateToBias(snapBias) {
                            saveBiasSetting(snapBias)
                            VibratorUtils.vibrator(activity, false)
                            showPositionToast(snapBias)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun getNearestBias(bias: Float): Float {
        return when {
            bias < 0.25f -> BIAS_LEFT
            bias > 0.75f -> BIAS_RIGHT
            else -> BIAS_CENTER
        }
    }

    private fun cyclePosition() {
        val nextBias = when {
            currentBias < 0.25f -> BIAS_CENTER
            currentBias < 0.75f -> BIAS_RIGHT
            else -> BIAS_LEFT
        }
        animateToBias(nextBias) {
            saveBiasSetting(nextBias)
            VibratorUtils.vibrator(activity, false)
            showPositionToast(nextBias)
        }
    }

    private fun resetToFollowTrigger() {
        val defaultBias = when (activity.triggerSide) {
            TriggerSide.LEFT -> BIAS_LEFT
            TriggerSide.RIGHT -> BIAS_RIGHT
            else -> BIAS_CENTER
        }
        animateToBias(defaultBias) {
            saveBiasSetting(BIAS_FOLLOW_TRIGGER)
            VibratorUtils.vibrator(activity, true)
            ToastHelper.showInUIThread(activity, R.string.quick_search_position_follow_trigger)
        }
    }

    private fun animateToBias(targetBias: Float, onEnd: (() -> Unit)? = null) {
        biasAnimator?.cancel()
        if (abs(currentBias - targetBias) < 0.001f) {
            applyBias(targetBias)
            onEnd?.invoke()
            return
        }
        biasAnimator = ValueAnimator.ofFloat(currentBias, targetBias).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                applyBias(anim.animatedValue as Float)
            }
            doOnEnd {
                onEnd?.invoke()
            }
            start()
        }
    }

    private fun saveBiasSetting(bias: Float) {
        val key = getBiasKeyForSide(activity.triggerSide)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            activity.settingsStore.saveKey(key, bias)
        }
    }

    private fun showPositionToast(bias: Float) {
        val resId = when {
            bias < 0.25f -> R.string.quick_search_position_left
            bias > 0.75f -> R.string.quick_search_position_right
            else -> R.string.quick_search_position_center
        }
        ToastHelper.showInUIThread(activity, resId)
    }
}
