#!/usr/bin/env python3
"""按文本定位并点击 Android 界面控件（真机 UI 自动化辅助）。

用法：
    python tools/ui.py tap "打开内置模拟页面"
    python tools/ui.py tap "启动监测" --scroll 3      # 先向下滚动最多 3 次再找
    python tools/ui.py find "日志"                    # 只打印坐标
    python tools/ui.py dump                           # 打印所有可点击节点

依赖：adb 在 PATH 或通过 ADB 环境变量指定。
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = os.environ.get("ADB", r"D:\xiazai\platform-tools\adb.exe")
TMP = Path(__file__).resolve().parent.parent / "build" / "ui.xml"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*args: str) -> str:
    result = subprocess.run(
        [ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    return result.stdout


def dump() -> ET.Element:
    TMP.parent.mkdir(parents=True, exist_ok=True)
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    adb("pull", "/sdcard/ui.xml", str(TMP))
    return ET.parse(TMP).getroot()


def nodes(root: ET.Element):
    for node in root.iter("node"):
        label = (node.get("text") or "").strip() or (node.get("content-desc") or "").strip()
        match = BOUNDS.match(node.get("bounds") or "")
        if not label or not match:
            continue
        x1, y1, x2, y2 = map(int, match.groups())
        yield label, ((x1 + x2) // 2, (y1 + y2) // 2), node


def find(label: str, scroll: int = 0):
    for attempt in range(scroll + 1):
        root = dump()
        for text, center, node in nodes(root):
            if label in text:
                return text, center, node
        if attempt < scroll:
            adb("shell", "input", "swipe", "630", "2000", "630", "900", "300")
            time.sleep(0.8)
    return None


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    action = sys.argv[1]

    if action == "dump":
        for text, center, _ in nodes(dump()):
            print(f"{text[:30]:32s} {center}")
        return 0

    if len(sys.argv) < 3:
        print("缺少文本参数")
        return 2
    label = sys.argv[2]
    scroll = 0
    if "--scroll" in sys.argv:
        scroll = int(sys.argv[sys.argv.index("--scroll") + 1])

    hit = find(label, scroll)
    if not hit:
        print(f"未找到：{label}")
        return 1
    text, (x, y), _ = hit
    print(f"命中 {text!r} -> ({x},{y})")
    if action == "tap":
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.2)
        print("已点击")
    return 0


if __name__ == "__main__":
    sys.exit(main())
