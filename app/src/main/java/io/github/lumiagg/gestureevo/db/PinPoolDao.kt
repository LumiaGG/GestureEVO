package io.github.lumiagg.gestureevo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PinPoolDao {
    @Query("SELECT * FROM PinPoolItem ORDER BY pinnedAt DESC, createdAt DESC")
    suspend fun getAll(): List<PinPoolModel>

    @Query(
        "SELECT * FROM PinPoolItem WHERE category = :category ORDER BY pinnedAt DESC, createdAt DESC"
    )
    suspend fun getByCategory(category: String): List<PinPoolModel>

    @Query("SELECT * FROM PinPoolItem WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PinPoolModel?

    @Query(
        "SELECT id FROM PinPoolItem WHERE category = :category AND contentHash = :hash AND contentHash != '' LIMIT 1"
    )
    suspend fun findIdByCategoryAndHash(category: String, hash: String): String?

    @Query("SELECT * FROM PinPoolItem WHERE category = :category AND pinnedAt <= 0")
    suspend fun getUnpinnedByCategory(category: String): List<PinPoolModel>

    @Query(
        "SELECT * FROM PinPoolItem WHERE category = :category AND pinnedAt <= 0 AND createdAt < :expireBefore"
    )
    suspend fun getExpiredUnpinnedByCategory(
        category: String,
        expireBefore: Long,
    ): List<PinPoolModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: PinPoolModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(models: List<PinPoolModel>)

    @Update
    suspend fun update(model: PinPoolModel)

    @Query("DELETE FROM PinPoolItem WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM PinPoolItem WHERE category = :category AND pinnedAt <= 0")
    suspend fun deleteUnpinnedByCategory(category: String)

    @Query("DELETE FROM PinPoolItem")
    suspend fun deleteAll()
}
