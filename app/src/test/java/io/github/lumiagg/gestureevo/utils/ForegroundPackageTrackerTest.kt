package io.github.lumiagg.gestureevo.utils

import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundPackageTrackerTest {
    private val home = "example.launcher"
    private val app = "example.app"
    private val self = "example.gesture"
    private val back = FreeTriggerConfig().apply { blackList = listOf(home) }
    private val lock = FreeTriggerConfig().apply {
        setState(FreeTriggerConfig.FLAG_WHITELIST, true)
        whiteList = listOf(home)
    }

    private fun assertActions(tracker: ForegroundPackageTracker, expected: List<String>) {
        val pkg = requireNotNull(tracker.currentPackage.value)
        val actions = listOf("back" to back, "lock" to lock)
            .filter { it.second.isPackageAllowed(pkg, self) }.map { it.first }
        assertEquals(expected, actions)
    }

    @Test fun windowListEventsWithoutPackageSwitchBothRulesInBothDirections() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var window: String? = app
        val tracker = ForegroundPackageTracker(scope, { window })
        try {
            repeat(20) {
                window = app
                tracker.onWindowsChanged()
                assertActions(tracker, listOf("back"))
                window = home
                tracker.onWindowsChanged()
                assertActions(tracker, listOf("lock"))
            }
        } finally { scope.cancel() }
    }

    @Test fun rejectedActivityClassStillRefreshesFromActualWindows() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var window = app
        val tracker = ForegroundPackageTracker(scope, { window })
        try {
            tracker.onWindowStateChanged(app)
            window = home
            tracker.onWindowStateChanged(null)
            assertActions(tracker, listOf("lock"))
        } finally { scope.cancel() }
    }

    @Test fun confirmationFindsDesktopWhenImmediateWindowSnapshotWasStale() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var window = app
        val tracker = ForegroundPackageTracker(scope, { window }, confirmationDelayMs = 1)
        try {
            tracker.onWindowsChanged()
            assertActions(tracker, listOf("back"))
            window = home
            withTimeout(1000) { tracker.currentPackage.first { it == home } }
            assertActions(tracker, listOf("lock"))
        } finally { scope.cancel() }
    }

    @Test fun trustedActivityEventTakesEffectBeforeWindowsCatchUp() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val tracker = ForegroundPackageTracker(scope, { app })
        try {
            tracker.onWindowStateChanged(home)
            assertActions(tracker, listOf("lock"))
        } finally { scope.cancel() }
    }

    @Test fun unavailableWindowsPreserveTheLastKnownRulesUntilTheyRecover() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var window: String? = null
        val tracker = ForegroundPackageTracker(scope, { window })
        try {
            tracker.onWindowStateChanged(home)
            tracker.onWindowsChanged()
            assertActions(tracker, listOf("lock"))
            window = app
            tracker.onWindowsChanged()
            assertActions(tracker, listOf("back"))
        } finally { scope.cancel() }
    }

    @Test fun disconnectCancelsPendingConfirmation() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val tracker = ForegroundPackageTracker(scope, { home }, confirmationDelayMs = 0)
        try {
            tracker.onWindowStateChanged(app)
            tracker.clear()
            yield()
            assertNull(tracker.currentPackage.value)
        } finally { scope.cancel() }
    }
}
