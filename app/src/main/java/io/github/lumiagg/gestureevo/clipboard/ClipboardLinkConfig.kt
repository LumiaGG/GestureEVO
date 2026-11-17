// 来源：wangzhen9331 的 GestureEVO 自用改版（GPL-3.0-only），检测后端按本仓库架构适配。

package io.github.lumiagg.gestureevo.clipboard

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * User-facing settings for the copied-link capsule.
 *
 * Kept in one place because three sides read it: the settings page writes it, the watcher decides
 * whether to watch at all, and the capsule needs the auto-dismiss window and its screen anchor.
 */
data class ClipboardLinkConfig(
    val enabled: Boolean = true,
    val autoDismissMs: Long = DEFAULT_AUTO_DISMISS_MS,
    val maxTargets: Int = DEFAULT_MAX_TARGETS,
    val siteAppsOnly: Boolean = false,
    val clearAfterOpen: Boolean = false,
    val backend: String = BACKEND_AUTO,
    val edge: String = EDGE_RIGHT,
    val yRatio: Float = DEFAULT_Y_RATIO,
) {

    companion object {
        const val KEY_ENABLED = "key_clipboard_link_enabled"
        const val KEY_AUTO_DISMISS_MS = "key_clipboard_link_auto_dismiss_ms"
        const val KEY_MAX_TARGETS = "key_clipboard_link_max_targets"
        const val KEY_SITE_APPS_ONLY = "key_clipboard_link_site_apps_only"
        const val KEY_CAPSULE_EDGE = "key_clipboard_link_capsule_edge"
        const val KEY_CAPSULE_Y_RATIO = "key_clipboard_link_capsule_y_ratio"
        const val KEY_CLEAR_AFTER_OPEN = "key_clipboard_link_clear_after_open"
        const val KEY_BACKEND = "key_clipboard_link_backend"

        const val BACKEND_AUTO = "auto"
        const val BACKEND_SHIZUKU = "shizuku"
        const val BACKEND_ROOT = "root"

        const val EDGE_LEFT = "left"
        const val EDGE_RIGHT = "right"

        const val DEFAULT_AUTO_DISMISS_MS = 5_000L
        const val DEFAULT_MAX_TARGETS = 5
        const val DEFAULT_Y_RATIO = 0.5f

        /** Offered in the settings page; 0 means the capsule stays until it is dismissed. */
        val AUTO_DISMISS_CHOICES = longArrayOf(3_000L, 5_000L, 8_000L, 0L)
        val MAX_TARGET_CHOICES = intArrayOf(3, 5, 7)

        fun from(preferences: Preferences) = ClipboardLinkConfig(
            enabled = preferences.readKey(KEY_ENABLED, true),
            autoDismissMs = preferences.readKey(
                KEY_AUTO_DISMISS_MS, DEFAULT_AUTO_DISMISS_MS.toInt()
            ).toLong().coerceAtLeast(0L),
            maxTargets = preferences.readKey(KEY_MAX_TARGETS, DEFAULT_MAX_TARGETS)
                .coerceIn(1, MAX_TARGET_CHOICES.last()),
            siteAppsOnly = preferences.readKey(KEY_SITE_APPS_ONLY, false),
            clearAfterOpen = preferences.readKey(KEY_CLEAR_AFTER_OPEN, false),
            backend = preferences.readKey(KEY_BACKEND, BACKEND_AUTO)
                .takeIf { it == BACKEND_SHIZUKU || it == BACKEND_ROOT } ?: BACKEND_AUTO,
            edge = preferences.readKey(KEY_CAPSULE_EDGE, EDGE_RIGHT)
                .takeIf { it == EDGE_LEFT || it == EDGE_RIGHT } ?: EDGE_RIGHT,
            yRatio = preferences.readKey(KEY_CAPSULE_Y_RATIO, DEFAULT_Y_RATIO)
                .coerceIn(0f, 1f),
        )

        /** Live view of the settings, so a change applies to the next copy, not the next reboot. */
        fun observe(context: Context): Flow<ClipboardLinkConfig> =
            context.settingsStore.data.map { from(it) }.distinctUntilChanged()
    }
}
