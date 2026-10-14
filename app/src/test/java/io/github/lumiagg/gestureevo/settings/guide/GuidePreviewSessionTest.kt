package io.github.lumiagg.gestureevo.settings.guide

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GuidePreviewSessionTest {
    @Test
    fun statusRefreshKeepsTheCompletePracticeRequest() {
        val draft = QuickSetupConfig.default()
        val target = draft.copy(wheelItems = draft.wheelItems.take(1))
        GuidePreviewSession.show("guide", target, QuickGesture.SWIPE_IN, "practice", wheelSelection = true)
        val request = GuidePreviewSession.current.value
        GuidePreviewSession.refresh("guide", draft.copy(verticalPercent = 100), GuideWheelMode.HIDDEN)
        assertSame(request, GuidePreviewSession.current.value)
    }

    @Test
    fun refreshUpdatesTheDraftAfterPracticeEnds() {
        GuidePreviewSession.show("guide", QuickSetupConfig.default())
        val draft = QuickSetupConfig.default().copy(verticalPercent = 100)
        GuidePreviewSession.refresh("guide", draft, GuideWheelMode.PREVIEW)
        assertEquals(GuidePreviewRequest("guide", draft, wheelMode = GuideWheelMode.PREVIEW),
            GuidePreviewSession.current.value)
    }

    @Test
    fun wheelIsHiddenOutsideEditingAdjustmentsAndSelection() {
        val config = QuickSetupConfig.default()
        assertFalse(GuidePreviewRequest("guide", config).showsWheel)
        assertFalse(GuidePreviewRequest("guide", config, QuickGesture.TAP, "tap").showsWheel)
        assertTrue(GuidePreviewRequest("guide", config, wheelMode = GuideWheelMode.PREVIEW).showsWheel)
        assertTrue(GuidePreviewRequest("guide", config, wheelSelection = true).showsWheel)
    }

    @Test
    fun oldActivityCannotClearNewActivityPreview() {
        val config = QuickSetupConfig.default()
        GuidePreviewSession.show("old", config)
        GuidePreviewSession.show("new", config.copy(verticalPercent = 70))
        GuidePreviewSession.clear("old")
        assertEquals("new", GuidePreviewSession.current.value?.owner)
        assertEquals(70, GuidePreviewSession.current.value?.config?.verticalPercent)
        GuidePreviewSession.clear("new")
        assertNull(GuidePreviewSession.current.value)
    }

    @Test
    fun latestDraftRemainsAvailableBeforeServiceConnects() {
        GuidePreviewSession.show("guide", QuickSetupConfig.default())
        val draft = QuickSetupConfig.default().copy(triggerHeightDp = 160)
        GuidePreviewSession.show("guide", draft, QuickGesture.SWIPE_IN, "wheel")
        assertEquals(GuidePreviewRequest("guide", draft, QuickGesture.SWIPE_IN, "wheel"), GuidePreviewSession.current.value)
    }

    @Test
    fun staleOrMismatchedGestureCannotCompleteCurrentPractice() = runBlocking {
        val config = QuickSetupConfig.default()
        GuidePreviewSession.show("guide", config, QuickGesture.TAP, "old")
        val oldRequest = requireNotNull(GuidePreviewSession.current.value)
        GuidePreviewSession.show("guide", config, QuickGesture.SWIPE_IN, "new")
        val currentRequest = requireNotNull(GuidePreviewSession.current.value)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1000) { GuidePreviewSession.gestures.first() }
        }
        GuidePreviewSession.reportGesture(oldRequest, QuickGesture.TAP)
        GuidePreviewSession.reportGesture(currentRequest, QuickGesture.TAP)
        GuidePreviewSession.reportGesture(currentRequest, QuickGesture.SWIPE_IN)
        assertEquals(GuidePreviewGesture("guide", "new", QuickGesture.SWIPE_IN), result.await())
    }

    @Test
    fun wheelResultRequiresAnActiveSelectionAndARealItemIndex() = runBlocking {
        val config = QuickSetupConfig.default()
        GuidePreviewSession.show("guide", config, QuickGesture.SWIPE_IN, "wheel")
        val opening = requireNotNull(GuidePreviewSession.current.value)
        GuidePreviewSession.show("guide", config, QuickGesture.SWIPE_IN, "wheel", wheelSelection = true)
        val selecting = requireNotNull(GuidePreviewSession.current.value)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1000) { GuidePreviewSession.gestures.first() }
        }
        GuidePreviewSession.reportWheelSelection(opening, 0)
        GuidePreviewSession.reportWheelSelection(selecting, -1)
        GuidePreviewSession.reportWheelSelection(selecting, config.wheelItems.size)
        GuidePreviewSession.reportWheelSelection(selecting, 2)
        assertEquals(GuidePreviewGesture("guide", "wheel", QuickGesture.SWIPE_IN, 2), result.await())
    }
}
