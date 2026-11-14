"""字符集归类与正则字符类推导。

本模块只依赖标准库。核心思路：把"观测到的字符集合"映射到一个"命名字符集"
（数字、大写字母、Base32 等），并给出对应的正则片段；当命名字符集都不合适时，
退化为显式的方括号字符类。
"""

from __future__ import annotations

import string
from typing import Iterable, NamedTuple, Optional

DIGITS = frozenset(string.digits)
UPPER = frozenset(string.ascii_uppercase)
LOWER = frozenset(string.ascii_lowercase)
LETTERS = UPPER | LOWER
ALNUM_UPPER = DIGITS | UPPER
ALNUM_LOWER = DIGITS | LOWER
ALNUM = DIGITS | LETTERS

HEX_UPPER = frozenset("0123456789ABCDEF")
HEX_LOWER = frozenset("0123456789abcdef")
BASE32_CROCKFORD = frozenset("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
BASE32_RFC4648 = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
BASE58 = frozenset("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
BASE64URL = ALNUM | frozenset("-_")


class NamedSet(NamedTuple):
    key: str
    chars: frozenset
    regex: str
    label: str
    prior: float  # 先验权重：越"常见"的字符集权重越高，用于抑制冷门字符集的误判


# 顺序无关，最终按打分选择；prior 用于压制 Base32/Base58 这类冷门集合的过度拟合。
NAMED_SETS: tuple[NamedSet, ...] = (
    NamedSet("digits", DIGITS, r"\d", "纯数字", 1.00),
    NamedSet("upper", UPPER, r"[A-Z]", "大写字母", 1.00),
    NamedSet("lower", LOWER, r"[a-z]", "小写字母", 1.00),
    NamedSet("alnum_upper", ALNUM_UPPER, r"[A-Z0-9]", "大写字母+数字", 1.00),
    NamedSet("alnum_lower", ALNUM_LOWER, r"[a-z0-9]", "小写字母+数字", 1.00),
    NamedSet("alnum", ALNUM, r"[A-Za-z0-9]", "字母+数字", 0.95),
    NamedSet("hex_upper", HEX_UPPER, r"[0-9A-F]", "大写十六进制", 0.95),
    NamedSet("hex_lower", HEX_LOWER, r"[0-9a-f]", "小写十六进制", 0.95),
    NamedSet("base32_crockford", BASE32_CROCKFORD, r"[0-9A-HJKMNP-TV-Z]", "Base32(Crockford)", 0.60),
    NamedSet("base32_rfc4648", BASE32_RFC4648, r"[A-Z2-7]", "Base32(RFC4648)", 0.55),
    NamedSet("base58", BASE58, r"[1-9A-HJ-NP-Za-km-z]", "Base58", 0.50),
    NamedSet("base64url", BASE64URL, r"[A-Za-z0-9\-_]", "Base64URL", 0.45),
)

_BY_KEY = {ns.key: ns for ns in NAMED_SETS}


def is_separator(ch: str) -> bool:
    """非字母数字且可见的字符视为候选分隔符（空格也算）。"""
    return not ch.isalnum()


def char_kind(ch: str) -> str:
    """返回单字符的粗分类，用于人类可读的统计。"""
    if ch.isdigit():
        return "digit"
    if ch in UPPER:
        return "upper"
    if ch in LOWER:
        return "lower"
    if ch.isspace():
        return "space"
    return "symbol"


def class_escape(text: str) -> str:
    """把一串字符转义成可安全放进 [...] 的内容。"""
    out = []
    for ch in text:
        if ch in "\\]^-[":
            out.append("\\" + ch)
        elif ch.isspace():
            out.append(r"\s" if ch == " " else "\\x%02x" % ord(ch))
        else:
            out.append(ch)
    return "".join(out)


def _score(observed: frozenset, ns: NamedSet) -> float:
    if not observed <= ns.chars:
        return -1.0
    coverage = len(observed) / len(ns.chars)
    return coverage * ns.prior


def classify(observed: Iterable[str]) -> tuple[str, str, str, float]:
    """把观测字符集映射为 (key, 正则片段, 中文标签, 覆盖率)。

    选择策略：在所有"包含观测集"的命名字符集中，取 (覆盖率 × 先验权重) 最大者；
    若最优得分低于阈值或观测集含非字母数字字符，则退回显式字符类。
    """
    obs = frozenset(observed)
    if not obs:
        return ("empty", r".", "空", 0.0)

    if any(is_separator(ch) for ch in obs):
        # 含符号：只能是显式字符类（分隔符已在分析阶段单独处理）
        return ("explicit", "[" + class_escape("".join(sorted(obs))) + "]", "自定义符号集", 1.0)

    best: Optional[NamedSet] = None
    best_score = -1.0
    for ns in NAMED_SETS:
        sc = _score(obs, ns)
        if sc > best_score:
            best, best_score = ns, sc

    if best is None or best_score < 0.06:
        # 观测集跨类别且无法归入命名集合
        return ("explicit", "[" + class_escape("".join(sorted(obs))) + "]", "自定义字符集", 1.0)

    coverage = len(obs) / len(best.chars)
    return (best.key, best.regex, best.label, coverage)


def strict_class(observed: Iterable[str]) -> str:
    """严格字符类：只接受观测到的字符（用于"严格模式"校验）。"""
    obs = "".join(sorted(frozenset(observed)))
    if not obs:
        return r"."
    return "[" + class_escape(obs) + "]"


def pool_for(key: str, observed: Iterable[str]) -> str:
    """生成池：命名字符集取全集（更接近真实分布），显式集合取观测集。"""
    ns = _BY_KEY.get(key)
    if ns is not None:
        return "".join(sorted(ns.chars))
    return "".join(sorted(frozenset(observed)))


def describe_charset(chars: Iterable[str]) -> str:
    """生成人类可读的字符集描述，如 "数字(10) 大写字母(26)"。"""
    obs = frozenset(chars)
    if not obs:
        return "空"
    parts = []
    for name, subset in (
        ("数字", DIGITS),
        ("大写字母", UPPER),
        ("小写字母", LOWER),
    ):
        hit = obs & subset
        if hit:
            parts.append("%s(%d)" % (name, len(hit)))
    others = obs - ALNUM
    if others:
        parts.append("符号(%s)" % "".join(sorted(others)))
    return " + ".join(parts)


def named_label(key: str) -> str:
    ns = _BY_KEY.get(key)
    return ns.label if ns else key
