package io.github.lumiagg.gestureevo.utils

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.drop.DropOverlayAppearance
import io.github.lumiagg.gestureevo.drop.DropPreferences
import io.github.lumiagg.gestureevo.inspire.InspireDataHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import java.lang.ref.WeakReference

/** Requires an Android 12+ device with hardware rendering/card blur support. */
class CardRegionBlurTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(10_000) { while (!withContext(Dispatchers.Main) { condition() }) delay(50) }
    }

    @Before fun bringAppToForeground() {
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/io.github.lumiagg.gestureevo.MainActivity"
        ).use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
    }

    private suspend fun startActivity(name: String): Activity {
        val application = context.applicationContext as Application
        var resumed = WeakReference<Activity>(null)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                if (a.javaClass.name == "io.github.lumiagg.gestureevo.$name") resumed = WeakReference(a)
            }
            override fun onActivityCreated(a: Activity, state: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivityDestroyed(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            // The test owns only a weak reference while waiting for launch.
            main {
                context.startActivity(Intent().setClassName(context.packageName, "io.github.lumiagg.gestureevo.$name")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            await { resumed.get() != null }
            return checkNotNull(resumed.get())
        } finally { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }

    private suspend fun collect() {
        repeat(3) {
            main {}
            Runtime.getRuntime().gc()
            System.runFinalization()
            delay(300)
        }
    }

    private fun host(card: View): View? = card.getTag(R.id.cardRegionBlurHost) as? View

    private suspend fun withSettings(keys: List<String>, block: suspend () -> Unit) {
        val store = context.settingsStore
        val original = store.data.first()
        val typedKeys = keys.map(::booleanPreferencesKey)
        try { block() }
        finally {
            store.edit { prefs ->
                typedKeys.forEach { key ->
                    val value = original[key]
                    if (value == null) prefs.remove(key) else prefs[key] = value
                }
            }
        }
    }

    @Test fun detachReattachAndDisableRestoreRenderingAndReleaseDrawable() = runBlocking {
        val activity = startActivity("settings.SettingsActivity")
        try {
            lateinit var root: FrameLayout
            lateinit var card: MaterialCardView
            main {
                root = FrameLayout(activity)
                card = MaterialCardView(activity).apply { radius = 24f; forceHasOverlappingRendering(true) }
                activity.setContentView(root)
                root.addView(card, FrameLayout.LayoutParams(360, 240))
                CardRegionBlur.update(card, true, card.radius)
            }
            await { host(card)?.background != null }
            val originalHost = withContext(Dispatchers.Main) { host(card) }
            main {
                assertFalse(card.getHasOverlappingRendering())
                root.removeView(card)
                assertNull(originalHost?.background)
                assertTrue(card.getHasOverlappingRendering())
                root.addView(card)
            }
            // Reattachment alone must rebind: reused overlay cards need no style update.
            await { host(card)?.background != null }
            main {
                assertSame(originalHost, host(card))
                assertFalse(card.getHasOverlappingRendering())
                CardRegionBlur.update(card, false, card.radius)
                assertNull(host(card))
                assertNull(originalHost?.parent)
                assertNull(originalHost?.background)
                assertTrue(card.getHasOverlappingRendering())
                card.forceHasOverlappingRendering(false)
                CardRegionBlur.update(card, true, card.radius)
                CardRegionBlur.update(card, false, card.radius)
                assertFalse(card.getHasOverlappingRendering())
            }
        } finally { main { activity.finish() } }
    }

    @Test fun delayedEnableCannotReattachAfterDisableOrCreateDuplicateHosts() = runBlocking {
        val activity = startActivity("settings.SettingsActivity")
        try {
            lateinit var card: MaterialCardView
            var nextLayoutState: Boolean? = false
            main {
                card = object : MaterialCardView(activity) {
                    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                        super.onLayout(changed, left, top, right, bottom)
                        val enabledAtEndOfLayout = nextLayoutState ?: return
                        nextLayoutState = null
                        CardRegionBlur.update(this, true, 24f)
                        CardRegionBlur.update(this, false, 24f)
                        if (enabledAtEndOfLayout) CardRegionBlur.update(this, true, 24f)
                    }
                }
                activity.setContentView(card)
            }
            instrumentation.waitForIdleSync()
            main { assertNull(host(card)); assertEquals(0, card.childCount); nextLayoutState = true; card.requestLayout() }
            await { host(card)?.background != null }
            main { assertEquals(1, card.childCount); nextLayoutState = false; card.requestLayout() }
            instrumentation.waitForIdleSync()
            main { assertNull(host(card)); assertEquals(0, card.childCount) }
        } finally { main { activity.finish() } }
    }

    @Test fun reflectionFailureCleansAttachedAndDetachedCards() = runBlocking {
        val activity = startActivity("settings.SettingsActivity")
        val type = CardRegionBlur.javaClass
        val blocked = type.getDeclaredField("drawableBlocked").apply { isAccessible = true }
        val create = type.getDeclaredField("createDrawableMethod").apply { isAccessible = true }
        val originalBlocked = blocked.getBoolean(null)
        val originalCreate = create.get(null)
        try {
            lateinit var root: FrameLayout
            lateinit var attached: MaterialCardView
            lateinit var detached: MaterialCardView
            main {
                root = FrameLayout(activity)
                activity.setContentView(root)
                attached = MaterialCardView(activity)
                detached = MaterialCardView(activity)
                for (card in listOf(attached, detached)) {
                    card.forceHasOverlappingRendering(true)
                    root.addView(card, FrameLayout.LayoutParams(360, 240))
                    CardRegionBlur.update(card, true, 24f)
                }
            }
            await { host(attached)?.background != null && host(detached)?.background != null }
            main {
                root.removeView(detached)
                // Force the same failure path as an incompatible framework reflection method.
                create.set(null, String::class.java.getMethod("length"))
                CardRegionBlur.update(attached, false, 24f)
                CardRegionBlur.update(attached, true, 24f)
            }
            await { host(attached) == null && host(detached) == null }
            main {
                assertTrue(attached.getHasOverlappingRendering())
                assertTrue(detached.getHasOverlappingRendering())
                assertTrue(blocked.getBoolean(null))
            }
        } finally {
            main { activity.finish(); create.set(null, originalCreate); blocked.setBoolean(null, originalBlocked) }
        }
    }

    @Test fun blurredSearchKeyboardAndInspireActivitiesAreCollectible() = runBlocking {
        val cardKeys = listOf("key_search_card_background_blur", "key_keyboard_card_background_blur", "key_screen_content_card_background_blur")
        val backgroundKeys = listOf("key_search_background_blur", "key_keyboard_background_blur", "key_screen_content_background_blur")
        val destroyed = mutableListOf<WeakReference<Activity>>()
        val trackedNames = setOf("QuickSearchActivity", "QuickStartActivity", "InspireContentActivity")
        val application = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(a: Activity) {
                if (a.javaClass.simpleName in trackedNames) destroyed.add(WeakReference(a))
            }
            override fun onActivityCreated(a: Activity, state: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            withSettings(cardKeys + backgroundKeys) {
                context.settingsStore.edit { prefs ->
                    cardKeys.forEach { prefs[booleanPreferencesKey(it)] = true }
                    backgroundKeys.forEach { prefs[booleanPreferencesKey(it)] = false }
                }
                repeat(5) {
                    cycleActivity("search.QuickSearchActivity")
                    cycleActivity("quick.QuickStartActivity")
                    main { InspireDataHolder.accessibilityContent = "内存回归测试 Memory regression text" }
                    cycleActivity("inspire.InspireContentActivity")
                }
                collect()
                main {
                    assertEquals(15, destroyed.size)
                    assertEquals("Destroyed activities must not be rooted by card blur", 0, destroyed.count { it.get() != null })
                }
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            main { InspireDataHolder.clear() }
        }
    }

    private suspend fun cycleActivity(name: String) {
        val activity = startActivity(name)
        delay(700)
        main { (activity as ComponentActivity).onBackPressedDispatcher.onBackPressed() }
        await { activity.isDestroyed }
    }

    @Test fun dragPanelsRebindAfterHidingAndRecreation() = runBlocking {
        val keys = listOf(DropPreferences.KEY_EXTERNAL_DROP_CARD_BACKGROUND_BLUR, DropPreferences.KEY_EXTERNAL_DROP_BACKGROUND_BLUR)
        withSettings(keys) {
            context.settingsStore.edit {
                it[booleanPreferencesKey(keys[0])] = true
                it[booleanPreferencesKey(keys[1])] = false
            }
            repeat(5) { dragPanelCycle() }
        }
    }

    private suspend fun dragPanelCycle() {
        val activity = startActivity("settings.SettingsActivity")
        val appearance = DropOverlayAppearance(activity, (activity as ComponentActivity).lifecycleScope)
        lateinit var targets: View
        try {
            appearance.configure()
            lateinit var root: FrameLayout
            main {
                root = FrameLayout(activity)
                activity.setContentView(root)
                targets = activity.layoutInflater.inflate(R.layout.external_drop_floating, root, false)
                root.addView(targets)
                appearance.applyGlassStyle(targets, true)
            }
            val card = withContext(Dispatchers.Main) { targets.findViewById<MaterialCardView>(R.id.externalDropPanel) }
            await { host(card)?.background != null }
            main { root.removeView(targets); appearance.hide(); assertNull(host(card)?.background); root.addView(targets) }
            await { host(card)?.background != null }
            main { root.removeView(targets); appearance.hide() }
        } finally {
            main { activity.finish() }
            await { activity.isDestroyed }
        }
    }
}
