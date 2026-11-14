package io.github.lumiagg.gestureevo.drop

/** Shares one successful interruption between early protection and the eventual drag. */
internal class PressTouchInterruptionState {
    var protectionRequested = false
        private set
    var succeeded = false
        private set
    private var pending = false
    private var generation = 0

    fun beginProtection(): Int? {
        if (protectionRequested || pending || succeeded) return null
        protectionRequested = true
        return beginDrag()
    }

    fun beginDrag(): Int? {
        if (pending || succeeded) return null
        pending = true
        return ++generation
    }

    fun complete(request: Int, success: Boolean): Boolean {
        if (!pending || request != generation) return false
        pending = false
        succeeded = success
        return true
    }

    fun clear() {
        generation++
        pending = false
        succeeded = false
        protectionRequested = false
    }
}
