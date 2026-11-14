"""命令行界面：交互式菜单 + 非交互子命令。"""

from __future__ import annotations

import argparse
import os
import sys
from typing import List, Optional, Sequence

from . import __version__, analyzer, exporter, generator, reporter, storage, validator
from .inputs import InputError, load_codes_from_file, normalize_codes, split_input_text
from .models import FormatRule

BANNER = r"""
  ____                          _____ __  __ _____
 / ___|___  _   _ _ __   ___ _ __|  ___|  \/  |_   _|
| |   / _ \| | | | '_ \ / _ \ '_ \ |_  | |\/| | | |
| |__| (_) | |_| | |_) |  __/ | | |  _| | |  | | | |
 \____\___/ \__,_| .__/ \___|_| |_|_|   |_|  |_| |_|
                 |_|
 通用兑换码格式分析工具  v%s   （本地离线 · 不连接任何真实平台）
""" % __version__

DISCLAIMER = (
    "提示：格式匹配仅表示字符串符合归纳出的规则，不代表兑换码真实有效；"
    "所有生成与校验均在本地完成。"
)


# --------------------------------------------------------------------------- #
# 控制台与输入辅助
# --------------------------------------------------------------------------- #


def setup_console() -> None:
    if os.name == "nt":
        try:
            import ctypes

            ctypes.windll.kernel32.SetConsoleOutputCP(65001)
        except Exception:
            pass
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except Exception:
            pass


def _w(text: str) -> int:
    """按东亚字符宽度计算显示宽度，避免中文表格错位。"""
    import unicodedata

    return sum(2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1 for ch in text)


def _pad(text: str, width: int) -> str:
    return text + " " * max(0, width - _w(text))


class _EOFReached(Exception):
    """标准输入被关闭（管道结束或 Ctrl+Z），用于优雅退出。"""


def _ask(prompt: str, default: str = "") -> str:
    hint = " [%s]" % default if default else ""
    try:
        raw = input("%s%s: " % (prompt, hint))
    except EOFError:
        print()
        raise _EOFReached()
    raw = raw.strip()
    return raw or default


def _ask_int(prompt: str, default: int, minimum: int = 1, maximum: int = 1000000) -> int:
    while True:
        raw = _ask(prompt, str(default))
        try:
            value = int(raw)
        except ValueError:
            print("  请输入整数。")
            continue
        if not (minimum <= value <= maximum):
            print("  请输入 %d ~ %d 之间的整数。" % (minimum, maximum))
            continue
        return value


def _ask_yes(prompt: str, default: bool = False) -> bool:
    raw = _ask(prompt + " (y/n)", "y" if default else "n").lower()
    return raw in ("y", "yes", "是", "1", "true")


def read_codes_interactive(title: str = "请输入兑换码样例") -> List[str]:
    """多行粘贴输入，空行结束；也支持一行内用逗号分隔。"""
    print("\n%s（每行一个，空行结束；可直接粘贴多行）：" % title)
    lines: List[str] = []
    while True:
        try:
            line = input("> ")
        except EOFError:
            break
        if not line.strip():
            break
        lines.extend(split_input_text(line))
    return lines


def _rule_or_none(state: "AppState") -> Optional[FormatRule]:
    if state.rule is None:
        print("  当前还没有规则，请先执行「1) 输入样例并分析」。")
        return None
    return state.rule


# --------------------------------------------------------------------------- #
# 应用状态
# --------------------------------------------------------------------------- #


class AppState:
    def __init__(self, out_dir: str = "out", rule_dir: str = storage.DEFAULT_RULE_DIR) -> None:
        self.rule: Optional[FormatRule] = None
        self.last_codes: List[str] = []
        self.out_dir = out_dir
        self.rule_dir = rule_dir

    def out_path(self, filename: str) -> str:
        return os.path.join(self.out_dir, filename)


# --------------------------------------------------------------------------- #
# 各功能流程
# --------------------------------------------------------------------------- #


