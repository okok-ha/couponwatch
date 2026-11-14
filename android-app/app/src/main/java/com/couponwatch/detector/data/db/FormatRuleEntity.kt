package com.couponwatch.detector.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 保存的兑换码格式规则。支持多套并存，同一时刻只有一套 active。
 */
@Entity(tableName = "format_rules")
data class FormatRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val regex: String,
    /** 样例，换行分隔 */
    val samples: String,
    val lengthMin: Int,
    val lengthMax: Int,
    val prefix: String,
    val suffix: String,
    val separators: String,
    val charset: String,
    val mode: String,
    val active: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface FormatRuleDao {

    @Query("SELECT * FROM format_rules ORDER BY active DESC, createdAt DESC")
    fun observeAll(): Flow<List<FormatRuleEntity>>

    @Query("SELECT * FROM format_rules WHERE active = 1 LIMIT 1")
    fun observeActive(): Flow<FormatRuleEntity?>

    @Query("SELECT * FROM format_rules WHERE active = 1 LIMIT 1")
    suspend fun activeRule(): FormatRuleEntity?

    @Query("SELECT * FROM format_rules ORDER BY createdAt DESC")
    suspend fun loadAll(): List<FormatRuleEntity>

    @Upsert
    suspend fun upsert(rule: FormatRuleEntity): Long

    @Query("UPDATE format_rules SET active = 0")
    suspend fun clearActive()

    @Query("UPDATE format_rules SET active = 1 WHERE id = :id")
    suspend fun markActive(id: Long)

    @Query("DELETE FROM format_rules WHERE id = :id")
    suspend fun delete(id: Long)
}
