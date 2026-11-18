package io.github.lumiagg.gestureevo.pointer

internal enum class PointerHoverAction { LONG_PRESS, DOUBLE_TAP, SELECT, OPEN_LINK, DISMISS }

/** A menu owns the release even when cancelled; it must never fall through to a screen tap. */
internal class PointerHoverState {
    enum class Phase { IDLE, MENU, SELECTING, FINISHED }

    var phase = Phase.IDLE
        private set
    var selected: PointerHoverAction? = null
        private set
    var hasOpened = false
        private set
    val isSelectionPreviewActive: Boolean
        get() = phase == Phase.MENU && selected == PointerHoverAction.SELECT

    fun reset() { phase = Phase.IDLE; selected = null; hasOpened = false }
    fun show(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.MENU
        hasOpened = true
        return true
    }
    fun highlight(action: PointerHoverAction?) {
        if (phase == Phase.MENU) selected = action
    }
    fun enterSelection(): Boolean {
        if (phase != Phase.MENU || selected != PointerHoverAction.SELECT) return false
        phase = Phase.SELECTING
        selected = null
        return true
    }
    fun release(): PointerHoverAction? {
        val action = selected.takeIf { phase == Phase.MENU && it != PointerHoverAction.SELECT && it != PointerHoverAction.DISMISS }
        phase = Phase.FINISHED
        selected = null
        return action
    }
    fun cancel() { phase = Phase.FINISHED; selected = null }

    /** Dismissing a preview rearms hovering, but this session still owns the eventual release. */
    fun dismiss() {
        if (phase == Phase.MENU || phase == Phase.SELECTING) {
            phase = Phase.IDLE
            selected = null
        }
    }
}

internal data class HoverMenuPosition(val x: Float, val y: Float)

internal fun placeHoverMenu(
    targetX: Float, targetY: Float, menuWidth: Int, menuHeight: Int,
    left: Float, top: Float, right: Float, bottom: Float, gap: Float,
): HoverMenuPosition {
    val preferredX = if (targetX + gap + menuWidth <= right) targetX + gap else targetX - gap - menuWidth
    return HoverMenuPosition(
        preferredX.coerceIn(left, (right - menuWidth).coerceAtLeast(left)),
        (targetY - menuHeight / 2f).coerceIn(top, (bottom - menuHeight).coerceAtLeast(top)),
    )
}

/** Apply the return-to-pointer offset while preserving screen bounds. */
internal fun rebasePointerPosition(mapped: Float, offset: Float, limit: Int): Float =
    (mapped + offset).coerceIn(0f, limit.toFloat())
