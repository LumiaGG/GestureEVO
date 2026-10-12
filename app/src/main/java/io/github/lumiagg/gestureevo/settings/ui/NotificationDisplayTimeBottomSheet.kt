package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.BottomSheetNotificationDisplayTimeBinding
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils

class NotificationDisplayTimeBottomSheet(
    private val activity: Activity,
    private val initialSeconds: Int,
    private val titleRes: Int = R.string.inspire_settings_notification_display_time,
    private val maxValue: Int = 30,
    private val persistLabelRes: Int = R.string.inspire_settings_notification_display_time_persist_short,
    private val valueFormatRes: Int = R.string.inspire_settings_notification_display_time_seconds,
    private val onValueChanged: (Int) -> Unit,
) {
    private lateinit var bottomSheetDialog: BottomSheetDialog
    private lateinit var binding: BottomSheetNotificationDisplayTimeBinding

    fun show() {
        initBottomSheet()
        setupBottomSheetBehavior()
        bindCurrentValue()
        bottomSheetDialog.show()
    }

    private fun initBottomSheet() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        binding =
            BottomSheetNotificationDisplayTimeBinding.inflate(LayoutInflater.from(themedContext))
        bottomSheetDialog =
            BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        bottomSheetDialog.setContentView(binding.root)

        binding.titleText.setText(titleRes)
        binding.displayTimeSlider.valueFrom = 0f
        binding.displayTimeSlider.valueTo = maxValue.toFloat()
        binding.displayTimeSlider.setLabelFormatter(
            LabelFormatter { value ->
                if (value.toInt() == 0) {
                    activity.getString(persistLabelRes)
                } else {
                    activity.getString(valueFormatRes, value.toInt())
                }
            }
        )
        binding.displayTimeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                onValueChanged(slider.value.toInt())
            }
        })
    }

    private fun setupBottomSheetBehavior() {
        bottomSheetDialog.setOnShowListener { dialog ->
            val bottomSheet =
                (dialog as BottomSheetDialog).findViewById<View>(
                    com.google.android.material.R.id.design_bottom_sheet
                ) as FrameLayout?

            bottomSheet?.let { sheet ->
                DynamicColorUtils.applyBottomSheetSurface(dialog)
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                    isFitToContents = true
                    isHideable = true
                }
            }
        }
    }

    private fun bindCurrentValue() {
        binding.displayTimeSlider.value = initialSeconds.coerceIn(0, maxValue).toFloat()
    }
}
