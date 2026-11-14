package com.couponwatch.detector

import com.couponwatch.detector.data.db.FormatRuleEntity
import com.couponwatch.detector.format.CodeFormatAnalyzer
import com.couponwatch.detector.format.TestDataFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatAnalyzerTest {

    @Test
    fun `定长样例能归纳出前缀分隔符与正则`() {
        val format = CodeFormatAnalyzer.analyze(
            listOf("SAVE-2026-8F3K", "SAVE-2026-QW7M", "SAVE-2026-4XZ9"),
            "定长",
        )
        assertEquals("定长逐位", format.mode)
        assertEquals(14, format.lengthMin)
        assertEquals(14, format.lengthMax)
        assertEquals("SAVE", format.prefix)
        assertTrue(format.separators.contains("-"))
        format.samples.forEach { assertTrue("应匹配：$it", CodeFormatAnalyzer.matches(it, format.regex)) }
        assertFalse(CodeFormatAnalyzer.matches("SAVE-2026-8F3", format.regex))
        assertFalse(CodeFormatAnalyzer.matches("save-2026-8f3k", format.regex))
    }

    @Test
    fun `变长样例能给出长度区间`() {
        val format = CodeFormatAnalyzer.analyze(
            listOf("GC-8K3M2", "GC-7QP", "GC-9XZ4L"),
            "变长",
        )
        assertEquals(6, format.lengthMin)
        assertEquals(8, format.lengthMax)
        format.samples.forEach { assertTrue(CodeFormatAnalyzer.matches(it, format.regex)) }
        assertFalse(CodeFormatAnalyzer.matches("GC-1A", format.regex))
    }

    @Test
    fun `自匹配率应为百分之百`() {
        val samples = listOf("AB-1234", "AB-5678", "AB-9012")
        val format = CodeFormatAnalyzer.analyze(samples, "自洽")
        assertEquals(1.0, CodeFormatAnalyzer.matchRate(samples, format.regex), 0.0001)
    }

    @Test
    fun `正则特殊字符会被转义`() {
        val format = CodeFormatAnalyzer.analyze(listOf("A.B-11", "A.B-22"), "转义")
        assertTrue(CodeFormatAnalyzer.matches("A.B-33", format.regex))
        // '.' 若未转义，会匹配任意字符
        assertFalse(CodeFormatAnalyzer.matches("AXB-33", format.regex))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空样例应抛异常`() {
        CodeFormatAnalyzer.analyze(listOf("   ", ""), "空")
    }

    @Test
    fun `非法正则不会导致崩溃`() {
        assertFalse(CodeFormatAnalyzer.matches("abc", "[unclosed"))
    }

    @Test
    fun `测试数据工厂数量与内容正确`() {
        val rule = FormatRuleEntity(
            name = "r",
            regex = "^SAVE\\-2026\\-[A-Z0-9]{4}$",
            samples = "SAVE-2026-8F3K\nSAVE-2026-QW7M",
            lengthMin = 14,
            lengthMax = 14,
            prefix = "SAVE",
            suffix = "",
            separators = "-",
            charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            mode = "定长逐位",
        )
        val codes = TestDataFactory.build(rule, 12, kotlin.random.Random(7))
        assertEquals(12, codes.size)
        assertEquals(codes.size, codes.distinct().size)
        assertTrue(codes.contains("SAVE-2026-8F3K"))
        // 必须掺入不合规串，才能触发"失败后切换下一条"
        assertTrue(codes.any { !CodeFormatAnalyzer.matches(it, rule.regex) })
    }

    @Test
    fun `变异数据大部分仍然合规`() {
        val rule = FormatRuleEntity(
            name = "r",
            regex = "^GC\\-[A-Z0-9]{3,5}$",
            samples = "GC-8K3M2\nGC-7QP",
            lengthMin = 6,
            lengthMax = 8,
            prefix = "GC",
            suffix = "",
            separators = "-",
            charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            mode = "分段",
        )
        val codes = TestDataFactory.build(rule, 20, kotlin.random.Random(3))
        val ok = codes.count { CodeFormatAnalyzer.matches(it, rule.regex) }
        assertTrue("合规条数应占多数，实际 $ok/${codes.size}", ok >= codes.size / 2)
    }
}
