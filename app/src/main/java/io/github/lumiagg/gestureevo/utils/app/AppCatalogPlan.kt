package io.github.lumiagg.gestureevo.utils.app

internal data class CatalogPackage(val packageName: String, val removed: Boolean)
internal data class AppCatalogPlan(
    val added: Set<String>,
    val reactivated: Set<String>,
    val removed: Set<String>,
)

internal fun planAppCatalogSync(installed: Set<String>, known: List<CatalogPackage>): AppCatalogPlan {
    val allKnown = known.mapTo(mutableSetOf()) { it.packageName }
    val active = known.filterNot { it.removed }.mapTo(mutableSetOf()) { it.packageName }
    return AppCatalogPlan(
        added = installed - allKnown,
        reactivated = (installed intersect allKnown) - active,
        removed = active - installed,
    )
}
