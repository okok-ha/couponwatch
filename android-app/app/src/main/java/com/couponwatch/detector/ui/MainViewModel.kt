package com.couponwatch.detector.ui

import android.app.Application
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.couponwatch.detector.CouponWatchApp
import com.couponwatch.detector.data.db.AppFilterEntity
import com.couponwatch.detector.data.db.DetectionLogEntity
import com.couponwatch.detector.data.db.FormatRuleEntity
import com.couponwatch.detector.data.db.PackageStat
import com.couponwatch.detector.format.CodeFormat
import com.couponwatch.detector.format.CodeFormatAnalyzer
import com.couponwatch.detector.format.CodeEnumerator
import com.couponwatch.detector.service.BatchState
import com.couponwatch.detector.overlay.FloatingBallService
import com.couponwatch.detector.service.AutoTestState
import com.couponwatch.detector.service.MonitorState
import com.couponwatch.detector.data.prefs.Settings
import com.couponwatch.detector.service.CouponAccessibilityService
import com.couponwatch.detector.service.EngineStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 已安装且可启动的应用（用于过滤列表）。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: CouponWatchApp = application as CouponWatchApp

    val settings: StateFlow<Settings> = app.settings.state

    val logs: StateFlow<List<DetectionLogEntity>> = app.logRepository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todayCount: StateFlow<Int> = app.logRepository.observeTodayCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val todayAppear: StateFlow<Int> = app.logRepository.observeTodayAppearCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val packageStats: StateFlow<List<PackageStat>> = app.logRepository.observePackageStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filters: StateFlow<List<AppFilterEntity>> = app.filterRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engineStats: StateFlow<EngineStats> = CouponAccessibilityService.liveStats

    // ---------------- 格式规则 ----------------

    val formatRules: StateFlow<List<FormatRuleEntity>> = app.formatRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeRule: StateFlow<FormatRuleEntity?> = app.formatRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _formatPreview = MutableStateFlow<CodeFormat?>(null)
    val formatPreview: StateFlow<CodeFormat?> = _formatPreview

    private val _formatMessage = MutableStateFlow("")
    val formatMessage: StateFlow<String> = _formatMessage

    // ---------------- 悬浮球与监测状态 ----------------

    val monitorStatus: StateFlow<MonitorState.Status> = MonitorState.status
    val monitorDetections: StateFlow<Int> = MonitorState.detections
    val autoTestState: StateFlow<AutoTestState> = CouponAccessibilityService.autoTestState

    private val _overlayAllowed = MutableStateFlow(false)
    val overlayAllowed: StateFlow<Boolean> = _overlayAllowed

    fun refreshOverlayPermission(context: Context) {
        _overlayAllowed.value = android.provider.Settings.canDrawOverlays(context)
    }

    fun startFloatingBall(context: Context) = FloatingBallService.start(context)

    fun stopFloatingBall(context: Context) = FloatingBallService.stop(context)

    fun setMonitorStatus(status: MonitorState.Status) = MonitorState.setStatus(status)

    fun toggleMonitorPause() = MonitorState.togglePause()

    fun setPendingCode(code: String) =
        app.settings.update { it.copy(pendingCode = code) }

    fun setWhitelistMode(enabled: Boolean) =
        app.settings.update { it.copy(whitelistMode = enabled) }

    fun setAutoTestMax(value: Int) =
        app.settings.update { it.copy(autoTestMax = value.coerceIn(1, 200)) }

    // ---------------- 批量试码 ----------------

    val batchState: StateFlow<BatchState> = CouponAccessibilityService.batchState

    fun setBatchSamples(text: String) =
        app.settings.update { it.copy(batchSamples = text) }

    fun setBatchMax(value: Int) =
        app.settings.update { it.copy(batchMax = value.coerceIn(1, 5000)) }

    fun setBatchInterval(ms: Long) =
        app.settings.update { it.copy(batchIntervalMs = ms.coerceIn(200L, 60_000L)) }

    fun setBatchStopOnHit(enabled: Boolean) =
        app.settings.update { it.copy(batchStopOnHit = enabled) }

    fun samplesOf(text: String): List<String> =
        text.split('\n', ',', '，', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** 只做预览：归纳规律并返回前 count 条候选，不提交任何东西。 */
    fun previewCandidates(text: String, count: Int): String {
        val samples = samplesOf(text)
        if (samples.isEmpty()) return "请先输入至少一条样例"
        val plan = CodeEnumerator.plan(samples) ?: return "样例无法归纳"
        val strategy = CodeEnumerator.autoStrategy(plan)
        val codes = CodeEnumerator.generate(plan, count, strategy)
        val label = if (strategy == CodeEnumerator.Strategy.RANDOM) "随机采样" else "顺序推进"
        return plan.describe() + "\n生成策略：" + label +
            "\n\n前 ${codes.size} 条：\n" + codes.joinToString("\n")
    }

    fun startBatch(text: String): String {
        val service = CouponAccessibilityService.instance ?: return "无障碍服务未连接"
        val cfg = app.settings.current()
        val samples = samplesOf(text)
        if (samples.isEmpty()) return "请先输入样例码"
        return service.startBatch(
            samples = samples,
            maxAttempts = cfg.batchMax,
            intervalMs = cfg.batchIntervalMs,
            stopOnHit = cfg.batchStopOnHit,
        )
    }

    fun pauseBatch() {
        CouponAccessibilityService.instance?.pauseBatch()
    }

    fun resumeBatch() {
        CouponAccessibilityService.instance?.resumeBatch()
    }

    fun stopBatch() {
        CouponAccessibilityService.instance?.stopBatch()
    }

    fun analyzeFormat(samplesText: String, name: String) {
        val samples = samplesText.split('\n', ',', '，', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (samples.isEmpty()) {
            _formatMessage.value = "请先输入至少一个样例"
            _formatPreview.value = null
            return
        }
        try {
            val format = CodeFormatAnalyzer.analyze(samples, name.ifBlank { "未命名规则" })
            _formatPreview.value = format
            val rate = CodeFormatAnalyzer.matchRate(samples, format.regex)
            _formatMessage.value = "分析完成，${samples.size} 个样例自匹配率 " +
                (rate * 100).toInt() + "%"
        } catch (e: Exception) {
            _formatPreview.value = null
            _formatMessage.value = "分析失败：" + (e.message ?: "未知错误")
        }
    }

    fun saveFormat() {
        val format = _formatPreview.value ?: return
        viewModelScope.launch {
            val entity = FormatRuleEntity(
                name = format.name,
                regex = format.regex,
                samples = format.samples.joinToString("\n"),
                lengthMin = format.lengthMin,
                lengthMax = format.lengthMax,
                prefix = format.prefix,
                suffix = format.suffix,
                separators = format.separators,
                charset = format.charset,
                mode = format.mode,
                active = false,
            )
            val id = app.formatRepository.save(entity)
            app.formatRepository.activate(id)
            _formatMessage.value = "已保存并启用：" + format.name
        }
    }

    fun activateRule(id: Long) {
        viewModelScope.launch {
            app.formatRepository.activate(id)
            _message.value = "已切换规则"
        }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch {
            app.formatRepository.delete(id)
            _message.value = "已删除规则"
        }
    }

    /** 无障碍服务是否已在系统设置中启用（与进程内实例无关，更可靠）。 */
    private val _serviceEnabled = MutableStateFlow(false)
    val serviceEnabled: StateFlow<Boolean> = _serviceEnabled

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    private val _loadingApps = MutableStateFlow(false)
    val loadingApps: StateFlow<Boolean> = _loadingApps

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() {
        _message.value = null
    }

    fun refreshServiceState(context: Context) {
        _serviceEnabled.value = isAccessibilityServiceEnabled(context)
    }

    fun loadInstalledApps() {
        if (_loadingApps.value) return
        _loadingApps.value = true
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { queryLaunchableApps() }
            _installedApps.value = apps
            _loadingApps.value = false
        }
    }

    fun setBlocked(packageName: String, appLabel: String, blocked: Boolean) {
        viewModelScope.launch {
            app.filterRepository.setBlocked(packageName, appLabel, blocked)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) =
        app.settings.update { it.copy(notificationsEnabled = enabled) }

    fun setDetectOwnApp(enabled: Boolean) =
        app.settings.update { it.copy(detectOwnApp = enabled) }

    fun setThreshold(value: Int) =
        app.settings.update { it.copy(scoreThreshold = value.coerceIn(1, 20)) }

    fun setKeepDays(days: Int) =
        app.settings.update { it.copy(keepDays = days.coerceIn(1, 365)) }

    fun setKeywordList(text: String) =
        app.settings.update { it.copy(keywordList = text) }

    fun setOcrEnabled(enabled: Boolean) =
        app.settings.update { it.copy(ocrEnabled = enabled) }

    fun clearLogs() {
        viewModelScope.launch {
            app.logRepository.clearAll()
            _message.value = "已清空全部检测记录"
        }
    }

    fun pruneOldLogs() {
        viewModelScope.launch {
            val removed = app.logRepository.prune(app.settings.current().keepDays)
            _message.value = "已按 ${app.settings.current().keepDays} 天保留策略清理 $removed 条"
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            try {
                val csv = app.logRepository.exportCsv()
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM，Excel 友好
                        out.write(csv.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
                _message.value = "已导出 CSV"
            } catch (e: Exception) {
                _message.value = "导出失败：${e.message}"
            }
        }
    }

    fun postMessage(text: String) {
        _message.value = text
    }

    // ------------------------------------------------------------------ //

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val enabled = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabled.any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo
            serviceInfo != null && serviceInfo.packageName == context.packageName
        }
    }

    @Suppress("DEPRECATION")
    private fun queryLaunchableApps(): List<InstalledApp> {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            emptyList()
        }
        return resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                InstalledApp(pkg, info.loadLabel(pm).toString())
            }
            .filter { it.packageName != app.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
