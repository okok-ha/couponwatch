package com.couponwatch.detector.format

import com.couponwatch.detector.data.db.FormatRuleEntity
import kotlin.random.Random

/**
 * 本地测试数据工厂。
 *
 * 从当前规则生成一批"应当命中格式"的码，再掺入少量"必然不符合"的串，
 * 用于验证自动化测试的"失败后切换下一条"逻辑。
 * 这些数据只在本机使用，不涉及任何真实兑换码。
 */
object TestDataFactory {

    private const val ALNUM_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun build(rule: FormatRuleEntity, count: Int, random: Random = Random.Default): List<String> {
        val target = count.coerceIn(1, 200)
        val out = ArrayList<String>(target)

        // 1) 先用用户自己的样例，保证至少有一条必然合规
        rule.samples.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { if (out.size < target) out.add(it) }

        // 2) 用样例做字符位变异，生成同形态的新码
        val bases = rule.samples.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var guard = 0
        while (out.size < target && bases.isNotEmpty() && guard < target * 20) {
            guard++
            val base = bases[random.nextInt(bases.size)]
            // 固定前后缀必须原样保留，否则变异会破坏前缀，生成的数据几乎全部不合规
            val prefixLen = rule.prefix.length.coerceAtMost(base.length)
            val suffixLen = rule.suffix.length.coerceAtMost(base.length - prefixLen)
            val mutableEnd = base.length - suffixLen
            val mutated = base.mapIndexed { index, ch ->
                val inVariablePart = index >= prefixLen && index < mutableEnd
                if (inVariablePart && ch.isLetterOrDigit() && random.nextInt(100) < 55) {
                    ALNUM_UPPER[random.nextInt(ALNUM_UPPER.length)]
                } else {
                    ch
                }
            }.joinToString("")
            if (mutated !in out) out.add(mutated)
        }

        // 3) 掺入明显不合规的串，用来触发"格式错误"分支
        val invalids = listOf("!!!", "123", "??", "code", "0")
        for (bad in invalids) {
            if (out.size >= target) break
            if (bad !in out) out.add(bad)
        }

        return out.take(target)
    }
}
