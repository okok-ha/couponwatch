package com.couponwatch.detector.format

/**
 * 兑换码格式分析（纯 Kotlin，无 Android 依赖，便于单元测试）。
 *
 * 与后端/平台无关：只根据用户给的样例推导"长度 / 字符集 / 前缀 / 后缀 / 分隔符 / 正则"。
 * 分析结果只描述字符串形态，不代表任何兑换码真实有效。
 */
data class CodeField(
    val kind: String,          // literal | charset
    val value: String,         // literal 内容
    val charset: String,       // 观测到的字符
    val regexClass: String,    // 正则字符类
    val minLen: Int,
    val maxLen: Int,
    val label: String,
)

data class CodeFormat(
    val name: String,
    val samples: List<String>,
    val regex: String,
    val lengthMin: Int,
    val lengthMax: Int,
    val prefix: String,
    val suffix: String,
    val separators: String,
    val charset: String,
    val fields: List<CodeField>,
    val mode: String,
) {
    fun summary(): String = buildString {
        append("模式：").append(mode).append('\n')
        append("长度：").append(if (lengthMin == lengthMax) "$lengthMin" else "$lengthMin~$lengthMax").append('\n')
        append("前缀：").append(if (prefix.isEmpty()) "（无）" else prefix).append('\n')
        append("后缀：").append(if (suffix.isEmpty()) "（无）" else suffix).append('\n')
        append("分隔符：").append(if (separators.isEmpty()) "（无）" else separators).append('\n')
        append("字符集：").append(if (charset.isEmpty()) "（无）" else charset).append('\n')
        append("正则：").append(regex)
    }
}

object CodeFormatAnalyzer {

    private val DIGITS = ('0'..'9').toSet()
    private val UPPER = ('A'..'Z').toSet()
    private val LOWER = ('a'..'z').toSet()
    private val HEX = DIGITS + ('A'..'F').toSet()

    fun analyze(rawSamples: List<String>, name: String = "规则"): CodeFormat {
        val samples = rawSamples.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(samples.isNotEmpty()) { "至少需要一个样例" }

        val lengths = samples.map { it.length }
        val fields: List<CodeField>
        val mode: String

        if (lengths.distinct().size == 1) {
            fields = positionalFields(samples)
            mode = "定长逐位"
        } else {
            val segmented = segmentedFields(samples)
            if (segmented != null) {
                fields = segmented
                mode = "分段"
            } else {
                fields = coarseFields(samples)
                mode = "粗粒度"
            }
        }

        val regex = buildRegex(fields)
        return CodeFormat(
            name = name,
            samples = samples,
            regex = regex,
            lengthMin = lengths.min(),
            lengthMax = lengths.max(),
            prefix = prefixOf(fields),
            suffix = suffixOf(fields),
            separators = fields.filter { it.kind == "literal" && it.value.any { c -> !c.isLetterOrDigit() } }
                .joinToString("") { it.value }.filter { !it.isLetterOrDigit() }.toSet().joinToString(""),
            charset = fields.filter { it.kind == "charset" }.joinToString("") { it.charset }
                .toSet().sorted().joinToString(""),
            fields = fields,
            mode = mode,
        )
    }

    fun matches(code: String, regex: String): Boolean = try {
        Regex(regex).matches(code.trim())
    } catch (e: Exception) {
        false
    }

    /** 对一批样例统计匹配率，用于界面上验证规则是否自洽。 */
    fun matchRate(samples: List<String>, regex: String): Double {
        if (samples.isEmpty()) return 0.0
        val hit = samples.count { matches(it, regex) }
        return hit.toDouble() / samples.size
    }

    // ------------------------------------------------------------------ //

    private fun positionalFields(samples: List<String>): List<CodeField> {
        val n = samples.first().length
        val cols = (0 until n).map { index -> samples.map { it[index] }.toSet() }
        val fields = ArrayList<CodeField>()

        var i = 0
        while (i < n) {
            val chars = cols[i]
            if (chars.size == 1) {
                val ch = chars.first()
                val sb = StringBuilder(ch.toString())
                var j = i + 1
                while (j < n && cols[j].size == 1 &&
                    cols[j].first().isLetterOrDigit() == ch.isLetterOrDigit()
                ) {
                    sb.append(cols[j].first())
                    j++
                }
                fields.add(CodeField("literal", sb.toString(), "", "", sb.length, sb.length, literalLabel(sb.toString())))
                i = j
            } else {
                val start = i
                var j = i + 1
                while (j < n && cols[j].size > 1) j++
                val merged = (start until j).flatMap { cols[it] }.toSet()
                val cls = classify(merged)
                val len = j - start
                fields.add(CodeField("charset", "", merged.sorted().joinToString(""), cls.first, len, len, cls.second))
                i = j
            }
        }
        return fields
    }

