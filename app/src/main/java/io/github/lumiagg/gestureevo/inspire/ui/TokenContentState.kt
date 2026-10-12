package io.github.lumiagg.gestureevo.inspire.ui

import io.github.lumiagg.gestureevo.inspire.InspireTokenContentMode

internal data class TokenContentState(
    val currentMode: InspireTokenContentMode = InspireTokenContentMode.ACCESSIBILITY,
    val currentContents: List<String>? = null,
    val ocrContents: List<String>? = null,
    val accessibilityContents: List<String>? = null,
    val requestableModes: Set<InspireTokenContentMode> = emptySet(),
) {
    fun contentFor(mode: InspireTokenContentMode): List<String>? = when (mode) {
        InspireTokenContentMode.ACCESSIBILITY -> accessibilityContents
        InspireTokenContentMode.OCR -> ocrContents
        InspireTokenContentMode.OTHER -> currentContents
    }

    fun canUse(mode: InspireTokenContentMode): Boolean {
        return contentFor(mode) != null || requestableModes.contains(mode)
    }
}
