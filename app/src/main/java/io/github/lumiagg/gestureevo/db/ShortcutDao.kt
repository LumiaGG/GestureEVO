package io.github.lumiagg.gestureevo.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM ShortcutModel ORDER BY `pinyinFK` ASC")
    suspend fun getAll(): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE state & :state = :state and (state & :excludeState) = 0 ORDER BY `pinyinFK` ASC")
    suspend fun getAll(state: Int, excludeState: Int = 0): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE (state & :state) = 0 ORDER BY `pinyinFK` ASC")
    suspend fun getAllNotInclude(state: Int): List<ShortcutModel>

    @Query("SELECT count(*) FROM ShortcutModel")
    suspend fun total(): Long

    @Query("SELECT count(*) FROM ShortcutModel WHERE state & :state = :state and (state & :excludeState) = 0")
    suspend fun total(state: Int, excludeState: Int = 0): Long

    @Query("SELECT * FROM ShortcutModel WHERE id = :id")
    suspend fun findById(id: Int): ShortcutModel?

    @Query("SELECT * FROM ShortcutModel WHERE name LIKE '%' || :name || '%'")
    suspend fun findByName(name: String): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE name LIKE '%' || :name || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByName(name: String, state: Int, excludeState: Int = 0): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE pinyinFK LIKE '%' || :pinyin || '%'")
    suspend fun findByPinYinFK(pinyin: String): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE pinyinFK LIKE '%' || :pinyin || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByPinYinFK(
        pinyin: String,
        state: Int,
        excludeState: Int = 0
    ): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE pinyinDK LIKE '%' || :pinyin || '%'")
    suspend fun findByPinYinDK(pinyin: String): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE pinyinDK LIKE '%' || :pinyin || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByPinYinDK(
        pinyin: String,
        state: Int,
        excludeState: Int = 0
    ): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE pinyinT9 LIKE '%' || :pinyin || '%'")
    suspend fun findByPinYinT9(pinyin: String): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE pinyinT9 LIKE '%' || :pinyin || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByPinYinT9(
        pinyin: String,
        state: Int,
        excludeState: Int = 0
    ): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE initialsFK LIKE '%' || :initials || '%'")
    suspend fun findByInitialsFK(initials: String): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE initialsFK LIKE '%' || :initials || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByInitialsFK(
        initials: String,
        state: Int,
        excludeState: Int = 0
    ): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE initialsDK LIKE '%' || :initials || '%'")
    suspend fun findByInitialsDK(initials: String): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE initialsDK LIKE '%' || :initials || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByInitialsDK(
        initials: String,
        state: Int,
        excludeState: Int = 0
    ): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE initialsT9 LIKE '%' || :initials || '%'")
    suspend fun findByInitialsT9(initials: String): List<ShortcutModel>

    @Query("SELECT * FROM ShortcutModel WHERE initialsT9 LIKE '%' || :initials || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByInitialsT9(
        initials: String,
        state: Int,
        excludeState: Int = 0
    ): List<ShortcutModel>


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg models: ShortcutModel)

    @Update
    suspend fun update(model: ShortcutModel)

    @Delete
    suspend fun delete(model: ShortcutModel)

    @Query("DELETE FROM ShortcutModel")
    suspend fun deleteAll()

    @Query("delete from ShortcutModel")
    suspend fun deleteTable()

    @Query("update sqlite_sequence SET seq = 0 where name = 'ShortcutModel'")
    suspend fun resetPriKey()
}
