package com.couponwatch.detector.data.repo

import com.couponwatch.detector.data.db.AppFilterDao
import com.couponwatch.detector.data.db.AppFilterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class FilterRepository(private val dao: AppFilterDao) {

    fun observeAll(): Flow<List<AppFilterEntity>> = dao.observeAll()

    suspend fun blockedPackages(): Set<String> = withContext(Dispatchers.IO) {
        dao.loadAll().filter { it.blocked }.map { it.packageName }.toSet()
    }

    suspend fun isBlocked(packageName: String): Boolean = withContext(Dispatchers.IO) {
        dao.isBlocked(packageName) ?: false
    }

    suspend fun setBlocked(packageName: String, appLabel: String, blocked: Boolean) =
        withContext(Dispatchers.IO) {
            dao.upsert(
                AppFilterEntity(
                    packageName = packageName,
                    appLabel = appLabel,
                    blocked = blocked,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }

    suspend fun remove(packageName: String) = withContext(Dispatchers.IO) {
        dao.delete(packageName)
    }
}
