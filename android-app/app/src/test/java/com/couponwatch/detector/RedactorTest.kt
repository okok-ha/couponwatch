package com.couponwatch.detector

import com.couponwatch.detector.data.db.DetectionLogEntity
import com.couponwatch.detector.data.repo.LogRepository
import com.couponwatch.detector.util.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun `长数字被掩码`() {
        assertEquals("13*********", Redactor.maskDigits("13800138000"))
    }

    @Test
    fun `短数字保持原样`() {
        assertEquals("共 3 件", Redactor.maskDigits("共 3 件"))
    }

    @Test
    fun `兑换码形态的长串被掩码`() {
        // SAVE20268F3K 长度 12 且含数字 → 只保留前 2 位
        assertEquals("SA**********", Redactor.maskCodeLike("SAVE20268F3K"))
    }

    @Test
    fun `纯字母长串不掩码`() {
        assertEquals("ABCDEFGHIJ", Redactor.maskCodeLike("ABCDEFGHIJ"))
    }

    @Test
    fun `空白文本返回 null`() {
        assertNull(Redactor.text("   "))
    }

    @Test
    fun `超长文本被截断`() {
        val long = "兑".repeat(100)
        val result = Redactor.text(long)!!
        assertTrue(result.length <= 41)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `摘要限制总长度`() {
        val parts = List(30) { "第${it}段内容" }
        val summary = Redactor.summary(parts)
        assertTrue(summary.length <= 241)
    }

    @Test
    fun `CSV 单元格转义`() {
        assertEquals("\"a,b\"", Redactor.csvCell("a,b"))
        assertEquals("\"他说\"\"好\"\"\"", Redactor.csvCell("他说\"好\""))
        assertEquals("普通", Redactor.csvCell("普通"))
        assertEquals("", Redactor.csvCell(null))
    }
}

class CsvExportTest {

    private fun entity(action: String) = DetectionLogEntity(
        id = 1,
        timestamp = 1_700_000_000_000,
        packageName = "com.demo.shop",
        appLabel = "演示商城",
        action = action,
        score = 8,
        keywords = "兑换码,兑换",
        windowTitle = "限时兑换",
        windowType = "TYPE_APPLICATION",
        eventType = "TYPE_WINDOW_STATE_CHANGED",
        nodeSummary = "限时兑换 | 请输入兑换码",
        ocrUsed = false,
    )

    @Test
    fun `CSV 含表头与数据行`() {
        val csv = LogRepository.buildCsv(listOf(entity("APPEAR"), entity("DISAPPEAR")))
        val lines = csv.trim().split("\r\n")
        assertEquals(3, lines.size)
        assertEquals(LogRepository.CSV_HEADER, lines[0])
        assertTrue(lines[1].contains("APPEAR"))
        assertTrue(lines[2].contains("DISAPPEAR"))
        assertTrue(lines[1].contains("com.demo.shop"))
    }

    @Test
    fun `CSV 中不出现未转义的逗号`() {
        val csv = LogRepository.buildCsv(listOf(entity("APPEAR")))
        val row = csv.trim().split("\r\n")[1]
        // 关键词字段含逗号，必须被引号包裹
        assertTrue(row.contains("\"兑换码,兑换\""))
        assertFalse(row.contains(",,,,"))
    }

    @Test
    fun `空数据只有表头`() {
        val csv = LogRepository.buildCsv(emptyList())
        assertEquals(LogRepository.CSV_HEADER + "\r\n", csv)
    }
}
