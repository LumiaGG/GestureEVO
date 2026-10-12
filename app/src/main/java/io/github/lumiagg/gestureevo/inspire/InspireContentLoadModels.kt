package io.github.lumiagg.gestureevo.inspire

internal data class InspireContentLoadPlan(
    val initialDisplayMode: InspireDisplayMode,
    val initialTokenMode: InspireTokenContentMode,
    val hasScreenshot: Boolean,
    val hasAccessibilityContent: Boolean,
)

internal val InspireContentLoadPlan.requestableTokenModes: Set<InspireTokenContentMode>
    get() = buildSet {
        if (hasScreenshot) add(InspireTokenContentMode.OCR)
        if (hasAccessibilityContent) add(InspireTokenContentMode.ACCESSIBILITY)
    }

internal data class OcrProcessingResult(
    val rawText: String = "",
    val imageTextNodes: List<ScreenContentNode> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
)
