package com.couponwatch.detector.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 脱敏与文本裁剪。
 *
 * 设计原则（对应需求"不采集密码、支付信息或无关个人信息"）：
 * 1. 密码类节点（AccessibilityNodeInfo.isPassword = true）整段跳过，不读取文本；
 * 2. 连续 6 位以上数字视为敏感（卡号/手机号/金额），只保留前 2 位；
 * 3. 单条文本最长保留 [MAX_TEXT]，整条摘要最长 [MAX_SUMMARY]，避免把整屏内容搬进数据库。
 */
object Redactor {

    private const val MAX_TEXT = 40
    private const val MAX_SUMMARY = 240
    private const val LONG_DIGITS = 6

    private val longDigits = Regex("\\d{6,}")
    private val longToken = Regex("[A-Za-z0-9]{8,}")

    /** 对单个节点文本做脱敏 + 裁剪；空白串返回 null。 */
    fun text(raw: CharSequence?): String? {
        val s = raw?.toString()?.trim().orEmpty()
        if (s.isEmpty()) return null
        val masked = maskDigits(s)
        return if (masked.length <= MAX_TEXT) masked else masked.take(MAX_TEXT) + "…"
    }

    /** 连续数字掩码：13800138000 → 13********* */
    fun maskDigits(input: String): String =
        longDigits.replace(input) { match ->
            val digits = match.value
            digits.take(2) + "*".repeat(digits.length - 2)
        }

    /** 兑换码一类的长串同样掩码，避免把"疑似有效码"原样落库。 */
    fun maskCodeLike(input: String): String =
        longToken.replace(input) { match ->
            val token = match.value
            if (token.any { it.isDigit() } && token.length >= 8) {
                token.take(2) + "*".repeat(token.length - 2)
            } else {
                token
            }
        }

    /** 拼接多条已脱敏文本为摘要，并限制总长度。 */
    fun summary(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        val joined = parts.joinToString(" | ")
        return if (joined.length <= MAX_SUMMARY) joined else joined.take(MAX_SUMMARY) + "…"
    }

    /** 把 CSV 单元格里的逗号、引号、换行转义。 */
    fun csvCell(value: String?): String {
        val v = value ?: ""
        val needsQuote = v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
}

/** 时间格式化工具。 */
object TimeFormat {

    private val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    fun full(millis: Long): String = synchronized(full) { full.format(Date(millis)) }

    fun clock(millis: Long): String = synchronized(clock) { clock.format(Date(millis)) }

    fun fileStamp(millis: Long): String = synchronized(fileStamp) { fileStamp.format(Date(millis)) }

    /** 当天 0 点的时间戳。 */
    fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun daysAgo(days: Int): Long = startOfToday() - days.toLong() * 24L * 60L * 60L * 1000L
}
