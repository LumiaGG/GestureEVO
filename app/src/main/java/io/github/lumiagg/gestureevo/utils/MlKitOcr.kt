package io.github.lumiagg.gestureevo.utils

import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import io.github.lumiagg.gestureevo.EvoApplication
import io.github.lumiagg.gestureevo.inspire.ScreenContentNode
import io.github.lumiagg.gestureevo.ocr.OcrGeometry
import io.github.lumiagg.gestureevo.ocr.OcrSettings
import io.github.lumiagg.gestureevo.ocr.coloros.ColorOsOcrClient
import android.graphics.PointF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.ppocrv5.ocr.AcceleratorType
import me.fleey.ppocrv5.ocr.OcrEngine
import me.fleey.ppocrv5.ocr.OcrResult
import me.fleey.ppocrv5.ocr.RotatedBox
import java.util.concurrent.atomic.AtomicBoolean

object MlKitOcr {
    private const val PRELOAD_UNUSED_RELEASE_DELAY_MS = 60_000L

    private val running = AtomicBoolean(false)
    private val preloadMutex = Mutex()
    private val taskMutex = Mutex()
    private val engineUseMutex = Mutex()
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var preloadedEngine: OcrEngine? = null
    private var preloadReleaseJob: Job? = null
    val taskRunning: Boolean
        get() = running.get()
    const val TAG = "MlKitOcr"

    suspend fun preload() {
        preloadMutex.withLock {
            // The ColorOS backend has nothing to preload; every request starts its own helper.
            if (OcrSettings.isColorOs(EvoApplication.application)) return
            if (preloadedEngine == null) {
                preloadedEngine = createEngine()
                if (preloadedEngine == null) {
                    return
                }
                Log.d(TAG, "preload: OCR engine ready")
            }
            schedulePreloadReleaseLocked()
        }
    }

    fun releasePreload() {
        preloadScope.launch {
            releaseUnusedPreloadedEngine("memory pressure")
        }
    }

    suspend fun detect(img: Bitmap): ArrayList<ScreenContentNode> =
        withContext(Dispatchers.Default) {
            taskMutex.withLock {
                val nodes = ArrayList<ScreenContentNode>()
                if (img.width < 32 || img.height < 32) return@withLock nodes
                running.set(true)
                try {
                    val colorOs = OcrSettings.isColorOs(EvoApplication.application)
                    if (colorOs) {
                        nodes.addAll(ColorOsOcrClient.detectNodes(img))
                    } else {
                        AppLogger.event(
                            LogModule.OCR, "detect_started",
                            LogLevel.DEBUG,
                            fields = mapOf("width" to img.width, "height" to img.height))
                        val filterLowConfidence = OcrSettings.filterLowConfidence(EvoApplication.application)
                        withBuiltInEngine(retainPreloaded = false) { engine ->
                            processWithEngine(engine, img, nodes, filterLowConfidence)
                        }
                        AppLogger.event(
                            LogModule.OCR, "detect_completed",
                            LogLevel.DEBUG, fields = mapOf("count" to nodes.size))
                    }
                    if (OcrSettings.stripSpaces(EvoApplication.application)) {
                        for (i in nodes.indices) {
                            nodes[i] = ScreenContentNode(nodes[i].bound, nodes[i].content?.let(OcrSettings::stripInline))
                        }
                    }
                    nodes
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.event(
                        LogModule.OCR, "detect_failed",
                        LogLevel.ERROR, error = e)
                    throw e
                } finally {
                    running.set(false)
                }
            }
        }

    suspend fun detectTextBoxes(img: Bitmap): ArrayList<Rect> =
        withContext(Dispatchers.Default) {
            taskMutex.withLock {
                val boxes = ArrayList<Rect>()
                if (img.width < 32 || img.height < 32) return@withLock boxes
                running.set(true)
                try {
                    if (OcrSettings.isColorOs(EvoApplication.application)) {
                        boxes.addAll(ColorOsOcrClient.detectRects(img))
                        return@withLock boxes
                    }
                    withBuiltInEngine(retainPreloaded = true) { engine ->
                        engine.detectTextBoxes(img).forEach { box ->
                            box.toRect(img.width, img.height)?.let(boxes::add)
                        }
                    }
                    boxes
                } finally {
                    running.set(false)
                }
            }
        }

