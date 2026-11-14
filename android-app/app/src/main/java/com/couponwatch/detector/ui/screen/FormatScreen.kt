package com.couponwatch.detector.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.couponwatch.detector.ui.MainViewModel
import com.couponwatch.detector.ui.SectionCard
import com.couponwatch.detector.ui.StatusPill

@Composable
fun FormatScreen(vm: MainViewModel) {
    val preview by vm.formatPreview.collectAsState()
    val message by vm.formatMessage.collectAsState()
    val rules by vm.formatRules.collectAsState()
    val active by vm.activeRule.collectAsState()

    var samples by remember {
        mutableStateOf("SAVE-2026-8F3K\nSAVE-2026-QW7M\nSAVE-2026-4XZ9")
    }
    var ruleName by remember { mutableStateOf("示例规则") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("兑换码格式分析", style = MaterialTheme.typography.headlineSmall)

        SectionCard(
            title = "输入样例",
            subtitle = "每行一个，也可用逗号分隔；建议 3 个以上，覆盖不同取值。",
        ) {
            OutlinedTextField(
                value = samples,
                onValueChange = { samples = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = { Text("样例") },
            )
            OutlinedTextField(
                value = ruleName,
                onValueChange = { ruleName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("规则名称") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.analyzeFormat(samples, ruleName) }) { Text("分析") }
                OutlinedButton(onClick = { vm.saveFormat() }) { Text("保存并启用") }
            }
            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        preview?.let { format ->
            SectionCard(title = "分析结果", subtitle = format.name) {
                Text(
                    format.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "样例：" + format.samples.joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(
            title = "已保存规则（${rules.size}）",
            subtitle = "同一时刻只有一条规则处于启用状态，模拟页面与自动测试都用它判定格式。",
        ) {
            if (rules.isEmpty()) {
                Text(
                    "还没有规则。上面分析后点「保存并启用」即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            rules.forEach { rule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            rule.regex,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "长度 ${if (rule.lengthMin == rule.lengthMax) "${rule.lengthMin}" else "${rule.lengthMin}~${rule.lengthMax}"}" +
                                " · ${rule.mode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (active?.id == rule.id) {
                            StatusPill("已启用", true)
                        } else {
                            OutlinedButton(onClick = { vm.activateRule(rule.id) }) { Text("启用") }
                        }
                        OutlinedButton(onClick = { vm.deleteRule(rule.id) }) { Text("删除") }
                    }
                }
                HorizontalDivider()
            }
        }

        Text(
            "说明：格式匹配只表示字符串符合归纳出的规则，不代表兑换码真实有效；" +
                "本应用不会向任何平台提交或校验真实兑换码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}
