package io.github.lumiagg.gestureevo

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.inspire.ClipboardToInspireActivityUtils
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.ToastHelper
import kotlinx.coroutines.launch

class ShareOpenAppProxyActivity : AppCompatActivity() {
    private var requestId: String = ""
    private var pendingIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(targetIntent: Intent) {
        val reqId = targetIntent.getStringExtra("REQUEST_ID") ?: ""
        requestId = reqId

        if (reqId.isNotEmpty()) {
            openIntent(targetIntent)
            return
        }

        if (targetIntent.action == Intent.ACTION_SEND || targetIntent.action == Intent.ACTION_SEND_MULTIPLE) {
            // Direct share intent has content in extras, execute immediately
            executeHandleIntent(targetIntent)
            return
        }

        // Clipboard actions require window focus on Android 10+
        scheduleClipboardIntentHandling(targetIntent)
    }

    private fun scheduleClipboardIntentHandling(targetIntent: Intent) {
        pendingIntent = targetIntent
        if (hasWindowFocus()) {
            dispatchPendingIntent()
            return
        }
        window.decorView.post {
            if (hasWindowFocus()) {
                dispatchPendingIntent()
            } else {
                // Fallback in case onWindowFocusChanged is delayed or skipped
                window.decorView.postDelayed({
                    dispatchPendingIntent()
                }, 300L)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            dispatchPendingIntent()
        }
    }

    private fun dispatchPendingIntent() {
        val target = pendingIntent ?: return
        pendingIntent = null
        executeHandleIntent(target)
    }

    private fun executeHandleIntent(targetIntent: Intent) {
        lifecycleScope.launch {
            try {
                ClipboardToInspireActivityUtils(activity = this@ShareOpenAppProxyActivity)
                    .handleIntent(targetIntent)
            } catch (e: Exception) {
                Log.e("ShareOpenAppProxy", "Failed to handle intent", e)
                finish()
            }
        }
    }

    private fun openIntent(sourceIntent: Intent): Boolean {
        val pkg = sourceIntent.getStringExtra(Intent.EXTRA_TEXT)
        if (!pkg.isNullOrEmpty()) {
            val targetIntent = sourceIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (targetIntent != null) {
                val launchIntent = Intent(targetIntent)
                try {
                    startActivity(launchIntent)
                    finishWithResult(success = true)
                    return true
                } catch (_: ActivityNotFoundException) {
                    ToastHelper.showInUIThread(this, getString(R.string.share_open_app_failed))
                } catch (_: SecurityException) {
                }
            } else {
                // Attempt to resolve launch Intent for the target package
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        startActivity(launchIntent)
                        finishWithResult(success = true)
                        return true
                    } catch (_: ActivityNotFoundException) {
                        ToastHelper.showInUIThread(this, getString(R.string.share_open_app_failed))
                    } catch (_: SecurityException) {
                    }
                } else {
                    // Target application is not installed or has no launcher activity
                    ToastHelper.showInUIThread(this, getString(R.string.share_open_app_failed))
                }
            }
        }

        finishWithResult(success = false)
        return false
    }

    private fun finishWithResult(success: Boolean, data: Intent? = null) {
        lifecycleScope.launch {
            ActionProxy.ShareOpenResultManager.sendResult(
                ActionProxy.ShareOpenResult(
                    requestId = requestId,
                    success = success,
                    data = data
                )
            )
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (requestId.isEmpty()) {
            return
        }

        // If the activity is destroyed unexpectedly, send failure result to prevent caller from hanging
        if (!isFinishing) {
            lifecycleScope.launch {
                ActionProxy.ShareOpenResultManager.sendResult(
                    ActionProxy.ShareOpenResult(
                        requestId = requestId,
                        success = false
                    )
                )
            }
        }
    }
}
