package io.github.lumiagg.gestureevo.search

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.BindingAdapter
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.search.config.SearchResult

class SearchResultRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {
    private var cachedRowHeight = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightSpec)
        if (heightMode == MeasureSpec.EXACTLY) {
            super.onMeasure(widthSpec, heightSpec)
            return
        }
        val limit = when (heightMode) {
            MeasureSpec.AT_MOST -> MeasureSpec.getSize(heightSpec)
            else -> resources.displayMetrics.heightPixels
        }.coerceAtLeast(0)
        val height = heightForCount(adapter?.itemCount ?: 0).coerceAtMost(limit)
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        val height = child.height.takeIf { it > 0 } ?: child.measuredHeight
        if (height > 0) cachedRowHeight = height
    }

    fun heightForCount(itemCount: Int): Int {
        if (itemCount <= 0) return paddingTop + paddingBottom
        val manager = layoutManager as? GridLayoutManager
        val spanCount = manager?.spanCount ?: 1
        val normalHeight = resources.getDimensionPixelSize(R.dimen.search_result_row_min_height)
        val imageRowHeight = resources.getDimensionPixelSize(R.dimen.search_result_image_row_height)
        val brvModels = (adapter as? BindingAdapter)?.models

        var totalHeight = 0
        var filled = 0
        var currentRowHeight = normalHeight
        for (index in 0 until itemCount) {
            val span = manager?.spanSizeLookup?.getSpanSize(index)?.coerceIn(1, spanCount) ?: 1
            val model = brvModels?.getOrNull(index)
            val itemHeight = if (model is SearchResult.PasteImageResult) imageRowHeight else normalHeight
            if (filled + span > spanCount) {
                totalHeight += currentRowHeight
                filled = span
                currentRowHeight = itemHeight
            } else {
                filled += span
                currentRowHeight = maxOf(currentRowHeight, itemHeight)
            }
            if (filled >= spanCount) {
                totalHeight += currentRowHeight
                filled = 0
                currentRowHeight = normalHeight
            }
        }
        if (filled > 0) totalHeight += currentRowHeight
        return paddingTop + paddingBottom + totalHeight
    }
}
