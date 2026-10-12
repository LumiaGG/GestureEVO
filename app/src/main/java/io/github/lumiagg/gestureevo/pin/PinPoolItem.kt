package io.github.lumiagg.gestureevo.pin

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PinPoolItem(
    val id: String,
    val type: PinType,
    val text: String? = null,
    val imagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val pinnedAt: Long = 0L,
    val reminderAt: Long = 0L,
    val category: String = PinPoolCategory.GENERAL,
    val contentHash: String = "",
)

enum class PinType {
    TEXT,
    IMAGE
}

object PinPoolCategory {
    const val GENERAL = "general"
    const val SCREEN_CONTENT = "screen_content"
}

fun PinPoolItem.resolvedCategory(): String =
    category.ifBlank { PinPoolCategory.GENERAL }

fun PinPoolItem.matchesCategory(target: String): Boolean =
    resolvedCategory() == target
