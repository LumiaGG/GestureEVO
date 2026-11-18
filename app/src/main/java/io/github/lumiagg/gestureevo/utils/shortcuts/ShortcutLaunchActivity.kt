package io.github.lumiagg.gestureevo.utils.shortcuts

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.utils.ActionProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Internal continuation after vivo's share protocol, without exposing the publisher's Intent. */
class ShortcutLaunchActivity : AppCompatActivity() {
    private var requestId: String? = null
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST)
        // A restored bridge must not repeat a command that may already have been submitted.
        if (savedInstanceState != null || requestId.isNullOrBlank()) {
            report(false)
            finish()
            return
        }
        val request = ShortcutLaunchRequests.consume(requestId.orEmpty())
        if (request == null) {
            report(false)
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                report(ActionProxy(this@ShortcutLaunchActivity, request.link)
                    .executePinnedShortcutContinuation(request.options))
            } catch (e: CancellationException) {
                report(false)
                throw e
            } catch (e: Exception) {
                AppLogger.event(LogModule.ACTION, "shortcut_bridge_failed", LogLevel.ERROR, error = e)
                report(false)
            } finally {
                finish()
            }
        }
    }

    private fun report(success: Boolean) {
        if (reported) return
        val id = requestId ?: return
        reported = true
        ActionProxy.ShareOpenResultManager.sendResult(ActionProxy.ShareOpenResult(id, success))
    }

    override fun onDestroy() {
        report(false)
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_REQUEST = "shortcut_launch_request"
    }
}

/** The exported share proxy cannot authorize a command by supplying arbitrary extras. */
internal object ShortcutLaunchRequests {
    data class Request(val link: String, val options: Bundle)
    private val requests = mutableMapOf<String, Request>()

    @Synchronized fun register(id: String, link: String, options: Bundle) {
        check(requests.size < 32) { "Too many pending shortcut launches" }
        check(id !in requests)
        requests[id] = Request(link, Bundle(options))
    }

    @Synchronized fun consume(id: String): Request? = requests.remove(id)
    @Synchronized fun forget(id: String) { requests.remove(id) }
}
