package io.github.lumiagg.gestureevo.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "PinPoolItem",
    indices = [
        Index(value = ["category"]),
        Index(value = ["createdAt"]),
        Index(value = ["pinnedAt"]),
        Index(value = ["category", "contentHash"]),
    ]
)
data class PinPoolModel(
    @PrimaryKey val id: String,
    val type: String,
    val text: String?,
    val imagePath: String?,
    val createdAt: Long,
    val pinnedAt: Long,
    val reminderAt: Long,
    val category: String,
    val contentHash: String = "",
)
