package io.github.lumiagg.gestureevo.utils.shortcuts

/** Pure coordination: stage before the external pin, publish only after confirmation. */
internal data class ShortcutIdentity(val packageName: String, val id: String, val userSerial: Long)

internal interface ShortcutImportBackend {
    suspend fun pinnedIds(packageName: String, userSerial: Long): Set<String>
    suspend fun stage(shortcuts: List<ShortcutIdentity>)
    suspend fun pin(packageName: String, userSerial: Long, ids: Set<String>)
    suspend fun confirm(shortcuts: List<ShortcutIdentity>)
}

internal suspend fun importShortcutGroup(
    backend: ShortcutImportBackend,
    selected: List<ShortcutIdentity>,
) {
    require(selected.isNotEmpty())
    val first = selected.first()
    require(selected.all { it.packageName == first.packageName && it.userSerial == first.userSerial })
    val existing = backend.pinnedIds(first.packageName, first.userSerial)
    backend.stage(selected)
    // pinShortcuts replaces this caller's pins; retain all its existing pins.
    backend.pin(first.packageName, first.userSerial, existing + selected.map { it.id })
    val actual = backend.pinnedIds(first.packageName, first.userSerial)
    val confirmed = selected.filter { it.id in actual }
    backend.confirm(confirmed)
    check(confirmed.size == selected.size) { "Some shortcuts were not pinned" }
}
