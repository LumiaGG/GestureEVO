package io.github.lumiagg.gestureevo.db

import androidx.room.Room
import io.github.lumiagg.gestureevo.EvoApplication.Companion.application

object DatabaseManager {
    private const val DB_NAME = "GestureEvoRoom.db"
    val db: DataBase by lazy {
        Room.databaseBuilder(application.applicationContext, DataBase::class.java, DB_NAME)
            .build()
    }
}

fun getAPPDao(): APPDao {
    return DatabaseManager.db.appDao
}

fun getContactDao(): ContactDao {
    return DatabaseManager.db.contactDao
}

fun getShortcutDao(): ShortcutDao {
    return DatabaseManager.db.shortcutDao
}

fun getSearchHistoryDao(): SearchHistoryDao {
    return DatabaseManager.db.searchHistoryDao
}

fun getSearchResultPinDao(): SearchResultPinDao {
    return DatabaseManager.db.searchResultPinDao
}

fun getHiddenSearchResultDao(): HiddenSearchResultDao {
    return DatabaseManager.db.hiddenSearchResultDao
}

fun getExternalActionPermissionDao(): ExternalActionPermissionDao {
    return DatabaseManager.db.externalActionPermissionDao
}

fun getPinPoolDao(): PinPoolDao {
    return DatabaseManager.db.pinPoolDao
}
