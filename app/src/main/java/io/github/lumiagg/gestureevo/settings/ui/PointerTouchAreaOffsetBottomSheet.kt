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
import io.github.lumiagg.gestureevo.databinding.BottomSheetPointerTouchAreaOffsetBinding
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils

class PointerTouchAreaOffsetBottomSheet(
    private val activity: Activity,
    private val initialOffsetDp: Int,
    private val onValueChanged: (Int) -> Unit
) {
    private lateinit var bottomSheetDialog: BottomSheetDialog
    private lateinit var binding: BottomSheetPointerTouchAreaOffsetBinding

    fun show() {
        initBottomSheet()
        setupBottomSheetBehavior()
        bindCurrentValue()
        bottomSheetDialog.show()
    }

    private fun initBottomSheet() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        binding =
            BottomSheetPointerTouchAreaOffsetBinding.inflate(LayoutInflater.from(themedContext))
        bottomSheetDialog =
            BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        bottomSheetDialog.setContentView(binding.root)

        binding.touchAreaOffsetSlider.setLabelFormatter(
            LabelFormatter { value ->
                activity.getString(
                    R.string.inspire_settings_pointer_touch_area_offset_dp,
                    value.toInt()
                )
            }
        )
        binding.touchAreaOffsetSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
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
        binding.touchAreaOffsetSlider.value = initialOffsetDp.coerceIn(-100, 100).toFloat()
    }
}
