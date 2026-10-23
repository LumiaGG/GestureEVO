package io.github.lumiagg.gestureevo.utils.icon

import org.junit.Assert.assertEquals
import org.junit.Test

class IconEditGeometryTest {
    @Test fun fitsPortraitAndLandscapeWithoutStretching() {
        val landscape = geometry(1000, 500)
        assertEquals(0f, landscape.left, 0.001f)
        assertEquals(128f, landscape.top, 0.001f)
        assertEquals(512f, landscape.width, 0.001f)
        assertEquals(256f, landscape.height, 0.001f)
        val portrait = geometry(500, 1000)
        assertEquals(128f, portrait.left, 0.001f)
        assertEquals(0f, portrait.top, 0.001f)
        assertEquals(256f, portrait.width, 0.001f)
        assertEquals(512f, portrait.height, 0.001f)
    }

    @Test fun zoomAndPositionAllowCroppingAtOutputEdges() {
        val zoomed = iconEditGeometry(512, 512, 512, 512, 2f, 0f, 0.5f, -0.5f)
        assertEquals(-128f, zoomed.left, 0.001f)
        assertEquals(-384f, zoomed.top, 0.001f)
        assertEquals(1024f, zoomed.width, 0.001f)
    }

    @Test fun cornersFollowImageBoundsIncludingLetterboxedImages() {
        val rounded = iconEditGeometry(1000, 500, 512, 512, 0.5f, 1f, 0f, 0f)
        assertEquals(64f, rounded.cornerRadius, 0.001f)
        assertEquals(128f, rounded.left, 0.001f)
        assertEquals(192f, rounded.top, 0.001f)
    }

    @Test fun previewAndExportRemainProportionalAcrossAllAdjustments() {
        listOf(0.25f, 1f, 3f).forEach { scale ->
            val preview = iconEditGeometry(800, 1200, 160, 160, scale, 0.7f, -0.3f, 0.8f)
            val export = iconEditGeometry(800, 1200, 512, 512, scale, 0.7f, -0.3f, 0.8f)
            val ratio = 512f / 160f
            assertEquals(preview.left * ratio, export.left, 0.001f)
            assertEquals(preview.top * ratio, export.top, 0.001f)
            assertEquals(preview.width * ratio, export.width, 0.001f)
            assertEquals(preview.height * ratio, export.height, 0.001f)
            assertEquals(preview.cornerRadius * ratio, export.cornerRadius, 0.001f)
        }
    }

    private fun geometry(width: Int, height: Int) =
        iconEditGeometry(width, height, 512, 512, 1f, 0f, 0f, 0f)
}
