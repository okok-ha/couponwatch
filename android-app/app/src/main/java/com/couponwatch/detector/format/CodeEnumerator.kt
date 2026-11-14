package com.couponwatch.detector.format

import kotlin.random.Random

/**
 * 兑换码枚举器：从若干真实样例里拆出「固定段 + 可变段」，按位做进制推进，
 * 生成同形态的大批量候选码。
 *
 * 设计要点：
 * 1. **定长优先**：多组样例长度不同时，取样本数最多的那组长度的模板；
 * 2. **逐位归类**：同一列所有样例字符相同 → 固定字面量；不同 → 可变位，按字符类取字母表
 *    （纯数字 `0123456789`、纯大写 `A..Z`、纯小写、字母数字混合）；
 * 3. **顺序推进**：把每个可变位当作一个「数位」，从样例本身的取值开始，最右位进得最快，
 *    等价于把样例尾部当计数器 +1，产出 0001→0002→0003 这类连续码；
 * 4. **随机采样**：空间很大时按字母表随机取值（可复现 seed），避免全部集中在连续区间；
 * 5. **校验位重算**：整串纯数字且样例末位能通过 Luhn / Damm / Verhoeff / MOD 97-10 时，
 *    生成的码会按同一算法重算校验位，否则生成的码会被平台的校验位检查直接拒掉。
 *
 * 本类纯 Kotlin、无 Android 依赖，可单测。
 */
object CodeEnumerator {

    /** 只有一条样例时，最多把末尾多少位当作隐式可变段。 */
    private const val MAX_IMPLICIT_VAR = 8

    /** 候选空间大于这个值时，默认改用随机采样（顺序推进只会把十几万条压在样例附近）。 */
    private const val AUTO_RANDOM_SPACE = 200_000L

    enum class Strategy(val label: String) {
        SEQUENTIAL("顺序推进"),
        RANDOM("随机采样"),
    }

    enum class Checksum(val label: String) {
        NONE("无"),
        LUHN("Luhn"),
        DAMM("Damm"),
        VERHOEFF("Verhoeff"),
        MOD97("MOD 97-10"),
    }

    /** 模板 + 每个可变位的字母表与起始下标。 */
    data class Plan(
        val template: String,
        val variablePositions: List<Int>,
        val alphabets: List<String>,
        val startIndexes: IntArray,
        val checksum: Checksum,
        /** 校验位所在下标；-1 表示无校验位 */
        val checkPos: Int,
        /** 候选空间大小（可变位字母表长度连乘） */
        val space: Long,
        val sampleCount: Int,
    ) {
        fun describe(): String = buildString {
            append("模板：").append(template).append('\n')
            append("可变位：").append(variablePositions.size).append(" 个")
            if (variablePositions.isEmpty()) {
                append("（无，全部为固定字符）")
            } else {
                append("［")
                variablePositions.forEachIndexed { i, pos ->
                    if (i > 0) append(' ')
                    append(pos).append('=').append(alphabets[i].let {
                        if (it.length > 12) it.take(6) + "…" + it.takeLast(3) else it
                    })
                }
                append("］")
            }
            append('\n')
            append("空间：").append(if (space >= 1_000_000_000L) "≥10 亿" else space.toString()).append('\n')
            append("校验位：").append(checksum.label).append("（样例 ").append(sampleCount).append(" 条）")
        }
    }

    // ------------------------------------------------------------------ //
    // 规划
    // ------------------------------------------------------------------ //

