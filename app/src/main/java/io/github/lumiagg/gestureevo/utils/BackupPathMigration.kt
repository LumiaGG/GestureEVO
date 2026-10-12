package io.github.lumiagg.gestureevo.utils

private val backupFilesPrefix = Regex(
    """(?:^|(?<="))/data/(?:user(?:_de)?/\d+|data)/(?:com\.jwg\.gesture_evo|io\.github\.lumiagg\.gestureevo(?:\.debug)?)/files/"""
)

/** Rebinds backed-up app-owned file paths without changing references to other apps. */
internal fun rebaseBackupFilePaths(value: String, filesDirectory: String): String =
    backupFilesPrefix.replace(value) { filesDirectory.trimEnd('/') + "/" }
