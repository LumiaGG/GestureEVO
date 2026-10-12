package me.fleey.ppocrv5.ocr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.Executors

object OcrModelAssets {
  private const val ARCHIVE_ASSET = "models/ocr_models.tar.xz"
  private const val VERSION_ASSET = "models/ocr_models.version"
  private const val EXTRACT_ROOT = "ocr_models"
  private const val READY_FILE = ".ready"
  private const val TAR_BLOCK_SIZE = 512
  private const val TAG = "OcrModelAssets"

  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "ocr-model-assets").apply { isDaemon = true }
  }

  @Volatile
  private var prepareFuture: Future<File>? = null

  fun prewarm(context: Context) {
    getOrCreateFuture(context.applicationContext)
  }

  fun modelPath(context: Context, fileName: String): String {
    return File(prepare(context.applicationContext), fileName).absolutePath
  }

  private fun prepare(context: Context): File {
    while (true) {
      val future = getOrCreateFuture(context)
      try {
        return future.get()
      } catch (exception: ExecutionException) {
        synchronized(this) {
          if (prepareFuture === future) prepareFuture = null
        }
        throw exception.cause ?: exception
      }
    }
  }

  private fun getOrCreateFuture(context: Context): Future<File> {
    prepareFuture?.let { return it }

    return synchronized(this) {
      prepareFuture ?: executor.submit<File> { extractIfNeeded(context) }.also {
        prepareFuture = it
      }
    }
  }

  private fun extractIfNeeded(context: Context): File {
    val startMs = SystemClock.elapsedRealtime()
    val version = readAssetText(context, VERSION_ASSET).trim()
    require(version.isNotEmpty()) { "OCR model version is empty" }

    val rootDir = File(context.filesDir, EXTRACT_ROOT)
    val targetDir = File(rootDir, version)
    if (File(targetDir, READY_FILE).isFile) {
      cleanupOldVersions(rootDir, version)
      Log.i(TAG, "OCR model assets already prepared in ${SystemClock.elapsedRealtime() - startMs} ms, version=$version")
      return targetDir
    }

    synchronized(this) {
      if (File(targetDir, READY_FILE).isFile) {
        Log.i(TAG, "OCR model assets already prepared in ${SystemClock.elapsedRealtime() - startMs} ms, version=$version")
        return targetDir
      }

      val tempDir = File(rootDir, "$version.tmp")
      tempDir.deleteRecursively()
      tempDir.mkdirsOrThrow()

      context.assets.open(ARCHIVE_ASSET).use { assetInput ->
        XZInputStream(BufferedInputStream(assetInput)).use { xzInput ->
          extractTar(xzInput, tempDir)
        }
      }

      File(tempDir, READY_FILE).writeText(version, Charsets.UTF_8)
      targetDir.deleteRecursively()
      if (!tempDir.renameTo(targetDir)) {
        tempDir.copyRecursively(targetDir, overwrite = true)
        tempDir.deleteRecursively()
      }
      cleanupOldVersions(rootDir, version)
    }

    Log.i(TAG, "OCR model assets extracted in ${SystemClock.elapsedRealtime() - startMs} ms, version=$version")
    return targetDir
  }

  private fun extractTar(input: InputStream, targetDir: File) {
    val header = ByteArray(TAR_BLOCK_SIZE)
    while (true) {
      val bytesRead = input.readFullyOrEnd(header)
      if (bytesRead == 0 || header.all { it == 0.toByte() }) return
      if (bytesRead != TAR_BLOCK_SIZE) throw IOException("Invalid TAR header")

      val name = header.readString(0, 100)
      val prefix = header.readString(345, 155)
      val relativePath = listOf(prefix, name).filter { it.isNotEmpty() }.joinToString("/")
      val size = header.readOctal(124, 12)
      val type = header[156].toInt().toChar()

      val outputFile = safeOutputFile(targetDir, relativePath)
      when (type) {
        '0', '\u0000' -> {
          outputFile.parentFile?.mkdirsOrThrow()
          FileOutputStream(outputFile).use { output -> input.copyExactlyTo(output, size) }
          input.skipPadding(size)
        }
        '5' -> outputFile.mkdirsOrThrow()
        else -> {
          input.skipExactly(size)
          input.skipPadding(size)
        }
      }
    }
  }

  private fun safeOutputFile(targetDir: File, relativePath: String): File {
    if (relativePath.isBlank() || relativePath.startsWith("/") || relativePath.contains("..")) {
      throw IOException("Unsafe TAR entry: $relativePath")
    }
    val outputFile = File(targetDir, relativePath).canonicalFile
    val canonicalTarget = targetDir.canonicalFile
    if (!outputFile.path.startsWith(canonicalTarget.path + File.separator)) {
      throw IOException("TAR entry escapes target directory: $relativePath")
    }
    return outputFile
  }

  private fun cleanupOldVersions(rootDir: File, currentVersion: String) {
    rootDir.listFiles()?.forEach { file ->
      if (file.name != currentVersion) file.deleteRecursively()
    }
  }

  private fun readAssetText(context: Context, assetPath: String): String {
    return context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
  }

  private fun File.mkdirsOrThrow() {
    if (!isDirectory && !mkdirs()) throw IOException("Failed to create directory: $this")
  }

  private fun ByteArray.readString(offset: Int, length: Int): String {
    val end = (offset until offset + length).firstOrNull { this[it] == 0.toByte() } ?: offset + length
    return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
  }

  private fun ByteArray.readOctal(offset: Int, length: Int): Long {
    val value = readString(offset, length).trim { it <= ' ' || it == '\u0000' }
    return if (value.isEmpty()) 0L else value.toLong(8)
  }

  private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
      val read = read(buffer, total, buffer.size - total)
      if (read == -1) return total
      total += read
    }
    return total
  }

  private fun InputStream.copyExactlyTo(output: FileOutputStream, size: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = size
    while (remaining > 0) {
      val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
      if (read == -1) throw IOException("Unexpected EOF in TAR entry")
      output.write(buffer, 0, read)
      remaining -= read
    }
  }

  private fun InputStream.skipPadding(size: Long) {
    val padding = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE
    skipExactly(padding)
  }

  private fun InputStream.skipExactly(size: Long) {
    var remaining = size
    while (remaining > 0) {
      val skipped = skip(remaining)
      if (skipped > 0) {
        remaining -= skipped
      } else if (read() != -1) {
        remaining--
      } else {
        throw IOException("Unexpected EOF while skipping TAR data")
      }
    }
  }
}
