package io.github.lumiagg.gestureevo.link

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class LinkPersistenceTest {
    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    @Test fun concurrentWritesKeepEveryEntry() = runBlocking {
        val store = Store()
        val writes = (0 until 80).map { number ->
            launch(Dispatchers.Default) { store.updateLinkList<Int>("rules") { it + number } }
        }
        writes.forEach { it.join() }
        assertEquals((0 until 80).toSet(), store.data.first().readLinkList<Int>("rules").toSet())
    }

    @Test fun statisticsAndToggleUpdatesPreserveBothChanges() = runBlocking {
        val store = Store()
        store.updateLinkList<LinkDispatchRule>("rules") { listOf(LinkDispatchRule(id = "r", isEnabled = true)) }
        val hits = launch(Dispatchers.Default) {
            repeat(40) { store.updateLinkList<LinkDispatchRule>("rules") { rules -> rules.map { it.copy(hitCount = it.hitCount + 1) } } }
        }
        val edit = launch(Dispatchers.Default) {
            store.updateLinkList<LinkDispatchRule>("rules") { rules -> rules.map { it.copy(isEnabled = false) } }
        }
        hits.join(); edit.join()
        val saved = store.data.first().readLinkList<LinkDispatchRule>("rules").single()
        assertEquals(40, saved.hitCount)
        assertFalse(saved.isEnabled)
    }

    @Test fun failedTransformationDoesNotReplaceStoredConfiguration() = runBlocking {
        val store = Store()
        store.updateLinkList<Int>("rules") { listOf(1, 2) }
        try {
            store.updateLinkList<Int>("rules") { throw IllegalArgumentException("invalid import") }
            fail("Expected invalid import to fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(1, 2), store.data.first().readLinkList<Int>("rules"))
    }
}
