package io.github.lumiagg.gestureevo.search

import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSearchEnginePagerTest {
    @Test fun compactViewportUsesMorePagesWithoutClippingConfiguredRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_GestureEVO)
        instrumentation.runOnMainSync {
            val pager = QuickSearchEnginePager(context)
            pager.setIconLoader { _, _, _ -> }
            pager.configure(QuickSearchEnginePager.Config(5, 2, true, false, false))
            val engines = List(12) { index -> SearchEngine().apply { name = "Engine $index" } }
            pager.submitList(engines)
            val width = (240 * context.resources.displayMetrics.density).toInt()
            fun layout() {
                pager.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                pager.layout(0, 0, width, pager.measuredHeight)
            }
            layout()
            val naturalHeight = pager.height
            val compactHeight = naturalHeight * 2 / 5
            pager.scrollTo(width, 0)
            pager.limitHeight(compactHeight)
            layout()
            assertTrue(pager.height in 1..compactHeight)
            assertEquals(2 * width, pager.scrollX)
            assertEquals(engines, pager.currentOrder)
            for (index in engines.indices) {
                val cell = pager.getChildAt(index)
                assertTrue(cell.bottom <= pager.height)
                assertEquals(index / 4 * width, cell.left / width * width)
            }
            pager.limitHeight(naturalHeight)
            layout()
            assertEquals(naturalHeight, pager.height)
            assertEquals(0, pager.scrollX)
        }
    }
}
