package io.github.lumiagg.gestureevo.inspire.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.children
import androidx.core.widget.NestedScrollView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lumiagg.gestureevo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BigBangSelectionScrollTest {
    @Test fun deselectingUpperTokensKeepsViewportWithLowerTokensStillSelected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_GestureEVO)
        instrumentation.runOnMainSync {
            val scroll = NestedScrollView(context)
            val tokens = BigBangView(context, null).apply {
                repeat(30) { index ->
                    addTextItem("词语 $index", isSelected = true)
                    addTextItem(BigBangView.ENTER)
                }
            }
            scroll.addView(tokens, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val density = context.resources.displayMetrics.density
            fun layout() {
                val width = (260 * density).toInt()
                val height = (140 * density).toInt()
                scroll.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                scroll.layout(0, 0, width, height)
            }
            layout()
            val words = tokens.children.filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.toList()
            scroll.scrollTo(0, tokens.height)
            val bottomScrollY = scroll.scrollY
            scroll.scrollTo(0, words[3].top)
            val upperScrollY = scroll.scrollY
            assertTrue(upperScrollY in 1 until bottomScrollY)
            tokens.onFunctionViewReveal = { bottom -> scroll.scrollTo(0, bottom - scroll.height) }

            val tapTime = SystemClock.uptimeMillis()
            sendTouch(tokens, words[3], MotionEvent.ACTION_DOWN, tapTime)
            sendTouch(tokens, words[3], MotionEvent.ACTION_UP, tapTime)
            layout()
            assertTrue(!words[3].isSelected)
            assertEquals(upperScrollY, scroll.scrollY)

            val dragTime = SystemClock.uptimeMillis()
            sendTouch(tokens, words[4], MotionEvent.ACTION_DOWN, dragTime)
            sendTouch(tokens, words[5], MotionEvent.ACTION_MOVE, dragTime)
            sendTouch(tokens, words[5], MotionEvent.ACTION_UP, dragTime)
            layout()
            tokens.requestLayout()
            layout()
            assertTrue(!words[4].isSelected && !words[5].isSelected)
            assertTrue(words.last().isSelected)
            assertEquals(upperScrollY, scroll.scrollY)
        }
    }

    private fun sendTouch(tokens: BigBangView, word: TextView, action: Int, downTime: Long) {
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action,
            (word.left + word.right) / 2f, (word.top + word.bottom) / 2f, 0,
        )
        try {
            tokens.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
