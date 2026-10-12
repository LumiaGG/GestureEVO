package io.github.lumiagg.gestureevo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HiddenSearchResultDao {
    @Query("SELECT resultKey FROM HiddenSearchResultModel ORDER BY resultKey")
    suspend fun getKeys(): List<String>

    @Query("SELECT resultKey FROM HiddenSearchResultModel ORDER BY resultKey")
    fun observeKeys(): Flow<List<String>>

    @Query("SELECT * FROM HiddenSearchResultModel ORDER BY hiddenAt DESC, resultKey ASC")
    fun observeAll(): Flow<List<HiddenSearchResultModel>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: HiddenSearchResultModel)

    @Query("DELETE FROM HiddenSearchResultModel WHERE resultKey = :resultKey")
    suspend fun delete(resultKey: String)
}
