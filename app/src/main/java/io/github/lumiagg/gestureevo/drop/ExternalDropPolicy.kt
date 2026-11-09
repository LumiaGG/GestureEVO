package io.github.lumiagg.gestureevo.drop

import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchEngine

internal object ExternalDropPolicy {
    const val EVO_SOURCE_EXTRA = "io.github.lumiagg.gestureevo.drag_source"

    enum class Content { TEXT, IMAGE, URI }

    // ClipDescription comes from external apps and may contain null MIME entries.
    fun content(mimeTypes: List<String?>): Content = when {
        mimeTypes.any { it?.startsWith("image/") == true } -> Content.IMAGE
        mimeTypes.any { it?.startsWith("text/") == true && it != "text/uri-list" } -> Content.TEXT
        else -> Content.URI
    }

    fun showsEngine(content: Content, engine: SearchEngine): Boolean = when (content) {
        // Dual-mode engines handle image content too.
        Content.IMAGE -> engine.supportsImageShare()
        Content.TEXT -> engine.engineType != EngineType.SHARE_IMAGE_TO_APP
        Content.URI -> true // The provider's URI type is only available at ACTION_DROP.
    }

    fun accepts(fromEvo: Boolean, hasLocalState: Boolean, mimeTypes: List<String?>): Boolean =
        !fromEvo && !hasLocalState && mimeTypes.any {
            it?.startsWith("text/") == true || it?.startsWith("image/") == true
        }
}
