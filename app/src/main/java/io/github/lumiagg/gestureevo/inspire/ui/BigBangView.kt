package io.github.lumiagg.gestureevo.inspire.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.view.NestedScrollingChild
import androidx.core.view.size
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.BigbangFunctionItemBinding
import io.github.lumiagg.gestureevo.databinding.BigbangSelectBgBinding
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import io.github.lumiagg.gestureevo.utils.UrlDetector
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import kotlin.math.max

class BigBangView : ViewGroup, NestedScrollingChild {

    private lateinit var functionItemBinding: BigbangFunctionItemBinding
    private lateinit var selectBgBinding: BigbangSelectBgBinding
    private lateinit var functionView: CardView
    private lateinit var selectBgView: MaterialCardView

    private val config = BigBangConfig()
    private val mLines: MutableList<Line> = ArrayList<Line>()

    private var mSectionIndex: MutableList<Int> = ArrayList<Int>()
    private var showAnimation = false
    private var mDisallowedParentIntercept = false

    private var selectedItemsSet = HashSet<ItemState>()
    private var firstTouchItem: Item? = null
    private var firstTouchItemIsSelected = false
    private var isClickSelection = false
    private var lastTouchItem: Item? = null
    private var mNeedReDetectInMeasure = true
    private var interruptTouchMoveEvent = false
    private var functionViewIsAdded = false

    var onFunctionViewLayout: (Int) -> Unit = { _ -> }
    var onTouchCallback: (MotionEvent, View) -> Unit = { _, _ -> }
    var bigBangStatusListener: BigBangStatusListener? = null

    constructor(context: Context?) : super(context)

