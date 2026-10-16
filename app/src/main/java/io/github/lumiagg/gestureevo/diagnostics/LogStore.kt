package io.github.lumiagg.gestureevo.diagnostics

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.Closeable
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class LogModule { APP, ACTION, SERVICE, OVERLAY, SHIZUKU, BACKUP, OCR, SYSTEM }
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogRecord(
    val timestamp: Long,
    val level: LogLevel,
    val module: LogModule,
    val event: String,
    val trace: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val error: String? = null,
    val id: String = UUID.randomUUID().toString(),
    val session: String? = null,
)

data class LogFilter(
    val since: Long = Long.MIN_VALUE, val until: Long = Long.MAX_VALUE,
    val level: LogLevel? = null, val module: LogModule? = null,
    val query: String = "", val trace: String? = null, val session: String? = null,
) {
    fun matches(record: LogRecord): Boolean = record.timestamp in since..until &&
        (level == null || record.level == level) && (module == null || record.module == module) &&
        (trace == null || record.trace == trace) && (session == null || record.session == session) &&
        (query.isBlank() || sequenceOf(record.event, record.trace, record.session, record.error,
            record.fields.toString(), record.module.name, record.level.name).filterNotNull().any { it.contains(query, true) })
}

data class LogPage(val records: List<LogRecord>, val stats: Pair<Int, Long>, val latestError: LogRecord?)

/** Only explicitly supported metadata reaches disk. Exception messages can contain user data. */
object LogPrivacy {
    private val identifiers = setOf("package", "component", "action", "type", "method", "source", "reason", "thread", "versionName")
    private val numbers = setOf("flags", "durationMs", "count", "width", "height", "dropped", "exitCode", "systemTimeMs", "pid", "versionCode", "httpStatus", "errno")
    private val booleans = setOf("foreground", "overlay", "accessibility", "shizuku", "freeform")
    fun fields(input: Map<String, Any?>): Map<String, String> = input.mapNotNull { (key, value) ->
        val text = value?.toString() ?: return@mapNotNull null
        val safe = when (key) {
            in identifiers -> text.takeIf { it.length <= 200 && it.matches(Regex("[A-Za-z0-9_.$:/ -]+")) }
            in numbers -> text.takeIf { it.matches(Regex("-?[0-9]{1,18}")) }
            in booleans -> text.takeIf { it in setOf("true", "false", "unknown") }
            else -> null
        }
        safe?.let { key to it }
    }.toMap()

    fun stack(error: Throwable): String = buildString {
        var current: Throwable? = error
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (current != null && seen.add(current) && seen.size <= 5) {
            append(current.javaClass.name).append('\n')
            current.stackTrace.take(40).forEach { frame ->
                // Deliberately omit messages, file paths and custom Throwable.toString().
                append("  at ").append(frame.className).append('.').append(frame.methodName)
                    .append(':').append(frame.lineNumber).append('\n')
            }
            current = current.cause
        }
    }.take(12_000)
}

