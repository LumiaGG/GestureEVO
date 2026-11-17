// 来源：wangzhen9331 的 GestureEVO 自用改版（GPL-3.0-only），检测后端按本仓库架构适配。

package io.github.lumiagg.gestureevo.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import io.github.lumiagg.gestureevo.BuildConfig
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.link.LinkLauncher
import io.github.lumiagg.gestureevo.link.LinkRuleRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Watches the clipboard and shows a capsule per copied link.
 *
 * Registering the listener needs no permission, but reading the content does, and on this ROM the
 * read is only granted to the foreground app: neither root nor shell qualify (both were measured
 * on-device and both come back empty). So the content is fetched by briefly becoming the foreground
 * app — see [ClipboardReadActivity]. The background half of the change detection is
 * [ClipboardDaemonHost], which needs root.
 */
class ClipboardLinkWatcher(private val service: GestureService) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clipboard =
        service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val power =
        service.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var capsule: ClipboardCapsuleOverlay? = null
    private var daemon: ClipboardDaemonHost? = null
    private var settleJob: Job? = null
    private var configJob: Job? = null

    /** Mirrors the settings page; the daemon is only kept alive while the feature is on. */
    @Volatile
    private var config = ClipboardLinkConfig()

    /** One read session at a time: two overlapping sessions corrupt each other's answer. */
    private val readLock = Mutex()

    private var lastFingerprint: String? = null
    private var lastFingerprintAt = 0L

    private var started = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { scheduleRead() }

    fun start() {
        if (started) return
        started = true
        // Front-facing path: works while the app is visible and needs no root.
        val registered = runCatching { clipboard?.addPrimaryClipChangedListener(listener) }
            .onFailure { Log.w(TAG, "register listener failed: ${it.javaClass.simpleName}") }
            .isSuccess
        configJob = scope.launch(Dispatchers.Default) {
            ClipboardLinkConfig.observe(service).collect { next ->
                config = next
                applyEnabled(next.enabled)
            }
        }
        diagnose("start listener=$registered")
    }

    private fun applyEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.Main.immediate) {
            if (enabled) {
                if (daemon == null) {
                    daemon = ClipboardDaemonHost(service, { config.backend }) { scheduleRead() }.also { it.start() }
                    diagnose("daemon started")
                }
            } else {
                if (daemon != null) {
                    daemon?.stop()
                    daemon = null
                    diagnose("daemon stopped: feature off")
                }
                settleJob?.cancel()
                settleJob = null
                capsule?.destroy()
                capsule = null
            }
        }
    }

    fun stop() {
        started = false
        runCatching { clipboard?.removePrimaryClipChangedListener(listener) }
        daemon?.stop()
        daemon = null
        configJob?.cancel()
        configJob = null
        settleJob?.cancel()
        settleJob = null
        capsule?.destroy()
        capsule = null
        scope.cancel()
    }

    /**
     * Some apps set the clipboard several times for one copy (plain text, then html), so the
     * read is deferred a little and only the last change is honoured.
     */
    private fun scheduleRead() {
        diagnose("clipboard changed")
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_DELAY_MS)
            onClipboardChanged()
        }
    }

    private suspend fun onClipboardChanged() {
        if (!config.enabled) {
            diagnose("skip: feature off")
            return
        }
        // Nothing to offer while the screen is off: the read would be refused and the capsule
        // would be invisible anyway.
        if (power?.isInteractive == false) {
            diagnose("skip: screen off")
            return
        }

        val text = readClipboardText()
        diagnose("read len=" + (text?.length ?: -1))
        if (text.isNullOrBlank()) return

        // A paste can hold several links (夸克 / 百度 / 迅雷 各一条). They are kept apart, because
        // each belongs to a different app; one capsule is offered per link.
        val links = runCatching {
            ClipboardLinkRules.collect(
                text,
                LinkRuleRepository.loadExtractPatterns(service),
                LinkRuleRepository.loadRules(service),
                service.packageName,
                maxLinks = config.maxTargets,
            )
        }.getOrElse {
            diagnose("extract failed: " + it.javaClass.simpleName)
            emptyList()
        }
        diagnose("links=" + links.size + " " + links.joinToString(",") { it.url })

        if (links.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        val fingerprint = links.joinToString("|") { it.url }
        if (fingerprint == lastFingerprint && now - lastFingerprintAt < DEDUP_WINDOW_MS) {
            diagnose("deduped")
            return
        }
        lastFingerprint = fingerprint
        lastFingerprintAt = now

        showCapsules(links, config)
    }

    /**
     * Reads the current clip, preferring the cheap path.
     *
     * When this app happens to be the foreground app the read is allowed directly, so no activity
     * is needed; backgrounded (the normal case) it returns null and the foreground is borrowed
     * instead. Sessions are serialised because the answer comes back through a single slot.
     */
    private suspend fun readClipboardText(): String? {
        val direct = readPrimaryText()
        if (!direct.isNullOrBlank()) {
            diagnose("direct len=" + direct.length)
            return direct
        }
        return readLock.withLock { readViaActivity() }
    }

    /**
     * Fetches the clip by launching [ClipboardReadActivity] and waiting for its answer.
     *
     * The launch is fire-and-forget: the activity reports through [ClipboardReadBridge] with the
     * token of the request it was started for, so an instance left over from an earlier copy
     * cannot answer this one. A null result means either an empty clip or a launch the system
     * refused, so callers must not treat it as "nothing was copied" without checking the log.
     */
    private suspend fun readViaActivity(): String? = withContext(Dispatchers.Main.immediate) {
        val token = ClipboardReadBridge.nextToken()
        val deferred = CompletableDeferred<String?>()
        ClipboardReadBridge.expect(token) { if (!deferred.isCompleted) deferred.complete(it) }
        try {
            val intent = Intent(service, ClipboardReadActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(ClipboardReadActivity.EXTRA_TOKEN, token)
            }
            val launched = runCatching { service.startActivity(intent) }
                .onFailure { Log.w(TAG, "launch read activity failed: ${it.javaClass.simpleName}") }
                .isSuccess
            diagnose("launch activity=$launched token=$token")
            if (!launched) return@withContext null
            val result = withTimeoutOrNull(READ_TIMEOUT_MS) { deferred.await() }
            diagnose("activity result=" + (result?.length ?: -1))
            result
        } finally {
            ClipboardReadBridge.cancel(token)
        }
    }

    private fun readPrimaryText(): String? = runCatching {
        val clip = clipboard?.primaryClip ?: return@runCatching null
        if (clip.itemCount == 0) return@runCatching null
        clip.getItemAt(0).coerceToText(service)?.toString()
    }.getOrNull()

    /**
     * One capsule per link, labelled with the app its rules point at.
     *
     * Rules come from 链接分发 → 分发规则 and answer the case App Links cannot: apps that only
     * publish a private scheme are invisible to the package manager for an https link. Links no
     * app claims still get a capsule labelled 手动选择, which opens the system chooser.
     */
    private fun showCapsules(
        links: List<ClipboardLinkCandidate>,
        config: ClipboardLinkConfig
    ) {
        val items = links.mapNotNull { candidate ->
            resolveTarget(candidate.url, candidate.rules, config)?.let { target ->
                ClipboardCapsuleOverlay.CapsuleItemData(
                    icon = target.icon,
                    label = target.label,
                    onTap = { reverse -> openLink(candidate.url, target, reverse) },
                )
            }
        }
        diagnose("capsule items=" + items.size + " of links=" + links.size)
        if (items.isEmpty()) return
        val target = capsule ?: ClipboardCapsuleOverlay(service).also { capsule = it }
        target.showArray(items, config)
    }

    /** The app a link should open in, or null when nothing can be shown for it. */
    private data class Target(val label: String, val icon: Drawable, val packageName: String, val uri: String?)

    private fun resolveTarget(
        url: String,
        rules: List<ClipboardLinkRule>,
        config: ClipboardLinkConfig
    ): Target? {
        val pm = service.packageManager

        // 1. Rule hits: explicit target plus the templated scheme the app actually wants.
        rules.firstOrNull()?.let { rule ->
            val info = runCatching {
                pm.getApplicationInfo(rule.packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }.getOrNull()
            if (info != null) {
                return Target(
                    label = pm.getApplicationLabel(info).toString(),
                    icon = pm.getApplicationIcon(info),
                    packageName = rule.packageName,
                    uri = rule.uri,
                )
            }
        }

        // 2. Apps that claim this URL; browsers last unless nothing else is left.
        val handlers = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                PackageManager.MATCH_DEFAULT_ONLY
            )
        }.getOrDefault(emptyList())

        var exact: Target? = null
        var generic: Target? = null
        for (info in handlers) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == service.packageName) continue
            val target = runCatching {
                Target(info.loadLabel(pm).toString(), info.loadIcon(pm), pkg, null)
            }.getOrNull() ?: continue
            if (isGenericBrowser(pkg)) {
                if (generic == null) generic = target
            } else {
                exact = target
                break
            }
        }
        val picked = if (config.siteAppsOnly && exact != null) exact else exact ?: generic
        if (picked != null) return picked

        // 3. Nothing claims it: keep the capsule so the chooser stays one tap away.
        return Target(
            label = service.getString(R.string.clipboard_link_manual_choice),
            icon = service.getDrawable(R.drawable.ic_baseline_insert_link_24)
                ?: ColorDrawable(Color.TRANSPARENT),
            packageName = "",
            uri = null,
        )
    }

    /**
     * A browser answers for any https host; an app that owns one domain does not. Probing an
     * unrelated host separates the two without hard-coding package names.
     */
    private fun isGenericBrowser(pkg: String): Boolean = runCatching {
        service.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).setPackage(pkg),
            0
        ).isNotEmpty()
    }.getOrDefault(false)

    /** Tap opens normally; the capsule's downward swipe asks for the ROM's small window. */
    private fun openLink(url: String, target: Target, reverse: Boolean) {
        val uri = target.uri?.takeIf { it.isNotBlank() } ?: url
        scope.launch {
            if (reverse && target.packageName.isNotBlank()) {
                val opened = runCatching {
                    LinkLauncher.openInSmallWindow(service, uri, target.packageName)
                }.getOrDefault(false)
                if (opened) {
                    clearClipboardIfEnabled()
                    return@launch
                }
            }
            if (openTargeted(uri, target.packageName)) {
                clearClipboardIfEnabled()
                return@launch
            }
            val opened = openPlain(uri) || (uri != url && openPlain(url))
            if (opened) clearClipboardIfEnabled()
        }
    }

    /**
     * Empties the clip once a link was opened, so the app it opened in cannot read it back and
     * ask again. Clearing needs the foreground like reading does, so it rides the same activity.
     */
    private fun clearClipboardIfEnabled() {
        if (!config.clearAfterOpen) return
        runCatching {
            service.startActivity(Intent(service, ClipboardReadActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(ClipboardReadActivity.EXTRA_CLEAR, true)
            })
        }
    }

    private fun openTargeted(uri: String, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { service.startActivity(intent) }.isSuccess
    }

    private fun openPlain(uri: String): Boolean = runCatching {
        service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }.isSuccess

    /**
     * Temporary: this ROM ships with the app log buffers turned off, so the only way to see what
     * the watcher did is to write it somewhere readable. Debug builds only.
     */
    private fun diagnose(message: String) {
        Log.d(TAG, message)
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = File(service.getExternalFilesDir(null), "clipboard-debug.txt")
            if (file.length() > 64 * 1024) file.delete()
            file.appendText(System.currentTimeMillis().toString() + " " + message + "\n")
        }
    }

    private companion object {
        const val TAG = "ClipboardLinkWatcher"
        const val SETTLE_DELAY_MS = 180L

        /**
         * Guards against one copy being reported twice. It must stay short: copying the same link
         * again a moment later is a real user action and has to show the capsule again.
         */
        const val DEDUP_WINDOW_MS = 1_200L

        /** Long enough for a cold launch plus the activity's own retry budget, not longer. */
        const val READ_TIMEOUT_MS = 2_600L
    }
}
