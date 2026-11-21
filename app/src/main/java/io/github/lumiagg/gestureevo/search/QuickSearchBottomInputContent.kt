package io.github.lumiagg.gestureevo.search

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import io.github.lumiagg.gestureevo.R

/** Keeps the input and engine controls inside the card while the results resize. */
internal class QuickSearchBottomInputContent(context: Context, attrs: AttributeSet) :
    ViewGroup(context, attrs) {
    private lateinit var dragBar: View
    private lateinit var results: View
    private lateinit var input: View
    private lateinit var engines: View

    override fun onFinishInflate() {
        super.onFinishInflate()
        dragBar = findViewById(R.id.drag_bar_container)
        results = findViewById(R.id.result_panel)
        input = findViewById(R.id.search_input_row)
        engines = findViewById(R.id.bottom_engine_card)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val childWidth = MeasureSpec.makeMeasureSpec(
            (width - paddingLeft - paddingRight).coerceAtLeast(0), MeasureSpec.EXACTLY,
        )
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val verticalPadding = paddingTop + paddingBottom
        val dragHeight = measureFixedHeight(dragBar, childWidth)
        val inputHeight = measureFixedHeight(input, childWidth)
        val fixedHeight = verticalPadding + dragHeight + inputHeight
        val flexibleHeight = if (heightMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (MeasureSpec.getSize(heightMeasureSpec) - fixedHeight).coerceAtLeast(0)
        }

        // During keyboard changes the result animator may still request a height
        // from the previous viewport. Allocate the engine card before honoring it.
        val engineHeight = if (engines.visibility != GONE) {
            val engineSpec = if (heightMode == MeasureSpec.UNSPECIFIED) {
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            } else {
                MeasureSpec.makeMeasureSpec(flexibleHeight, MeasureSpec.AT_MOST)
            }
            engines.measure(childWidth, engineSpec)
            engines.measuredHeight
        } else {
            0
        }
        val resultHeight = measureFixedHeight(results, childWidth, flexibleHeight - engineHeight)
        setMeasuredDimension(width, resolveSize(fixedHeight + engineHeight + resultHeight, heightMeasureSpec))
    }

    private fun measureFixedHeight(child: View, widthSpec: Int, limit: Int = Int.MAX_VALUE): Int {
        if (child.visibility == GONE) return 0
        val childHeight = child.layoutParams.height.coerceIn(0, limit.coerceAtLeast(0))
        child.measure(widthSpec, MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY))
        return child.measuredHeight
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var childTop = paddingTop
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val childBottom = childTop + child.measuredHeight
            child.layout(paddingLeft, childTop, paddingLeft + child.measuredWidth, childBottom)
            childTop = childBottom
        }
    }
}
