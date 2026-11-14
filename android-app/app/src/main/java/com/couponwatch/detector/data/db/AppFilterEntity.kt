package com.couponwatch.detector.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 应用过滤条目。
 *
 * 语义：
 * - [blocked] = true  → 该应用的界面完全不参与检测（黑名单）
 * - 表中不存在该包名 → 按"检测"处理（默认全量检测，用户主动排除）
 *
 * 这样默认行为最直观：开启服务即开始检测，用户只需要排除不想被检测的应用。
 */
@Entity(tableName = "app_filters")
data class AppFilterEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val blocked: Boolean,
    val updatedAt: Long,
)

@Dao
interface AppFilterDao {

    @Query("SELECT * FROM app_filters ORDER BY appLabel COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AppFilterEntity>>

    @Query("SELECT * FROM app_filters")
    suspend fun loadAll(): List<AppFilterEntity>

    @Query("SELECT blocked FROM app_filters WHERE packageName = :packageName LIMIT 1")
    suspend fun isBlocked(packageName: String): Boolean?

    @Upsert
    suspend fun upsert(entry: AppFilterEntity)

    @Query("DELETE FROM app_filters WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM app_filters WHERE blocked = 0")
    suspend fun clearAllowedEntries(): Int
}
