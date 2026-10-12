package io.github.lumiagg.gestureevo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SearchResultPinDao {
    @Query("SELECT * FROM SearchResultPinModel WHERE `query` = :query ORDER BY pinnedAt DESC, resultKey ASC")
    suspend fun findByQuery(query: String): List<SearchResultPinModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: SearchResultPinModel)

    @Query("DELETE FROM SearchResultPinModel WHERE `query` = :query AND resultKey = :resultKey")
    suspend fun delete(query: String, resultKey: String)
}
