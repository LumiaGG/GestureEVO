package io.github.lumiagg.gestureevo.gesture.config

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager.buildTag
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.MoshiUtils
import io.github.lumiagg.gestureevo.utils.toGestureActionType
import com.squareup.moshi.JsonClass

enum class IconType {
    URI, TEXT, OTHER, INVISIBLE
}

@JsonClass(generateAdapter = true)
class GestureActionItem {
    var tag: String = buildTag()
    var name: String? = null
    var subActionListTag: String? = null
    var icon: String? = null
    var iconType: IconType = IconType.OTHER
    var firstStageLink: String? = null
        set(value) {
            field = value
            // Update cached URI and metadata
            if (value != null) {
                firstStageUri = value.toUri()
                firstStageAction = firstStageUri?.toGestureActionType() ?: GestureActionType.NONE
                firstStagePkg = firstStageUri?.getQueryParameter("pkg")
            } else {
                firstStageUri = null
                firstStageAction = GestureActionType.NONE
                firstStagePkg = null
            }
        }
    var secondStageLink: String? = null
        set(value) {
            field = value
            // Update cached URI and metadata
            if (value != null) {
                secondStageUri = value.toUri()
                secondStageAction = secondStageUri?.toGestureActionType() ?: GestureActionType.NONE
                secondStagePkg = secondStageUri?.getQueryParameter("pkg")
            } else {
                secondStageUri = null
                secondStageAction = GestureActionType.NONE
                secondStagePkg = null
            }
        }


    // Cached properties excluded from serialization
    @Transient
    var firstStageUri: Uri? = null
        private set

    @Transient
    var secondStageUri: Uri? = null
        private set

    @Transient
    var firstStageAction: GestureActionType = GestureActionType.NONE
        private set

    @Transient
    var secondStageAction: GestureActionType = GestureActionType.NONE
        private set

    @Transient
    var firstStagePkg: String? = null
        private set

    @Transient
    var secondStagePkg: String? = null
        private set

    fun getFirstStageParam(param: String): Boolean {
        return firstStageUri?.getBooleanQueryParameter(param, false) == true
    }

    fun getSecondStageParam(param: String): Boolean {
        return secondStageUri?.getBooleanQueryParameter(param, false) == true
    }

    fun addParam(param: String, value: String, isFirstStage: Boolean = true) {
        val uri = if (isFirstStage) firstStageUri else secondStageUri
        if (uri == null) {
            return
        }

        try {
            val newLink = uri.buildUpon()
                .clearQuery()
                .apply {
                    // Preserve other parameters
                    uri.queryParameterNames
                        .filter { it != param }
                        .forEach {
                            appendQueryParameter(it, uri.getQueryParameter(it))
                        }
                    // Append new parameter
                    appendQueryParameter(param, value)
                }
                .build()
                .toString()

            if (isFirstStage) {
                firstStageLink = newLink // Triggers setter to update cache automatically
            } else {
                secondStageLink = newLink // Triggers setter to update cache automatically
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun haveSecondStage(): Boolean {
        return !secondStageLink.isNullOrEmpty() && secondStageAction != GestureActionType.NONE
    }

    fun toJson(format: Boolean = true): String {
        return MoshiUtils.toJson(this, format = format)
    }

    companion object {
        fun toModel(json: String): GestureActionItem? {
            return MoshiUtils.toModel<GestureActionItem>(json)
        }

        fun toJsonList(list: List<GestureActionItem>): String {
            return MoshiUtils.toJson(list)
        }

        fun toModelList(json: String): List<GestureActionItem>? {
            return MoshiUtils.toModel<List<GestureActionItem>>(json)
        }
    }
}


enum class GestureActionType {
    SHOW_ACTION_VIEW, CLICK_THROUGH, SCREEN_CONTENT, SCREEN_CONTENT_FULLSCREEN, SCREEN_SEARCH, SEARCH, PIN_POOL, POINTER, KEYBOARD, OPEN_APP, OTHER_SHORTCUT, OTHER_LINK, CLIPBOARD_ACTION, ACC_ACTION,
    RECENT_APP, OPEN_ACTIVITY, FREEZE_APP, FREEFORM_FOREGROUND, OPEN_SHELL, TEMP_DND,
    NONE;

    fun showName(context: Context): String {
        return when (this) {
            SHOW_ACTION_VIEW -> context.getString(R.string.gesture_action_show_action_view)
            OPEN_APP -> context.getString(R.string.gesture_action_open_app)
            OPEN_ACTIVITY -> context.getString(R.string.gesture_action_open_activity)
            OPEN_SHELL -> context.getString(R.string.gesture_action_open_shell)
            SCREEN_CONTENT -> context.getString(R.string.gesture_action_screen_content)
            SCREEN_CONTENT_FULLSCREEN -> context.getString(R.string.gesture_action_screen_content_fullscreen)
            SCREEN_SEARCH -> context.getString(R.string.gesture_action_screen_search)
            KEYBOARD -> context.getString(R.string.gesture_action_keyboard)
            OTHER_LINK -> context.getString(R.string.gesture_action_open_other_link)
            RECENT_APP -> context.getString(R.string.gesture_action_recent_app)
            FREEZE_APP -> context.getString(R.string.gesture_action_freeze_app)
            OTHER_SHORTCUT -> context.getString(R.string.gesture_action_other_shortcut)
            FREEFORM_FOREGROUND -> context.getString(R.string.gesture_action_freeform_foreground)
            POINTER -> context.getString(R.string.gesture_action_pointer)
            NONE -> context.getString(R.string.gesture_action_none)
            CLICK_THROUGH -> context.getString(R.string.gesture_action_click_through)
            ACC_ACTION -> context.getString(R.string.gesture_action_acc_action)
            CLIPBOARD_ACTION -> context.getString(R.string.gesture_action_clipboard_action)
            SEARCH -> context.getString(R.string.gesture_action_search)
            PIN_POOL -> context.getString(R.string.gesture_action_pin_pool)
            TEMP_DND -> context.getString(R.string.gesture_action_temp_dnd)
        }
    }

    fun isQuickSearchWhitelisted(): Boolean = this in QUICK_SEARCH_WHITELIST

    fun matchesQuickSearchKeyword(context: Context, keyword: String): Boolean {
        val query = keyword.trim()
        if (query.isEmpty()) return false
        val queryLowercase = query.lowercase()
        return showName(context).contains(queryLowercase, ignoreCase = true) ||
                name.contains(queryLowercase, ignoreCase = true)
    }

    companion object {
        // Only actions that can be executed safely without extra parameters.
        val QUICK_SEARCH_WHITELIST: Set<GestureActionType> = setOf(
            SCREEN_CONTENT,
            SCREEN_CONTENT_FULLSCREEN,
            SCREEN_SEARCH,
            PIN_POOL,
            POINTER,
            KEYBOARD,
            TEMP_DND
        )

        fun valueOfSafe(value: String): GestureActionType {
            return try {
                valueOf(value)
            } catch (e: IllegalArgumentException) {
                NONE
            }
        }
    }
}
