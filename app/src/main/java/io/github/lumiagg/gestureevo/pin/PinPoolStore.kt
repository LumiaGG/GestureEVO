package io.github.lumiagg.gestureevo.pin

import androidx.core.graphics.scale

import android.content.Context
import android.graphics.Bitmap
import io.github.lumiagg.gestureevo.db.PinPoolModel
import io.github.lumiagg.gestureevo.db.getPinPoolDao
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity
import io.github.lumiagg.gestureevo.utils.MoshiUtils
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.removeKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PinPoolStore {
    private const val KEY_PIN_POOL_ITEMS = "pin_pool_items"
    private const val PIN_DIR_NAME = "pin_pool"
    private val migrateMutex = Mutex()
    private val inFlightContentHashes = ConcurrentHashMap.newKeySet<String>()

    suspend fun loadPins(context: Context): MutableList<PinPoolItem> = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        cleanupMissingImages(context).toMutableList()
    }

    suspend fun loadPinsForDisplay(context: Context): MutableList<PinPoolItem> =
        withContext(Dispatchers.IO) {
            ensureMigrated(context)
            purgeExpiredAutoSave(context)
            cleanupMissingImages(context).toMutableList()
        }

    suspend fun findPin(context: Context, id: String): PinPoolItem? = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        getPinPoolDao().findById(id)?.toItem()?.let { normalizePinImagePath(pinImageDir(context), it) }
    }

    suspend fun addTextPin(context: Context, text: String): PinPoolItem? =
        withContext(Dispatchers.IO) {
            ensureMigrated(context)
            val content = text.trim()
            if (content.isEmpty()) return@withContext null
            val item = PinPoolItem(
                id = UUID.randomUUID().toString(),
                type = PinType.TEXT,
                text = content,
                category = PinPoolCategory.GENERAL,
            )
            getPinPoolDao().insert(item.toModel())
            item
        }

    suspend fun addImagePin(
        context: Context,
        bitmap: Bitmap,
        category: String = PinPoolCategory.GENERAL,
        contentHash: String = "",
    ): PinPoolItem? =
        withContext(Dispatchers.IO) {
            ensureMigrated(context)
            val dir = File(context.filesDir, PIN_DIR_NAME).apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val file = File(dir, "pin_$id.png")
            try {
                FileOutputStream(file).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        return@withContext null
                    }
                }
            } catch (e: Exception) {
                return@withContext null
            }
            val item = PinPoolItem(
                id = id,
                type = PinType.IMAGE,
                imagePath = file.absolutePath,
                category = category.ifBlank { PinPoolCategory.GENERAL },
                contentHash = contentHash,
            )
            getPinPoolDao().insert(item.toModel())
            item
        }

    suspend fun addScreenContentImageIfEnabled(context: Context, bitmap: Bitmap): PinPoolItem? {
        val enabled = context.settingsStore.readKey(
            InspireSettingsActivity.KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES,
            true
        )
        if (!enabled) return null
        return withContext(Dispatchers.IO) {
            ensureMigrated(context)
            val hash = bitmapFingerprint(bitmap)
            if (hash.isNotEmpty() && !inFlightContentHashes.add(hash)) {
                return@withContext null
            }
            try {
                if (hash.isNotEmpty()) {
                    val existing = getPinPoolDao().findIdByCategoryAndHash(
                        PinPoolCategory.SCREEN_CONTENT,
                        hash
                    )
                    if (existing != null) return@withContext null
                }
                addImagePin(context, bitmap, PinPoolCategory.SCREEN_CONTENT, hash)
            } finally {
                if (hash.isNotEmpty()) inFlightContentHashes.remove(hash)
            }
        }
    }

    suspend fun togglePinTop(context: Context, target: PinPoolItem): MutableList<PinPoolItem> =
        withContext(Dispatchers.IO) {
            ensureMigrated(context)
            val dao = getPinPoolDao()
            val existing = dao.findById(target.id) ?: return@withContext loadCurrentPins(context)
            val newPinnedAt = if (existing.pinnedAt > 0L) 0L else System.currentTimeMillis()
            dao.update(existing.copy(pinnedAt = newPinnedAt))
            loadCurrentPins(context)
        }

    suspend fun updateReminderAt(
        context: Context,
        target: PinPoolItem,
        reminderAt: Long
    ): MutableList<PinPoolItem> = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        val dao = getPinPoolDao()
        val existing = dao.findById(target.id) ?: return@withContext loadCurrentPins(context)
        dao.update(existing.copy(reminderAt = reminderAt))
        loadCurrentPins(context)
    }

    suspend fun deletePin(context: Context, target: PinPoolItem): MutableList<PinPoolItem> =
        withContext(Dispatchers.IO) {
            ensureMigrated(context)
            val dao = getPinPoolDao()
            val existing = dao.findById(target.id)
            dao.deleteById(target.id)
            if (existing?.type == PinType.IMAGE.name) {
                deletePinImageFile(pinImageDir(context), existing.imagePath)
            }
            loadCurrentPins(context)
        }

    suspend fun clearPins(
        context: Context,
        category: String = PinPoolCategory.GENERAL,
    ): MutableList<PinPoolItem> =
        withContext(Dispatchers.IO) {
            ensureMigrated(context)
            val dao = getPinPoolDao()
            dao.getUnpinnedByCategory(category).forEach { item ->
                if (item.type == PinType.IMAGE.name) {
                    deletePinImageFile(pinImageDir(context), item.imagePath)
                }
            }
            dao.deleteUnpinnedByCategory(category)
            loadCurrentPins(context)
        }

    suspend fun replaceAll(context: Context, items: List<PinPoolItem>) = withContext(Dispatchers.IO) {
        val validated = validatePinImagePaths(pinImageDir(context), items)
        ensureMigrated(context)
        val dao = getPinPoolDao()
        dao.deleteAll()
        if (validated.isNotEmpty()) {
            dao.insertAll(validated.map { it.toModel() })
        }
    }

    fun pinImageDir(context: Context): File = File(context.filesDir, PIN_DIR_NAME)

    fun sortPins(items: List<PinPoolItem>): List<PinPoolItem> {
        return items.sortedWith(
            compareByDescending<PinPoolItem> { it.pinnedAt }
                .thenByDescending { it.createdAt }
        )
    }

    private suspend fun purgeExpiredAutoSave(context: Context) {
        val days = context.settingsStore.readKey(
            InspireSettingsActivity.KEY_PIN_POOL_AUTO_DELETE_DAYS,
            0
        )
        if (days <= 0) return
        val expireBefore = System.currentTimeMillis() -
            days.coerceAtMost(InspireSettingsActivity.PIN_POOL_AUTO_DELETE_DAYS_MAX) *
            24L * 60L * 60L * 1000L
        val dao = getPinPoolDao()
        dao.getExpiredUnpinnedByCategory(PinPoolCategory.SCREEN_CONTENT, expireBefore)
            .forEach { item ->
                if (item.type == PinType.IMAGE.name) {
                    deletePinImageFile(pinImageDir(context), item.imagePath)
                }
                dao.deleteById(item.id)
            }
    }

    private suspend fun loadCurrentPins(context: Context): MutableList<PinPoolItem> =
        cleanupMissingImages(context).toMutableList()

    private suspend fun cleanupMissingImages(context: Context): List<PinPoolItem> {
        val dao = getPinPoolDao()
        val kept = mutableListOf<PinPoolItem>()
        dao.getAll().forEach { model ->
            val item = normalizePinImagePath(pinImageDir(context), model.toItem())
            val imageMissing = item == null || (item.type == PinType.IMAGE &&
                resolvePinImageFile(pinImageDir(context), item.imagePath)?.isFile != true)
            if (imageMissing) {
                dao.deleteById(model.id)
            } else {
                kept.add(requireNotNull(item))
            }
        }
        return kept
    }

    private suspend fun ensureMigrated(context: Context) {
        migrateMutex.withLock {
            val json = context.settingsStore.readKey(KEY_PIN_POOL_ITEMS, "")
            if (json.isBlank()) return
            val items = MoshiUtils.toModel<List<PinPoolItem>>(json).orEmpty()
                .mapNotNull { normalizePinImagePath(pinImageDir(context), it) }
            if (items.isNotEmpty()) {
                getPinPoolDao().insertAll(items.map { it.toModel() })
            }
            context.settingsStore.removeKey<String>(KEY_PIN_POOL_ITEMS)
        }
    }

    private fun PinPoolModel.toItem(): PinPoolItem {
        val pinType = runCatching { PinType.valueOf(type) }.getOrDefault(PinType.TEXT)
        return PinPoolItem(
            id = id,
            type = pinType,
            text = text,
            imagePath = imagePath,
            createdAt = createdAt,
            pinnedAt = pinnedAt,
            reminderAt = reminderAt,
            category = category,
            contentHash = contentHash,
        )
    }

    private fun bitmapFingerprint(bitmap: Bitmap): String {
        return runCatching {
            val width = 48
            val height = 48
            val scaled = bitmap.scale(width, height, true)
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            if (scaled !== bitmap) scaled.recycle()
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(pixels.size * 4)
            var offset = 0
            for (pixel in pixels) {
                bytes[offset++] = (pixel shr 24).toByte()
                bytes[offset++] = (pixel shr 16).toByte()
                bytes[offset++] = (pixel shr 8).toByte()
                bytes[offset++] = pixel.toByte()
            }
            digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    private fun PinPoolItem.toModel(): PinPoolModel = PinPoolModel(
        id = id,
        type = type.name,
        text = text,
        imagePath = imagePath,
        createdAt = createdAt,
        pinnedAt = pinnedAt,
        reminderAt = reminderAt,
        category = resolvedCategory(),
        contentHash = contentHash,
    )
}
