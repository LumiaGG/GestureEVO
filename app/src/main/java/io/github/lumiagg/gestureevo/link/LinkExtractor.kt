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

        val fromPatterns = patterns
            .asSequence()
            .filter { it.isEnabled && it.pattern.isNotBlank() }
            .mapNotNull { pattern ->
                val regex = compile(pattern.pattern) ?: return@mapNotNull null
                regex.find(text)?.value?.trim()
            }
            .filter { it.isNotEmpty() }
            .maxByOrNull { it.length }

        if (fromPatterns != null) return fromPatterns

        // Fall through to the general detector, which understands bare domains and full-width
        // characters that a user pattern is unlikely to cover.
        return UrlDetector.findUrls(text).firstOrNull()
    }

    suspend fun extractWithStoredPatterns(context: Context, text: String): String? {
        val patterns = LinkRuleRepository.loadExtractPatterns(context)
        return extract(text, patterns)
    }

    private fun compile(pattern: String): Regex? =
        cachedRegex.getOrPut(pattern) {
            runCatching { Regex(pattern) }.getOrDefault(INVALID_PATTERN)
        }.takeIf { it !== INVALID_PATTERN }
}
