package com.couponwatch.detector.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.couponwatch.detector.ui.MainViewModel

@Composable
fun FiltersScreen(vm: MainViewModel) {
    val filters by vm.filters.collectAsState()
    val apps by vm.installedApps.collectAsState()
    val loading by vm.loadingApps.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadInstalledApps() }

    val settings by vm.settings.collectAsState()
    val blocked = remember(filters) {
        filters.filter { it.blocked }.associateBy { it.packageName }
    }
    val whitelist = settings.whitelistMode
    val shown = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("应用过滤", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (whitelist) {
                    "白名单模式：只监测下方勾选的应用，其余全部忽略。已选 ${blocked.size} 个。"
                } else {
                    "黑名单模式：监测所有应用，勾选的会被排除。已排除 ${blocked.size} 个。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("白名单模式", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "开启后只有勾选的应用会被监测，适合只盯一两个 App 的场景。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = whitelist,
                    onCheckedChange = { vm.setWhitelistMode(it) },
                    colors = SwitchDefaults.colors(),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                label = { Text("搜索应用名或包名") },
            )
            if (loading) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                    Text("正在读取应用列表…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.packageName }) { app ->
                val isBlocked = blocked.containsKey(app.packageName)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (isBlocked) (if (whitelist) "已选入" else "已排除") else (if (whitelist) "忽略" else "检测中"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = isBlocked,
                        onCheckedChange = { checked ->
                            vm.setBlocked(app.packageName, app.label, checked)
                        },
                    )
                }
                HorizontalDivider()
            }
            if (shown.isEmpty() && !loading) {
                item {
                    Text(
                        "没有匹配的应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}
