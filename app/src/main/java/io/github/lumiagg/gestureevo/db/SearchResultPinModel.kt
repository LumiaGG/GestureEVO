package io.github.lumiagg.gestureevo.db

import androidx.room.Entity

@Entity(primaryKeys = ["query", "resultKey"])
data class SearchResultPinModel(
    val query: String,
    val resultKey: String,
    val pinnedAt: Long
)
