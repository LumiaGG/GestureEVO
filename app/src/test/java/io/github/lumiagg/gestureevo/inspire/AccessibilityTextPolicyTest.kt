package io.github.lumiagg.gestureevo.inspire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTextPolicyTest {
    @Test fun flutterVideoTitleAndPlainTextStillUseTheirDescription() {
        assertEquals("视频标题", accessibilityNodeText(null, "视频标题", VIEW, isLongClickable = true))
        assertEquals("正文", accessibilityNodeText(null, "正文", VIEW))
    }

    @Test fun spokenButtonAndImageDescriptionsAreNotExtractedAsText() {
        assertNull(accessibilityNodeText(null, "显示菜单", "android.widget.Button", isClickable = true))
        assertNull(accessibilityNodeText(null, "认证个人\n作者\n100万粉丝", "android.widget.ImageView"))
        assertNull(accessibilityNodeText(null, "返回", "android.widget.ImageButton"))
    }

    @Test fun genericClickableControlsDoNotSupplyTextDescriptions() {
        assertNull(accessibilityNodeText(null, "投币\n1.2万", VIEW, isClickable = true))
        assertNull(accessibilityNodeText(null, "评论\n第2个标签，共2个", VIEW, isClickable = true))
        assertNull(accessibilityNodeText(null, "已勾选", VIEW, isCheckable = true))
        assertNull(accessibilityNodeText(null, "向下滚动", VIEW, isScrollable = true))
    }

    @Test fun realButtonTextIsKeptWithoutItsSpokenInstructions() {
        assertEquals(
            "关注",
            accessibilityNodeText("关注", "双击关注这个用户", "android.widget.Button", isClickable = true),
        )
    }

    @Test fun selectableDescriptionsAndLongPressableTextLinksAreKept() {
        assertEquals("链接文字", accessibilityNodeText(null, "链接文字", VIEW, isClickable = true, hasTextSelection = true))
        assertEquals("#话题", accessibilityNodeText(null, "#话题", VIEW, isClickable = true, isLongClickable = true))
    }

    @Test fun textIsNotRemovedUsingAWordBlacklist() {
        assertEquals("播放", accessibilityNodeText(null, "播放", VIEW))
        assertEquals("点赞", accessibilityNodeText("点赞", null, "android.widget.TextView"))
        assertEquals("显示菜单", accessibilityNodeText("显示菜单", null, "android.widget.Button"))
    }

    @Test fun absentAndBlankDescriptionsDoNotCreateCandidates() {
        assertNull(accessibilityNodeText(null, null, VIEW))
        assertNull(accessibilityNodeText(null, " \n ", VIEW))
    }

    @Test fun actualTitleSelectionDoesNotIncludeTheThreePixelAuthorOverlap() {
        assertFalse(hasSubstantialAccessibilityOverlap(1135, 1311, 1020, 1138))
        assertTrue(hasSubstantialAccessibilityOverlap(1135, 1311, 1165, 1316))
        assertTrue(hasSubstantialAccessibilityOverlap(91, 1247, 39, 1221))
    }

    @Test fun largerTitleSelectionDoesNotIncludeTheEdgeOfActionIcons() {
        assertFalse(hasSubstantialAccessibilityOverlap(1005, 1460, 1424, 1581))
        assertTrue(hasSubstantialAccessibilityOverlap(1005, 1460, 1165, 1316))
    }

    @Test fun partialSelectionsInsideLargeNodesAreNotRejectedForLackOfFullContainment() {
        assertTrue(hasSubstantialAccessibilityOverlap(1200, 1250, 1100, 1400))
        assertTrue(hasSubstantialAccessibilityOverlap(100, 200, 0, 1000))
    }

    @Test fun disjointEmptyAndMerelyTouchingBoundsAreRejected() {
        assertFalse(hasSubstantialAccessibilityOverlap(100, 200, 0, 100))
        assertFalse(hasSubstantialAccessibilityOverlap(100, 200, 300, 400))
        assertFalse(hasSubstantialAccessibilityOverlap(100, 100, 0, 200))
    }

    @Test fun offscreenCharacterLocationsDoNotExpandTheSelectionToTheWholeText() {
        val locations = arrayOf("outside", "selected", null, "outside")
        assertEquals("乙", selectAccessibilityCharacters("甲乙丙丁", locations) { it == "selected" })
    }

    @Test fun noSelectedGlyphsIsAnEmptyResultRatherThanUnsupportedData() {
        assertEquals("", selectAccessibilityCharacters("甲乙", arrayOf<String?>(null, null)) { true })
        assertEquals("", selectAccessibilityCharacters("甲乙", arrayOf(false, false)) { it })
    }

    @Test fun truncatedCharacterLocationsCannotBeUsedForPreciseSelection() {
        assertNull(selectAccessibilityCharacters("甲乙丙", arrayOf(true, true)) { it })
    }

    @Test fun lineBreaksWithoutLocationsArePreservedAfterSelectedText() {
        assertEquals("甲\n乙", selectAccessibilityCharacters("甲\n乙", arrayOf(true, null, true)) { it })
        assertEquals("甲\r\n乙", selectAccessibilityCharacters("甲\r\n乙", arrayOf(true, null, null, true)) { it })
    }

    @Test fun unselectedTextDoesNotIntroduceAnExtraLineBreak() {
        assertEquals("乙", selectAccessibilityCharacters("甲\n乙", arrayOf(false, null, true)) { it })
        assertEquals("甲", selectAccessibilityCharacters("甲乙\n", arrayOf(true, false, null)) { it })
    }

    private companion object {
        const val VIEW = "android.view.View"
    }
}
