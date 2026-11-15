package io.github.lumiagg.gestureevo.link

import android.app.ActivityOptions
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.UrlDetector
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Turns a routed link into an actual launch.
 *
 * Split from [LinkDispatcher] so matching stays testable and the launch path can be swapped
 * without touching rule logic. Launches go through a plain ACTION_VIEW intent pinned to the
 * rule's target package; when no app can handle that, the link falls back to the system
 * chooser so a stale rule never makes a link dead.
 */
object LinkLauncher {

    /**
     * Extracts the first link from [text], routes it and launches the result.
     *
     * Extraction honours the user's extract patterns first, so schemes like magnet links work
     * even though the general detector does not know them. With [bypassRules] the first link
     * opens through the system default handler instead, which is the long-press escape hatch
     * when a rule misfires.
     *
     * @return true when a link was dispatched (including the browser fallback).
     */
    suspend fun openFromText(
        context: Context,
        text: String,
        sourcePackage: String = "",
        bypassRules: Boolean = false
    ): Boolean {
        val raw = LinkExtractor.extractWithStoredPatterns(context, text)
            ?: UrlDetector.findUrls(text).firstOrNull()
        if (raw.isNullOrBlank()) {
            showToast(context, context.getString(R.string.inspire_link_not_found))
            return false
        }
        if (bypassRules) {
            return launch(context, raw, "", false)
        }
        return openUrl(context, raw, sourcePackage, sourceText = text)
    }

    suspend fun openUrl(
        context: Context,
        rawUrl: String,
        sourcePackage: String = "",
        sourceText: String? = null
    ): Boolean {
        val result = LinkDispatcher.route(context, rawUrl, sourcePackage, sourceText)
        return openRouted(context, rawUrl, result)
    }

    /** Handles a routing result: launch the rule target or fall back to the system handler. */
    private suspend fun openRouted(
        context: Context,
        rawUrl: String,
        result: LinkDispatchResult
    ): Boolean {
        AppLogger.event(
            LogModule.ACTION,
            "link_route_result",
            fields = mapOf(
                "reason" to result::class.simpleName,
                "package" to when (result) {
                    is LinkDispatchResult.Routed -> result.rule.targetPackage
                    else -> ""
                },
                "count" to LinkRuleRepository.loadRules(context).size
            )
        )

        val dispatched = when (result) {
            is LinkDispatchResult.Routed ->
                launch(
                    context,
                    result.url,
                    result.rule.targetPackage,
                    result.rule.excludeFromRecents,
                    result.rule.component
                )

            is LinkDispatchResult.Exempted -> launch(context, result.url, "", false)
            is LinkDispatchResult.SystemDefault -> launch(context, result.url, "", false)
            LinkDispatchResult.NoRule ->
                // No rule owns this link, so behave like the built-in browser action.
                launch(context, rawUrl, "", false)

            LinkDispatchResult.Failed -> {
                showToast(context, context.getString(R.string.inspire_link_no_app))
                false
            }
        }

        recordHistory(context, rawUrl, result, dispatched)
        if (dispatched) recordRuleHit(context, result)
        return dispatched
    }

    /**
     * Bumps the matching rule's counters so the settings screen can show which rules actually
     * fire. Failures are ignored: a statistics write must never affect the launch result.
     */
    private suspend fun recordRuleHit(context: Context, result: LinkDispatchResult) {
        val rule = (result as? LinkDispatchResult.Routed)?.rule ?: return
        runCatching {
            val current = LinkRuleRepository.loadRules(context).toMutableList()
            val index = current.indexOfFirst { it.id == rule.id }
            if (index >= 0) {
                current[index] = current[index].copy(
                    hitCount = current[index].hitCount + 1,
                    lastHitAt = System.currentTimeMillis()
                )
                LinkRuleRepository.saveRules(context, current)
            }
        }
    }

    /**
     * History is best effort: a failure to record must never make a successful launch look
     * like it failed, so any error here is swallowed.
     */
    private suspend fun recordHistory(
        context: Context,
        rawUrl: String,
        result: LinkDispatchResult,
        dispatched: Boolean
    ) {
        val routed = result as? LinkDispatchResult.Routed
        runCatching {
            LinkHistoryRepository.record(
                context,
                LinkHistoryEntry(
                    id = UUID.randomUUID().toString(),
                    url = when (result) {
                        is LinkDispatchResult.Routed -> result.url
                        is LinkDispatchResult.Exempted -> result.url
                        is LinkDispatchResult.SystemDefault -> result.url
                        else -> rawUrl
                    },
                    targetPackage = routed?.rule?.targetPackage.orEmpty(),
                    ruleName = routed?.rule?.name.orEmpty(),
                    timestamp = System.currentTimeMillis(),
                    success = dispatched
                )
            )
        }
    }