def flow_analyze(state: AppState) -> None:
    print("\n--- 分析样例并建立规则 ---")
    print("支持三种输入方式：直接粘贴 / 从文件读取 / 使用内置示例")
    mode = _ask("输入方式 1=粘贴 2=文件 3=示例", "1")

    samples: List[str] = []
    if mode == "2":
        path = _ask("样例文件路径", "samples/sample_codes.txt")
        try:
            samples = load_codes_from_file(path)
        except OSError as exc:
            print("  读取失败：%s" % exc)
            return
    elif mode == "3":
        samples = [
            "SAVE-2026-8F3K",
            "SAVE-2026-QW7M",
            "SAVE-2026-4XZ9",
            "SAVE-2026-7H2P",
        ]
        print("  已载入示例：%s" % ", ".join(samples))
    else:
        samples = read_codes_interactive()

    if not samples:
        print("  未输入任何样例。")
        return

    name = _ask("规则名称", state.rule.name if state.rule else "default")
    try:
        rule = analyzer.analyze(samples, name=name)
    except InputError as exc:
        print("  输入有误：%s" % exc)
        return

    state.rule = rule
    print("\n分析完成，共 %d 个有效样例。\n" % len(rule.samples))
    print("\n".join(rule.summary_lines()))
    print("\n" + DISCLAIMER)

    if _ask_yes("是否保存该规则配置", True):
        try:
            path = storage.save_rule(rule, rule_dir=state.rule_dir)
            print("  已保存：%s" % path)
        except OSError as exc:
            print("  保存失败：%s" % exc)


def flow_validate(state: AppState) -> None:
    rule = _rule_or_none(state)
    if rule is None:
        return
    print("\n--- 校验兑换码格式 ---")
    mode = _ask("输入方式 1=粘贴 2=文件", "1")
    codes: List[str] = []
    if mode == "2":
        path = _ask("文件路径", state.out_path("codes.txt"))
        try:
            codes = load_codes_from_file(path)
        except OSError as exc:
            print("  读取失败：%s" % exc)
            return
    else:
        codes = read_codes_interactive("请输入待校验的字符串")
    if not codes:
        print("  没有输入内容。")
        return

    strict = _ask_yes("使用严格模式（只接受观测到的字符）", False)
    results = validator.validate_batch(codes, rule, strict=strict)
    print("\n" + _pad("字符串", 26) + _pad("结果", 10) + "说明")
    print("-" * 68)
    for res in results:
        print(_pad(res.code, 26) + _pad("通过" if res.ok else "不通过", 10)
              + ("; ".join(res.issues) or "符合规则"))
    print("-" * 68)
    print("通过率：%.2f%%（%d/%d）" % (validator.pass_rate(results) * 100,
                                      sum(1 for r in results if r.ok), len(results)))
    print(DISCLAIMER)

    if _ask_yes("导出校验结果 CSV", False):
        path = _ask("CSV 路径", state.out_path("validate_result.csv"))
        try:
            exporter.export_csv(path, [r.to_row() for r in results])
            print("  已导出：%s" % os.path.abspath(path))
        except (OSError, ValueError) as exc:
            print("  导出失败：%s" % exc)


def flow_generate(state: AppState) -> None:
    rule = _rule_or_none(state)
    if rule is None:
        return
    print("\n--- 按规则生成离线测试码 ---")
    count = _ask_int("生成数量", 10, 1, 200000)
    seed_raw = _ask("随机种子（留空表示随机）", "")
    seed = int(seed_raw) if seed_raw.isdigit() else None
    avoid = _ask_yes("排除易混淆字符 0/O/1/I/l", False)
    try:
        codes = generator.generate(rule, count=count, seed=seed, avoid_ambiguous=avoid)
    except generator.GenerateError as exc:
        print("  生成失败：%s" % exc)
        return

    state.last_codes = codes
    print("\n已生成 %d 个测试码（前 20 个）：" % len(codes))
    for code in codes[:20]:
        print("  " + code)
    if len(codes) > 20:
        print("  ...（其余 %d 个见导出文件）" % (len(codes) - 20))

    results = validator.validate_batch(codes, rule)
    print("\n自校验通过率：%.2f%%" % (validator.pass_rate(results) * 100))

    if _ask_yes("导出为 TXT", True):
        path = _ask("TXT 路径", state.out_path("codes.txt"))
        try:
            exporter.export_txt(path, codes, rule=rule)
            print("  已导出：%s" % os.path.abspath(path))
        except OSError as exc:
            print("  导出失败：%s" % exc)
    if _ask_yes("导出为 CSV（含匹配结果）", False):
        path = _ask("CSV 路径", state.out_path("codes.csv"))
        try:
            exporter.export_csv(path, exporter.codes_to_rows(codes, rule))
            print("  已导出：%s" % os.path.abspath(path))
        except (OSError, ValueError) as exc:
            print("  导出失败：%s" % exc)


