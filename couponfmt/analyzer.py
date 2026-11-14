"""格式分析引擎：从一组样例动态归纳格式规则。

分析分三条路径：
1. positional  —— 所有样例等长：逐位统计，合并同类相邻位；
2. segmented   —— 长度不等但分隔符结构一致：按分隔符切段，逐段统计长度区间；
3. coarse      —— 上述都不成立：提取公共前后缀，其余部分按整体字符集处理。

三条路径都只依赖样例本身，不包含任何硬编码的兑换码格式。
"""

from __future__ import annotations

import os
from typing import List, Optional, Sequence, Tuple

from . import checksum as checksum_mod
from .charset import classify, pool_for, strict_class
from .inputs import normalize_codes
from .models import Field, FormatRule, KIND_CHARSET, KIND_LITERAL

DEFAULT_MIN_FIXED_SUPPORT = 2


# --------------------------------------------------------------------------- #
# 工具函数
# --------------------------------------------------------------------------- #


def _make_literal(text: str, label: str = "") -> Field:
    if not label:
        if all(not ch.isalnum() for ch in text):
            label = "分隔符"
        elif text.isalnum():
            label = "固定片段"
        else:
            label = "固定字符"
    return Field(
        kind=KIND_LITERAL,
        value=text,
        min_len=len(text),
        max_len=len(text),
        label=label,
        regex_class="",
        gen_charset="",
        strict_class="",
    )


def _make_charset_field(
    chars: set,
    min_len: int,
    max_len: int,
    label_suffix: str = "",
) -> Field:
    key, frag, label, coverage = classify(chars)
    charset = "".join(sorted(chars))
    pool = pool_for(key, chars)
    return Field(
        kind=KIND_CHARSET,
        charset=charset,
        regex_class=frag,
        gen_charset=pool,
        min_len=min_len,
        max_len=max_len,
        label=(label + label_suffix),
        class_key=key,
        strict_class=strict_class(chars),
        observed_ratio=(len(charset) / len(pool)) if pool else 1.0,
    )


def _can_merge(prev: Field, new_key: str, new_regex: str) -> bool:
    return (
        prev.kind == KIND_CHARSET
        and prev.class_key == new_key
        and prev.regex_class == new_regex
        and prev.min_len == prev.max_len  # 变长字段不与定长字段合并
    )


def _common_prefix(strings: Sequence[str]) -> str:
    if not strings:
        return ""
    return os.path.commonprefix(list(strings))


def _common_suffix(strings: Sequence[str]) -> str:
    if not strings:
        return ""
    reversed_strings = [s[::-1] for s in strings]
    return os.path.commonprefix(reversed_strings)[::-1]


def _confidence_for(mode: str, n_samples: int) -> float:
    base = {1: 0.30, 2: 0.60, 3: 0.80, 4: 0.88}.get(n_samples, 0.94)
    if mode == "segmented":
        base = max(0.25, base - 0.05)
    elif mode == "coarse":
        base = min(base, 0.45)
    elif mode == "single":
        base = 0.30
    return round(base, 4)


# --------------------------------------------------------------------------- #
# 路径一：定长逐位分析
# --------------------------------------------------------------------------- #


