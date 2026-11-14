package com.couponwatch.detector

import com.couponwatch.detector.service.ResultClassifier
import com.couponwatch.detector.service.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ResultClassifierTest {

    @Test
    fun `成功文案判定为命中`() {
        assertEquals(Verdict.SUCCESS, ResultClassifier.classify("兑换成功，奖励已到账"))
        assertEquals(Verdict.SUCCESS, ResultClassifier.classify("恭喜！领取成功"))
        assertEquals(Verdict.SUCCESS, ResultClassifier.classify("Redeemed successfully"))
    }

    @Test
    fun `失败文案含成功二字时仍判无效`() {
        assertEquals(Verdict.INVALID, ResultClassifier.classify("兑换失败，请检查兑换码"))
        assertEquals(Verdict.INVALID, ResultClassifier.classify("该兑换码已被使用"))
        assertEquals(Verdict.INVALID, ResultClassifier.classify("兑换码已过期"))
        assertEquals(Verdict.INVALID, ResultClassifier.classify("coupon not found"))
    }

    @Test
    fun `否定前缀优先于成功词`() {
        assertEquals(Verdict.INVALID, ResultClassifier.classify("未能成功兑换"))
        assertEquals(Verdict.INVALID, ResultClassifier.classify("无法兑换，库存不足"))
    }

    @Test
    fun `限流文案优先于其他判定`() {
        assertEquals(Verdict.RATE_LIMIT, ResultClassifier.classify("操作过于频繁，请稍后再试"))
        assertEquals(Verdict.RATE_LIMIT, ResultClassifier.classify("too many requests, slow down"))
    }

    @Test
    fun `空文本与无关文本判未判定`() {
        assertEquals(Verdict.UNKNOWN, ResultClassifier.classify(""))
        assertEquals(Verdict.UNKNOWN, ResultClassifier.classify("   "))
        assertEquals(Verdict.UNKNOWN, ResultClassifier.classify("请输入兑换码"))
    }

    @Test
    fun `提交按钮打分按具体度排序`() {
        assertEquals(0, ResultClassifier.submitScore("立即兑换"))
        assertEquals(1, ResultClassifier.submitScore("确认兑换"))
        assertEquals(2, ResultClassifier.submitScore("兑换"))
        assertEquals(5, ResultClassifier.submitScore("确定"))
        assertEquals(9, ResultClassifier.submitScore("Redeem"))
        assertEquals(-1, ResultClassifier.submitScore("取消"))
        assertEquals(-1, ResultClassifier.submitScore(""))
    }
}
