package io.github.lumiagg.gestureevo.settings.ui

import androidx.core.graphics.toColorInt

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.alpha
import com.google.android.material.R as MaterialR
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import java.util.Locale

fun showColorPickerDialog(
    activity: AppCompatActivity,
    @ColorInt initialColor: Int,
    enableAlpha: Boolean = true,
    onColorSelected: (color: Int) -> Unit,
    onColorPreview: ((color: Int) -> Unit)? = null
) {
    val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
    val contentView =
        LayoutInflater.from(themedContext).inflate(R.layout.dialog_color_picker_md3, null)

    val colorValueText = contentView.findViewById<TextView>(R.id.colorValueText)
    val colorPreviewView = contentView.findViewById<View>(R.id.colorPreviewView)
    val alphaSlider = contentView.findViewById<Slider>(R.id.alphaSlider)
    val hexInputLayout = contentView.findViewById<TextInputLayout>(R.id.hexInputLayout)
    val hexInputEditText = contentView.findViewById<TextInputEditText>(R.id.hexInputEditText)
    val presetChipGroup = contentView.findViewById<ChipGroup>(R.id.presetChipGroup)

    var selectedColor = if (enableAlpha) initialColor else (initialColor or -0x1000000)
    var baseColorNoAlpha = selectedColor or -0x1000000
    var alpha = selectedColor.alpha

    val alphaLabelFormatter = LabelFormatter { value ->
        String.format(Locale.US, "%d%%", (value / 255f * 100f).toInt())
    }
    alphaSlider.setLabelFormatter(alphaLabelFormatter)
    alphaSlider.value = alpha.toFloat()

    val updatingInput = booleanArrayOf(false)
    val currentColor: () -> Int = { selectedColor }

    fun syncHexInput(@ColorInt color: Int) {
        if (!hexInputEditText.isFocused) {
            updatingInput[0] = true
            hexInputEditText.setText(colorToHex(color, enableAlpha))
            updatingInput[0] = false
        }
    }

    fun refreshColorPreview(emitPreview: Boolean) {
        colorPreviewView.setBackgroundColor(selectedColor)
        colorValueText.text = colorToHex(selectedColor, enableAlpha)
        syncHexInput(selectedColor)
        if (emitPreview) onColorPreview?.invoke(selectedColor)
    }

    fun applyParsedColor(@ColorInt parsedColor: Int, emitPreview: Boolean) {
        baseColorNoAlpha = parsedColor or -0x1000000
        alpha = if (enableAlpha) Color.alpha(parsedColor) else 255
        selectedColor =
            if (enableAlpha) parsedColor
            else Color.argb(255, Color.red(parsedColor), Color.green(parsedColor), Color.blue(parsedColor))
        if (enableAlpha) alphaSlider.value = alpha.toFloat()
        refreshColorPreview(emitPreview = emitPreview)
    }

    val alphaSection = contentView.findViewById<View>(R.id.alphaSection)
    if (enableAlpha) {
        alphaSection.visibility = View.VISIBLE
        alphaSlider.addOnChangeListener { _, value, _ ->
            alpha = value.toInt()
            selectedColor =
                Color.argb(alpha, Color.red(baseColorNoAlpha), Color.green(baseColorNoAlpha), Color.blue(baseColorNoAlpha))
            refreshColorPreview(emitPreview = true)
        }
    } else {
        alpha = 255
        alphaSection.visibility = View.GONE
    }

    hexInputEditText.setOnEditorActionListener { _, _, _ ->
        val parsedColor = parseColorOrNull(hexInputEditText.text?.toString(), enableAlpha)
        if (parsedColor != null) {
            hexInputLayout.error = null
            applyParsedColor(parsedColor, emitPreview = true)
        } else {
            hexInputLayout.error = activity.getString(R.string.color_picker_hex_invalid)
        }
        true
    }

    hexInputEditText.setOnFocusChangeListener { _, hasFocus ->
        if (!hasFocus && !updatingInput[0]) {
            val parsedColor = parseColorOrNull(hexInputEditText.text?.toString(), enableAlpha)
            if (parsedColor == null) {
                hexInputLayout.error = activity.getString(R.string.color_picker_hex_invalid)
                syncHexInput(currentColor())
            } else {
                hexInputLayout.error = null
                applyParsedColor(parsedColor, emitPreview = true)
            }
        }
    }

    val presetColors = buildPresetColors(themedContext, enableAlpha)
    presetColors.forEach { color ->
        val chip = Chip(themedContext).apply {
            text = colorToHex(color, enableAlpha)
            isCheckable = false
            chipBackgroundColor = ColorStateList.valueOf(color)
            chipStrokeWidth = 1f
            chipStrokeColor = ColorStateList.valueOf(
                DynamicColorUtils.outlineColor.takeIf { it != 0 } ?: Color.BLACK
            )
            setTextColor(ensureContrastTextColor(color))
            setOnClickListener {
                baseColorNoAlpha = color or -0x1000000
                alpha = if (enableAlpha) Color.alpha(color) else 255
                selectedColor =
                    if (enableAlpha) color else Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
                if (enableAlpha) alphaSlider.value = alpha.toFloat()
                refreshColorPreview(emitPreview = true)
            }
        }
        presetChipGroup.addView(chip)
    }

    refreshColorPreview(emitPreview = false)

    MaterialAlertDialogBuilder(themedContext)
        .setTitle(R.string.color_picker_title)
        .setView(contentView)
        .setNegativeButton(R.string.color_picker_cancel, null)
        .setPositiveButton(R.string.color_picker_confirm) { _, _ ->
            val parsedColor = parseColorOrNull(hexInputEditText.text?.toString(), enableAlpha)
            if (parsedColor != null) {
                applyParsedColor(parsedColor, emitPreview = false)
            }
            onColorSelected(currentColor())
        }
        .show()
}

