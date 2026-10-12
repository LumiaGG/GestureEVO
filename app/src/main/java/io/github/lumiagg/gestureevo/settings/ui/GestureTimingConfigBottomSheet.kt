package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.LabelFormatter
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.BottomSheetGestureTimingConfigBinding
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureTimingConfig
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils

interface GestureTimingConfigCallback {
    fun onTimingConfigChanged()
}

class GestureTimingConfigBottomSheet(
    private val activity: Activity,
    private val triggerConfig: FreeTriggerConfig,
    private val callback: GestureTimingConfigCallback
) {
    private lateinit var bottomSheetDialog: BottomSheetDialog
    private lateinit var binding: BottomSheetGestureTimingConfigBinding

    fun show() {
        initBottomSheet()
        setupBottomSheetBehavior()
        bindCurrentValues()
        showDialog()
    }

    private fun initBottomSheet() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        binding = BottomSheetGestureTimingConfigBinding.inflate(LayoutInflater.from(themedContext))
        bottomSheetDialog =
            BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        bottomSheetDialog.setContentView(binding.root)
        bottomSheetDialog.setCanceledOnTouchOutside(false)

        val timeLabelFormatter = LabelFormatter { value -> "${value.toInt()} ms" }
        val distanceLabelFormatter = LabelFormatter { value -> "${value.toInt()} dp" }
        binding.longPressSlider.setLabelFormatter(timeLabelFormatter)
        binding.doubleTapSlider.setLabelFormatter(timeLabelFormatter)
        binding.pauseSlider.setLabelFormatter(timeLabelFormatter)
        binding.swipeThresholdSlider.setLabelFormatter(distanceLabelFormatter)
        binding.backThresholdSlider.setLabelFormatter(distanceLabelFormatter)

        binding.resetButton.setOnClickListener {
            resetToDefault()
        }

        binding.saveButton.setOnClickListener {
            saveConfiguration()
        }
    }

    private fun setupBottomSheetBehavior() {
        bottomSheetDialog.setOnShowListener { dialog ->
            val bottomSheet =
                (dialog as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as FrameLayout?

            bottomSheet?.let { sheet ->
                DynamicColorUtils.applyBottomSheetSurface(dialog)
                val windowHeight = activity.window.decorView.height
                val behavior = BottomSheetBehavior.from(sheet)
                sheet.layoutParams.height = windowHeight
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.isDraggable = true
                behavior.skipCollapsed = true
                behavior.isFitToContents = true
                behavior.isHideable = true
            }
        }
    }

    private fun bindCurrentValues() {
        val timingConfig = triggerConfig.gestureTimingConfig
        binding.longPressSlider.value = timingConfig.resolvedLongPressTimeoutMs().toFloat()
        binding.doubleTapSlider.value = timingConfig.resolvedDoubleTapTimeoutMs().toFloat()
        binding.pauseSlider.value = timingConfig.resolvedPauseTimeoutMs().toFloat()
        binding.swipeThresholdSlider.value =
            (timingConfig.swipeThresholdDp.takeIf { it > 0 } ?: GestureTimingConfig.DEFAULT_SWIPE_THRESHOLD_DP).toFloat()
        binding.backThresholdSlider.value =
            (timingConfig.backThresholdDp.takeIf { it > 0 } ?: GestureTimingConfig.DEFAULT_BACK_THRESHOLD_DP).toFloat()
    }

    private fun resetToDefault() {
        binding.longPressSlider.value = GestureTimingConfig.defaultLongPressTimeoutMs().toFloat()
        binding.doubleTapSlider.value = GestureTimingConfig.defaultDoubleTapTimeoutMs().toFloat()
        binding.pauseSlider.value = GestureTimingConfig.DEFAULT_PAUSE_TIMEOUT_MS.toFloat()
        binding.swipeThresholdSlider.value = GestureTimingConfig.DEFAULT_SWIPE_THRESHOLD_DP.toFloat()
        binding.backThresholdSlider.value = GestureTimingConfig.DEFAULT_BACK_THRESHOLD_DP.toFloat()
    }

    private fun saveConfiguration() {
        val longPress = binding.longPressSlider.value.toInt()
        val doubleTap = binding.doubleTapSlider.value.toInt()
        val pause = binding.pauseSlider.value.toInt()
        val swipeThreshold = binding.swipeThresholdSlider.value.toInt()
        val backThreshold = binding.backThresholdSlider.value.toInt()

        triggerConfig.gestureTimingConfig = GestureTimingConfig(
            longPressTimeoutMs = if (longPress == GestureTimingConfig.defaultLongPressTimeoutMs()) 0 else longPress,
            doubleTapTimeoutMs = if (doubleTap == GestureTimingConfig.defaultDoubleTapTimeoutMs()) 0 else doubleTap,
            pauseTimeoutMs = if (pause == GestureTimingConfig.DEFAULT_PAUSE_TIMEOUT_MS) 0 else pause,
            swipeThresholdDp = if (swipeThreshold == GestureTimingConfig.DEFAULT_SWIPE_THRESHOLD_DP) 0 else swipeThreshold,
            backThresholdDp = if (backThreshold == GestureTimingConfig.DEFAULT_BACK_THRESHOLD_DP) 0 else backThreshold
        )

        callback.onTimingConfigChanged()
        bottomSheetDialog.dismiss()
    }

    private fun showDialog() {
        bottomSheetDialog.show()
        bottomSheetDialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}
