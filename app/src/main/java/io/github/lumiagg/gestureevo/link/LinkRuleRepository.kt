package io.github.lumiagg.gestureevo.link

import android.content.Context
import io.github.lumiagg.gestureevo.utils.MoshiUtils
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore

/**
 * Persists the link routing table in the shared settings DataStore.
 *
 * Each collection is stored as a single JSON string so a partial write can never leave the
 * rule table half-updated. Writes go through [settingsStore], which the rest of the app
 * already observes for theme and gesture changes.
 */
object LinkRuleRepository {
    private const val KEY_RULES = "link_dispatch_rules"
    private const val KEY_EXTRACTS = "link_extract_patterns"
    private const val KEY_EXEMPTS = "link_exempt_domains"
    private const val KEY_SEEDED = "link_rules_seeded"

    suspend fun loadRules(context: Context): List<LinkDispatchRule> {
        val json = context.settingsStore.readKey(KEY_RULES, "")
        if (json.isEmpty()) return emptyList()
        return MoshiUtils.toModel<List<LinkDispatchRule>>(json).orEmpty()
            .sortedBy { it.priority }
    }

    suspend fun saveRules(context: Context, rules: List<LinkDispatchRule>) {
        context.settingsStore.saveKey(KEY_RULES, MoshiUtils.toJson(rules))
    }

    suspend fun loadExtractPatterns(context: Context): List<LinkExtractPattern> {
        seedBuiltinExtractsIfNeeded(context)
        val json = context.settingsStore.readKey(KEY_EXTRACTS, "")
        if (json.isEmpty()) return LinkExtractPattern.builtinDefaults()
        return MoshiUtils.toModel<List<LinkExtractPattern>>(json)
            ?: LinkExtractPattern.builtinDefaults()
    }

    suspend fun saveExtractPatterns(context: Context, patterns: List<LinkExtractPattern>) {
        context.settingsStore.saveKey(KEY_EXTRACTS, MoshiUtils.toJson(patterns))
    }

    suspend fun loadExemptDomains(context: Context): List<LinkExemptDomain> {
        val json = context.settingsStore.readKey(KEY_EXEMPTS, "")
        if (json.isEmpty()) return emptyList()
        return MoshiUtils.toModel<List<LinkExemptDomain>>(json).orEmpty()
    }

    suspend fun saveExemptDomains(context: Context, domains: List<LinkExemptDomain>) {
        context.settingsStore.saveKey(KEY_EXEMPTS, MoshiUtils.toJson(domains))
    }

    /**
     * Writes the built-in extraction patterns once, so users can edit or delete them later
     * instead of having them reappear on every launch.
     */
    private suspend fun seedBuiltinExtractsIfNeeded(context: Context) {
        if (context.settingsStore.readKey(KEY_SEEDED, false)) return
        val existing = context.settingsStore.readKey(KEY_EXTRACTS, "")
        if (existing.isEmpty()) {
            context.settingsStore.saveKey(
                KEY_EXTRACTS,
                MoshiUtils.toJson(LinkExtractPattern.builtinDefaults())
            )
        }
        context.settingsStore.saveKey(KEY_SEEDED, true)
    }
}
