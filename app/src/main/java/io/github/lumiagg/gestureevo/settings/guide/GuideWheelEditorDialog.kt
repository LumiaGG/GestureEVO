package io.github.lumiagg.gestureevo.settings.guide

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px

internal fun showGuideWheelEditor(
    activity: AppCompatActivity,
    configProvider: () -> QuickSetupConfig = { QuickSetupConfig.default() },
    onEditItem: (Int) -> Unit = {},
    onRemoveItem: (Int) -> Unit = {},
    onAddItem: () -> Unit = {},
    onDismiss: () -> Unit
): Dialog {
    val context = DynamicColorUtils.dynamicThemedContext(activity)
    val dialog = Dialog(context, R.style.Theme_GestureEVO)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

    val root = FrameLayout(context).apply {
        setBackgroundColor(android.graphics.Color.argb(120, 0, 0, 0))
    }

    // --- Top App Bar Card ---
    val topCard = MaterialCardView(context).apply {
        radius = 16f.dp2px
        cardElevation = 6f.dp2px
        setCardBackgroundColor(DynamicColorUtils.surfaceContainerColor)
        strokeWidth = 0
    }
    val topCardParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.TOP
    ).apply {
        setMargins(16f.dp2px.toInt(), 12f.dp2px.toInt(), 16f.dp2px.toInt(), 0)
    }
    root.addView(topCard, topCardParams)

    val topContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = 16f.dp2px.toInt()
        setPadding(pad, pad, pad, pad)
    }
    topCard.addView(topContent)

    val titleRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    topContent.addView(titleRow)

    val titleView = TextView(context).apply {
        setText(R.string.quick_setup_edit_wheel)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        setTextColor(DynamicColorUtils.onSurfaceColor)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    titleRow.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

    val doneButton = AppCompatImageButton(context).apply {
        setImageResource(R.drawable.baseline_check_24)
        imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
        contentDescription = context.getString(R.string.quick_practice_done)
        TooltipCompat.setTooltipText(this, contentDescription)
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
        setBackgroundResource(background.resourceId)
        val pad = 8f.dp2px.toInt()
        setPadding(pad, pad, pad, pad)
        setOnClickListener { dialog.dismiss() }
    }
    val btnSize = 40f.dp2px.toInt()
    titleRow.addView(doneButton, LinearLayout.LayoutParams(btnSize, btnSize))

    val hintView = TextView(context).apply {
        setText(R.string.quick_setup_edit_hint)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(DynamicColorUtils.onSurfaceVariantColor)
        setPadding(0, 4f.dp2px.toInt(), 0, 0)
    }
    topContent.addView(hintView)

    // --- Bottom Palette Card ---
    val bottomCard = MaterialCardView(context).apply {
        radius = 20f.dp2px
        cardElevation = 6f.dp2px
        setCardBackgroundColor(DynamicColorUtils.surfaceContainerColor)
        strokeWidth = 0
    }
    val bottomCardParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM
    ).apply {
        setMargins(16f.dp2px.toInt(), 0, 16f.dp2px.toInt(), 16f.dp2px.toInt())
    }
    root.addView(bottomCard, bottomCardParams)

    val bottomContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = 14f.dp2px.toInt()
        setPadding(pad, pad, pad, pad)
    }
    bottomCard.addView(bottomContent)

    val paletteHeader = TextView(context).apply {
        setText(R.string.quick_setup_wheel_card_title)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(DynamicColorUtils.primaryColor)
        setPadding(4f.dp2px.toInt(), 0, 0, 8f.dp2px.toInt())
    }
    bottomContent.addView(paletteHeader)

    val horizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
    }
    val itemsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    horizontalScrollView.addView(itemsContainer)
    bottomContent.addView(horizontalScrollView)

    fun refreshBottomItems() {
        itemsContainer.removeAllViews()
        val current = configProvider()

        current.wheelItems.forEachIndexed { index, action ->
            val itemChip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f.dp2px
                    setColor(DynamicColorUtils.surfaceContainerHighColor)
                }
                val padH = 10f.dp2px.toInt()
                val padV = 6f.dp2px.toInt()
                setPadding(padH, padV, 6f.dp2px.toInt(), padV)
                isClickable = true
                isFocusable = true
                setOnClickListener { onEditItem(index) }
            }

            // Index
            val idxText = TextView(context).apply {
                text = "${index + 1}."
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                setTextColor(DynamicColorUtils.primaryColor)
                setPadding(0, 0, 4f.dp2px.toInt(), 0)
            }
            itemChip.addView(idxText)

            // Icon
            val iconView = ImageView(context).apply {
                val size = 20f.dp2px.toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = 6f.dp2px.toInt()
                }
                setImageDrawable(action.icon(context))
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (action.type != QuickActionType.OPEN_APP || action.packageName == null) {
                    imageTintList = ColorStateList.valueOf(DynamicColorUtils.onSurfaceColor)
                }
            }
            itemChip.addView(iconView)

            // Label
            val labelView = TextView(context).apply {
                text = action.label(context)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(DynamicColorUtils.onSurfaceColor)
            }
            itemChip.addView(labelView)

            // Delete button
            if (current.wheelItems.size > 2) {
                val delBtn = AppCompatImageButton(context).apply {
                    val size = 28f.dp2px.toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginStart = 4f.dp2px.toInt()
                    }
                    setImageResource(R.drawable.baseline_clear_24)
                    imageTintList = ColorStateList.valueOf(DynamicColorUtils.onSurfaceVariantColor)
                    val background = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
                    setBackgroundResource(background.resourceId)
                    val pad = 4f.dp2px.toInt()
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener { onRemoveItem(index) }
                }
                itemChip.addView(delBtn)
            }

            val chipParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8f.dp2px.toInt()
            }
            itemsContainer.addView(itemChip, chipParams)
        }

        // Add button chip
        if (current.wheelItems.size < QuickSetupConfig.MAX_WHEEL_ITEMS) {
            val addChip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f.dp2px
                    setColor(DynamicColorUtils.secondaryContainerColor)
                }
                val padH = 12f.dp2px.toInt()
                val padV = 6f.dp2px.toInt()
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                setOnClickListener { onAddItem() }
            }

            val addIcon = ImageView(context).apply {
                val size = 20f.dp2px.toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = 4f.dp2px.toInt()
                }
                setImageResource(R.drawable.baseline_add_24)
                imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
            }
            addChip.addView(addIcon)

            val addText = TextView(context).apply {
                setText(R.string.quick_setup_add)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(DynamicColorUtils.primaryColor)
            }
            addChip.addView(addText)
            itemsContainer.addView(addChip)
        }
    }

    refreshBottomItems()

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }

    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
    }
    activity.lifecycle.addObserver(observer)

    dialog.setOnDismissListener {
        activity.lifecycle.removeObserver(observer)
        onDismiss()
    }

    dialog.setContentView(root)
    dialog.show()
    dialog.window?.let {
        WindowCompat.setDecorFitsSystemWindows(it, false)
        it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
    ViewCompat.requestApplyInsets(root)
    return dialog
}