def flow_report(state: AppState) -> None:
    rule = _rule_or_none(state)
    if rule is None:
        return
    print("\n--- 生成统计报告 ---")
    source = _ask("数据来源 1=刚生成的测试码 2=从文件读取 3=现场生成新数据", "3")
    codes: List[str] = []
    if source == "1" and state.last_codes:
        codes = list(state.last_codes)
    elif source == "2":
        path = _ask("文件路径", state.out_path("codes.txt"))
        try:
            codes = load_codes_from_file(path)
        except OSError as exc:
            print("  读取失败：%s" % exc)
            return
    else:
        count = _ask_int("生成数量", 200, 1, 200000)
        try:
            codes = generator.generate(rule, count=count)
            state.last_codes = codes
        except generator.GenerateError as exc:
            print("  生成失败：%s" % exc)
            return
    if not codes:
        print("  没有可用数据。")
        return

    stats = reporter.analyze_codes(codes, rule)
    text = reporter.format_report(stats, rule)
    print("\n" + text)

    if _ask_yes("导出报告为 TXT", True):
        path = _ask("TXT 路径", state.out_path("report.txt"))
        try:
            exporter.export_report_txt(path, text)
            print("  已导出：%s" % os.path.abspath(path))
        except OSError as exc:
            print("  导出失败：%s" % exc)
    if _ask_yes("导出统计明细 CSV", False):
        path = _ask("CSV 路径", state.out_path("report_stats.csv"))
        rows = [
            {"metric": "length_%d" % length, "value": count}
            for length, count in stats["length_distribution"].items()
        ]
        rows += [
            {"metric": "char_%s" % ch, "value": count}
            for ch, count in stats["char_frequency"][:100]
        ]
        rows.append({"metric": "match_rate", "value": stats.get("match_rate", 0)})
        try:
            exporter.export_csv(path, rows, fieldnames=["metric", "value"])
            print("  已导出：%s" % os.path.abspath(path))
        except (OSError, ValueError) as exc:
            print("  导出失败：%s" % exc)


def flow_rules(state: AppState) -> None:
    print("\n--- 规则配置管理 ---")
    print("  1) 查看当前规则")
    print("  2) 保存当前规则")
    print("  3) 加载已有规则")
    print("  4) 列出全部规则")
    choice = _ask("请选择", "1")

    if choice == "1":
        rule = _rule_or_none(state)
        if rule is None:
            return
        print()
        print("\n".join(rule.summary_lines()))
    elif choice == "2":
        rule = _rule_or_none(state)
        if rule is None:
            return
        name = _ask("保存名称", rule.name)
        rule.name = name
        try:
            path = storage.save_rule(rule, rule_dir=state.rule_dir)
            print("  已保存：%s" % path)
        except OSError as exc:
            print("  保存失败：%s" % exc)
    elif choice == "3":
        items = storage.list_rules(state.rule_dir)
        if not items:
            print("  %s 目录下没有规则文件。" % os.path.abspath(state.rule_dir))
            return
        for i, item in enumerate(items, 1):
            print("  %2d) %-16s %-11s %s" % (i, item["name"], item["mode"], item["regex"]))
        raw = _ask("选择序号或直接输入文件路径", "1")
        target = items[int(raw) - 1]["file"] if raw.isdigit() and 1 <= int(raw) <= len(items) else raw
        try:
            state.rule = storage.load_rule(target)
            print("  已加载规则：%s" % state.rule.name)
            print("\n".join(state.rule.summary_lines()))
        except storage.RuleFileError as exc:
            print("  加载失败：%s" % exc)
    elif choice == "4":
        items = storage.list_rules(state.rule_dir)
        if not items:
            print("  暂无规则文件。")
            return
        print("  " + _pad("名称", 22) + _pad("模式", 13) + _pad("样本", 8) + "正则")
        print("  " + "-" * 66)
        for item in items:
            print("  " + _pad(str(item["name"]), 22) + _pad(str(item["mode"]), 13)
                  + _pad(str(item["samples"]), 8) + str(item["regex"]))
    else:
        print("  无效选项。")


