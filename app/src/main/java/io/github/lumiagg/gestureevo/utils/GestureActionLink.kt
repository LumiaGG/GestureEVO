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
    var userId: Long = 0
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
        }.build().toString()
    }

    fun parseLink(link: String): GestureActionLink {
        if (link.isEmpty()) return this

        val uri = link.toUri()
        actionType = uri.toGestureActionType()
        queryList.clear()

        val excludeParams = setOf("freeform", "newWindow", "timeout", "userId")
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
