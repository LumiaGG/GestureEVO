package io.github.lumiagg.gestureevo.settings.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.core.view.isGone
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px

class SettingsNavRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val valueSlotIndex: Int
    private val swatchView: View?
    private val chevronView: ImageView
    private val titleView: TextView
    private var building = true

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 64f.dp2px.toInt()
        val padH = 14f.dp2px.toInt()
        val padV = 10f.dp2px.toInt()
        setPadding(padH, padV, padH, padV)
        setBackgroundResource(R.drawable.bg_settings_tile)
        isClickable = true
        isFocusable = true

        var title = ""
        var summary = ""
        var trailingIcon = R.drawable.ic_chevron_right
        var showSwatch = false
        context.withStyledAttributes(attrs, R.styleable.SettingsNavRow) {
            title = getText(R.styleable.SettingsNavRow_settingsRowTitle)?.toString().orEmpty()
            summary = getText(R.styleable.SettingsNavRow_settingsRowSummary)?.toString().orEmpty()
            trailingIcon = getResourceId(
                R.styleable.SettingsNavRow_settingsRowTrailingIcon,
                R.drawable.ic_chevron_right,
            )
            showSwatch = getBoolean(R.styleable.SettingsNavRow_settingsRowSwatch, false)
        }

        val textColumn = LinearLayout(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            orientation = VERTICAL
        }
        titleView = labelView(title, isTitle = true)
        textColumn.addView(titleView)
        textColumn.addView(labelView(summary, isTitle = false).apply {
            isGone = summary.isBlank()
        })
        addView(textColumn)

        swatchView = if (showSwatch) {
            View(context).apply {
                layoutParams = LayoutParams(28f.dp2px.toInt(), 28f.dp2px.toInt()).apply {
                    marginStart = 12f.dp2px.toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(DynamicColorUtils.surfaceContainerHighColor)
                    setStroke(
                        1f.dp2px.toInt().coerceAtLeast(1),
                        DynamicColorUtils.outlineColor,
                    )
                }
            }.also { addView(it) }
        } else {
            null
        }
        valueSlotIndex = childCount

        chevronView = ImageView(context).apply {
            layoutParams = LayoutParams(32f.dp2px.toInt(), 32f.dp2px.toInt()).apply {
                marginStart = 12f.dp2px.toInt()
            }
            val inset = 6f.dp2px.toInt()
            setPadding(inset, inset, inset, inset)
            setImageResource(trailingIcon)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(DynamicColorUtils.secondaryContainerColor)
            }
            imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSecondaryContainer,
                    0,
                )
            )
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(chevronView)
        building = false
    }

    fun setTitle(text: CharSequence?) {
        titleView.text = text
    }

    fun setSwatchColor(color: Int) {
        val swatch = swatchView ?: return
        (swatch.background as? GradientDrawable)?.setColor(color)
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (building) {
            super.addView(child, index, params)
            return
        }
        val chevronIndex = indexOfChild(chevronView).takeIf { it >= 0 } ?: childCount
        if (child is TextView) {
            child.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 12f.dp2px.toInt()
            }
            child.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            child.setTextColor(DynamicColorUtils.primaryColor)
            child.maxLines = 2
            child.ellipsize = TextUtils.TruncateAt.END
            child.gravity = Gravity.END
            child.maxWidth = 168f.dp2px.toInt()
            val refresh = { child.isGone = child.text.isNullOrBlank() }
            child.doAfterTextChanged { refresh() }
            refresh()
            super.addView(child, valueSlotIndex.coerceAtMost(chevronIndex), child.layoutParams)
            return
        }
        child.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = 4f.dp2px.toInt()
        }
        super.addView(child, chevronIndex, child.layoutParams)
    }

    private fun labelView(text: String, isTitle: Boolean): TextView {
        return TextView(context).apply {
            this.text = text
            setTextAppearance(
                if (isTitle) {
                    com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
                } else {
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                }
            )
            setTextColor(
                if (isTitle) DynamicColorUtils.onSurfaceColor
                else DynamicColorUtils.onSurfaceVariantColor
            )
            if (!isTitle) {
                val top = 2f.dp2px.toInt()
                setPadding(0, top, 0, 0)
            }
        }
    }
}

class SettingsSwitchRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var switchView: CompoundButton? = null
    private var building = true

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 64f.dp2px.toInt()
        val padH = 14f.dp2px.toInt()
        val padV = 10f.dp2px.toInt()
        setPadding(padH, padV, padH, padV)
        setBackgroundResource(R.drawable.bg_settings_tile)
        isClickable = true
        isFocusable = true

        var title = ""
        var summary = ""
        context.withStyledAttributes(attrs, R.styleable.SettingsSwitchRow) {
            title = getText(R.styleable.SettingsSwitchRow_settingsRowTitle)?.toString().orEmpty()
            summary = getText(R.styleable.SettingsSwitchRow_settingsRowSummary)?.toString().orEmpty()
        }

        val textColumn = LinearLayout(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            orientation = VERTICAL
        }
        textColumn.addView(styledLabel(title, isTitle = true))
        if (summary.isNotBlank()) {
            textColumn.addView(styledLabel(summary, isTitle = false))
        }
        addView(textColumn)
        setOnClickListener { switchView?.performClick() }
        building = false
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (building || child !is CompoundButton) {
            super.addView(child, index, params)
            return
        }
        switchView = child
        val switchParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = 12f.dp2px.toInt()
        }
        super.addView(child, index, switchParams)
    }

    private fun styledLabel(text: String, isTitle: Boolean): TextView {
        return TextView(context).apply {
            this.text = text
            setTextAppearance(
                if (isTitle) {
                    com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
                } else {
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                }
            )
            setTextColor(
                if (isTitle) DynamicColorUtils.onSurfaceColor
                else DynamicColorUtils.onSurfaceVariantColor
            )
            if (!isTitle) setPadding(0, 2f.dp2px.toInt(), 0, 0)
        }
    }
}
