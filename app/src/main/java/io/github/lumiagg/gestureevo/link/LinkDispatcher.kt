package io.github.lumiagg.gestureevo.link

import android.content.Context
import android.util.Base64
import android.util.Log
import io.github.lumiagg.gestureevo.utils.UrlDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** Outcome of routing one link, so callers can decide whether to fall back to the browser. */
sealed class LinkDispatchResult {
    data class Routed(val rule: LinkDispatchRule, val url: String) : LinkDispatchResult()
    data class Exempted(val domain: String) : LinkDispatchResult()
    object NoRule : LinkDispatchResult()
    object Failed : LinkDispatchResult()
}

/**
 * Matches a URL against the configured rules and produces the URL that should be opened.
 *
 * Pure matching and rewriting live here with no Android UI dependencies, so they can be
 * exercised by unit tests. Launching is left to the caller.
 */
object LinkDispatcher {
    private const val TAG = "LinkDispatcher"

    private val cachedPatterns = ConcurrentHashMap<String, Regex>()
    private val resolvedShortLinks = ConcurrentHashMap<String, String>()

    /** Short links do not change, so a small in-memory cache avoids repeat round trips. */
    private const val SHORT_LINK_CACHE_LIMIT = 64

    fun matchRule(url: String, rule: LinkDispatchRule): Boolean {
        if (rule.pattern.isBlank()) return false
        return when (rule.matchType) {
            LinkMatchType.REGEX -> compile(rule.pattern)?.containsMatchIn(url) ?: false
            LinkMatchType.EXACT -> url.equals(rule.pattern, ignoreCase = true)
            LinkMatchType.CONTAINS -> matchesContains(url, rule.pattern)
        }
    }

    /**
     * A domain-shaped pattern is compared against the host so "example.com" does not match
     * "notexample.com". Anything else falls back to a plain substring search, which keeps
     * patterns like "share?id=" usable.
     */
    private fun matchesContains(url: String, pattern: String): Boolean {
        val trimmed = pattern.trim()
        if (looksLikeDomain(trimmed)) {
            val host = runCatching { URL(url).host }.getOrNull() ?: return false
            return host.equals(trimmed, ignoreCase = true) ||
                host.endsWith(".$trimmed", ignoreCase = true)
        }
        return url.contains(trimmed, ignoreCase = true)
    }

    private fun looksLikeDomain(value: String): Boolean =
        DOMAIN_SHAPE.containsMatchIn(value) && !value.contains('/') && !value.contains(' ')

    /**
     * Applies the rule template. Two placeholder families are supported:
     *
     * - `$1..$9` — regex capture groups from the rule pattern (local style).
     * - `{url}`, `{1}`..`{9}` — the LinkGo style, where groups come from the rule's
     *   extract pattern applied to the source text.
     *
     * LinkGo transform suffixes are honoured: `{1_url_dec}`, `{url_b64_enc}`,
     * `{1_url_dec_b64_dec}`.
     */
    fun applyTemplate(
        url: String,
        rule: LinkDispatchRule,
        extractGroups: List<String> = emptyList()
    ): String {
        val template = rule.template?.takeIf { it.isNotBlank() } ?: return url
        var result = resolveLinkGoPlaceholders(template, url, extractGroups)
        if (result == template && rule.matchType == LinkMatchType.REGEX) {
            // No LinkGo placeholder fired; fall back to $N back-references.
            val regex = compile(rule.pattern) ?: return url
            val match = regex.find(url) ?: return url
            result = template
            for (group in 0..match.groupValues.lastIndex) {
                val value = match.groupValues[group]
                result = result.replace("\$$group", value)
            }
        }
        return result
    }

    private val linkGoPlaceholderRegex =
        Regex("""\{(url|\d+)(?:_(url_dec|b64_enc|b64_dec|url_dec_b64_dec))?\}""")

    private fun resolveLinkGoPlaceholders(
        template: String,
        url: String,
        groups: List<String>
    ): String = linkGoPlaceholderRegex.replace(template) { match ->
        val key = match.groupValues[1]
        val transform = match.groupValues[2].ifEmpty { null }
        val raw = when (key) {
            "url" -> url
            else -> groups.getOrNull(key.toInt() - 1) ?: return@replace ""
        }
        applyTransform(raw, transform)
    }

    private fun applyTransform(value: String, transform: String?): String = when (transform) {
        "url_dec" -> runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        "b64_enc" -> Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
        "b64_dec" -> runCatching {
            String(Base64.decode(value, Base64.DEFAULT))
        }.getOrDefault(value)

        "url_dec_b64_dec" -> runCatching {
            val decoded = URLDecoder.decode(value, "UTF-8")
            String(Base64.decode(decoded, Base64.DEFAULT))
        }.getOrDefault(value)

        else -> value
    }

