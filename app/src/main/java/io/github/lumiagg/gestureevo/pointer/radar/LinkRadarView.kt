package io.github.lumiagg.gestureevo.pointer.radar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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
    private var actionGroups = emptyMap<Int, List<LinkRegion>>()
    private val actionViews = mutableMapOf<Int, ViewLinkRadarTargetBinding>()
    private var actionBounds = emptyMap<Int, RadarBox>()
    private var singleActionLabel = ""
    private var multipleActionLabel = ""
    private var actionLayoutKey: List<Int>? = null

    val visibleActionKeys: Set<Int> get() = actionBounds.keys

    fun showActionPrompts(groups: Map<Int, List<LinkRegion>>, singleLabel: String, multipleLabel: String) {
        clearActionPrompts()
        actionGroups = groups
        singleActionLabel = singleLabel
        multipleActionLabel = multipleLabel
        requestLayout()
    }

    fun updateActionPrompt(key: Int, label: String, icon: Drawable?) {
        val binding = actionViews[key] ?: return
        binding.targetLabel.text = label
        binding.targetIcon.setAppIcon(icon, R.drawable.ic_baseline_insert_link_24)
    }

    fun actionLinksAt(screenX: Float, screenY: Float): List<LinkRegion> {
        if (visibility != VISIBLE) return emptyList()
        getLocationOnScreen(origin)
        val x = screenX - origin[0]
        val y = screenY - origin[1]
        val key = actionBounds.entries.firstOrNull { (_, box) ->
            x >= box.left && x < box.right && y >= box.top && y < box.bottom
        }?.key ?: return emptyList()
        return actionGroups[key].orEmpty()
    }

    fun highlightLinks(links: List<LinkRegion>) {
        regions = links
        invalidate()
    }

    private fun clearActionPrompts() {
        actionViews.values.forEach { removeView(it.root) }
        actionViews.clear()
        actionGroups = emptyMap()
        actionBounds = emptyMap()
        actionLayoutKey = null
    }

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
        styleTarget(target)
        actionViews.values.forEach(::styleTarget)
        invalidate()
    }

    private fun styleTarget(binding: ViewLinkRadarTargetBinding) {
        binding.root.background = GradientDrawable().apply {
            cornerRadius = 24f * density
            setColor(DynamicColorUtils.surfaceContainerHighestColor)
            setStroke((density * 2).toInt().coerceAtLeast(1), DynamicColorUtils.primaryColor)
        }
        binding.targetLabel.setTextColor(DynamicColorUtils.onPrimaryContainerColor)
        binding.targetIcon.refreshTheme()
    }

    fun clearTarget() = show(emptyList(), "")
    fun clear() { detected = emptyList(); clearActionPrompts(); clearTarget() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        target.targetLabel.maxWidth = (MeasureSpec.getSize(widthMeasureSpec) - 88 * density).toInt().coerceAtLeast(1)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutActionPrompts()
        if (regions.isEmpty() || target.root.visibility != VISIBLE) return
        getLocationOnScreen(origin)
        val anchor = regions.first().frameBounds().apply { regions.drop(1).forEach { union(it.frameBounds()) } }
        val margin = (12 * density).toInt()
        val bubbleWidth = target.root.measuredWidth
        val bubbleHeight = target.root.measuredHeight
        val x = (anchor.centerX() - origin[0] - bubbleWidth / 2).coerceIn(margin, (width - margin - bubbleWidth).coerceAtLeast(margin))
        val above = anchor.top - origin[1] - bubbleHeight - margin
        val preferredY = if (above >= margin) above else anchor.bottom - origin[1] + margin
        val y = preferredY.coerceIn(margin, (height - bubbleHeight - margin).coerceAtLeast(margin))
        target.root.layout(x, y, x + bubbleWidth, y + bubbleHeight)
    }

    private fun layoutActionPrompts() {
        if (actionGroups.isEmpty() || width == 0 || height == 0) return
        getLocationOnScreen(origin)
        val fontScale = resources.configuration.fontScale
        val actionWidth = minOf(240 * density, width - 24 * density).toInt().coerceAtLeast(1)
        val actionHeight = maxOf(48 * density, 24 * density + 20 * density * fontScale).toInt()
        val key = listOf(width, height, origin[0], origin[1], actionWidth, actionHeight)
        if (actionLayoutKey != key) {
            actionLayoutKey = key
            fun box(link: LinkRegion): RadarBox = link.frameBounds().toRadarBox(origin[0], origin[1])
            val anchors = actionGroups.mapValues { (_, links) -> enclosingRadarBox(links.map(::box)) }
                .mapNotNull { (id, bounds) -> bounds?.let { id to it } }.toMap()
            actionBounds = placeRadarActions(anchors, detected.groupBy { it.groupId }.mapValues { (_, links) -> links.map(::box) },
                RadarBox(12 * density, 12 * density, width - 12 * density, height - 12 * density),
                actionWidth.toFloat(), actionHeight.toFloat(), 8 * density)
            actionViews.keys.filter { it !in actionBounds }.forEach { removed ->
                actionViews.remove(removed)?.let { removeView(it.root) }
            }
            for ((id, _) in actionBounds) {
                if (id in actionViews) continue
                val binding = ViewLinkRadarTargetBinding.inflate(LayoutInflater.from(context), this, false)
                binding.targetLabel.text = if (actionGroups.getValue(id).size == 1) singleActionLabel else multipleActionLabel
                binding.targetIcon.setAppIcon(null, R.drawable.ic_baseline_insert_link_24)
                styleTarget(binding)
                actionViews[id] = binding
                addView(binding.root, LayoutParams(actionWidth, actionHeight))
            }
        }
        for ((id, bounds) in actionBounds) {
            val child = actionViews.getValue(id).root
            child.measure(MeasureSpec.makeMeasureSpec(actionWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(actionHeight, MeasureSpec.EXACTLY))
            child.layout(bounds.left.toInt(), bounds.top.toInt(), bounds.left.toInt() + actionWidth, bounds.top.toInt() + actionHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(origin)
        canvas.save()
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        border.color = DynamicColorUtils.primaryColor
        border.alpha = 140
        border.strokeWidth = density
        detected.map(LinkRegion::frameBounds).distinct().forEach { canvas.drawRoundRect(RectF(it), 5 * density, 5 * density, border) }
        border.alpha = 255
        border.strokeWidth = 2 * density
        regions.map(LinkRegion::frameBounds).distinct().forEach { canvas.drawRoundRect(RectF(it), 5 * density, 5 * density, border) }
        canvas.restore()
    }
}
