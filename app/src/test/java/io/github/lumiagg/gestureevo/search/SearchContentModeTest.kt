package io.github.lumiagg.gestureevo.search

import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchContentMode
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchContentModeTest {
    @Test fun dualEnginesUseTheirPreferenceOnlyWhenBothContentTypesExist() {
        for (type in EngineType.entries.filter { it != EngineType.SHARE_IMAGE_TO_APP }) {
            for (preference in SearchContentMode.entries) {
                val engine = SearchEngine().apply {
                    engineType = type
                    imageShareEnabled = true
                    mixedContentMode = preference
                }
                assertEquals("$type / $preference", preference, engine.resolveContentMode(true, true))
                assertEquals(SearchContentMode.TEXT, engine.resolveContentMode(true, false))
                assertEquals(SearchContentMode.IMAGE, engine.resolveContentMode(false, true))
                assertEquals(SearchContentMode.TEXT, engine.resolveContentMode(false, false))
            }
        }
    }

    @Test fun textOnlyEnginesIgnoreImagePreferencesAndImages() {
        for (type in EngineType.entries.filter { it != EngineType.SHARE_IMAGE_TO_APP }) {
            for (preference in SearchContentMode.entries) {
                val engine = SearchEngine().apply {
                    engineType = type
                    mixedContentMode = preference
                }
                for (hasText in listOf(false, true)) {
                    for (hasImage in listOf(false, true)) {
                        assertEquals(SearchContentMode.TEXT, engine.resolveContentMode(hasText, hasImage))
                    }
                }
            }
        }
    }

    @Test fun imageOnlyEnginesAlwaysUseImagesIncludingMissingImageHandling() {
        for (preference in SearchContentMode.entries) {
            val engine = SearchEngine().apply {
                engineType = EngineType.SHARE_IMAGE_TO_APP
                mixedContentMode = preference
            }
            for (hasText in listOf(false, true)) {
                for (hasImage in listOf(false, true)) {
                    assertEquals(SearchContentMode.IMAGE, engine.resolveContentMode(hasText, hasImage))
                }
            }
        }
    }
}
