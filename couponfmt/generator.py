"""按规则生成离线测试字符串。"""

from __future__ import annotations

import math
import random
import secrets
from typing import Dict, List, Optional

from . import checksum as checksum_mod
from .models import FormatRule, KIND_CHARSET

AMBIGUOUS = set("0O1Il|")


class GenerateError(RuntimeError):
    pass


def _pool_for(field, mode: str, avoid_ambiguous: bool) -> str:
    pool = field.gen_charset if mode == "class" else field.charset
    if not pool:
        pool = field.charset or field.gen_charset
    if avoid_ambiguous:
        filtered = "".join(ch for ch in pool if ch not in AMBIGUOUS)
        if filtered:
            pool = filtered
    if not pool:
        raise GenerateError(
            "字段 %r 没有可用字符池，请检查规则配置。" % (field.regex_class or field.label)
        )
    return pool


def _random_chars(pool: str, length: int, rng: random.Random, crypto: bool) -> str:
    if crypto:
        return "".join(secrets.choice(pool) for _ in range(length))
    return "".join(rng.choice(pool) for _ in range(length))


def generate_one(
    rule: FormatRule,
    rng: Optional[random.Random] = None,
    pool_mode: str = "class",
    avoid_ambiguous: bool = False,
    apply_checksum: bool = True,
    crypto: bool = False,
) -> str:
    """按规则生成 1 个测试码。"""
    rng = rng or random.Random()
    parts: List[str] = []
    for field in rule.fields:
        if field.kind == KIND_CHARSET:
            length = field.min_len if field.min_len == field.max_len else rng.randint(field.min_len, field.max_len)
            pool = _pool_for(field, pool_mode, avoid_ambiguous)
            parts.append(_random_chars(pool, length, rng, crypto))
        else:
            parts.append(field.value)
    code = "".join(parts)

    if apply_checksum and rule.checksum:
        code = checksum_mod.apply_checksum(
            code, rule.checksum.get("algorithm", ""), int(rule.checksum.get("length", 1))
        )
    return code


def generate(
    rule: FormatRule,
    count: int = 10,
    seed: Optional[int] = None,
    unique: bool = True,
    pool_mode: str = "class",
    avoid_ambiguous: bool = False,
    apply_checksum: bool = True,
    crypto: bool = False,
) -> List[str]:
    """批量生成测试码。

    unique=True 时保证结果不重复；若规则空间过小导致无法满足数量，会提前返回。
    """
    if count <= 0:
        raise GenerateError("生成数量必须为正整数。")
    if not rule.fields:
        raise GenerateError("当前没有可用规则，请先分析样例或加载规则文件。")

    space = search_space(rule, pool_mode=pool_mode, avoid_ambiguous=avoid_ambiguous)
    if unique and space < count:
        raise GenerateError(
            "规则空间仅约 %d 个组合，无法生成 %d 个不重复测试码。请减少数量或放宽规则。"
            % (space, count)
        )

    rng = random.Random(seed) if seed is not None else random.Random()
    results: List[str] = []
    seen = set()
    attempts = 0
    max_attempts = max(count * 50, 1000)
    while len(results) < count and attempts < max_attempts:
        attempts += 1
        code = generate_one(
            rule,
            rng=rng,
            pool_mode=pool_mode,
            avoid_ambiguous=avoid_ambiguous,
            apply_checksum=apply_checksum,
            crypto=crypto,
        )
        if unique:
            if code in seen:
                continue
            seen.add(code)
        results.append(code)
    if len(results) < count:
        raise GenerateError(
            "仅生成 %d/%d 个不重复测试码，规则空间可能不足（估计 %d 个组合）。"
            % (len(results), count, space)
        )
    return results


def search_space(
    rule: FormatRule,
    pool_mode: str = "class",
    avoid_ambiguous: bool = False,
) -> int:
    """估算规则描述的组合空间大小（用于判断能否满足去重要求）。"""
    total = 1
    for field in rule.fields:
        if field.kind != KIND_CHARSET:
            continue
        try:
            pool = len(_pool_for(field, pool_mode, avoid_ambiguous))
        except GenerateError:
            pool = 0
        if pool == 0:
            return 0
        if field.min_len == field.max_len:
            total *= pool ** field.min_len
        else:
            total *= sum(pool ** n for n in range(field.min_len, field.max_len + 1))
        if total > 10 ** 18:
            return 10 ** 18
    return total


def entropy_bits(rule: FormatRule, pool_mode: str = "class") -> float:
    """规则空间的信息熵（bit），用于评估测试码的可猜测性。"""
    space = search_space(rule, pool_mode=pool_mode)
    if space <= 1:
        return 0.0
    return round(math.log2(space), 2)


def format_distribution(rule: FormatRule) -> Dict[str, object]:
    """给出各字段的取值规模，便于报告展示。"""
    rows = []
    for i, f in enumerate(rule.fields, 1):
        if f.kind == KIND_CHARSET:
            pool = len(f.gen_charset) or len(f.charset)
            rows.append(
                {
                    "index": i,
                    "type": "charset",
                    "label": f.label,
                    "regex": f.regex_class,
                    "min_len": f.min_len,
                    "max_len": f.max_len,
                    "pool": pool,
                    "combinations": pool ** f.max_len,
                }
            )
        else:
            rows.append(
                {
                    "index": i,
                    "type": "literal",
                    "label": f.label,
                    "regex": repr(f.value),
                    "min_len": len(f.value),
                    "max_len": len(f.value),
                    "pool": 1,
                    "combinations": 1,
                }
            )
    return {"fields": rows}