    private fun segmentedFields(samples: List<String>): List<CodeField>? {
        val separators = samples.map { s -> s.filter { !it.isLetterOrDigit() }.toSet() }
            .reduce { acc, set -> acc intersect set }
        if (separators.isEmpty()) return null

        val parts = samples.map { s -> s.split(*separators.toCharArray()).filter { it.isNotEmpty() } }
        if (parts.map { it.size }.distinct().size != 1) return null
        if (parts.first().size < 2) return null

        val fields = ArrayList<CodeField>()
        for (index in parts.first().indices) {
            val segSamples = parts.map { it[index] }
            if (segSamples.distinct().size == 1) {
                val text = segSamples.first()
                fields.add(CodeField("literal", text, "", "", text.length, text.length, literalLabel(text)))
            } else {
                val chars = segSamples.flatMap { it.toList() }.toSet()
                val cls = classify(chars)
                fields.add(
                    CodeField(
                        "charset", "", chars.sorted().joinToString(""), cls.first,
                        segSamples.minOf { it.length }, segSamples.maxOf { it.length }, cls.second,
                    )
                )
            }
            if (index < parts.first().lastIndex) {
                fields.add(
                    CodeField(
                        "literal",
                        separators.first().toString(),
                        "", "", 1, 1, "分隔符",
                    )
                )
            }
        }
        return fields
    }

    private fun coarseFields(samples: List<String>): List<CodeField> {
        val prefix = commonPrefix(samples)
        val suffix = commonSuffix(samples)
        val fields = ArrayList<CodeField>()
        if (prefix.isNotEmpty()) {
            fields.add(CodeField("literal", prefix, "", "", prefix.length, prefix.length, "公共前缀"))
        }
        val cores = samples.map { it.removePrefix(prefix).removeSuffix(suffix) }.filter { it.isNotEmpty() }
        if (cores.isNotEmpty()) {
            val chars = cores.flatMap { it.toList() }.toSet()
            val cls = classify(chars)
            fields.add(
                CodeField(
                    "charset", "", chars.sorted().joinToString(""), cls.first,
                    cores.minOf { it.length }, cores.maxOf { it.length }, cls.second,
                )
            )
        }
        if (suffix.isNotEmpty()) {
            fields.add(CodeField("literal", suffix, "", "", suffix.length, suffix.length, "公共后缀"))
        }
        return fields
    }

    private fun buildRegex(fields: List<CodeField>): String = buildString {
        append('^')
        fields.forEach { field ->
            if (field.kind == "literal") {
                append(Regex.escape(field.value))
            } else {
                append(field.regexClass)
                when {
                    field.minLen == field.maxLen && field.minLen == 1 -> Unit
                    field.minLen == field.maxLen -> append('{').append(field.minLen).append('}')
                    else -> append('{').append(field.minLen).append(',').append(field.maxLen).append('}')
                }
            }
        }
        append('$')
    }

    private fun classify(chars: Set<Char>): Pair<String, String> = when {
        chars.all { it in DIGITS } -> "\\d" to "数字"
        chars.all { it in HEX } && chars.any { it in UPPER } -> "[0-9A-F]" to "十六进制"
        chars.all { it in UPPER } -> "[A-Z]" to "大写字母"
        chars.all { it in LOWER } -> "[a-z]" to "小写字母"
        chars.all { it in DIGITS + UPPER } -> "[A-Z0-9]" to "大写字母+数字"
        chars.all { it in DIGITS + LOWER } -> "[a-z0-9]" to "小写字母+数字"
        chars.all { it.isLetterOrDigit() } -> "[A-Za-z0-9]" to "字母+数字"
        else -> "[" + chars.sorted().joinToString("") { escapeInClass(it) } + "]" to "自定义字符集"
    }

    private fun escapeInClass(ch: Char): String = when (ch) {
        '\\', ']', '^', '-' -> "\\" + ch
        else -> ch.toString()
    }

    private fun literalLabel(text: String): String =
        if (text.all { !it.isLetterOrDigit() }) "分隔符" else "固定片段"

    private fun prefixOf(fields: List<CodeField>): String {
        val sb = StringBuilder()
        for (field in fields) {
            if (field.kind == "literal" && field.value.all { it.isLetterOrDigit() }) sb.append(field.value) else break
        }
        return sb.toString()
    }

    private fun suffixOf(fields: List<CodeField>): String {
        val sb = StringBuilder()
        for (field in fields.asReversed()) {
            if (field.kind == "literal" && field.value.all { it.isLetterOrDigit() }) sb.insert(0, field.value) else break
        }
        return sb.toString()
    }

    private fun commonPrefix(samples: List<String>): String {
        if (samples.isEmpty()) return ""
        var prefix = samples.first()
        samples.forEach { s ->
            var i = 0
            while (i < prefix.length && i < s.length && prefix[i] == s[i]) i++
            prefix = prefix.substring(0, i)
        }
        return prefix
    }

    private fun commonSuffix(samples: List<String>): String {
        if (samples.isEmpty()) return ""
        val reversed = samples.map { it.reversed() }
        return commonPrefix(reversed).reversed()
    }
}
