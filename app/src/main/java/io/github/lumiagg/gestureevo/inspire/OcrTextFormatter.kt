package io.github.lumiagg.gestureevo.inspire

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object OcrTextFormatter {
    fun buildText(nodes: List<ScreenContentNode>): String {
        val lines = buildVisualLines(nodes)
        if (lines.isEmpty()) return ""

        return buildString {
            var prevLine: VisualLine? = null
            var prevText = ""
            lines.forEachIndexed { lineIndex, line ->
                if (lineIndex > 0 && shouldInsertLineBreakBetweenLines(prevLine, line)) {
                    append('\n')
                    prevText = ""
                }
                line.nodes.forEach { node ->
                    val currentText = node.content?.trim().orEmpty()
                    if (currentText.isEmpty()) return@forEach
                    if (shouldInsertSpaceBetweenOcrNodes(prevText, currentText)) {
                        append(' ')
                    }
                    append(currentText)
                    prevText = currentText
                }
                prevLine = line
            }
        }.trim()
    }

    private fun buildVisualLines(nodes: List<ScreenContentNode>): List<VisualLine> {
        val sortedNodes = nodes
            .filter { !it.content.isNullOrBlank() && it.bound.width() > 0 && it.bound.height() > 0 }
            .sortedWith(
                compareBy<ScreenContentNode>(
                    { it.bound.top },
                    { it.bound.centerY() },
                    { it.bound.left },
                    { it.bound.right },
                )
            )
        val lines = mutableListOf<VisualLine>()
        sortedNodes.forEach { node ->
            val line = lines.firstOrNull { it.isSameLine(node.bound) }
            if (line == null) {
                lines.add(VisualLine(node))
            } else {
                line.add(node)
            }
        }

        return lines
            .sortedWith(compareBy<VisualLine>({ it.centerY }, { it.left }))
            .onEach { line -> line.nodes.sortWith(compareBy({ it.bound.left }, { it.bound.centerY() })) }
    }

    private fun shouldInsertLineBreakBetweenLines(prev: VisualLine?, current: VisualLine): Boolean {
        if (prev == null) return false
        val averageHeight = (prev.height + current.height) / 2f
        if (averageHeight <= 0f) return false

        val verticalGap = current.top - prev.bottom
        if (verticalGap > averageHeight * HARD_BREAK_MIN_VERTICAL_GAP_RATIO) return true
        if (prev.height > current.height * HARD_BREAK_MAX_HEIGHT_RATIO) return true
        if (current.height > prev.height * HARD_BREAK_MAX_HEIGHT_RATIO) return true
        if (current.firstText.firstOrNull()?.isListMarker() == true) return true
        return prev.textLength <= SHORT_HEADING_MAX_TEXT_LENGTH &&
                prev.width < current.width * SHORT_HEADING_MAX_WIDTH_RATIO
    }

    private fun shouldInsertSpaceBetweenOcrNodes(prevText: String, currentText: String): Boolean {
        if (prevText.isEmpty() || currentText.isEmpty()) return false
        if (prevText.last().isWhitespace() || currentText.first().isWhitespace()) return false
        if (currentText.first().isClosingPunctuation()) return false
        if (prevText.last().isOpeningPunctuation()) return false
        val prevChar = prevText.last()
        val currentChar = currentText.first()
        if (prevChar.isCjk() || currentChar.isCjk()) return false
        return prevChar.isSpaceSeparatedText() && currentChar.isSpaceSeparatedText()
    }

    private fun Char.isSpaceSeparatedText(): Boolean {
        return isLetterOrDigit() || this == '$' || this == '%' || this == '#' || this == '@'
    }

    private fun Char.isCjk(): Boolean {
        return code in 0x3400..0x9FFF ||
                code in 0xF900..0xFAFF ||
                code in 0x3040..0x30FF ||
                code in 0xAC00..0xD7AF
    }

    private fun Char.isListMarker(): Boolean {
        return this == '-' || this == '*' || this == '•' || this == '·'
    }

    private fun Char.isClosingPunctuation(): Boolean {
        return this in setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '，', '。', '；', '：', '！', '？', '）', '】', '》')
    }

    private fun Char.isOpeningPunctuation(): Boolean {
        return this in setOf('(', '[', '{', '（', '【', '《')
    }

    private class VisualLine(firstNode: ScreenContentNode) {
        val nodes = mutableListOf(firstNode)
        var left = firstNode.bound.left
            private set
        var right = firstNode.bound.right
            private set
        var top = firstNode.bound.top
            private set
        var bottom = firstNode.bound.bottom
            private set
        val width: Int
            get() = right - left
        val height: Int
            get() = bottom - top
        val centerY: Int
            get() = (top + bottom) / 2
        val firstText: String
            get() = nodes.firstOrNull()?.content?.trim().orEmpty()
        val textLength: Int
            get() = nodes.sumOf { it.content?.trim()?.length ?: 0 }

        fun add(node: ScreenContentNode) {
            nodes.add(node)
            left = min(left, node.bound.left)
            right = max(right, node.bound.right)
            top = min(top, node.bound.top)
            bottom = max(bottom, node.bound.bottom)
        }

        fun isSameLine(bound: Rect): Boolean {
            val lineHeight = bottom - top
            val boundHeight = bound.height()
            if (lineHeight <= 0 || boundHeight <= 0) return false

            val overlap = min(bottom, bound.bottom) - max(top, bound.top)
            val overlapRatio = overlap.coerceAtLeast(0) / min(lineHeight, boundHeight).toFloat()
            val centerDistance = abs(bound.centerY() - centerY)
            return overlapRatio >= SAME_LINE_MIN_OVERLAP_RATIO ||
                    centerDistance <= max(lineHeight, boundHeight) * SAME_LINE_MAX_CENTER_DISTANCE_RATIO
        }
    }

    private const val SAME_LINE_MIN_OVERLAP_RATIO = 0.45f
    private const val SAME_LINE_MAX_CENTER_DISTANCE_RATIO = 0.35f
    private const val HARD_BREAK_MIN_VERTICAL_GAP_RATIO = 0.7f
    private const val HARD_BREAK_MAX_HEIGHT_RATIO = 1.35f
    private const val SHORT_HEADING_MAX_TEXT_LENGTH = 16
    private const val SHORT_HEADING_MAX_WIDTH_RATIO = 0.65f
}
