package io.github.lumiagg.gestureevo.ocr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SharedMemory
import android.system.OsConstants
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

@RequiresApi(OCR_MIN_API)
internal class OcrServiceClient(private val context: Context) : AutoCloseable {
    companion object {
        private const val CANCEL_DRAIN_MS = 2_000L
        private val nextRequestId = AtomicInteger()
    }

    private val connected = CompletableDeferred<Messenger>()
    private val disconnected = CompletableDeferred<Nothing>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            connected.complete(Messenger(service))
        }
        override fun onServiceDisconnected(name: ComponentName) = connectionLost()
        override fun onBindingDied(name: ComponentName) = connectionLost()
        override fun onNullBinding(name: ComponentName) = connectionLost()
    }

    private val bound = context.bindService(
        Intent(context, OcrRecognitionService::class.java),
        connection,
        Context.BIND_AUTO_CREATE,
    )

    private fun connectionLost() {
        val failure = IllegalStateException("OCR service disconnected")
        connected.completeExceptionally(failure)
        disconnected.completeExceptionally(failure)
    }

    suspend fun request(operation: Int, bitmap: Bitmap): Bundle {
        check(bound) { "Could not bind OCR service" }
        val service = connected.await()
        val response = CompletableDeferred<Bundle>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            response.complete(message.data)
            true
        })
        val id = nextRequestId.incrementAndGet()
        val request = Message.obtain().apply {
            what = operation
            arg1 = id
            replyTo = reply
        }
        var sent = false
        try {
            sendFrame(service, request, bitmap)
            sent = true
            return select {
                disconnected.onAwait { it }
                response.onAwait {
                    if (it.getBoolean("cancelled")) throw CancellationException("OCR request cancelled")
                    check(!it.getBoolean("failed")) { "OCR inference failed" }
                    it
                }
            }
        } catch (error: CancellationException) {
            if (sent && !response.isCompleted) {
                withContext(NonCancellable) {
                    // Let the worker close this frame before the next request allocates another.
                    runCatching {
                        service.send(Message.obtain().apply {
                            what = OCR_CANCEL
                            arg1 = id
                        })
                        withTimeoutOrNull(CANCEL_DRAIN_MS) {
                            select<Unit> {
                                response.onAwait { }
                                disconnected.onAwait { }
                            }
                        }
                    }
                }
            }
            throw error
        } finally {
            response.cancel()
        }
    }

    private suspend fun sendFrame(service: Messenger, request: Message, bitmap: Bitmap) {
        val pixelCount = bitmap.width.toLong() * bitmap.height
        val maxPixels = OCR_MAX_FRAME_BYTES / 4
        val scaled = if (pixelCount > maxPixels) {
            val factor = sqrt(maxPixels.toDouble() / pixelCount)
            val width = (bitmap.width * factor).toInt().coerceIn(1, maxPixels)
            val height = (bitmap.height * factor).toInt().coerceIn(1, maxPixels / width)
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else bitmap
        var software = scaled
        try {
            if (scaled.config != Bitmap.Config.ARGB_8888) {
                software = checkNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false))
            }
            val size = software.rowBytes.toLong() * software.height
            require(size in 1..OCR_MAX_FRAME_BYTES.toLong()) { "OCR image exceeds frame budget" }
            SharedMemory.create("evo-ocr-frame", size.toInt()).use { frame ->
                val target = frame.mapReadWrite()
                try {
                    software.copyPixelsToBuffer(target)
                } finally {
                    SharedMemory.unmap(target)
                }
                check(frame.setProtect(OsConstants.PROT_READ))
                currentCoroutineContext().ensureActive()
                request.data = Bundle().apply {
                    putParcelable("frame", frame)
                    putInt("width", software.width)
                    putInt("height", software.height)
                    putInt("stride", software.rowBytes)
                    putInt("sourceWidth", bitmap.width)
                    putInt("sourceHeight", bitmap.height)
                }
                service.send(request)
            }
        } finally {
            if (software !== scaled) software.recycle()
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    override fun close() {
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // A failed bind may never have registered the connection.
        } finally {
            connectionLost()
        }
    }
}
