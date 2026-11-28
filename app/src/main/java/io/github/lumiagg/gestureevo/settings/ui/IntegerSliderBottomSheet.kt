package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.BottomSheetIntegerSliderBinding
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import kotlin.math.roundToInt

class IntegerSliderBottomSheet(
    private val activity: Activity,
    private val initialValue: Int,
    private val titleRes: Int,
    private val maxValue: Int,
    private val zeroLabelRes: Int,
    private val valueFormatRes: Int,
    private val stepSize: Int = 1,
    private val exactInput: ExactInput? = null,
    private val onValueChanged: (Int) -> Unit,
) {
    data class ExactInput(val hintRes: Int, val rangeErrorRes: Int)

    private lateinit var bottomSheetDialog: BottomSheetDialog
    private lateinit var binding: BottomSheetIntegerSliderBinding

    fun show() {
        initBottomSheet()
        setupBottomSheetBehavior()
        bottomSheetDialog.show()
    }

    private fun initBottomSheet() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        binding =
            BottomSheetIntegerSliderBinding.inflate(LayoutInflater.from(themedContext))
        bottomSheetDialog =
            BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        bottomSheetDialog.setContentView(binding.root)

        binding.titleText.setText(titleRes)
        bindSlider()
    }

    private fun formatValue(value: Int): String =
        if (value == 0) activity.getString(zeroLabelRes) else activity.getString(valueFormatRes, value)

    private fun sliderPosition(value: Int): Float =
        ((value.toFloat() / stepSize).roundToInt() * stepSize).toFloat()

    private fun bindSlider() = with(binding) {
        var selectedValue = initialValue.coerceIn(0, maxValue)
        var appliedValue = selectedValue
        var trackingTouch = false

        fun applyValue() {
            if (selectedValue == appliedValue) return
            appliedValue = selectedValue
            onValueChanged(selectedValue)
        }

        displayTimeSlider.valueTo = maxValue.toFloat()
        displayTimeSlider.stepSize = stepSize.toFloat()
        displayTimeSlider.value = sliderPosition(selectedValue)
        displayTimeSlider.setLabelFormatter(LabelFormatter { value -> formatValue(value.toInt()) })
        displayTimeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                selectedValue = value.toInt()
                if (exactValueButton.isVisible) exactValueButton.text = formatValue(selectedValue)
                if (!trackingTouch) applyValue()
            }
        }
        displayTimeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                trackingTouch = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                trackingTouch = false
                applyValue()
            }
        })

        val config = exactInput ?: return@with
        exactValueButton.isVisible = true
        exactValueButton.text = formatValue(selectedValue)
        displayTimeSlider.isTickVisible = false
        exactValueButton.setOnClickListener {
            showExactValueDialog(config, selectedValue) { value ->
                selectedValue = value
                exactValueButton.text = formatValue(value)
                displayTimeSlider.value = sliderPosition(value)
                applyValue()
            }
        }
    }

    private fun showExactValueDialog(config: ExactInput, currentValue: Int, onConfirm: (Int) -> Unit) {
        val builder = MaterialAlertDialogBuilder(bottomSheetDialog.context)
        val input = TextInputEditText(builder.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentValue.toString())
            setSelectAllOnFocus(true)
        }
        val field = TextInputLayout(builder.context).apply {
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPaddingRelative(inset, 0, inset, 0)
            hint = activity.getString(config.hintRes)
            addView(input)
        }
        val dialog = builder.setTitle(titleRes)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        input.requestFocus()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().ifBlank { "0" }.toIntOrNull()
            if (value == null || value !in 0..maxValue) {
                field.error = activity.getString(config.rangeErrorRes, maxValue)
                return@setOnClickListener
            }
            onConfirm(value)
            dialog.dismiss()
        }
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
}
