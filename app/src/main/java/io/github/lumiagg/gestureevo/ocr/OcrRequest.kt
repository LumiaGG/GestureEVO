package io.github.lumiagg.gestureevo.ocr

import ai.onnxruntime.OrtSession
import java.util.concurrent.CancellationException

/** The IPC handler can cancel the worker without closing resources under native inference. */
internal class OcrRequest : AutoCloseable {
    @Volatile
    var isCancelled = false
        private set

    private var options: OrtSession.RunOptions? = null

    fun ensureActive() {
        if (isCancelled) throw CancellationException("OCR request cancelled")
    }

    @Synchronized
    fun runOptions(): OrtSession.RunOptions {
        ensureActive()
        return options ?: OrtSession.RunOptions().also { options = it }
    }

    @Synchronized
    fun cancel() {
        isCancelled = true
        // If native termination fails, ensureActive still stops at the next inference boundary.
        runCatching { options?.setTerminate(true) }
    }

    @Synchronized
    override fun close() {
        options?.close()
        options = null
    }
}
