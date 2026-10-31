package io.github.lumiagg.gestureevo.inspire

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityTextFormatterTest {
    @Test fun fragmentsOnTheSameRowAreJoinedWithoutAnExtraBreak() {
        assertEquals("视频标题", format(fragment("视频", 0, 20), fragment("标题", 1, 21)))
    }

    @Test fun separateRowsPreserveTheirLineStructure() {
        assertEquals("标题\n正文", format(fragment("标题", 0, 20), fragment("正文", 30, 50)))
    }

    @Test fun existingCrLfIsNotDuplicatedBetweenRows() {
        assertEquals("标题\r\n正文", format(fragment("标题\r\n", 0, 20), fragment("正文", 30, 50)))
        assertEquals("标题\n正文", format(fragment("标题", 0, 20), fragment("\n正文", 30, 50)))
    }

    @Test fun traversalOrderIsStableAndEmptyFragmentsDoNotDisturbIt() {
        assertEquals("第一行\n第二行", format(fragment("第一行", 0, 20), fragment("", 0, 0), fragment("第二行", 30, 50)))
        assertEquals("", joinAccessibilityTextInReadingOrder(emptyList()))
    }

    private fun fragment(text: String, top: Int, bottom: Int) = AccessibilityTextFragment(text, top, bottom)
    private fun format(vararg fragments: AccessibilityTextFragment) = joinAccessibilityTextInReadingOrder(fragments.toList())
}
