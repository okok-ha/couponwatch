package com.couponwatch.detector.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.couponwatch.detector.BuildConfig
import com.couponwatch.detector.couponApp
import com.couponwatch.detector.format.CodeEnumerator
import com.couponwatch.detector.notify.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 无障碍服务：唯一的事件入口。
 *
 * 职责边界很窄——只做三件事：
 * 1. 接收窗口类事件（事件类型在 res/xml/accessibility_service_config.xml 中声明）；
 * 2. 交给 [CouponDetectionEngine] 判定；
 * 3. 维护过滤列表缓存与能力自检信息。
 *
 * 不做的事：不注入手势、不模拟点击、不代填、不提交、不读取密码框、不截屏（除非显式开启 OCR）。
 */
class CouponAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: CouponDetectionEngine

    /** 自动测试执行器；仅在无障碍服务连接后可用 */
    var autoTester: AutoTester? = null
        private set

    /** 跨应用填入器 */
    var filler: CrossAppFiller? = null
        private set

    /** 跨应用批量试码执行器（生成 → 填入 → 提交 → 判结果 → 换下一条） */
    var batchRunner: BatchTrialRunner? = null
        private set

    override fun onCreate() {
        super.onCreate()
        val app = couponApp
        engine = CouponDetectionEngine(
            context = this,
            scope = scope,
            settings = app.settings,
            logs = app.logRepository,
        )
        // 把引擎统计镜像到伴生对象，界面在服务未连接时也能安全读取
        scope.launch {
            engine.stats.collect { _liveStats.value = it }
        }

        filler = CrossAppFiller(this, scope, app.logRepository)
        autoTester = AutoTester(this, scope, app.logRepository)
        autoTester?.let { tester ->
            scope.launch { tester.state.collect { _autoTestState.value = it } }
        }

        batchRunner = BatchTrialRunner(this, scope, app.logRepository)
        batchRunner?.let { runner ->
            scope.launch { runner.state.collect { _batchState.value = it } }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val app = couponApp

        engine.updateCapability(capabilityHeadline())

        MonitorState.setServiceConnected(true)

        // 过滤列表 + 白/黑名单模式实时同步到引擎
        scope.launch {
            combine(
                app.filterRepository.observeAll(),
                app.settings.state,
            ) { list, settings ->
                list.filter { it.blocked }.map { it.packageName }.toSet() to settings.whitelistMode
            }.collect { (selected, whitelist) ->
                engine.updateFilter(selected, whitelist)
            }
        }

        // 启动时按保留天数清理旧日志
        scope.launch {
            try {
                app.logRepository.prune(app.settings.current().keepDays)
            } catch (e: Exception) {
                Log.w(TAG, "prune failed", e)
            }
        }

        // 定时兜底：处理"弹窗静默消失、没有后续事件"的情况
        scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                try {
                    engine.tick()
                } catch (e: Exception) {
                    Log.w(TAG, "tick failed", e)
                }
            }
        }

        Log.i(TAG, "connected: " + capabilityHeadline())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            engine.handle(this, event)
        } catch (e: Exception) {
            // 任何异常都不能让服务崩溃，否则系统会反复重启它
            Log.w(TAG, "handle event failed", e)
        }
    }

    override fun onInterrupt() {
        try {
            engine.reset()
        } catch (e: Exception) {
            Log.w(TAG, "reset failed", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        MonitorState.setServiceConnected(false)
        try {
            engine.reset()
        } catch (e: Exception) {
            Log.w(TAG, "reset on unbind failed", e)
        }
        scope.cancel()
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------------ //
    // 能力自检
    // ------------------------------------------------------------------ //

    private fun capabilityHeadline(): String {
        val info = serviceInfo ?: return "serviceInfo 未就绪"
        return WindowScanner.describeEventMask(info.eventTypes)
    }

    /** 供"自检"页面展示的逐项能力报告。 */
    fun capabilityReport(): List<String> {
        val lines = ArrayList<String>(16)
        lines.add("系统版本：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        lines.add("设备：${Build.MANUFACTURER} ${Build.MODEL}")

        val info: AccessibilityServiceInfo? = serviceInfo
        if (info == null) {
            lines.add("serviceInfo：未就绪（服务可能刚启动，请稍后刷新）")
            return lines
        }

        lines.add("订阅事件：${WindowScanner.describeEventMask(info.eventTypes)}")
        lines.add("生效标志：${WindowScanner.describeFlags(info.flags)}")

        val caps = info.capabilities
        val canContent =
            caps and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
        val canScreenshot =
            caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
        val hasWindowsFlag =
            info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0

        lines.add("可读取窗口内容：$canContent")
        lines.add("交互窗口标志：$hasWindowsFlag")
        lines.add("可截屏（OCR 前置能力）：$canScreenshot")
        lines.add("当前可见窗口数：${probeWindowCount()}")
        lines.add("通知权限：${NotificationHelper.canPost(this)}")
        lines.add("OCR 模块：${if (BuildConfig.OCR_ENABLED) "已编译（full 变体）" else "未编译（lite 变体）"}")
        lines.add("事件节流：${CouponDetectionEngine.MIN_SCAN_INTERVAL_MS} ms／应用")
        lines.add("弹窗判定超时：${CouponDetectionEngine.ACTIVE_TTL_MS} ms")
        return lines
    }

    /** 探测前台应用的输入框，供悬浮球展示"将填入哪里"。 */
    fun probeFillTarget(): CrossAppFiller.Target? = filler?.probe()

    /** 把用户提供的兑换码填进前台应用的输入框（单次，不提交）。 */
    fun fillForeground(code: String): CrossAppFiller.Result =
        filler?.fill(code)?.first ?: CrossAppFiller.Result.NO_TARGET

    // ------------------------------------------------------------------ //
    // 批量试码：生成 → 填入 → 提交 → 判结果
    // ------------------------------------------------------------------ //

    /**
     * 启动一轮批量试码。
     *
     * @param samples 用户给的真实样例（换行分隔），用来归纳规律
     * @param maxAttempts 本轮最多提交多少条
     * @param intervalMs 两条之间的基础间隔（会叠加抖动）
     * @param stopOnHit 命中一条就停
     * @param lockPackage 锁定目标包名；空字符串表示锁定第一次定位到的前台应用
     * @param strategy 生成策略；传 null 表示按候选空间自动选（空间大→随机，空间小→顺序）
     */
    fun startBatch(
        samples: List<String>,
        maxAttempts: Int,
        intervalMs: Long,
        stopOnHit: Boolean,
        strategy: CodeEnumerator.Strategy? = null,
        lockPackage: String = "",
    ): String {
        val runner = batchRunner ?: return "无障碍服务未就绪"
        if (runner.isRunning()) return "批量试码已在运行中"
        val resolved = strategy
            ?: CodeEnumerator.plan(samples)?.let { CodeEnumerator.autoStrategy(it) }
            ?: CodeEnumerator.Strategy.SEQUENTIAL
        val config = BatchTrialRunner.Config(
            maxAttempts = maxAttempts,
            intervalMs = intervalMs,
            stopOnHit = stopOnHit,
            strategy = resolved,
            lockPackage = lockPackage,
        )
        return runner.start(samples, config)
    }

    fun pauseBatch(): Boolean = batchRunner?.let { it.pause(); true } ?: false

    fun resumeBatch(): Boolean = batchRunner?.let { it.resume(); true } ?: false

    fun stopBatch(): Boolean = batchRunner?.let { it.stop(); true } ?: false

    fun isBatchRunning(): Boolean = batchRunner?.isRunning() == true

    /** 直接探测 getWindows() 是否真的返回窗口，这是"能否识别跨应用窗口"的硬指标。 */
    fun probeWindowCount(): Int = try {
        windows?.size ?: 0
    } catch (e: Exception) {
        -1
    }

    companion object {
        private const val TAG = "CouponWatchService"
        private const val TICK_INTERVAL_MS = 2000L

        @Volatile
        var instance: CouponAccessibilityService? = null
            private set

        private val _autoTestState = MutableStateFlow(AutoTestState())

        /** 自动测试状态（界面安全订阅） */
        val autoTestState: StateFlow<AutoTestState> = _autoTestState.asStateFlow()

        private val _batchState = MutableStateFlow(BatchState())

        /** 批量试码状态（界面与悬浮球安全订阅） */
        val batchState: StateFlow<BatchState> = _batchState.asStateFlow()

        private val _liveStats = MutableStateFlow(EngineStats())

        /** 界面可安全订阅的统计流（服务未连接时为空数据）。 */
        val liveStats: StateFlow<EngineStats> = _liveStats.asStateFlow()

        fun isConnected(): Boolean = instance != null

        fun capabilityReportOrEmpty(): List<String> = instance?.capabilityReport() ?: emptyList()
    }
}
