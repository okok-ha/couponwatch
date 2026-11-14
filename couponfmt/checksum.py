"""校验位算法实现与自动识别。

所有算法都是"由载荷推算校验字符"，因此可以用于：
1) 从多个样本反推是否存在校验位；
2) 生成测试码时自动补上合法校验位，使生成结果能通过自身校验。

实现均基于公开的标准算法（Luhn / Damm / Verhoeff / ISO 7064 系列），
不针对任何特定平台的私有算法做逆向。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Optional, Sequence

ALPHABET36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
ALPHABET32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

# --------------------------------------------------------------------------- #
# 基础算法
# --------------------------------------------------------------------------- #

_DAMM_TABLE = (
    (0, 3, 1, 7, 5, 9, 8, 6, 4, 2),
    (7, 0, 9, 2, 1, 5, 4, 8, 6, 3),
    (4, 2, 0, 6, 8, 7, 1, 3, 5, 9),
    (1, 7, 5, 0, 9, 8, 3, 4, 2, 6),
    (6, 1, 2, 3, 0, 4, 5, 9, 7, 8),
    (3, 6, 7, 4, 2, 0, 9, 5, 8, 1),
    (5, 8, 6, 9, 7, 2, 0, 1, 3, 4),
    (8, 9, 4, 5, 3, 6, 2, 0, 1, 7),
    (9, 4, 3, 8, 6, 1, 7, 2, 0, 5),
    (2, 5, 8, 1, 4, 3, 6, 7, 9, 0),
)

_VERHOEFF_D = (
    (0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    (1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
    (2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
    (3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
    (4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
    (5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
    (6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
    (7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
    (8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
    (9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
)

_VERHOEFF_P = (
    (0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    (1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
    (5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
    (8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
    (9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
    (4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
    (2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
    (7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
)


def luhn_digit(payload: str) -> str:
    total = 0
    for i, ch in enumerate(reversed(payload)):
        d = int(ch)
        if i % 2 == 0:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return str((10 - total % 10) % 10)


def damm_digit(payload: str) -> str:
    interim = 0
    for ch in payload:
        interim = _DAMM_TABLE[interim][int(ch)]
    return str(interim)


_VERHOEFF_INV = (0, 4, 3, 2, 1, 5, 6, 7, 8, 9)


def verhoeff_digit(payload: str) -> str:
    """按标准 Verhoeff 生成校验位（校验位位于最右端，位置索引从 0 起）。"""
    c = 0
    for i, ch in enumerate(reversed(payload)):
        c = _VERHOEFF_D[c][_VERHOEFF_P[(i + 1) % 8][int(ch)]]
    return str(_VERHOEFF_INV[c])


def mod97_10(payload: str) -> str:
    num = "".join(str(ALPHABET36.index(ch)) for ch in payload)
    check = 98 - (int(num) * 100) % 97
    return "%02d" % check


def mod11_2(payload: str) -> str:
    """ISO/IEC 7064 MOD 11,2（纯系统）。校验字符 0-9 或 X。"""
    p = 10
    for ch in payload:
        s = (p + ALPHABET36.index(ch)) % 11
        if s == 0:
            s = 11
        p = (s * 2) % 11
    check = (11 - p) % 11
    return "X" if check == 10 else str(check)


def weighted_sum(payload: str, alphabet: str, weights: str = "position") -> str:
    """通用加权和校验：check = sum(value_i * w_i) mod len(alphabet)。

    weights: "position" -> 1,2,3...；"unit" -> 全 1；"alt13" -> 1,3,1,3...
    """
    base = len(alphabet)
    total = 0
    for i, ch in enumerate(payload):
        if ch not in alphabet:
            raise ValueError("char %r not in alphabet" % ch)
        if weights == "unit":
            w = 1
        elif weights == "alt13":
            w = 1 if i % 2 == 0 else 3
        else:
            w = i + 1
        total += alphabet.index(ch) * w
    return alphabet[total % base]


# --------------------------------------------------------------------------- #
# 算法注册表
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class Algorithm:
    key: str
    label: str
    length: int            # 校验字符个数
    check_alphabet: str    # 校验字符允许出现的字符集
    payload_alphabet: str  # 载荷允许出现的字符集
    func: Callable[[str], str]

    def compute(self, payload: str) -> str:
        return self.func(payload)

    def verify(self, full: str) -> bool:
        if len(full) <= self.length:
            return False
        payload, check = full[: -self.length], full[-self.length:]
        if any(ch not in self.payload_alphabet for ch in payload):
            return False
        if any(ch not in self.check_alphabet for ch in check):
            return False
        try:
            return self.compute(payload) == check
        except ValueError:
            return False


ALGORITHMS: tuple[Algorithm, ...] = (
    Algorithm("luhn", "Luhn (MOD 10)", 1, "0123456789", "0123456789", luhn_digit),
    Algorithm("damm", "Damm", 1, "0123456789", "0123456789", damm_digit),
    Algorithm("verhoeff", "Verhoeff", 1, "0123456789", "0123456789", verhoeff_digit),
    Algorithm("mod97_10", "ISO 7064 MOD 97-10", 2, "0123456789", ALPHABET36, mod97_10),
    Algorithm("mod11_2", "ISO 7064 MOD 11-2", 1, "0123456789X", ALPHABET36, mod11_2),
    Algorithm(
        "sum36_pos", "加权和 MOD 36（位置权重）", 1, ALPHABET36, ALPHABET36,
        lambda p: weighted_sum(p, ALPHABET36, "position"),
    ),
    Algorithm(
        "sum36_unit", "求和 MOD 36（等权）", 1, ALPHABET36, ALPHABET36,
        lambda p: weighted_sum(p, ALPHABET36, "unit"),
    ),
    Algorithm(
        "sum32_pos", "加权和 MOD 32（Crockford 字母表）", 1, ALPHABET32, ALPHABET32,
        lambda p: weighted_sum(p, ALPHABET32, "position"),
    ),
    Algorithm(
        "sum10_pos", "加权和 MOD 10（位置权重）", 1, "0123456789", "0123456789",
        lambda p: weighted_sum(p, "0123456789", "position"),
    ),
)

ALGO_BY_KEY = {a.key: a for a in ALGORITHMS}


def compact(code: str) -> str:
    """去掉分隔符并统一大写，得到用于校验计算的紧凑串。"""
    return "".join(ch for ch in code.upper() if ch.isalnum())


def detect(
    codes: Sequence[str],
    min_samples: int = 3,
    max_length: int = 2,
) -> Optional[dict]:
    """在样本中探测校验位。

    返回 {"algorithm", "label", "length", "confidence", "support"} 或 None。
    置信度按"偶然全部命中"的概率估算：1 - base^(1-n)，base 为校验字符集大小。
    """
    samples = [compact(c) for c in codes if c]
    if len(samples) < min_samples:
        return None
    if len({len(s) for s in samples}) != 1:
        return None

    results = []
    for algo in ALGORITHMS:
        if algo.length > max_length:
            continue
        if not all(len(s) > algo.length + 1 for s in samples):
            continue
        if not all(algo.verify(s) for s in samples):
            continue
        base = len(set(algo.check_alphabet))
        confidence = 1.0 - base ** (1 - len(samples))
        results.append(
            {
                "algorithm": algo.key,
                "label": algo.label,
                "length": algo.length,
                "confidence": round(confidence, 6),
                "support": len(samples),
            }
        )

    if not results:
        return None
    # 命中多个时优先取置信度高的；同分时取标准算法（列表靠前）
    results.sort(key=lambda r: (-r["confidence"], ALGORITHMS.index(ALGO_BY_KEY[r["algorithm"]])))
    return results[0]


def apply_checksum(code: str, algorithm: str, length: int) -> str:
    """把 code 末尾 length 个紧凑字符替换为按算法算出的校验字符。

    载荷字符集不满足算法要求时原样返回（例如 Luhn 只适用于数字）。
    """
    algo = ALGO_BY_KEY.get(algorithm)
    if algo is None:
        return code
    positions = [i for i, ch in enumerate(code) if ch.isalnum()]
    if len(positions) <= length:
        return code
    compact_chars = [code[i] for i in positions]
    payload = "".join(compact_chars[:-length]).upper()
    try:
        check = algo.compute(payload)
    except ValueError:
        return code
    new_chars = list(compact_chars)
    for offset, ch in enumerate(check):
        new_chars[len(new_chars) - length + offset] = ch
    out = list(code)
    for idx, ch in zip(positions, new_chars):
        out[idx] = ch
    return "".join(out)


def verify_checksum(code: str, algorithm: str, length: int) -> bool:
    algo = ALGO_BY_KEY.get(algorithm)
    if algo is None:
        return True
    return algo.verify(compact(code))
