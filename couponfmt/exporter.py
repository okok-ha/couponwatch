"""导出模块：TXT / CSV 输出。"""

from __future__ import annotations

import csv
import os
from typing import Dict, List, Optional, Sequence

from .models import FormatRule

DEFAULT_HEADER = (
    "# 由 couponfmt 生成的本地离线测试数据\n"
    "# 仅用于本地格式研究与程序自测，不代表任何真实兑换码\n"
)


def ensure_parent(path: str) -> None:
    parent = os.path.dirname(os.path.abspath(path))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)


def export_txt(
    path: str,
    codes: Sequence[str],
    header: Optional[str] = DEFAULT_HEADER,
    rule: Optional[FormatRule] = None,
) -> str:
    """导出纯文本，一行一个码，UTF-8（无 BOM）。"""
    ensure_parent(path)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        if header:
            fh.write(header)
        if rule is not None:
            fh.write("# 规则: %s\n" % rule.to_regex())
            fh.write("# 生成时间: %s\n" % rule.created_at)
        for code in codes:
            fh.write(code + "\n")
    return os.path.abspath(path)


def export_csv(
    path: str,
    rows: Sequence[Dict[str, object]],
    fieldnames: Optional[Sequence[str]] = None,
    bom: bool = True,
) -> str:
    """导出 CSV，默认带 UTF-8 BOM 以便 Excel 正确识别中文。"""
    ensure_parent(path)
    if not rows:
        raise ValueError("没有可导出的数据行。")
    names = list(fieldnames) if fieldnames else list(rows[0].keys())
    encoding = "utf-8-sig" if bom else "utf-8"
    with open(path, "w", encoding=encoding, newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=names, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow(row)
    return os.path.abspath(path)


def codes_to_rows(codes: Sequence[str], rule: Optional[FormatRule] = None) -> List[Dict[str, object]]:
    """把码列表转成可导出的行（含长度、是否匹配规则）。"""
    rows: List[Dict[str, object]] = []
    if rule is None:
        for i, code in enumerate(codes, 1):
            rows.append({"index": i, "code": code, "length": len(code)})
        return rows

    from . import validator

    for i, code in enumerate(codes, 1):
        res = validator.validate(code, rule)
        rows.append(
            {
                "index": i,
                "code": code,
                "length": len(code),
                "match": "PASS" if res.ok else "FAIL",
                "regex_ok": "1" if res.regex_ok else "0",
                "checksum_ok": "1" if res.checksum_ok else "0",
                "issues": "; ".join(res.issues),
            }
        )
    return rows


def export_report_txt(path: str, text: str) -> str:
    ensure_parent(path)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
        if not text.endswith("\n"):
            fh.write("\n")
    return os.path.abspath(path)
