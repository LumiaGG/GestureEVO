package io.github.lumiagg.gestureevo.utils

internal fun webDavResourceUrl(baseUrl: String, path: String): String =
    baseUrl.trimEnd('/') + "/" + path.trimStart('/')
