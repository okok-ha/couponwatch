"""数据模型：字段、格式规则，以及规则与正则/JSON 之间的转换。"""

from __future__ import annotations

import datetime
import json
import re
from dataclasses import dataclass, field as dc_field
from typing import Any, Dict, List, Optional

SCHEMA_VERSION = 1

KIND_LITERAL = "literal"
KIND_CHARSET = "charset"


def _now() -> str:
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


@dataclass
class Field:
    """规则中的一个片段：要么是固定字面量（含分隔符），要么是可变字符集字段。"""

    kind: str = KIND_CHARSET
    value: str = ""            # literal 的实际文本
    charset: str = ""          # 观测到的字符（排序去重）
    regex_class: str = r"."    # 正则字符类片段
    gen_charset: str = ""      # 生成用字符池（通常是命名字符集全集）
    min_len: int = 1
    max_len: int = 1
    label: str = ""            # 中文标签
    class_key: str = ""        # 命名字符集 key（digits/upper/...）
    strict_class: str = r"."   # 严格模式字符类（仅观测字符）
    observed_ratio: float = 1.0

    # ---------------- 序列化 ---------------- #
    def to_dict(self) -> Dict[str, Any]:
        return {
            "kind": self.kind,
            "value": self.value,
            "charset": self.charset,
            "regex_class": self.regex_class,
            "gen_charset": self.gen_charset,
            "min_len": self.min_len,
            "max_len": self.max_len,
            "label": self.label,
            "class_key": self.class_key,
            "strict_class": self.strict_class,
            "observed_ratio": round(self.observed_ratio, 4),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Field":
        return cls(
            kind=data.get("kind", KIND_CHARSET),
            value=data.get("value", ""),
            charset=data.get("charset", ""),
            regex_class=data.get("regex_class", r"."),
            gen_charset=data.get("gen_charset", ""),
            min_len=int(data.get("min_len", 1)),
            max_len=int(data.get("max_len", 1)),
            label=data.get("label", ""),
            class_key=data.get("class_key", ""),
            strict_class=data.get("strict_class", r"."),
            observed_ratio=float(data.get("observed_ratio", 1.0)),
        )

    # ---------------- 派生属性 ---------------- #
    @property
    def is_separator(self) -> bool:
        """整段都是非字母数字时才算分隔符。"""
        return (
            self.kind == KIND_LITERAL
            and bool(self.value)
            and all(not ch.isalnum() for ch in self.value)
        )

    @property
    def length(self) -> int:
        if self.kind == KIND_LITERAL:
            return len(self.value)
        return self.max_len

    def quantifier(self) -> str:
        if self.min_len == self.max_len:
            return "" if self.min_len == 1 else "{%d}" % self.min_len
        return "{%d,%d}" % (self.min_len, self.max_len)

    def to_regex(self, strict: bool = False) -> str:
        if self.kind == KIND_LITERAL:
            return re.escape(self.value)
        cls = self.strict_class if strict else self.regex_class
        return "%s%s" % (cls, self.quantifier())

    def describe(self) -> str:
        if self.kind == KIND_LITERAL:
            if self.is_separator:
                return "分隔符 %r ×%d" % (self.value, len(self.value))
            return "固定 %r" % self.value
        span = str(self.min_len) if self.min_len == self.max_len else "%d~%d" % (self.min_len, self.max_len)
        return "%s ×%s %s" % (self.label or self.class_key or "字段", span, self.regex_class)


@dataclass
class FormatRule:
    """由一组样例归纳出的格式规则。"""

    name: str = "default"
    samples: List[str] = dc_field(default_factory=list)
    fields: List[Field] = dc_field(default_factory=list)
    mode: str = "positional"          # positional | segmented | coarse | single
    checksum: Optional[Dict[str, Any]] = None
    length_min: int = 0
    length_max: int = 0
    confidence: float = 0.0
    notes: List[str] = dc_field(default_factory=list)
    created_at: str = dc_field(default_factory=_now)
    schema_version: int = SCHEMA_VERSION

    # ---------------- 派生属性 ---------------- #
    def to_regex(self, strict: bool = False, anchors: bool = True) -> str:
        body = "".join(f.to_regex(strict=strict) for f in self.fields)
        if anchors:
            return "^" + body + "$"
        return body

    @property
    def separators(self) -> List[str]:
        """只收集字面量中的非字母数字字符（即真正的分隔符）。"""
        seen: List[str] = []
        for f in self.fields:
            if f.kind != KIND_LITERAL:
                continue
            for ch in f.value:
                if not ch.isalnum() and ch not in seen:
                    seen.append(ch)
        return seen

    @property
    def prefix(self) -> str:
        parts = []
        for f in self.fields:
            if f.kind == KIND_LITERAL and not f.is_separator:
                parts.append(f.value)
            else:
                break
        return "".join(parts)

    @property
    def suffix(self) -> str:
        parts = []
        for f in reversed(self.fields):
            if f.kind == KIND_LITERAL and not f.is_separator:
                parts.append(f.value)
            else:
                break
        return "".join(reversed(parts))

    @property
    def charset(self) -> str:
        chars = set()
        for f in self.fields:
            if f.kind == KIND_CHARSET:
                chars |= set(f.charset)
        return "".join(sorted(chars))

    @property
    def var_fields(self) -> List[Field]:
        return [f for f in self.fields if f.kind == KIND_CHARSET]

    def compile(self, strict: bool = False) -> "re.Pattern[str]":
        return re.compile(self.to_regex(strict=strict), re.ASCII)

    # ---------------- 序列化 ---------------- #
    def to_dict(self) -> Dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "name": self.name,
            "created_at": self.created_at,
            "mode": self.mode,
            "confidence": round(self.confidence, 4),
            "length": {"min": self.length_min, "max": self.length_max},
            "prefix": self.prefix,
            "suffix": self.suffix,
            "separators": self.separators,
            "charset": self.charset,
            "checksum": self.checksum,
            "notes": list(self.notes),
            "regex": self.to_regex(),
            "regex_strict": self.to_regex(strict=True),
            "fields": [f.to_dict() for f in self.fields],
            "samples": list(self.samples),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "FormatRule":
        length = data.get("length") or {}
        return cls(
            name=data.get("name", "default"),
            samples=list(data.get("samples", [])),
            fields=[Field.from_dict(d) for d in data.get("fields", [])],
            mode=data.get("mode", "positional"),
            checksum=data.get("checksum"),
            length_min=int(length.get("min", 0)),
            length_max=int(length.get("max", 0)),
            confidence=float(data.get("confidence", 0.0)),
            notes=list(data.get("notes", [])),
            created_at=data.get("created_at", _now()),
            schema_version=int(data.get("schema_version", SCHEMA_VERSION)),
        )

    def dumps(self, indent: int = 2) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False, indent=indent)

    # ---------------- 人类可读摘要 ---------------- #
    def summary_lines(self) -> List[str]:
        lines = [
            "规则名称      : %s" % self.name,
            "分析模式      : %s" % _MODE_LABEL.get(self.mode, self.mode),
            "样本数量      : %d" % len(self.samples),
            "长度范围      : %s" % (self.length_min if self.length_min == self.length_max
                                   else "%d ~ %d" % (self.length_min, self.length_max)),
            "前缀          : %r" % self.prefix,
            "后缀          : %r" % self.suffix,
            "分隔符        : %s" % (", ".join(repr(s) for s in self.separators) or "无"),
            "可变字段字符集: %s" % (self.charset or "无"),
            "正则(通用)    : %s" % self.to_regex(),
            "正则(严格)    : %s" % self.to_regex(strict=True),
            "校验位        : %s" % (
                "未检出" if not self.checksum
                else "%s（末 %d 位，置信度 %.2f%%）" % (
                    self.checksum.get("label", self.checksum.get("algorithm")),
                    self.checksum.get("length", 1),
                    float(self.checksum.get("confidence", 0)) * 100,
                )
            ),
            "规则置信度    : %.1f%%" % (self.confidence * 100),
        ]
        if self.fields:
            lines.append("字段拆分      :")
            for i, f in enumerate(self.fields, 1):
                lines.append("  %2d. %s" % (i, f.describe()))
        if self.notes:
            lines.append("提示          :")
            for note in self.notes:
                lines.append("  - %s" % note)
        return lines


_MODE_LABEL = {
    "positional": "定长逐位分析",
    "segmented": "分段分析（按分隔符对齐）",
    "coarse": "粗粒度分析（长度不一致，仅推导整体字符集）",
    "single": "单样本分析（置信度低）",
}
