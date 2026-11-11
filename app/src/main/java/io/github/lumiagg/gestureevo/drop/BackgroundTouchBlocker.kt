package io.github.lumiagg.gestureevo.drop

import io.github.lumiagg.gestureevo.utils.shizuku.ShizukuServiceHelper
import java.util.concurrent.Executors

/** Adapted from MMGG BackgroundTouchBlocker (GPL-3.0-only): cancellation stays best effort and serial. */
internal class BackgroundTouchBlocker {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "evo-source-touch-cancel").apply { isDaemon = true }
    }
    fun startAsync(stillCurrent: () -> Boolean) {
        if (worker.isShutdown) return
        worker.execute {
            if (stillCurrent()) runCatching { ShizukuServiceHelper.service?.cancelSourceTouch() }
        }
    }
    fun stopAsync() {
        if (!worker.isShutdown) worker.execute {
            runCatching { ShizukuServiceHelper.service?.releaseSourceTouch() }
        }
    }
    fun close() {
        stopAsync()
        worker.shutdown()
    }
}
