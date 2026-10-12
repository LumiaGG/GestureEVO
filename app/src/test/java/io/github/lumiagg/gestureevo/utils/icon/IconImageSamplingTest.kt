package io.github.lumiagg.gestureevo.utils.icon

import org.junit.Assert.assertEquals
import org.junit.Test

class IconImageSamplingTest {
    @Test fun boundsSamplingHandlesLandscapePortraitAndSmallImages() {
        assertEquals(6, imageSampleSize(4096, 2048, 512, 512))
        assertEquals(6, imageSampleSize(1024, 4096, 512, 512))
        assertEquals(1, imageSampleSize(32, 64, 512, 512))
    }

    @Test fun samplingPreservesOriginalToleranceWithoutAnExtraResize() {
        assertEquals(3, imageSampleSize(1500, 1000, 512, 512))
        assertEquals(2, imageSampleSize(900, 600, 512, 512))
        assertEquals(1, imageSampleSize(700, 512, 512, 512))
    }

    @Test fun samplingMatchesThePreviousImporterAcrossImageSizes() {
        for (width in listOf(1, 32, 512, 700, 717, 900, 1024, 1500, 4096)) {
            for (height in listOf(1, 64, 512, 1000, 4096)) {
                for (limit in listOf(1, 100, 512)) {
                    var expected = 1
                    while (width / expected > limit * 1.4 || height / expected > limit * 1.4) expected++
                    assertEquals("${width}x$height target=$limit", expected, imageSampleSize(width, height, limit, limit))
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidTargetDimensionsAreRejected() {
        imageSampleSize(1024, 1024, 0, 512)
    }
}
