package io.github.lumiagg.gestureevo.drop

/** Window identity is checked independently of focus changes caused by our accessibility overlays. */
internal object PressWindowPolicy {
    private const val APPLICATION = 1
    private const val ACCESSIBILITY_OVERLAY = 4
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Window(val id: Int, val type: Int, val packageName: String?, val bounds: Bounds,
        val active: Boolean = false, val focused: Boolean = false)

    fun cancellationReason(sourceId: Int, sourcePackage: String, sourceBounds: Bounds,
        windows: List<Window>, eventWindowId: Int, stateChanged: Boolean, removed: Boolean,
        ownPackage: String, knownOwnOverlays: Set<Int> = emptySet()): String? {
        if (removed && eventWindowId == sourceId) return "source_removed"
        val source = windows.firstOrNull { it.id == sourceId } ?: return "source_missing"
        if (source.type != APPLICATION || source.packageName != sourcePackage) return "source_identity_changed"
        if (source.bounds != sourceBounds) return "source_geometry_changed"
        if (EvoSourcePackages.contains(sourcePackage)) return "evo_source"
        if (windows.any { it.type == ACCESSIBILITY_OVERLAY && it.packageName != ownPackage &&
                EvoSourcePackages.contains(it.packageName) && (it.active || it.focused ||
                    (it.id == eventWindowId && it.bounds.right - it.bounds.left > 1 && it.bounds.bottom - it.bounds.top > 1)) }) return "other_evo_overlay"
        fun ownOverlay(window: Window) = window.type == ACCESSIBILITY_OVERLAY &&
            (window.packageName == ownPackage || window.id in knownOwnOverlays)
        if (windows.any { it.id != sourceId && !ownOverlay(it) && (it.active || it.focused) }) return "source_switched"
        if (stateChanged && eventWindowId != sourceId) {
            val changed = windows.firstOrNull { it.id == eventWindowId }
            if (changed != null && ownOverlay(changed)) return null
            if (changed == null && eventWindowId in knownOwnOverlays) return null
            return "other_window_state"
        }
        return null
    }
}
