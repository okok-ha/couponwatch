package com.couponwatch.detector.data.repo

import com.couponwatch.detector.data.db.DetectionLogDao
import com.couponwatch.detector.data.db.DetectionLogEntity
import com.couponwatch.detector.data.db.PackageStat
import com.couponwatch.detector.util.Redactor
import com.couponwatch.detector.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class LogRepository(private val dao: DetectionLogDao) {

    fun observeRecent(limit: Int = 300): Flow<List<DetectionLogEntity>> = dao.observeRecent(limit)

    fun observeByPackage(packageName: String, limit: Int = 300): Flow<List<DetectionLogEntity>> =
        dao.observeByPackage(packageName, limit)

    fun observeTodayCount(): Flow<Int> = dao.observeCountSince(TimeFormat.startOfToday())

    fun observeTodayAppearCount(): Flow<Int> =
        dao.observeAppearCountSince(TimeFormat.startOfToday())

    fun observePackageStats(): Flow<List<PackageStat>> = dao.observePackageStats()

    suspend fun insert(log: DetectionLogEntity): Long =
        withContext(Dispatchers.IO) { dao.insert(log) }

    suspend fun clearAll() = withContext(Dispatchers.IO) { dao.clearAll() }

    /** 按保留天数清理旧日志，返回删除条数。 */
    suspend fun prune(keepDays: Int): Int = withContext(Dispatchers.IO) {
        dao.deleteOlderThan(TimeFormat.daysAgo(keepDays))
    }

    suspend fun loadAll(): List<DetectionLogEntity> = withContext(Dispatchers.IO) { dao.loadAll() }

    /** 导出为 CSV 文本（UTF-8，带表头）。纯函数式拼装，便于单元测试。 */
    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        buildCsv(dao.loadAll())
    }

    companion object {
        const val CSV_HEADER =
            "序号,时间,应用包名,应用名称,动作,评分,命中关键词,窗口标题,窗口类型,事件类型," +
                "控件类型,界面摘要,测试结果,OCR"

        fun buildCsv(rows: List<DetectionLogEntity>): String {
            val sb = StringBuilder()
            sb.append(CSV_HEADER).append("\r\n")
            rows.forEachIndexed { index, row ->
                sb.append(index + 1).append(',')
                    .append(Redactor.csvCell(TimeFormat.full(row.timestamp))).append(',')
                    .append(Redactor.csvCell(row.packageName)).append(',')
                    .append(Redactor.csvCell(row.appLabel)).append(',')
                    .append(Redactor.csvCell(row.action)).append(',')
                    .append(row.score).append(',')
                    .append(Redactor.csvCell(row.keywords)).append(',')
                    .append(Redactor.csvCell(row.windowTitle)).append(',')
                    .append(Redactor.csvCell(row.windowType)).append(',')
                    .append(Redactor.csvCell(row.eventType)).append(',')
                    .append(Redactor.csvCell(row.controlTypes)).append(',')
                    .append(Redactor.csvCell(row.nodeSummary)).append(',')
                    .append(Redactor.csvCell(row.testResult)).append(',')
                    .append(if (row.ocrUsed) "是" else "否")
                    .append("\r\n")
            }
            return sb.toString()
        }
    }
}
