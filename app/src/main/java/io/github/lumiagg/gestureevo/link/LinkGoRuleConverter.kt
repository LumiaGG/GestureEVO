package io.github.lumiagg.gestureevo.link

import android.content.Context
import com.squareup.moshi.JsonClass
import io.github.lumiagg.gestureevo.utils.MoshiUtils

/**
 * Parses rules exported from LinkGo (`{"linkgo_rules":[...]}`) into the local model.
 *
 * Three LinkGo concepts need translation:
 *
 * 1. `targetPackage` may be `pkg/activity`; the activity becomes a component launch.
 * 2. Templates use LinkGo placeholders — `{url}`, `{1}` capture groups and the transform
 *    suffixes `_url_dec` / `_b64_enc` / `_url_dec_b64_dec`.
 * 3. A few rules act as pure resolvers: they have no target app and only follow a short link,
 *    then re-run dispatch. Those keep an empty package and never launch an app directly.
 */
object LinkGoRuleConverter {

    @JsonClass(generateAdapter = true)
    data class LinkGoExport(val linkgo_rules: List<LinkGoRule> = emptyList())

    @JsonClass(generateAdapter = true)
    data class LinkGoRule(
        val id: String = "",
        val name: String = "",
        val pattern: String = "",
        val matchType: String = "REGEX",
        val targetPackage: String = "",
        val template: String? = null,
        val extractPattern: String? = null,
        val isEnabled: Boolean = true,
        val resolveShortLink: Boolean = false,
        val resolveStrategy: String = "NONE",
        val excludeFromRecents: Boolean = false
    )

    /** Parses an exported LinkGo file body. Accepts the wrapped or bare array form. */
    fun parse(json: String): List<LinkDispatchRule> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        val goRules: List<LinkGoRule> = if (trimmed.startsWith('[')) {
            MoshiUtils.toModel<List<LinkGoRule>>(trimmed).orEmpty()
        } else {
            MoshiUtils.toModel<LinkGoExport>(trimmed)?.linkgo_rules.orEmpty()
        }
        return goRules.mapIndexed { index, rule -> convert(rule, index) }
    }

    /** Loads the bundled LinkGo rule set from assets. */
    fun loadBuiltin(context: Context): List<LinkDispatchRule> = runCatching {
        val json = context.assets.open("linkgo_builtin_rules.json")
            .bufferedReader().use { it.readText() }
        parse(json)
    }.getOrDefault(emptyList())

    private fun convert(rule: LinkGoRule, index: Int): LinkDispatchRule {
        val (pkg, component) = splitTarget(rule.targetPackage)
        return LinkDispatchRule(
            name = rule.name,
            pattern = rule.pattern,
            targetPackage = pkg,
            component = component,
            matchType = parseEnum(rule.matchType, LinkMatchType.CONTAINS),
            template = rule.template?.takeIf { it.isNotEmpty() },
            extractPattern = rule.extractPattern?.takeIf { it.isNotEmpty() },
            isEnabled = rule.isEnabled,
            resolveShortLink = rule.resolveShortLink,
            resolveStrategy = parseEnum(rule.resolveStrategy, LinkResolveStrategy.NONE),
            excludeFromRecents = rule.excludeFromRecents,
            priority = index
        )
    }

    /** `pkg/activity` form pins a component; a bare package lets the app resolve it. */
    fun splitTarget(target: String): Pair<String, String?> {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return "" to null
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.length - 1) return trimmed to null
        return trimmed.substring(0, slash) to trimmed.substring(slash + 1)
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
}
