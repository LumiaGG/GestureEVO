package io.github.lumiagg.gestureevo.link

import com.squareup.moshi.JsonClass
import io.github.lumiagg.gestureevo.utils.MoshiUtils
import java.util.UUID

/**
 * Serialisable snapshot of the whole link routing configuration.
 *
 * Import and export share one shape so a backup taken on one device restores exactly on
 * another. Unknown or missing fields fall back to defaults, which keeps older backups usable
 * after the model grows.
 */
@JsonClass(generateAdapter = true)
data class LinkConfigBackup(
    val version: Int = CURRENT_VERSION,
    val rules: List<LinkDispatchRule> = emptyList(),
    val exempts: List<LinkExemptDomain> = emptyList(),
    val extracts: List<LinkExtractPattern> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Result of an import attempt, so callers can report what actually happened. */
data class LinkImportResult(
    val rulesAdded: Int,
    val exemptsAdded: Int,
    val extractsAdded: Int,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * Converts the configuration to and from JSON.
 *
 * Import is additive and never overwrites: entries are matched by pattern (rules) or domain
 * (exemptions) and duplicates are skipped. That way importing the same file twice is harmless
 * and a partial backup cannot destroy an existing setup.
 */
object LinkConfigCodec {

    fun export(
        rules: List<LinkDispatchRule>,
        exempts: List<LinkExemptDomain>,
        extracts: List<LinkExtractPattern>
    ): String {
        val backup = LinkConfigBackup(
            rules = rules,
            exempts = exempts,
            extracts = extracts
        )
        return MoshiUtils.toJson(backup, format = true)
    }

    fun decode(json: String): LinkConfigBackup? = runCatching {
        MoshiUtils.toModel<LinkConfigBackup>(json)
    }.getOrNull()

    /**
     * Merges [incoming] into the existing configuration.
     *
     * New ids are assigned to imported entries so they cannot collide with existing rows that
     * happen to share an id but represent different content.
     */
    fun merge(
        existingRules: List<LinkDispatchRule>,
        existingExempts: List<LinkExemptDomain>,
        existingExtracts: List<LinkExtractPattern>,
        incoming: LinkConfigBackup
    ): Pair<LinkConfigBackup, LinkImportResult> {
        val knownPatterns = existingRules.map { it.pattern }.toMutableSet()
        val knownDomains = existingExempts.map { it.domain.lowercase() to it.sourcePackage }.toMutableSet()
        val knownExtractIds = existingExtracts.map { it.id }.toMutableSet()
        val knownExtractPatterns = existingExtracts.map { it.pattern }.toMutableSet()

        val newRules = incoming.rules.filter { rule ->
            rule.pattern.isNotBlank() && knownPatterns.add(rule.pattern)
        }.map { it.copy(id = UUID.randomUUID().toString()) }
        val newExempts = incoming.exempts.filter { exempt ->
            exempt.domain.isNotBlank() && knownDomains.add(exempt.domain.lowercase() to exempt.sourcePackage)
        }.map { it.copy(id = UUID.randomUUID().toString()) }
        val newExtracts = incoming.extracts.filter { pattern ->
            pattern.pattern.isNotBlank() && knownExtractPatterns.add(pattern.pattern) &&
                (!pattern.isBuiltin || knownExtractIds.add(pattern.id))
        }.map {
            if (it.isBuiltin) it else it.copy(id = UUID.randomUUID().toString())
        }

        val merged = LinkConfigBackup(
            rules = existingRules + newRules,
            exempts = existingExempts + newExempts,
            extracts = existingExtracts + newExtracts
        )
        val result = LinkImportResult(
            rulesAdded = newRules.size,
            exemptsAdded = newExempts.size,
            extractsAdded = newExtracts.size
        )
        return merged to result
    }
}
