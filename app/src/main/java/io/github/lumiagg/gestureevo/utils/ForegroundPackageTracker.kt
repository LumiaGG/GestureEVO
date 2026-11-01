package io.github.lumiagg.gestureevo.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Tracks window changes even when an accessibility event has no usable package or class. */
internal class ForegroundPackageTracker(
    private val scope: CoroutineScope,
    private val resolvePackage: () -> String?,
    private val confirmationDelayMs: Long = 500L,
) {
    private val current = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = current.asStateFlow()
    private var confirmationJob: Job? = null

    @Synchronized
    fun onWindowStateChanged(trustedPackage: String?) {
        // Activity events can arrive before the application window tree catches up.
        if (trustedPackage != null) current.value = trustedPackage else refresh()
        scheduleConfirmation()
    }

    @Synchronized
    fun onWindowsChanged() {
        // Window-list events often have neither packageName nor className.
        refresh()
        scheduleConfirmation()
    }

    @Synchronized
    fun refresh(): String? {
        resolvePackage()?.let { current.value = it }
        return current.value
    }

    @Synchronized
    fun clear() {
        confirmationJob?.cancel()
        confirmationJob = null
        current.value = null
    }

    private fun scheduleConfirmation() {
        confirmationJob?.cancel()
        confirmationJob = scope.launch {
            delay(confirmationDelayMs)
            refresh()
        }
    }
}
