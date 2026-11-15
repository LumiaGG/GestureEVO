package io.github.lumiagg.gestureevo.drop

/** Once DOWN is attempted, CANCEL must run even if the request ends or the injection fails. */
internal object TouchInterruptionSequence {
    fun run(
        isCurrent: () -> Boolean,
        down: () -> Boolean,
        cancel: () -> Boolean,
        pause: () -> Unit,
    ): Boolean {
        if (!isCurrent()) return false
        var cancelled = false
        val accepted = try {
            down().also { if (it) pause() }
        } finally {
            cancelled = cancel()
        }
        return accepted && cancelled
    }
}
