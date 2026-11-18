package io.github.lumiagg.gestureevo.pointer.radar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.widget.FrameLayout
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ViewLinkRadarTargetBinding
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.icon.setAppIcon

/** Link outlines with the same app-icon component used by search result items. */
internal class LinkRadarView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val origin = IntArray(2)
    private val target = ViewLinkRadarTargetBinding.inflate(LayoutInflater.from(context), this, false)
    private var detected = emptyList<LinkRegion>()
    private var regions = emptyList<LinkRegion>()

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(target.root)
        target.root.visibility = GONE
    }

    fun setLinks(links: List<LinkRegion>) {
        if (detected == links) return
        detected = links
        invalidate()
    }

    fun show(regions: List<LinkRegion>, label: String, icon: Drawable? = null) {
        this.regions = regions
        target.root.visibility = if (regions.isEmpty()) GONE else VISIBLE
        target.targetLabel.text = label
        target.targetIcon.setAppIcon(icon, R.drawable.ic_baseline_insert_link_24)
        refreshTheme()
        requestLayout()
        invalidate()
    }

    fun refreshTheme() {
        target.root.background = GradientDrawable().apply {
            cornerRadius = 24f * density
            setColor(DynamicColorUtils.surfaceContainerHighestColor)
            setStroke((density * 2).toInt().coerceAtLeast(1), DynamicColorUtils.primaryColor)
        }
        target.targetLabel.setTextColor(DynamicColorUtils.onPrimaryContainerColor)
        target.targetIcon.refreshTheme()
        invalidate()
    }

    fun clearTarget() = show(emptyList(), "")
    fun clear() { detected = emptyList(); clearTarget() }

    private fun enclosingBounds(link: LinkRegion): Rect =
        Rect(link.rects.first()).apply { link.rects.drop(1).forEach(::union) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        target.targetLabel.maxWidth = (MeasureSpec.getSize(widthMeasureSpec) - 88 * density).toInt().coerceAtLeast(1)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (regions.isEmpty()) return
        getLocationOnScreen(origin)
        val anchor = enclosingBounds(regions.first()).apply { regions.drop(1).forEach { union(enclosingBounds(it)) } }
        val margin = (12 * density).toInt()
        val bubbleWidth = target.root.measuredWidth
        val bubbleHeight = target.root.measuredHeight
        val x = (anchor.centerX() - origin[0] - bubbleWidth / 2).coerceIn(margin, (width - margin - bubbleWidth).coerceAtLeast(margin))
        val above = anchor.top - origin[1] - bubbleHeight - margin
        val preferredY = if (above >= margin) above else anchor.bottom - origin[1] + margin
        val y = preferredY.coerceIn(margin, (height - bubbleHeight - margin).coerceAtLeast(margin))
        target.root.layout(x, y, x + bubbleWidth, y + bubbleHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(origin)
        canvas.save()
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        border.color = DynamicColorUtils.primaryColor
        border.alpha = 140
        border.strokeWidth = density
        detected.map(::enclosingBounds).distinct().forEach { canvas.drawRoundRect(RectF(it), 5 * density, 5 * density, border) }
        border.alpha = 255
        border.strokeWidth = 2 * density
        regions.map(::enclosingBounds).distinct().forEach { canvas.drawRoundRect(RectF(it), 5 * density, 5 * density, border) }
        canvas.restore()
    }
}