    private suspend fun launch(
        context: Context,
        url: String,
        targetPackage: String,
        excludeFromRecents: Boolean,
        component: String? = null
    ): Boolean = withContext(Dispatchers.Main) {
        val intent = buildLaunchIntent(context, url, targetPackage, excludeFromRecents, component)
            ?: run {
                showToast(context, context.getString(R.string.inspire_link_no_app))
                return@withContext false
            }

        // Try the launch directly instead of pre-checking resolveActivity: on Android 11+
        // the pre-check is subject to package visibility and can report null even when the
        // target would accept the intent. Let the framework decide and catch the miss.
        val options = buildRadarActivityOptions(context)
        val launched = runCatching {
            if (options != null) context.startActivity(intent, options.toBundle())
            else context.startActivity(intent)
        }
        if (launched.isSuccess) return@withContext true
        val reason = launched.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
        AppLogger.event(
            LogModule.ACTION,
            "link_target_unavailable",
            LogLevel.WARN,
            fields = mapOf("package" to targetPackage, "reason" to reason)
        )

        // A component-pinned launch that failed may still work through the plain link.
        if (component != null) {
            val unpinned = buildLaunchIntent(context, url, targetPackage, excludeFromRecents, null)
            if (unpinned != null && runCatching {
                    if (options != null) context.startActivity(unpinned, options.toBundle())
                    else context.startActivity(unpinned)
                }.isSuccess
            ) {
                return@withContext true
            }
        }

        // Fall back to any app that can handle the link, including the system chooser.
        val fallback = Intent(Intent.ACTION_VIEW).apply {
            data = intent.data
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (excludeFromRecents) addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        if (runCatching { context.startActivity(fallback) }.isSuccess) {
            return@withContext true
        }

        showToast(context, context.getString(R.string.inspire_link_no_app))
        false
    }

    /**
     * 分发规则的打开方式：小窗 or 全屏（设置项，默认全屏）。
     *
     * 小窗复用 GEVO 既有的 FreeForm 通道（ActionProxy.buildFreeformActivityOptions，
     * 含 ColorOS/Huawei 的反射适配与小窗位置尺寸），全屏返回 null 走普通启动。
     */
    private suspend fun buildRadarActivityOptions(context: Context): ActivityOptions? {
        val smallWindow = runCatching {
            context.settingsStore.readKey(KEY_LINK_OPEN_IN_SMALL_WINDOW, false)
        }.getOrDefault(false)
        if (!smallWindow) return null
        return runCatching { ActionProxy.buildFreeformActivityOptions(context) }.getOrNull()
    }

    /** 分发规则触发时的打开方式：true=小窗，false=全屏（默认）。 */
    const val KEY_LINK_OPEN_IN_SMALL_WINDOW = "key_link_open_in_small_window"

    /**
     * Builds the launch intent for a routed link.
     *
     * `intent://` templates are parsed by the framework so component and extras inside the URI
     * survive. A component target pins the intent to that activity.
     */
    private fun buildLaunchIntent(
        context: Context,
        url: String,
        targetPackage: String,
        excludeFromRecents: Boolean,
        component: String?
    ): Intent? {
        val normalized = UrlDetector.normalizeForOpening(url) ?: url
        val intent = if (normalized.startsWith("intent://", ignoreCase = true)) {
            runCatching {
                Intent.parseUri(normalized, Intent.URI_INTENT_SCHEME)
            }.getOrNull() ?: return null
        } else {
            val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
            Intent(Intent.ACTION_VIEW, uri)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (excludeFromRecents) intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        if (targetPackage.isNotEmpty()) {
            intent.setPackage(targetPackage)
            component?.let { activity ->
                intent.component = ComponentName.unflattenFromString(
                    if ('/' in activity) activity else "$targetPackage/$activity"
                )
            }
        }
        return intent
    }

    private suspend fun showToast(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