private fun buildPresetColors(context: android.content.Context, enableAlpha: Boolean): List<Int> {
    val base = listOf(
        DynamicColorUtils.primaryColor.takeIf { it != 0 } ?: "#6750A4".toColorInt(),
        DynamicColorUtils.secondaryContainerColor.takeIf { it != 0 }
            ?: MaterialColors.getColor(
                context,
                MaterialR.attr.colorSecondaryContainer,
                "#625B71".toColorInt()
            ),
        MaterialColors.getColor(context, MaterialR.attr.colorTertiary, "#7D5260".toColorInt()),
        "#F44336".toColorInt(),
        "#FF9800".toColorInt(),
        "#4CAF50".toColorInt(),
        "#03A9F4".toColorInt(),
        "#3F51B5".toColorInt(),
        "#9C27B0".toColorInt(),
        "#000000".toColorInt(),
        "#FFFFFF".toColorInt()
    )
    if (!enableAlpha) return base
    return base + listOf(
        Color.argb(180, 255, 255, 255),
        Color.argb(180, 0, 0, 0)
    )
}

private fun colorToHex(@ColorInt color: Int, withAlpha: Boolean): String {
    return if (withAlpha) {
        String.format(Locale.US, "#%08X", color)
    } else {
        String.format(Locale.US, "#%06X", 0xFFFFFF and color)
    }
}

private fun parseColorOrNull(rawText: String?, withAlpha: Boolean): Int? {
    if (rawText.isNullOrBlank()) return null
    val text = rawText.trim().removePrefix("#")
    return try {
        when {
            text.length == 8 -> "#$text".toColorInt()
            text.length == 6 -> {
                val parsed = "#$text".toColorInt()
                if (withAlpha) Color.argb(255, Color.red(parsed), Color.green(parsed), Color.blue(parsed))
                else parsed
            }

            else -> null
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

@ColorInt
private fun ensureContrastTextColor(@ColorInt background: Int): Int {
    return if (ColorUtils.calculateLuminance(background) >= 0.5) Color.BLACK else Color.WHITE
}
