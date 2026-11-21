package io.github.lumiagg.gestureevo.ocr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
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
import java.util.zip.ZipFile

internal const val OCR_MIN_API = 28
internal const val OCR_PROCESS_SUFFIX = ":ocr"
internal const val OCR_MAX_FRAME_BYTES = 64 * 1024 * 1024
internal const val OCR_WARM = 1
internal const val OCR_DETECT = 2
internal const val OCR_RECOGNIZE = 3
internal const val OCR_LINE = 4

/** Each request owns its shared-memory descriptor and runs on the same model worker. */
@RequiresApi(OCR_MIN_API)
class OcrRecognitionService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var pipeline: OcrPipeline? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("ocr-inference", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        handler = Handler(worker.looper, ::handleRequest)
    }

    override fun onBind(intent: Intent): IBinder = Messenger(handler).binder

    override fun onUnbind(intent: Intent): Boolean {
        handler.post(::releasePipeline)
        return false
    }

    override fun onDestroy() {
        handler.post {
            releasePipeline()
            worker.quitSafely()
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) handler.post(::releasePipeline)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        handler.post(::releasePipeline)
    }

    @Suppress("DEPRECATION")
    private fun handleRequest(message: Message): Boolean {
        val result = Bundle()
        try {
            val data = message.data.apply { classLoader = SharedMemory::class.java.classLoader }
            val frame = data.getParcelable<SharedMemory>("frame")
            if (frame == null) {
                check(message.what == OCR_WARM)
                getPipeline()
            } else {
                frame.use { processFrame(message.what, it, data, result) }
            }
        } catch (error: Exception) {
            result.putBoolean("failed", true)
            android.util.Log.w("OcrService", "OCR request failed (${error.javaClass.simpleName})")
        } catch (error: LinkageError) {
            result.putBoolean("failed", true)
            android.util.Log.w("OcrService", "OCR runtime could not be loaded")
        }
        runCatching {
            message.replyTo?.send(Message.obtain().apply { data = result })
        }
        return true
    }

    private fun processFrame(operation: Int, frame: SharedMemory, data: Bundle, result: Bundle) {
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
                    OCR_LINE -> result.putString("line", engine.recognizeLine(bgr))
                    OCR_DETECT, OCR_RECOGNIZE -> {
                        val regions = if (operation == OCR_DETECT) engine.detectRegions(bgr) else engine.recognize(bgr)
                        val corners = DoubleArray(regions.size * 8)
                        val text = ArrayList<String>(regions.size)
                        regions.forEachIndexed { index, region ->
                            region.points.forEachIndexed { corner, point ->
                                val coordinate = index * 8 + corner * 2
                                corners[coordinate] = point.x * scaleX
                                corners[coordinate + 1] = point.y * scaleY
                            }
                            text.add(region.text)
                        }
                        result.putDoubleArray("corners", corners)
                        result.putStringArrayList("text", text)
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
        val characters = assets.open("ocr/ppocrv5-characters.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() }
        return ZipFile(applicationInfo.sourceDir).use { apk ->
            OcrPipeline(
                mapModel(apk, "ch_PP-OCRv5_det_mobile.onnx"),
                mapModel(apk, "ch_PP-OCRv5_rec_mobile.onnx"),
                characters,
            )
        }.also { pipeline = it }
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
