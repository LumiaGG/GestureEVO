package io.github.lumiagg.gestureevo.search.config

import android.net.Uri
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.ContactModel
import io.github.lumiagg.gestureevo.db.ShortcutModel
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager.buildTag
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.config.IconType
import io.github.lumiagg.gestureevo.utils.MoshiUtils
import com.squareup.moshi.JsonClass

enum class EngineType {
    DIRECT_LINK,
    SHARE_TO_APP,
    SHARE_IMAGE_TO_APP,
    JUMP_TO_ACTIVITY,
    EXTERN_JUMP_LINK,

}

@JsonClass(generateAdapter = true)
class SearchEngine {
    var tag: String = buildTag()
    var name: String? = null
    var engineType: EngineType = EngineType.DIRECT_LINK
    var icon: String? = null
    var iconType: IconType = IconType.OTHER
    var textIcon: String? = null
    var searchLink: String? = null
    var externJumpLink: String? = null
    var externJumpPackage: String? = null
    var targetPackage: String? = null
    var targetActivity: String? = null
    var useCustomTabs: Boolean = false
    var autoInputEnter: Boolean = true
    var showInSearchEngineList: Boolean = true

    fun toJson(format: Boolean = true): String {
        return MoshiUtils.toJson(this, format = format)
    }

    companion object {
        fun toModel(json: String): SearchEngine? {
            return MoshiUtils.toModel<SearchEngine>(json)
        }

        fun toJsonList(list: List<SearchEngine>): String {
            return MoshiUtils.toJson(list)
        }

        fun toExportJsonList(list: List<SearchEngine>): String {
            return MoshiUtils.toJson(list.map { it.toExportPayload() })
        }

        fun toModelList(json: String): List<SearchEngine>? {
            return MoshiUtils.toModel<List<SearchEngine>>(json)
        }
    }

    private fun toExportPayload(): ExportPayload {
        val normalizedIconType = when {
            !targetPackage.isNullOrBlank() -> IconType.OTHER
            iconType == IconType.URI -> IconType.TEXT
            else -> iconType
        }
        val normalizedTextIcon = when {
            normalizedIconType == IconType.TEXT && !textIcon.isNullOrBlank() -> textIcon
            normalizedIconType == IconType.TEXT -> name?.trim()?.firstOrNull()?.toString() ?: ""
            else -> textIcon
        }
        return ExportPayload(
            name = name,
            engineType = engineType,
            iconType = normalizedIconType,
            textIcon = normalizedTextIcon,
            searchLink = searchLink,
            externJumpLink = externJumpLink,
            externJumpPackage = externJumpPackage,
            targetPackage = targetPackage,
            targetActivity = targetActivity,
            useCustomTabs = useCustomTabs,
            autoInputEnter = autoInputEnter
        )
    }

    @JsonClass(generateAdapter = true)
    data class ExportPayload(
        val name: String?,
        val engineType: EngineType,
        val iconType: IconType,
        val textIcon: String?,
        val searchLink: String?,
        val externJumpLink: String?,
        val externJumpPackage: String?,
        val targetPackage: String?,
        val targetActivity: String?,
        val useCustomTabs: Boolean,
        val autoInputEnter: Boolean
    )
}

sealed class SearchResult {
    data class AppResult(val app: APPModel) : SearchResult()

    data class UrlResult(val url: String) : SearchResult()

    data class PhoneResult(val phone: String) : SearchResult()

    data class PasteResult(val text: String) : SearchResult()

    data class PasteImageResult(val uri: Uri) : SearchResult()

    data class ContactResult(val contact: ContactModel) : SearchResult()

    data class ShortcutResult(val shortcut: ShortcutModel) : SearchResult()

    data class HistoryResult(val text: String) : SearchResult()

    data object ClearHistoryAction : SearchResult()

    data object AppSettingsResult : SearchResult()

    data class GestureActionResult(val actionType: GestureActionType) : SearchResult()
}
