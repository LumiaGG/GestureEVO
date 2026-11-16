package io.github.lumiagg.gestureevo.link

import android.content.Context
import io.github.lumiagg.gestureevo.utils.UrlDetector
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the link to dispatch inside a piece of text.
 *
 * User-configured patterns run first because they cover schemes the built-in detector does not
 * know about, such as magnet links. Only when none of them match does the general URL detector
 * run, which keeps the common case fast and the special cases possible.
 */
object LinkExtractor {

    private val cachedRegex = ConcurrentHashMap<String, Regex>()
    private val INVALID_PATTERN = Regex("(?!)")

    /**
     * Returns the first match among the enabled patterns, preferring the longest match so a
     * pattern covering a whole URL wins over one that only catches a fragment.
     */
    fun extract(text: String, patterns: List<LinkExtractPattern>): String? {
        if (text.isBlank()) return null

        val fromPatterns = configuredMatches(text, patterns).maxByOrNull { it.url.length }?.url

        if (fromPatterns != null) return fromPatterns

        // Fall through to the general detector, which understands bare domains and full-width
        // characters that a user pattern is unlikely to cover.
        return UrlDetector.findUrls(text).firstOrNull()
    }

    suspend fun extractWithStoredPatterns(context: Context, text: String): String? {
        val patterns = LinkRuleRepository.loadExtractPatterns(context)
        return extract(text, patterns)
    }

    /** All non-overlapping ranges, using the same configured patterns and URL detector. */
    internal fun findAll(text: String, patterns: List<LinkExtractPattern>): List<LinkTextMatch> {
        val selected = selectLinkTextMatches(emptyList(), configuredMatches(text, patterns).toList())
        var offset = 0
        val detected = UrlDetector.findUrls(text).mapNotNull { url ->
            val start = text.indexOf(url, offset)
            if (start < 0) null else {
                offset = start + url.length
                LinkTextMatch(url, start, offset)
            }
        }
        return selectLinkTextMatches(selected, detected)
    }

    private fun configuredMatches(text: String, patterns: List<LinkExtractPattern>): Sequence<LinkTextMatch> =
        patterns.asSequence().filter { it.isEnabled && it.pattern.isNotBlank() }.flatMap { pattern ->
            compile(pattern.pattern)?.findAll(text)?.mapNotNull { match ->
                val value = match.value.trim()
                value.takeIf { it.isNotEmpty() }?.let {
                    val start = match.range.first + match.value.indexOf(value)
                    LinkTextMatch(value, start, start + value.length)
                }
            } ?: emptySequence()
        }

    private fun compile(pattern: String): Regex? =
        cachedRegex.getOrPut(pattern) {
            runCatching { Regex(pattern) }.getOrDefault(INVALID_PATTERN)
        }.takeIf { it !== INVALID_PATTERN }
}

internal data class LinkTextMatch(val url: String, val start: Int, val end: Int)

/** URL spans/custom rules take precedence; longer overlapping regex matches win. */
internal fun selectLinkTextMatches(
    preferred: List<LinkTextMatch>, candidates: List<LinkTextMatch>,
): List<LinkTextMatch> {
    val selected = preferred.filter { it.start >= 0 && it.end > it.start && it.url.isNotBlank() }.toMutableList()
    candidates.sortedByDescending { it.end - it.start }.forEach { candidate ->
        if (candidate.start >= 0 && candidate.end > candidate.start && candidate.url.isNotBlank() &&
            selected.none { candidate.start < it.end && candidate.end > it.start }) {
            selected += candidate
        }
    }
    return selected.sortedBy { it.start }
}
