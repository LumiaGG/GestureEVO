package io.github.lumiagg.gestureevo.utils

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager

class MultiUserAPP(val context: Context) {
    private val userManager: UserManager by lazy { context.getSystemService(Context.USER_SERVICE) as UserManager }
    private val launcherApps: LauncherApps by lazy { context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps }
    val userHandleList: List<UserHandle> by lazy { userManager.userProfiles }
    var mainUser: Long = 0

    fun getActivityList(pkgName: String?, handle: UserHandle?): List<LauncherActivityInfo> {
        return launcherApps.getActivityList(pkgName, handle)
    }

    fun getSerialNumberForUser(handle: UserHandle?): Long {
        return userManager.getSerialNumberForUser(handle)
    }

    fun getSerialNumberForUser(pkgName: String): Long {
        for (handle in userHandleList) {
            for (info in getActivityList(pkgName, handle)) {
                val userId = getSerialNumberForUser(info.user)
                if (userId != mainUser) {
                    return userId
                }
            }
        }
        return mainUser
    }
}
