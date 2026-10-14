package io.github.lumiagg.gestureevo.gesture.ui

import io.github.lumiagg.gestureevo.gesture.config.ActionConfig

/** Shared by runtime wheels and embedded setup previews. Scale is for the entire wheel. */
internal fun RoundImageTextView.applyWheelStyle(config: ActionConfig, scale: Float = 1f) {
    applyGlobalImageRoundPercent = false
    setCornerRadius(config.iconCornerRadius * scale)
    setTransparentBackground(config.transparentIconBackground)
    setImageRoundPercent(config.iconImageRoundPercent)
    setImagePadding((config.iconPadding * scale).toInt())
}
