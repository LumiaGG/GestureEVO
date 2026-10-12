package io.github.lumiagg.gestureevo.inspire

import io.github.lumiagg.gestureevo.utils.UrlDetector

internal object AccessibilityTextCorrection {
    data class CorrectionResult(
        val text: String,
        val corrected: Boolean,
    )

    data class OcrCorrectionResult(
        val result: OcrProcessingResult,
        val corrected: Boolean,
    )

    fun correctUrlText(
        text: String,
        accessibilityContent: List<String>?,
    ): String = correctUrlTextWithResult(text, accessibilityContent).text

    fun correctUrlTextWithResult(
        text: String,
        accessibilityContent: List<String>?,
    ): CorrectionResult {
        val accessibilityText = accessibilityContent
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
            ?: return CorrectionResult(text, corrected = false)
        val candidates = UrlDetector.findUrls(accessibilityText)
        if (candidates.isEmpty()) return CorrectionResult(text, corrected = false)

        val selectedSpans = findCorrectableUrlSpans(text)
        if (selectedSpans.isEmpty()) return CorrectionResult(text, corrected = false)

        val correctedText = selectedSpans.distinctBy { it.original }.fold(text) { currentText, span ->
            val correctedUrl = bestCandidateFor(span.normalized, candidates) ?: span.original
            currentText.replace(span.original, correctedUrl)
        }
        return CorrectionResult(correctedText, corrected = correctedText != text)
    }

    fun correctOcrResult(
        result: OcrProcessingResult,
        accessibilityContent: List<String>?,
    ): OcrCorrectionResult {
        val rawTextResult = correctUrlTextWithResult(
            text = result.rawText,
            accessibilityContent = accessibilityContent,
        )
        var nodeCorrected = false
        val correctedNodes = result.imageTextNodes.map { node ->
            val content = node.content ?: return@map node
            val nodeResult = correctUrlTextWithResult(
                text = content,
                accessibilityContent = accessibilityContent,
            )
            nodeCorrected = nodeCorrected || nodeResult.corrected
            node.copy(content = nodeResult.text)
        }
        return OcrCorrectionResult(
            result = result.copy(
                rawText = rawTextResult.text,
                imageTextNodes = correctedNodes,
            ),
            corrected = rawTextResult.corrected || nodeCorrected,
        )
    }

    private fun bestCandidateFor(selectedUrl: String, candidates: List<String>): String? {
        val selectedKey = similarityKey(selectedUrl)
        if (selectedKey.isEmpty()) return null
        val scoredCandidate = candidates
            .map { candidate -> candidate to similarityScore(similarityKey(candidate), selectedKey) }
            .maxByOrNull { it.second }
            ?: return null
        return scoredCandidate.first.takeIf { scoredCandidate.second >= MIN_SIMILARITY_SCORE }
    }

    private fun findCorrectableUrlSpans(text: String): List<UrlSpan> {
        val detectedUrls = UrlDetector.findUrls(text).map { UrlSpan(it, normalizeUrlLikeText(it)) }
        val fuzzyUrls = URL_LIKE_START_REGEX.findAll(text).mapNotNull { match ->
            val original = extractUrlLikeSpan(text, match.range.first)
            val normalized = normalizeUrlLikeText(original)
            if (normalized.length >= MIN_URL_LIKE_LENGTH) UrlSpan(original, normalized) else null
        }
        return (detectedUrls + fuzzyUrls).distinctBy { it.original }
    }

    private fun extractUrlLikeSpan(text: String, start: Int): String {
        var end = start
        while (end < text.length) {
            val char = text[end]
            if (char == '\n' || char == '\r' || char == '\t') break
            if (char.isWhitespace()) {
                val nextIndex = nextNonWhitespace(text, end + 1)
                if (nextIndex == -1 || !isUrlLikeChar(text[nextIndex])) break
            } else if (!isUrlLikeChar(char)) {
                break
            }
            end++
        }
        return trimTrailingPunctuation(text.substring(start, end))
    }

    private fun nextNonWhitespace(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            if (!text[index].isWhitespace()) return index
            index++
        }
        return -1
    }

    private fun isUrlLikeChar(char: Char): Boolean {
        return char.isLetterOrDigit() || char in URL_LIKE_SYMBOLS
    }

    private fun normalizeUrlLikeText(text: String): String {
        return text.map { char -> URL_LIKE_CHAR_REPLACEMENTS[char] ?: char }
            .joinToString("")
            .filterNot { it.isWhitespace() }
    }

    private fun trimTrailingPunctuation(text: String): String {
        return text.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'', '。', '，', '；', '：', '！', '？')
    }

    private fun similarityScore(candidateKey: String, selectedKey: String): Float {
        if (candidateKey.isEmpty() || selectedKey.isEmpty()) return 0f
        val prefixScore = candidateKey.commonPrefixScore(selectedKey).toFloat() /
                minOf(candidateKey.length, selectedKey.length)
        val lcsScore = longestCommonSubsequenceLength(candidateKey, selectedKey).toFloat() /
                maxOf(candidateKey.length, selectedKey.length)
        return maxOf(prefixScore, lcsScore)
    }

    private fun longestCommonSubsequenceLength(first: String, second: String): Int {
        val previous = IntArray(second.length + 1)
        val current = IntArray(second.length + 1)
        for (i in first.indices) {
            for (j in second.indices) {
                current[j + 1] = if (first[i] == second[j]) {
                    previous[j] + 1
                } else {
                    maxOf(previous[j + 1], current[j])
                }
            }
            for (j in current.indices) {
                previous[j] = current[j]
                current[j] = 0
            }
        }
        return previous[second.length]
    }

    private fun similarityKey(text: String): String {
        return text.lowercase().filter { it.isLetterOrDigit() }
    }

    private fun String.commonPrefixScore(other: String): Int {
        val maxLength = minOf(length, other.length)
        var score = 0
        while (score < maxLength && this[score] == other[score]) {
            score++
        }
        return score
    }

    private data class UrlSpan(
        val original: String,
        val normalized: String,
    )

    private const val MIN_URL_LIKE_LENGTH = 8
    private const val MIN_SIMILARITY_SCORE = 0.45f
    private val URL_LIKE_START_REGEX = Regex(
        pattern = "(?i)(?:h\\s*t\\s*t\\s*p\\s*s?|w\\s*w\\s*w)",
    )
    private const val URL_LIKE_SYMBOLS = "-._~%:/?#[]@!$&'()*+,;=|\\／：．。？＆＝＃％＿－～"
    private val URL_LIKE_CHAR_REPLACEMENTS = mapOf(
        '：' to ':',
        '／' to '/',
        '｜' to '/',
        '|' to '/',
        '．' to '.',
        '。' to '.',
        '？' to '?',
        '＆' to '&',
        '＝' to '=',
        '＃' to '#',
        '％' to '%',
        '＿' to '_',
        '－' to '-',
        '～' to '~',
    )
}
