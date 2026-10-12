package io.github.lumiagg.gestureevo.pin

import java.io.File

/** Pin images are regular files directly inside the app's dedicated image directory. */
internal fun resolvePinImageFile(directory: File, path: String?): File? {
    if (path.isNullOrBlank()) return null
    return runCatching {
        val root = directory.canonicalFile
        val candidate = File(path)
        if (!candidate.isAbsolute) return null
        val file = candidate.canonicalFile
        file.takeIf { it.parentFile == root && !it.isDirectory }
    }.getOrNull()
}

internal fun deletePinImageFile(directory: File, path: String?): Boolean =
    runCatching {
        resolvePinImageFile(directory, path)?.takeIf { it.isFile }?.delete() ?: false
    }.getOrDefault(false)

internal fun normalizePinImagePath(directory: File, item: PinPoolItem): PinPoolItem? =
    if (item.type == PinType.IMAGE) {
        resolvePinImageFile(directory, item.imagePath)?.let { item.copy(imagePath = it.path) }
    } else {
        item.copy(imagePath = null)
    }

/** Validate the whole index before replacing files or database records. */
internal fun validatePinImagePaths(directory: File, items: List<PinPoolItem>): List<PinPoolItem> =
    items.map { item ->
        requireNotNull(normalizePinImagePath(directory, item)) { "Invalid Pin image path in backup" }
    }
