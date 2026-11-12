package io.github.lumiagg.gestureevo.drop

/** Window identity is checked independently of focus changes caused by our accessibility overlays. */
internal object PressWindowPolicy {
    private const val APPLICATION = 1
    private const val ACCESSIBILITY_OVERLAY = 4
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Window(val id: Int, val type: Int, val packageName: String?, val bounds: Bounds,
        val active: Boolean = false, val focused: Boolean = false)

    // Root cancellation can finish after the source app has already opened a small menu.
    private fun isSourceMenuPopup(window: Window, sourcePackage: String, sourceBounds: Bounds): Boolean {
        if (window.type != APPLICATION || window.packageName != sourcePackage) return false
        val width = window.bounds.right - window.bounds.left
        val height = window.bounds.bottom - window.bounds.top
        val sourceWidth = sourceBounds.right - sourceBounds.left
        val sourceHeight = sourceBounds.bottom - sourceBounds.top
        return width > 0 && height > 0 && width < sourceWidth && height < sourceHeight &&
            width.toLong() * height * 2 < sourceWidth.toLong() * sourceHeight
    }

    // Some application menus hide the original window from accessibility without removing it.
    fun isSourceCoveredByMenu(sourceId: Int, sourcePackage: String, sourceBounds: Bounds,
        windows: List<Window>, ownPackage: String): Boolean {
        if (windows.any { it.id == sourceId }) return false
        val applications = windows.filter { it.type == APPLICATION }
        if (applications.isEmpty() || applications.none { it.active || it.focused }) return false
        if (applications.any { !isSourceMenuPopup(it, sourcePackage, sourceBounds) ||
                it.bounds.left < sourceBounds.left || it.bounds.top < sourceBounds.top ||
                it.bounds.right > sourceBounds.right || it.bounds.bottom > sourceBounds.bottom }) return false
        return windows.none { (it.active || it.focused) && it.type != APPLICATION &&
            !(it.type == ACCESSIBILITY_OVERLAY && it.packageName == ownPackage) }
    }

    fun cancellationReason(sourceId: Int, sourcePackage: String, sourceBounds: Bounds,
        windows: List<Window>, eventWindowId: Int, stateChanged: Boolean, removed: Boolean,
        ownPackage: String, knownOwnOverlays: Set<Int> = emptySet(), allowSourceMenu: Boolean = false): String? {
        if (removed && eventWindowId == sourceId) return "source_removed"
        val source = windows.firstOrNull { it.id == sourceId } ?: return if (allowSourceMenu &&
            isSourceCoveredByMenu(sourceId, sourcePackage, sourceBounds, windows, ownPackage)) null else "source_missing"
        if (source.type != APPLICATION || source.packageName != sourcePackage) return "source_identity_changed"
        if (source.bounds != sourceBounds) return "source_geometry_changed"
        if (EvoSourcePackages.contains(sourcePackage)) return "evo_source"
        if (windows.any { it.type == ACCESSIBILITY_OVERLAY && it.packageName != ownPackage &&
                EvoSourcePackages.contains(it.packageName) && (it.active || it.focused ||
                    (it.id == eventWindowId && it.bounds.right - it.bounds.left > 1 && it.bounds.bottom - it.bounds.top > 1)) }) return "other_evo_overlay"
        fun ownOverlay(window: Window) = window.type == ACCESSIBILITY_OVERLAY &&
            (window.packageName == ownPackage || window.id in knownOwnOverlays)
        fun sourceMenuPopup(window: Window) = allowSourceMenu && isSourceMenuPopup(window, sourcePackage, sourceBounds)
        if (windows.any { it.id != sourceId && !ownOverlay(it) && !sourceMenuPopup(it) && (it.active || it.focused) }) return "source_switched"
        if (stateChanged && eventWindowId != sourceId) {
            val changed = windows.firstOrNull { it.id == eventWindowId }
            if (changed != null && (ownOverlay(changed) || sourceMenuPopup(changed))) return null
            if (changed == null && eventWindowId in knownOwnOverlays) return null
            return "other_window_state"
        }
        return null
    }
}
