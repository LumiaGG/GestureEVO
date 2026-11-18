package io.github.lumiagg.gestureevo.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `DynamicShortcutModel` (
                `packageName` TEXT NOT NULL,
                `shortcutId` TEXT NOT NULL,
                `userSerial` INTEGER NOT NULL,
                `label` TEXT NOT NULL,
                `appLabel` TEXT NOT NULL,
                `imported` INTEGER NOT NULL,
                PRIMARY KEY(`packageName`, `shortcutId`, `userSerial`)
            )
        """.trimIndent())
    }
}
