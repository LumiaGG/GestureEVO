package io.github.lumiagg.gestureevo.search

internal class ExternalEngineHover {
    var position: Int = -1
        private set
    private var longPressed = false

    fun moveTo(position: Int): Boolean {
        if (this.position == position) return false
        this.position = position
        longPressed = false
        return true
    }

    fun triggerLongPress(): Boolean {
        if (position < 0 || longPressed) return false
        longPressed = true
        return true
    }

    fun isLongPressDrop(position: Int): Boolean =
        position >= 0 && position == this.position && longPressed
}
