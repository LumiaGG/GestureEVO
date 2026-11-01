package io.github.lumiagg.gestureevo.drop

internal object ExternalDropPolicy {
    const val EVO_SOURCE_EXTRA = "io.github.lumiagg.gestureevo.drag_source"

    enum class Content { TEXT, IMAGE, URI }

    // ClipDescription comes from external apps and may contain null MIME entries.
    fun content(mimeTypes: List<String?>): Content = when {
        mimeTypes.any { it?.startsWith("image/") == true } -> Content.IMAGE
        mimeTypes.any { it?.startsWith("text/") == true && it != "text/uri-list" } -> Content.TEXT
        else -> Content.URI
    }

    fun showsEngine(content: Content, imageEngine: Boolean): Boolean = when (content) {
        Content.IMAGE -> imageEngine
        Content.TEXT -> !imageEngine
        Content.URI -> true // The provider's URI type is only available at ACTION_DROP.
    }

    fun accepts(fromEvo: Boolean, hasLocalState: Boolean, mimeTypes: List<String?>): Boolean =
        !fromEvo && !hasLocalState && mimeTypes.any {
            it?.startsWith("text/") == true || it?.startsWith("image/") == true
        }
}
