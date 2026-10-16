package io.github.lumiagg.gestureevo.diagnostics

import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LogArchive {
    suspend fun export(cacheDir: File, filter: LogFilter? = null, problem: ProblemSession? = null): File = withContext(Dispatchers.IO) {
        val exportRoot = File(cacheDir, "diagnostics").apply { mkdirs() }
        // Keep recent shares usable, expire old temporary archives and bound cache growth.
        exportRoot.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }
            ?.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
        val work = File(exportRoot, "work-${UUID.randomUUID()}").apply { mkdirs() }
        val archive = File(exportRoot, "GestureEVO-logs-${System.currentTimeMillis()}.zip")
        try {
            AppLogger.ordered { Unit }
            val snapshots = ArrayList<Pair<String, LogSnapshot>>()
            try {
                AppLogger.allStores().forEach { snapshots.add(it.directory.name to it.captureSnapshot()) }
                snapshots.forEach { (name, snapshot) -> snapshot.copyTo(File(work, name), filter) }
            }
            finally { snapshots.forEach { it.second.close() } }
            File(work, "environment.txt").writeText(AppLogger.environment())
            File(work, "scope.txt").writeText(buildString {
                appendLine("since: ${filter?.since ?: "all"}; until: ${filter?.until ?: "all"}")
                appendLine("trace: ${filter?.trace ?: "all"}; session: ${filter?.session ?: "all"}")
                appendLine("level: ${filter?.level ?: "all"}; module: ${filter?.module ?: "all"}")
                // Search terms may be private; do not include the query string in the exported manifest.
                appendLine("keyword filter: ${!filter?.query.isNullOrBlank()}")
                problem?.let { appendLine("problem: ${it.id}; result: ${it.result}") }
            })
            zip(work, archive)
            exportRoot.listFiles()?.filter { it.extension == "zip" }?.sortedByDescending { it.lastModified() }
                ?.drop(3)?.forEach { it.delete() }
            archive
        } catch (e: Exception) { archive.delete(); throw e }
        finally { work.deleteRecursively() }
    }

    internal fun zip(source: File, destination: File) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(source).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
