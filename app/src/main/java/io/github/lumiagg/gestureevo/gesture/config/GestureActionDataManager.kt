package io.github.lumiagg.gestureevo.gesture.config

import android.content.Context
import android.util.Log
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.actionStore
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import java.util.UUID

object GestureActionDataManager {
    const val TAG = "GestureActionDataManager"
    const val KEY_ACTION_ITEMS_TAG_LIST = "key_action_items_tag_list"
    const val KEY_ACTION_CONFIG_PREFIX = "main_config_"
    const val KEY_SUB_ACTION_CONFIG_PREFIX = "sub_config_"

    fun buildTag(): String {
        return UUID.randomUUID().toString()
    }

    // Config management functions
    suspend fun saveActionConfig(
        context: Context,
        tag: String,
        config: ActionConfig,
        isSubConfig: Boolean
    ) {
        val prefix = if (isSubConfig) KEY_SUB_ACTION_CONFIG_PREFIX else KEY_ACTION_CONFIG_PREFIX
        context.actionStore.saveKey("${prefix}$tag", config.toJson())
    }

    suspend fun readActionConfig(
        context: Context,
        tag: String,
        isSubConfig: Boolean
    ): ActionConfig {
        val prefix = if (isSubConfig) KEY_SUB_ACTION_CONFIG_PREFIX else KEY_ACTION_CONFIG_PREFIX
        val configJson = context.actionStore.readKey("${prefix}$tag", "")
        return ActionConfig.toModel(configJson) ?: ActionConfig().apply {
            this.tag = tag
        }
    }

    suspend fun removeActionConfig(context: Context, tag: String) {
        context.actionStore.saveKey("${KEY_ACTION_CONFIG_PREFIX}$tag", "")
        context.actionStore.saveKey("${KEY_SUB_ACTION_CONFIG_PREFIX}$tag", "")
    }

    // Existing tag management functions
    suspend fun readActionListTags(context: Context): List<ActionItemsTag> {
        val tagsJson = context.actionStore.readKey(KEY_ACTION_ITEMS_TAG_LIST, "")
        val tags = ArrayList(ActionItemsTag.toModelList(tagsJson) ?: ArrayList())
        tags.add(ActionItemsTag().apply {
            tag = ActionConfig.DEFAULT_TAG
            name = context.getString(R.string.default_action_tag_name)
        })
        return tags.reversed()
    }

    suspend fun addActionItemsTag(context: Context, actionItemsTag: ActionItemsTag) {
        var tags =
            ActionItemsTag.toModelList(context.actionStore.readKey(KEY_ACTION_ITEMS_TAG_LIST, ""))
                ?: ArrayList()
        tags = ArrayList(tags)
        tags.add(actionItemsTag)
        context.actionStore.saveKey(KEY_ACTION_ITEMS_TAG_LIST, ActionItemsTag.toJsonList(tags))
    }

    suspend fun updateActionItemsTag(context: Context, tag: String, newName: String) {
        var tags =
            ActionItemsTag.toModelList(context.actionStore.readKey(KEY_ACTION_ITEMS_TAG_LIST, ""))
                ?: ArrayList()
        tags = ArrayList(tags)
        tags.find { it.tag == tag }?.let {
            it.name = newName
        }
        context.actionStore.saveKey(KEY_ACTION_ITEMS_TAG_LIST, ActionItemsTag.toJsonList(tags))
    }

    suspend fun removeActionItemsTag(context: Context, tag: String) {
        if (tag == ActionConfig.DEFAULT_TAG) return

        // Remove the tag from the list
        var tags =
            ActionItemsTag.toModelList(context.actionStore.readKey(KEY_ACTION_ITEMS_TAG_LIST, ""))
                ?: ArrayList()
        tags = ArrayList(tags)
        tags.removeIf { it.tag == tag }
        context.actionStore.saveKey(KEY_ACTION_ITEMS_TAG_LIST, ActionItemsTag.toJsonList(tags))

        // Clean up associated data
        context.actionStore.saveKey(tag, "") // Remove items
        removeActionConfig(context, tag) // Remove config
    }

    // Existing action item management functions
    suspend fun readActionItemList(context: Context, tag: String): List<GestureActionItem>? {
        val actionItemListJson = context.actionStore.readKey(tag, "")
        return GestureActionItem.toModelList(actionItemListJson)
    }

    suspend fun addActionItem(context: Context, tag: String, actionItem: GestureActionItem) {
        Log.d(TAG, "actionItemListAdd: $tag")
        val itemList = readActionItemList(context, tag)?.let { ArrayList<GestureActionItem>(it) }
            ?: ArrayList()
        itemList.removeIf { it.tag == actionItem.tag }
        itemList.add(actionItem)
        saveActionItemList(context, tag, itemList)
    }

    suspend fun updateActionItem(
        context: Context,
        tag: String,
        actionItem: GestureActionItem
    ): Boolean {
        Log.d(TAG, "updateActionItem: $tag")
        val currentList = readActionItemList(context, tag)
        val updatedList = ArrayList(currentList ?: listOf())
        val index = updatedList.indexOfFirst { it.tag == actionItem.tag }
        return if (index != -1) {
            updatedList[index] = actionItem
            saveActionItemList(context, tag, updatedList)
            true
        } else {
            Log.w(TAG, "updateActionItem: Item not found with tag ${actionItem.tag}")
            false
        }
    }

    suspend fun saveActionItemList(
        context: Context,
        tag: String,
        actionItemList: ArrayList<GestureActionItem>
    ) {
        context.actionStore.saveKey(
            tag,
            GestureActionItem.toJsonList(actionItemList).also {
                Log.d(TAG, "saveActionItemList: ${actionItemList.size} items")
            }
        )
    }
}
