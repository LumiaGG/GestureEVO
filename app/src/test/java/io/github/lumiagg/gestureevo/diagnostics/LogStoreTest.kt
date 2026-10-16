package io.github.lumiagg.gestureevo.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class LogStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(number: Int) = LogRecord(number.toLong(), LogLevel.INFO, LogModule.ACTION, "launch_requested",
        trace = "trace-$number", fields = mapOf("package" to "example.app"))

    @Test fun rotationNeverExceedsFileOrTotalBudget() {
        val dir = temporary.newFolder()
        val store = LogStore(dir, fileLimit = 600, totalLimit = 1500)
        repeat(40) { assertTrue(store.append(record(it))) }
        assertTrue(dir.listFiles()!!.all { it.length() <= 600 })
        assertTrue(store.stats().second <= 1500)
        assertTrue(store.stats().first > 1)
    }

    @Test fun retentionPrunesOldRecordsEvenWhenNoNewLogsAreWritten() {
        var now = System.currentTimeMillis()
        val dir = temporary.newFolder()
        val store = LogStore(dir, retentionMs = 1000, clock = { now })
        store.append(record(1))
        now += 2000
        assertEquals(0, store.stats().first)
        assertTrue(store.query().isEmpty())
    }

    @Test fun cursorPreservesEventsWithTheSameTimestampAcrossRotatedFiles() {
        val store = LogStore(temporary.newFolder(), fileLimit = 600)
        repeat(20) { store.append(record(it).copy(timestamp = 100)) }
        val seen = mutableSetOf<String>()
        var cursor: LogRecord? = null
        do {
            val page = store.query(before = cursor, limit = 3)
            page.forEach { assertTrue(seen.add(it.id)) }
            cursor = page.lastOrNull()
        } while (cursor != null)
        assertEquals(20, seen.size)
    }

    @Test fun ongoingLowVolumeLoggingCannotKeepOldRecordsForever() {
        var now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        val store = LogStore(temporary.newFolder(), clock = { now })
        store.append(record(1))
        now += 6 * day
        store.append(record(2))
        assertEquals(2, store.stats().first)
        now += 2 * day
        assertEquals(listOf(2L), store.query().map { it.timestamp })
    }

    @Test fun filtersAndMalformedCrashTailDoNotHideValidRecords() {
        val dir = temporary.newFolder()
        val store = LogStore(dir)
        repeat(4) { store.append(record(it)) }
        dir.listFiles()!!.single().appendText("{incomplete")
        assertEquals(listOf(3L, 1L), store.query { it.timestamp % 2 == 1L }.map { it.timestamp })
        val restarted = LogStore(dir)
        assertTrue(restarted.append(record(5)))
        assertEquals(listOf(5L, 3L, 1L), restarted.query { it.timestamp % 2 == 1L }.map { it.timestamp })
    }

    @Test fun concurrentWritesSnapshotAndClearLeaveReadableFiles() {
        val store = LogStore(temporary.newFolder())
        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { worker -> pool.submit { repeat(30) { store.append(record(worker * 30 + it)) } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(120, store.query(limit = 200).size)
        val snapshot = temporary.newFolder()
        store.snapshot(snapshot)
        store.clear()
        assertTrue(store.query().isEmpty())
        assertEquals(120, LogStore(snapshot).query(limit = 200).size)
        val zip = File(temporary.root, "logs.zip")
        LogArchive.zip(snapshot, zip)
        ZipFile(zip).use { archive ->
            assertEquals(snapshot.listFiles()!!.size, archive.size())
            assertTrue(archive.entries().asSequence().all { !it.name.contains("..") && !it.name.contains('\\') })
        }
    }

    @Test fun privacyRejectsPayloadFieldsAndNeverSavesThrowableMessages() {
        val fields = LogPrivacy.fields(mapOf("package" to "com.arlosoft.macrodroid", "flags" to 123,
            "uri" to "https://example.com/?token=secret", "shell" to "secret", "password" to "secret",
            "reason" to "https://example.com/?token=secret"))
        assertEquals(setOf("package", "flags"), fields.keys)
        val stack = LogPrivacy.stack(IllegalStateException("secret-password", IllegalArgumentException("private OCR text")))
        assertTrue(stack.contains("IllegalStateException"))
        assertTrue(stack.contains("IllegalArgumentException"))
        assertFalse(stack.contains("secret-password"))
        assertFalse(stack.contains("private OCR text"))
    }

    @Test fun oversizedRecordCannotBreakTheStorageBudget() {
        val store = LogStore(temporary.newFolder(), fileLimit = 400, totalLimit = 1000)
        assertFalse(store.append(record(1).copy(error = "x".repeat(2000))))
        assertEquals(0L, store.stats().second)
    }

    @Test fun systemCaptureKeepsOnlyKnownTargetsAndClassifications() {
        val packages = listOf("example.app")
        val result = SystemLogCapture.classify("Background activity start denied example.app extras=secret", packages)
        assertEquals("background_launch_blocked", result?.get("reason"))
        assertFalse(result.toString().contains("secret"))
        assertNull(SystemLogCapture.classify("Background activity start denied example.application", packages))
        assertNull(SystemLogCapture.classify("Unrelated content example.app secret", packages))
    }
}
