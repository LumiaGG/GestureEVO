package io.github.lumiagg.gestureevo.drop

import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogModule

/** Lifecycle metadata only; never include ClipData, labels, URIs or text in persistent diagnostics. */
internal object DropDiagnostics {
    fun event(name: String, source: String, generation: Int, reason: String = "", width: Int = 0,
        height: Int = 0, flags: Int = 0) {
        AppLogger.event(LogModule.OVERLAY, name, fields = mapOf("source" to source,
            "count" to generation, "reason" to reason, "width" to width, "height" to height, "flags" to flags))
    }
}
