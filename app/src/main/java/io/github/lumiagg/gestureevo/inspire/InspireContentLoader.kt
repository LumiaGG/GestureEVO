package io.github.lumiagg.gestureevo.inspire

import android.content.Context
import android.graphics.Bitmap
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_DEFAULT_IMAGE_TEXT_OVERLAY
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore

internal class InspireContentLoader(private val context: Context) {
    private val ocrImageTextProcessor = OcrImageTextProcessor(context.applicationContext)

    suspend fun buildLoadPlan(screenshotBitmapHandle: ManagedBitmap?): InspireContentLoadPlan {
        val defaultImageTextOverlay = InspireDataHolder.forceImageTextSelection ||
                context.settingsStore.readKey(KEY_DEFAULT_IMAGE_TEXT_OVERLAY, false)
        val hasScreenshot = screenshotBitmapHandle?.bitmapOrNull != null
        val accessibilityContent = InspireDataHolder.accessibilityContent
        val hasAccessibilityContent = accessibilityContent?.any { it.trim().isNotEmpty() } == true
        val initialDisplayMode = if (defaultImageTextOverlay && hasScreenshot) {
            InspireDisplayMode.IMAGE_TEXT_SELECTION
        } else {
            InspireDisplayMode.TOKEN_SELECTION
        }
        val initialTokenMode = selectInitialTokenMode(
            hasScreenshot = hasScreenshot,
            hasAccessibilityContent = hasAccessibilityContent,
        )
        return InspireContentLoadPlan(
            initialDisplayMode = initialDisplayMode,
            initialTokenMode = initialTokenMode,
            hasScreenshot = hasScreenshot,
            hasAccessibilityContent = hasAccessibilityContent,
        )
    }

    suspend fun buildOcrResult(
        originalBitmap: Bitmap,
    ): OcrProcessingResult = ocrImageTextProcessor.process(
        originalBitmap = originalBitmap,
        allowedTextBoxes = InspireDataHolder.ocrAllowedTextBoxes,
    )

    fun segmentOcrText(rawText: String): List<String> {
        return ocrImageTextProcessor.segment(rawText)
    }

    suspend fun buildAccessibilityContents(): List<String>? {
        val accessibilityContent = InspireDataHolder.accessibilityContent
        return accessibilityContent?.let {
            val isAccessibilityEmpty = !it.any { content -> content.trim().isNotEmpty() }
            if (isAccessibilityEmpty) {
                listOf(context.getString(R.string.inspire_content_accessibility_empty))
            } else {
                SmartSegmenter.segment(context.applicationContext, it.joinToString(""))
            }
        }
    }

    private suspend fun selectInitialTokenMode(
        hasScreenshot: Boolean,
        hasAccessibilityContent: Boolean,
    ): InspireTokenContentMode {
        val isOcrPriority = context.settingsStore.readKey(InspireSettingsActivity.KEY_OCR_PRIORITY, true)
        return when {
            hasScreenshot && (isOcrPriority || !hasAccessibilityContent) -> InspireTokenContentMode.OCR
            hasAccessibilityContent -> InspireTokenContentMode.ACCESSIBILITY
            else -> InspireTokenContentMode.ACCESSIBILITY
        }
    }

}
