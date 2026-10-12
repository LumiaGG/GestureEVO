package io.github.lumiagg.gestureevo.utils.shizuku

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PermissionDecisionTest {
    @Test fun grantedAndDeniedResultsBothRemoveTheirListener() = runBlocking {
        for (decision in listOf(true, false)) {
            var callback: ((Boolean) -> Unit)? = null
            var removed = 0
            var requested = 0
            var actual: Boolean? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                actual = awaitPermissionDecision(
                    subscribe = { callback = it; { removed++ } },
                    request = { requested++ },
                )
            }
            checkNotNull(callback)(decision)
            job.join()
            assertEquals(decision, actual)
            assertEquals(1, requested)
            assertEquals(1, removed)
        }
    }

    @Test fun cancellationRemovesListenerAndLateResultsDoNotResumeTheCaller() = runBlocking {
        var callback: ((Boolean) -> Unit)? = null
        var removed = 0
        var completed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitPermissionDecision({ callback = it; { removed++ } }, {})
            completed = true
        }
        job.cancel()
        job.join()
        checkNotNull(callback)(true)
        assertFalse(completed)
        assertEquals(1, removed)
    }

    @Test fun duplicateAndSynchronousResultsUseTheFirstDecision() = runBlocking {
        var callback: ((Boolean) -> Unit)? = null
        var removed = 0
        val actual = awaitPermissionDecision(
            subscribe = { callback = it; { removed++ } },
            request = { checkNotNull(callback)(true); checkNotNull(callback)(false) },
        )
        assertTrue(actual)
        assertEquals(1, removed)
    }

    @Test fun requestFailureRemovesListenerAndKeepsTheOriginalError() = runBlocking {
        var removed = 0
        val failure = IllegalStateException("request failed")
        try {
            awaitPermissionDecision({ { removed++ } }, { throw failure })
            fail("Expected request failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, removed)
    }

    @Test fun cancellationIsPropagatedRatherThanReturnedAsPermissionDenial() = runBlocking {
        var removed = 0
        try {
            awaitPermissionDecision({ { removed++ } }, { throw CancellationException("cancelled") })
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, removed)
        }
    }
}
