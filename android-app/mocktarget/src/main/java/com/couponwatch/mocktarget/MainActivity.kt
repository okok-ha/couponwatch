package com.couponwatch.mocktarget

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 沙箱兑换页：只做一件事——提供一个"输入框 + 提交按钮 + 结果文本"，用来真机验证
 * CouponWatch 的批量试码链路（写入 → 提交 → 文本 diff → 判定）。
 *
 * 判定规则（故意做得可命中）：
 *   sum(charCode) % 11 == 0  → 兑换成功
 *   其它                      → 兑换码无效
 * 这样随机生成的候选大约 1/11 命中，几十条内就能同时看到"无效→清空换下一条"和"命中→记录"。
 */
class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var submit: Button
    private lateinit var result: TextView
    private lateinit var counter: TextView

    private var attempts = 0
    private var hits = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(TextView(this).apply {
            text = "兑换中心（沙箱）"
            textSize = 22f
            setTextColor(Color.parseColor("#1B1B1F"))
        })

        root.addView(TextView(this).apply {
            text = "仅用于本机联调：输入兑换码 → 点「立即兑换」→ 看结果。"
            textSize = 13f
            setTextColor(Color.parseColor("#5A5A60"))
            setPadding(0, pad / 2, 0, pad)
        })

        input = EditText(this).apply {
            hint = "请输入兑换码"
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setTextColor(Color.parseColor("#1B1B1F"))
        }
        root.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        submit = Button(this).apply {
            text = "立即兑换"
            textSize = 16f
        }
        root.addView(
            submit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = pad / 2 },
        )

        result = TextView(this).apply {
            text = "等待提交"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.parseColor("#1B1B1F"))
            setPadding(0, pad, 0, 0)
        }
        root.addView(result)

        counter = TextView(this).apply {
            text = "已提交 0 次 · 命中 0 次"
            textSize = 13f
            setTextColor(Color.parseColor("#5A5A60"))
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(counter)

        submit.setOnClickListener { redeem() }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun redeem() {
        val code = input.text?.toString()?.trim().orEmpty()
        attempts++
        when {
            code.isEmpty() -> result.text = "兑换码无效：请先输入兑换码"
            isValid(code) -> {
                hits++
                result.text = "兑换成功，奖励已到账"
            }
            else -> result.text = "兑换码无效，请检查后重试"
        }
        counter.text = "已提交 $attempts 次 · 命中 $hits 次"
    }

    private fun isValid(code: String): Boolean =
        code.isNotEmpty() && code.sumOf { it.code } % 11 == 0
}