    @JvmOverloads
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int = 0) : super(
        context, attrs, defStyleAttr
    ) {
        initFunctionView()
    }

    fun initFunctionView() {
        clipChildren = false

        // Initialize function item view binding
        functionItemBinding = BigbangFunctionItemBinding.inflate(LayoutInflater.from(context))
        with(functionItemBinding) {
            functionView = functionItemBinding.root
            root.elevation = 1f
            share.setOnClickListener { bigBangStatusListener?.onShare() }
            edit.setOnClickListener { bigBangStatusListener?.onEdit(0, 0, "") }
            transition.setOnClickListener { bigBangStatusListener?.onTranslation() }
            copySelected.setOnClickListener { bigBangStatusListener?.onCopy(false) }
            copySelected.setOnLongClickListener {
                bigBangStatusListener?.onCopy(true)
                true
            }
            sendToEVO.apply {
                setOnClickListener { bigBangStatusListener?.onSendToEVO(false) }
                setOnLongClickListener {
                    bigBangStatusListener?.onSendToEVO(true)
                    true
                }
            }
            openUrl.apply {
                setOnClickListener { bigBangStatusListener?.onURL() }
                visibility = GONE
            }
            pin.setOnClickListener { bigBangStatusListener?.onPin() }
        }

        // Initialize select background view binding
        selectBgBinding = BigbangSelectBgBinding.inflate(LayoutInflater.from(context))
        selectBgView = selectBgBinding.root
        selectBgBinding.root.elevation = -1f

        functionView.visibility = VISIBLE
        addView(functionView)
        addView(selectBgView)
        functionViewIsAdded = true
    }

    fun applySelectBackgroundGlassStyle(glassEnabled: Boolean) {
        val color = DynamicColorUtils.surfaceContainerHighColor
        selectBgView.setCardBackgroundColor(GlassEffectUtils.glassCardColor(color, glassEnabled))
        selectBgView.cardElevation = 0f
        selectBgView.elevation = -1f
        selectBgView.translationZ = -1f
    }

    fun addTextItem(text: String, isSelected: Boolean = false) {
        mNeedReDetectInMeasure = true

        if (TextUtils.isEmpty(text)) {
            return
        }
        if (text.contains(TAB)) {
            return
        }
        addView(genTextItem(text, isSelected))
    }

    fun genTextItem(text: String?, isSelected: Boolean): TextView {
        val view =
            LayoutInflater.from(context).inflate(R.layout.bigbang_item, this, false) as TextView
        view.text = text
        view.gravity = Gravity.CENTER
        view.isSelected = isSelected
        return view
    }

    fun replaceTextItem(
        contentIndex: Int,
        texts: List<String>,
        selectedIndexes: Set<Int>,
    ): Boolean {
        if (texts.isEmpty()) return false
        val targetChildIndex = findChildIndexByContentIndex(contentIndex) ?: return false
        removeViewAt(targetChildIndex)
        texts.forEachIndexed { offset, text ->
            addView(
                genTextItem(text, selectedIndexes.contains(contentIndex + offset)),
                targetChildIndex + offset,
            )
        }
        mNeedReDetectInMeasure = true
        checkURL()
        requestLayout()
        return true
    }

    private fun findChildIndexByContentIndex(contentIndex: Int): Int? {
        var currentContentIndex = 0
        for (childIndex in 0..<childCount) {
            val child = getChildAt(childIndex)
            if (child !is TextView) continue
            if (currentContentIndex == contentIndex) return childIndex
            currentContentIndex++
        }
        return null
    }

    fun reset() {
        mNeedReDetectInMeasure = true
        showAnimation = false
        removeAllViews()
        functionViewIsAdded = false
        mLines.clear()
    }

    fun isAlreadyReset(): Boolean {
        return mLines.isEmpty()
    }

    fun preSetContent() {
        if (!isAlreadyReset()) {
            reset()
        }
        if (!functionViewIsAdded) {
            initFunctionView()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize =
            MeasureSpec.getSize(widthMeasureSpec) - config.itemPaddingH - config.itemPaddingH
        if (mNeedReDetectInMeasure) {
            val childCount = size

            val measureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

            mLines.clear()
            mSectionIndex.clear()
            var currentLine: Line? = null
            var currentLineWidth = widthSize
            var isEnter = true
            var textItemIndex = 0
            for (i in 0..<childCount) {
                val v = getChildAt(i)
                if (v !is TextView) {
                    v.visibility = VISIBLE
                    v.measure(measureSpec, measureSpec)
                    continue
                }

                val content = v.text.toString()
                val currentContentIndex = textItemIndex++

                v.visibility = VISIBLE
                if (content.contains(ENTER) || content == ENTER_SYMBOL) {
                    v.visibility = GONE
                    mSectionIndex.add(currentContentIndex)
                    isEnter = true
                    continue
                }
                v.measure(measureSpec, measureSpec)

                if (currentLineWidth > 0) {
                    currentLineWidth += config.mItemSpace
                }
                currentLineWidth += v.measuredWidth
                if (mLines.isEmpty() || currentLineWidth > widthSize || (isEnter && config.showSection)) {
                    currentLineWidth = v.measuredWidth
                    currentLine = Line(mLines.size)
                    if (mLines.isNotEmpty()) {
                        mLines[mLines.size - 1].realNewline = isEnter
                    }
                    mLines.add(currentLine)
                }
                currentLine?.let {
                    val item: Item = Item(currentLine, v)
                    item.index = i
                    item.contentIndex = currentContentIndex
                    item.width = v.measuredWidth
                    item.height = v.measuredHeight
                    currentLine.addItem(item)
                    isEnter = false
                }
            }
            mNeedReDetectInMeasure = false
        }

        val size =
            ((if (mLines.isNotEmpty()) (mLines.size * mLines[0].height) else 0) + config.itemPaddingTop + config.itemPaddingBottom + (mLines.size) * config.mLineSpace + config.mActionBarTopHeight + config.mActionBarBottomHeight + config.bottomSpaceHeight)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val firstSelectedLine = findFirstSelectedLine()
        val lastSelectedLine = findLastSelectedLine()
        val itemHeight = maxItemHeight()

        for (i in mLines.indices) {
            val line = mLines[i]
            val items = line.items
            val offsetTop: Int =
                if (firstSelectedLine != null && firstSelectedLine.maxIndex > line.maxIndex) {
                    // If before the first selected line, shift up by mActionBarTopHeight
                    -config.mActionBarTopHeight
                } else if (lastSelectedLine != null && lastSelectedLine.maxIndex < line.maxIndex) {
                    // If after the last selected line, shift down by mActionBarBottomHeight
                    config.mActionBarBottomHeight
                } else {
                    0
                }
            line.isLastSelected = offsetTop == 0
            var left = config.itemPaddingH
            line.top =
                config.itemPaddingTop + i * (itemHeight + config.mLineSpace) + offsetTop + config.mActionBarTopHeight
            for (j in items.indices) {
                val item = items[j]
                val child = item.view
                val oldTop = child.top
                child.layout(
                    left.toInt(),
                    line.top,
                    (left + child.measuredWidth).toInt(),
                    line.top + child.measuredHeight
                )
                if (showAnimation && oldTop != line.top) {
                    val translationY = oldTop - line.top
                    child.translationY = translationY.toFloat()
                    child.animate().translationYBy(-translationY.toFloat()).setDuration(200).start()
                }
                left += child.measuredWidth + config.mItemSpace
            }
        }

        if (firstSelectedLine != null && lastSelectedLine != null) {
            // Layout for selected state
            val actionBarTop =
                firstSelectedLine.maxIndex * (itemHeight + config.mLineSpace) + config.itemPaddingTop
            val actionBarBottom =
                config.mActionBarTopHeight + (lastSelectedLine.maxIndex + 1) * (itemHeight + config.mLineSpace) + config.itemPaddingTop

            val rightPos = measuredWidth - config.actionBarMargin
            val leftPos = rightPos - functionView.measuredWidth

            functionView.layout(
                leftPos, actionBarBottom, rightPos, actionBarBottom + functionView.measuredHeight
            )
            onFunctionViewLayout(actionBarBottom + functionView.measuredHeight)

            selectBgView.layout(
                0,
                actionBarTop + config.actionBarMargin,
                measuredWidth,
                actionBarBottom + functionView.measuredHeight / 2
            )
        } else {
            val rightPos = measuredWidth - config.actionBarMargin
            val leftPos = rightPos - functionView.measuredWidth
            val lastLineBottom = mLines.lastOrNull()?.let {
                it.top + itemHeight
            } ?: let { config.itemPaddingTop + config.mActionBarTopHeight }

            val actionBarTop = lastLineBottom + config.mLineSpace
            val actionBarBottom = lastLineBottom + config.mLineSpace + functionView.measuredHeight
            functionView.layout(leftPos, actionBarTop, rightPos, actionBarBottom)
            onFunctionViewLayout(lastLineBottom + config.mLineSpace + functionView.measuredHeight)

            val selectBgTop = mLines.firstOrNull()?.let {
                it.maxIndex * (itemHeight + config.mLineSpace) + config.itemPaddingTop + config.actionBarMargin
            } ?: let {
                config.itemPaddingTop + config.mActionBarTopHeight + config.actionBarMargin
            }
            selectBgView.layout(
                0,
                selectBgTop,
                measuredWidth,
                actionBarBottom - functionView.measuredHeight / 2
            )
        }
    }

    private fun findLastSelectedLine(): Line? {
        var result: Line? = null
        for (line in mLines) {
            if (line.hasSelected()) {
                result = line
            }
        }
        return result
    }

    private fun findFirstSelectedLine(): Line? {
        for (line in mLines) {
            if (line.hasSelected()) {
                return line
            }
        }
        return null
    }

    private fun maxItemHeight(): Int {
        var height = 0
        for (i in mLines.indices) {
            val line = mLines[i]
            for (item in line.items) {
                height = max(height.toDouble(), item.view.measuredHeight.toDouble()).toInt()
            }
        }
        return height
    }

    private var mIsLongPress = false
    private var mLongPressHandler = Handler(Looper.getMainLooper())
    private var mLongPressRunnable: Runnable? = null
    private var mInitialItemState: Boolean = false  // Track selection state of initial item
    private var mHasStartedSelection = false  // Track whether drag selection has started
    private val LONG_PRESS_TIMEOUT = 300L // Long press timeout in milliseconds

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        onTouchCallback(event, this)

        val x = event.x.toInt()
        val y = event.y.toInt()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                showAnimation = true
                isClickSelection = true
                mIsLongPress = false
                mHasStartedSelection = false

                firstTouchItem = findItemByPoint(x, y)

                if (firstTouchItem != null) {
                    firstTouchItemIsSelected = firstTouchItem!!.isSelected
                    mInitialItemState = firstTouchItemIsSelected
                    lastTouchItem = firstTouchItem
                    selectedItemsSet.clear()

                    parent.requestDisallowInterceptTouchEvent(true)
                    mDisallowedParentIntercept = true
                }

                mLongPressRunnable = Runnable {
                    mIsLongPress = true
                    VibratorUtils.vibrator(context, true)
                    firstTouchItem?.let { item ->
                        item.view.isSelected = mInitialItemState
                        bigBangStatusListener?.onContentLongPress(
                            item.contentIndex,
                            item.text.toString()
                        )
                    }
                    if (mDisallowedParentIntercept) {
                        parent.requestDisallowInterceptTouchEvent(false)
                        mDisallowedParentIntercept = false
                    }
                }
                mLongPressHandler.postDelayed(mLongPressRunnable!!, LONG_PRESS_TIMEOUT)

                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (interruptTouchMoveEvent) return true

                if (mIsLongPress) {
                    return false
                }

                if (firstTouchItem != null) {
                    val findItem = findItemByPoint(x, y)
                    if (findItem != null) {
                        if (findItem != firstTouchItem) {
                            // Drag selection starts once touching a second item
                            if (!mHasStartedSelection) {
                                // Cancel long press detection
                                mLongPressHandler.removeCallbacks(mLongPressRunnable ?: return true)
                                // Invert first item's selection state
                                firstTouchItem?.let { item ->
                                    item.view.isSelected = !mInitialItemState
                                }
                                mHasStartedSelection = true
                            }
                            isClickSelection = false
                        }

                        lastTouchItem = findItem
                        setNaturalSelectionByRect()
                        invalidate()
                    }
                    return true
                }

                return false
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                // If selection hasn't started, cancel long press detection
                if (!mHasStartedSelection) {
                    mLongPressHandler.removeCallbacks(mLongPressRunnable ?: return true)
                }

                if (!mIsLongPress && firstTouchItem != null) {
                    // For single tap (no second item touched and not long-pressed), toggle selection
                    if (isClickSelection && !mHasStartedSelection) {
                        firstTouchItem?.let { item ->
                            item.view.isSelected = !mInitialItemState
                        }
                    }
                    bigBangStatusListener?.onSelected()
                    checkURL()
                    requestLayout()
                }

                if (mDisallowedParentIntercept) {
                    parent.requestDisallowInterceptTouchEvent(false)
                    mDisallowedParentIntercept = false
                }

                mIsLongPress = false
                mHasStartedSelection = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Clean up long press detection
        mLongPressHandler.removeCallbacks(mLongPressRunnable ?: return)
    }

    private fun findItemByPoint(x: Int, y: Int): Item? {
        for (line in mLines) {
            val items = line.items
            for (item in items) {
                if (item.getRectFromView().contains(x, y)) {
                    return item
                }
            }
        }
        return null
    }

    private fun setNaturalSelectionByRect() {
        if (firstTouchItem == null || lastTouchItem == null) {
            return
        }
        for (line in mLines) {
            val items = line.items
            for (item in items) {
                if (inNaturalSelection(item.getRectFromView())) {
                    val state = ItemState(item, item.view.isSelected)
                    selectedItemsSet.add(state)
                    item.view.isSelected = !firstTouchItemIsSelected
                }
            }
        }

        for (item in selectedItemsSet) {
            if (!inNaturalSelection(item.item.getRectFromView())) {
                item.item.view.isSelected = item.isSelected
            }
        }

        if (!isClickSelection && firstTouchItem === lastTouchItem) {
            selectedItemsSet.clear()
            lastTouchItem!!.view.isSelected = !lastTouchItem!!.view.isSelected
        }
    }

    private fun needChangeFirstLastItem(): Boolean {
        if (firstTouchItem == null || lastTouchItem == null) {
            return false
        }
        return firstTouchItem!!.index > lastTouchItem!!.index
    }

    private fun inNaturalSelection(item: Rect?): Boolean {
        if (firstTouchItem == null || lastTouchItem == null || item == null) {
            return false
        }

        val first: Rect
        val last: Rect
        if (needChangeFirstLastItem()) {
            first = lastTouchItem!!.getRectFromView()
            last = firstTouchItem!!.getRectFromView()
        } else {
            first = firstTouchItem!!.getRectFromView()
            last = lastTouchItem!!.getRectFromView()
        }

        if (item.bottom >= first.top && item.top <= last.bottom) {
            if (item.centerY() < first.bottom && item.centerY() > first.top && item.right < first.left) {
                return false
            }
            return item.centerY() >= last.bottom || item.centerY() <= last.top || item.left <= last.right
        }

        return false
    }

    private fun checkURL() {
        showURLBt(UrlDetector.hasUrl(makeSelectedText().trim { it <= ' ' }))
    }

    private fun showURLBt(isShow: Boolean) {
        functionItemBinding.openUrl.visibility = if (isShow) VISIBLE else GONE
        val measureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        functionItemBinding.root.measure(measureSpec, measureSpec)

    }

    fun selectAll() {
        post(Runnable {
            var active = true
            outer@ for (line in mLines) {
                val items = line.items
                for (item in items) {
                    if (item.view.isSelected) active = false
                    break@outer
                }
            }
            for (line in mLines) {
                for (item in line.items) {
                    item.view.isSelected = active
                }
            }
            checkURL()
            requestLayout()
        })
    }

    fun cancelSelect() {
        post(Runnable {
            for (line in mLines) {
                val items = line.items
                for (item in items) {
                    item.view.isSelected = false
                }
            }
            requestLayout()
        })
    }

    fun invertSelection() {
        post(Runnable {
            for (line in mLines) {
                val items = line.items
                for (item in items) {
                    item.view.isSelected = !item.isSelected
                }
            }
            checkURL()
            requestLayout()
        })
    }

    fun getSelectedContentIndexes(): List<Int> {
        val indexes = ArrayList<Int>()
        for (line in mLines) {
            for (item in line.items) {
                if (item.isSelected) {
                    indexes.add(item.contentIndex)
                }
            }
        }
        return indexes.sorted()
    }

    fun setSelectedContentIndexes(indexes: Set<Int>) {
        post {
            for (line in mLines) {
                for (item in line.items) {
                    item.view.isSelected = indexes.contains(item.contentIndex)
                }
            }
            checkURL()
            requestLayout()
        }
    }

    fun makeSelectedText(): String {
        val builder = StringBuilder()
        val length = mLines.size
        for (i in 0..<length) {
            val line = mLines[i]
            var containEnter = false
            if (i < length - 1) {
                val nextLine = mLines.get(i + 1)
                val thisLineLastIndex = line.items.get(line.items.size - 1).contentIndex
                val nextLineFirstIndex = nextLine.items.get(nextLine.items.size - 1).contentIndex
                for (j in thisLineLastIndex..<nextLineFirstIndex) {
                    if (mSectionIndex.contains(j)) {
                        containEnter = true
                        break
                    }
                }
            }
            builder.append(line.selectedText)
            if (containEnter && config.showSection) {
                builder.append("\n")
            }
        }
        return builder.toString().replace("[\\n]+".toRegex(), "\n").trim { it <= ' ' }
    }

    companion object {
        const val TAG: String = "BigBangLayout"
        const val ENTER: String = "_Enter_"
        const val ENTER_SYMBOL: String = "\n"
        const val TAB: String = "_Tab_"
    }
}
