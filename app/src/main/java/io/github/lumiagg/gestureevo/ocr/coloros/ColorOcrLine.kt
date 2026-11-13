package io.github.lumiagg.gestureevo.ocr.coloros

import android.graphics.Rect
import io.github.lumiagg.gestureevo.inspire.ScreenContentNode
import io.github.lumiagg.gestureevo.ocr.OcrGeometry
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One recognized text line with its four-corner polygon in image pixel coordinates,
 * straight from the ocr_ar JSON. Coordinates map 1:1 onto the submitted bitmap.
 * Confidence is the average of the item's per-character probabilities when present.
 */
class ColorOcrLine(
    val text: String,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val confidence: Double = 1.0,
) {

    fun toRect(imageWidth: Int, imageHeight: Int): Rect? =
        OcrGeometry.boundingRect(xs, ys, imageWidth, imageHeight)
            ?.let { Rect(it[0], it[1], it[2], it[3]) }

    /** Same conversion contract as the built-in engine's detect(): blank text is dropped. */
    fun toScreenContentNode(imageWidth: Int, imageHeight: Int): ScreenContentNode? {
        if (text.isBlank()) return null
        return toRect(imageWidth, imageHeight)?.let { ScreenContentNode(it, text) }
    }

    companion object {
        internal const val MAX_ITEMS = 500

        /** Parses the raw ocr_ar JSON; throws IllegalArgumentException on malformed payloads. */
        fun parseJson(json: String): List<ColorOcrLine> {
            try {
                return parseJsonOrThrow(json)
            } catch (error: JSONException) {
                throw IllegalArgumentException("Malformed OCR JSON: " + error.message, error)
            }
        }

        private fun parseJsonOrThrow(json: String): List<ColorOcrLine> {
            val result = JSONObject(json)
            val items = result.optJSONArray("items")
                ?: throw IllegalArgumentException("OCR JSON has no items array")
            if (items.length() > MAX_ITEMS) {
                throw IllegalArgumentException("OCR JSON item count exceeds limit: " + items.length())
            }
            val lines = ArrayList<ColorOcrLine>(items.length())
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val text = item.optString("text")
                val points = item.optJSONArray("rect")
                    ?: throw IllegalArgumentException("OCR item $i has no rect")
                if (points.length() < 3) {
                    throw IllegalArgumentException("OCR item $i has too few rect points")
                }
                val xs = DoubleArray(points.length())
                val ys = DoubleArray(points.length())
                for (p in 0 until points.length()) {
                    val point = points.getJSONObject(p)
                    xs[p] = point.getDouble("x")
                    ys[p] = point.getDouble("y")
                }
                if (xs.any { !it.isFinite() } || ys.any { !it.isFinite() }) {
                    throw IllegalArgumentException("OCR item $i has non-finite coordinates")
                }
                lines.add(ColorOcrLine(text, xs, ys, averageProbability(item)))
            }
            return lines
        }

        private fun averageProbability(item: JSONObject): Double {
            val probabilities = item.optJSONArray("probabilities") ?: return 1.0
            if (probabilities.length() == 0) return 1.0
            var sum = 0.0
            for (p in 0 until probabilities.length()) sum += probabilities.optDouble(p, 0.0)
            return sum / probabilities.length()
        }

        /** Recognition-only reading for already-cropped line bitmaps; multi-line crops join by newline. */
        fun joinTexts(lines: List<ColorOcrLine>): String =
            lines.map { it.text }.filter { it.isNotBlank() }.joinToString("\n")
    }
}
