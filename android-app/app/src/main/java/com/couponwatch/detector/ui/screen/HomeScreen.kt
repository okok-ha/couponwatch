package com.couponwatch.detector.ui.screen

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.couponwatch.detector.notify.NotificationHelper
import com.couponwatch.detector.service.MonitorState
import com.couponwatch.detector.ui.LabeledSwitch
import com.couponwatch.detector.ui.MainViewModel
import com.couponwatch.detector.ui.SectionCard
import com.couponwatch.detector.ui.StatTile
import com.couponwatch.detector.ui.StatusPill
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onOpenMockPage: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val serviceEnabled by vm.serviceEnabled.collectAsState()
    val todayCount by vm.todayCount.collectAsState()
    val todayAppear by vm.todayAppear.collectAsState()
    val stats by vm.engineStats.collectAsState()

    var keywordsDraft by remember(settings.keywordList) { mutableStateOf(settings.keywordList) }
    var notificationGranted by remember {
        mutableStateOf(NotificationHelper.canPost(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationGranted = granted
        vm.postMessage(if (granted) "通知权限已授予" else "未授予通知权限，检测仍会记录到本地日志")
    }

    // 服务开关状态来自系统设置，轮询保证从设置页返回后立即刷新
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshServiceState(context)
            delay(1500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "兑换弹窗检测",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "仅在本机识别「疑似兑换弹窗」的出现与消失，不代填、不提交、不联网。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard(
            title = "服务状态",
            subtitle = "需要在系统设置中手动开启无障碍服务，应用无法自行开启。",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusPill(
                    text = if (serviceEnabled) "服务已开启" else "服务未开启",
                    ok = serviceEnabled,
                )
                StatusPill(
                    text = if (notificationGranted) "通知可用" else "通知不可用",
                    ok = notificationGranted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                ) { Text(if (serviceEnabled) "管理无障碍服务" else "去开启无障碍服务") }

                if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(
                        onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    ) { Text("授予通知权限") }
                }
            }
            if (serviceEnabled) {
                Text(
                    "已接收事件 ${stats.events} 次 · 扫描 ${stats.scans} 次 · " +
                        "窗口事件 ${stats.windowStateEvents + stats.windowsChangedEvents} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (stats.lastEvent.isNotBlank()) {
                    Text(
                        "最近事件：${stats.lastEvent} · ${stats.lastPackage.ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard(
            title = "悬浮球",
            subtitle = "需要「显示在其他应用上层」权限；点击圆球展开面板，可拖动。",
        ) {
            val overlayAllowed by vm.overlayAllowed.collectAsState()
            val monitorStatus by vm.monitorStatus.collectAsState()
            val monitorCount by vm.monitorDetections.collectAsState()
            val autoState by vm.autoTestState.collectAsState()

            val overlayLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult(),
            ) { vm.refreshOverlayPermission(context) }

            LaunchedEffect(Unit) { vm.refreshOverlayPermission(context) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    text = if (overlayAllowed) "悬浮窗已授权" else "悬浮窗未授权",
                    ok = overlayAllowed,
                )
                StatusPill(text = monitorStatus.label, ok = monitorStatus == MonitorState.Status.RUNNING)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.packageName),
                    )
                    overlayLauncher.launch(intent)
                }) { Text("授权悬浮窗") }

                Button(
                    enabled = overlayAllowed,
                    onClick = { vm.startFloatingBall(context) },
                ) { Text("启动悬浮球") }

                OutlinedButton(onClick = { vm.stopFloatingBall(context) }) { Text("关闭") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.setMonitorStatus(MonitorState.Status.RUNNING) }) { Text("启动监测") }
                OutlinedButton(onClick = { vm.toggleMonitorPause() }) { Text("暂停/继续") }
                OutlinedButton(onClick = { vm.setMonitorStatus(MonitorState.Status.STOPPED) }) { Text("停止") }
            }

            Text(
                "已识别 $monitorCount 次 · 自动测试 " +
                    (if (autoState.running) "${autoState.index}/${autoState.total}" else "未运行") +
                    " · 成功 ${autoState.success} / 失败 ${autoState.failure}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "跨应用填入（手动）",
            subtitle = "把某一个码填进其他 App 的输入框，不自动点提交；批量自动提交见下一节。",
        ) {
            var draft by remember(settings.pendingCode) { mutableStateOf(settings.pendingCode) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("待填入的兑换码") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.setPendingCode(draft.trim())
                    vm.postMessage("已保存待填入内容")
                }) { Text("保存") }

                OutlinedButton(onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java)
                        ?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.text?.toString().orEmpty().trim()
                    if (clip.isEmpty()) {
                        vm.postMessage("剪贴板为空")
                    } else {
                        draft = clip
                        vm.setPendingCode(clip)
                        vm.postMessage("已从剪贴板读取")
                    }
                }) { Text("读取剪贴板") }
            }
            Text(
                "用法：在目标 App 里点开兑换框 → 点悬浮球 → 点「填入兑换码」。\n" +
                    "只填这一个码，不点提交；是否兑换由你自己点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "批量试码（自动提交）",
            subtitle = "给几条真实样例 → 按位归纳规律生成候选 → 逐条填入前台 App 并点提交 → " +
                "无效自动清空换下一条，命中记录完整码。",
        ) {
            val batch by vm.batchState.collectAsState()
            var samplesDraft by remember(settings.batchSamples) {
                mutableStateOf(settings.batchSamples)
            }
            var preview by remember { mutableStateOf("") }

            OutlinedTextField(
                value = samplesDraft,
                onValueChange = {
                    samplesDraft = it
                    vm.setBatchSamples(it)
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("样例码（一行一条，2 条以上才能拆出可变位）") },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    preview = vm.previewCandidates(samplesDraft, 10)
                }) { Text("预览规律") }
                OutlinedButton(onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java)
                        ?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.text?.toString().orEmpty().trim()
                    if (clip.isEmpty()) {
                        vm.postMessage("剪贴板为空")
                    } else {
                        samplesDraft = clip
                        vm.setBatchSamples(clip)
                        vm.postMessage("已从剪贴板读取样例")
                    }
                }) { Text("读剪贴板") }
            }

            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("本轮上限：${settings.batchMax} 条", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = settings.batchMax.toFloat(),
                onValueChange = { vm.setBatchMax(it.toInt()) },
                valueRange = 1f..500f,
            )

            Text(
                "提交间隔：${settings.batchIntervalMs} ms（会叠加 ±35% 抖动）",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = settings.batchIntervalMs.toFloat(),
                onValueChange = { vm.setBatchInterval(it.toLong()) },
                valueRange = 200f..8000f,
            )

            LabeledSwitch(
                title = "命中即停",
                description = "拿到一个有效码就结束本轮，避免继续消耗",
                checked = settings.batchStopOnHit,
                onCheckedChange = { vm.setBatchStopOnHit(it) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.postMessage(vm.startBatch(samplesDraft))
                }) { Text("开始批量") }
                OutlinedButton(onClick = { vm.pauseBatch() }) { Text("暂停") }
                OutlinedButton(onClick = { vm.resumeBatch() }) { Text("继续") }
                OutlinedButton(onClick = { vm.stopBatch() }) { Text("停止") }
            }

            Text(
                if (batch.running || batch.attempt > 0) {
                    "状态：" + (if (batch.running) (if (batch.paused) "已暂停" else "运行中") else "已结束") +
                        "\n" + batch.progressLine() +
                        "\n目标：" + batch.targetApp.ifBlank { "—" } +
                        "\n最近：" + batch.lastCode + " → " + batch.lastVerdict +
                        (if (batch.hitsList.isEmpty()) "" else
                            "\n命中：" + batch.hitsList.joinToString("，"))
                } else {
                    "尚未开始。开始前先在目标 App 里点开兑换框，让输入框处于前台可聚焦状态。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "今日统计") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile(label = "记录条数", value = todayCount.toString())
                StatTile(label = "弹窗出现", value = todayAppear.toString())
                StatTile(label = "实时评分", value = stats.lastScore.toString())
            }
        }

        SectionCard(title = "检测参数", subtitle = "评分达到阈值才记为「疑似兑换弹窗」。") {
            LabeledSwitch(
                title = "发送通知",
                description = "检测到弹窗时提醒（同一应用 10 秒内最多一条）",
                checked = settings.notificationsEnabled,
                onCheckedChange = { enabled ->
                    vm.setNotificationsEnabled(enabled)
                    if (enabled && !notificationGranted &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            LabeledSwitch(
                title = "检测本应用",
                description = "内置模拟页面在本应用内，关闭后无法用它自测",
                checked = settings.detectOwnApp,
                onCheckedChange = { vm.setDetectOwnApp(it) },
            )

            Text(
                "判定阈值：${settings.scoreThreshold} 分",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = settings.scoreThreshold.toFloat(),
                onValueChange = { vm.setThreshold(it.toInt()) },
                valueRange = 1f..12f,
                steps = 10,
            )

            Text(
                "日志保留：${settings.keepDays} 天",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = settings.keepDays.toFloat(),
                onValueChange = { vm.setKeepDays(it.toInt()) },
                valueRange = 1f..90f,
                steps = 88,
            )
        }

        SectionCard(
            title = "关键词",
            subtitle = "逗号分隔。命中越多分越高，直接决定误报与漏报的平衡。",
        ) {
            OutlinedTextField(
                value = keywordsDraft,
                onValueChange = { keywordsDraft = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("关键词列表") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.setKeywordList(keywordsDraft)
                    vm.postMessage("关键词已保存")
                }) { Text("保存关键词") }

                OutlinedButton(onClick = {
                    keywordsDraft = com.couponwatch.detector.data.prefs.Settings.DEFAULT_KEYWORDS
                    vm.setKeywordList(keywordsDraft)
                    vm.postMessage("已恢复默认关键词")
                }) { Text("恢复默认") }
            }
        }

        SectionCard(title = "快捷操作") {
            Button(onClick = onOpenMockPage, modifier = Modifier.fillMaxWidth()) {
                Text("打开内置模拟页面")
            }
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                Text("查看检测日志")
            }
        }

        Text(
            "说明：格式/界面匹配只代表「看起来像兑换弹窗」，不代表任何兑换码有效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}
