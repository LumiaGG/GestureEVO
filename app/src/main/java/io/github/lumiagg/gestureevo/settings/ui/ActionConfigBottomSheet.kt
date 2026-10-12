package io.github.lumiagg.gestureevo.settings.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActionConfigBottomsheetBinding
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.px2dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

fun createSliderLabelFormatter() =
    LabelFormatter { value -> String.format(Locale.US, "%d", value.toInt()) }

fun createSliderPercentLabelFormatter() = LabelFormatter { value -> "${(value * 100).toInt()}%" }

/**
 * Safely sets slider value, ensuring value stays within valid range.
 * @param slider Slider component
 * @param value Value to set
 * @param valueText Corresponding text display component
 */
fun setSliderValueSafely(slider: Slider, value: Float, valueText: android.widget.TextView) {
    val safeValue = max(slider.valueFrom, min(slider.valueTo, value))
    slider.value = safeValue
    valueText.text = "${safeValue.toInt()}"
}

fun showActionConfigBottomSheet(
    activity: AppCompatActivity,
    tag: String,
    isSubConfig: Boolean,
    coroutineScope: LifecycleCoroutineScope,
    configUpdateCallback: () -> Unit,
    onSliderStateChanged: ((key: String, value: Float, isTouching: Boolean) -> Unit)? = null
) {
    val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
    val bottomSheetDialog =
        BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
    val binding = ActionConfigBottomsheetBinding.inflate(activity.layoutInflater)
    bottomSheetDialog.setContentView(binding.root)

    if (isSubConfig) {
        binding.autoSizeSwitch.setSettingsRowVisibility(View.VISIBLE)
        binding.offsetX.visibility = View.GONE
        binding.offsetY.visibility = View.GONE
    } else {
        binding.autoSizeSwitch.setSettingsRowVisibility(View.GONE)
        binding.offsetX.visibility = View.VISIBLE
        binding.offsetY.visibility = View.VISIBLE
    }

    coroutineScope.launch {
        // Load current configuration
        val config =
            GestureActionDataManager.readActionConfig(activity, tag, isSubConfig = isSubConfig)

        // Create generic slider change listener
        fun createSliderChangeListener(
            updateText: (String) -> Unit,
            onValueChanged: (Float) -> Unit,
            isPercent: Boolean = false
        ) = Slider.OnChangeListener { _, value, _ ->
            if (isPercent) {
                updateText("${(value * 100).toInt()}%")
            } else {
                updateText("${value.toInt()}")
            }

            onValueChanged(value)
            onSliderStateChanged?.invoke("slider", value, true)
        }

        // Create generic touch listener
        fun createSliderTouchListener() = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                onSliderStateChanged?.invoke("slider", slider.value, true)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                onSliderStateChanged?.invoke("slider", slider.value, false)
                coroutineScope.launch {
                    GestureActionDataManager.saveActionConfig(activity, tag, config, isSubConfig)
                    withContext(Dispatchers.Main) {
                        configUpdateCallback()
                    }
                }
            }
        }

        // Configure all sliders
        with(binding) {
            // `subActionFollowConfig` only overrides these sub-wheel style fields:
            // itemDiameter, initRadius, itemWarpOffset, iconPadding, iconCornerRadius.
            fun updateFollowMainStyleAffectedVisibility(followMainStyle: Boolean) {
                if (!isSubConfig) {
                    followMainStyleConfigContainer.visibility = View.VISIBLE
                    return
                }
                followMainStyleConfigContainer.visibility =
                    if (followMainStyle) View.GONE else View.VISIBLE
            }

            itemDiameterSlider.apply {
                // Safely set initial value
                setSliderValueSafely(this, config.itemDiameter.px2dp.toFloat(), itemDiameterValue)
                addOnChangeListener(
                    createSliderChangeListener(
                        updateText = { itemDiameterValue.text = it },
                        onValueChanged = { config.itemDiameter = it.dp2px }
                    ))
                addOnSliderTouchListener(createSliderTouchListener())
                setLabelFormatter(createSliderLabelFormatter())
            }

            initRadiusSlider.apply {
                setSliderValueSafely(this, config.initRadius.px2dp.toFloat(), initRadiusValue)
                addOnChangeListener(
                    createSliderChangeListener(
                        updateText = { initRadiusValue.text = it },
                        onValueChanged = { config.initRadius = it.dp2px }
                    ))
                addOnSliderTouchListener(createSliderTouchListener())
                setLabelFormatter(createSliderLabelFormatter())
            }

            itemWarpOffsetSlider.apply {
                setSliderValueSafely(
                    this,
                    config.itemWarpOffset.px2dp.toFloat(),
                    itemWarpOffsetValue
                )
                addOnChangeListener(
                    createSliderChangeListener(
                        updateText = { itemWarpOffsetValue.text = it },
                        onValueChanged = { config.itemWarpOffset = it.dp2px }
                    ))
                addOnSliderTouchListener(createSliderTouchListener())
                setLabelFormatter(createSliderLabelFormatter())
            }

            iconPaddingSlider.apply {
                setSliderValueSafely(this, config.iconPadding.px2dp.toFloat(), iconPaddingValue)
                addOnChangeListener(
                    createSliderChangeListener(
                        updateText = { iconPaddingValue.text = it },
                        onValueChanged = { config.iconPadding = it.dp2px }
                    ))
                addOnSliderTouchListener(createSliderTouchListener())
                setLabelFormatter(createSliderLabelFormatter())
            }

            iconCornerRadiusSlider.apply {
                setSliderValueSafely(
                    this,
                    config.iconCornerRadius.px2dp.toFloat(),
                    iconCornerRadiusValue
                )
                addOnChangeListener(
                    createSliderChangeListener(
                        updateText = { iconCornerRadiusValue.text = it },
                        onValueChanged = { config.iconCornerRadius = it.dp2px }
                    ))
                addOnSliderTouchListener(createSliderTouchListener())
                setLabelFormatter(createSliderLabelFormatter())
            }


            iconImageRoundPercentSlider.apply {
                setSliderValueSafely(
                    this,
                    config.iconImageRoundPercent,
                    iconImageRoundPercentValue
                )
                addOnChangeListener(
                    createSliderChangeListener(
                        updateText = { iconImageRoundPercentValue.text = it },
                        onValueChanged = { config.iconImageRoundPercent = it.dp2px },
                        isPercent = true
                    )
                )
                addOnSliderTouchListener(createSliderTouchListener())
                setLabelFormatter(createSliderPercentLabelFormatter())
            }

            fun updateRectangleColumnsVisibility(isEnabled: Boolean) {
                rectangleColumnsRow.visibility = if (isEnabled) View.VISIBLE else View.GONE
            }

            transparentIconSwitch.apply {
                isChecked = config.transparentIconBackground
                setOnCheckedChangeListener { _, isChecked ->
                    config.transparentIconBackground = isChecked
                    coroutineScope.launch {
                        GestureActionDataManager.saveActionConfig(
                            activity,
                            tag,
                            config,
                            isSubConfig
                        )
                        withContext(Dispatchers.Main) {
                            configUpdateCallback()
                        }
                    }
                }
            }

            rectangleLayoutSwitch.apply {
                isChecked = config.rectangleLayoutEnabled
                updateRectangleColumnsVisibility(config.rectangleLayoutEnabled)
                setOnCheckedChangeListener { _, isChecked ->
                    config.rectangleLayoutEnabled = isChecked
                    updateRectangleColumnsVisibility(isChecked)
                    coroutineScope.launch {
                        GestureActionDataManager.saveActionConfig(
                            activity,
                            tag,
                            config,
                            isSubConfig
                        )
                        withContext(Dispatchers.Main) {
                            configUpdateCallback()
                        }
                    }
                }
            }

            rectangleColumnsSlider.apply {
                setSliderValueSafely(
                    this,
                    config.rectangleColumns.toFloat(),
                    rectangleColumnsValue
                )
                addOnChangeListener(
                    createSliderChangeListener(
                        updateText = { rectangleColumnsValue.text = it },
                        onValueChanged = { config.rectangleColumns = it.toInt().coerceAtLeast(1) }
                    )
                )
                addOnSliderTouchListener(createSliderTouchListener())
                setLabelFormatter(createSliderLabelFormatter())
            }

            offsetXSlider.apply {
                setSliderValueSafely(this, config.actionOffsetX.px2dp.toFloat(), offsetXValue)
                addOnChangeListener(
                    createSliderChangeListener(
                        updateText = { offsetXValue.text = it },
                        onValueChanged = { config.actionOffsetX = it.dp2px }
                    ))
                addOnSliderTouchListener(createSliderTouchListener())
                setLabelFormatter(createSliderLabelFormatter())
            }

            offsetYSlider.apply {
                setSliderValueSafely(this, config.actionOffsetY.px2dp.toFloat(), offsetYValue)
                addOnChangeListener(
                    createSliderChangeListener(
                        updateText = { offsetYValue.text = it },
                        onValueChanged = { config.actionOffsetY = it.dp2px }
                    ))
                addOnSliderTouchListener(createSliderTouchListener())
                setLabelFormatter(createSliderLabelFormatter())
            }

            autoSizeSwitch.apply {
                isChecked = config.subActionFollowConfig
                updateFollowMainStyleAffectedVisibility(config.subActionFollowConfig)
                setOnCheckedChangeListener { _, isCheck ->
                    config.subActionFollowConfig = isCheck
                    updateFollowMainStyleAffectedVisibility(isCheck)
                    coroutineScope.launch {
                        GestureActionDataManager.saveActionConfig(
                            activity,
                            tag,
                            config,
                            isSubConfig
                        )
                        withContext(Dispatchers.Main) {
                            configUpdateCallback()
                        }
                    }
                }
            }

            // Reset button
            resetButton.setOnClickListener {
                coroutineScope.launch {
                    config.apply {
                        itemDiameter = 55f.dp2px
                        initRadius = 130f.dp2px
                        itemWarpOffset = 10f.dp2px
                        iconPadding = 10f.dp2px
                        iconCornerRadius = 25f.dp2px
                        iconImageRoundPercent = 0f
                        actionOffsetX = 30f.dp2px
                        actionOffsetY = 30f.dp2px
                        transparentIconBackground = false
                        rectangleLayoutEnabled = false
                        rectangleColumns = 4
                    }

                    // Safely update UI display
                    withContext(Dispatchers.Main) {
                        setSliderValueSafely(
                            itemDiameterSlider,
                            config.itemDiameter.px2dp.toFloat(),
                            itemDiameterValue
                        )
                        setSliderValueSafely(
                            initRadiusSlider,
                            config.initRadius.px2dp.toFloat(),
                            initRadiusValue
                        )
                        setSliderValueSafely(
                            itemWarpOffsetSlider,
                            config.itemWarpOffset.px2dp.toFloat(),
                            itemWarpOffsetValue
                        )
                        setSliderValueSafely(
                            iconPaddingSlider,
                            config.iconPadding.px2dp.toFloat(),
                            iconPaddingValue
                        )
                        setSliderValueSafely(
                            iconCornerRadiusSlider,
                            config.iconCornerRadius.px2dp.toFloat(),
                            iconCornerRadiusValue
                        )
                        setSliderValueSafely(
                            iconImageRoundPercentSlider,
                            config.iconImageRoundPercent,
                            iconImageRoundPercentValue
                        )
                        transparentIconSwitch.isChecked = config.transparentIconBackground
                        rectangleLayoutSwitch.isChecked = config.rectangleLayoutEnabled
                        updateRectangleColumnsVisibility(config.rectangleLayoutEnabled)
                        setSliderValueSafely(
                            rectangleColumnsSlider,
                            config.rectangleColumns.toFloat(),
                            rectangleColumnsValue
                        )
                        setSliderValueSafely(
                            offsetXSlider,
                            config.actionOffsetX.px2dp.toFloat(),
                            offsetXValue
                        )
                        setSliderValueSafely(
                            offsetYSlider,
                            config.actionOffsetY.px2dp.toFloat(),
                            offsetYValue
                        )
                        updateFollowMainStyleAffectedVisibility(config.subActionFollowConfig)
                    }

                    // Save configuration
                    GestureActionDataManager.saveActionConfig(activity, tag, config, isSubConfig)
                    withContext(Dispatchers.Main) {
                        configUpdateCallback()
                    }
                }
            }
        }
    }

    // Configure bottom sheet behavior
    bottomSheetDialog.setOnShowListener { dialog ->
        val bottomSheet = (dialog as BottomSheetDialog)
            .findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)

        bottomSheet?.let { sheet ->
            DynamicColorUtils.applyBottomSheetSurface(dialog)
            val windowHeight = activity.window.decorView.height
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.apply {
                sheet.layoutParams.height = windowHeight
                state = BottomSheetBehavior.STATE_EXPANDED
                isDraggable = true
                skipCollapsed = true
                isFitToContents = true
                isHideable = true
            }
        }
    }

    bottomSheetDialog.setCanceledOnTouchOutside(false)
    bottomSheetDialog.show()

    bottomSheetDialog.window?.setLayout(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
}

private fun View.setSettingsRowVisibility(visibility: Int) {
    (parent as? SettingsSwitchRow ?: this).visibility = visibility
}
