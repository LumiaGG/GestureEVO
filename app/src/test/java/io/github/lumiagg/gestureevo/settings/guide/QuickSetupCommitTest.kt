package io.github.lumiagg.gestureevo.settings.guide

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuickSetupCommitTest {
    @Test
    fun failedSecondWriteRestoresTheFirstWriteBeforeReturningFailure() = runBlocking {
        var actions = "old-actions"
        var published = false
        val failure = runCatching {
            commitQuickSetup(writeConfiguration = {
                actions = "new-actions"
                error("trigger write failed")
            }, publishConfiguration = { published = true }, rollbackConfiguration = { actions = "old-actions" })
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("old-actions", actions)
        assertFalse(published)
    }

    @Test
    fun runtimeIsRefreshedOnlyAfterConfigurationIsCommitted() = runBlocking {
        val steps = mutableListOf<String>()
        commitQuickSetup(
            writeConfiguration = { steps.add("write") },
            publishConfiguration = { steps.add("refresh") }
        )
        assertEquals(listOf("write", "refresh"), steps)
    }

    @Test
    fun recreationDuringWritingStillRefreshesRuntime() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val finishWriting = CompletableDeferred<Unit>()
        var committed = false
        var published = false
        var returnedToScreen = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            commitQuickSetup(
                writeConfiguration = {
                    writing.complete(Unit)
                    finishWriting.await()
                    committed = true
                },
                publishConfiguration = {
                    assertTrue(committed)
                    published = true
                }
            )
            returnedToScreen = true
        }
        writing.await()
        job.cancel()
        finishWriting.complete(Unit)
        job.join()
        assertTrue(committed)
        assertTrue(published)
        assertFalse(returnedToScreen)
    }

    @Test
    fun cancelledScreenCannotStartANewCommit() = runBlocking {
        var written = false
        var published = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            commitQuickSetup(
                writeConfiguration = { written = true },
                publishConfiguration = { published = true }
            )
        }
        job.join()
        assertFalse(written)
        assertFalse(published)
    }

    @Test
    fun failedWritesDoNotPublishAndPropagateTheFailure() = runBlocking {
        val failure = IllegalStateException("write failed")
        var published = false
        try {
            commitQuickSetup(
                writeConfiguration = { throw failure },
                publishConfiguration = { published = true }
            )
            fail("Expected write failure")
        } catch (actual: IllegalStateException) {
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
        }
        assertFalse(published)
    }
}
