package io.github.lumiagg.gestureevo.utils

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class ExecuteResult(val exitCode: Int, val error: String?, val output: String?) : Parcelable {
    val isSuccess get() = exitCode == 0
    override fun toString(): String {
        return "ExecuteResult(exitCode=$exitCode, error='$error', output='$output')"
    }
}
