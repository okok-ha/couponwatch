package com.couponwatch.detector.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 运行时设置。
 *
 * 用 SharedPreferences 而不是 DataStore：少一个依赖，且这里只有十来个键值，
 * 读写都在主线程可接受范围（只在界面操作时触发）。
 */
data class Settings(
    /** 检测到弹窗时是否发通知 */
    val notificationsEnabled: Boolean = true,
    /** 判定阈值，分数 >= 阈值才算"疑似兑换弹窗" */
    val scoreThreshold: Int = 5,
    /** 是否检测本应用（内置模拟页面就在本应用内，默认开启便于自测） */
    val detectOwnApp: Boolean = true,
    /** 日志保留天数 */
    val keepDays: Int = 14,
    /** 是否启用 OCR 辅助（仅 full 变体且依赖可用时生效） */
    val ocrEnabled: Boolean = false,
    /** 关键词列表，逗号分隔 */
    val keywordList: String = DEFAULT_KEYWORDS,
    /** 同一应用两次记录之间的最小间隔，避免刷屏 */
    val debounceMs: Long = 1200L,
    /** true=白名单模式（只监测勾选的应用）；false=黑名单模式（监测除勾选外的应用） */
    val whitelistMode: Boolean = false,
    /** 自动测试最大次数 */
    val autoTestMax: Int = 10,
    /** 自动测试每条间隔（毫秒） */
    val autoTestDelayMs: Long = 800L,
    /** 待填入到其他应用的兑换码（用户自己提供） */
    val pendingCode: String = "",
    /** 批量试码用的样例码，一行一条，用来归纳规律 */
    val batchSamples: String = "",
    /** 批量试码本轮最多提交条数 */
    val batchMax: Int = 50,
    /** 批量试码两条之间的基础间隔（毫秒） */
    val batchIntervalMs: Long = 1200L,
    /** 命中一条就停 */
    val batchStopOnHit: Boolean = false,
) {
    fun keywords(): List<String> =
        keywordList.split(',', '，', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    companion object {
        const val DEFAULT_KEYWORDS =
            "兑换码,兑换,优惠券,代金券,激活码,礼品卡,领取,coupon,redeem,gift code,voucher,code"
    }
}

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    fun current(): Settings = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        prefs.edit()
            .putBoolean(KEY_NOTIFY, next.notificationsEnabled)
            .putInt(KEY_THRESHOLD, next.scoreThreshold)
            .putBoolean(KEY_OWN_APP, next.detectOwnApp)
            .putInt(KEY_KEEP_DAYS, next.keepDays)
            .putBoolean(KEY_OCR, next.ocrEnabled)
            .putString(KEY_KEYWORDS, next.keywordList)
            .putLong(KEY_DEBOUNCE, next.debounceMs)
            .putBoolean(KEY_WHITELIST, next.whitelistMode)
            .putInt(KEY_AUTO_MAX, next.autoTestMax)
            .putLong(KEY_AUTO_DELAY, next.autoTestDelayMs)
            .putString(KEY_PENDING_CODE, next.pendingCode)
            .putString(KEY_BATCH_SAMPLES, next.batchSamples)
            .putInt(KEY_BATCH_MAX, next.batchMax)
            .putLong(KEY_BATCH_INTERVAL, next.batchIntervalMs)
            .putBoolean(KEY_BATCH_STOP_ON_HIT, next.batchStopOnHit)
            .apply()
        _state.value = next
    }

    private fun read(): Settings = Settings(
        notificationsEnabled = prefs.getBoolean(KEY_NOTIFY, true),
        scoreThreshold = prefs.getInt(KEY_THRESHOLD, 5).coerceIn(1, 20),
        detectOwnApp = prefs.getBoolean(KEY_OWN_APP, true),
        keepDays = prefs.getInt(KEY_KEEP_DAYS, 14).coerceIn(1, 365),
        ocrEnabled = prefs.getBoolean(KEY_OCR, false),
        keywordList = prefs.getString(KEY_KEYWORDS, Settings.DEFAULT_KEYWORDS)
            ?: Settings.DEFAULT_KEYWORDS,
        debounceMs = prefs.getLong(KEY_DEBOUNCE, 1200L).coerceIn(0L, 10_000L),
        whitelistMode = prefs.getBoolean(KEY_WHITELIST, false),
        autoTestMax = prefs.getInt(KEY_AUTO_MAX, 10).coerceIn(1, 200),
        autoTestDelayMs = prefs.getLong(KEY_AUTO_DELAY, 800L).coerceIn(0L, 5000L),
        pendingCode = prefs.getString(KEY_PENDING_CODE, "") ?: "",
        batchSamples = prefs.getString(KEY_BATCH_SAMPLES, "") ?: "",
        batchMax = prefs.getInt(KEY_BATCH_MAX, 50).coerceIn(1, 5000),
        batchIntervalMs = prefs.getLong(KEY_BATCH_INTERVAL, 1200L).coerceIn(200L, 60_000L),
        batchStopOnHit = prefs.getBoolean(KEY_BATCH_STOP_ON_HIT, false),
    )

    private companion object {
        const val PREF_NAME = "coupon_watch_settings"
        const val KEY_NOTIFY = "notify"
        const val KEY_THRESHOLD = "threshold"
        const val KEY_OWN_APP = "own_app"
        const val KEY_KEEP_DAYS = "keep_days"
        const val KEY_OCR = "ocr"
        const val KEY_KEYWORDS = "keywords"
        const val KEY_DEBOUNCE = "debounce"
        const val KEY_WHITELIST = "whitelist_mode"
        const val KEY_AUTO_MAX = "auto_max"
        const val KEY_AUTO_DELAY = "auto_delay"
        const val KEY_PENDING_CODE = "pending_code"
        const val KEY_BATCH_SAMPLES = "batch_samples"
        const val KEY_BATCH_MAX = "batch_max"
        const val KEY_BATCH_INTERVAL = "batch_interval"
        const val KEY_BATCH_STOP_ON_HIT = "batch_stop_on_hit"
    }
}
