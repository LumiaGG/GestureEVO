package io.github.lumiagg.gestureevo.search

import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SearchEngineCompatibilityTest {
    @Test fun oldEngineJsonKeepsImageSharingDisabled() {
        val engine = requireNotNull(SearchEngine.toModel("""{"engineType":"DIRECT_LINK","searchLink":"https://example.com/?q=%s"}"""))
        assertFalse(engine.imageShareEnabled)
        assertFalse(engine.supportsImageShare())
        assertNull(engine.imageTargetPackage)
    }

    @Test fun exportingAndImportingPreservesSeparateImageAndTextTargets() {
        val engine = SearchEngine().apply {
            engineType = EngineType.SHARE_TO_APP
            targetPackage = "example.text"
            targetActivity = "example.text.Share"
            imageShareEnabled = true
            imageTargetPackage = "example.image"
            imageTargetActivity = "example.image.Share"
        }
        val imported = requireNotNull(SearchEngine.toModelList(SearchEngine.toExportJsonList(listOf(engine)))).single()
        assertEquals(engine.engineType, imported.engineType)
        assertEquals(engine.targetPackage, imported.targetPackage)
        assertEquals(engine.targetActivity, imported.targetActivity)
        assertEquals(engine.imageShareEnabled, imported.imageShareEnabled)
        assertEquals(engine.imageTargetPackage, imported.imageShareTargetPackage())
        assertEquals(engine.imageTargetActivity, imported.imageShareTargetActivity())
    }

    @Test fun pureImageEngineRetainsItsOriginalShareTarget() {
        val engine = SearchEngine().apply {
            engineType = EngineType.SHARE_IMAGE_TO_APP
            targetPackage = "example.original"
            targetActivity = "example.original.Share"
            imageTargetPackage = "example.unused"
            imageTargetActivity = "example.unused.Share"
        }
        val restored = requireNotNull(SearchEngine.toModel(engine.toJson()))
        assertEquals(engine.targetPackage, restored.imageShareTargetPackage())
        assertEquals(engine.targetActivity, restored.imageShareTargetActivity())
    }
}
