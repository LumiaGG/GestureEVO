package io.github.lumiagg.gestureevo.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/** Publishes only committed settings; every writer goes through the same store. */
internal class SnapshotPreferencesDataStore(
    private val delegate: DataStore<Preferences>
) : DataStore<Preferences> {
    private val snapshot = AtomicReference<Preferences?>(null)
    private val updateMutex = Mutex()
    @Volatile private var cacheValid = true
    val current: Preferences? get() = if (cacheValid) snapshot.get() else null

    override val data = delegate.data.onEach {
        // A slow collector must never overwrite a newer committed update.
        snapshot.compareAndSet(null, it)
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        updateMutex.withLock {
            try {
                delegate.updateData(transform).also { snapshot.set(it) }
            } catch (failure: Throwable) {
                // Cancellation can happen after the disk commit but before the result arrives.
                // Fall back to DataStore reads for this process rather than expose stale settings.
                cacheValid = false
                throw failure
            }
        }
}

internal object SettingsSnapshotCache {
    @Volatile private var store: SnapshotPreferencesDataStore? = null
    val current: Preferences? get() = store?.current

    fun getStore(delegate: DataStore<Preferences>): DataStore<Preferences> =
        store ?: synchronized(this) {
            store ?: SnapshotPreferencesDataStore(delegate).also { store = it }
        }
}
