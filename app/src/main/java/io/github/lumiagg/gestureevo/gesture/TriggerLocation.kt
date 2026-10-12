package io.github.lumiagg.gestureevo.gesture

/**
 * Trigger location abstraction containing touch/gesture screen coordinates and screen size.
 */
data class TriggerLocation(
    val x: Float,
    val y: Float,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
) {
    val side: String
        get() = if (screenWidth > 0 && x < screenWidth / 2f) TriggerSide.LEFT else TriggerSide.RIGHT

    val isLeft: Boolean
        get() = side == TriggerSide.LEFT

    val isRight: Boolean
        get() = side == TriggerSide.RIGHT

    val normalizedX: Float
        get() = if (screenWidth > 0) (x / screenWidth).coerceIn(0f, 1f) else 0.5f

    val normalizedY: Float
        get() = if (screenHeight > 0) (y / screenHeight).coerceIn(0f, 1f) else 0.5f
}
