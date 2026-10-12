package io.github.lumiagg.gestureevo.inspire.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.lumiagg.gestureevo.inspire.ScreenContentNode
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OcrImageTextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val nodes = mutableListOf<ScreenContentNode>()
    private val nodeRects = mutableListOf<RectF>()
    private val columnGroups = mutableListOf<RectF>()
    private val charBoxes = mutableListOf<CharBox>()
    private val imageBounds = RectF()

    private val handleRadius = 10f.dp2px
    private val handleStemLength = 30f.dp2px
    private val handleTouchRadius = 40f.dp2px
    private val handleStemWidth = 6f.dp2px
    private val handleStrokeWidth = 2f.dp2px

    private val selectablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DynamicColorUtils.primaryColor.withAlpha(0x18)
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DynamicColorUtils.primaryColor.withAlpha(0x66)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DynamicColorUtils.primaryColor
        style = Paint.Style.FILL
    }
    private val handleStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DynamicColorUtils.primaryColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = handleStemWidth
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = handleStrokeWidth
    }

    private var imageWidth = 0
    private var imageHeight = 0
    private var selectionStart = -1
    private var selectionEnd = -1
    private var dragMode = DragMode.NONE
    var onSelectionChanged: ((String) -> Unit)? = null
    var onSelectionStateChanged: ((String, RectF?, Boolean, PointF?) -> Unit)? = null

    init {
        isClickable = true
    }

    fun setImageTextNodes(
        imageWidth: Int,
        imageHeight: Int,
        nodes: List<ScreenContentNode>,
    ) {
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.nodes.clear()
        this.nodes.addAll(nodes.filter { !it.content.isNullOrBlank() && !it.bound.isEmpty })
        clearSelection(notify = false)
        rebuildMappedBoxes()
        notifySelectionChanged()
        invalidate()
    }

    fun setDisplayedImageBounds(bounds: RectF) {
        imageBounds.set(bounds)
        rebuildMappedBoxes()
        invalidate()
    }

    fun clearSelection() = clearSelection(notify = true)

    fun selectedText(): String {
        return selectedCharBoxes()
            .asSequence()
            .joinToString(separator = "") { it.char.toString() }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        rebuildMappedBoxes()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        refreshDynamicColors()
        nodeRects.forEach { rect ->
            canvas.drawRoundRect(rect, rect.cornerRadius, rect.cornerRadius, selectablePaint)
        }
        selectedHighlightRects().forEach { rect ->
            canvas.drawRoundRect(rect, rect.cornerRadius, rect.cornerRadius, selectedPaint)
        }
        drawSelectionHandles(canvas)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private val clickSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                dragMode = hitHandle(event.x, event.y) ?: DragMode.NEW
                val hitIndex = findNearestCharIndex(event.x, selectionTouchY(event.y))
                if (hitIndex >= 0) {
                    when (dragMode) {
                        DragMode.START -> selectionStart = hitIndex
                        DragMode.END -> selectionEnd = hitIndex
                        DragMode.NEW -> {
                            selectionStart = hitIndex
                            selectionEnd = hitIndex
                        }

                        DragMode.NONE -> Unit
                    }
                    normalizeSelectionOrderIfNeeded()
                    notifySelectionChanged(
                        isInteracting = true,
                        interactionAnchor = PointF(event.x, event.y),
                    )
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragMode != DragMode.NONE) {
                    val hitIndex = findNearestCharIndex(event.x, selectionTouchY(event.y))
                    if (hitIndex >= 0) {
                        when (dragMode) {
                            DragMode.START -> selectionStart = hitIndex
                            DragMode.END, DragMode.NEW -> selectionEnd = hitIndex
                            DragMode.NONE -> Unit
                        }
                        normalizeSelectionOrderIfNeeded()
                        notifySelectionChanged(
                            isInteracting = true,
                            interactionAnchor = PointF(event.x, event.y),
                        )
                        invalidate()
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    kotlin.math.abs(event.x - touchDownX) <= clickSlop &&
                    kotlin.math.abs(event.y - touchDownY) <= clickSlop
                ) performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
                dragMode = DragMode.NONE
                notifySelectionChanged(isInteracting = false)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun rebuildMappedBoxes() {
        nodeRects.clear()
        columnGroups.clear()
        charBoxes.clear()
        if (imageWidth <= 0 || imageHeight <= 0 || imageBounds.isEmpty) return
        val scaleX = imageBounds.width() / imageWidth
        val scaleY = imageBounds.height() / imageHeight
        var globalIndex = 0
        nodes.forEachIndexed { nodeIndex, node ->
            val text = node.content?.trim().orEmpty()
            if (text.isEmpty()) return@forEachIndexed
            val nodeRect = node.bound.toDisplayRect(scaleX, scaleY).apply {
                val insetX = min(width() * 0.03f, 1.2f.dp2px)
                val insetY = min(height() * 0.08f, 1.2f.dp2px)
                inset(insetX, insetY)
            }
            nodeRects.add(nodeRect)
            val columnGroupId = resolveColumnGroup(nodeRect)
            val horizontal = nodeRect.width() >= nodeRect.height()
            val charCount = text.length.coerceAtLeast(1)
            text.forEachIndexed { charIndex, char ->
                val rect = if (horizontal) {
                    val charWidth = nodeRect.width() / charCount
                    RectF(
                        nodeRect.left + charWidth * charIndex,
                        nodeRect.top,
                        nodeRect.left + charWidth * (charIndex + 1),
                        nodeRect.bottom,
                    )
                } else {
                    val charHeight = nodeRect.height() / charCount
                    RectF(
                        nodeRect.left,
                        nodeRect.top + charHeight * charIndex,
                        nodeRect.right,
                        nodeRect.top + charHeight * (charIndex + 1),
                    )
                }
                charBoxes.add(
                    CharBox(
                        nodeIndex = nodeIndex,
                        columnGroupId = columnGroupId,
                        globalIndex = globalIndex,
                        char = char,
                        rect = rect,
                    )
                )
                globalIndex++
            }
        }
        val maxIndex = charBoxes.lastOrNull()?.globalIndex ?: -1
        if (selectionStart !in 0..maxIndex || selectionEnd !in 0..maxIndex) {
            clearSelection(notify = false)
        }
    }

    private fun Rect.toDisplayRect(scaleX: Float, scaleY: Float): RectF {
        return RectF(
            imageBounds.left + left * scaleX,
            imageBounds.top + top * scaleY,
            imageBounds.left + right * scaleX,
            imageBounds.top + bottom * scaleY,
        )
    }

    private fun findNearestCharIndex(x: Float, y: Float): Int {
        charBoxes.lastOrNull { it.rect.contains(x, y) }?.let { return it.globalIndex }
        if (!imageBounds.contains(x, y)) return -1
        return charBoxes.minByOrNull { box ->
            val dx = when {
                x < box.rect.left -> box.rect.left - x
                x > box.rect.right -> x - box.rect.right
                else -> 0f
            }
            val dy = when {
                y < box.rect.top -> box.rect.top - y
                y > box.rect.bottom -> y - box.rect.bottom
                else -> 0f
            }
            dx * dx + dy * dy + abs(box.rect.centerY() - y) * 0.2f
        }?.globalIndex ?: -1
    }

    private fun hitHandle(x: Float, y: Float): DragMode? {
        val range = selectedRange() ?: return null
        val startBox = charBoxes.firstOrNull { it.globalIndex == range.first } ?: return null
        val endBox = charBoxes.firstOrNull { it.globalIndex == range.last } ?: return null
        val startX = startBox.rect.left
        val startY = handleCenterY(startBox.rect.bottom)
        val endX = endBox.rect.right
        val endY = handleCenterY(endBox.rect.bottom)
        val startDistance = squaredDistance(x, y, startX, startY)
        val endDistance = squaredDistance(x, y, endX, endY)
        val hitRadiusSquared = handleTouchRadius * handleTouchRadius
        return when {
            startDistance <= hitRadiusSquared && startDistance <= endDistance -> DragMode.START
            endDistance <= hitRadiusSquared -> DragMode.END
            else -> null
        }
    }

    private fun normalizeSelectionOrderIfNeeded() {
        if (selectionStart < 0 || selectionEnd < 0 || selectionStart <= selectionEnd) return
        val oldStart = selectionStart
        selectionStart = selectionEnd
        selectionEnd = oldStart
        dragMode = when (dragMode) {
            DragMode.START -> DragMode.END
            DragMode.END -> DragMode.START
            else -> dragMode
        }
    }

    private fun selectedRange(): IntRange? {
        if (selectionStart < 0 || selectionEnd < 0) return null
        return selectionStart..selectionEnd
    }

    private fun selectedCharBoxes(): List<CharBox> {
        val range = selectedRange() ?: return emptyList()
        val startGroupId = charBoxes.firstOrNull { it.globalIndex == range.first }?.columnGroupId
        val endGroupId = charBoxes.firstOrNull { it.globalIndex == range.last }?.columnGroupId
        val constrainedGroupId = startGroupId
            ?.takeIf { it != NO_COLUMN_GROUP && it == endGroupId }
        return charBoxes.filter { box ->
            box.globalIndex in range &&
                    (constrainedGroupId == null || box.columnGroupId == constrainedGroupId)
        }
    }

    private fun selectedHighlightRects(): List<RectF> {
        return selectedCharBoxes()
            .groupBy { it.nodeIndex }
            .toSortedMap()
            .mapNotNull { (nodeIndex, boxes) ->
                val nodeRect = nodeRects.getOrNull(nodeIndex) ?: return@mapNotNull null
                val horizontal = nodeRect.width() >= nodeRect.height()
                val left = boxes.minOf { it.rect.left }
                val top = boxes.minOf { it.rect.top }
                val right = boxes.maxOf { it.rect.right }
                val bottom = boxes.maxOf { it.rect.bottom }
                if (horizontal) {
                    RectF(left, nodeRect.top, right, nodeRect.bottom)
                } else {
                    RectF(nodeRect.left, top, nodeRect.right, bottom)
                }
            }
    }

    private fun drawSelectionHandles(canvas: Canvas) {
        val range = selectedRange() ?: return
        val first = charBoxes.firstOrNull { it.globalIndex == range.first } ?: return
        val last = charBoxes.firstOrNull { it.globalIndex == range.last } ?: return
        drawHandle(canvas, first.rect.left, first.rect.bottom)
        drawHandle(canvas, last.rect.right, last.rect.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        val centerY = handleCenterY(y)
        canvas.drawLine(x, y, x, centerY, handleStemPaint)
        canvas.drawCircle(x, centerY, handleRadius, handlePaint)
        canvas.drawCircle(x, centerY, handleRadius, handleStrokePaint)
    }

    private fun handleCenterY(anchorY: Float): Float = anchorY + handleStemLength + handleRadius

    private fun selectionTouchY(y: Float): Float {
        return when (dragMode) {
            DragMode.START, DragMode.END -> y - handleStemLength - handleRadius
            else -> y
        }
    }

    private fun clearSelection(notify: Boolean) {
        selectionStart = -1
        selectionEnd = -1
        dragMode = DragMode.NONE
        if (notify) notifySelectionChanged(isInteracting = false)
        invalidate()
    }

    private fun notifySelectionChanged(
        isInteracting: Boolean = dragMode != DragMode.NONE,
        interactionAnchor: PointF? = null,
    ) {
        val selectedText = selectedText()
        onSelectionChanged?.invoke(selectedText)
        onSelectionStateChanged?.invoke(
            selectedText,
            selectedBounds(),
            isInteracting,
            interactionAnchor,
        )
    }

    private fun selectedBounds(): RectF? {
        val rects = selectedHighlightRects()
        if (rects.isEmpty()) return null
        return RectF(rects.first()).apply {
            rects.drop(1).forEach { union(it) }
        }
    }

    private fun squaredDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private fun refreshDynamicColors() {
        val primaryColor = DynamicColorUtils.primaryColor
        selectablePaint.color = primaryColor.withAlpha(0x18)
        selectedPaint.color = primaryColor.withAlpha(0x66)
        handlePaint.color = primaryColor
        handleStemPaint.color = primaryColor
    }

    private fun resolveColumnGroup(rect: RectF): Int {
        if (rect.width() > imageBounds.width() * WIDE_BLOCK_WIDTH_RATIO) return NO_COLUMN_GROUP
        val matchedIndex = columnGroups.indexOfFirst { group ->
            val overlap = min(group.right, rect.right) - max(group.left, rect.left)
            overlap > 0f && overlap / min(group.width(), rect.width()) >= COLUMN_OVERLAP_RATIO
        }
        if (matchedIndex >= 0) {
            columnGroups[matchedIndex].union(rect)
            return matchedIndex
        }
        columnGroups.add(RectF(rect))
        return columnGroups.lastIndex
    }

    private data class CharBox(
        val nodeIndex: Int,
        val columnGroupId: Int,
        val globalIndex: Int,
        val char: Char,
        val rect: RectF,
    )

    private enum class DragMode {
        NONE, NEW, START, END
    }

    private val RectF.cornerRadius: Float
        get() = min(width(), height()) * 0.22f

    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)

    private companion object {
        const val NO_COLUMN_GROUP = -1
        const val WIDE_BLOCK_WIDTH_RATIO = 0.65f
        const val COLUMN_OVERLAP_RATIO = 0.35f
    }
}
