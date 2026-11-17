// 来源：wangzhen9331 的 GestureEVO 自用改版（GPL-3.0-only），检测后端按本仓库架构适配。

package io.github.lumiagg.gestureevo.clipboard

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.lumiagg.gestureevo.BuildConfig
import java.io.File

/**
 * One-frame activity that borrows the foreground to read the clipboard.
 *
 * Android decides who may read the clipboard by caller identity, not by permission: only the
 * foreground app, the default IME and — on stock builds — shell qualify. Root is not on that list,
 * and this ROM goes further by refusing shell too (verified on-device: an app_process running as
 * uid 2000 can write the clip but reads back null). So the only way left is to actually *be* the
 * foreground app for the instant the read happens.
 *
 * The activity draws nothing, has its own task affinity (so closing it returns the user to whatever
 * they were doing instead of to GestureEVO's main screen), and finishes as soon as it has an
 * answer. The visible side effect is a brief flicker of the app the user was in.
 */
class ClipboardReadActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    /** Identifies which request this instance serves; see [ClipboardReadBridge]. */
    private var token = 0
    private var attempt = 0
    private var deadline = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        // Clear mode: the same borrowed foreground also lets the clip be emptied after a link
        // was opened, so the app it was opened in cannot read it again and pop another capsule.
        if (intent?.getBooleanExtra(EXTRA_CLEAR, false) == true) {
            runCatching {
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.clearPrimaryClip()
            }
            diagnose("cleared the clip")
            finish()
            return
        }
        token = intent?.getIntExtra(EXTRA_TOKEN, 0) ?: 0
        diagnose("created token=$token")
    }

    override fun onResume() {
        super.onResume()
        attempt = 0
        deadline = SystemClock.uptimeMillis() + READ_BUDGET_MS
        diagnose("resumed")
        // The read is only allowed once this activity owns the foreground, and the appop that
        // governs it flips a frame or two after onResume — sometimes noticeably later, which is
        // why the first read is delayed and the following ones retry instead of giving up.
        handler.postDelayed({ read() }, FOCUS_DELAY_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // A no-op unless this instance still owns the slot: either it published already, or a
        // newer request has taken over and must not be answered by a stale activity.
        ClipboardReadBridge.publish(token, null)
        super.onDestroy()
    }

    private fun read() {
        val text = readPrimaryText()
        diagnose("read attempt=$attempt len=" + (text?.length ?: -1))
        if (!text.isNullOrBlank()) {
            publish(text)
            return
        }
        if (SystemClock.uptimeMillis() < deadline) {
            attempt++
            handler.postDelayed({ read() }, RETRY_MS)
            return
        }
        diagnose("gave up: " + describeClip())
        publish(null)
    }

    private fun publish(text: String?) {
        ClipboardReadBridge.publish(token, text)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun diagnose(message: String) {
        Log.d(TAG, message)
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = File(getExternalFilesDir(null), "clipboard-debug.txt")
            if (file.length() > 64 * 1024) file.delete()
            file.appendText(System.currentTimeMillis().toString() + " [activity] " + message + "\n")
        }
    }

    private fun readPrimaryText(): String? = runCatching {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = manager?.primaryClip ?: return@runCatching null
        if (clip.itemCount == 0) return@runCatching null
        clip.getItemAt(0).coerceToText(this)?.toString()
    }.onFailure {
        Log.w(TAG, "read failed: ${it.javaClass.simpleName}")
    }.getOrNull()

    /** Distinguishes "the read was refused" from "the clip is empty" when it does fail. */
    private fun describeClip(): String = runCatching {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = manager?.primaryClip
        when {
            clip == null -> "clip=null keyguard=" + isKeyguardLocked()
            else -> "clip items=" + clip.itemCount + " types=" + clip.description?.mimeTypeCount
        }
    }.getOrDefault("clip=? ")

    private fun isKeyguardLocked(): Boolean = runCatching {
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "ClipboardReadActivity"

        /** Request id, so a late activity cannot answer a newer request. */
        const val EXTRA_TOKEN = "io.github.lumiagg.gestureevo.extra.CLIP_READ_TOKEN"

        /** Runs the activity in clear mode instead of read mode. */
        const val EXTRA_CLEAR = "io.github.lumiagg.gestureevo.extra.CLIP_CLEAR"

        /** Enough for the foreground hand-off to settle, short enough to stay out of the way. */
        private const val FOCUS_DELAY_MS = 300L

        /** How long a single read session may spend retrying before it reports failure. */
        private const val READ_BUDGET_MS = 1_400L

        private const val RETRY_MS = 110L
    }
}

/**
 * Hands the text read by [ClipboardReadActivity] back to the watcher living in the same process.
 *
 * A single slot is enough — the watcher only ever waits for the most recent copy — but the slot
 * must be *owned*: several change events can arrive for one copy, and an activity launched for an
 * older event finishes late and would otherwise answer a newer request with stale content. Every
 * request therefore carries a token, and an answer is only delivered while its token is still the
 * current one.
 */
internal object ClipboardReadBridge {

    private val lock = Any()
    private var pending: ((String?) -> Unit)? = null
    private var currentToken = 0

    fun nextToken(): Int = synchronized(lock) { ++currentToken }

    fun expect(token: Int, callback: (String?) -> Unit) {
        synchronized(lock) {
            if (token == currentToken) pending = callback
        }
    }

    fun cancel(token: Int) {
        synchronized(lock) {
            if (token == currentToken) pending = null
        }
    }

    fun publish(token: Int, text: String?) {
        val callback = synchronized(lock) {
            if (token != currentToken) return@synchronized null
            val current = pending
            pending = null
            current
        }
        callback?.invoke(text)
    }
}
