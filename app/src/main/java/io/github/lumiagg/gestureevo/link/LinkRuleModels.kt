package io.github.lumiagg.gestureevo.link

import com.squareup.moshi.JsonClass
import java.util.UUID

/** How a rule's pattern is compared against the URL. */
enum class LinkMatchType {
    CONTAINS,
    REGEX,
    EXACT
}

/**
 * What to do once a short link has been resolved.
 *
 * NONE never performs a network request. DIRECT resolves and then launches the resolved URL
 * with the rule that matched. REDISPATCH resolves and runs the whole rule table again, which
 * lets a short link point at a host that another rule owns.
 */
enum class LinkResolveStrategy {
    NONE,
    DIRECT,
    REDISPATCH
}

/**
 * One dispatch rule: which links belong to which app, and how to rewrite them first.
 *
 * The pattern is matched against the normalised URL. Template supports $1..$9 back references
 * taken from the pattern when [LinkMatchType.REGEX] is used.
 */
@JsonClass(generateAdapter = true)
data class LinkDispatchRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val pattern: String = "",
    val targetPackage: String = "",
    val matchType: LinkMatchType = LinkMatchType.CONTAINS,
    val template: String? = null,
    val extractPattern: String? = null,
    val isEnabled: Boolean = true,
    val resolveShortLink: Boolean = false,
    val resolveStrategy: LinkResolveStrategy = LinkResolveStrategy.NONE,
    val excludeFromRecents: Boolean = false,
    /** Rule order matters: the first match wins, so users can put specific rules first. */
    val priority: Int = 0,
    /** Optional custom icon path; falls back to the target app icon when empty. */
    val iconPath: String? = null,
    /** How many links this rule has dispatched, used to spot rules that never fire. */
    val hitCount: Int = 0,
    val lastHitAt: Long = 0L,
    /** Optional activity for rules that must launch a specific component (pkg/activity form). */
    val component: String? = null
)

/**
 * A named regex used to pull a link out of a longer piece of text. Kept separate from dispatch
 * rules so the same extraction can feed several rules.
 */
@JsonClass(generateAdapter = true)
data class LinkExtractPattern(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val pattern: String = "",
    val isEnabled: Boolean = true,
    val isBuiltin: Boolean = false
) {
    companion object {
        const val BUILTIN_HTTP_ID = "builtin_http"
        const val BUILTIN_MAGNET_ID = "builtin_magnet"
        const val BUILTIN_CODE_ID = "builtin_code"

        /** Patterns that cover the common cases out of the box. */
        fun builtinDefaults(): List<LinkExtractPattern> = listOf(
            LinkExtractPattern(
                id = BUILTIN_HTTP_ID,
                name = "标准链接",
                pattern = "(?i)(?:[a-zA-Z0-9+.-]+://)[^\\s\\u4e00-\\u9fa5]+(?<![.,!?])",
                isBuiltin = true
            ),
            LinkExtractPattern(
                id = BUILTIN_MAGNET_ID,
                name = "磁力链接",
                pattern = "(?i)magnet:\\?xt=urn:btih:[a-zA-Z0-9]+[^\\s\\u4e00-\\u9fa5<>\\[\\]{}|^]*",
                isBuiltin = true
            ),
            LinkExtractPattern(
                id = BUILTIN_CODE_ID,
                name = "带提取码链接",
                pattern = "(?i)https?://[^\\s\\u4e00-\\u9fa5<>\\[\\]{}|^]+(?:\\s*(?:提取码|访问码)[：:\\s]\\s*[a-zA-Z0-9]+|\\?pwd=[a-zA-Z0-9]+)",
                isBuiltin = true
            )
        )
    }
}

/** Domains that must never be intercepted, so a rule cannot capture them by accident. */
@JsonClass(generateAdapter = true)
data class LinkExemptDomain(
    val id: String = UUID.randomUUID().toString(),
    val domain: String = "",
    /** Empty means the exemption applies to every source app. */
    val sourcePackage: String = "",
    val isEnabled: Boolean = true
)
