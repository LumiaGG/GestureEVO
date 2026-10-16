package io.github.lumiagg.gestureevo.utils.app

import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import android.content.Context
import android.system.Os
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.runProcess
import java.io.IOException

object AShell {
    suspend fun doCmd(context: Context, command: String): Pair<Boolean, String> {
        val rootResult = doCmdWithRoot(command)
        return if (!rootResult.first && AShizuku.checkPermission(context)) {
            AShizuku.doCmdWithShizuku(command)
        } else rootResult
    }

    fun doCmdWithRoot(command: String): Pair<Boolean, String> {
        return try {
            runProcess(listOf("su", "-c", command)).also { result ->
                AppLogger.event(
                    LogModule.SHIZUKU, "command_returned",
                    if (result.isSuccess) LogLevel.DEBUG else LogLevel.WARN,
                    fields = mapOf("method" to "root", "exitCode" to result.exitCode))
            }.asCommandResult()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false to e.message.orEmpty()
        } catch (e: IOException) {
            AppLogger.event(
                LogModule.SHIZUKU, "root_unavailable",
                LogLevel.WARN, error = e)
            false to e.message.orEmpty()
        }
    }

    fun setAppEnabledSettings(packageName: String, enable: Boolean, context: Context): Boolean {
        if (context.getAppEnabledState(packageName) == null) return false
        val userId = Os.getuid() / ANDROID_UIDS_PER_USER
        val changeState = packageStateCommand(packageName, enable, userId)
        val command = if (enable) {
            "pm unhide --user $userId ${shellArgument(packageName)}; $changeState"
        } else changeState
        val (success, _) = doCmdWithRoot(command)
        val changed = success && context.getAppEnabledState(packageName) == enable
        if (!changed) {
            ToastHelper.showInUIThread(context, context.getString(R.string.root_shizuku_permission_failed))
        }
        return changed
    }
}
