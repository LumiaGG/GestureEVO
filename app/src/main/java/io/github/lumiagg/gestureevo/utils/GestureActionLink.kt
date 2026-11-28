package io.github.lumiagg.gestureevo.utils

import android.net.Uri
import androidx.core.net.toUri
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType


fun Uri.toGestureActionType(): GestureActionType {
    return GestureActionType.valueOfSafe(lastPathSegment ?: GestureActionType.NONE.name)
}

fun String?.toGestureActionType(): GestureActionType {
    if (isNullOrEmpty()) return GestureActionType.NONE
    return runCatching { toUri().toGestureActionType() }
        .getOrDefault(GestureActionType.NONE)
}


data class GestureActionLink(
    var actionType: GestureActionType = GestureActionType.NONE,
    var queryList: ArrayList<Pair<String, String>> = ArrayList(),
    var isFreeform: Boolean = false,
    var isNewWindow: Boolean = false,
    var isTimeout: Boolean = false,
    var userId: Long = 0,
    var delayMs: Long = 0,
) {
    private val uriBuilder = Uri.Builder()
        .scheme("gesture-evo")
        .authority("action")

    fun buildLink(): String {
        return uriBuilder.apply {
            clearQuery()
            path(actionType.name)
            queryList.forEach { (key, value) -> appendQueryParameter(key, value) }
            appendQueryParameter("freeform", isFreeform.toString())
            appendQueryParameter("newWindow", isNewWindow.toString())
            appendQueryParameter("timeout", isTimeout.toString())
            appendQueryParameter("userId", userId.toString())
            if (delayMs in 1..MAX_ACTION_DELAY_MS) {
                appendQueryParameter(ACTION_DELAY_PARAMETER, delayMs.toString())
            }
        }.build().toString()
    }

    fun parseLink(link: String): GestureActionLink {
        if (link.isEmpty()) return this

        val uri = link.toUri()
        actionType = uri.toGestureActionType()
        queryList.clear()

        val excludeParams = setOf("freeform", "newWindow", "timeout", "userId", ACTION_DELAY_PARAMETER)
        uri.queryParameterNames
            .filterNot { it in excludeParams }
            .forEach { name ->
                uri.getQueryParameter(name)?.let { value ->
                    addParams(name, value)
                }
            }

        isFreeform = uri.getBooleanQueryParameter("freeform", false)
        isNewWindow = uri.getBooleanQueryParameter("newWindow", false)
        isTimeout = uri.getBooleanQueryParameter("timeout", false)
        userId = uri.getQueryParameter("userId")?.toLongOrNull() ?: 0
        delayMs = parseActionDelayMs(uri.getQueryParameter(ACTION_DELAY_PARAMETER))
        return this
    }

    fun addParams(key: String, value: String) {
        queryList.add(Pair(key, value))
    }

    val isDualOpen: Boolean
        get() = userId > 0

    val pkg: String?
        get() = queryList.find { it.first == "pkg" }?.second
}

internal const val ACTION_DELAY_PARAMETER = "delayMs"
internal const val MAX_ACTION_DELAY_MS = 2_000L

internal fun parseActionDelayMs(value: String?): Long =
    value?.toLongOrNull()?.takeIf { it in 0..MAX_ACTION_DELAY_MS } ?: 0L
