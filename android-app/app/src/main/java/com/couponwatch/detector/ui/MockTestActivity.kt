package com.couponwatch.detector.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.couponwatch.detector.R
import com.couponwatch.detector.couponApp
import com.couponwatch.detector.data.db.FormatRuleEntity
import com.couponwatch.detector.format.CodeFormatAnalyzer
import com.couponwatch.detector.format.TestDataFactory
import com.couponwatch.detector.service.AutoTester
import com.couponwatch.detector.service.CouponAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内置模拟兑换页面。
 *
 * 刻意使用**原生 View**（EditText / Button / TextView）而不是 Compose：
 * 1. 自动化测试可以通过稳定的 viewId 精确找到控件（Compose 的节点没有 resource-id）；
 * 2. 无障碍扫描能拿到真实的 `android.widget.EditText` 等类名，便于验证控件识别逻辑。
 *
 * 页面只做本地格式匹配，不发任何网络请求。
 */
class MockTestActivity : ComponentActivity() {

    private lateinit var ruleView: TextView
    private lateinit var input: EditText
    private lateinit var submit: Button
    private lateinit var result: TextView
    private lateinit var switchNotFound: Switch
    private lateinit var autoStatus: TextView

    private var activeRule: FormatRuleEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_mock)
        applySystemBarInsets(findViewById(R.id.mock_root))

        ruleView = findViewById(R.id.mock_rule)
        input = findViewById(R.id.mock_input)
        submit = findViewById(R.id.mock_submit)
        result = findViewById(R.id.mock_result)
        switchNotFound = findViewById(R.id.mock_switch_notfound)
        autoStatus = findViewById(R.id.mock_auto_status)

        submit.setOnClickListener { submitCode() }

        findViewById<Button>(R.id.mock_show_dialog).setOnClickListener { showMockDialog() }
        findViewById<Button>(R.id.mock_auto).setOnClickListener { startAutoTest() }
        findViewById<Button>(R.id.mock_stop_auto).setOnClickListener {
            CouponAccessibilityService.instance?.autoTester?.stop()
        }

        observeAutoTest()
    }

    override fun onResume() {
        super.onResume()
        loadActiveRule()
    }

    private fun loadActiveRule() {
        lifecycleScope.launch {
            val rule = withContext(Dispatchers.IO) { couponApp.database.formatRuleDao().activeRule() }
            activeRule = rule
            ruleView.text = if (rule == null) {
                "当前规则：未设置（先到「格式」页分析并启用一条规则）"
            } else {
                "当前规则：${rule.name}\n${rule.regex}"
            }
        }
    }

    private fun evaluate(code: String): String {
        val text = code.trim()
        if (text.isEmpty()) return "格式错误：兑换码为空"
        val rule = activeRule ?: return "格式正确（未设置规则，仅演示）"
        if (!CodeFormatAnalyzer.matches(text, rule.regex)) {
            return "格式错误：不符合当前规则"
        }
        return if (switchNotFound.isChecked) "不存在" else "格式正确"
    }

    private fun submitCode() {
        val message = evaluate(input.text.toString())
        result.text = message
        result.setTextColor(
            when {
                message.startsWith("格式正确") -> 0xFF1E7A3C.toInt()
                message.startsWith("不存在") -> 0xFF96631A.toInt()
                else -> 0xFFA52A2A.toInt()
            }
        )
    }

    private fun showMockDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_mock)
        val dialogInput = dialog.findViewById<EditText>(R.id.dialog_input)
        val dialogResult = dialog.findViewById<TextView>(R.id.dialog_result)
        dialog.findViewById<Button>(R.id.dialog_cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<Button>(R.id.dialog_confirm).setOnClickListener {
            val message = evaluate(dialogInput.text.toString())
            dialogResult.text = message
            result.text = message
        }
        dialog.show()
    }

    private fun startAutoTest() {
        val service = CouponAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }
        val rule = activeRule
        if (rule == null) {
            Toast.makeText(this, "请先在「格式」页启用一条规则", Toast.LENGTH_SHORT).show()
            return
        }
        val settings = couponApp.settings.current()
        val codes = TestDataFactory.build(rule, settings.autoTestMax)
        service.autoTester?.start(
            codes = codes,
            maxAttempts = settings.autoTestMax,
            delayMs = settings.autoTestDelayMs,
            notFoundMode = switchNotFound.isChecked,
        )
        Toast.makeText(this, "自动测试已启动（${codes.size} 条）", Toast.LENGTH_SHORT).show()
    }

    private fun observeAutoTest() {
        lifecycleScope.launch {
            CouponAccessibilityService.autoTestState.collect { state ->
                autoStatus.text = buildString {
                    append("自动测试：")
                    append(
                        when {
                            state.running && state.paused -> "已暂停"
                            state.running -> "运行中"
                            else -> "未开始"
                        }
                    )
                    append(" · ${state.index}/${state.total}")
                    append(" · 成功 ${state.success} / 失败 ${state.failure}")
                    if (state.lastCode.isNotEmpty()) {
                        append("\n最近：").append(state.lastCode).append(" → ").append(state.lastResult)
                    }
                }
            }
        }
    }

    /** enableEdgeToEdge 之后必须自己让出状态栏/导航栏，否则标题会被状态栏压住。 */
    private fun applySystemBarInsets(root: android.view.View?) {
        root ?: return
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
