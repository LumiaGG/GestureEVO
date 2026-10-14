package io.github.lumiagg.gestureevo.settings.guide

/** Position hints relative to the measured dialog content, including at screen edges. */
internal fun guideHintTop(triggerCenterY: Int, hintHeight: Int, availableHeight: Int): Int =
    (triggerCenterY - hintHeight / 2).coerceIn(0, (availableHeight - hintHeight).coerceAtLeast(0))
