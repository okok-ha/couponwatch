"""统计报告：长度分布、字符频率、格式匹配率等。"""

from __future__ import annotations

import collections
import math
import unicodedata
from typing import Dict, List, Optional, Sequence

from . import generator, validator
from .charset import describe_charset
from .models import FormatRule


def _w(text: str) -> int:
    """按东亚字符宽度计算显示宽度，保证中文表格对齐。"""
    return sum(2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1 for ch in text)


def _pad(text: str, width: int) -> str:
    return text + " " * max(0, width - _w(text))


def _entropy(counter: collections.Counter, total: int) -> float:
    if total <= 0:
        return 0.0
    h = 0.0
    for _, count in counter.items():
        p = count / total
        h -= p * math.log2(p)
    return round(h, 4)


def analyze_codes(codes: Sequence[str], rule: Optional[FormatRule] = None) -> Dict[str, object]:
    """对一批字符串做统计，可选地附带规则匹配率。"""
    codes = [c for c in codes if c]
    total = len(codes)
    length_counter = collections.Counter(len(c) for c in codes)
    char_counter: collections.Counter = collections.Counter()
    for c in codes:
        char_counter.update(c)

    kind_counter: collections.Counter = collections.Counter()
    for ch, n in char_counter.items():
        if ch.isdigit():
            kind_counter["数字"] += n
        elif ch.isupper():
            kind_counter["大写字母"] += n
        elif ch.islower():
            kind_counter["小写字母"] += n
        elif ch.isspace():
            kind_counter["空白"] += n
        else:
            kind_counter["符号"] += n

    total_chars = sum(char_counter.values())
    stats: Dict[str, object] = {
        "total": total,
        "unique": len(set(codes)),
        "duplicates": total - len(set(codes)),
        "length_distribution": dict(sorted(length_counter.items())),
        "length_min": min(length_counter) if length_counter else 0,
        "length_max": max(length_counter) if length_counter else 0,
        "char_total": total_chars,
        "char_distinct": len(char_counter),
        "char_frequency": char_counter.most_common(),
        "char_entropy": _entropy(char_counter, total_chars),
        "kind_distribution": dict(kind_counter),
        "charset_summary": describe_charset(char_counter.keys()),
        "prefix_counter": collections.Counter(c[:3] for c in codes).most_common(5),
    }

    if rule is not None:
        results = validator.validate_batch(codes, rule)
        strict_results = validator.validate_batch(codes, rule, strict=True)
        stats["match_rate"] = round(validator.pass_rate(results), 4)
        stats["match_rate_strict"] = round(validator.pass_rate(strict_results), 4)
        stats["checksum_pass_rate"] = round(
            sum(1 for r in results if r.checksum_ok) / total, 4
        ) if total else 0.0
        stats["fail_examples"] = [
            {"code": r.code, "issues": r.issues} for r in results if not r.ok
        ][:5]
        stats["rule_regex"] = rule.to_regex()
        stats["search_space"] = generator.search_space(rule)
        stats["entropy_bits"] = generator.entropy_bits(rule)

    return stats


def format_report(stats: Dict[str, object], rule: Optional[FormatRule] = None) -> str:
    lines: List[str] = []
    add = lines.append

    add("=" * 68)
    add(" 兑换码格式统计报告（本地离线分析，仅代表格式符合性）")
    add("=" * 68)
    add("")
    add("【1】基本信息")
    add("  样本/测试码总数 : %d" % stats["total"])
    add("  去重后数量      : %d" % stats["unique"])
    add("  重复数量        : %d" % stats["duplicates"])
    add("  总字符数        : %d" % stats["char_total"])
    add("  不同字符数      : %d" % stats["char_distinct"])
    add("  字符集构成      : %s" % stats["charset_summary"])
    add("")

    add("【2】长度分布")
    add("  " + _pad("长度", 8) + _pad("数量", 8) + _pad("占比", 9) + "直方图")
    for length, count in stats["length_distribution"].items():
        ratio = count / stats["total"] if stats["total"] else 0
        bar = "#" * max(1, int(round(ratio * 40)))
        add("  " + _pad(str(length), 8) + _pad(str(count), 8) + _pad("%.1f%%" % (ratio * 100), 9) + bar)
    add("")

    add("【3】字符出现频率（Top 20）")
    top = stats["char_frequency"][:20]
    if top:
        add("  " + _pad("字符", 6) + _pad("次数", 8) + _pad("占比", 9) + "直方图")
        for ch, count in top:
            ratio = count / stats["char_total"] if stats["char_total"] else 0
            shown = ch if ch.isprintable() and ch != " " else repr(ch)
            add("  " + _pad(shown, 6) + _pad(str(count), 8) + _pad("%.2f%%" % (ratio * 100), 9)
                + "#" * max(1, int(ratio * 40)))
    else:
        add("  （无数据）")
    add("  字符香农熵      : %.4f bit/字符" % stats["char_entropy"])
    add("")

    add("【4】字符类别分布")
    for kind, count in stats["kind_distribution"].items():
        ratio = count / stats["char_total"] if stats["char_total"] else 0
        add("  " + _pad(kind, 10) + _pad(str(count), 8) + "%.2f%%" % (ratio * 100))
    add("")

    if "match_rate" in stats:
        add("【5】格式匹配率")
        add("  通用模式匹配率  : %.2f%%" % (stats["match_rate"] * 100))
        add("  严格模式匹配率  : %.2f%%" % (stats["match_rate_strict"] * 100))
        add("  校验位通过率    : %.2f%%" % (stats["checksum_pass_rate"] * 100))
        add("  规则正则        : %s" % stats["rule_regex"])
        add("  规则空间        : %s 种组合（约 %.2f bit 熵）"
            % (_human_int(stats["search_space"]), stats["entropy_bits"]))
        if stats["match_rate_strict"] + 0.01 < stats["match_rate"]:
            add("  说明            : 严格模式只接受样本中出现过的字符，"
                "因此按字符集全集生成的测试码在严格模式下匹配率天然偏低，属正常现象。")
        if stats.get("fail_examples"):
            add("  未通过示例      :")
            for item in stats["fail_examples"]:
                add("    - %s  ->  %s" % (item["code"], "; ".join(item["issues"])))
        add("")

    if rule is not None:
        add("【6】规则字段明细")
        for line in validator.explain(rule):
            add("  " + line)
        add("")

    add("说明：以上结论仅描述字符串层面的格式特征，不代表任何兑换码在真实平台上的有效性。")
    add("=" * 68)
    return "\n".join(lines)


def _human_int(value: object) -> str:
    try:
        n = int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return str(value)
    if n >= 10 ** 12:
        return "%.2e" % n
    return "{:,}".format(n)