/** One writer per process directory; append, clear and snapshot share a lock. No open file survives an append. */
class LogStore(
    val directory: File,
    private val fileLimit: Long = 2L * 1024 * 1024,
    private val totalLimit: Long = 18L * 1024 * 1024,
    private val retentionMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()
    private val adapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter(LogRecord::class.java)
    private var active: File? = null
    private val indexLock = ReentrantLock()
    private data class Entry(val file: File, val offset: Long, val length: Int, val summary: LogRecord)
    private class Segment(val file: File) {
        var indexed = 0L
        var observed = 0L
        var modified = 0L
        val entries = ArrayList<Entry>()
        var latestError: Entry? = null
    }
    private val segments = HashMap<File, Segment>()
    private val recordCache = object : LinkedHashMap<String, LogRecord>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LogRecord>?): Boolean = size > 128
    }
    internal var decodedLines = 0L
        private set

    fun append(record: LogRecord, waitMs: Long = 1000): Boolean {
        if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return false
        try {
            directory.mkdirs()
            val bytes = (adapter.toJson(record) + "\n").toByteArray(Charsets.UTF_8)
            if (bytes.size > fileLimit || bytes.size > totalLimit) return false
            prune()
            var target = active ?: files().lastOrNull()
            if (target == null || !target.exists() || target.length() + bytes.size + 1 > fileLimit ||
                clock() - createdAt(target) >= 24 * 60 * 60 * 1000L) {
                target = File(directory, "${clock()}-${UUID.randomUUID()}.jsonl")
            }
            active = target
            if (target.exists() && target.length() > 0) {
                val terminated = java.io.RandomAccessFile(target, "r").use { file ->
                    file.seek(file.length() - 1); file.read() == 10
                }
                if (!terminated) target.appendBytes(byteArrayOf(10))
            }
            target.appendBytes(bytes)
            prune()
            return true
        } finally { lock.unlock() }
    }

    private fun files(): List<File> = directory.listFiles()?.filter { it.isFile && it.extension == "jsonl" }
        ?.sortedWith(compareBy<File> { createdAt(it) }.thenBy { it.name }) ?: emptyList()

    private fun createdAt(file: File): Long = file.name.substringBefore('-').toLongOrNull() ?: file.lastModified()

    private fun prune() {
        var size = files().sumOf { it.length() }
        files().forEach { file ->
            if (createdAt(file) < clock() - retentionMs || size > totalLimit) {
                val length = file.length()
                if (file.delete()) size -= length
            }
        }
    }

    fun clear() = lock.withLock {
        files().forEach { check(it.delete()) { "Unable to delete log" } }
        active = null
    }

    fun stats(): Pair<Int, Long> = lock.withLock {
        prune()
        files().let { it.size to it.sumOf(File::length) }
    }

    fun fingerprint(): String = lock.withLock {
        prune()
        files().joinToString(";") { "${it.name}:${it.length()}:${it.lastModified()}" }
    }

    private fun decode(bytes: ByteArray): LogRecord? {
        decodedLines++
        return runCatching { adapter.fromJson(bytes.toString(Charsets.UTF_8)) }.getOrNull()
    }

    /** Retain only metadata for history. Appends decode the newly completed lines, not older segments. */
    private fun index(): List<Segment> {
        val current = lock.withLock { prune(); files() }
        segments.keys.retainAll(current.toSet())
        current.forEach { file ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val segment = segments.getOrPut(file) { Segment(file) }
            val bytes = lock.withLock {
                if (!file.exists()) return@withLock ByteArray(0)
                val size = file.length()
                val modified = file.lastModified()
                if (size < segment.observed || (size == segment.observed && modified != segment.modified)) {
                    segment.entries.clear()
                    segment.latestError = null
                    segment.indexed = 0
                    recordCache.clear()
                }
                segment.observed = size
                segment.modified = modified
                RandomAccessFile(file, "r").use { input ->
                    input.seek(segment.indexed)
                    ByteArray((size - segment.indexed).toInt()).also { input.readFully(it) }
                }
            }
            var start = 0
            val added = ArrayList<Entry>()
            for (end in bytes.indices) {
                if (bytes[end] != 10.toByte()) continue
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val record = decode(bytes.copyOfRange(start, end))
                if (record != null) added.add(Entry(file, segment.indexed + start, end - start,
                    record.copy(fields = emptyMap(), error = null, event = "")))
                start = end + 1
            }
            segment.indexed += start
            segment.entries.addAll(added)
            added.filter { it.summary.level == LogLevel.ERROR }.forEach { entry ->
                if (segment.latestError?.let { NEWEST_FIRST.compare(entry.summary, it.summary) < 0 } != false) segment.latestError = entry
            }
        }
        return current.mapNotNull { segments[it] }
    }

    private fun record(entry: Entry): LogRecord? {
        recordCache[entry.summary.id]?.let { return it }
        val bytes = lock.withLock {
            if (!entry.file.exists() || entry.file.length() < entry.offset + entry.length) return null
            RandomAccessFile(entry.file, "r").use { input ->
                input.seek(entry.offset)
                ByteArray(entry.length).also { input.readFully(it) }
            }
        }
        return decode(bytes)?.also { recordCache[it.id] = it }
    }

    fun page(filter: LogFilter = LogFilter(), before: LogRecord? = null, limit: Int = 100): LogPage = indexLock.withLock {
        require(limit in 1..1000)
        val indexed = index()
        val candidates = indexed.asSequence().flatMap { it.entries.asSequence() }
            .filter { entry -> entry.summary.let { r -> r.timestamp in filter.since..filter.until &&
                (filter.level == null || r.level == filter.level) && (filter.module == null || r.module == filter.module) &&
                (filter.trace == null || r.trace == filter.trace) && (filter.session == null || r.session == filter.session) &&
                (before == null || NEWEST_FIRST.compare(r, before) > 0) } }
            .sortedWith { a, b -> NEWEST_FIRST.compare(a.summary, b.summary) }
        val page = candidates.mapNotNull(::record).filter(filter::matches).take(limit).toList()
        val error = indexed.asSequence().mapNotNull { it.latestError }
            .minWithOrNull { a, b -> NEWEST_FIRST.compare(a.summary, b.summary) }?.let(::record)
        LogPage(page, indexed.size to indexed.sumOf { it.observed }, error)
    }

    fun query(before: LogRecord? = null, limit: Int = 100, predicate: (LogRecord) -> Boolean = { true }): List<LogRecord> = indexLock.withLock {
        require(limit in 1..1000)
        index().asSequence().flatMap { it.entries.asSequence() }
            .filter { before == null || NEWEST_FIRST.compare(it.summary, before) > 0 }
            .sortedWith { a, b -> NEWEST_FIRST.compare(a.summary, b.summary) }
            .mapNotNull(::record).filter(predicate).take(limit).toList()
    }

    /** Open handles and fix byte lengths under a short lock. Copying never occupies the writer queue. */
    fun captureSnapshot(): LogSnapshot = lock.withLock {
        prune()
        val entries = ArrayList<LogSnapshot.Part>()
        try {
            files().forEach { entries.add(LogSnapshot.Part(it.name, RandomAccessFile(it, "r"), it.length())) }
            LogSnapshot(entries, adapter)
        } catch (e: Exception) { entries.forEach { it.input.close() }; throw e }
    }

    fun snapshot(destination: File) { captureSnapshot().use { it.copyTo(destination) } }

    companion object {
        val NEWEST_FIRST: Comparator<LogRecord> = compareByDescending<LogRecord> { it.timestamp }.thenByDescending { it.id }
    }
}

class LogSnapshot internal constructor(private val parts: List<Part>, private val adapter: com.squareup.moshi.JsonAdapter<LogRecord>) : Closeable {
    internal data class Part(val name: String, val input: RandomAccessFile, val length: Long)
    fun copyTo(destination: File, filter: LogFilter? = null) {
        destination.mkdirs()
        parts.forEach { part ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val file = File(destination, part.name)
            part.input.seek(0)
            val bounded = object : java.io.InputStream() {
                var remaining = part.length
                override fun read(): Int = if (remaining <= 0) -1 else part.input.read().also { if (it >= 0) remaining-- }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = if (remaining <= 0) -1 else
                    part.input.read(bytes, offset, minOf(length.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
            }
            if (filter == null) file.outputStream().use { bounded.copyTo(it) }
            else file.bufferedWriter(Charsets.UTF_8).use { output ->
                bounded.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    val record = runCatching { adapter.fromJson(line) }.getOrNull()
                    if (record != null && filter.matches(record)) { output.write(line); output.newLine() }
                }
            }
            if (file.length() == 0L) file.delete()
        }
    }
    override fun close() { parts.forEach { runCatching { it.input.close() } } }
}
