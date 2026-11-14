"""格式校验：正则匹配 + 校验位验证 + 逐字段解释。

注意：格式匹配只表示字符串符合归纳出的规则，不代表兑换码在真实平台上有效。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field as dc_field
from typing import Dict, List, Sequence

from . import checksum as checksum_mod
from .models import FormatRule, KIND_LITERAL


@dataclass
class ValidationResult:
    code: str
    ok: bool = False
    regex_ok: bool = False
    checksum_ok: bool = True
    length_ok: bool = True
    issues: List[str] = dc_field(default_factory=list)
    mode: str = "general"   # general | strict

    def to_row(self) -> Dict[str, object]:
        return {
            "code": self.code,
            "ok": "PASS" if self.ok else "FAIL",
            "regex_ok": "1" if self.regex_ok else "0",
            "checksum_ok": "1" if self.checksum_ok else "0",
            "length_ok": "1" if self.length_ok else "0",
            "mode": self.mode,
            "issues": "; ".join(self.issues),
        }


def validate(
    code: str,
    rule: FormatRule,
    strict: bool = False,
    check_checksum: bool = True,
) -> ValidationResult:
    """校验单个字符串是否符合规则。"""
    text = code.strip()
    result = ValidationResult(code=text, mode="strict" if strict else "general")

    if not text:
        result.issues.append("空字符串")
        return result

    if not (rule.length_min <= len(text) <= rule.length_max):
        result.length_ok = False
        result.issues.append(
            "长度 %d 不在规则范围 %d~%d 内" % (len(text), rule.length_min, rule.length_max)
        )

    pattern = rule.compile(strict=strict)
    if pattern.fullmatch(text):
        result.regex_ok = True
    else:
        result.issues.append("不匹配正则 %s" % rule.to_regex(strict=strict))
        _explain_mismatch(text, rule, result)

    if check_checksum and rule.checksum:
        algo = rule.checksum.get("algorithm", "")
        length = int(rule.checksum.get("length", 1))
        if checksum_mod.verify_checksum(text, algo, length):
            result.checksum_ok = True
        else:
            result.checksum_ok = False
            result.issues.append("校验位不符合 %s" % rule.checksum.get("label", algo))

    result.ok = result.regex_ok and result.checksum_ok and result.length_ok
    return result


def _explain_mismatch(code: str, rule: FormatRule, result: ValidationResult) -> None:
    """尝试定位第一个不匹配的位置，给出可读解释。"""
    pos = 0
    for idx, field in enumerate(rule.fields, 1):
        if field.kind == KIND_LITERAL:
            expected = field.value
            actual = code[pos: pos + len(expected)]
            if actual != expected:
                result.issues.append(
                    "第 %d 段应为固定文本 %r，实际为 %r" % (idx, expected, actual)
                )
                return
            pos += len(expected)
        else:
            remaining = code[pos:]
            if not remaining:
                result.issues.append("第 %d 段缺失，规则要求 %s 至少 %d 位" % (idx, field.label, field.min_len))
                return
            if len(remaining) < field.min_len:
                result.issues.append(
                    "第 %d 段长度不足：需要 %d~%d 位，实际仅剩 %d 位"
                    % (idx, field.min_len, field.max_len, len(remaining))
                )
                return
            pattern = re.compile(field.regex_class + r"{%d,%d}" % (field.min_len, field.max_len), re.ASCII)
            # 贪心匹配最长合法前缀
            matched = ""
            for size in range(min(field.max_len, len(remaining)), field.min_len - 1, -1):
                chunk = remaining[:size]
                if re.fullmatch(field.regex_class + "{%d}" % size, chunk, re.ASCII):
                    matched = chunk
                    break
            if not matched:
                bad = remaining[0]
                result.issues.append(
                    "第 %d 段第 %d 个字符 %r 不符合 %s（%s）"
                    % (idx, pos + 1, bad, field.regex_class, field.label)
                )
                return
            pos += len(matched)
    if pos != len(code):
        result.issues.append("末尾存在多余字符 %r" % code[pos:])


def validate_batch(
    codes: Sequence[str],
    rule: FormatRule,
    strict: bool = False,
    check_checksum: bool = True,
) -> List[ValidationResult]:
    return [validate(c, rule, strict=strict, check_checksum=check_checksum) for c in codes]


def pass_rate(results: Sequence[ValidationResult]) -> float:
    if not results:
        return 0.0
    return sum(1 for r in results if r.ok) / len(results)


def explain(rule: FormatRule) -> List[str]:
    """把规则翻译成人类可读的逐段说明。"""
    lines = []
    pos = 1
    for idx, f in enumerate(rule.fields, 1):
        if f.kind == KIND_LITERAL:
            span = "%d" % pos if len(f.value) == 1 else "%d-%d" % (pos, pos + len(f.value) - 1)
            kind = "分隔符" if f.is_separator else "固定文本"
            lines.append("段%-2d 位置 %-7s %-6s %r" % (idx, span, kind, f.value))
            pos += len(f.value)
        else:
            span = "%d" % pos if f.max_len == 1 else "%d-%d" % (pos, pos + f.max_len - 1)
            length = str(f.min_len) if f.min_len == f.max_len else "%d~%d" % (f.min_len, f.max_len)
            lines.append(
                "段%-2d 位置 %-7s %-6s %s × %s  观测字符 %d 个"
                % (idx, span, "可变字段", f.label + " " + f.regex_class, length, len(f.charset))
            )
            pos += f.max_len
    return lines
