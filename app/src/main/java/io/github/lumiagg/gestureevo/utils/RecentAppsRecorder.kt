package io.github.lumiagg.gestureevo.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.db.updateDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RecentAppsRecorder {
    private const val TAG = "RecentAppsRecorder"

    fun getLauncherPackages(context: Context): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }

        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
    }

    suspend fun isLauncherPackage(packageName: String?): Boolean = withContext(Dispatchers.IO) {
        if (packageName.isNullOrBlank()) return@withContext false
        val model = getAPPDao().findByPkg(pkg = packageName) ?: return@withContext false
        model.getState(APPModel.FLAG_HOME)
    }

    suspend fun initLauncherPackagesInDB(context: Context) = withContext(Dispatchers.IO) {
        val launcherPkgs = getLauncherPackages(context = context)
        launcherPkgs.forEach {
            getAPPDao().findByPkg(pkg = it)?.let { model ->
                model.setState(APPModel.FLAG_HOME, true)
                updateDB(model)
            }
        }
    }

    // Records recent application usage.
    suspend fun recordApp(context: Context, model: APPModel) = withContext(Dispatchers.IO) {
        try {
            if (model.getState(APPModel.FLAG_NO_LAUNCHER)) return@withContext

            model.lastUsageTime = System.currentTimeMillis()
            updateDB(model)
        } catch (e: Exception) {
            Log.e(TAG, "Error recording app", e)
        }
    }

    // Retrieves the most recently used applications.
    suspend fun getRecentApps(
        count: Int,
        excludeState: Int = APPModel.FLAG_HOME or APPModel.FLAG_RECENT_APPS_DISABLE
    ): List<APPModel> {
        return getAPPDao().oderByLastUsageTime(
            state = APPModel.FLAG_LAUNCHER,
            excludeState = excludeState,
            limit = count
        )
    }
}
