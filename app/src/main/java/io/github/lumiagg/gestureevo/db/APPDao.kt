package io.github.lumiagg.gestureevo.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface APPDao {
    @Query("SELECT * FROM APPModel ORDER BY `pinyinFK` ASC")
    suspend fun getAll(): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE state & :state = :state and (state & :excludeState) = 0 ORDER BY `pinyinFK` ASC")
    suspend fun getAll(state: Int, excludeState: Int = 0): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE (state & :state) = 0 ORDER BY `pinyinFK` ASC")
    suspend fun getAllNotInclude(state: Int): List<APPModel>

    @Query("SELECT count(*) FROM APPModel")
    suspend fun total(): Long

    @Query("SELECT count(*) FROM APPModel WHERE state & :state = :state and (state & :excludeState) = 0")
    suspend fun total(state: Int, excludeState: Int = 0): Long

    @Query("SELECT * FROM APPModel WHERE id = :id")
    suspend fun findById(id: Int): APPModel?

    @Query("SELECT * FROM APPModel WHERE name LIKE '%' || :name || '%'")
    suspend fun findByName(name: String): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE name LIKE '%' || :name || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByName(name: String, state: Int, excludeState: Int = 0): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE pkgName = :pkg LIMIT 1")
    suspend fun findByPkg(pkg: String): APPModel?

    @Query("SELECT * FROM APPModel WHERE pinyinFK LIKE '%' || :pinyin || '%'")
    suspend fun findByPinYinFK(pinyin: String): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE pinyinFK LIKE '%' || :pinyin || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByPinYinFK(pinyin: String, state: Int, excludeState: Int = 0): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE pinyinDK LIKE '%' || :pinyin || '%'")
    suspend fun findByPinYinDK(pinyin: String): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE pinyinDK LIKE '%' || :pinyin || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByPinYinDK(pinyin: String, state: Int, excludeState: Int = 0): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE pinyinT9 LIKE '%' || :pinyin || '%'")
    suspend fun findByPinYinT9(pinyin: String): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE pinyinT9 LIKE '%' || :pinyin || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByPinYinT9(pinyin: String, state: Int, excludeState: Int = 0): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE initialsFK LIKE '%' || :initials || '%'")
    suspend fun findByInitialsFK(initials: String): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE initialsFK LIKE '%' || :initials || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByInitialsFK(
        initials: String,
        state: Int,
        excludeState: Int = 0
    ): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE initialsDK LIKE '%' || :initials || '%'")
    suspend fun findByInitialsDK(initials: String): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE initialsDK LIKE '%' || :initials || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByInitialsDK(
        initials: String,
        state: Int,
        excludeState: Int = 0
    ): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE initialsT9 LIKE '%' || :initials || '%'")
    suspend fun findByInitialsT9(initials: String): List<APPModel>

    @Query("SELECT * FROM APPModel WHERE initialsT9 LIKE '%' || :initials || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByInitialsT9(
        initials: String,
        state: Int,
        excludeState: Int = 0
    ): List<APPModel>


    @Query("SELECT * FROM APPModel WHERE state & :state = :state and (state & :excludeState) = 0 ORDER BY lastUsageTime desc LIMIT :limit")
    suspend fun oderByLastUsageTime(state: Int, excludeState: Int = 0, limit: Int): List<APPModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg models: APPModel)

    @Update
    suspend fun update(model: APPModel)

    @Delete
    suspend fun delete(model: APPModel)

    @Query("DELETE FROM APPModel")
    suspend fun deleteAll()

    @Query("delete from APPModel")
    suspend fun deleteTable()

    @Query("update sqlite_sequence SET seq = 0 where name = 'APPModel'")
    suspend fun resetPriKey()
}
