package io.github.lumiagg.gestureevo.drop

import android.os.ParcelFileDescriptor
import io.github.lumiagg.gestureevo.diagnostics.DiagnosticProcess
import io.github.lumiagg.gestureevo.diagnostics.DiagnosticRelay
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class RootTouchConnection : Closeable {
    @Volatile private var closed = false
    private var relay: DiagnosticRelay? = null
    private var descriptor: ParcelFileDescriptor? = null

    suspend fun open(): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        val (code, ranges) = DiagnosticProcess.run(listOf("su", "-c", "exec /system/bin/getevent -lp"), 10_000)
        check(code == 0) { "Root touchscreen discovery failed: exit=$code" }
        val device = checkNotNull(TouchInputProtocol.discover("", ranges)) { "No readable root touchscreen" }
        currentCoroutineContext().ensureActive()
        synchronized(this@RootTouchConnection) {
            check(!closed) { "Root input cancelled" }
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                relay = DiagnosticRelay(TouchStreamProcess.rootArguments(device),
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]),
                    durationMs = null, byteLimit = null, stdinLease = true,
                    leaseHeartbeatMs = TouchStreamProcess.HEARTBEAT_MS)
                descriptor = pipe[0]
                pipe[0]
            } catch (error: Exception) {
                pipe.forEach { runCatching { it.close() } }
                throw error
            }
        }
    }

    override fun close() {
        val owned = synchronized(this) {
            closed = true
            runCatching { descriptor?.close() }
            descriptor = null
            relay.also { relay = null }
        }
        owned?.close()
    }
}
