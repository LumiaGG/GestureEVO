package io.github.lumiagg.gestureevo.ocr.coloros

/** Failure of any ColorOS OCR step (su, binding, authorize, SDK, inference, timeout). */
class ColorOsOcrException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
