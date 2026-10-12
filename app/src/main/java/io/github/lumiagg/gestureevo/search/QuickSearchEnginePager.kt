package io.github.lumiagg.gestureevo.search

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.core.view.children
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.SearchEngineItemBinding
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import kotlin.math.abs
import kotlin.math.roundToInt

class QuickSearchEnginePager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    data class Config(
        val rowCount: Int,
        val columnCount: Int,
        val showLabel: Boolean,
        val showImageBadge: Boolean,
        val previewSortMode: Boolean
    ) {
        val pageSize: Int = (rowCount * columnCount).coerceAtLeast(1)
    }

    private val engines = mutableListOf<SearchEngine>()
    private val inflater = LayoutInflater.from(context)
    // Measure the same cell before and after async engine data arrives. This is
    // deliberately not attached or bound, so it never loads an icon.
    private val measurementItem by lazy {
        SearchEngineItemBinding.bind(inflater.inflate(R.layout.search_engine_item, this, false))
    }
    private val scroller = OverScroller(context)
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val edgeFlipSize = 36.dp
    private val edgeFlipDelay = 850L
    private val reorderInterpolator = DecelerateInterpolator()

    private var config = Config(
        rowCount = 1,
        columnCount = 5,
        showLabel = true,
        showImageBadge = true,
        previewSortMode = false
    )
    private var onEngineClick: (SearchEngine) -> Unit = {}
    private var onEngineLongClick: (SearchEngine) -> Unit = {}
    private var onOrderChanged: (List<SearchEngine>) -> Unit = {}
    private var iconLoader: (RoundImageTextView, SearchEngine, Boolean) -> Unit = { view, engine, badge ->
        setEngineIcon(context, view, engine, badge)
    }

    fun setIconLoader(loader: (RoundImageTextView, SearchEngine, Boolean) -> Unit) {
        iconLoader = loader
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var activeEnginePosition = NO_POSITION
    private var draggedView: View? = null
    private var draggedEngine: SearchEngine? = null
    private var dragTouchOffsetX = 0f
    private var dragTouchOffsetY = 0f
    private var lastDragX = 0f
    private var lastDragY = 0f
    private var orderChangedDuringDrag = false
    private var animateNextLayout = false
    private var isScrolling = false
    private var isDraggingEngine = false
    private var longPressConsumed = false
    private var velocityTracker: VelocityTracker? = null
    private var pendingEdgeDirection = 0

    private val longPressRunnable = Runnable {
        if (activeEnginePosition == NO_POSITION || isScrolling) return@Runnable
        longPressConsumed = true
        if (config.previewSortMode) {
            startEngineDrag(activeEnginePosition)
        } else {
            engines.getOrNull(activeEnginePosition)?.let(onEngineLongClick)
        }
    }

    private val edgeFlipRunnable = Runnable {
        if (!isDraggingEngine || pendingEdgeDirection == 0) return@Runnable
        val nextPage = (currentPage + pendingEdgeDirection).coerceIn(0, pageCount - 1)
        if (nextPage != currentPage) {
            smoothScrollToPage(nextPage)
        }
        pendingEdgeDirection = 0
    }

    val currentOrder: List<SearchEngine>
        get() = engines.toList()

    fun configure(config: Config) {
        this.config = config
        rebuildChildren()
        requestLayout()
    }

    fun setCallbacks(
        onEngineClick: (SearchEngine) -> Unit,
        onEngineLongClick: (SearchEngine) -> Unit,
        onOrderChanged: (List<SearchEngine>) -> Unit
    ) {
        this.onEngineClick = onEngineClick
        this.onEngineLongClick = onEngineLongClick
        this.onOrderChanged = onOrderChanged
    }

    fun submitList(searchEngines: List<SearchEngine>) {
        if (engines == searchEngines && childCount == searchEngines.size) {
            return
        }
        engines.clear()
        engines.addAll(searchEngines)
        rebuildChildren()
        requestLayout()
        post { scrollTo(scrollX.coerceIn(0, maxScrollX), 0) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = (widthSize - paddingStart - paddingEnd).coerceAtLeast(1)
        val cellWidth = (contentWidth / config.columnCount.coerceAtLeast(1)).coerceAtLeast(1)
        val template = measurementItem.root
        measurementItem.engineName.visibility = if (config.showLabel) View.VISIBLE else View.GONE
        val templateParams = template.layoutParams as MarginLayoutParams
        template.measure(
            MeasureSpec.makeMeasureSpec(
                (cellWidth - templateParams.leftMargin - templateParams.rightMargin).coerceAtLeast(1),
                MeasureSpec.EXACTLY
            ),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val rowHeight = template.measuredHeight + templateParams.topMargin + templateParams.bottomMargin

        children.forEach { child ->
            val layoutParams = child.layoutParams as MarginLayoutParams
            val childWidthSpec = MeasureSpec.makeMeasureSpec(
                (cellWidth - layoutParams.leftMargin - layoutParams.rightMargin).coerceAtLeast(1),
                MeasureSpec.EXACTLY
            )
            val childHeightSpec = MeasureSpec.makeMeasureSpec(
                (rowHeight - layoutParams.topMargin - layoutParams.bottomMargin).coerceAtLeast(1),
                MeasureSpec.EXACTLY
            )
            child.measure(childWidthSpec, childHeightSpec)
        }

        val desiredHeight = paddingTop + paddingBottom + rowHeight * config.rowCount.coerceAtLeast(1)
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(widthSize, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentWidth = pageWidth
        if (contentWidth <= 0) return

        val cellWidth = (contentWidth / config.columnCount.coerceAtLeast(1)).coerceAtLeast(1)
        val rowHeight = ((height - paddingTop - paddingBottom) / config.rowCount.coerceAtLeast(1))
            .coerceAtLeast(1)

        children.forEachIndexed { desktopPosition, child ->
            val layoutParams = child.layoutParams as MarginLayoutParams
            val oldLeft = child.left
            val oldTop = child.top
            val oldTranslationX = child.translationX
            val oldTranslationY = child.translationY
            val shouldAnimateChild = animateNextLayout && child != draggedView && child.width > 0
            val page = desktopPosition / config.pageSize
            val inPagePosition = desktopPosition % config.pageSize
            val row = inPagePosition / config.columnCount
            val column = inPagePosition % config.columnCount
            val cellLeft = paddingStart + page * contentWidth + column * cellWidth
            val cellTop = paddingTop + row * rowHeight
            val childLeft = cellLeft + layoutParams.leftMargin
            val childTop = cellTop + layoutParams.topMargin +
                    ((rowHeight - child.measuredHeight - layoutParams.topMargin - layoutParams.bottomMargin) / 2)
                        .coerceAtLeast(0)
            child.layout(
                childLeft,
                childTop,
                childLeft + child.measuredWidth,
                childTop + child.measuredHeight
            )

            if (child == draggedView) {
                updateDraggedViewPosition(child, lastDragX, lastDragY)
                return@forEachIndexed
            }

            if (shouldAnimateChild && (oldLeft != childLeft || oldTop != childTop)) {
                val visualLeft = oldLeft + oldTranslationX
                val visualTop = oldTop + oldTranslationY
                child.animate().cancel()
                child.translationX = visualLeft - childLeft
                child.translationY = visualTop - childTop
                child.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(REORDER_ANIMATION_DURATION_MS)
                    .setInterpolator(reorderInterpolator)
                    .start()
            }
        }

        animateNextLayout = false

        scrollTo(scrollX.coerceIn(0, maxScrollX), 0)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return event.actionMasked == MotionEvent.ACTION_DOWN || isScrolling || isDraggingEngine
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // handleActionUp calls performClick only for a tap, before invoking the engine callback.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        velocityTracker = (velocityTracker ?: VelocityTracker.obtain()).also { it.addMovement(event) }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(event)
            MotionEvent.ACTION_MOVE -> handleActionMove(event)
            MotionEvent.ACTION_UP -> handleActionUp(event)
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX.coerceIn(0, maxScrollX), 0)
            postInvalidateOnAnimation()
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateLayoutParams(layoutParams: LayoutParams?): LayoutParams {
        return MarginLayoutParams(layoutParams)
    }

    override fun checkLayoutParams(layoutParams: LayoutParams?): Boolean {
        return layoutParams is MarginLayoutParams
    }

    private fun handleActionDown(event: MotionEvent) {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (!scroller.isFinished) scroller.abortAnimation()
        downX = event.x
        downY = event.y
        lastX = event.x
        isScrolling = false
        isDraggingEngine = false
        longPressConsumed = false
        activeEnginePosition = findEnginePositionUnder(event.x, event.y)
        handler.removeCallbacks(longPressRunnable)
        if (activeEnginePosition != NO_POSITION) {
            handler.postDelayed(longPressRunnable, longPressTimeout)
        }
    }

    private fun handleActionMove(event: MotionEvent) {
        val dxFromDown = event.x - downX
        val dyFromDown = event.y - downY

        if (isDraggingEngine) {
            updateEngineDrag(event)
            return
        }

        if (!isScrolling && abs(dxFromDown) > touchSlop && abs(dxFromDown) > abs(dyFromDown)) {
            isScrolling = true
            handler.removeCallbacks(longPressRunnable)
        }

        if (isScrolling) {
            val deltaX = (lastX - event.x).toInt()
            scrollTo((scrollX + deltaX).coerceIn(0, maxScrollX), 0)
            lastX = event.x
        }
    }

    private fun handleActionUp(event: MotionEvent) {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(edgeFlipRunnable)

        when {
            isDraggingEngine -> finishEngineDrag()
            isScrolling -> settlePageFromVelocity()
            !longPressConsumed -> {
                val upPosition = findEnginePositionUnder(event.x, event.y)
                if (upPosition != NO_POSITION && upPosition == activeEnginePosition) {
                    engines.getOrNull(upPosition)?.let { engine ->
                        performClick()
                        onEngineClick(engine)
                    }
                }
            }
        }

        releaseVelocityTracker()
        resetTouchState()
    }

    private fun cancelTouch() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(edgeFlipRunnable)
        resetDraggedChild()
        releaseVelocityTracker()
        resetTouchState()
    }

    private fun startEngineDrag(position: Int) {
        val child = getChildAt(position) ?: return
        val engine = engines.getOrNull(position) ?: return
        isDraggingEngine = true
        draggedView = child
        draggedEngine = engine
        dragTouchOffsetX = downX + scrollX - child.left
        dragTouchOffsetY = downY - child.top
        lastDragX = downX
        lastDragY = downY
        orderChangedDuringDrag = false
        child.elevation = 16.dp.toFloat()
        child.alpha = 0.88f
        child.scaleX = 1.08f
        child.scaleY = 1.08f
    }

    private fun updateEngineDrag(event: MotionEvent) {
        val child = draggedView ?: return
        lastDragX = event.x
        lastDragY = event.y
        updateDraggedViewPosition(child, event.x, event.y)
        shiftIconsIfNeeded(event.x, event.y)
        scheduleEdgeFlipIfNeeded(event.x)
    }

    private fun finishEngineDrag() {
        val changed = orderChangedDuringDrag
        resetDraggedChild()
        if (changed) {
            requestLayout()
            onOrderChanged(currentOrder)
        }
    }

    private fun shiftIconsIfNeeded(x: Float, y: Float) {
        val child = draggedView ?: return
        val engine = draggedEngine ?: return
        val fromPosition = engines.indexOf(engine)
        if (fromPosition == NO_POSITION) return

        val toPosition = findEnginePositionByDraggedCenter(child, x, y)
            .takeIf { it != fromPosition }
            ?: NO_POSITION
        if (toPosition == NO_POSITION) return

        engines.removeAt(fromPosition)
        engines.add(toPosition, engine)
        moveChild(fromPosition, toPosition, child)
        activeEnginePosition = toPosition
        orderChangedDuringDrag = true
        animateNextLayout = true
        requestLayout()
    }

    private fun findEnginePositionByDraggedCenter(child: View, x: Float, y: Float): Int {
        val contentWidth = pageWidth
        if (contentWidth <= 0 || engines.isEmpty()) return NO_POSITION

        val cellWidth = (contentWidth / config.columnCount.coerceAtLeast(1)).coerceAtLeast(1)
        val rowHeight = ((height - paddingTop - paddingBottom) / config.rowCount.coerceAtLeast(1))
            .coerceAtLeast(1)
        val centerX = x + scrollX - dragTouchOffsetX + child.width / 2f
        val centerY = y - dragTouchOffsetY + child.height / 2f
        if (centerY < paddingTop || centerY > height - paddingBottom) return NO_POSITION

        val page = (centerX / contentWidth).toInt().coerceIn(0, pageCount - 1)
        val pageX = centerX - page * contentWidth - paddingStart
        val column = (pageX / cellWidth).toInt().coerceIn(0, config.columnCount - 1)
        val row = ((centerY - paddingTop) / rowHeight).toInt().coerceIn(0, config.rowCount - 1)
        val position = page * config.pageSize + row * config.columnCount + column
        return position.takeIf { it in engines.indices } ?: NO_POSITION
    }

    private fun moveChild(fromPosition: Int, toPosition: Int, child: View) {
        removeViewAt(fromPosition)
        addView(child, toPosition)
    }

    private fun updateDraggedViewPosition(child: View, x: Float, y: Float) {
        val desiredLeft = x + scrollX - dragTouchOffsetX
        val desiredTop = y - dragTouchOffsetY
        child.translationX = desiredLeft - child.left
        child.translationY = desiredTop - child.top
    }

    private fun scheduleEdgeFlipIfNeeded(x: Float) {
        if (!scroller.isFinished) return

        val direction = when {
            x < edgeFlipSize && currentPage > 0 -> -1
            x > width - edgeFlipSize && currentPage < pageCount - 1 -> 1
            else -> 0
        }

        if (direction == pendingEdgeDirection) return
        handler.removeCallbacks(edgeFlipRunnable)
        pendingEdgeDirection = direction
        if (direction != 0) {
            handler.postDelayed(edgeFlipRunnable, edgeFlipDelay)
        }
    }

    private fun settlePageFromVelocity() {
        val velocityX = velocityTracker?.let {
            it.computeCurrentVelocity(1000)
            it.xVelocity
        } ?: 0f
        val targetPage = when {
            velocityX > minimumFlingVelocity -> currentPage - 1
            velocityX < -minimumFlingVelocity -> currentPage + 1
            else -> (scrollX.toFloat() / pageWidth.coerceAtLeast(1)).roundToInt()
        }.coerceIn(0, pageCount - 1)
        smoothScrollToPage(targetPage)
    }

    private fun smoothScrollToPage(page: Int) {
        val targetX = page * pageWidth
        scroller.startScroll(scrollX, 0, targetX - scrollX, 0, 260)
        postInvalidateOnAnimation()
    }

    private fun findEnginePositionUnder(x: Float, y: Float, ignoredView: View? = null): Int {
        val contentX = x + scrollX
        for (index in childCount - 1 downTo 0) {
            val child = getChildAt(index)
            if (child == ignoredView) continue
            if (contentX >= child.left + child.translationX &&
                contentX <= child.right + child.translationX &&
                y >= child.top + child.translationY &&
                y <= child.bottom + child.translationY
            ) {
                return index
            }
        }
        return NO_POSITION
    }

    private fun rebuildChildren() {
        val currentCount = childCount
        val targetCount = engines.size
        if (currentCount > targetCount) {
            removeViews(targetCount, currentCount - targetCount)
        }
        engines.forEachIndexed { index, engine ->
            val child = if (index < childCount) {
                getChildAt(index)
            } else {
                val newView = inflater.inflate(R.layout.search_engine_item, this, false)
                addView(newView)
                newView
            }
            bindEngineView(child, engine)
        }
    }

    private fun bindEngineView(child: View, engine: SearchEngine) {
        val binding = SearchEngineItemBinding.bind(child)
        binding.engineName.visibility = if (config.showLabel) View.VISIBLE else View.GONE
        binding.engineName.text = engine.name
        child.visibility = View.VISIBLE
        child.isEnabled = true
        child.background = null
        iconLoader(binding.engineIcon, engine, config.showImageBadge)
    }

    private fun resetDraggedChild() {
        draggedView?.let { child ->
            child.animate().cancel()
            child.elevation = 0f
            child.alpha = 1f
            child.scaleX = 1f
            child.scaleY = 1f
            child.translationX = 0f
            child.translationY = 0f
        }
    }

    private fun resetTouchState() {
        isScrolling = false
        isDraggingEngine = false
        longPressConsumed = false
        activeEnginePosition = NO_POSITION
        draggedView = null
        draggedEngine = null
        orderChangedDuringDrag = false
        pendingEdgeDirection = 0
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private val pageWidth: Int
        get() = (width - paddingStart - paddingEnd).coerceAtLeast(1)

    private val pageCount: Int
        get() = ((engines.size + config.pageSize - 1) / config.pageSize).coerceAtLeast(1)

    private val maxScrollX: Int
        get() = (pageCount - 1) * pageWidth

    private val currentPage: Int
        get() = (scrollX.toFloat() / pageWidth.coerceAtLeast(1)).roundToInt().coerceIn(0, pageCount - 1)

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val NO_POSITION = -1
        private const val REORDER_ANIMATION_DURATION_MS = 180L
    }
}
