package io.github.lumiagg.gestureevo.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch

class IoResultTest {
    @Test fun successfulOperationKeepsTheValue() = runBlocking {
        assertEquals(listOf("backup.zip"), ioResult { listOf("backup.zip") }.getOrThrow())
    }

    @Test fun operationFailureKeepsTheOriginalException() = runBlocking {
        val failure = IOException("server unavailable")
        assertSame(failure, ioResult { throw failure }.exceptionOrNull())
    }

    @Test fun cancellationIsNotConvertedIntoAnOrdinaryFailure() = runBlocking {
        val cancelled = CancellationException("screen closed")
        try {
            ioResult { throw cancelled }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            // Coroutine stack-trace recovery may copy cancellation exceptions across dispatchers.
            assertEquals(cancelled.message, actual.message)
        }
    }

    @Test(timeout = 10000) fun cancellationDuringBlockingIoDoesNotResumeWithFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var resumed = false
        val job = launch {
            ioResult {
                entered.complete(Unit)
                release.await()
                throw IOException("socket closed")
            }
            resumed = true
        }
        try {
            entered.await()
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(resumed)
    }

    @Test fun resourceUrlsKeepExistingServerPathsAndEncodedNames() {
        assertEquals("https://host/dav/backups/a%20b.zip", webDavResourceUrl("https://host/dav/", "backups/a%20b.zip"))
        assertEquals("https://host/dav/backups", webDavResourceUrl("https://host/dav", "/backups"))
    }

    @Test fun slashVariantsPointToTheSameDirectory() {
        for (server in listOf("https://host/dav", "https://host/dav/")) {
            for (path in listOf("backups", "/backups")) {
                assertEquals("https://host/dav/backups", webDavResourceUrl(server, path))
            }
            assertEquals("https://host/dav/", webDavResourceUrl(server, ""))
        }
    }
}
