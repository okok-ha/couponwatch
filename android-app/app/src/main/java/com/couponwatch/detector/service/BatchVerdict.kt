package com.couponwatch.detector.service

/** 单条候选码的执行结论。 */
enum class Verdict(val label: String) {
    SUCCESS("命中"),
    INVALID("无效"),
    RATE_LIMIT("限流"),
    NO_TARGET("未找到目标窗口"),
    NO_INPUT("未找到输入框"),
    NO_BUTTON("未找到提交按钮"),
    SET_TEXT_FAILED("写入失败"),
    SWITCHED_APP("目标应用已切换"),
    UNKNOWN("未判定"),
}

/**
 * 结果文案判定：纯字符串逻辑，无 Android 依赖，便于单测。
 *
 * 判定顺序固定为 **限流 → 否定/失败 → 成功**，因为很多失败提示里也带「成功」二字
 * （「兑换失败，请稍后重试」），先看成功会把无效码记成命中，代价最高。
 */
object ResultClassifier {

    /** 提交按钮文案候选，越靠前越具体，命中即取序号最小的那个按钮。 */
    val SUBMIT_TOKENS = listOf(
        "立即兑换", "确认兑换", "兑换", "提交", "领取", "确定", "确认", "激活", "使用",
        "redeem", "submit", "confirm", "apply", "activate",
    )

    private val SUCCESS_WORDS = listOf(
        "兑换成功", "领取成功", "已到账", "已发放", "恭喜", "success", "redeemed", "accepted",
    )

    private val FAIL_WORDS = listOf(
        "无效", "不存在", "错误", "失败", "已被使用", "已使用", "过期", "已失效", "不正确",
        "已领取", "invalid", "not found", "expired", "used", "error", "wrong",
    )

    private val NEGATIVE_PREFIX = listOf("未成功", "未能", "无法兑换")

    private val RATE_LIMIT_WORDS = listOf(
        "频繁", "稍后再试", "操作过快", "too many", "slow down", "rate limit", "try again later",
    )

    fun classify(text: String): Verdict {
        if (text.isBlank()) return Verdict.UNKNOWN
        val t = text.lowercase()

        if (RATE_LIMIT_WORDS.any { t.contains(it) }) return Verdict.RATE_LIMIT
        if (NEGATIVE_PREFIX.any { t.contains(it) } || FAIL_WORDS.any { t.contains(it) }) {
            return Verdict.INVALID
        }
        if (SUCCESS_WORDS.any { t.contains(it) }) return Verdict.SUCCESS
        return Verdict.UNKNOWN
    }

    /** 按钮文案打分：词表序号，越小越优先；-1 表示不像提交按钮。 */
    fun submitScore(text: String): Int {
        if (text.isBlank()) return -1
        return SUBMIT_TOKENS.indexOfFirst { text.contains(it, ignoreCase = true) }
    }
}
