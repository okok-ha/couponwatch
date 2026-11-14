package com.couponwatch.detector.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.couponwatch.detector.data.db.DetectionLogEntity
import com.couponwatch.detector.ui.MainViewModel
import com.couponwatch.detector.ui.StatusPill
import com.couponwatch.detector.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(vm: MainViewModel) {
    val logs by vm.logs.collectAsState()
    val packageStats by vm.packageStats.collectAsState()
    var selectedPackage by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) vm.exportCsv(uri)
    }

    val filtered = remember(logs, selectedPackage) {
        val pkg = selectedPackage
        if (pkg == null) logs else logs.filter { it.packageName == pkg }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("检测日志（${filtered.size}）") },
            actions = {
                IconButton(onClick = {
                    exportLauncher.launch("coupon_logs_${TimeFormat.fileStamp(System.currentTimeMillis())}.csv")
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "导出 CSV")
                }
                IconButton(onClick = { vm.clearLogs() }) {
                    Icon(Icons.Filled.Delete, contentDescription = "清空")
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = selectedPackage == null,
                onClick = { selectedPackage = null },
                label = { Text("全部") },
            )
            packageStats.forEach { stat ->
                FilterChip(
                    selected = selectedPackage == stat.packageName,
                    onClick = { selectedPackage = stat.packageName },
                    label = { Text("${stat.appLabel} ${stat.count}") },
                )
            }
        }

        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无检测记录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "开启无障碍服务后，打开「内置模拟页面」点一次兑换弹窗即可生成记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.id }) { log ->
                LogRow(log)
            }
        }
    }
}

@Composable
private fun LogRow(log: DetectionLogEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    TimeFormat.clock(log.timestamp),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                StatusPill(
                    text = if (log.action == "APPEAR") "出现" else "消失",
                    ok = log.action == "APPEAR",
                )
                Text(
                    log.appLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${log.score} 分",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "${log.windowType} · ${log.eventType}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (log.controlTypes.isNotBlank()) {
                Text(
                    "控件：" + log.controlTypes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (log.testResult.isNotBlank()) {
                Text(
                    "测试结果：" + log.testResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (log.keywords.isNotBlank()) {
                Text(
                    "关键词：${log.keywords}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (log.nodeSummary.isNotBlank()) {
                Text(
                    log.nodeSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
