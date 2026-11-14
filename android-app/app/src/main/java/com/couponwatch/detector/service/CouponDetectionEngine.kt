package com.couponwatch.detector.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.couponwatch.detector.data.db.DetectionLogEntity
import com.couponwatch.detector.data.prefs.Settings
import com.couponwatch.detector.data.prefs.SettingsStore
import com.couponwatch.detector.data.repo.LogRepository
import com.couponwatch.detector.notify.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 检测引擎：把"窗口变化事件"变成"弹窗出现 / 消失"的确定性记录。
 *
 * 状态机（每个包名独立）：
 * ```
 * 无 → [命中候选] → 记录 APPEAR → 持续刷新 lastSeen
 *                                 ├─ 再次命中同一指纹：只刷新时间
 *                                 ├─ 命中不同指纹：记录 APPEAR（新弹窗）
 *                                 └─ 未命中 / 超过 TTL：记录 DISAPPEAR
 * ```
 * 用"指纹 + TTL"而不是"收到某个关闭事件"，是因为弹窗关闭时系统未必会给出
 * 一个明确的关闭事件，超时兜底比事件驱动更可靠。
 */
class CouponDetectionEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val logs: LogRepository,
    private val scanner: WindowScanner = WindowScanner(context),
) {

    private data class Active(
        val signal: DialogSignal,
        var lastSeen: Long,
    )

    private val _stats = MutableStateFlow(EngineStats())
    val stats: StateFlow<EngineStats> = _stats.asStateFlow()

    private val active = HashMap<String, Active>()
    private val lastScanAt = HashMap<String, Long>()
    @Volatile
    private var selectedPackages: Set<String> = emptySet()

    @Volatile
    private var whitelistMode: Boolean = false

    /**
     * 过滤语义由 [whitelistMode] 决定：
     * - 黑名单模式：勾选的应用被跳过
     * - 白名单模式：只有勾选的应用被监测
     */
    fun updateFilter(packages: Set<String>, whitelist: Boolean) {
        selectedPackages = packages
        whitelistMode = whitelist
    }

    private fun shouldSkip(packageName: String): Boolean =
        if (whitelistMode) packageName !in selectedPackages else packageName in selectedPackages

    fun updateCapability(text: String) {
        _stats.update { it.copy(capability = text) }
    }

    fun handle(service: AccessibilityService, event: AccessibilityEvent) {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        // 悬浮球上的启动/暂停/停止直接决定是否记录
        if (!MonitorState.isRecording) return

        val packageName = event.packageName?.toString() ?: return
        val current = settings.current()

        if (packageName == context.packageName && !current.detectOwnApp) return
        if (shouldSkip(packageName)) return

        val now = System.currentTimeMillis()
        _stats.update {
            it.copy(
                events = it.events + 1,
                windowStateEvents = it.windowStateEvents +
                    if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) 1 else 0,
                windowsChangedEvents = it.windowsChangedEvents +
                    if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) 1 else 0,
                contentChangedEvents = it.contentChangedEvents +
                    if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) 1 else 0,
                lastEvent = eventName(type),
                lastPackage = packageName,
            )
        }

        // 同一应用的连续事件做节流，避免内容变化事件把扫描打满
        val last = lastScanAt[packageName] ?: 0L
        if (now - last < MIN_SCAN_INTERVAL_MS) {
            expireStale(now, current)
            return
        }
        lastScanAt[packageName] = now

        scan(service, packageName, current, now)
        expireStale(now, current)
    }

    /** 定时兜底：弹窗静默消失（没有新事件）时也能补一条 DISAPPEAR。 */
    fun tick() {
        expireStale(System.currentTimeMillis(), settings.current())
    }

    fun reset() {
        val current = settings.current()
        val now = System.currentTimeMillis()
        active.entries.toList().forEach { (packageName, entry) ->
            record(packageName, entry.signal, ACTION_DISAPPEAR, now, current)
        }
        active.clear()
    }

    private fun scan(
        service: AccessibilityService,
        packageName: String,
        current: Settings,
        now: Long,
    ) {
        val signals = try {
            scanner.scan(service, current.keywords(), current.scoreThreshold)
        } catch (e: Exception) {
            emptyList()
        }
        _stats.update { it.copy(scans = it.scans + 1) }

        // 本应用只在自己模拟页面内自测，避免把设置界面误判成兑换弹窗
        val best = signals.firstOrNull {
            it.score >= current.scoreThreshold &&
                (it.packageName != context.packageName || it.isOwnMockPage)
        }
        if (best == null) {
            val removed = active.remove(packageName)
            if (removed != null) {
                record(packageName, removed.signal, ACTION_DISAPPEAR, now, current)
            }
            return
        }

        _stats.update {
            it.copy(
                lastScore = best.score,
                lastKeywords = best.keywords.joinToString("/"),
                lastPackage = packageName,
            )
        }

        val existing = active[packageName]
        when {
            existing == null -> {
                active[packageName] = Active(best, now)
                record(packageName, best, ACTION_APPEAR, now, current)
            }
            existing.signal.signature() != best.signature() -> {
                // 旧弹窗消失 + 新弹窗出现，各记一条，时间线才完整
                record(packageName, existing.signal, ACTION_DISAPPEAR, now, current)
                active[packageName] = Active(best, now)
                record(packageName, best, ACTION_APPEAR, now, current)
            }
            else -> {
                existing.lastSeen = now
                active[packageName] = Active(best, existing.lastSeen)
            }
        }
    }

    private fun expireStale(now: Long, current: Settings) {
        val expired = active.filterValues { now - it.lastSeen > ACTIVE_TTL_MS }
        if (expired.isEmpty()) return
        expired.forEach { (packageName, entry) ->
            active.remove(packageName)
            record(packageName, entry.signal, ACTION_DISAPPEAR, now, current)
        }
    }

    private fun record(
        packageName: String,
        signal: DialogSignal,
        action: String,
        now: Long,
        current: Settings,
    ) {
        val entity = DetectionLogEntity(
            timestamp = now,
            // 以信号自身所属窗口为准：事件包名可能是宿主，弹窗可能属于另一个进程
            packageName = signal.packageName.ifBlank { packageName },
            appLabel = signal.appLabel,
            action = action,
            score = signal.score,
            keywords = signal.keywords.joinToString(","),
            windowTitle = signal.windowTitle,
            windowType = signal.windowType,
            eventType = _stats.value.lastEvent,
            nodeSummary = signal.summary,
            ocrUsed = false,
            controlTypes = signal.controlTypes,
        )

        _stats.update {
            if (action == ACTION_APPEAR) it.copy(appear = it.appear + 1)
            else it.copy(disappear = it.disappear + 1)
        }
        if (action == ACTION_APPEAR) {
            MonitorState.noteDetection(
                signal.appLabel + " · " + signal.score + "分 · " +
                    signal.keywords.take(2).joinToString("/")
            )
        }

        scope.launch {
            try {
                logs.insert(entity)
                if (action == ACTION_APPEAR && current.notificationsEnabled) {
                    NotificationHelper.notifyDetection(context, entity)
                }
            } catch (e: Exception) {
                // 落库失败不应影响无障碍服务本身
            }
        }
    }

    companion object {
        const val ACTION_APPEAR = "APPEAR"
        const val ACTION_DISAPPEAR = "DISAPPEAR"

        /** 同一应用两次扫描的最小间隔。 */
        const val MIN_SCAN_INTERVAL_MS = 250L

        /** 超过这个时间没有再次命中，判定弹窗已消失。 */
        const val ACTIVE_TTL_MS = 3500L

        fun eventName(type: Int): String = when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "TYPE_WINDOWS_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            else -> "TYPE_$type"
        }
    }
}
