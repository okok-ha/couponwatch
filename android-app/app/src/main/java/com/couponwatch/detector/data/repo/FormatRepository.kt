package com.couponwatch.detector.data.repo

import com.couponwatch.detector.data.db.FormatRuleDao
import com.couponwatch.detector.data.db.FormatRuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class FormatRepository(private val dao: FormatRuleDao) {

    fun observeAll(): Flow<List<FormatRuleEntity>> = dao.observeAll()

    fun observeActive(): Flow<FormatRuleEntity?> = dao.observeActive()

    suspend fun activeRule(): FormatRuleEntity? = withContext(Dispatchers.IO) { dao.activeRule() }

    suspend fun save(rule: FormatRuleEntity): Long = withContext(Dispatchers.IO) { dao.upsert(rule) }

    /** 启用某条规则（同一时刻只有一条 active）。 */
    suspend fun activate(id: Long) = withContext(Dispatchers.IO) {
        dao.clearActive()
        dao.markActive(id)
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.delete(id) }
}
