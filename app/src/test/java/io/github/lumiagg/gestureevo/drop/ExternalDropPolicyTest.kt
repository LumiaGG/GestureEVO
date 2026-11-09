package io.github.lumiagg.gestureevo.drop

import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalDropPolicyTest {
    private val textEngine = SearchEngine()
    private val imageEngine = SearchEngine().apply { engineType = EngineType.SHARE_IMAGE_TO_APP }
    private val dualEngine = SearchEngine().apply { imageShareEnabled = true }

    @Test fun excludesEvoAndLocalDragsEvenWithSupportedContent() {
        assertFalse(ExternalDropPolicy.accepts(true, false, listOf("image/png")))
        assertFalse(ExternalDropPolicy.accepts(false, true, listOf("text/plain")))
    }

    @Test fun acceptsExternalTextImagesAndUriLists() {
        listOf("text/plain", "text/html", "text/uri-list", "image/png", "image/jpeg").forEach {
            assertTrue(ExternalDropPolicy.accepts(false, false, listOf(it)))
        }
        assertTrue(ExternalDropPolicy.accepts(false, false, listOf("application/octet-stream", "image/*")))
    }

    @Test fun ignoresUnsupportedOrMissingMimeTypes() {
        assertFalse(ExternalDropPolicy.accepts(false, false, emptyList()))
        assertFalse(ExternalDropPolicy.accepts(false, false, listOf("application/pdf", "video/mp4")))
    }

    @Test fun ignoresNullMimeTypesWhenAcceptingDrags() {
        assertFalse(ExternalDropPolicy.accepts(false, false, listOf(null)))
        assertFalse(ExternalDropPolicy.accepts(false, false, listOf(null, "application/pdf")))
        listOf("text/plain", "text/uri-list", "image/png").forEach {
            assertTrue(ExternalDropPolicy.accepts(false, false, listOf(null, it, null)))
        }
        assertFalse(ExternalDropPolicy.accepts(true, false, listOf(null, "image/png")))
        assertFalse(ExternalDropPolicy.accepts(false, true, listOf(null, "text/plain")))
    }

    @Test fun ignoresNullMimeTypesWhenClassifyingContent() {
        assertEquals(ExternalDropPolicy.Content.URI, ExternalDropPolicy.content(listOf(null)))
        assertEquals(ExternalDropPolicy.Content.URI, ExternalDropPolicy.content(listOf(null, "text/uri-list")))
        assertEquals(ExternalDropPolicy.Content.TEXT, ExternalDropPolicy.content(listOf(null, "text/plain", null)))
        assertEquals(ExternalDropPolicy.Content.IMAGE, ExternalDropPolicy.content(listOf(null, "text/plain", "image/png")))
    }

    @Test fun filtersSearchEnginesByDraggedContent() {
        val image = ExternalDropPolicy.content(listOf("image/png", "text/uri-list"))
        assertTrue(ExternalDropPolicy.showsEngine(image, imageEngine))
        assertFalse(ExternalDropPolicy.showsEngine(image, textEngine))
        val text = ExternalDropPolicy.content(listOf("text/plain", "text/html"))
        assertTrue(ExternalDropPolicy.showsEngine(text, textEngine))
        assertFalse(ExternalDropPolicy.showsEngine(text, imageEngine))
    }

    @Test fun leavesBothEngineKindsForUrisWhoseTypeIsUnknownUntilDrop() {
        val uri = ExternalDropPolicy.content(listOf("text/uri-list"))
        assertTrue(ExternalDropPolicy.showsEngine(uri, textEngine))
        assertTrue(ExternalDropPolicy.showsEngine(uri, imageEngine))
        assertTrue(ExternalDropPolicy.showsEngine(uri, dualEngine))
    }

    @Test fun dualModeEnginesAcceptImagesAndTextForEveryTextMode() {
        EngineType.entries.filter { it != EngineType.SHARE_IMAGE_TO_APP }.forEach { type ->
            dualEngine.engineType = type
            assertTrue(ExternalDropPolicy.showsEngine(ExternalDropPolicy.Content.IMAGE, dualEngine))
            assertTrue(ExternalDropPolicy.showsEngine(ExternalDropPolicy.Content.TEXT, dualEngine))
            dualEngine.imageShareEnabled = false
            assertFalse(ExternalDropPolicy.showsEngine(ExternalDropPolicy.Content.IMAGE, dualEngine))
            assertTrue(ExternalDropPolicy.showsEngine(ExternalDropPolicy.Content.TEXT, dualEngine))
            dualEngine.imageShareEnabled = true
        }
    }
}
