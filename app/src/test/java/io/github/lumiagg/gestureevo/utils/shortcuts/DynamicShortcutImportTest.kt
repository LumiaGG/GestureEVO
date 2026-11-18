package io.github.lumiagg.gestureevo.utils.shortcuts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DynamicShortcutImportTest {
    private val selected = listOf(ShortcutIdentity("app", "same-id", 12))

    private class Backend : ShortcutImportBackend {
        var pins = setOf("older")
        var staged = emptyList<ShortcutIdentity>()
        var confirmed = emptyList<ShortcutIdentity>()
        var failPin = false
        var dropPin = false
        var cancelPin = false
        override suspend fun pinnedIds(packageName: String, userSerial: Long) = pins
        override suspend fun stage(shortcuts: List<ShortcutIdentity>) { staged = shortcuts }
        override suspend fun pin(packageName: String, userSerial: Long, ids: Set<String>) {
            check(staged.isNotEmpty())
            if (cancelPin) throw CancellationException()
            check(!failPin)
            if (!dropPin) pins = ids
        }
        override suspend fun confirm(shortcuts: List<ShortcutIdentity>) { confirmed = shortcuts }
    }

    @Test fun importRetainsEarlierPinsAndCanBeRepeated() = runBlocking {
        val backend = Backend()
        repeat(2) { importShortcutGroup(backend, selected) }
        assertEquals(setOf("older", "same-id"), backend.pins)
        assertEquals(selected, backend.confirmed)
    }

    @Test fun failedPinDoesNotPublishStagedRecords() = runBlocking {
        val backend = Backend().apply { failPin = true }
        assertTrue(runCatching { importShortcutGroup(backend, selected) }.isFailure)
        assertEquals(selected, backend.staged)
        assertTrue(backend.confirmed.isEmpty())
        assertEquals(setOf("older"), backend.pins)
    }

    @Test fun silentPinFailureIsNotReportedAsSuccess() = runBlocking {
        val backend = Backend().apply { dropPin = true }
        assertTrue(runCatching { importShortcutGroup(backend, selected) }.isFailure)
        assertTrue(backend.confirmed.isEmpty())
    }

    @Test fun differentProfilesCannotBeMergedDespiteEqualShortcutIds() = runBlocking {
        val backend = Backend()
        assertTrue(runCatching {
            importShortcutGroup(backend, selected + selected.first().copy(userSerial = 13))
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(backend.staged.isEmpty())
    }

    @Test fun cancellationRemainsCancellationAndDoesNotConfirm() = runBlocking {
        val backend = Backend().apply { cancelPin = true }
        assertTrue(runCatching { importShortcutGroup(backend, selected) }.exceptionOrNull() is CancellationException)
        assertTrue(backend.confirmed.isEmpty())
    }
}
