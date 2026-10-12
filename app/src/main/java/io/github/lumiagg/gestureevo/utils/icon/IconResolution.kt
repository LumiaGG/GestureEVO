package io.github.lumiagg.gestureevo.utils.icon

internal fun <T> resolveIcon(
    overridePath: String?,
    loadOverride: (String) -> T?,
    loadPack: () -> T?,
    loadApplication: () -> T?,
): T? = overridePath?.takeIf { it.isNotBlank() }?.let(loadOverride) ?: loadPack() ?: loadApplication()
