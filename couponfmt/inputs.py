"""样例归一化与输入校验。"""

from __future__ import annotations

import re
from typing import Iterable, List

MAX_CODE_LEN = 256
MIN_CODE_LEN = 2


class InputError(ValueError):
    """用户输入不合法时抛出，CLI 会把它转成友好提示。"""


def normalize_code(raw: str) -> str:
    """去掉首尾空白与不可见的零宽字符，内部空白保留（可能是分隔符）。"""
    text = raw.strip().strip("\ufeff")
    text = text.replace("\u200b", "").replace("\u3000", " ")
    return text


def normalize_codes(raw_codes: Iterable[str]) -> List[str]:
    """批量归一化：去空行、去重、保留顺序、校验合法性。"""
    out: List[str] = []
    seen = set()
    invalid: List[str] = []
    for raw in raw_codes:
        code = normalize_code(raw)
        if not code:
            continue
        if len(code) > MAX_CODE_LEN:
            invalid.append(code)
            continue
        if not code.isprintable():
            invalid.append(code)
            continue
        if code not in seen:
            seen.add(code)
            out.append(code)
    if invalid:
        raise InputError(
            "以下样例包含不可打印字符或长度超过 %d，已中止：%s"
            % (MAX_CODE_LEN, ", ".join(repr(c[:20]) for c in invalid[:5]))
        )
    if not out:
        raise InputError("没有有效样例，请至少输入 1 个兑换码样例。")
    return out


def split_input_text(text: str) -> List[str]:
    """把多行/逗号/分号/空格混合粘贴的文本切成样例列表。"""
    tokens = re.split(r"[\r\n,;，；]+", text)
    return [t for t in (normalize_code(x) for x in tokens) if t]


def load_codes_from_file(path: str) -> List[str]:
    """从文本文件读取兑换码，一行一个，忽略 # 注释与空行。"""
    codes: List[str] = []
    with open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            codes.append(line)
    return codes
