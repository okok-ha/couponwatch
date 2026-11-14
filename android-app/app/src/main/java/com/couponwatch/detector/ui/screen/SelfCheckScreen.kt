package com.couponwatch.detector.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.couponwatch.detector.ocr.OcrEngineFactory
import com.couponwatch.detector.service.CouponAccessibilityService
import com.couponwatch.detector.ui.KeyValueRow
import com.couponwatch.detector.ui.MainViewModel
import com.couponwatch.detector.ui.SectionCard
import com.couponwatch.detector.ui.StatTile
import com.couponwatch.detector.ui.StatusPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfCheckScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val serviceEnabled by vm.serviceEnabled.collectAsState()
    val stats by vm.engineStats.collectAsState()
    val settings by vm.settings.collectAsState()

    var refreshKey by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    var connected by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        vm.refreshServiceState(context)
        connected = CouponAccessibilityService.isConnected()
        report = if (connected) CouponAccessibilityService.capabilityReportOrEmpty() else emptyList()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("能力自检") },
            actions = {
                androidx.compose.material3.IconButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(
                title = "服务连通性",
                subtitle = "用于确认无障碍服务是否真的在接收事件，而不是「以为开了」。",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        text = if (serviceEnabled) "系统已启用" else "系统未启用",
                        ok = serviceEnabled,
                    )
                    StatusPill(
                        text = if (connected) "进程内已连接" else "进程内未连接",
                        ok = connected,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }) { Text("打开无障碍设置") }
                    OutlinedButton(onClick = { refreshKey++ }) { Text("刷新") }
                }
            }

            SectionCard(title = "运行计数（本次服务启动以来）") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatTile("接收事件", stats.events.toString())
                    StatTile("扫描次数", stats.scans.toString())
                    StatTile("出现/消失", "${stats.appear}/${stats.disappear}")
                }
                HorizontalDivider()
                KeyValueRow("窗口状态事件", stats.windowStateEvents.toString())
                KeyValueRow("窗口集合事件", stats.windowsChangedEvents.toString())
                KeyValueRow("内容变化事件", stats.contentChangedEvents.toString())
                KeyValueRow("最近事件", stats.lastEvent.ifBlank { "—" })
                KeyValueRow("最近应用", stats.lastPackage.ifBlank { "—" })
                KeyValueRow("最近评分", stats.lastScore.toString())
                KeyValueRow("最近关键词", stats.lastKeywords.ifBlank { "—" })
            }

            SectionCard(
                title = "系统能力报告",
                subtitle = "直接读取 AccessibilityServiceInfo 的实际生效值。",
            ) {
                if (report.isEmpty()) {
                    Text(
                        "服务未连接，暂无数据。请先开启无障碍服务，再点右上角刷新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    report.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        val text = buildString {
                            appendLine("== 兑换弹窗检测 诊断信息 ==")
                            appendLine("serviceEnabled=$serviceEnabled connected=$connected")
                            report.forEach { appendLine(it) }
                            appendLine("events=${stats.events} scans=${stats.scans}")
                            appendLine("appear=${stats.appear} disappear=${stats.disappear}")
                            appendLine("lastEvent=${stats.lastEvent} lastPkg=${stats.lastPackage}")
                        }
                        val clipboard =
                            context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("couponwatch", text))
                        vm.postMessage("诊断信息已复制到剪贴板")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("复制诊断信息") }
            }

            SectionCard(
                title = "OCR 辅助（可选）",
                subtitle = "默认不做屏幕截取；OCR 仅作为「读不到控件文本时」的兜底手段。",
            ) {
                KeyValueRow(
                    "编译情况",
                    if (OcrEngineFactory.COMPILED) "full 变体：已包含 ML Kit（离线）"
                    else "lite 变体：未包含 OCR",
                )
                KeyValueRow("用户开关", if (settings.ocrEnabled) "已开启" else "未开启")
                Text(
                    "启用真实取词需要两步：① 构建 full 变体；② 在 accessibility_service_config.xml " +
                        "中增加 android:canTakeScreenshot=\"true\"。第二步会扩大隐私面（服务可截屏），" +
                        "因此本工程默认不开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(
                title = "在 Android 16 上的验证步骤",
                subtitle = "按顺序执行，任何一步不符合预期都能定位到具体环节。",
            ) {
                val steps = listOf(
                    "1. 打开系统设置 → 无障碍 → 兑换弹窗检测 → 开启。首次侧载安装可能需要在应用信息里先「允许受限设置」。",
                    "2. 回到本页点刷新：应看到「系统已启用」「进程内已连接」，且「可读取窗口内容 = true」。",
                    "3. 检查「交互窗口标志 = true」且「当前可见窗口数 > 0」——这是识别跨应用窗口的硬指标。",
                    "4. 打开内置模拟页面，点「显示原生弹窗」：1 秒内「接收事件」应增长，日志页出现一条「出现」。",
                    "5. 关闭弹窗：3.5 秒内日志页应出现一条「消失」。",
                    "6. 若窗口数恒为 0：说明系统未返回交互窗口，请确认 xml 配置里保留了 flagRetrieveInteractiveWindows。",
                )
                steps.forEach { step ->
                    Text(step, style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                "自检只读取本应用自身的服务状态与计数，不读取任何第三方应用的内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}
