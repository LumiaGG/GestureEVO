package io.github.lumiagg.gestureevo.inspire

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityTextHierarchyTest {
    @Test fun cardSummaryIsReplacedByItsSeparateTextNodes() {
        val entries = listOf(
            entry(null, "标题，正文，按钮"),
            entry(0, "标题"),
            entry(0, "正文"),
            entry(0, "按钮"),
        )
        assertEquals(listOf(1, 2, 3), preferredAccessibilityTextIndices(entries))
    }

    @Test fun selectionIsAppliedAfterRemovingSummaries() {
        val entries = listOf(
            entry(null, "选中的文字\n框外的文字"),
            entry(0, "选中的文字"),
            entry(0, "框外的文字"),
        )
        // Both the card and its first child intersect the selection; the second
        // child must still prevent its text leaking through the card summary.
        val intersecting = setOf(0, 1)
        assertEquals(
            listOf(1),
            preferredAccessibilityTextIndices(entries).filter { it in intersecting },
        )
        // A selection in the card's padding must not resurrect the whole card.
        assertEquals(emptyList<Int>(), preferredAccessibilityTextIndices(entries).filter { it == 0 })
    }

    @Test fun structuralContainersDoNotHideGrandchildren() {
        val entries = listOf(
            entry(null, "第一行 第二行"),
            entry(0, null, readable = false),
            entry(1, "第一行"),
            entry(1, "第二行"),
        )
        assertEquals(listOf(2, 3), preferredAccessibilityTextIndices(entries))
    }

    @Test fun anIndependentParentLabelIsPreserved() {
        val entries = listOf(entry(null, "账户"), entry(0, "100元"))
        assertEquals(listOf(0, 1), preferredAccessibilityTextIndices(entries))
    }

    @Test fun invisibleChildrenCannotSuppressVisibleText() {
        val entries = listOf(entry(null, "正文"), entry(0, "正文", readable = false))
        assertEquals(listOf(0), preferredAccessibilityTextIndices(entries))
    }

    @Test fun identicalTextInDifferentBranchesIsNotDeduplicated() {
        val entries = listOf(
            entry(null, null),
            entry(0, "确定"),
            entry(0, "确定"),
            entry(null, "确定"),
        )
        assertEquals(listOf(1, 2, 3), preferredAccessibilityTextIndices(entries))
    }

    @Test fun aMergedLeafIsKeptEvenWithoutChildren() {
        val entries = listOf(entry(null, "应用只暴露这一整段文字"))
        assertEquals(listOf(0), preferredAccessibilityTextIndices(entries))
    }

    @Test fun duplicateNestedLabelsPreferTheDeepestVisibleNode() {
        val entries = listOf(
            entry(null, "第一行\n第二行"),
            entry(0, "第一行 第二行"),
            entry(1, "第一行"),
            entry(1, "第二行"),
        )
        assertEquals(listOf(2, 3), preferredAccessibilityTextIndices(entries))
    }

    @Test fun selectingVideoTitleExcludesStatisticsAttachedToTheEnclosingCard() {
        // PiliPlus exposes distinct statistics on a large card, not as repeated
        // text in its title child. String-based deduplication cannot remove it.
        val entries = listOf(
            entry(null, "播放\n10万\n弹幕\n1000\n昨天"),
            entry(0, "作者\n100万粉丝"),
            entry(0, "视频标题"),
        )
        assertEquals(listOf(2), selectedAccessibilityTextIndices(entries, setOf(0, 2), setOf(2)))
    }

    @Test fun fullySelectingTheCardKeepsItsIndependentStatistics() {
        val entries = listOf(entry(null, "播放10万"), entry(0, "标题"))
        assertEquals(listOf(0, 1), selectedAccessibilityTextIndices(entries, setOf(0, 1), setOf(0, 1)))
    }

    @Test fun statisticsRemainAvailableWhenNoChildIntersectsTheSelection() {
        val entries = listOf(entry(null, "播放10万"), entry(0, "标题"))
        assertEquals(listOf(0), selectedAccessibilityTextIndices(entries, setOf(0), emptySet()))
    }

    @Test fun selectedGrandchildSuppressesCoarseTextThroughAnEmptyContainer() {
        val entries = listOf(
            entry(null, "播放10万"),
            entry(0, null),
            entry(1, "标题"),
        )
        assertEquals(listOf(2), selectedAccessibilityTextIndices(entries, setOf(0, 1, 2), setOf(2)))
    }

    @Test fun aChildSubstringDoesNotEraseTheParentsIndependentPrefix() {
        val entries = listOf(entry(null, "账户余额100元"), entry(0, "100元"))
        assertEquals(listOf(0, 1), preferredAccessibilityTextIndices(entries))
        assertEquals(listOf(0, 1), selectedAccessibilityTextIndices(entries, setOf(0, 1), setOf(0, 1)))
        assertEquals(listOf(1), selectedAccessibilityTextIndices(entries, setOf(0, 1), setOf(1)))
    }

    @Test fun partialDescendantCoverageDoesNotEraseUnrepresentedParentText() {
        val entries = listOf(
            entry(null, "标题，简介，独立信息"),
            entry(0, "标题"),
            entry(0, "简介"),
        )
        assertEquals(listOf(0, 1, 2), preferredAccessibilityTextIndices(entries))
    }

    @Test fun exactDuplicateIsRemovedEvenWithAnIndependentSibling() {
        val entries = listOf(entry(null, "标题"), entry(0, "标题"), entry(0, "徽标"))
        assertEquals(listOf(1, 2), preferredAccessibilityTextIndices(entries))
    }

    @Test fun punctuationInASingleParentLabelIsNotMistakenForASummarySeparator() {
        val entries = listOf(entry(null, "你好，世界"), entry(0, "你好世界"))
        assertEquals(listOf(0, 1), preferredAccessibilityTextIndices(entries))
    }

    private fun entry(parent: Int?, text: String?, readable: Boolean = true) =
        AccessibilityTextEntry(parent, text, readable)
}
