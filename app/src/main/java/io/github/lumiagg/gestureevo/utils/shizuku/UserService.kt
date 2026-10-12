package io.github.lumiagg.gestureevo.utils.shizuku

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.lumiagg.gestureevo.IUserService
import io.github.lumiagg.gestureevo.utils.ExecuteResult
import io.github.lumiagg.gestureevo.utils.runProcess
import java.io.File
import kotlin.system.exitProcess

class UserService : IUserService.Stub {
    constructor() : super()

    @Keep
    constructor(context: Context) : this() {
        Log.i(TAG, "constructor with Context: context=$context")
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun execute(
        cmdarray: MutableList<String>,
        envp: MutableList<String>?,
        dir: String?
    ): ExecuteResult {
        // AIDL executes on a Binder worker thread; no blocking coroutine bridge is needed.
        val result = runProcess(cmdarray, envp, dir?.let(::File))
        return ExecuteResult(result.exitCode, result.stderr, result.stdout)
    }

    companion object {
        private const val TAG = "UserService"
    }
}
