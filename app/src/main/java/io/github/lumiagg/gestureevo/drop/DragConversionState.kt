package io.github.lumiagg.gestureevo.drop

internal class DragConversionState<T> {
    var content: T? = null
        private set
    var alternate: T? = null
        private set
    var busy = false
        private set
    private var generation = 0
    private var hovered = false
    private var triggered = false

    fun hover(active: Boolean): Boolean {
        if (hovered == active) return false
        hovered = active
        if (!active) triggered = false
        return true
    }

    fun retain(value: T) {
        clear()
        content = value
    }

    fun begin(): Int? {
        if (content == null || busy || !hovered || triggered) return null
        triggered = true
        busy = true
        return ++generation
    }

    fun complete(request: Int, value: T): Boolean {
        if (!busy || request != generation) return false
        alternate = content
        content = value
        busy = false
        return true
    }

    fun fail(request: Int): Boolean {
        if (!busy || request != generation) return false
        busy = false
        return true
    }

    fun clear() {
        generation++
        content = null
        alternate = null
        busy = false
        hovered = false
        triggered = false
    }
}
