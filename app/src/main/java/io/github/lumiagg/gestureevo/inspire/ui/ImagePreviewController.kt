package io.github.lumiagg.gestureevo.inspire.ui

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.lumiagg.gestureevo.databinding.InspireContentViewBinding
import io.github.lumiagg.gestureevo.inspire.InspireDisplayMode
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.ScreenContentNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal class ImagePreviewController(
    private val binding: InspireContentViewBinding,
    private val hostView: View,
    private val isImageTextSelectionProvider: () -> Boolean,
) {
    private var imageHandle: ManagedBitmap? = null
    private var previewImageWidth = 0
    private var previewImageHeight = 0
    private var ocrImageWidth = 0
    private var ocrImageHeight = 0
    private var lockedImageTextPreviewHeight = 0
    private var ocrImageTextNodes: List<ScreenContentNode> = emptyList()

    val hasImage: Boolean get() = imageHandle?.bitmapOrNull != null
    val hasImageTextNodes: Boolean get() = ocrImageTextNodes.isNotEmpty()

    val imageTextContent: String
        get() = ocrImageTextNodes
            .asSequence()
            .mapNotNull { it.content }
            .filter { it.isNotBlank() }
            .joinToString("")

    suspend fun setScreenShot(handle: ManagedBitmap) = withContext(Dispatchers.Main) {
        releaseImageHandle()
        imageHandle = handle.acquire()
        val image = imageHandle?.bitmapOrNull
        previewImageWidth = image?.width ?: 0
        previewImageHeight = image?.height ?: 0
        lockedImageTextPreviewHeight = 0
        binding.screenshotPreview.apply {
            setImageBitmap(image)
            alpha = 1f
        }
        binding.screenshotPreviewContainer?.visibility = View.VISIBLE
        refreshOverlayHeightIfNeeded()
        postUpdateOverlayImageBounds()
    }

    fun setImageTextNodes(imageWidth: Int, imageHeight: Int, nodes: List<ScreenContentNode>) {
        ocrImageWidth = imageWidth
        ocrImageHeight = imageHeight
        ocrImageTextNodes = nodes
        binding.ocrImageTextOverlay?.apply {
            alpha = 1f
            setImageTextNodes(imageWidth, imageHeight, nodes)
        }
        refreshOverlayHeightIfNeeded()
        postUpdateOverlayImageBounds()
    }

    fun acquireImageHandle(): ManagedBitmap? = imageHandle?.acquire()

    fun clear() {
        releaseImageHandle()
        ocrImageTextNodes = emptyList()
        previewImageWidth = 0
        previewImageHeight = 0
        ocrImageWidth = 0
        ocrImageHeight = 0
        lockedImageTextPreviewHeight = 0
        binding.screenshotPreview.setImageBitmap(null)
    }

    fun resetLockedImageTextPreviewHeight() {
        lockedImageTextPreviewHeight = 0
    }

    fun previewHeightForMode(displayMode: InspireDisplayMode): Int {
        return if (displayMode.isImageTextSelection) {
            getStableImageTextPreviewHeight()
        } else {
            InspireContentLayoutMetrics.COLLAPSED_PREVIEW_HEIGHT_PX
        }
    }

    fun refreshOverlayHeightIfNeeded() {
        if (!isImageTextSelectionProvider()) return
        binding.screenshotPreviewContainer?.let {
            val params = it.layoutParams ?: return@let
            val height = getStableImageTextPreviewHeight()
            if (params.height != height) {
                params.height = height
                it.layoutParams = params
            }
            it.requestLayout()
        }
    }

    fun isTouchInsideDisplayedImage(view: ImageView, x: Float, y: Float): Boolean {
        val drawable = view.drawable ?: return false
        val imageBounds = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        view.imageMatrix.mapRect(imageBounds)
        return imageBounds.contains(x, y)
    }

    fun postUpdateOverlayImageBounds() {
        binding.screenshotPreview.post {
            updateOverlayImageBounds()
            binding.screenshotPreview.post { updateOverlayImageBounds() }
        }
    }

    fun updateOverlayImageBounds() {
        val drawable = binding.screenshotPreview.drawable ?: return
        val imageBounds = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        binding.screenshotPreview.imageMatrix.mapRect(imageBounds)
        binding.ocrImageTextOverlay?.setDisplayedImageBounds(imageBounds)
    }

    fun releaseImageHandle() {
        imageHandle?.close()
        imageHandle = null
    }

    private fun getStableImageTextPreviewHeight(): Int {
        if (lockedImageTextPreviewHeight <= 0) {
            lockedImageTextPreviewHeight = calculateExpandedPreviewHeight()
        }
        return lockedImageTextPreviewHeight
    }

    private fun calculateExpandedPreviewHeight(): Int {
        val imageWidth = previewImageWidth.takeIf { it > 0 }
            ?: ocrImageWidth.takeIf { it > 0 }
            ?: binding.screenshotPreview.drawable?.intrinsicWidth?.takeIf { it > 0 }
            ?: return InspireContentLayoutMetrics.EXPANDED_PREVIEW_FALLBACK_HEIGHT_PX
        val imageHeight = previewImageHeight.takeIf { it > 0 }
            ?: ocrImageHeight.takeIf { it > 0 }
            ?: binding.screenshotPreview.drawable?.intrinsicHeight?.takeIf { it > 0 }
            ?: return InspireContentLayoutMetrics.EXPANDED_PREVIEW_FALLBACK_HEIGHT_PX
        val availableWidth = (binding.screenshotPreviewContainer?.width ?: 0).takeIf { it > 0 }
            ?: (hostView.width - hostView.paddingLeft - hostView.paddingRight).takeIf { it > 0 }
            ?: hostView.resources.displayMetrics.widthPixels
        val desiredHeight = (availableWidth * imageHeight / imageWidth.toFloat()).roundToInt()
        val maxHeight = calculateMaxExpandedPreviewHeight()
        return desiredHeight.coerceIn(InspireContentLayoutMetrics.COLLAPSED_PREVIEW_HEIGHT_PX, maxHeight)
    }

    private fun calculateMaxExpandedPreviewHeight(): Int {
        val parentView = hostView.parent as? View
        val cardBottom = hostView.bottom.takeIf { it > 0 }
            ?: parentView?.height?.takeIf { it > 0 }
            ?: hostView.resources.displayMetrics.heightPixels
        val previewMargins = (binding.screenshotPreviewContainer?.layoutParams as? ViewGroup.MarginLayoutParams)
            ?.let { it.topMargin + it.bottomMargin }
            ?: 0
        val actionButtonsHeight = if (isImageTextSelectionProvider()) {
            binding.imageActionButtons.measuredOrEstimatedHeight(InspireContentLayoutMetrics.IMAGE_ACTION_BUTTONS_HEIGHT_PX)
        } else {
            binding.actionButtons.measuredOrEstimatedHeight(InspireContentLayoutMetrics.TOKEN_ACTION_BUTTONS_HEIGHT_PX)
        }
        val nonPreviewHeight = actionButtonsHeight + previewMargins + hostView.paddingTop + hostView.paddingBottom
        val maxHeight = cardBottom - nonPreviewHeight - InspireContentLayoutMetrics.RESERVED_BOTTOM_GAP_PX
        return maxHeight.coerceAtLeast(InspireContentLayoutMetrics.COLLAPSED_PREVIEW_HEIGHT_PX)
    }
}
