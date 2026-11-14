"""规则持久化：保存 / 加载 / 列举 JSON 规则文件。"""

from __future__ import annotations

import json
import os
import re
import time
from typing import Dict, List, Optional

from .exporter import ensure_parent
from .models import FormatRule, SCHEMA_VERSION

DEFAULT_RULE_DIR = "rules"


class RuleFileError(RuntimeError):
    """规则文件读取或校验失败。"""


def _safe_name(name: str) -> str:
    cleaned = re.sub(r"[^\w\-.]+", "_", name.strip(), flags=re.UNICODE)
    return cleaned or "rule"


def resolve_path(name_or_path: str, rule_dir: str = DEFAULT_RULE_DIR) -> str:
    """既支持传入完整路径，也支持传入规则名。"""
    if name_or_path.lower().endswith(".json") or os.sep in name_or_path or "/" in name_or_path:
        return name_or_path
    return os.path.join(rule_dir, _safe_name(name_or_path) + ".json")


def save_rule(rule: FormatRule, path: Optional[str] = None, rule_dir: str = DEFAULT_RULE_DIR) -> str:
    target = path or os.path.join(rule_dir, _safe_name(rule.name) + ".json")
    ensure_parent(target)
    payload = rule.to_dict()
    payload["saved_at"] = time.strftime("%Y-%m-%d %H:%M:%S")
    with open(target, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
    return os.path.abspath(target)


def load_rule(path: str) -> FormatRule:
    if not os.path.isfile(path):
        raise RuleFileError("规则文件不存在：%s" % path)
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            data = json.load(fh)
    except json.JSONDecodeError as exc:
        raise RuleFileError("规则文件不是合法 JSON：%s（第 %d 行）" % (path, exc.lineno)) from exc
    except OSError as exc:
        raise RuleFileError("无法读取规则文件：%s（%s）" % (path, exc)) from exc

    if not isinstance(data, dict) or "fields" not in data:
        raise RuleFileError("规则文件缺少 fields 字段，可能不是本工具生成的配置：%s" % path)

    version = int(data.get("schema_version", 0))
    if version > SCHEMA_VERSION:
        raise RuleFileError(
            "规则文件版本 %d 高于程序支持的 %d，请升级程序或重新分析样例。" % (version, SCHEMA_VERSION)
        )

    try:
        rule = FormatRule.from_dict(data)
    except (TypeError, ValueError, KeyError) as exc:
        raise RuleFileError("规则文件结构异常：%s（%s）" % (path, exc)) from exc

    if not rule.fields:
        raise RuleFileError("规则文件中没有任何字段：%s" % path)
    try:
        re.compile(rule.to_regex())
    except re.error as exc:
        raise RuleFileError("规则生成的正则无法编译：%s（%s）" % (rule.to_regex(), exc)) from exc
    return rule


def list_rules(rule_dir: str = DEFAULT_RULE_DIR) -> List[Dict[str, object]]:
    if not os.path.isdir(rule_dir):
        return []
    items: List[Dict[str, object]] = []
    for entry in sorted(os.listdir(rule_dir)):
        if not entry.lower().endswith(".json"):
            continue
        path = os.path.join(rule_dir, entry)
        try:
            with open(path, "r", encoding="utf-8-sig") as fh:
                data = json.load(fh)
            items.append(
                {
                    "file": path,
                    "name": data.get("name", entry),
                    "mode": data.get("mode", "?"),
                    "samples": len(data.get("samples", [])),
                    "regex": data.get("regex", ""),
                    "created_at": data.get("created_at", ""),
                    "saved_at": data.get("saved_at", ""),
                }
            )
        except (OSError, json.JSONDecodeError):
            items.append({"file": path, "name": entry, "mode": "invalid", "samples": 0,
                          "regex": "", "created_at": "", "saved_at": ""})
    return items
