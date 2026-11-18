package io.github.lumiagg.gestureevo.link

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.GestureActionLink
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.utils.UrlDetector
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/**
 * Turns a routed link into an actual launch.
 *
 * Split from [LinkDispatcher] so matching stays testable and the launch path can be swapped
 * without touching rule logic. Every attempt uses ActionProxy's OTHER_LINK action, including
 * its ROM-specific small-window launchers. A stale target falls back to the system handler.
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
        bypassRules: Boolean = false,
        side: String? = null,
    ): Boolean {
        val raw = LinkExtractor.extractWithStoredPatterns(context, text)
        if (raw.isNullOrBlank()) {
            ToastHelper.showInUIThread(context, R.string.inspire_link_not_found)
            return false
        }
        if (bypassRules) {
            return launch(context, raw, "", false, side = side)
        }
        return openUrl(context, raw, sourcePackage, sourceText = text, side = side)
    }

    suspend fun openUrl(
        context: Context,
        rawUrl: String,
        sourcePackage: String = "",
        sourceText: String? = null,
        side: String? = null,
    ): Boolean {
        val result = LinkDispatcher.route(context, rawUrl, sourcePackage, sourceText)
        return openRouted(context, rawUrl, result, side)
    }

    /** Handles a routing result: launch the rule target or fall back to the system handler. */
    private suspend fun openRouted(
        context: Context,
        rawUrl: String,
        result: LinkDispatchResult,
        side: String?,
    ): Boolean {
        AppLogger.event(
            LogModule.ACTION,
            "link_route_result",
            fields = mapOf(
                "reason" to result::class.simpleName,
                "package" to when (result) {
                    is LinkDispatchResult.Routed -> result.rule.targetPackage
                    else -> ""
                }
            )
        )

        val dispatched = when (result) {
            is LinkDispatchResult.Routed ->
                launch(
                    context,
                    result.url,
                    result.rule.targetPackage,
                    result.rule.excludeFromRecents,
                    result.rule.component,
                    side,
                    result.fallbackUrl,
                    privilegedComponent = ruleComponent(result.rule),
                )

            is LinkDispatchResult.Exempted -> launch(context, result.url, "", false, side = side)
            is LinkDispatchResult.SystemDefault -> launch(context, result.url, "", false, side = side)
            LinkDispatchResult.NoRule ->
                // No rule owns this link, so behave like the built-in browser action.
                launch(context, rawUrl, "", false, side = side)

            LinkDispatchResult.Failed -> {
                ToastHelper.showInUIThread(context, R.string.inspire_link_no_app)
                false
            }
        }

        if (dispatched) recordRuleHit(context, result)
        return dispatched
    }

    /**
     * Bumps the matching rule's counters so the settings screen can show which rules actually
     * fire. Failures are ignored: a statistics write must never affect the launch result.
     */
    private suspend fun recordRuleHit(context: Context, result: LinkDispatchResult) {
        val rule = (result as? LinkDispatchResult.Routed)?.rule ?: return
        try {
            LinkRuleRepository.updateRules(context) { current ->
                current.map { if (it.id == rule.id) it.copy(hitCount = it.hitCount + 1,
                    lastHitAt = System.currentTimeMillis()) else it }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLogger.event(LogModule.ACTION, "link_statistics_failed", LogLevel.WARN, error = error)
        }
    }

    private suspend fun launch(
        context: Context,
        url: String,
        targetPackage: String,
        excludeFromRecents: Boolean,
        component: String? = null,
        side: String? = null,
        fallbackUrl: String = url,
        privilegedComponent: ComponentName? = null,
    ): Boolean = withContext(Dispatchers.Main) {
        val intent = buildLaunchIntent(url, targetPackage, excludeFromRecents, component)
        val attemptedPackage = intent?.component?.packageName ?: intent?.`package`
        val smallWindow = context.settingsStore.readKey(KEY_LINK_OPEN_IN_SMALL_WINDOW, false)
        val launchSide = side ?: TriggerSide.peek()
        val anchor = if (side == null) TriggerSide.peekLocation()?.let {
            TriggerSide.Point(it.x, it.screenWidth, it.y, it.screenHeight)
        } else null
        suspend fun open(
            target: Intent,
            alreadyAttempted: String? = null,
            privilegedTarget: ComponentName? = null,
        ): Boolean {
            val action = GestureActionLink(
                actionType = GestureActionType.OTHER_LINK,
                isFreeform = smallWindow,
            ).apply {
                // Serialize the whole Intent: component, extras and task flags must survive.
                addParams("uri", target.toUri(Intent.URI_INTENT_SCHEME))
                addParams("pkg", target.`package` ?: target.component?.packageName.orEmpty())
            }
            return ActionProxy(context, action.buildLink()).executeLink(
                anchor = anchor,
                side = launchSide,
                authorizedComponent = privilegedTarget,
                skipUnfreezeFor = alreadyAttempted,
            )
        }
        if (intent != null && open(intent, privilegedTarget = privilegedComponent)) return@withContext true
        AppLogger.event(
            LogModule.ACTION,
            "link_target_unavailable",
            LogLevel.WARN,
            fields = mapOf("package" to targetPackage)
        )

        if (intent?.component != null && intent.`package` != null) {
            val unpinned = Intent(intent).setComponent(null)
            if (open(unpinned, alreadyAttempted = attemptedPackage)) return@withContext true
        }

        val originalData = buildLaunchIntent(fallbackUrl, "", false, null)?.data
            ?.takeIf { !it.scheme.isNullOrBlank() && !it.scheme.equals("intent", ignoreCase = true) }
        val routedData = intent?.data?.takeIf { !it.scheme.isNullOrBlank() && !it.scheme.equals("intent", ignoreCase = true) }
        for (data in listOfNotNull(routedData, originalData).distinct()) {
            val fallback = Intent(Intent.ACTION_VIEW, data).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (excludeFromRecents) addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            if (!fallback.filterEquals(intent) && open(fallback)) return@withContext true
        }

        ToastHelper.showInUIThread(context, R.string.inspire_link_no_app)
        false
    }

    internal fun ruleComponent(rule: LinkDispatchRule): ComponentName? {
        if (rule.targetPackage.isBlank()) return null
        val template = if (rule.component == null) rule.template.orEmpty() else ""
        val intent = buildLaunchIntent(template, rule.targetPackage, false, rule.component)
        return intent?.component?.takeIf { it.packageName == rule.targetPackage }
    }

    /** 分发规则触发时的打开方式：true=小窗，false=全屏（默认）。 */
    const val KEY_LINK_OPEN_IN_SMALL_WINDOW = "key_link_open_in_small_window"

    /**
     * Builds the launch intent for a routed link.
     *
     * `intent://` templates are parsed by the framework so component and extras inside the URI
     * survive. A component target pins the intent to that activity.
     */
    internal fun buildLaunchIntent(
        url: String,
        targetPackage: String,
        excludeFromRecents: Boolean,
        component: String?
    ): Intent? {
        val normalized = UrlDetector.normalizeForOpening(url) ?: url
        val intent = if (normalized.startsWith("intent:", ignoreCase = true)) {
            runCatching {
                Intent.parseUri(normalized, Intent.URI_INTENT_SCHEME).apply {
                    // LinkGo uses intent:// with no data when the address is passed as an extra.
                    if (data?.toString() == "//") data = null
                }
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

}
