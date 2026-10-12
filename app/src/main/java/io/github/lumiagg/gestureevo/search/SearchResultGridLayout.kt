package io.github.lumiagg.gestureevo.search

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.SearchResultGridItemBinding
import io.github.lumiagg.gestureevo.search.config.SearchResult
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils

/** Presentation cache only: rebuilt when results or the available width change. */
internal class SearchResultGridLayout(
    private val context: Context,
    private val recycler: RecyclerView
) {
    private val resources = context.resources
    private val paint = TextPaint(TextView(context).paint).apply {
        textSize = resources.getDimension(R.dimen.search_grid_text_size)
    }
    private val cardMargins = 2 * size(R.dimen.search_grid_card_margin)
    private val paddingStart = size(R.dimen.search_grid_padding_start)
    private val menuSize = size(R.dimen.search_grid_menu_size)
    private val iconSize = size(R.dimen.search_grid_icon_size)
    private val iconGap = size(R.dimen.search_grid_icon_gap)
    private var results: List<SearchResult> = emptyList()
    private var measuredWidth = -1
    private var spans = IntArray(0)
    private val refreshWidth = Runnable {
        if (rebuild()) {
            recycler.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
            recycler.requestLayout()
        }
    }

    val spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int = spans.getOrElse(position) { 2 }
    }

    init {
        recycler.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                recycler.removeCallbacks(refreshWidth)
                recycler.post(refreshWidth)
            }
        }
    }

    fun updateResults(newResults: List<SearchResult>) {
        results = newResults
        rebuild(force = true)
    }

    private fun rebuild(force: Boolean = false): Boolean {
        val width = ((recycler.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) -
            recycler.paddingLeft - recycler.paddingRight).coerceAtLeast(0)
        if (!force && width == measuredWidth) return false
        measuredWidth = width
        val halfWidth = width / 2
        spans = compactResultSpans(BooleanArray(results.size) { index ->
            needsFullWidth(results[index], halfWidth)
        })
        spanSizeLookup.invalidateSpanIndexCache()
        spanSizeLookup.invalidateSpanGroupIndexCache()
        return true
    }

    private fun needsFullWidth(result: SearchResult, halfWidth: Int): Boolean {
        val menuWidth = if (result is SearchResult.ShortcutResult && result.shortcut.uri.isNullOrBlank()) 0 else menuSize
        val textWidth = halfWidth - cardMargins - paddingStart - menuWidth - iconSize - iconGap
        return when (result) {
            is SearchResult.HistoryResult -> !fitsTwoLines(result.text, textWidth)
            is SearchResult.PhoneResult -> !fitsOneLine(result.phone, textWidth)
            is SearchResult.AppResult -> !fitsTwoLines(result.app.name, textWidth)
            is SearchResult.ShortcutResult -> !fitsTwoLines(result.shortcut.name, textWidth)
            is SearchResult.GestureActionResult -> !fitsTwoLines(result.actionType.showName(context), textWidth)
            is SearchResult.AppSettingsResult -> !fitsTwoLines(context.getString(R.string.quick_search_app_settings), textWidth)
            else -> true
        }
    }

    private fun fitsOneLine(text: String, width: Int): Boolean = width > 0 &&
        '\n' !in text && '\r' !in text && paint.breakText(text, true, width.toFloat(), null) == text.length

    // LineBreaker integer constants are inlined and match the API 23 Layout constants.
    @SuppressLint("InlinedApi")
    private fun fitsTwoLines(text: String, width: Int): Boolean {
        if (width <= 0) return false
        if (fitsOneLine(text, width)) return true
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setIncludePad(false)
            .setMaxLines(3)
            .build()
        return layout.lineCount <= 2 && layout.getLineEnd(layout.lineCount - 1) == text.length
    }

    // LineBreaker integer constants are inlined and match the API 23 Layout constants.
    @SuppressLint("InlinedApi")
    fun bind(itemView: View, result: SearchResult, position: Int) {
        if (result is SearchResult.PasteImageResult) return
        val binding = SearchResultGridItemBinding.bind(itemView)
        binding.icon.updateLayoutParams { width = iconSize; height = iconSize }
        binding.resultTextGroup.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = iconGap
        }
        binding.resultContainer.minimumHeight = size(R.dimen.search_grid_min_height)
        binding.resultText.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(
            if (result is SearchResult.UrlResult) R.dimen.search_grid_url_text_size else R.dimen.search_grid_text_size
        ))
        binding.resultText.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        binding.resultText.maxLines = if (
            result is SearchResult.ContactResult ||
            (result is SearchResult.PhoneResult &&
                spanSizeLookup.getSpanSize(position) == 1)
        ) 1 else 2
        binding.resultText.setTextColor(
            if (result is SearchResult.ClearHistoryAction) {
                DynamicColorUtils.onSurfaceVariantColor
            } else {
                DynamicColorUtils.onSurfaceColor
            }
        )
        binding.resultSubtitle.isVisible = result is SearchResult.ContactResult
        if (result is SearchResult.ContactResult) {
            binding.resultText.text = result.contact.name
            binding.resultSubtitle.text = result.contact.phoneNumber
        } else {
            binding.resultSubtitle.text = null
        }
    }

    private fun size(id: Int): Int = resources.getDimensionPixelSize(id)
}
