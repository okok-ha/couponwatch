package com.couponwatch.detector.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.couponwatch.detector.R
import com.couponwatch.detector.couponApp
import com.couponwatch.detector.service.CouponAccessibilityService
import com.couponwatch.detector.service.MonitorState
import com.couponwatch.detector.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 悬浮球：可拖动的小圆球 + 可展开的控制面板。
 *
 * 实现要点：
 * - 用 [WindowManager] + `TYPE_APPLICATION_OVERLAY`（API 26+ 唯一允许的类型），
 *   需要用户授予「显示在其他应用上层」权限；
 * - 以「前台服务 + specialUse 类型」常驻，避免被系统回收（Android 14+ 强制要求声明类型）；
 * - 圆球拖动用 `LayoutParams.x/y` 直接更新，不做动画，保证跟手；
 * - 面板与圆球是两个独立窗口，收起即 `removeView`，不常驻内存。
 */
class FloatingBallService : Service() {

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var panelView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "缺少悬浮窗权限，服务退出")
            stopSelf()
            return
        }
        showBall()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        removePanel()
        ballView?.let { runCatching { windowManager.removeView(it) } }
        ballView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ //
    // 前台通知
    // ------------------------------------------------------------------ //

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "悬浮球",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "保持悬浮球常驻" }
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("兑换码界面检测助手")
            .setContentText("悬浮球运行中，点击可打开应用")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------------ //
    // 圆球
    // ------------------------------------------------------------------ //

    private fun showBall() {
        val size = dp(56)
        val view = TextView(this).apply {
            text = "检"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(statusColor())
            }
            elevation = dp(6).toFloat()
            contentDescription = "兑换码检测悬浮球"
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }

        attachDrag(view, params)
        windowManager.addView(view, params)
        ballView = view
        ballParams = params
        updateBallLook()
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        val slop = dp(6)

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) dragged = true
                    if (dragged) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) togglePanel()
                    true
                }

                else -> false
            }
        }
    }

    private fun statusColor(): Int = when (MonitorState.status.value) {
        MonitorState.Status.RUNNING -> 0xFF2F6FED.toInt()
        MonitorState.Status.PAUSED -> 0xFFE0A106.toInt()
        MonitorState.Status.STOPPED -> 0xFF8A94A6.toInt()
    }

    private fun updateBallLook() {
        val view = ballView as? TextView ?: return
        val drawable = view.background as? GradientDrawable
        drawable?.setColor(statusColor())
        view.text = when (MonitorState.status.value) {
            MonitorState.Status.RUNNING -> "检"
            MonitorState.Status.PAUSED -> "停"
            MonitorState.Status.STOPPED -> "止"
        }
    }

    // ------------------------------------------------------------------ //
    // 控制面板
    // ------------------------------------------------------------------ //

    private fun togglePanel() {
        if (panelView != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xF2FFFFFF.toInt())
            }
            elevation = dp(8).toFloat()
        }

        val title = TextView(this).apply {
            text = "兑换码界面检测助手"
            setTextColor(0xFF1F2733.toInt())
            textSize = 14f
            setPadding(0, 0, 0, dp(6))
        }
        val statusText = TextView(this).apply {
            text = buildStatusText()
            setTextColor(0xFF46505F.toInt())
            textSize = 12f
        }

        val fillInfo = TextView(this).apply {
            text = probeText()
            setTextColor(0xFF2F6FED.toInt())
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }

        val fillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fillRow.addView(
            Button(this).apply {
                text = "填入兑换码"
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    val service = CouponAccessibilityService.instance
                    if (service == null) {
                        Toast.makeText(context, "无障碍服务未连接", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val code = couponApp.settings.current().pendingCode
                    val result = service.fillForeground(code)
                    Toast.makeText(context, result.label, Toast.LENGTH_SHORT).show()
                    fillInfo.text = probeText()
                    statusText.text = buildStatusText()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        fillRow.addView(
            Button(this).apply {
                text = "刷新目标"
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { fillInfo.text = probeText() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        // ---- 批量试码区 ----
        val batchInfo = TextView(this).apply {
            text = batchText()
            setTextColor(0xFF7A3E00.toInt())
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        val batchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }

        fun addBatchButton(label: String, color: Int, onClick: () -> Unit) {
            batchRow.addView(
                Button(this).apply {
                    text = label
                    setTextColor(color)
                    textSize = 13f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener {
                        onClick()
                        batchInfo.text = batchText()
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                },
            )
        }

        addBatchButton("批量试码", 0xFF1E7A3C.toInt()) {
            val service = CouponAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this@FloatingBallService, "无障碍服务未连接", Toast.LENGTH_SHORT).show()
                return@addBatchButton
            }
            val cfg = couponApp.settings.current()
            val samples = cfg.batchSamples.split('\n', '，', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf(cfg.pendingCode).filter { it.isNotBlank() } }
            val msg = service.startBatch(
                samples = samples,
                maxAttempts = cfg.batchMax,
                intervalMs = cfg.batchIntervalMs,
                stopOnHit = cfg.batchStopOnHit,
            )
            Toast.makeText(this@FloatingBallService, msg, Toast.LENGTH_SHORT).show()
        }
        addBatchButton("暂停", 0xFF96631A.toInt()) {
            CouponAccessibilityService.instance?.pauseBatch()
        }
        addBatchButton("停止", 0xFFA52A2A.toInt()) {
            CouponAccessibilityService.instance?.stopBatch()
        }

        fun addButton(label: String, color: Int, onClick: () -> Unit) {
            buttonRow.addView(
                Button(this).apply {
                    text = label
                    setTextColor(color)
                    textSize = 13f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    setOnClickListener {
                        onClick()
                        statusText.text = buildStatusText()
                        updateBallLook()
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                },
            )
        }

        addButton("启动", 0xFF1E7A3C.toInt()) { MonitorState.start() }
        addButton("暂停/继续", 0xFF96631A.toInt()) { MonitorState.togglePause() }
        addButton("停止", 0xFFA52A2A.toInt()) { MonitorState.stop() }

        val closeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        closeRow.addView(
            Button(this).apply {
                text = "收起面板"
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { removePanel() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        closeRow.addView(
            Button(this).apply {
                text = "关闭悬浮球"
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    MonitorState.stop()
                    stopSelf()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        panel.addView(title)
        panel.addView(statusText)
        panel.addView(fillInfo)
        panel.addView(fillRow)
        panel.addView(batchInfo)
        panel.addView(batchRow)
        panel.addView(buttonRow)
        panel.addView(closeRow)

        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        runCatching { windowManager.addView(panel, params) }
        panelView = panel
        panelParams = params
    }

    private fun removePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelParams = null
    }

    private fun probeText(): String {
        val target = CouponAccessibilityService.instance?.probeFillTarget()
            ?: return "填入目标：未找到输入框（先在目标 App 里点开兑换框）"
        val hint = target.hint.ifBlank { "输入框" }
        return "填入目标：" + target.appLabel + " · " + hint
    }

    private fun batchText(): String {
        val s = CouponAccessibilityService.batchState.value
        if (!s.running && s.attempt == 0) return "批量试码：未开始（进度在应用首页设置样例）"
        val head = if (s.running) (if (s.paused) "已暂停" else "运行中") else "已结束"
        val tail = s.message.ifBlank { s.lastVerdict }
        return "批量试码·$head ${s.progressLine()}\n目标：${s.targetApp.ifBlank { "—" }} · $tail"
    }

    private fun buildStatusText(): String {        val status = MonitorState.status.value.label
        val connected = if (MonitorState.serviceConnected.value) "无障碍已连接" else "无障碍未连接"
        val count = MonitorState.detections.value
        val last = MonitorState.lastSummary.value.ifBlank { "—" }
        return "$status · $connected · 已识别 $count 次\n最近：$last"
    }

    private fun observeState() {
        scope.launch {
            MonitorState.status.collect { updateBallLook() }
        }
        scope.launch {
            MonitorState.detections.collect {
                // 面板打开时同步刷新文本
                panelView?.let { view ->
                    (view as? LinearLayout)?.getChildAt(1)?.let { child ->
                        (child as? TextView)?.text = buildStatusText()
                    }
                }
            }
        }
        scope.launch {
            CouponAccessibilityService.batchState.collect { st ->
                val panel = panelView as? LinearLayout ?: return@collect
                // 面板结构：0 标题 / 1 状态 / 2 填入目标 / 3 填入行 / 4 批量信息
                (panel.getChildAt(4) as? TextView)?.text = batchText()
                if (st.hits > 0) {
                    updateBallLook()
                }
            }
        }
    }

    // ------------------------------------------------------------------ //

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        private const val TAG = "FloatingBall"
        private const val CHANNEL_ID = "floating_ball"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }
    }
}
