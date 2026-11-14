"""couponfmt 启动入口。

用法：
    python main.py                # 交互式菜单
    python main.py analyze --codes SAVE-2026-8F3K SAVE-2026-QW7M ...
    python main.py --help
"""

from __future__ import annotations

import sys

from couponfmt.cli import main

if __name__ == "__main__":
    sys.exit(main())