    fun plan(rawSamples: List<String>): Plan? {
        val samples = rawSamples.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (samples.isEmpty()) return null

        // 取样本数最多的长度组；并列时取更长的一组（更长通常信息量更大）
        val byLength = samples.groupBy { it.length }
        val group = byLength.entries
            .sortedWith(compareByDescending<Map.Entry<Int, List<String>>> { it.value.size }
                .thenByDescending { it.key })
            .first().value

        val n = group.first().length
        val template = StringBuilder(n)
        val varPositions = ArrayList<Int>()
        val alphabets = ArrayList<String>()
        val starts = ArrayList<Int>()
        val observedClasses = ArrayList<Set<CharClass>>()

        for (i in 0 until n) {
            val col = group.map { it[i] }.toSet()
            if (col.size == 1) {
                template.append(col.first())
                continue
            }
            val alphabet = alphabetFor(col)
            val startCh = group.first()[i]
            val startIdx = alphabet.indexOf(startCh).let { if (it >= 0) it else 0 }
            template.append('#')
            varPositions.add(i)
            alphabets.add(alphabet)
            starts.add(startIdx)
            observedClasses.add(col.mapNotNull { charClass(it) }.toSet())
        }

        // 只有一条样例时无法通过「列间比较」找出可变位：退回「末段递增」——
        // 把末尾连续的同类字符（数字段/字母段）当作计数器，最多取末 8 位。
        if (group.size == 1 && varPositions.isEmpty()) {
            val s = group.first()
            val cls = charClass(s[n - 1])
            if (cls != null) {
                var i = n - 1
                while (i >= 0 && charClass(s[i]) == cls) i--
                val from = maxOf(i + 1, n - MAX_IMPLICIT_VAR)
                val alphabet = alphabetOf(cls)
                for (p in from until n) {
                    template.setCharAt(p, '#')
                    varPositions.add(p)
                    alphabets.add(alphabet)
                    starts.add(alphabet.indexOf(s[p]).coerceAtLeast(0))
                    observedClasses.add(setOf(cls))
                }
            }
        }

        // 混合段统一：只要**某一列**同时出现过字母和数字，说明这段是混排编码，
        // 把其余可变列也放宽到同一套并集字母表——否则纯字母列永远不会枚举到数字，
        // 纯数字列永远枚举不到字母，覆盖率被人为砍掉一大半。
        // 注意：只有"列间差异"（如 A9 / B0 各列纯净）不算混排，不能放宽，否则会
        // 把本来严格分段的编号体系打散。
        val union = observedClasses.flatten().toSet()
        val hasMixedColumn = observedClasses.any { it.size > 1 }
        if (hasMixedColumn && union.size > 1 && varPositions.isNotEmpty()) {
            val widened = CharClass.values()
                .filter { it in union }
                .joinToString("") { alphabetOf(it) }
            val first = group.first()
            for (i in varPositions.indices) {
                alphabets[i] = widened
                starts[i] = widened.indexOf(first[varPositions[i]]).coerceAtLeast(0)
            }
        }

        val space = if (varPositions.isEmpty()) 1L else {
            var acc = 1L
            for (a in alphabets) {
                acc = if (acc > Long.MAX_VALUE / a.length) Long.MAX_VALUE else acc * a.length
            }
            acc
        }

        val checksum = detectChecksum(group)
        val checkPos = if (checksum != Checksum.NONE) n - 1 else -1

        return Plan(
            template = template.toString(),
            variablePositions = varPositions,
            alphabets = alphabets,
            startIndexes = starts.toIntArray(),
            checksum = checksum,
            checkPos = checkPos,
            space = space,
            sampleCount = group.size,
        )
    }

    // ------------------------------------------------------------------ //
    // 生成
    // ------------------------------------------------------------------ //

    /**
     * 默认策略：空间大到顺序推进没意义时用随机采样，小空间用顺序推进（能保证不漏）。
     */
    fun autoStrategy(plan: Plan): Strategy =
        if (plan.space >= AUTO_RANDOM_SPACE) Strategy.RANDOM else Strategy.SEQUENTIAL

    fun generate(
        plan: Plan,
        count: Int,
        strategy: Strategy = Strategy.SEQUENTIAL,
        seed: Long = System.currentTimeMillis(),
    ): List<String> {
        val target = count.coerceIn(0, 100_000)
        if (target == 0) return emptyList()
        if (plan.variablePositions.isEmpty()) {
            return listOf(render(plan, plan.startIndexes.copyOf()))
        }

        return when (strategy) {
            Strategy.SEQUENTIAL -> sequential(plan, target)
            Strategy.RANDOM -> randomSample(plan, target, seed)
        }
    }

