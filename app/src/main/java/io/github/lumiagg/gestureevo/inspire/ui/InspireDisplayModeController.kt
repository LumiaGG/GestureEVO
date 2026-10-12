package io.github.lumiagg.gestureevo.inspire.ui

import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import io.github.lumiagg.gestureevo.databinding.InspireContentViewBinding
import io.github.lumiagg.gestureevo.inspire.InspireDisplayMode

internal class InspireDisplayModeController(
    private val binding: InspireContentViewBinding,
    private val hostView: View,
    private val previewHeightProvider: (InspireDisplayMode) -> Int,
    private val onPreviewLayoutChanged: () -> Unit,
) {
    fun apply(displayMode: InspireDisplayMode) = with(binding) {
        val isImageTextSelection = displayMode.isImageTextSelection
        screenshotPreviewContainer?.visibility = VISIBLE
        actionButtons.visibility = VISIBLE
        imageActionButtons?.visibility = VISIBLE
        textActionButtons?.visibility = if (isImageTextSelection) GONE else VISIBLE
        bigBangLayoutNestedScroll.visibility = if (isImageTextSelection) GONE else VISIBLE
        applyHeight(bigBangLayoutNestedScroll, displayMode.bigBangHeight)
        screenshotPreviewContainer?.let {
            applyHeight(it, previewHeightProvider(displayMode))
            it.requestLayout()
        }
        bigBangLayoutNestedScroll.requestLayout()
        refreshAfterMeasure(displayMode)
    }

    fun hideAll() = with(binding) {
        screenshotPreviewContainer?.visibility = GONE
        actionButtons.visibility = GONE
        imageActionButtons?.visibility = GONE
        textActionButtons?.visibility = GONE
        bigBangLayoutNestedScroll.visibility = GONE
        ocrImageTextOverlay?.visibility = GONE
    }

    private fun refreshAfterMeasure(displayMode: InspireDisplayMode) {
        hostView.post {
            binding.screenshotPreviewContainer?.let {
                applyHeight(it, previewHeightProvider(displayMode))
                it.requestLayout()
                onPreviewLayoutChanged()
            }
        }
    }

    private fun applyHeight(view: View, height: Int) {
        val params = view.layoutParams ?: return
        if (params.height == height) return
        params.height = height
        view.layoutParams = params
    }

    private val InspireDisplayMode.bigBangHeight: Int
        get() = if (isImageTextSelection) 0 else InspireContentLayoutMetrics.BIG_BANG_CONTENT_HEIGHT_PX
}
