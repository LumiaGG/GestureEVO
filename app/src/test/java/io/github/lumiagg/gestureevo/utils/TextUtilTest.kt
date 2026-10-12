package io.github.lumiagg.gestureevo.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TextUtilTest {
    @Test fun existingTokenizationAndSimilarityExamplesRemainTheSame() {
        assertEquals(mapOf("wifi" to 2, "设置" to 1), TextUtil.createVector("WiFi wifi\t设置"))
        assertEquals(1.0, TextUtil.calculateSimilarity("WiFi 设置", "设置 WIFI"), 1e-12)
        assertEquals(0.0, TextUtil.calculateSimilarity("wifi", "bluetooth"), 1e-12)
        assertEquals(2.0 / kotlin.math.sqrt(5.0), TextUtil.calculateSimilarity("wifi wifi 设置", "wifi"), 1e-12)
    }

    @Test fun emptyVectorsDoNotReturnNan() {
        assertEquals(0.0, TextUtil.calculateCosineSimilarity(emptyMap(), mapOf("word" to 1)), 0.0)
        assertEquals(0.0, TextUtil.calculateCosineSimilarity(emptyMap(), emptyMap()), 0.0)
    }

    @Test fun largeFrequencyCountsCannotOverflowIntegerMultiplication() {
        assertEquals(1.0, TextUtil.calculateCosineSimilarity(mapOf("word" to 100000), mapOf("word" to 100000)), 1e-12)
    }

    @Test fun searchNormalizationDoesNotChangeWithTheDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(mapOf("inspire" to 1), TextUtil.createVector("INSPIRE"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
