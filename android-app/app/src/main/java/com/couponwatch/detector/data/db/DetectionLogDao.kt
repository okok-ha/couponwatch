package com.couponwatch.detector.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DetectionLogDao {

    @Insert
    suspend fun insert(log: DetectionLogEntity): Long

    @Query("SELECT * FROM detection_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DetectionLogEntity>>

    @Query(
        "SELECT * FROM detection_logs WHERE packageName = :packageName " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    fun observeByPackage(packageName: String, limit: Int): Flow<List<DetectionLogEntity>>

    @Query("SELECT * FROM detection_logs WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun loadSince(since: Long): List<DetectionLogEntity>

    @Query("SELECT * FROM detection_logs ORDER BY timestamp DESC")
    suspend fun loadAll(): List<DetectionLogEntity>

    @Query("SELECT COUNT(*) FROM detection_logs WHERE timestamp >= :since")
    fun observeCountSince(since: Long): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM detection_logs " +
            "WHERE timestamp >= :since AND action = 'APPEAR'"
    )
    fun observeAppearCountSince(since: Long): Flow<Int>

    @Query(
        "SELECT packageName, appLabel, COUNT(*) AS count FROM detection_logs " +
            "GROUP BY packageName ORDER BY count DESC"
    )
    fun observePackageStats(): Flow<List<PackageStat>>

    @Query("DELETE FROM detection_logs")
    suspend fun clearAll()

    @Query("DELETE FROM detection_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
