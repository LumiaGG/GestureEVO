package io.github.lumiagg.gestureevo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExternalActionPermissionDao {
    @Query("SELECT * FROM ExternalActionPermissionModel ORDER BY id ASC")
    suspend fun getAll(): List<ExternalActionPermissionModel>

    @Query(
        """
        SELECT * FROM ExternalActionPermissionModel
        WHERE callerPackage = :callerPackage
          AND scopeType = :scopeType
          AND scopeValue = :scopeValue
        LIMIT 1
        """
    )
    suspend fun findByScope(
        callerPackage: String,
        scopeType: String,
        scopeValue: String
    ): ExternalActionPermissionModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: ExternalActionPermissionModel): Long

    @Query("DELETE FROM ExternalActionPermissionModel WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM ExternalActionPermissionModel")
    suspend fun deleteAll()
}
