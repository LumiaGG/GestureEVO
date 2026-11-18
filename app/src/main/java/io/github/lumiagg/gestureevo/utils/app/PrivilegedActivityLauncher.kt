package io.github.lumiagg.gestureevo.utils.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.system.Os
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule

internal object PrivilegedActivityLauncher {
    @Suppress("DEPRECATION")
    suspend fun launch(context: Context, intent: Intent, trace: String): Boolean {
        val component = intent.component ?: return false
        if (intent.selector != null || intent.clipData != null || intent.flags and URI_GRANT_FLAGS != 0) return false
        val scheme = intent.data?.scheme
        if (scheme.equals("content", ignoreCase = true) || scheme.equals("file", ignoreCase = true)) return false
        val extras = intent.extras
        if (extras != null && extras.keySet().any { key -> !isSupportedExtra(extras[key]) }) return false

        val argument = shellArgument(intent.toUri(Intent.URI_INTENT_SCHEME))
        return launch(context, component, argument, trace)
    }

    suspend fun launchSearchActivity(context: Context, component: ComponentName, trace: String): Boolean {
        return launch(context, component, "-n ${shellArgument(component.flattenToString())}", trace, preferRoot = true)
    }

    private suspend fun launch(
        context: Context,
        component: ComponentName,
        argument: String,
        trace: String,
        preferRoot: Boolean = false,
    ): Boolean {
        val userId = Os.getuid() / ANDROID_UIDS_PER_USER
        val launched = AShell.tryWithAuthorizedShell(
            context, "am start -W --user $userId $argument", preferRoot
        ) { (success, output) ->
            success && output.lineSequence().any { it.trim() == "Status: ok" }
        }
        AppLogger.event(
            LogModule.ACTION, "shell_launch_completed",
            if (launched) LogLevel.DEBUG else LogLevel.WARN, trace,
            mapOf("method" to "am_start", "component" to component.flattenToShortString())
        )
        return launched
    }

    private fun isSupportedExtra(value: Any?): Boolean = when (value) {
        is String, is Boolean, is Byte, is Char, is Short, is Int, is Long, is Float, is Double -> true
        else -> false
    }

    private const val URI_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
}
