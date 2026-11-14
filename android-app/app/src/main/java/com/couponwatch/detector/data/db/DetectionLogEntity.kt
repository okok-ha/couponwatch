package com.couponwatch.detector.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条检测记录。
 *
 * 只保存"判定结果 + 脱敏摘要"，不保存完整界面文本：
 * - [nodeSummary] 由 [com.couponwatch.detector.util.Redactor] 生成，密码类节点整体跳过，
 *   连续数字会被掩码处理。
 * - 不保存任何截图文件（OCR 仅在内存中处理，结果同样经过脱敏）。
 */
@Entity(
    tableName = "detection_logs",
    indices = [Index("timestamp"), Index("packageName")],
)
data class DetectionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val packageName: String,
    val appLabel: String,
    /** APPEAR = 弹窗出现；DISAPPEAR = 弹窗消失 */
    val action: String,
    val score: Int,
    /** 命中的关键词，逗号分隔 */
    val keywords: String,
    val windowTitle: String,
    /** 窗口类型描述，如 TYPE_APPLICATION / TYPE_SYSTEM */
    val windowType: String,
    /** 触发本次判定的 AccessibilityEvent 类型名 */
    val eventType: String,
    val nodeSummary: String,
    val ocrUsed: Boolean = false,
    /** 识别到的控件类型，如 "EditText,Button,TextView" */
    val controlTypes: String = "",
    /** 自动化测试结果（仅测试环境产生），如 FORMAT_OK / NOT_FOUND / FORMAT_ERROR */
    val testResult: String = "",
)

/** 按应用聚合的统计结果（用于首页与日志页筛选）。 */
data class PackageStat(
    val packageName: String,
    val appLabel: String,
    val count: Int,
)
