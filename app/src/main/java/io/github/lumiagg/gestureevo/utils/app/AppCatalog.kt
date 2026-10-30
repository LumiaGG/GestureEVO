package io.github.lumiagg.gestureevo.utils.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import androidx.room.withTransaction
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.DatabaseManager
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val KEY_LAST_APP_SYNC_TIME = "lastAPPsSyncTime"
private const val KEY_LAST_APP_HASH = "lastAPPsHash"
private val appSyncMutex = Mutex()

suspend fun syncAppCatalog(context: Context) = appSyncMutex.withLock {
    syncAppCatalogUnlocked(context)
}

private suspend fun syncAppCatalogUnlocked(context: Context) = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
    val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(flags)
    }.filter { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }
        .associateBy { it.packageName }

    val database = DatabaseManager.db
    database.withTransaction {
        val dao = database.appDao
        val known = dao.getAll()
        val plan = planAppCatalogSync(installedApps.keys, known.map { CatalogPackage(it.pkgName, it.isRemove) })
        val changes = plan.added.map { packageName ->
            val info = installedApps.getValue(packageName)
            APPModel(appDisplayName(context, info) ?: packageName, packageName).apply {
                refreshCatalogMetadata(context, info)
            }
        }
        if (changes.isNotEmpty()) dao.insert(*changes.toTypedArray())
        for (model in known) {
            val info = installedApps[model.pkgName]
            if (info != null) {
                model.refreshCatalogMetadata(context, info)
                dao.update(model)
            } else if (model.pkgName in plan.removed) {
                model.isRemove = true
                dao.update(model)
            }
        }
    }
    // Preserve the persisted key for existing backups; commit it only after database success.
    context.settingsStore.saveKey(KEY_LAST_APP_HASH, installedApps.keys.hashCode())
}

/** Read current package state so queued broadcasts cannot apply stale install/remove events. */
internal suspend fun syncAppCatalogPackage(context: Context, packageName: String) = appSyncMutex.withLock {
    withContext(Dispatchers.IO) {
        val info = context.packageManager.applicationInfoOrNull(packageName)
            ?.takeIf { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }
        val database = DatabaseManager.db
        database.withTransaction {
            val dao = database.appDao
            val existing = dao.findByPkg(packageName)
            if (info == null) {
                if (existing != null && !existing.isRemove) {
                    existing.isRemove = true
                    dao.update(existing)
                }
            } else {
                val model = existing ?: APPModel(packageName, packageName)
                model.refreshCatalogMetadata(context, info)
                if (existing == null) dao.insert(model) else dao.update(model)
            }
        }
    }
}

private fun APPModel.refreshCatalogMetadata(context: Context, info: ApplicationInfo) {
    appDisplayName(context, info)?.let { if (name != it) name = it }
    isRemove = false
    // Preserve support for launching frozen apps and all unrelated user-configured flags.
    val launchable = !info.enabled || context.packageManager.getLaunchIntentForPackage(pkgName) != null
    setState(APPModel.FLAG_LAUNCHER, launchable)
    setState(APPModel.FLAG_NO_LAUNCHER, !launchable)
    val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    setState(APPModel.FLAG_SYSTEM, system)
    setState(APPModel.FLAG_USER, !system)
}

suspend fun appDisplayNameFromDatabase(context: Context, packageName: String): String? =
    getAPPDao().findByPkg(packageName)?.name ?: appDisplayName(context, packageName)

fun appDisplayName(context: Context, packageName: String): String? =
    context.packageManager.applicationInfoOrNull(packageName)?.let { appDisplayName(context, it) }

fun appDisplayName(context: Context, info: ApplicationInfo): String? = try {
    context.packageManager.getApplicationLabel(info).toString()
} catch (_: Resources.NotFoundException) {
    null
}

fun isActivityExported(context: Context, packageName: String, className: String): Boolean = try {
    @Suppress("DEPRECATION")
    context.packageManager.getActivityInfo(ComponentName(packageName, className), 0).exported
} catch (_: PackageManager.NameNotFoundException) {
    false
}

suspend fun syncAppCatalogIfNeeded(context: Context, minIntervalMs: Long = 60_000L) = appSyncMutex.withLock {
    val now = System.currentTimeMillis()
    val lastSync = context.settingsStore.readKey(KEY_LAST_APP_SYNC_TIME, 0L)
    if (now >= lastSync && now - lastSync < minIntervalMs) return@withLock
    syncAppCatalogUnlocked(context)
    context.settingsStore.saveKey(KEY_LAST_APP_SYNC_TIME, System.currentTimeMillis())
}
