package io.github.lumiagg.gestureevo.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class HiddenSearchResultModel(
    @PrimaryKey val resultKey: String,
    val displayName: String,
    val hiddenAt: Long
)
