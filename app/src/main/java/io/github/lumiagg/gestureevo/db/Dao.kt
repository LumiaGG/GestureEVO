package io.github.lumiagg.gestureevo.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun insertDB(model: APPModel) = withContext(Dispatchers.IO) {
    model.isRemove = false
    DatabaseManager.db.appDao.insert(model)
}

suspend fun updateDB(model: APPModel) = withContext(Dispatchers.IO) {
    model.isRemove = false
    DatabaseManager.db.appDao.update(model)
}

suspend fun deleteDB(model: APPModel) = withContext(Dispatchers.IO) {
    // TODO: Delete icon
    model.isRemove = true
    DatabaseManager.db.appDao.update(model)
}

suspend fun clearDBAPP() = withContext(Dispatchers.IO) {
    DatabaseManager.db.appDao.deleteTable()
    DatabaseManager.db.appDao.resetPriKey()
}
