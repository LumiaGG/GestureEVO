package io.github.lumiagg.gestureevo.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ContactDao {
    @Query("SELECT * FROM ContactModel ORDER BY `pinyinFK` ASC")
    suspend fun getAll(): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE state & :state = :state and (state & :excludeState) = 0 ORDER BY `pinyinFK` ASC")
    suspend fun getAll(state: Int, excludeState: Int = 0): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE (state & :state) = 0 ORDER BY `pinyinFK` ASC")
    suspend fun getAllNotInclude(state: Int): List<ContactModel>

    @Query("SELECT count(*) FROM ContactModel")
    suspend fun total(): Long

    @Query("SELECT count(*) FROM ContactModel WHERE state & :state = :state and (state & :excludeState) = 0")
    suspend fun total(state: Int, excludeState: Int = 0): Long

    @Query("SELECT * FROM ContactModel WHERE id = :id")
    suspend fun findById(id: Int): ContactModel?

    @Query("SELECT * FROM ContactModel WHERE name LIKE '%' || :name || '%'")
    suspend fun findByName(name: String): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE name LIKE '%' || :name || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByName(name: String, state: Int, excludeState: Int = 0): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE pinyinFK LIKE '%' || :pinyin || '%'")
    suspend fun findByPinYinFK(pinyin: String): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE pinyinFK LIKE '%' || :pinyin || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByPinYinFK(
        pinyin: String,
        state: Int,
        excludeState: Int = 0
    ): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE pinyinDK LIKE '%' || :pinyin || '%'")
    suspend fun findByPinYinDK(pinyin: String): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE pinyinDK LIKE '%' || :pinyin || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByPinYinDK(
        pinyin: String,
        state: Int,
        excludeState: Int = 0
    ): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE pinyinT9 LIKE '%' || :pinyin || '%'")
    suspend fun findByPinYinT9(pinyin: String): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE pinyinT9 LIKE '%' || :pinyin || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByPinYinT9(
        pinyin: String,
        state: Int,
        excludeState: Int = 0
    ): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE initialsFK LIKE '%' || :initials || '%'")
    suspend fun findByInitialsFK(initials: String): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE initialsFK LIKE '%' || :initials || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByInitialsFK(
        initials: String,
        state: Int,
        excludeState: Int = 0
    ): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE initialsDK LIKE '%' || :initials || '%'")
    suspend fun findByInitialsDK(initials: String): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE initialsDK LIKE '%' || :initials || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByInitialsDK(
        initials: String,
        state: Int,
        excludeState: Int = 0
    ): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE initialsT9 LIKE '%' || :initials || '%'")
    suspend fun findByInitialsT9(initials: String): List<ContactModel>

    @Query("SELECT * FROM ContactModel WHERE initialsT9 LIKE '%' || :initials || '%' AND state & :state = :state and (state & :excludeState) = 0")
    suspend fun findByInitialsT9(
        initials: String,
        state: Int,
        excludeState: Int = 0
    ): List<ContactModel>


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg models: ContactModel)

    @Update
    suspend fun update(model: ContactModel)

    @Delete
    suspend fun delete(model: ContactModel)

    @Query("DELETE FROM ContactModel")
    suspend fun deleteAll()

    @Query("delete from ContactModel")
    suspend fun deleteTable()

    @Query("update sqlite_sequence SET seq = 0 where name = 'ContactModel'")
    suspend fun resetPriKey()
}
