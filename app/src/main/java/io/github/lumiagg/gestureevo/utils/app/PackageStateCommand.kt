package io.github.lumiagg.gestureevo.utils.app

// Android assigns each user/profile a range of 100000 UIDs.
internal const val ANDROID_UIDS_PER_USER = 100000

internal fun shellArgument(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

internal fun packageStateCommand(packageName: String, enabled: Boolean, userId: Int): String {
    require(packageName.isNotBlank() && userId >= 0)
    val operation = if (enabled) "enable" else "disable-user"
    return "pm $operation --user $userId ${shellArgument(packageName)}"
}
