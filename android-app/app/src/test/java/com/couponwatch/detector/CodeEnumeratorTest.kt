package com.couponwatch.detector

import com.couponwatch.detector.format.CodeEnumerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeEnumeratorTest {

    @Test
    fun `顺序推进按样例尾部加一`() {
        val plan = CodeEnumerator.plan(listOf("SAVE-2026-0001", "SAVE-2026-0002"))
        assertNotNull(plan)
        val codes = CodeEnumerator.generate(plan!!, 3)
        assertEquals(listOf("SAVE-2026-0001", "SAVE-2026-0002", "SAVE-2026-0003"), codes)
    }

    @Test
    fun `字母段按字母表进位`() {
        val plan = CodeEnumerator.plan(listOf("GC-AAAA", "GC-AAAB"))!!
        val codes = CodeEnumerator.generate(plan, 3)
        assertEquals(listOf("GC-AAAA", "GC-AAAB", "GC-AAAC"), codes)
    }

    @Test
    fun `低位溢出时向高位进位`() {
        // 两条样例只差在低位，低位 9 → 0 时应向高一位进位（A→B）
        val plan = CodeEnumerator.plan(listOf("A9", "B0"))!!
        val codes = CodeEnumerator.generate(plan, 2)
        assertEquals("A9", codes[0])
        assertEquals("B0", codes[1])
    }

    @Test
    fun `纯数字样例识别 Luhn 并重算校验位`() {
        // 79927398713 与 12345678903 均为经典 Luhn 合法串，长度一致才进同一模板
        val samples = listOf("79927398713", "12345678903")
        val plan = CodeEnumerator.plan(samples)!!
        assertEquals(CodeEnumerator.Checksum.LUHN, plan.checksum)

        val codes = CodeEnumerator.generate(plan, 8)
        assertEquals(8, codes.size)
        codes.forEach { code ->
            assertEquals("校验位应自洽：$code", true, luhnValid(code))
        }
    }

    @Test
    fun `随机策略产出不重复且长度一致`() {
        val plan = CodeEnumerator.plan(listOf("AB-1234", "AB-5678"))!!
        val codes = CodeEnumerator.generate(plan, 50, CodeEnumerator.Strategy.RANDOM, seed = 42)
        assertEquals(50, codes.size)
        assertEquals(50, codes.distinct().size)
        assertTrue(codes.all { it.length == 7 && it.startsWith("AB-") })
    }

    @Test
    fun `空样例返回空计划`() {
        assertEquals(null, CodeEnumerator.plan(listOf("", "  ")))
    }

    @Test
    fun `单条样例也能生成`() {
        val plan = CodeEnumerator.plan(listOf("CODE-0007"))!!
        val codes = CodeEnumerator.generate(plan, 2)
        assertEquals(listOf("CODE-0007", "CODE-0008"), codes)
    }

    @Test
    fun `next 单步推进`() {
        val plan = CodeEnumerator.plan(listOf("A-01", "A-02"))!!
        assertEquals("A-02", CodeEnumerator.next(plan, "A-01"))
    }

    @Test
    fun `混排片段会把整段可变列放宽到字母数字`() {
        // 真实样例：24 位，前 5 位固定 DAB6P，中间 D7B78JIED 固定，其余 10 位可变。
        // 其中第 6 位（J/3）与第 19 位（G/0）出现过数字，说明整段是 36 进制混排，
        // 其余"恰好都是字母"的列不能只在 26 个字母里枚举。
        val samples = listOf(
            "DAB6PMJD7B78JIEDMJSGQED8",
            "DAB6PQ3D7B78JIEDOHF0HSF3",
        )
        val plan = CodeEnumerator.plan(samples)!!
        assertEquals(10, plan.variablePositions.size)
        assertEquals(24, plan.template.length)
        plan.alphabets.forEach { assertEquals("可变列字母表应为 36 进制：$it", 36, it.length) }

        val codes = CodeEnumerator.generate(plan, 40, CodeEnumerator.Strategy.RANDOM, seed = 7)
        assertEquals(40, codes.size)
        assertTrue(codes.all { it.length == 24 && it.startsWith("DAB6P") && it.substring(7, 16) == "D7B78JIED" })
    }

    @Test
    fun `候选空间大时自动改用随机采样`() {
        val big = CodeEnumerator.plan(listOf("DAB6PMJD7B78JIEDMJSGQED8", "DAB6PQ3D7B78JIEDOHF0HSF3"))!!
        assertEquals(CodeEnumerator.Strategy.RANDOM, CodeEnumerator.autoStrategy(big))

        val small = CodeEnumerator.plan(listOf("AB-1234", "AB-5678"))!!
        assertEquals(CodeEnumerator.Strategy.SEQUENTIAL, CodeEnumerator.autoStrategy(small))
    }

    private fun luhnValid(code: String): Boolean {
        var sum = 0
        var double = false
        for (i in code.indices.reversed()) {
            var d = code[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }
}
