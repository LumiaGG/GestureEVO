package io.github.lumiagg.gestureevo.utils

import io.github.lumiagg.gestureevo.db.ExternalActionPermissionModel
import io.github.lumiagg.gestureevo.db.getExternalActionPermissionDao

object ExternalActionPermissionStore {
    private val defaultScopeType = ExternalActionPermissionModel.ScopeType.LINK.name

    suspend fun isAlwaysAllowed(callerPackage: String, link: String): Boolean {
        if (callerPackage.isBlank() || link.isBlank()) return false
        val dao = getExternalActionPermissionDao()
        val normalizedLink = link.trim()
        val actionScopeValue = buildActionScopeValue(normalizedLink)
        return dao.findByScope(
            callerPackage = callerPackage.trim(),
            scopeType = ExternalActionPermissionModel.ScopeType.ACTION.name,
            scopeValue = actionScopeValue
        ) != null ||
            dao.findByScope(
                callerPackage = callerPackage.trim(),
                scopeType = ExternalActionPermissionModel.ScopeType.LINK.name,
                scopeValue = normalizedLink
            ) != null
    }

    suspend fun allowAlwaysForAction(callerPackage: String, link: String) {
        val normalizedLink = link.trim()
        insertPermission(
            callerPackage = callerPackage,
            link = normalizedLink,
            scopeType = ExternalActionPermissionModel.ScopeType.ACTION,
            scopeValue = buildActionScopeValue(normalizedLink)
        )
    }

    suspend fun allowAlwaysForLink(callerPackage: String, link: String) {
        val normalizedLink = link.trim()
        insertPermission(
            callerPackage = callerPackage,
            link = normalizedLink,
            scopeType = ExternalActionPermissionModel.ScopeType.LINK,
            scopeValue = normalizedLink
        )
    }

    suspend fun getAllPermissions(): List<ExternalActionPermissionModel> {
        return getExternalActionPermissionDao().getAll()
    }

    suspend fun deletePermission(id: Int) {
        getExternalActionPermissionDao().deleteById(id)
    }

    suspend fun replaceAllPermissions(permissions: List<ExternalActionPermissionModel>) {
        val dao = getExternalActionPermissionDao()
        dao.deleteAll()
        permissions.forEach { permission ->
            dao.insert(normalizePermission(permission).copy(id = 0))
        }
    }

    private suspend fun insertPermission(
        callerPackage: String,
        link: String,
        scopeType: ExternalActionPermissionModel.ScopeType,
        scopeValue: String
    ) {
        val normalizedCallerPackage = callerPackage.trim()
        val normalizedLink = link.trim()
        if (normalizedCallerPackage.isBlank() || normalizedLink.isBlank()) return
        getExternalActionPermissionDao().insert(
            ExternalActionPermissionModel(
                callerPackage = normalizedCallerPackage,
                link = normalizedLink,
                scopeType = scopeType.name,
                scopeValue = scopeValue.trim()
            )
        )
    }

    private fun normalizePermission(
        permission: ExternalActionPermissionModel
    ): ExternalActionPermissionModel {
        val normalizedLink = permission.link.trim()
        val validScopeTypes = ExternalActionPermissionModel.ScopeType.values().map { it.name }
        val normalizedScopeType = permission.scopeType
            .trim()
            .takeIf { scope -> scope in validScopeTypes }
            ?: defaultScopeType

        val normalizedScopeValue = when (normalizedScopeType) {
            ExternalActionPermissionModel.ScopeType.ACTION.name -> {
                permission.scopeValue.trim().ifBlank { buildActionScopeValue(normalizedLink) }
            }

            else -> permission.scopeValue.trim().ifBlank { normalizedLink }
        }

        return permission.copy(
            callerPackage = permission.callerPackage.trim(),
            link = normalizedLink,
            scopeType = normalizedScopeType,
            scopeValue = normalizedScopeValue
        )
    }

    private fun buildActionScopeValue(link: String): String {
        return GestureActionLink()
            .apply { parseLink(link) }
            .actionType
            .name
    }
}
