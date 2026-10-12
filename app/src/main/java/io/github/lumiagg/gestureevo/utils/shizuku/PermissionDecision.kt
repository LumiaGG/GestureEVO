package io.github.lumiagg.gestureevo.utils.shizuku

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** The callback is removed on grant, denial, request failure and caller cancellation. */
internal suspend fun awaitPermissionDecision(
    subscribe: ((Boolean) -> Unit) -> (() -> Unit),
    request: () -> Unit,
): Boolean {
    coroutineContext.ensureActive()
    val result = CompletableDeferred<Boolean>()
    val unsubscribe = subscribe { result.complete(it) }
    try {
        request()
        return result.await()
    } finally {
        unsubscribe()
    }
}