def flow_mock_server(state: AppState) -> None:
    print("\n--- 启动本地模拟兑换页面 ---")
    try:
        from automation import mock_server
    except ImportError as exc:
        print("  无法加载 automation 模块：%s" % exc)
        return
    port = _ask_int("监听端口", 8765, 1024, 65535)
    lan = _ask_yes("允许同一 Wi-Fi 下的手机访问（绑定 0.0.0.0）", False)
    host = "0.0.0.0" if lan else "127.0.0.1"
    issued = _ask("有效码清单文件（留空表示全部返回格式正确）", state.out_path("codes.txt"))
    issued_path = issued if issued and os.path.isfile(issued) else None
    if issued and not issued_path:
        print("  文件不存在，将使用空清单（所有格式正确的码都会返回「不存在」）。")
    try:
        url = mock_server.start_background(port=port, issued_path=issued_path, host=host)
    except OSError as exc:
        print("  启动失败：%s" % exc)
        return
    urls = mock_server.access_urls(host, port)
    print("  已启动：")
    for item in urls:
        print("    %s" % item)
    if len(urls) > 1:
        print("  手机与电脑连同一 Wi-Fi，在手机浏览器打开上面的第二个地址。")
        print("  若打不开：Windows 防火墙需放行 TCP %d（见 README「手机运行方案」）。" % port)
    else:
        print("  当前仅本机可访问；如需手机访问，请在上面选择 y 允许局域网访问。")
    print("  返回菜单后服务仍在后台运行，退出程序即停止。")


def flow_autotest(state: AppState) -> None:
    print("\n--- 自动化测试（仅限本地模拟页面 / 已授权测试环境）---")
    try:
        from automation import runner
    except ImportError as exc:
        print("  无法加载 automation 模块：%s" % exc)
        return
    url = _ask("测试页面地址", "http://127.0.0.1:8765/")
    codes_path = _ask("测试码文件", state.out_path("codes.txt"))
    if not os.path.isfile(codes_path):
        print("  测试码文件不存在：%s（请先生成测试码）" % codes_path)
        return
    max_attempts = _ask_int("最大测试次数", 20, 1, 100000)
    delay = _ask("每次间隔秒数", "1.0")
    try:
        delay_value = max(0.0, float(delay))
    except ValueError:
        delay_value = 1.0
    dry = _ask_yes("使用 dry-run 模式（不启动浏览器，仅模拟流程）", False)
    channel = _ask("浏览器通道（留空=Playwright 自带 Chromium，可填 msedge/chrome）", "")
    device = _ask("设备模拟（留空=桌面；示例：iPhone 13 / Pixel 5）", "")
    cdp = _ask("CDP 调试地址（真机 Android 用，留空=不连接）", "")
    csv_path = _ask("CSV 报告路径", state.out_path("autotest_report.csv"))

    cfg = runner.RunConfig(
        url=url,
        codes_path=codes_path,
        max_attempts=max_attempts,
        delay=delay_value,
        csv_path=csv_path,
        dry_run=dry,
        browser_channel=channel,
        device=device,
        cdp_url=cdp,
    )
    try:
        summary = runner.run(cfg)
    except runner.RunnerError as exc:
        print("  运行失败：%s" % exc)
        return
    print("\n" + runner.format_summary(summary))


