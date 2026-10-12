/*
 * Copyright (C) 2025 Fleey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.fleey.ppocrv5.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File

class OcrEngine private constructor(
  private var nativeHandle: Long,
) : Closeable {

  companion object {
    private const val MODELS_DIR = "models"
    private const val DET_MODEL_FILE = "ocr_det_h1728_w768_fp16.tflite"
    private const val REC_MODEL_320_FILE = "ocr_rec_w320_fp32.tflite"
    private const val REC_MODEL_640_FILE = "ocr_rec_w640_fp32.tflite"
    private const val KEYS_FILE = "keys_v5.txt"
    private const val NPU_CACHE_DIR = "npu_cache"
    private const val TAG = "OcrEngine"

    @Volatile
    private var cacheInitialized = false

    init {
      System.loadLibrary("ppocrv5_jni")
    }

    /**
     * Initialize NPU compiler cache for faster model loading.
     * Call this once before creating OcrEngine instances.
     */
    fun initializeCache(context: Context) {
      if (cacheInitialized) return

      val cacheDir = File(context.cacheDir, NPU_CACHE_DIR)
      if (!cacheDir.exists()) {
        cacheDir.mkdirs()
      }
      nativeSetCacheDir(cacheDir.absolutePath)
      cacheInitialized = true
    }

    /**
     * Shutdown LiteRT environment and release resources.
     * Call this when the app is terminating.
     */
    fun shutdown() {
      nativeShutdown()
      cacheInitialized = false
    }

    fun create(
      context: Context,
      acceleratorType: AcceleratorType = AcceleratorType.CPU,
    ): Result<OcrEngine> = runCatching {
      initializeCache(context)

      val assetStartMs = SystemClock.elapsedRealtime()
      val detModelPath = OcrModelAssets.modelPath(context, DET_MODEL_FILE)
      val recModel320Path = OcrModelAssets.modelPath(context, REC_MODEL_320_FILE)
      val recModel640Path = OcrModelAssets.modelPath(context, REC_MODEL_640_FILE)
      val keysPath = OcrModelAssets.modelPath(context, KEYS_FILE)
      Log.i(TAG, "OCR model asset paths prepared in ${SystemClock.elapsedRealtime() - assetStartMs} ms")

      val nativeStartMs = SystemClock.elapsedRealtime()
      val handle = OcrEngine(0).nativeCreate(
        detModelPath,
        recModel320Path,
        recModel640Path,
        keysPath,
        acceleratorType.value,
      )
      Log.i(TAG, "OCR model loaded in ${SystemClock.elapsedRealtime() - nativeStartMs} ms, accelerator=$acceleratorType")

      if (handle == 0L) {
        throw OcrException("Failed to create native OCR engine")
      }

      OcrEngine(handle)
    }

    @JvmStatic
    private external fun nativeSetCacheDir(cacheDir: String)

    @JvmStatic
    private external fun nativeShutdown()
  }

  fun process(bitmap: Bitmap): List<OcrResult> {
    check(nativeHandle != 0L) { "OcrEngine has been closed" }
    return nativeProcess(nativeHandle, bitmap)?.toList() ?: emptyList()
  }

  fun detectTextBoxes(bitmap: Bitmap): List<RotatedBox> {
    check(nativeHandle != 0L) { "OcrEngine has been closed" }
    return nativeDetectTextBoxes(nativeHandle, bitmap)?.toList() ?: emptyList()
  }

  fun recognizeTextBox(bitmap: Bitmap): OcrResult? {
    check(nativeHandle != 0L) { "OcrEngine has been closed" }
    return nativeRecognizeTextBox(nativeHandle, bitmap)
  }

  fun getBenchmark(): Benchmark {
    check(nativeHandle != 0L) { "OcrEngine has been closed" }
    val data = nativeGetBenchmark(nativeHandle) ?: return Benchmark()
    return Benchmark(
      detectionTimeMs = data[0],
      recognitionTimeMs = data[1],
      totalTimeMs = data[2],
      fps = data[3],
    )
  }

  fun getActiveAccelerator(): AcceleratorType {
    check(nativeHandle != 0L) { "OcrEngine has been closed" }
    return AcceleratorType.fromValue(nativeGetActiveAccelerator(nativeHandle))
  }

  override fun close() {
    if (nativeHandle != 0L) {
      nativeDestroy(nativeHandle)
      nativeHandle = 0
    }
  }

  private external fun nativeCreate(
    detModelPath: String,
    recModel320Path: String,
    recModel640Path: String,
    keysPath: String,
    acceleratorType: Int,
  ): Long

  private external fun nativeProcess(
    handle: Long,
    bitmap: Bitmap,
  ): Array<OcrResult>?

  private external fun nativeDetectTextBoxes(
    handle: Long,
    bitmap: Bitmap,
  ): Array<RotatedBox>?

  private external fun nativeDestroy(handle: Long)

  private external fun nativeRecognizeTextBox(handle: Long, bitmap: Bitmap): OcrResult?

  private external fun nativeGetBenchmark(handle: Long): FloatArray?

  private external fun nativeGetActiveAccelerator(handle: Long): Int
}

class OcrException(message: String) : Exception(message)
