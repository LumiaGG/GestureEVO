package io.github.lumiagg.gestureevo.utils

/** Publishes a newly created resource only after writing succeeds. */
internal fun <T : Any> writeCreatedResource(
    create: () -> T?,
    write: (T) -> Unit,
    publish: (T) -> Unit,
    rollback: (T) -> Unit,
): T? {
    val resource = create() ?: return null
    try {
        write(resource)
        publish(resource)
        return resource
    } catch (failure: Exception) {
        try {
            rollback(resource)
        } catch (cleanupFailure: Exception) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}