def interactive_loop(state: AppState) -> None:
    print(BANNER)
    print(DISCLAIMER)
    while True:
        rule_desc = "无"
        if state.rule is not None:
            rule_desc = "%s | %s | 样本 %d" % (state.rule.name, state.rule.mode, len(state.rule.samples))
        print("\n" + "=" * 68)
        print(" 当前规则: %s" % rule_desc)
        print(" 输出目录: %s" % os.path.abspath(state.out_dir))
        print("-" * 68)
        print("  1) 输入样例并分析格式（更换样例可重新分析）")
        print("  2) 查看 / 保存 / 加载 规则配置")
        print("  3) 校验兑换码（单个或批量）")
        print("  4) 按规则生成离线测试码")
        print("  5) 生成统计报告（长度分布 / 字符频率 / 匹配率）")
        print("  6) 启动本地模拟兑换页面")
        print("  7) 自动化测试（Playwright，本地环境）")
        print("  0) 退出")
        try:
            choice = _ask("请选择", "0")
            if choice == "1":
                flow_analyze(state)
            elif choice == "2":
                flow_rules(state)
            elif choice == "3":
                flow_validate(state)
            elif choice == "4":
                flow_generate(state)
            elif choice == "5":
                flow_report(state)
            elif choice == "6":
                flow_mock_server(state)
            elif choice == "7":
                flow_autotest(state)
            elif choice == "0":
                print("再见。")
                return
            else:
                print("  无效选项，请输入 0-7。")
        except _EOFReached:
            print("输入已结束，退出。")
            return
        except KeyboardInterrupt:
            print("\n  已取消当前操作。")


# --------------------------------------------------------------------------- #
# 非交互子命令
# --------------------------------------------------------------------------- #


def _load_rule_arg(path: Optional[str]) -> FormatRule:
    if not path:
        raise SystemExit("请通过 --rule 指定规则文件（可用 rules 子命令查看已有规则）。")
    try:
        return storage.load_rule(path)
    except storage.RuleFileError as exc:
        raise SystemExit("加载规则失败：%s" % exc)


def _collect_codes(args: argparse.Namespace) -> List[str]:
    codes: List[str] = []
    if getattr(args, "codes", None):
        codes.extend(args.codes)
    if getattr(args, "file", None):
        try:
            codes.extend(load_codes_from_file(args.file))
        except OSError as exc:
            raise SystemExit("读取文件失败：%s" % exc)
    if not codes:
        raise SystemExit("请通过 --codes 或 --file 提供输入。")
    try:
        return normalize_codes(codes)
    except InputError as exc:
        raise SystemExit(str(exc))


def cmd_analyze(args: argparse.Namespace) -> int:
    codes = _collect_codes(args)
    try:
        rule = analyzer.analyze(codes, name=args.name)
    except InputError as exc:
        print("输入有误：%s" % exc, file=sys.stderr)
        return 2
    print("\n".join(rule.summary_lines()))
    print("\n" + DISCLAIMER)
    if args.save:
        path = storage.save_rule(rule, path=args.save)
        print("\n规则已保存：%s" % path)
    elif args.save_name:
        path = storage.save_rule(rule, rule_dir=args.rule_dir)
        print("\n规则已保存：%s" % path)
    if args.json:
        print(rule.dumps())
    return 0


def cmd_gen(args: argparse.Namespace) -> int:
    rule = _load_rule_arg(args.rule)
    try:
        codes = generator.generate(
            rule,
            count=args.count,
            seed=args.seed,
            unique=not args.allow_duplicates,
            avoid_ambiguous=args.avoid_ambiguous,
            apply_checksum=not args.no_checksum,
        )
    except generator.GenerateError as exc:
        print("生成失败：%s" % exc, file=sys.stderr)
        return 2
    if args.out:
        exporter.export_txt(args.out, codes, rule=rule)
        print("已生成 %d 个测试码 -> %s" % (len(codes), os.path.abspath(args.out)))
    if args.csv:
        exporter.export_csv(args.csv, exporter.codes_to_rows(codes, rule))
        print("CSV -> %s" % os.path.abspath(args.csv))
    if not args.out and not args.csv:
        for code in codes:
            print(code)
    return 0


def cmd_validate(args: argparse.Namespace) -> int:
    rule = _load_rule_arg(args.rule)
    codes = _collect_codes(args)
    results = validator.validate_batch(codes, rule, strict=args.strict)
    for res in results:
        print(_pad(res.code, 28) + _pad("PASS" if res.ok else "FAIL", 6) + "; ".join(res.issues))
    print("-" * 60)
    print("通过率 %.2f%%（%d/%d）" % (validator.pass_rate(results) * 100,
                                     sum(1 for r in results if r.ok), len(results)))
    if args.csv:
        exporter.export_csv(args.csv, [r.to_row() for r in results])
        print("CSV -> %s" % os.path.abspath(args.csv))
    return 0 if all(r.ok for r in results) else 1


