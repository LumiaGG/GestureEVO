package io.github.lumiagg.gestureevo.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Device-local references. A restored reference cannot restore the system pin grant. */
@Entity(primaryKeys = ["packageName", "shortcutId", "userSerial"])
data class DynamicShortcutModel(
    val packageName: String,
    val shortcutId: String,
    val userSerial: Long,
    val label: String,
    val appLabel: String,
    val imported: Boolean = false,
)

@Dao
interface DynamicShortcutDao {
    @Query("SELECT * FROM DynamicShortcutModel WHERE imported = 1 ORDER BY appLabel, label, userSerial, shortcutId")
    suspend fun getImported(): List<DynamicShortcutModel>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun stage(model: DynamicShortcutModel)

    @Query("UPDATE DynamicShortcutModel SET label = :label, appLabel = :appLabel WHERE packageName = :pkg AND shortcutId = :id AND userSerial = :serial")
    suspend fun updateLabels(pkg: String, id: String, serial: Long, label: String, appLabel: String)

    @Query("UPDATE DynamicShortcutModel SET imported = 1 WHERE packageName = :pkg AND shortcutId = :id AND userSerial = :serial")
    suspend fun confirm(pkg: String, id: String, serial: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM DynamicShortcutModel WHERE packageName = :pkg AND shortcutId = :id AND userSerial = :serial AND imported = 1)")
    suspend fun isImported(pkg: String, id: String, serial: Long): Boolean
}
