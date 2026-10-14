package io.github.lumiagg.gestureevo.settings.guide

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideHintGeometryTest {
    @Test fun screenEdgeHintsRemainVisible() {
        assertEquals(0, guideHintTop(-20, 40, 600))
        assertEquals(0, guideHintTop(0, 40, 600))
        assertEquals(280, guideHintTop(300, 40, 600))
        assertEquals(560, guideHintTop(600, 40, 600))
        assertEquals(560, guideHintTop(640, 40, 600))
    }
    @Test fun smallWindowsNeverProduceNegativeMargins() {
        assertEquals(0, guideHintTop(20, 60, 40))
        assertEquals(0, guideHintTop(0, 60, 0))
    }
}
