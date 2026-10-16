package io.github.lumiagg.gestureevo.diagnostics

import android.util.AtomicFile
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

data class ProblemSession(val id: String, val startedAt: Long, val endedAt: Long? = null, val result: String = "recording") {
    fun filter() = LogFilter(since = startedAt, until = endedAt ?: Long.MAX_VALUE, session = id)
}

object ProblemSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableHistory = MutableStateFlow<List<ProblemSession>>(emptyList())
    val history = mutableHistory.asStateFlow()
    val active: ProblemSession? get() = mutableHistory.value.firstOrNull { it.endedAt == null }
    private lateinit var file: AtomicFile
    private val adapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        .adapter<List<ProblemSession>>(Types.newParameterizedType(List::class.java, ProblemSession::class.java))
    private var timer: Job? = null
    private var previousDetailDeadline = 0L
    private var recordingDetailDeadline = 0L

    fun initialize(path: File) {
        file = AtomicFile(path)
        val recovered = runCatching { file.openRead().bufferedReader().use { adapter.fromJson(it.readText()) } }
            .getOrNull().orEmpty().take(20).map { session ->
                if (session.endedAt == null) session.copy(endedAt = System.currentTimeMillis(), result = "interrupted") else session
            }
        mutableHistory.value = recovered
        scope.launch { mutex.withLock { runCatching { persist(mutableHistory.value) }.onFailure { AppLogger.storageFailure() } } }
    }

    private fun persist(sessions: List<ProblemSession>) {
        val output = file.startWrite()
        try {
            output.write(adapter.toJson(sessions).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
    }

    suspend fun start(): ProblemSession = withContext(Dispatchers.IO) { mutex.withLock {
        check(AppLogger.enabled)
        active?.let { return@withLock it }
        val session = ProblemSession(UUID.randomUUID().toString(), System.currentTimeMillis())
        val next = (listOf(session) + mutableHistory.value).take(20)
        persist(next)
        mutableHistory.value = next
        previousDetailDeadline = AppLogger.detailDeadline()
        AppLogger.setDetailed(true)
        recordingDetailDeadline = AppLogger.detailDeadline()
        AppLogger.event(LogModule.APP, "problem_recording_started", trace = session.id)
        timer?.cancel()
        timer = scope.launch {
            delay(30 * 60 * 1000L)
            runCatching { finish("duration_limit") }.onFailure { AppLogger.storageFailure() }
        }
        session
    } }

    suspend fun finish(result: String = "complete"): ProblemSession? = withContext(Dispatchers.IO) { mutex.withLock {
        val current = active ?: return@withLock null
        // Finish system capture first so its terminal events belong to this problem record.
        SystemLogCapture.stopAndJoin()
        AppLogger.event(LogModule.APP, "problem_recording_finished", trace = current.id, fields = mapOf("reason" to result))
        val finished = current.copy(endedAt = System.currentTimeMillis(), result = result)
        val next = mutableHistory.value.map { if (it.id == current.id) finished else it }
        mutableHistory.value = next
        AppLogger.restoreDetailDeadline(recordingDetailDeadline, previousDetailDeadline)
        persist(next)
        finished
    } }

    suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock {
        timer?.cancel()
        persist(emptyList())
        mutableHistory.value = emptyList()
    } }
}
