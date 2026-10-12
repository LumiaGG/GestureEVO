package io.github.lumiagg.gestureevo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SearchHistoryDao {
    @Query("SELECT text FROM SearchHistoryModel ORDER BY updatedAt DESC")
    suspend fun getAllTexts(): List<String>

    @Query(
        """
        SELECT text FROM SearchHistoryModel
        WHERE text LIKE '%' || :query || '%'
        ORDER BY
            CASE
                WHEN text LIKE :query || '%' THEN 0
                ELSE 1
            END,
            updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchTexts(query: String, limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: SearchHistoryModel)

    @Query("DELETE FROM SearchHistoryModel WHERE text = :text")
    suspend fun deleteByText(text: String)

    @Query("DELETE FROM SearchHistoryModel")
    suspend fun deleteAll()
}
