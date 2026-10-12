package io.github.lumiagg.gestureevo.db

import androidx.room.Database
import androidx.room.RoomDatabase


@Database(
    version = 1,
    exportSchema = true,
    entities = [
        APPModel::class,
        ContactModel::class,
        ShortcutModel::class,
        SearchHistoryModel::class,
        SearchResultPinModel::class,
        HiddenSearchResultModel::class,
        ExternalActionPermissionModel::class,
        PinPoolModel::class,
    ]
)
abstract class DataBase : RoomDatabase() {
    val appDao: APPDao by lazy { createAPPDao() }

    val contactDao: ContactDao by lazy { createContactDao() }

    val shortcutDao: ShortcutDao by lazy { createShortcutDao() }

    val searchHistoryDao: SearchHistoryDao by lazy { createSearchHistoryDao() }

    val searchResultPinDao: SearchResultPinDao by lazy { createSearchResultPinDao() }

    val hiddenSearchResultDao: HiddenSearchResultDao by lazy { createHiddenSearchResultDao() }

    val externalActionPermissionDao: ExternalActionPermissionDao by lazy {
        createExternalActionPermissionDao()
    }

    val pinPoolDao: PinPoolDao by lazy { createPinPoolDao() }

    abstract fun createAPPDao(): APPDao
    abstract fun createContactDao(): ContactDao

    abstract fun createShortcutDao(): ShortcutDao

    abstract fun createSearchHistoryDao(): SearchHistoryDao

    abstract fun createSearchResultPinDao(): SearchResultPinDao

    abstract fun createHiddenSearchResultDao(): HiddenSearchResultDao

    abstract fun createExternalActionPermissionDao(): ExternalActionPermissionDao

    abstract fun createPinPoolDao(): PinPoolDao

}
