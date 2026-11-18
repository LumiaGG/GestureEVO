package io.github.lumiagg.gestureevo.pointer.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.widget.FrameLayout
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ItemPointerHoverActionBinding
import io.github.lumiagg.gestureevo.databinding.ViewPointerHoverMenuBinding
import io.github.lumiagg.gestureevo.pointer.PointerHoverAction
import io.github.lumiagg.gestureevo.pointer.placeHoverMenu
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.icon.setAppIcon
import com.google.android.material.color.MaterialColors
import androidx.core.graphics.ColorUtils

/** Presentation in pointer-local coordinates. Input continues through the original touch owner. */
internal class PointerHoverMenuView(context: Context) : FrameLayout(context) {
    private val menu = ViewPointerHoverMenuBinding.inflate(LayoutInflater.from(context), this, false)
    private val rows = linkedMapOf<PointerHoverAction, ItemPointerHoverActionBinding>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var anchorX = 0f
    private var anchorY = 0f
    private var selected: PointerHoverAction? = null
    private var menuY: Float? = null
    private var selectedBackground = 0
    private var selectedForeground = 0

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(menu.root)
        menu.root.clipToOutline = true
        visibility = GONE
    }

    fun show(x: Float, y: Float) {
        anchorX = x
        anchorY = y
        menuY = null
        selected = null
        rows.clear()
        menu.actions.removeAllViews()
        menu.root.scrollTo(0, 0)
        addRow(PointerHoverAction.LONG_PRESS, context.getString(R.string.pointer_hover_long_press))
        addRow(PointerHoverAction.DOUBLE_TAP, context.getString(R.string.pointer_hover_double_tap))
        addRow(PointerHoverAction.SELECT, context.getString(R.string.pointer_hover_select))
        visibility = VISIBLE
        refreshTheme()
    }

    private fun addRow(action: PointerHoverAction, label: String, icon: Drawable? = null) {
        val row = rows[action] ?: ItemPointerHoverActionBinding.inflate(
            LayoutInflater.from(context), menu.actions, false,
        ).also {
            rows[action] = it
            menu.actions.addView(it.root)
        }
        row.actionLabel.text = label
        val showIcon = action == PointerHoverAction.OPEN_LINK
        row.actionIcon.visibility = if (showIcon) VISIBLE else GONE
        row.actionGlyph.visibility = if (showIcon) GONE else VISIBLE
        row.actionGlyph.setImageResource(when (action) {
            PointerHoverAction.LONG_PRESS -> R.drawable.ic_pointer_long_press
            PointerHoverAction.DOUBLE_TAP -> R.drawable.ic_pointer_double_tap
            else -> R.drawable.ic_screen_content_fullscreen_text_24
        })
        if (showIcon) row.actionIcon.setAppIcon(icon, R.drawable.ic_baseline_insert_link_24)
    }

    fun showLink(label: String, icon: Drawable? = null) {
        addRow(PointerHoverAction.OPEN_LINK, label, icon)
        refreshTheme()
    }

    fun highlight(action: PointerHoverAction?) {
        selected = action
        rows.forEach { (key, row) ->
            row.root.background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(if (selected == key) selectedBackground else android.graphics.Color.TRANSPARENT)
            }
            row.actionLabel.setTextColor(if (selected == key) {
                selectedForeground
            } else DynamicColorUtils.onSurfaceColor)
            row.actionGlyph.imageTintList = if (key == PointerHoverAction.SELECT) null else ColorStateList.valueOf(if (selected == key) {
                selectedForeground
            } else DynamicColorUtils.onSurfaceVariantColor)
        }
        revealSelection()
    }

    private fun revealSelection() {
        val row = rows[selected]?.root ?: return
        if (row.isLaidOut && !menu.root.isLayoutRequested) {
            menu.root.requestChildRectangleOnScreen(menu.actions,
                Rect(0, row.top, menu.actions.width, row.bottom), true)
        }
    }

    fun refreshTheme() {
        val themed = DynamicColorUtils.dynamicThemedContext(context)
        selectedBackground = MaterialColors.getColor(themed, com.google.android.material.R.attr.colorPrimaryContainer, 0)
        selectedForeground = MaterialColors.getColor(themed, com.google.android.material.R.attr.colorOnPrimaryContainer, 0)
        menu.root.background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(DynamicColorUtils.surfaceContainerHighestColor)
            setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(DynamicColorUtils.outlineColor, 72))
        }
        rows.values.forEach { it.actionIcon.refreshTheme() }
        highlight(selected)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val bounds = availableBounds(width, height)
        val maxWidth = minOf(resources.getDimensionPixelSize(R.dimen.pointer_hover_menu_max_width), bounds.width())
        val minWidth = minOf(resources.getDimensionPixelSize(R.dimen.pointer_hover_menu_min_width), maxWidth)
        val menuPadding = menu.root.paddingLeft + menu.root.paddingRight
        val rowWidth = (maxWidth - menuPadding).coerceAtLeast(1)
        rows.values.forEach { row ->
            row.root.measure(
                MeasureSpec.makeMeasureSpec(rowWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }
        val contentWidth = (rows.values.maxOfOrNull { it.root.measuredWidth } ?: 0) + menuPadding
        menu.root.measure(
            MeasureSpec.makeMeasureSpec(contentWidth.coerceIn(minWidth, maxWidth), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bounds.height(), MeasureSpec.AT_MOST),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val bounds = availableBounds(width, height)
        val position = placeHoverMenu(
            anchorX, anchorY,
            menu.root.measuredWidth, menu.root.measuredHeight,
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            resources.getDimension(R.dimen.pointer_hover_menu_gap),
        )
        val x = position.x.toInt()
        val y = (menuY?.toInt() ?: position.y.toInt()).coerceIn(
            bounds.top, (bounds.bottom - menu.root.measuredHeight).coerceAtLeast(bounds.top),
        )
        menuY = y.toFloat()
        menu.root.layout(x, y, x + menu.root.measuredWidth, y + menu.root.measuredHeight)
        revealSelection()
    }

    @Suppress("DEPRECATION")
    private fun availableBounds(width: Int, height: Int): Rect {
        val insets = rootWindowInsets
        val edge = resources.getDimensionPixelSize(R.dimen.pointer_hover_menu_edge)
        val left = edge + (insets?.systemWindowInsetLeft ?: 0)
        val top = edge + (insets?.systemWindowInsetTop ?: 0)
        return Rect(left, top,
            (width - edge - (insets?.systemWindowInsetRight ?: 0)).coerceAtLeast(left + 1),
            (height - edge - (insets?.systemWindowInsetBottom ?: 0)).coerceAtLeast(top + 1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = DynamicColorUtils.primaryColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        canvas.drawCircle(anchorX, anchorY, 8 * density, paint)
    }
}
