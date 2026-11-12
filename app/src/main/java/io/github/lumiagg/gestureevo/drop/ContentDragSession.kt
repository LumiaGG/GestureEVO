package io.github.lumiagg.gestureevo.drop

/** A content drag becomes usable only after the current source and first panel layout are ready. */
internal class ContentDragSession {
    private var generation = 0
    private var pending = false
    var ready = false
        private set
    fun begin(): Int {
        generation++
        pending = true
        ready = false
        return generation
    }
    fun isCurrent(request: Int): Boolean = pending && generation == request
    fun confirm(request: Int, sourceCurrent: Boolean, laidOut: Boolean): Boolean {
        if (!isCurrent(request) || ready || !sourceCurrent || !laidOut) return false
        ready = true
        return true
    }
    fun cancel() {
        generation++
        pending = false
        ready = false
    }
}

internal object EvoSourcePackages {
    fun contains(packageName: String?): Boolean = packageName == "io.github.lumiagg.gestureevo" ||
        packageName == "io.github.lumiagg.gestureevo.debug"
}
