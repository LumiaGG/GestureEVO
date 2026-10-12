package io.github.lumiagg.gestureevo.inspire

import android.content.Context
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.inspire.ui.InspireContentView
import io.github.lumiagg.gestureevo.utils.ToastHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TokenContentController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val contentView: InspireContentView,
    private val contentLoader: InspireContentLoader,
) {
    private var tokenContentJob: Job? = null
    private var ocrRawText: String? = null

    fun cancel() {
        tokenContentJob?.cancel()
        tokenContentJob = null
    }

    fun reset() {
        cancel()
        ocrRawText = null
    }

    fun clearOcrData() {
        cancel()
        ocrRawText = null
        contentView.ocrContents = null
    }

    fun applyOcrResult(ocrResult: OcrProcessingResult, loadPlan: InspireContentLoadPlan) {
        val correction = AccessibilityTextCorrection.correctOcrResult(
            result = ocrResult,
            accessibilityContent = InspireDataHolder.accessibilityContent,
        )
        val correctedOcrResult = correction.result
        if (correction.corrected) {
            ToastHelper.showInUIThread(context, R.string.inspire_url_corrected)
        }
        ocrRawText = correctedOcrResult.rawText
        if (correctedOcrResult.rawText.isBlank()) {
            contentView.ocrContents = listOf(context.getString(R.string.inspire_content_ocr_empty))
            if (contentView.getCurrentMode().isOCR) {
                contentView.switchToMode(InspireTokenContentMode.OCR)
            }
            return
        }
        contentView.setOcrImageTextNodes(
            correctedOcrResult.imageWidth,
            correctedOcrResult.imageHeight,
            correctedOcrResult.imageTextNodes,
        )
        contentView.setDisplayMode(loadPlan.initialDisplayMode)
        if (contentView.getCurrentMode().isOCR) {
            requestMode(InspireTokenContentMode.OCR)
        }
    }

    fun requestMode(mode: InspireTokenContentMode) {
        tokenContentJob?.cancel()
        tokenContentJob = scope.launch {
            when (mode) {
                InspireTokenContentMode.OCR -> ensureOcrSegmentedContent(mode)
                InspireTokenContentMode.ACCESSIBILITY -> ensureAccessibilityContent()
                InspireTokenContentMode.OTHER -> Unit
            }
            tokenContentJob = null
        }
    }

    fun getContentsForMode(mode: InspireTokenContentMode): List<String> = when (mode) {
        InspireTokenContentMode.OCR -> contentView.ocrContents.orEmpty()
        InspireTokenContentMode.ACCESSIBILITY -> contentView.accessibilityContents.orEmpty()
        InspireTokenContentMode.OTHER -> contentView.getCurrentContent().orEmpty()
    }

    fun replaceModeContent(
        mode: InspireTokenContentMode,
        contents: List<String>,
        selectedIndexes: Set<Int>? = null,
        preserveScroll: Boolean = false,
        changedIndex: Int? = null,
        changedContents: List<String>? = null,
    ) {
        updateContentsForMode(mode, contents)
        refreshModeContent(
            mode = mode,
            contents = contents,
            selectedIndexes = selectedIndexes,
            preserveScroll = preserveScroll,
            changedIndex = changedIndex,
            changedContents = changedContents,
        )
    }

    fun setCustomContent(contents: List<String>) {
        contentView.setCustomContent(contents)
    }

    fun applyEditedText(mode: InspireTokenContentMode, editedText: String) {
        val segmented = SmartSegmenter.segment(context.applicationContext, editedText)
        replaceModeContent(mode, segmented)
    }

    private suspend fun ensureOcrSegmentedContent(targetMode: InspireTokenContentMode) {
        contentView.ocrContents?.let {
            contentView.switchToMode(targetMode)
            return
        }
        ocrRawText?.let { rawText ->
            val contents = if (rawText.isBlank()) {
                listOf(context.getString(R.string.inspire_content_ocr_empty))
            } else {
                withContext(Dispatchers.Default) { contentLoader.segmentOcrText(rawText) }
            }
            contentView.ocrContents = contents
            contentView.switchToMode(targetMode)
            return
        }
    }

    private suspend fun ensureAccessibilityContent() {
        if (contentView.accessibilityContents != null) {
            contentView.switchToMode(InspireTokenContentMode.ACCESSIBILITY)
            return
        }
        val accessibilityContents = withContext(Dispatchers.Default) {
            contentLoader.buildAccessibilityContents()
        }
        contentView.accessibilityContents = accessibilityContents
        contentView.switchToMode(InspireTokenContentMode.ACCESSIBILITY)
    }

    private fun updateContentsForMode(
        mode: InspireTokenContentMode,
        contents: List<String>,
    ) {
        when (mode) {
            InspireTokenContentMode.OCR -> contentView.ocrContents = contents
            InspireTokenContentMode.ACCESSIBILITY -> contentView.accessibilityContents = contents
            InspireTokenContentMode.OTHER -> Unit
        }
    }

    private fun refreshModeContent(
        mode: InspireTokenContentMode,
        contents: List<String>,
        selectedIndexes: Set<Int>? = null,
        preserveScroll: Boolean = false,
        changedIndex: Int? = null,
        changedContents: List<String>? = null,
    ) {
        if (mode == InspireTokenContentMode.OTHER) {
            contentView.setCustomContent(
                contents = contents,
                preserveScroll = preserveScroll,
                changedIndex = changedIndex,
                changedContents = changedContents,
                selectedIndexes = selectedIndexes,
            )
            return
        }
        contentView.setContent(
            contents,
            isSelected = false,
            selectedIndexes = selectedIndexes,
            preserveScroll = preserveScroll,
            changedIndex = changedIndex,
            changedContents = changedContents,
        )
    }
}