def _analyze_positional(samples: Sequence[str]) -> List[Field]:
    """逐位统计 + 可变段整体归类。

    1. 先判断每个位置在所有样例中是否恒定（恒定 = 固定字面量）；
    2. 连续固定位合并为字面量，但在"字母数字 ↔ 符号"边界处断开，
       这样前缀、分隔符、固定年份等能被正确区分；
    3. 连续可变位视为一个字段，用整段字符的并集统一归类，
       避免因个别位置样本不足而被切成 [A-Z0-9][A-Z][A-Z0-9] 这类碎片；
    4. 仅当两侧字符集互斥且证据充分（覆盖率 ≥ 0.6）时才在段内再切分。
    """
    n = len(samples[0])
    col_chars = [{s[i] for s in samples} for i in range(n)]
    fixed = [len(c) == 1 for c in col_chars]

    def same_kind(a: str, b: str) -> bool:
        return a.isalnum() == b.isalnum()

    def strong_boundary(prev: set, cur: set) -> bool:
        k1, _f1, _l1, c1 = classify(prev)
        k2, _f2, _l2, c2 = classify(cur)
        if k1 == k2:
            return False
        return c1 >= 0.6 and c2 >= 0.6 and not (prev & cur)

    fields: List[Field] = []
    i = 0
    while i < n:
        if fixed[i]:
            j = i
            while j + 1 < n and fixed[j + 1] and same_kind(samples[0][j + 1], samples[0][j]):
                j += 1
            fields.append(_make_literal(samples[0][i: j + 1]))
            i = j + 1
            continue

        j = i
        while j + 1 < n and not fixed[j + 1]:
            j += 1

        cuts = [i]
        for k in range(i + 1, j + 1):
            if strong_boundary(col_chars[k - 1], col_chars[k]):
                cuts.append(k)
        cuts.append(j + 1)

        for a, b in zip(cuts, cuts[1:]):
            chars: set = set()
            for pos in range(a, b):
                chars |= col_chars[pos]
            fields.append(_make_charset_field(chars, b - a, b - a))
        i = j + 1

    return fields


# --------------------------------------------------------------------------- #
# 路径二：按分隔符分段
# --------------------------------------------------------------------------- #


def _tokenize(code: str, sepchars: set) -> List[Tuple[str, str]]:
    tokens: List[Tuple[str, str]] = []
    buf: List[str] = []
    for ch in code:
        if ch in sepchars:
            if buf:
                tokens.append(("seg", "".join(buf)))
                buf = []
            tokens.append(("sep", ch))
        else:
            buf.append(ch)
    if buf:
        tokens.append(("seg", "".join(buf)))
    return tokens


def _try_segmented(samples: Sequence[str]) -> Optional[List[Field]]:
    common = set(samples[0])
    for s in samples[1:]:
        common &= set(s)
    sepchars = {ch for ch in common if not ch.isalnum()}
    if not sepchars:
        return None

    token_lists = [_tokenize(s, sepchars) for s in samples]
    shape = {tuple(k for k, _ in tl) for tl in token_lists}
    if len(shape) != 1 or len(token_lists[0]) < 3:
        return None

    fields: List[Field] = []
    for idx, (kind, _text) in enumerate(token_lists[0]):
        texts = [tl[idx][1] for tl in token_lists]
        if kind == "sep":
            uniq = set(texts)
            if len(uniq) == 1:
                fields.append(_make_literal(texts[0]))
            else:
                fields.append(_make_charset_field(uniq, 1, 1))
            continue

        if len(set(texts)) == 1 and len(samples) >= DEFAULT_MIN_FIXED_SUPPORT:
            fields.append(_make_literal(texts[0], "固定片段"))
            continue

        chars = {ch for t in texts for ch in t}
        lengths = [len(t) for t in texts]
        fields.append(_make_charset_field(chars, min(lengths), max(lengths)))
    return fields


# --------------------------------------------------------------------------- #
# 路径三：粗粒度（公共前后缀 + 整体字符集）
# --------------------------------------------------------------------------- #


def _analyze_coarse(samples: Sequence[str]) -> List[Field]:
    prefix = _common_prefix(samples)
    suffix = _common_suffix(samples)
    if len(prefix) + len(suffix) > min(len(s) for s in samples):
        # 前后缀重叠，说明样本过于相似，退化为纯整体分析
        prefix, suffix = "", ""

    cores = [s[len(prefix): len(s) - len(suffix) if suffix else len(s)] for s in samples]
    cores = [c for c in cores if c]
    fields: List[Field] = []
    if prefix:
        fields.append(_make_literal(prefix, "公共前缀"))
    if cores:
        chars = {ch for c in cores for ch in c}
        lengths = [len(c) for c in cores]
        fields.append(_make_charset_field(chars, min(lengths), max(lengths), "（变长）"))
    if suffix:
        fields.append(_make_literal(suffix, "公共后缀"))
    return fields


