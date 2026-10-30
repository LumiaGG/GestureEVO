package io.github.lumiagg.gestureevo.drop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalDropPolicyTest {
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

    @Test fun filtersSearchEnginesByDraggedContent() {
        val image = ExternalDropPolicy.content(listOf("image/png", "text/uri-list"))
        assertTrue(ExternalDropPolicy.showsEngine(image, true))
        assertFalse(ExternalDropPolicy.showsEngine(image, false))
        val text = ExternalDropPolicy.content(listOf("text/plain", "text/html"))
        assertTrue(ExternalDropPolicy.showsEngine(text, false))
        assertFalse(ExternalDropPolicy.showsEngine(text, true))
    }

    @Test fun leavesBothEngineKindsForUrisWhoseTypeIsUnknownUntilDrop() {
        val uri = ExternalDropPolicy.content(listOf("text/uri-list"))
        assertTrue(ExternalDropPolicy.showsEngine(uri, false))
        assertTrue(ExternalDropPolicy.showsEngine(uri, true))
    }
}
