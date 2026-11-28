package io.github.lumiagg.gestureevo.ocr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SharedMemory
import androidx.annotation.RequiresApi
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal const val OCR_MIN_API = 28
internal const val OCR_PROCESS_SUFFIX = ":ocr"
internal const val OCR_MAX_FRAME_BYTES = 64 * 1024 * 1024
internal const val OCR_DETECT = 2
internal const val OCR_RECOGNIZE = 3
internal const val OCR_LINE = 4
internal const val OCR_CANCEL = 5

/** Each request owns its shared-memory descriptor and runs on the same model worker. */
@RequiresApi(OCR_MIN_API)
class OcrRecognitionService : Service() {
    private companion object {
        // Serialize teardown with inference even if a new binding creates another service.
        val worker = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "ocr-inference")
        }
    }

    private val main = Handler(Looper.getMainLooper(), ::acceptRequest)
    private val requests = mutableMapOf<Int, OcrRequest>()
    private var stopping = false
    private var pipeline: OcrPipeline? = null

    override fun onBind(intent: Intent): IBinder = Messenger(main).binder

    override fun onDestroy() {
        stopping = true
        requests.values.forEach(OcrRequest::cancel)
        worker.execute {
            releasePipeline()
            // When unbound and destroyed, terminate process so no cached/frozen pages linger in memory.
            Process.killProcess(Process.myPid())
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            worker.execute(::releasePipeline)
        } else if (level >= TRIM_MEMORY_RUNNING_LOW) {
            worker.execute { pipeline?.releaseRecognition() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        worker.execute(::releasePipeline)
    }

    @Suppress("DEPRECATION")
    private fun acceptRequest(message: Message): Boolean {
        val id = message.arg1
        if (message.what == OCR_CANCEL) {
            requests[id]?.cancel()
            return true
        }

        val operation = message.what
        val data = message.data.apply { classLoader = SharedMemory::class.java.classLoader }
        val frame = data.getParcelable<SharedMemory>("frame")
        val reply = message.replyTo
        if (stopping || requests.containsKey(id)) {
            frame?.close()
            sendReply(reply, Bundle().apply { putBoolean("failed", true) })
            return true
        }

        val request = OcrRequest()
        requests[id] = request
        worker.execute {
            val result = Bundle()
            try {
                request.use {
                    frame.use { image ->
                        request.ensureActive()
                        processFrame(operation, checkNotNull(image), data, result, request)
                    }
                }
            } catch (error: Exception) {
                if (!request.isCancelled) {
                    result.putBoolean("failed", true)
                    android.util.Log.w("OcrService", "OCR request failed (${error.javaClass.simpleName})")
                }
            } catch (error: LinkageError) {
                result.putBoolean("failed", true)
                android.util.Log.w("OcrService", "OCR runtime could not be loaded")
            } finally {
                main.post {
                    requests.remove(id)
                    val response = if (request.isCancelled) {
                        Bundle().apply { putBoolean("cancelled", true) }
                    } else result
                    sendReply(reply, response)
                }
            }
        }
        return true
    }

    private fun sendReply(reply: Messenger?, result: Bundle) {
        runCatching { reply?.send(Message.obtain().apply { data = result }) }
    }

    private fun processFrame(operation: Int, frame: SharedMemory, data: Bundle, result: Bundle, request: OcrRequest) {
        val width = data.getInt("width")
        val height = data.getInt("height")
        val stride = data.getInt("stride")
        val sourceWidth = data.getInt("sourceWidth")
        val sourceHeight = data.getInt("sourceHeight")
        require(width > 0 && height > 0 && stride >= width.toLong() * 4)
        require(frame.size <= OCR_MAX_FRAME_BYTES && frame.size.toLong() == stride.toLong() * height)
        require(sourceWidth >= width && sourceHeight >= height)
        val scaleX = sourceWidth.toDouble() / width
        val scaleY = sourceHeight.toDouble() / height
        val engine = getPipeline()
        val pixels = frame.mapReadOnly()
        try {
            val rgba = Mat(height, width, CvType.CV_8UC4, pixels, stride.toLong())
            val bgr = Mat()
            try {
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                when (operation) {
                    OCR_LINE -> result.putString("line", engine.recognizeLine(bgr, request))
                    OCR_DETECT, OCR_RECOGNIZE -> {
                        val regions = if (operation == OCR_DETECT) engine.detectRegions(bgr, request) else engine.recognize(bgr, request)
                        val corners = DoubleArray(regions.size * 8)
                        regions.forEachIndexed { index, region ->
                            region.points.forEachIndexed { corner, point ->
                                val coordinate = index * 8 + corner * 2
                                corners[coordinate] = point.x * scaleX
                                corners[coordinate + 1] = point.y * scaleY
                            }
                        }
                        result.putDoubleArray("corners", corners)
                        if (operation == OCR_RECOGNIZE) {
                            result.putStringArrayList("text", regions.mapTo(ArrayList(regions.size)) { it.text })
                        }
                    }
                    else -> error("Unknown OCR operation")
                }
            } finally {
                bgr.release()
                rgba.release()
            }
        } finally {
            SharedMemory.unmap(pixels)
        }
    }

    private fun getPipeline(): OcrPipeline {
        pipeline?.let { return it }
        check(OpenCVLoader.initLocal()) { "OpenCV initialization failed" }
        Core.setNumThreads(1)
        return OcrPipeline(
            { name -> ZipFile(applicationInfo.sourceDir).use { mapModel(it, name) } },
            { assets.open("ocr/ppocrv5-characters.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() } },
        ).also { pipeline = it }
    }

    private fun releasePipeline() {
        val current = pipeline
        pipeline = null
        runCatching { current?.close() }
    }

    private fun mapModel(apk: ZipFile, name: String): ByteBuffer {
        val entry = checkNotNull(apk.getEntry("assets/ocr/$name")) { "Missing OCR model" }
        check(entry.size > 0)
        // Android clears codeCacheDir on upgrade. Only the serial OCR worker writes these files.
        val directory = File(codeCacheDir, "ocr-models")
        val file = File(directory, name)
        if (file.length() != entry.size) {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create OCR cache" }
            val partial = File(directory, "$name.partial")
            try {
                val bytesWritten = apk.getInputStream(entry).use { source ->
                    partial.outputStream().use { destination -> source.copyTo(destination) }
                }
                check(bytesWritten == entry.size) { "Incomplete OCR model" }
                check(partial.renameTo(file)) { "Cannot publish OCR model cache" }
            } finally {
                partial.delete()
            }
        }
        return file.inputStream().use { input ->
            input.channel.map(FileChannel.MapMode.READ_ONLY, 0, entry.size)
        }
    }
}
