package io.github.lumiagg.gestureevo.inspire.ui

import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import io.github.lumiagg.gestureevo.databinding.InspireContentViewBinding
import io.github.lumiagg.gestureevo.inspire.InspireDisplayMode
import io.github.lumiagg.gestureevo.utils.dp2px

internal class ImageTextSelectionPreviewController(
    private val binding: InspireContentViewBinding,
) {
    private var updateGeneration = 0L
    private var dockSide: DockSide? = null

    fun update(
        displayMode: InspireDisplayMode,
        selectedText: String,
        isInteracting: Boolean,
        interactionAnchor: PointF?,
    ) {
        val container = binding.imageSelectionTextPreviewContainer ?: return
        val textView = binding.imageSelectionTextPreview ?: return
        if (
            !displayMode.isImageTextSelection ||
            !isInteracting ||
            selectedText.isBlank() ||
            interactionAnchor == null
        ) {
            hide()
            return
        }
        updateDockSide(interactionAnchor)

        val textChanged = textView.text.toString() != selectedText
        if (textChanged) {
            textView.text = selectedText
        }
        val generation = ++updateGeneration
        val isFirstShow = container.visibility != VISIBLE
        if (textChanged || isFirstShow) {
            if (isFirstShow) {
                container.visibility = INVISIBLE
            } else {
                positionAtDock()
            }
            container.requestLayout()
            container.post {
                if (generation != updateGeneration) return@post
                positionAtDock()
                updateDockSide(interactionAnchor)
                positionAtDock()
                container.visibility = VISIBLE
            }
        } else {
            positionAtDock()
            container.visibility = VISIBLE
        }
    }

    fun hide() {
        updateGeneration++
        dockSide = null
        binding.imageSelectionTextPreviewContainer?.visibility = GONE
    }

    private fun updateDockSide(anchor: PointF) {
        val container = binding.imageSelectionTextPreviewContainer ?: return
        val previewContainer = binding.screenshotPreviewContainer ?: return
        if (container.width <= 0 || container.height <= 0) {
            dockSide = DockSide.TOP
            return
        }

        val margin = 8.dp2px
        val horizontalAvoidance = 24.dp2px
        val verticalAvoidance = 32.dp2px
        val returnHysteresis = 20.dp2px
        val visibleBounds = previewContainer.visibleVerticalBounds()
        val topX = (previewContainer.width - container.width) / 2f
        val isHorizontallyOverTopDock =
            anchor.x >= topX - horizontalAvoidance &&
                    anchor.x <= topX + container.width + horizontalAvoidance
        val topAvoidanceBottom =
            visibleBounds.top + margin + container.height + verticalAvoidance
        val isTopDockBlocked =
            isHorizontallyOverTopDock && anchor.y <= topAvoidanceBottom
        val isTopDockClearlyAvailable =
            !isHorizontallyOverTopDock ||
                    anchor.y > topAvoidanceBottom + returnHysteresis

        dockSide = when (dockSide) {
            null -> if (isTopDockBlocked) DockSide.BOTTOM else DockSide.TOP
            DockSide.TOP -> if (isTopDockBlocked) DockSide.BOTTOM else DockSide.TOP
            DockSide.BOTTOM -> if (isTopDockClearlyAvailable) DockSide.TOP else DockSide.BOTTOM
        }
    }

    private fun positionAtDock() {
        val container = binding.imageSelectionTextPreviewContainer ?: return
        val previewContainer = binding.screenshotPreviewContainer ?: return
        val margin = 8.dp2px
        val visibleBounds = previewContainer.visibleVerticalBounds()
        val maxX = (previewContainer.width - container.width - margin).coerceAtLeast(margin)
        val minY = visibleBounds.top + margin
        val maxY = (visibleBounds.bottom - container.height - margin).coerceAtLeast(minY)
        val desiredX = (previewContainer.width - container.width) / 2f
        val desiredY = when (dockSide) {
            DockSide.BOTTOM -> maxY
            DockSide.TOP, null -> minY
        }
        container.x = desiredX.coerceIn(margin, maxX)
        container.y = desiredY.coerceIn(minY, maxY)
    }

    private fun View.visibleVerticalBounds(): VisibleVerticalBounds {
        val visibleRect = Rect()
        if (!getGlobalVisibleRect(visibleRect) || visibleRect.isEmpty) {
            return VisibleVerticalBounds(top = 0f, bottom = height.toFloat())
        }
        val locationOnScreen = IntArray(2)
        getLocationOnScreen(locationOnScreen)
        val localTop = (visibleRect.top - locationOnScreen[1]).toFloat().coerceIn(0f, height.toFloat())
        val localBottom =
            (visibleRect.bottom - locationOnScreen[1]).toFloat().coerceIn(localTop, height.toFloat())
        return VisibleVerticalBounds(top = localTop, bottom = localBottom)
    }

    private data class VisibleVerticalBounds(
        val top: Float,
        val bottom: Float,
    )

    private enum class DockSide {
        TOP,
        BOTTOM,
    }
}
