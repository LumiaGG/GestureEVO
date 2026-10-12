package io.github.lumiagg.gestureevo.inspire

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import io.github.lumiagg.gestureevo.utils.MlKitOcr
import io.github.lumiagg.gestureevo.utils.dp2px

internal class OcrImageTextProcessor(private val context: Context) {
    suspend fun process(
        originalBitmap: Bitmap,
        allowedTextBoxes: List<Rect>? = null,
    ): OcrProcessingResult {
        val paddingPx = OCR_PADDING_DP.dp2px.toInt()
        return originalBitmap.useSoftwareBitmap { softwareOriginalBitmap ->
            val paddedBitmap = createPaddedBitmap(softwareOriginalBitmap, paddingPx)
            try {
                val imageNodes = detectImageTextNodes(
                    paddedBitmap = paddedBitmap,
                    sourceWidth = softwareOriginalBitmap.width,
                    sourceHeight = softwareOriginalBitmap.height,
                    paddingPx = paddingPx,
                ).filterByAllowedTextBoxes(allowedTextBoxes)
                val rawText = OcrTextFormatter.buildText(imageNodes)
                OcrProcessingResult(
                    rawText = rawText,
                    imageTextNodes = imageNodes,
                    imageWidth = softwareOriginalBitmap.width,
                    imageHeight = softwareOriginalBitmap.height,
                )
            } finally {
                if (!paddedBitmap.isRecycled) {
                    paddedBitmap.recycle()
                }
            }
        }
    }

    private fun List<ScreenContentNode>.filterByAllowedTextBoxes(
        allowedTextBoxes: List<Rect>?,
    ): List<ScreenContentNode> {
        if (allowedTextBoxes == null) return this
        if (allowedTextBoxes.isEmpty()) return emptyList()
        return filter { node ->
            allowedTextBoxes.any { allowed ->
                allowed.contains(node.bound.centerX(), node.bound.centerY())
            }
        }
    }

    fun segment(rawText: String): List<String> {
        return SmartSegmenter.segment(context.applicationContext, rawText)
    }

    private fun createPaddedBitmap(source: Bitmap, paddingPx: Int): Bitmap {
        val paddedBitmap = createBitmap(
            source.width + (paddingPx * 2),
            source.height + (paddingPx * 2),
        )
        Canvas(paddedBitmap).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, paddingPx.toFloat(), paddingPx.toFloat(), null)
        }
        return paddedBitmap
    }

    private suspend fun detectImageTextNodes(
        paddedBitmap: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        paddingPx: Int,
    ): List<ScreenContentNode> {
        return MlKitOcr.detect(paddedBitmap).mapNotNull { node ->
            node.toSourceNode(sourceWidth, sourceHeight, paddingPx)
        }.sortedWith(OCR_NODE_POSITION_COMPARATOR)
    }

    private fun ScreenContentNode.toSourceNode(
        sourceWidth: Int,
        sourceHeight: Int,
        paddingPx: Int,
    ): ScreenContentNode? {
        val adjustedBound = Rect(
            bound.left - paddingPx,
            bound.top - paddingPx,
            bound.right - paddingPx,
            bound.bottom - paddingPx,
        )
        if (!adjustedBound.intersect(0, 0, sourceWidth, sourceHeight)) return null
        return if (adjustedBound.isEmpty) null else ScreenContentNode(adjustedBound, content)
    }

    private companion object {
        const val OCR_PADDING_DP = 10
        val OCR_NODE_POSITION_COMPARATOR = compareBy<ScreenContentNode>(
            { it.bound.top },
            { it.bound.centerY() },
            { it.bound.left },
        )
    }
}