    private fun sequential(plan: Plan, target: Int): List<String> {
        val out = ArrayList<String>(target)
        val cursor = plan.startIndexes.copyOf()
        // 第一条就是样例本身（保证用户自己的码一定在列表里），之后逐位 +1
        repeat(target) {
            out.add(render(plan, cursor))
            if (!increment(plan, cursor)) return out
        }
        return out
    }

    private fun randomSample(plan: Plan, target: Int, seed: Long): List<String> {
        val rnd = Random(seed)
        val out = LinkedHashSet<String>(target * 2)
        val cursor = IntArray(plan.variablePositions.size)
        var guard = 0
        val guardLimit = target * 50 + 200
        while (out.size < target && guard < guardLimit) {
            guard++
            for (i in cursor.indices) {
                cursor[i] = rnd.nextInt(plan.alphabets[i].length)
            }
            out.add(render(plan, cursor))
        }
        return out.toList()
    }

    /** 最右位先加；溢出则归零并向前进位。全部溢出时返回 false。 */
    private fun increment(plan: Plan, cursor: IntArray): Boolean {
        for (i in cursor.indices.reversed()) {
            cursor[i]++
            if (cursor[i] < plan.alphabets[i].length) return true
            cursor[i] = 0
        }
        return false
    }

    private fun render(plan: Plan, cursor: IntArray): String {
        val chars = plan.template.toCharArray()
        plan.variablePositions.forEachIndexed { i, pos ->
            chars[pos] = plan.alphabets[i][cursor[i]]
        }
        if (plan.checksum != Checksum.NONE && plan.checkPos >= 0) {
            val body = String(chars, 0, plan.checkPos)
            val check = checkChar(plan.checksum, body)
            if (check != null) chars[plan.checkPos] = check
        }
        return String(chars)
    }

    /** 单步推进任意一条码（用于边跑边推的流式场景）。 */
    fun next(plan: Plan, code: String): String? {
        if (code.length != plan.template.length) return null
        val cursor = IntArray(plan.variablePositions.size)
        plan.variablePositions.forEachIndexed { i, pos ->
            val idx = plan.alphabets[i].indexOf(code[pos])
            cursor[i] = if (idx >= 0) idx else 0
        }
        return if (increment(plan, cursor)) render(plan, cursor) else null
    }

    // ------------------------------------------------------------------ //
    // 字符类与字母表
    // ------------------------------------------------------------------ //

    private enum class CharClass { DIGIT, UPPER, LOWER }

    private fun charClass(c: Char): CharClass? = when (c) {
        in '0'..'9' -> CharClass.DIGIT
        in 'A'..'Z' -> CharClass.UPPER
        in 'a'..'z' -> CharClass.LOWER
        else -> null
    }

    private fun alphabetOf(cls: CharClass): String = when (cls) {
        CharClass.DIGIT -> "0123456789"
        CharClass.UPPER -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        CharClass.LOWER -> "abcdefghijklmnopqrstuvwxyz"
    }

    private fun alphabetFor(observed: Set<Char>): String {
        val classes = observed.mapNotNull { charClass(it) }.toSet()
        return when {
            classes.size == 1 -> alphabetOf(classes.first())
            classes.isNotEmpty() -> CharClass.values()
                .filter { it in classes }
                .joinToString("") { alphabetOf(it) }

            else -> observed.sorted().joinToString("")
        }
    }

    // ------------------------------------------------------------------ //
    // 校验位：识别 + 重算
    // ------------------------------------------------------------------ //

