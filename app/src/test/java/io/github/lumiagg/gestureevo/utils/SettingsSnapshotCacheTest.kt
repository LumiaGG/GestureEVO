package io.github.lumiagg.gestureevo.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsSnapshotCacheTest {
    private val key = intPreferencesKey("value")
    private class FakeStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        var failAfterCommit = false
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(data.value)
            data.value = next
            if (failAfterCommit) throw kotlinx.coroutines.CancellationException("committed before cancellation")
            return next
        }
    }

    @Test fun firstReadSeedsCacheAndCommittedWriteIsImmediatelyVisible() = runBlocking {
        val delegate = FakeStore(preferencesOf(key to 1))
        val store = SnapshotPreferencesDataStore(delegate)
        assertNull(store.current)
        assertSame(store.data.first(), store.current)
        val committed = store.updateData { preferencesOf(key to 2) }
        assertSame(committed, store.current)
    }

    @Test fun staleCollectorCannotOverwriteCommittedSettings() = runBlocking {
        val delegate = FakeStore(preferencesOf(key to 1))
        val store = SnapshotPreferencesDataStore(delegate)
        store.data.first()
        store.updateData { preferencesOf(key to 2) }
        delegate.data.value = preferencesOf(key to 1)
        store.data.first()
        assertEquals(2, store.current?.get(key))
    }

    @Test fun concurrentUpdatesPreserveCommitOrder() = runBlocking {
        val delegate = FakeStore(preferencesOf(key to 0))
        val store = SnapshotPreferencesDataStore(delegate)
        store.data.first()
        (1..100).map {
            async(Dispatchers.Default) {
                store.updateData { preferencesOf(key to ((it[key] ?: 0) + 1)) }
            }
        }.awaitAll()
        assertEquals(100, store.current?.get(key))
        assertSame(delegate.data.value, store.current)
    }

    @Test fun interruptedCommitDisablesCacheEvenIfCollectorsContinue() = runBlocking {
        val delegate = FakeStore(preferencesOf(key to 1))
        val store = SnapshotPreferencesDataStore(delegate)
        store.data.first()
        delegate.failAfterCommit = true
        try {
            store.updateData { preferencesOf(key to 2) }
            throw AssertionError("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertNull(store.current)
        }
        assertEquals(2, store.data.first()[key])
        assertNull(store.current)
    }
}