    /** Recognizes an already detected text-line crop without running detection again. */
    suspend fun recognizeTextBox(img: Bitmap): String = withContext(Dispatchers.Default) {
        taskMutex.withLock {
            if (img.width < 2 || img.height < 2) return@withLock ""
            running.set(true)
            try {
                val text = if (OcrSettings.isColorOs(EvoApplication.application)) {
                    ColorOsOcrClient.recognize(img)
                } else {
                    withBuiltInEngine(retainPreloaded = false) { engine ->
                        engine.recognizeTextBox(img)?.text.orEmpty()
                    }.orEmpty()
                }
                if (OcrSettings.stripSpaces(EvoApplication.application)) {
                    OcrSettings.stripInline(text)
                } else {
                    text
                }
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun <T> withBuiltInEngine(
        retainPreloaded: Boolean,
        block: (OcrEngine) -> T,
    ): T? =
        engineUseMutex.withLock {
            val preloaded = preloadMutex.withLock {
                preloadedEngine?.also {
                    if (retainPreloaded) {
                        schedulePreloadReleaseLocked()
                    } else {
                        preloadedEngine = null
                        preloadReleaseJob?.cancel()
                        preloadReleaseJob = null
                    }
                }
            }
            if (preloaded != null) {
                if (retainPreloaded) block(preloaded) else preloaded.use(block)
            } else {
                createEngine()?.use(block)
            }
        }

    private fun schedulePreloadReleaseLocked() {
        preloadReleaseJob?.cancel()
        preloadReleaseJob = preloadScope.launch {
            delay(PRELOAD_UNUSED_RELEASE_DELAY_MS)
            releaseUnusedPreloadedEngine("timeout")
        }
    }

    private suspend fun releaseUnusedPreloadedEngine(reason: String) {
        engineUseMutex.withLock {
            preloadMutex.withLock {
                val engine = preloadedEngine ?: return@withLock
                engine.close()
                preloadedEngine = null
                preloadReleaseJob = null
                Log.d(TAG, "preload: OCR engine released by $reason")
            }
        }
    }

    private fun createEngine(): OcrEngine? {
        return OcrEngine.create(EvoApplication.application, AcceleratorType.CPU)
            .getOrElse { error ->
                AppLogger.event(LogModule.OCR, "engine_initialization_failed", LogLevel.ERROR, error = error)
                Log.e(TAG, "OCR engine init failed", error)
                null
            }
    }

    private fun processWithEngine(
        engine: OcrEngine,
        img: Bitmap,
        nodes: ArrayList<ScreenContentNode>,
        filterLowConfidence: Boolean,
    ) {
        engine.process(img).forEach { result ->
            if (filterLowConfidence && result.confidence < OcrSettings.MIN_CONFIDENCE) {
                return@forEach
            }
            val node = result.toScreenContentNode(img.width, img.height) ?: return@forEach
            nodes.add(node)
        }
    }

    private fun OcrResult.toScreenContentNode(
        imageWidth: Int,
        imageHeight: Int
    ): ScreenContentNode? {
        if (text.isBlank()) {
            return null
        }
        val corners = box.getCorners()
        if (corners.isEmpty()) {
            return null
        }
        return corners.boundingRect(imageWidth, imageHeight)
            ?.let { ScreenContentNode(Rect(it[0], it[1], it[2], it[3]), text) }
    }

    private fun RotatedBox.toRect(imageWidth: Int, imageHeight: Int): Rect? =
        getCorners().boundingRect(imageWidth, imageHeight)
            ?.let { Rect(it[0], it[1], it[2], it[3]) }

    private fun List<PointF>.boundingRect(
        imageWidth: Int,
        imageHeight: Int,
    ): IntArray? = OcrGeometry.boundingRect(
        this.map { it.x.toDouble() }.toDoubleArray(),
        this.map { it.y.toDouble() }.toDoubleArray(),
        imageWidth,
        imageHeight,
    )

}