# --------------------------------------------------------------------------- #
# 单样本
# --------------------------------------------------------------------------- #


def _analyze_single(sample: str) -> List[Field]:
    fields: List[Field] = []
    buf: List[str] = []

    def flush() -> None:
        if not buf:
            return
        text = "".join(buf)
        chars = set(text)
        if len(chars) == 1 and not text[0].isalnum():
            fields.append(_make_literal(text))
        else:
            fields.append(_make_charset_field(chars, len(text), len(text)))
        buf.clear()

    for ch in sample:
        if not ch.isalnum():
            flush()
            fields.append(_make_literal(ch))
        else:
            buf.append(ch)
    flush()
    return fields


# --------------------------------------------------------------------------- #
# 主入口
# --------------------------------------------------------------------------- #


def analyze(
    raw_samples: Sequence[str],
    name: str = "default",
    detect_checksum: bool = True,
    min_checksum_samples: int = 3,
    max_checksum_len: int = 2,
) -> FormatRule:
    """从样例归纳格式规则。样例变化时重新调用即可，无需改动代码。"""
    samples = normalize_codes(raw_samples)
    notes: List[str] = []
    lengths = {len(s) for s in samples}

    if len(samples) == 1:
        fields = _analyze_single(samples[0])
        mode = "single"
        notes.append("仅 1 个样本，无法区分固定片段与可变片段，建议补充 3 个以上样例后重新分析。")
    elif len(lengths) == 1:
        fields = _analyze_positional(samples)
        mode = "positional"
        notes.append("所有样例等长，采用逐位统计；同一位置出现多个字符即视为可变字段。")
    else:
        fields = _try_segmented(samples)
        if fields is not None:
            mode = "segmented"
            notes.append("样例长度不一致，按分隔符切段后逐段统计长度区间。")
        else:
            fields = _analyze_coarse(samples)
            mode = "coarse"
            notes.append("样例长度与分隔结构都不一致，只能给出粗粒度规则，建议补充同构样例。")

    rule = FormatRule(
        name=name,
        samples=list(samples),
        fields=fields,
        mode=mode,
        length_min=min(lengths),
        length_max=max(lengths),
        confidence=_confidence_for(mode, len(samples)),
        notes=notes,
    )

    # 校验位探测（仅在同长度紧凑串上做）
    if detect_checksum:
        compacts = [checksum_mod.compact(s) for s in samples]
        if len({len(c) for c in compacts}) == 1:
            found = checksum_mod.detect(
                samples,
                min_samples=min_checksum_samples,
                max_length=max_checksum_len,
            )
            if found:
                rule.checksum = found
                notes.append(
                    "在末 %d 位检出疑似校验位：%s（基于 %d 个样本，置信度 %.2f%%）。"
                    % (found["length"], found["label"], found["support"], found["confidence"] * 100)
                )
            else:
                notes.append(
                    "未检出常见校验位算法（Luhn/Damm/Verhoeff/ISO 7064/加权和）。"
                    "这不代表没有校验位，也可能使用了私有算法。"
                )
        else:
            notes.append("样例长度不一致，跳过校验位探测。")

    # 覆盖率提示
    for f in rule.fields:
        if f.kind == KIND_CHARSET and f.class_key not in ("explicit",) and f.observed_ratio < 0.5:
            notes.append(
                "字段 %r 仅观测到 %d/%d 个字符，正则按命名字符集 %s 泛化；"
                "如需严格匹配请使用严格模式。"
                % (f.regex_class, len(f.charset), len(f.gen_charset), f.label)
            )
            break

    return rule


def analyze_to_rule(
    raw_samples: Sequence[str],
    name: str = "default",
    **kwargs,
) -> FormatRule:
    """analyze 的别名，便于外部调用时语义更清晰。"""
    return analyze(raw_samples, name=name, **kwargs)
