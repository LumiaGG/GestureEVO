package io.github.lumiagg.gestureevo.settings.guide

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlin.math.cos
import kotlin.math.sin

internal class GuideWheelPreview(context: Context) : FrameLayout(context) {
    private var items: List<QuickAction> = emptyList()
    private var select: (Int) -> Unit = {}

    fun setItems(items: List<QuickAction>, onSelect: (Int) -> Unit) {
        this.items = items
        select = onSelect
        removeAllViews()
        addView(MaterialTextView(context).apply {
            setText(R.string.quick_setup_wheel)
            gravity = Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(DynamicColorUtils.onSurfaceVariantColor)
        }, LayoutParams(88f.dp2px.toInt(), 48f.dp2px.toInt(), Gravity.CENTER))
        items.forEachIndexed { index, action ->
            addView(AppCompatImageButton(context).apply {
                setImageDrawable(action.icon(context))
                contentDescription = action.label(context)
                TooltipCompat.setTooltipText(this, contentDescription)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                val pad = 12f.dp2px.toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(DynamicColorUtils.secondaryContainerColor)
                }
                if (action.type != QuickActionType.OPEN_APP) {
                    imageTintList = ColorStateList.valueOf(DynamicColorUtils.onSurfaceColor)
                }
                setOnClickListener { select(index) }
            }, LayoutParams(52f.dp2px.toInt(), 52f.dp2px.toInt()))
        }
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val radius = (minOf(width, height) / 2f - 30f.dp2px).coerceAtLeast(0f)
        items.indices.forEach { index ->
            val angle = -Math.PI / 2 + index * 2 * Math.PI / items.size
            val child = getChildAt(index + 1)
            val x = (width / 2f + cos(angle) * radius - child.measuredWidth / 2f).toInt()
            val y = (height / 2f + sin(angle) * radius - child.measuredHeight / 2f).toInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}
