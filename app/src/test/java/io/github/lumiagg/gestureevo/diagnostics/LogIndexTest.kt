package io.github.lumiagg.gestureevo.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LogIndexTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(index: Int, session: String? = null) = LogRecord(index.toLong(),
        if (index % 20 == 0) LogLevel.ERROR else LogLevel.INFO, LogModule.ACTION,
        "launch_requested", trace = "trace-$index", session = session, fields = mapOf("package" to "example.app"))

    @Test fun unchangedRefreshAndLatestErrorDoNotDecodeHistoryAgain() {
        val store = LogStore(temporary.newFolder())
        repeat(1000) { store.append(record(it)) }
        val first = store.page()
        assertEquals(980L, first.latestError?.timestamp)
        val decoded = store.decodedLines
        assertEquals(first, store.page())
        assertEquals(decoded, store.decodedLines)
        store.append(record(1000))
        assertEquals(1000L, store.page().latestError?.timestamp)
        assertTrue(store.decodedLines - decoded <= 2)
    }

    @Test fun partialTailIsIndexedOnceOnlyAfterItBecomesComplete() {
        val dir = temporary.newFolder()
        val store = LogStore(dir)
        store.append(record(1))
        val source = dir.listFiles()!!.single()
        source.appendText("{\"timestamp\":2,\"level\":\"WARN\",\"module\":\"SYSTEM\",\"event\":\"partial\",\"id\":\"tail\"")
        assertEquals(1, store.page().records.size)
        source.appendText("}\n")
        assertEquals(listOf(2L, 1L), store.page().records.map { it.timestamp })
        assertEquals(listOf(2L, 1L), store.page().records.map { it.timestamp })
    }

    @Test fun clearAndRotationInvalidateIndexesWithoutReturningStaleRecords() {
        val store = LogStore(temporary.newFolder(), fileLimit = 1000, totalLimit = 3000)
        repeat(10) { store.append(record(it)) }
        store.page()
        store.clear()
        assertTrue(store.page().records.isEmpty())
        store.append(record(99))
        assertEquals(listOf(99L), store.page().records.map { it.timestamp })
    }

    @Test fun typedFiltersAndPaginationKeepSameTimestampSessionsSeparate() {
        val store = LogStore(temporary.newFolder(), fileLimit = 1000)
        repeat(30) { store.append(record(it, if (it % 2 == 0) "problem-a" else "problem-b").copy(timestamp = 100)) }
        val filter = LogFilter(since = 100, until = 100, session = "problem-a", query = "example.app")
        val first = store.page(filter, limit = 7).records
        val second = store.page(filter, before = first.last(), limit = 10).records
        assertEquals(15, (first + second).map { it.id }.toSet().size)
        assertTrue((first + second).all { it.session == "problem-a" })
        assertEquals(1, store.page(LogFilter(trace = "trace-9")).records.size)
    }

    @Test fun openSnapshotAllowsWritesAndExportsOnlyCommittedBytesAndChosenProblem() {
        val store = LogStore(temporary.newFolder())
        store.append(record(1, "problem-a"))
        store.append(record(2, "problem-b"))
        val snapshot = store.captureSnapshot()
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertTrue(pool.submit<Boolean> { store.append(record(3, "problem-a")) }.get(2, TimeUnit.SECONDS))
            val dest = temporary.newFolder()
            snapshot.copyTo(dest, LogFilter(session = "problem-a"))
            assertEquals(listOf(1L), LogStore(dest).page().records.map { it.timestamp })
        } finally { snapshot.close(); pool.shutdownNow() }
    }

    @Test fun safeErrorFieldsKeepTypedCodesButDiscardPrivateExceptionMessages() {
        val exception = com.thegrizzlylabs.sardineandroid.impl.SardineException("https://private/?password=secret", 403, "private")
        assertEquals(403, LogErrors.fields(exception)["httpStatus"])
        val timeout = java.io.IOException("user content", java.net.SocketTimeoutException("secret token"))
        assertEquals("network_timeout", LogErrors.fields(timeout)["reason"])
        assertFalse(LogErrors.fields(timeout).toString().contains("secret"))
    }

    @Test fun targetLruKeepsNewAppsAndRefreshesRecentlyUsedApps() {
        val targets = RecentTargets(3)
        listOf("app.one", "app.two", "app.three", "app.one", "app.four").forEach(targets::remember)
        assertEquals(listOf("app.four", "app.one", "app.three"), targets.snapshot())
    }
}
