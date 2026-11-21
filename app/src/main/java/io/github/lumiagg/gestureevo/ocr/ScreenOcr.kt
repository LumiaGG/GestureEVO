package io.github.lumiagg.gestureevo.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import io.github.lumiagg.gestureevo.EvoApplication
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.inspire.ScreenContentNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

object ScreenOcr {
    private const val PREWARM_IDLE_MS = 5_000L
    private const val INTERACTION_IDLE_MS = 15_000L
    private const val REQUEST_TIMEOUT_MS = 60_000L

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val owners = mutableSetOf<Session>()
    private val handoffs = mutableMapOf<String, Session>()
    private var client: OcrServiceClient? = null
    private var idleRelease: Job? = null

    @get:ChecksSdkIntAtLeast(api = OCR_MIN_API)
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= OCR_MIN_API

    val taskRunning: Boolean
        get() = running.get()

    suspend fun preload() {
        if (!isSupported) return
        try {
            execute(OCR_WARM)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The user-initiated request can retry after a failed preload.
        }
    }

    fun trimMemory() {
        if (isSupported) {
            scope.launch { mutex.withLock { closeClient() } }
        }
    }

    internal class Session : AutoCloseable {
        // Accessed under the owners lock. The job belongs to this request's withContext.
        internal var requestJob: Job? = null

        override fun close() {
            val job = synchronized(owners) {
                if (!owners.remove(this)) return
                handoffs.values.remove(this)
                requestJob.also { requestJob = null }
            }
            job?.cancel()
            if (isSupported) closeIfUnowned()
        }
    }

    internal fun openSession(): Session = synchronized(owners) {
        Session().also(owners::add)
    }

    internal fun offerHandoff(session: Session): String {
        val token = UUID.randomUUID().toString()
        synchronized(owners) {
            check(session in owners)
            handoffs[token] = session
        }
        scope.launch {
            delay(PREWARM_IDLE_MS)
            takeHandoff(token)?.close()
        }
        return token
    }

    internal fun takeHandoff(token: String?): Session? =
        synchronized(owners) { handoffs.remove(token) }

    @RequiresApi(OCR_MIN_API)
    private fun closeIfUnowned() {
        scope.launch {
            mutex.withLock {
                if (!hasOwners()) closeClient()
            }
        }
    }

    private fun hasOwners(): Boolean = synchronized(owners) { owners.isNotEmpty() }

    suspend fun detect(img: Bitmap): ArrayList<ScreenContentNode> = detect(img, null)

    internal suspend fun detect(img: Bitmap, session: Session?): ArrayList<ScreenContentNode> {
        if (img.width < 32 || img.height < 32) return arrayListOf()
        val result = execute(OCR_RECOGNIZE, img, session)
        val text = requireNotNull(result.getStringArrayList("text"))
        return bounds(result, img).mapIndexedTo(ArrayList(text.size)) { index, rect ->
            ScreenContentNode(rect, text[index])
        }
    }

    internal suspend fun detectTextBoxes(img: Bitmap, session: Session? = null): ArrayList<Rect> {
        if (img.width < 32 || img.height < 32) return arrayListOf()
        return bounds(execute(OCR_DETECT, img, session), img)
    }

    /** Recognizes an already detected text-line crop. */
    internal suspend fun recognizeTextBox(img: Bitmap, session: Session? = null): String {
        if (img.width < 2 || img.height < 2) return ""
        return requireNotNull(execute(OCR_LINE, img, session).getString("line"))
    }

    private fun bounds(result: Bundle, img: Bitmap): ArrayList<Rect> {
        val coordinates = requireNotNull(result.getDoubleArray("corners"))
        return ArrayList<Rect>(coordinates.size / 8).apply {
            for (i in coordinates.indices step 8) {
                val x = i..i + 6 step 2
                val y = i + 1..i + 7 step 2
                add(Rect(
                    floor(x.minOf { coordinates[it] }).toInt().coerceIn(0, img.width),
                    floor(y.minOf { coordinates[it] }).toInt().coerceIn(0, img.height),
                    ceil(x.maxOf { coordinates[it] }).toInt().coerceIn(0, img.width),
                    ceil(y.maxOf { coordinates[it] }).toInt().coerceIn(0, img.height),
                ))
            }
        }
    }

    private suspend fun execute(operation: Int, bitmap: Bitmap? = null, session: Session? = null): Bundle {
        if (Build.VERSION.SDK_INT < OCR_MIN_API) error("Screen OCR requires Android 9 or later")
        val result = executeSupported(operation, bitmap, session)
        if (session != null && synchronized(owners) { session !in owners }) {
            throw CancellationException("OCR interaction closed")
        }
        return result
    }

    @RequiresApi(OCR_MIN_API)
    private suspend fun executeSupported(operation: Int, bitmap: Bitmap?, session: Session?): Bundle =
        withContext(Dispatchers.IO) {
            val requestJob = currentCoroutineContext()[Job]
            mutex.withLock {
                if (session != null) {
                    synchronized(owners) {
                        if (session !in owners) throw CancellationException("OCR interaction closed")
                        session.requestJob = requestJob
                    }
                }
                idleRelease?.cancel()
                if (operation != OCR_WARM) running.set(true)
                var completed = false
                try {
                    val service = client ?: OcrServiceClient(EvoApplication.application).also { client = it }
                    val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                        service.request(operation, bitmap)
                    } ?: error("OCR request timed out")
                    completed = true
                    result
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    closeClient()
                    AppLogger.event(LogModule.OCR, "inference_failed", LogLevel.ERROR, error = error)
                    throw error
                } finally {
                    if (session != null) synchronized(owners) { session.requestJob = null }
                    running.set(false)
                    if (client != null) {
                        when {
                            hasOwners() -> scheduleRelease(INTERACTION_IDLE_MS)
                            operation == OCR_WARM && completed -> scheduleRelease(PREWARM_IDLE_MS)
                            else -> closeClient()
                        }
                    }
                }
            }
        }

    @RequiresApi(OCR_MIN_API)
    private fun scheduleRelease(afterMillis: Long) {
        idleRelease = scope.launch {
            delay(afterMillis)
            mutex.withLock { closeClient() }
        }
    }

    @RequiresApi(OCR_MIN_API)
    private fun closeClient() {
        idleRelease?.cancel()
        idleRelease = null
        val current = client
        client = null
        current?.close()
    }
}
