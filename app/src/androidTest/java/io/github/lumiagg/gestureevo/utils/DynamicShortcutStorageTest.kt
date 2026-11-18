package io.github.lumiagg.gestureevo.utils

import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lumiagg.gestureevo.db.DataBase
import io.github.lumiagg.gestureevo.db.DynamicShortcutModel
import io.github.lumiagg.gestureevo.db.MIGRATION_1_2
import io.github.lumiagg.gestureevo.utils.shortcuts.DynamicShortcuts
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DynamicShortcutStorageTest {
    @Test fun migrateOldDatabaseAndKeepUserData() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "dynamic-shortcut-migration-test.db"
        context.deleteDatabase(name)
        // The exported v1 schema is the actual release schema, including its Room identity.
        val schema = JSONObject(instrumentation.context.assets.open("io.github.lumiagg.gestureevo.db.DataBase/1.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    repeat(entities.length()) { index ->
                        val entity = entities.getJSONObject(index)
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                        val indices = entity.optJSONArray("indices")
                        repeat(indices?.length() ?: 0) { i ->
                            requireNotNull(indices)
                            db.execSQL(indices.getJSONObject(i).getString("createSql")
                                .replace("\${TABLE_NAME}", entity.getString("tableName")))
                        }
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    repeat(setup.length()) { db.execSQL(setup.getString(it)) }
                    db.execSQL("INSERT INTO ShortcutModel (id, uri, state, isRemove, name, pinyinFK, initialsFK, pinyinDK, initialsDK, pinyinT9, initialsT9) VALUES (1, 'test:old', 7, 0, 'Existing', '', '', '', '', '', '')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, DataBase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            assertEquals(7, db.shortcutDao.findById(1)?.state)
            val dao = db.dynamicShortcutDao()
            val first = DynamicShortcutModel("app", "id/with?symbols", 1, "First", "App")
            val otherUser = first.copy(userSerial = 2)
            dao.stage(first)
            dao.stage(otherUser)
            assertTrue(dao.getImported().isEmpty())
            dao.confirm(first.packageName, first.shortcutId, first.userSerial)
            dao.stage(first.copy(label = "Retry"))
            assertTrue(dao.isImported(first.packageName, first.shortcutId, 1))
            assertFalse(dao.isImported(first.packageName, first.shortcutId, 2))
            assertEquals(1, dao.getImported().size)
            val uri = android.net.Uri.parse(DynamicShortcuts.uri(first))
            assertEquals(first.shortcutId, uri.getQueryParameter("id"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