    fun isExempt(url: String, sourcePackage: String, exempts: List<LinkExemptDomain>): String? {
        val host = runCatching { URL(url).host?.lowercase() }.getOrNull() ?: return null
        return exempts.firstOrNull { exempt ->
            if (!exempt.isEnabled || exempt.domain.isBlank()) return@firstOrNull false
            if (exempt.sourcePackage.isNotEmpty() &&
                !exempt.sourcePackage.equals(sourcePackage, ignoreCase = true)
            ) {
                return@firstOrNull false
            }
            val domain = exempt.domain.lowercase()
            host == domain || host.endsWith(".$domain")
        }?.domain
    }

    /**
     * Picks the first enabled rule whose pattern matches. Rules are ordered by priority so a
     * user can put a specific host above a catch-all.
     */
    fun findRule(
        url: String,
        rules: List<LinkDispatchRule>,
        sourcePackage: String = ""
    ): LinkDispatchRule? = rules
        .asSequence()
        .filter { it.isEnabled }
        .filter { rule ->
            // Rules without a target are pure short-link resolvers (e.g. t.cn): they only make
            // sense when they re-dispatch, so they participate in matching but never launch.
            if (rule.targetPackage.isEmpty() && rule.resolveStrategy != LinkResolveStrategy.REDISPATCH) {
                return@filter false
            }
            matchRule(url, rule)
        }
        .sortedBy { it.priority }
        .firstOrNull()

    /**
     * Runs the full routing pipeline: exemption check, rule match, short link resolution and
     * template rewrite. Network access only happens when the matched rule asks for it.
     *
     * [sourceText] is the original text the link was taken from; rules with an extract pattern
     * use it to fill `{1}`..`{9}` capture groups.
     */
    suspend fun route(
        context: Context,
        rawUrl: String,
        sourcePackage: String = "",
        sourceText: String? = null
    ): LinkDispatchResult = withContext(Dispatchers.IO) {
        val normalized = UrlDetector.normalizeForOpening(rawUrl) ?: rawUrl
        val rules = LinkRuleRepository.loadRules(context)
        val exempts = LinkRuleRepository.loadExemptDomains(context)

        isExempt(normalized, sourcePackage, exempts)?.let { return@withContext LinkDispatchResult.Exempted(it) }

        val rule = findRule(normalized, rules, sourcePackage)
            ?: return@withContext LinkDispatchResult.NoRule

        // Extract groups come from the rule's own pattern against the source text, which is how
        // templates like "type={2}&mainid={1}" recover structured data from a shared link.
        val extractGroups = rule.extractPattern?.let { pattern ->
            sourceText?.let { text ->
                compile(pattern)?.find(text)?.groupValues?.drop(1)
            }
        }.orEmpty()

        var target = normalized
        if (rule.resolveShortLink && rule.resolveStrategy != LinkResolveStrategy.NONE) {
            val resolved = resolveShortLink(normalized)
            if (resolved != null && rule.resolveStrategy == LinkResolveStrategy.REDISPATCH) {
                // The resolved URL may belong to a different rule, so run the table again.
                val redispatched = findRule(resolved, rules, sourcePackage)
                if (redispatched != null) {
                    return@withContext LinkDispatchResult.Routed(
                        redispatched,
                        applyTemplate(resolved, redispatched, extractGroups)
                    )
                }
            }
            target = resolved ?: normalized
        }

        val finalUrl = applyTemplate(target, rule, extractGroups)
        if (finalUrl.isBlank()) return@withContext LinkDispatchResult.Failed
        LinkDispatchResult.Routed(rule, finalUrl)
    }

    /**
     * Follows redirects until a non-redirect response is reached. Only used for rules that opt
     * in; anything that fails leaves the original URL in place.
     */
    suspend fun resolveShortLink(url: String): String? = withContext(Dispatchers.IO) {
        resolvedShortLinks[url]?.let { return@withContext it }
        var current = url
        repeat(MAX_REDIRECTS) {
            val next = runCatching { headRedirect(current) }.getOrNull()
                ?: return@withContext null
            if (next == current) return@withContext null
            current = next
        }
        if (current == url) {
            null
        } else {
            if (resolvedShortLinks.size >= SHORT_LINK_CACHE_LIMIT) resolvedShortLinks.clear()
            resolvedShortLinks[url] = current
            current
        }
    }

    private fun headRedirect(url: String): String? {
        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = NETWORK_TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
        }.getOrNull() ?: return null

        return try {
            val code = connection.responseCode
            if (code in 300..399) connection.getHeaderField("Location") else null
        } catch (error: Exception) {
            Log.w(TAG, "Short link probe failed for ${url.take(60)}", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Compiles once per pattern. An invalid pattern is cached as [INVALID_PATTERN] so a bad
     * rule is not recompiled for every link; ConcurrentHashMap forbids null values, which is
     * why the sentinel exists.
     */
    private fun compile(pattern: String): Regex? =
        cachedPatterns.getOrPut(pattern) {
            runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
                .getOrDefault(INVALID_PATTERN)
        }.takeIf { it !== INVALID_PATTERN }

    private const val MAX_REDIRECTS = 5
    private const val NETWORK_TIMEOUT_MS = 5_000
    private val DOMAIN_SHAPE = Regex("^[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}$")
    private val INVALID_PATTERN = Regex("(?!)")
}
