package io.github.lumiagg.gestureevo.inspire

enum class InspireTokenContentMode {
    ACCESSIBILITY,
    OCR,
    OTHER;

    val isOCR get() = this == OCR
}

enum class InspireDisplayMode {
    TOKEN_SELECTION,
    IMAGE_TEXT_SELECTION;

    val isImageTextSelection get() = this == IMAGE_TEXT_SELECTION
}
