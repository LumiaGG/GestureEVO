package io.github.lumiagg.gestureevo.link

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.squareup.moshi.JsonClass
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
    private const val KEY_LEGACY_MIGRATED = "link_legacy_domains_migrated"

    suspend fun loadRules(context: Context): List<LinkDispatchRule> {
        migrateLegacyDomains(context)
        val json = context.settingsStore.readKey(KEY_RULES, "")
        if (json.isEmpty()) return emptyList()
        return MoshiUtils.toModel<List<LinkDispatchRule>>(json).orEmpty()
            .sortedBy { it.priority }
    }

    suspend fun saveRules(context: Context, rules: List<LinkDispatchRule>) {
        migrateLegacyDomains(context)
        context.settingsStore.saveKey(KEY_RULES, MoshiUtils.toJson(rules))
    }

    private suspend fun migrateLegacyDomains(context: Context) {
        if (context.settingsStore.readKey(KEY_LEGACY_MIGRATED, false)) return
        context.settingsStore.edit { prefs ->
            val migratedKey = booleanPreferencesKey(KEY_LEGACY_MIGRATED)
            if (prefs[migratedKey] == true) return@edit
            val rulesKey = stringPreferencesKey(KEY_RULES)
            val existing = MoshiUtils.toModel<List<LinkDispatchRule>>(prefs[rulesKey] ?: "[]").orEmpty()
            val legacy = MoshiUtils.toModel<List<LegacyLinkDomain>>(
                prefs[stringPreferencesKey("domain_app_list_simple")] ?: "[]"
            ).orEmpty()
            prefs[rulesKey] = MoshiUtils.toJson(mergeLegacyDomains(existing, legacy))
            prefs[migratedKey] = true
        }
    }

    internal fun mergeLegacyDomains(
        existing: List<LinkDispatchRule>,
        legacy: List<LegacyLinkDomain>
    ): List<LinkDispatchRule> {
        val known = existing.map { it.pattern.lowercase() }.toMutableSet()
        val nextPriority = (existing.maxOfOrNull { it.priority } ?: -1) + 1
        val imported = legacy.sortedByDescending { it.domain.length }.mapNotNull { entry ->
            val domain = entry.domain.trim().trim('.').lowercase().removePrefix("www.")
            if (entry.pkg.isBlank() || domain.isBlank() || !known.add(domain)) return@mapNotNull null
            LinkDispatchRule(name = domain, pattern = domain, targetPackage = entry.pkg)
        }.mapIndexed { index, rule -> rule.copy(priority = nextPriority + index) }
        return existing + imported
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

/** Retains the JSON field names used by the previous domain-to-app settings. */
@JsonClass(generateAdapter = true)
internal data class LegacyLinkDomain(val pkg: String, val domain: String)