def cmd_report(args: argparse.Namespace) -> int:
    rule = _load_rule_arg(args.rule) if args.rule else None
    if args.file:
        try:
            codes = load_codes_from_file(args.file)
        except OSError as exc:
            raise SystemExit("读取文件失败：%s" % exc)
    elif args.codes:
        codes = list(args.codes)
    elif rule is not None and args.count:
        try:
            codes = generator.generate(rule, count=args.count)
        except generator.GenerateError as exc:
            raise SystemExit("生成失败：%s" % exc)
    else:
        raise SystemExit("请提供 --file / --codes，或在有 --rule 时使用 --count 现场生成。")

    stats = reporter.analyze_codes(codes, rule)
    text = reporter.format_report(stats, rule)
    print(text)
    if args.txt:
        exporter.export_report_txt(args.txt, text)
        print("报告已导出：%s" % os.path.abspath(args.txt))
    if args.csv:
        rows = [{"metric": "length_%d" % k, "value": v} for k, v in stats["length_distribution"].items()]
        rows += [{"metric": "char_%s" % k, "value": v} for k, v in stats["char_frequency"][:100]]
        rows.append({"metric": "match_rate", "value": stats.get("match_rate", 0)})
        exporter.export_csv(args.csv, rows, fieldnames=["metric", "value"])
        print("统计明细已导出：%s" % os.path.abspath(args.csv))
    return 0


def cmd_rules(args: argparse.Namespace) -> int:
    if args.action == "list":
        items = storage.list_rules(args.rule_dir)
        if not items:
            print("暂无规则文件（目录：%s）" % os.path.abspath(args.rule_dir))
            return 0
        print(_pad("名称", 22) + _pad("模式", 13) + _pad("样本", 8) + "正则")
        print("-" * 78)
        for item in items:
            print(_pad(str(item["name"]), 22) + _pad(str(item["mode"]), 13)
                  + _pad(str(item["samples"]), 8) + str(item["regex"]))
        return 0
    rule = _load_rule_arg(args.rule)
    print("\n".join(rule.summary_lines()))
    return 0