    private fun detectChecksum(samples: List<String>): Checksum {
        if (samples.size < 2) return Checksum.NONE
        if (samples.any { s -> s.any { it !in '0'..'9' } }) return Checksum.NONE
        if (samples.any { it.length < 4 }) return Checksum.NONE

        for (kind in listOf(Checksum.LUHN, Checksum.DAMM, Checksum.VERHOEFF)) {
            val allValid = samples.all { s ->
                val body = s.dropLast(1)
                checkChar(kind, body) == s.last()
            }
            if (allValid) return kind
        }

        // MOD 97-10：末两位为校验位
        val mod97Valid = samples.all { s ->
            val body = s.dropLast(2)
            if (body.isEmpty()) return@all false
            mod97Check(body) == s.takeLast(2)
        }
        if (mod97Valid) return Checksum.MOD97
        return Checksum.NONE
    }

    fun checkChar(kind: Checksum, body: String): Char? = when (kind) {
        Checksum.LUHN -> luhnDigit(body)?.let { ('0' + it) }
        Checksum.DAMM -> dammDigit(body)?.let { ('0' + it) }
        Checksum.VERHOEFF -> verhoeffDigit(body)?.let { ('0' + it) }
        Checksum.MOD97 -> mod97Check(body)?.first()
        Checksum.NONE -> null
    }

    private fun digitsOf(body: String): IntArray? {
        if (body.isEmpty()) return null
        val arr = IntArray(body.length)
        for (i in body.indices) {
            val d = body[i] - '0'
            if (d !in 0..9) return null
            arr[i] = d
        }
        return arr
    }

    private fun luhnDigit(body: String): Int? {
        val ds = digitsOf(body) ?: return null
        var sum = 0
        // 校验位补在最右；从校验位左边一位开始，奇数位（自右向左第 2、4、6…）乘 2
        var double = true
        for (i in ds.indices.reversed()) {
            var d = ds[i]
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return (10 - sum % 10) % 10
    }

    private fun dammDigit(body: String): Int? {
        val ds = digitsOf(body) ?: return null
        var interim = 0
        for (d in ds) interim = DAMM_TABLE[interim][d]
        for (c in 0..9) if (DAMM_TABLE[interim][c] == 0) return c
        return null
    }

    private fun verhoeffDigit(body: String): Int? {
        val ds = digitsOf(body) ?: return null
        var c = 0
        // 校验位位于最右，其自身权重为 1，前面的位从 2 开始
        for (i in ds.indices.reversed()) {
            val pos = ds.size - i  // 1,2,3...（i 从右往左，pos 从 1 开始）
            c = VERHOEFF_D[c][VERHOEFF_P[pos % 8][ds[i]]]
        }
        return VERHOEFF_INV[c]
    }

    private fun mod97Check(body: String): String? {
        val ds = digitsOf(body) ?: return null
        var rem = 0
        for (d in ds) rem = (rem * 10 + d) % 97
        val check = (98 - (rem * 100) % 97) % 97
        return check.toString().padStart(2, '0')
    }

    private val DAMM_TABLE = arrayOf(
        intArrayOf(0, 3, 1, 7, 5, 9, 8, 6, 4, 2),
        intArrayOf(7, 0, 9, 2, 1, 5, 4, 8, 6, 3),
        intArrayOf(4, 2, 0, 6, 8, 7, 1, 3, 5, 9),
        intArrayOf(1, 7, 5, 0, 9, 8, 3, 4, 2, 6),
        intArrayOf(6, 1, 2, 3, 0, 4, 5, 9, 7, 8),
        intArrayOf(3, 6, 7, 4, 2, 0, 9, 5, 8, 1),
        intArrayOf(5, 8, 6, 9, 7, 2, 0, 1, 3, 4),
        intArrayOf(8, 9, 4, 5, 3, 6, 2, 0, 1, 7),
        intArrayOf(9, 4, 3, 8, 6, 1, 7, 2, 0, 5),
        intArrayOf(2, 5, 8, 1, 4, 3, 6, 7, 9, 0),
    )

    private val VERHOEFF_D = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
        intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )

    private val VERHOEFF_P = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
        intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )

    private val VERHOEFF_INV = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)
}
