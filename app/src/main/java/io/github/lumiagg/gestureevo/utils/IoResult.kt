package io.github.lumiagg.gestureevo.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Converts operation failures to Result while preserving coroutine cancellation. */
internal suspend fun <T> ioResult(operation: () -> T): Result<T> = withContext(Dispatchers.IO) {
    coroutineContext.ensureActive()
    try {
        val value = operation()
        coroutineContext.ensureActive()
        Result.success(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        coroutineContext.ensureActive()
        Result.failure(failure)
    }
}