def cmd_mock(args: argparse.Namespace) -> int:
    from automation import mock_server

    try:
        mock_server.serve(port=args.port, issued_path=args.issued, quiet=args.quiet,
                          host=getattr(args, "host", "127.0.0.1"))
    except OSError as exc:
        print("启动失败：%s" % exc, file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("\n模拟服务已停止。")
    return 0


def cmd_autotest(args: argparse.Namespace) -> int:
    from automation import runner

    cfg = runner.RunConfig(
        url=args.url,
        codes_path=args.codes,
        max_attempts=args.max,
        delay=args.delay,
        csv_path=args.csv,
        dry_run=args.dry_run,
        stop_on_success=args.stop_on_success,
        allow_remote=args.allow_remote,
        headless=not args.headed,
        selector_input=args.selector_input,
        selector_submit=args.selector_submit,
        selector_result=args.selector_result,
        browser_channel=args.channel,
        device=args.device,
        cdp_url=args.cdp,
    )
    try:
        summary = runner.run(cfg)
    except runner.RunnerError as exc:
        print("运行失败：%s" % exc, file=sys.stderr)
        return 2
    print(runner.format_summary(summary))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="couponfmt",
        description="通用兑换码格式分析与本地离线测试数据生成工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="不带子命令运行时进入交互式菜单。",
    )
    parser.add_argument("--version", action="version", version="couponfmt %s" % __version__)
    sub = parser.add_subparsers(dest="command")

    p = sub.add_parser("analyze", help="分析样例并归纳规则")
    p.add_argument("--codes", nargs="+", help="样例（可多个）")
    p.add_argument("--file", help="样例文件，一行一个")
    p.add_argument("--name", default="default", help="规则名称")
    p.add_argument("--save", help="保存到指定 JSON 路径")
    p.add_argument("--save-name", action="store_true", help="保存到规则目录（按名称）")
    p.add_argument("--rule-dir", default=storage.DEFAULT_RULE_DIR, help="规则目录")
    p.add_argument("--json", action="store_true", help="同时输出规则 JSON")
    p.set_defaults(func=cmd_analyze)

    p = sub.add_parser("gen", help="按规则生成测试码")
    p.add_argument("--rule", required=True, help="规则文件路径")
    p.add_argument("--count", type=int, default=10, help="生成数量")
    p.add_argument("--seed", type=int, help="随机种子")
    p.add_argument("--out", help="导出 TXT 路径")
    p.add_argument("--csv", help="导出 CSV 路径")
    p.add_argument("--allow-duplicates", action="store_true", help="允许重复")
    p.add_argument("--avoid-ambiguous", action="store_true", help="排除 0/O/1/I/l")
    p.add_argument("--no-checksum", action="store_true", help="不自动补校验位")
    p.set_defaults(func=cmd_gen)

    p = sub.add_parser("validate", help="校验字符串是否符合规则")
    p.add_argument("--rule", required=True, help="规则文件路径")
    p.add_argument("--codes", nargs="+", help="待校验字符串")
    p.add_argument("--file", help="待校验文件")
    p.add_argument("--strict", action="store_true", help="严格模式")
    p.add_argument("--csv", help="导出 CSV 路径")
    p.set_defaults(func=cmd_validate)

    p = sub.add_parser("report", help="生成统计报告")
    p.add_argument("--rule", help="规则文件路径（可选，用于计算匹配率）")
    p.add_argument("--file", help="数据文件")
    p.add_argument("--codes", nargs="+", help="直接给出字符串")
    p.add_argument("--count", type=int, help="按规则现场生成的数据量")
    p.add_argument("--txt", help="报告导出路径")
    p.add_argument("--csv", help="统计明细 CSV 路径")
    p.set_defaults(func=cmd_report)

    p = sub.add_parser("rules", help="查看规则")
    p.add_argument("action", choices=["list", "show"], nargs="?", default="list")
    p.add_argument("--rule", help="规则文件路径（show 时必填）")
    p.add_argument("--rule-dir", default=storage.DEFAULT_RULE_DIR)
    p.set_defaults(func=cmd_rules)

    p = sub.add_parser("mock", help="启动本地模拟兑换页面")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--issued", help="有效码清单文件（用于模拟「格式正确」分支）")
    p.add_argument("--host", default="127.0.0.1", help="绑定地址；填 0.0.0.0 允许手机通过局域网访问")
    p.add_argument("--quiet", action="store_true")
    p.set_defaults(func=cmd_mock)

    p = sub.add_parser("autotest", help="自动化测试（仅本地/授权环境）")
    p.add_argument("--url", default="http://127.0.0.1:8765/", help="测试页面地址")
    p.add_argument("--codes", default=os.path.join("out", "codes.txt"), help="测试码文件")
    p.add_argument("--max", type=int, default=20, help="最大测试次数")
    p.add_argument("--delay", type=float, default=1.0, help="每次间隔秒数")
    p.add_argument("--csv", default=os.path.join("out", "autotest_report.csv"), help="CSV 报告路径")
    p.add_argument("--dry-run", action="store_true", help="不启动浏览器，仅模拟流程")
    p.add_argument("--stop-on-success", action="store_true", help="命中「格式正确」后停止")
    p.add_argument("--allow-remote", action="store_true", help="允许非本地地址（需自行确保已获授权）")
    p.add_argument("--headed", action="store_true", help="显示浏览器窗口")
    p.add_argument("--selector-input", help="输入框选择器")
    p.add_argument("--selector-submit", help="提交按钮选择器")
    p.add_argument("--selector-result", help="结果区域选择器")
    p.add_argument("--channel", default="", help="复用本机浏览器：msedge / chrome（默认用自带 Chromium）")
    p.add_argument("--device", default="", help='设备模拟，如 "iPhone 13"、"Pixel 5"')
    p.add_argument("--cdp", default="", help="CDP 调试地址，如 http://127.0.0.1:9222（真机 Android）")
    p.set_defaults(func=cmd_autotest)

    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    setup_console()
    parser = build_parser()
    args = parser.parse_args(argv)
    if getattr(args, "command", None):
        return int(args.func(args) or 0)
    try:
        interactive_loop(AppState())
    except KeyboardInterrupt:
        print("\n已退出。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
