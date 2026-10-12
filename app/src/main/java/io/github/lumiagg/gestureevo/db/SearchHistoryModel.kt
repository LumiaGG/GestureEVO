package io.github.lumiagg.gestureevo.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class SearchHistoryModel(
    @PrimaryKey
    val text: String,
    val updatedAt: Long = System.currentTimeMillis()
)
