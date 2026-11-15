package io.github.lumiagg.gestureevo.link

import android.content.Context
import android.util.Base64
import android.util.Log
import io.github.lumiagg.gestureevo.utils.UrlDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** Outcome of routing one link, so callers can decide whether to fall back to the browser. */
sealed class LinkDispatchResult {
    data class Routed(val rule: LinkDispatchRule, val url: String) : LinkDispatchResult()
    data class Exempted(val domain: String, val url: String) : LinkDispatchResult()
    data class SystemDefault(val url: String) : LinkDispatchResult()
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
        val captureMatch = if (rule.matchType == LinkMatchType.REGEX && BACK_REFERENCE.containsMatchIn(template)) {
            val regex = compile(rule.pattern) ?: return url
            regex.find(url) ?: return url
        } else null
        // Replace once so captured values containing placeholder syntax remain literal.
        return TEMPLATE_PLACEHOLDER.replace(template) { match ->
            val backReference = match.groupValues[1]
            if (backReference.isNotEmpty()) {
                return@replace captureMatch?.groupValues?.getOrNull(backReference.toInt()) ?: match.value
            }
            val key = match.groupValues[2]
            val raw = if (key == "url") url else
                extractGroups.getOrNull(key.toInt() - 1) ?: return@replace ""
            applyTransform(raw, match.groupValues[3].ifEmpty { null })
        }
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

        routeWithRules(normalized, rules, exempts, sourcePackage, sourceText)
    }

    internal suspend fun routeWithRules(
        url: String,
        rules: List<LinkDispatchRule>,
        exempts: List<LinkExemptDomain> = emptyList(),
        sourcePackage: String = "",
        sourceText: String? = null,
        resolver: suspend (String) -> String? = { resolveShortLink(it) }
    ): LinkDispatchResult {
        var target = url
        val visited = mutableSetOf<String>()
        repeat(MAX_REDIRECTS + 1) {
            if (!visited.add(target)) return LinkDispatchResult.Failed
            coroutineContext.ensureActive()
            isExempt(target, sourcePackage, exempts)?.let { return LinkDispatchResult.Exempted(it, target) }
            val rule = findRule(target, rules, sourcePackage)
                ?: return if (target == url) LinkDispatchResult.NoRule else
                    LinkDispatchResult.SystemDefault(target)
            if (rule.resolveShortLink && rule.resolveStrategy != LinkResolveStrategy.NONE) {
                val resolved = resolver(target)
                if (resolved != null && resolved != target && rule.resolveStrategy == LinkResolveStrategy.REDISPATCH) {
                    target = resolved
                    return@repeat
                }
                target = resolved ?: target
            }
            if (rule.targetPackage.isBlank()) return LinkDispatchResult.SystemDefault(target)
            // Radar calls have no source text; the URL itself must still supply captures.
            val groups = rule.extractPattern?.let { pattern ->
                compile(pattern)?.find(sourceText ?: target)?.groupValues?.drop(1)
                    ?: compile(pattern)?.find(target)?.groupValues?.drop(1)
            }.orEmpty()
            val finalUrl = applyTemplate(target, rule, groups)
            return if (finalUrl.isBlank()) LinkDispatchResult.Failed else
                LinkDispatchResult.Routed(rule, finalUrl)
        }
        return LinkDispatchResult.Failed
    }

    /**
     * Follows redirects until a non-redirect response is reached. Only used for rules that opt
     * in; anything that fails leaves the original URL in place.
     */
    suspend fun resolveShortLink(url: String): String? = withContext(Dispatchers.IO) {
        resolvedShortLinks[url]?.let { return@withContext it }
        val current = followRedirects(url) { headRedirect(it) }
        if (current != null) {
            if (resolvedShortLinks.size >= SHORT_LINK_CACHE_LIMIT) resolvedShortLinks.clear()
            resolvedShortLinks[url] = current
        }
        current
    }

    /** A null probe means failure; an unchanged URL means a successful terminal response. */
    internal suspend fun followRedirects(url: String, probe: (String) -> String?): String? {
        var current = url
        val visited = mutableSetOf(url)
        repeat(MAX_REDIRECTS + 1) { hop ->
            coroutineContext.ensureActive()
            val next = probe(current) ?: return null
            if (next == current) return current.takeIf { it != url }
            if (hop == MAX_REDIRECTS || !visited.add(next)) return null
            current = next
        }
        return null
    }

    internal fun headRedirect(url: String): String? {
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
            when (code) {
                301, 302, 303, 307, 308 -> connection.getHeaderField("Location")
                    ?.let { URL(URL(url), it).takeIf { next -> next.protocol in listOf("http", "https") }?.toString() }
                in 200..299 -> url
                else -> null
            }
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
    private val BACK_REFERENCE = Regex("""\$(\d)""")
    private val TEMPLATE_PLACEHOLDER =
        Regex("""\$(\d)|\{(url|\d+)(?:_(url_dec|b64_enc|b64_dec|url_dec_b64_dec))?\}""")
}
