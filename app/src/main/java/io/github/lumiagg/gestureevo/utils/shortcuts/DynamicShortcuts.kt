package io.github.lumiagg.gestureevo.utils.shortcuts

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import androidx.annotation.RequiresApi
import androidx.room.withTransaction
import io.github.lumiagg.gestureevo.db.DatabaseManager
import io.github.lumiagg.gestureevo.db.DynamicShortcutModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object DynamicShortcuts {
    const val SCHEME = "gesture-evo-shortcut"
    private val mutex = Mutex()
    private val dao get() = DatabaseManager.db.dynamicShortcutDao()

    fun uri(model: DynamicShortcutModel): String = Uri.Builder()
        .scheme(SCHEME).authority("launch")
        .appendQueryParameter("pkg", model.packageName)
        .appendQueryParameter("id", model.shortcutId)
        .appendQueryParameter("user", model.userSerial.toString())
        .build().toString()

    fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= 25 &&
        context.getSystemService(LauncherApps::class.java).hasShortcutHostPermission()

    @RequiresApi(25)
    suspend fun queryAndRecoverImports(context: Context): List<DynamicShortcutModel> = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(hasPermission(context)) { "Shortcut access requires the default home app" }
            val launcher = context.getSystemService(LauncherApps::class.java)
            val users = context.getSystemService(UserManager::class.java)
            // Complete every accessible profile before publishing a new selection snapshot.
            val profiles = if (Build.VERSION.SDK_INT >= 26) launcher.profiles else users.userProfiles
            val records = profiles.flatMap { user ->
                check(users.isUserUnlocked(user)) { "Unlock all accessible profiles before importing" }
                val serial = users.getSerialNumberForUser(user)
                check(serial >= 0) { "User profile is unavailable" }
                query(launcher, user).filter { it.isEnabled }.map { info ->
                    val appInfo = if (Build.VERSION.SDK_INT >= 26) {
                        launcher.getApplicationInfo(info.`package`, 0, user)
                    } else {
                        launcher.getActivityList(info.`package`, user).firstOrNull()?.applicationInfo
                    }
                    val appLabel = appInfo?.let { context.packageManager.getApplicationLabel(it).toString() }
                        ?: info.`package`
                    DynamicShortcutModel(info.`package`, info.id, serial,
                        (info.shortLabel ?: info.longLabel ?: info.id).toString(), appLabel,
                        imported = info.isPinned)
                }
            }
            // Recover an interrupted import whose system pin succeeded before the DB confirmation.
            DatabaseManager.db.withTransaction {
                records.filter { it.imported }.forEach { model ->
                    dao.confirm(model.packageName, model.shortcutId, model.userSerial)
                    dao.updateLabels(model.packageName, model.shortcutId, model.userSerial, model.label, model.appLabel)
                }
            }
            records
        }
    }

    @RequiresApi(25)
    suspend fun import(context: Context, selected: List<DynamicShortcutModel>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(hasPermission(context)) { "Shortcut access requires the default home app" }
            val launcher = context.getSystemService(LauncherApps::class.java)
            val users = context.getSystemService(UserManager::class.java)
            selected.groupBy { it.packageName to it.userSerial }.forEach { (key, models) ->
                val user = users.getUserForSerialNumber(key.second)
                    ?: error("User profile no longer exists")
                val backend = object : ShortcutImportBackend {
                    override suspend fun pinnedIds(packageName: String, userSerial: Long): Set<String> =
                        query(launcher, user, packageName).filter { it.isPinned }.map { it.id }.toSet()

                    override suspend fun stage(shortcuts: List<ShortcutIdentity>) {
                        DatabaseManager.db.withTransaction {
                            models.forEach { model ->
                                dao.stage(model.copy(imported = false))
                                dao.updateLabels(model.packageName, model.shortcutId, model.userSerial,
                                    model.label, model.appLabel)
                            }
                        }
                    }

                    override suspend fun pin(packageName: String, userSerial: Long, ids: Set<String>) {
                        launcher.pinShortcuts(packageName, ids.toList(), user)
                    }

                    override suspend fun confirm(shortcuts: List<ShortcutIdentity>) {
                        DatabaseManager.db.withTransaction {
                            shortcuts.forEach { dao.confirm(it.packageName, it.id, it.userSerial) }
                        }
                    }
                }
                importShortcutGroup(backend, models.map { ShortcutIdentity(it.packageName, it.shortcutId, it.userSerial) })
            }
        }
    }

    @RequiresApi(25)
    private fun query(launcher: LauncherApps, user: UserHandle, pkg: String? = null): List<ShortcutInfo> =
        launcher.getShortcuts(LauncherApps.ShortcutQuery().apply {
            setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            if (pkg != null) setPackage(pkg)
        }, user) ?: error("System shortcut query failed")

    suspend fun requireImported(context: Context, uri: Uri, expectedPackage: String): DynamicShortcutModel {
        require(Build.VERSION.SDK_INT >= 25 && uri.scheme == SCHEME && uri.host == "launch")
        val pkg = requireNotNull(uri.getQueryParameter("pkg")).also { require(it.isNotBlank()) }
        require(expectedPackage.isEmpty() || expectedPackage == pkg)
        val id = requireNotNull(uri.getQueryParameter("id")).also { require(it.isNotEmpty()) }
        val serial = requireNotNull(uri.getQueryParameter("user")?.toLongOrNull()).also { require(it >= 0) }
        check(dao.isImported(pkg, id, serial)) { "Shortcut needs to be imported on this device" }
        check(context.getSystemService(UserManager::class.java).getUserForSerialNumber(serial) != null)
        return DynamicShortcutModel(pkg, id, serial, "", "", true)
    }
}
