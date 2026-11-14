"""自动化测试运行器（Playwright 驱动，仅限本地模拟页面或已授权测试环境）。

能力：
- 读取本地测试码文件，逐条填入输入框、触发提交、读取返回结果；
- 返回「不存在」「格式错误」时自动切换下一个测试码，返回「格式正确」时记录；
- 支持运行中暂停 / 继续 / 停止（键盘 p / r / q，或 Ctrl+C）；
- 限制最大测试次数与连续异常次数，避免无限循环；
- 每条结果实时写入 CSV，结束后输出汇总报告。

安全边界：
- 默认只允许 localhost / 127.0.0.1 / ::1 / file:// 地址；
- 非本地地址必须显式传入 allow_remote=True，且应确保已获得环境所有者授权。
"""

from __future__ import annotations

import csv
import datetime
import ipaddress
import os
import sys
import threading
import time
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence
from urllib.parse import urlparse

from couponfmt.inputs import load_codes_from_file

LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1", "0.0.0.0", ""}

STATUS_LABEL = {
    "ok": "格式正确",
    "not_found": "不存在",
    "format_error": "格式错误",
    "timeout": "超时",
    "error": "异常",
    "unknown": "未知响应",
    "stopped": "已停止",
}

# 结果文本 -> 归一化状态（顺序敏感：先匹配"格式正确"，再"格式错误"，最后"不存在"）
TEXT_RULES: Sequence[tuple] = (
    ("ok", ("格式正确", "格式无误", "验证通过", "兑换成功", "success", "valid")),
    ("format_error", ("格式错误", "格式不正确", "格式非法", "invalid format", "malformed")),
    ("not_found", ("不存在", "未找到", "已使用", "已失效", "not found", "invalid")),
)


class RunnerError(RuntimeError):
    """运行前置条件不满足。"""


class StopRequested(Exception):
    """用户请求停止。"""


# --------------------------------------------------------------------------- #
# 配置与控制
# --------------------------------------------------------------------------- #


@dataclass
class RunConfig:
    url: str = "http://127.0.0.1:8765/"
    codes_path: str = os.path.join("out", "codes.txt")
    max_attempts: int = 20
    delay: float = 1.0
    csv_path: str = os.path.join("out", "autotest_report.csv")
    dry_run: bool = False
    stop_on_success: bool = False
    allow_remote: bool = False
    headless: bool = True
    selector_input: str = ""
    selector_submit: str = ""
    selector_result: str = ""
    timeout_ms: int = 10000
    max_consecutive_errors: int = 5
    keyboard_control: bool = True
    browser_channel: str = ""     # 留空=使用 Playwright 自带 Chromium；可填 msedge/chrome 复用本机浏览器
    device: str = ""              # 设备模拟，如 "iPhone 13"、"Pixel 5"（Playwright 内置设备名）
    cdp_url: str = ""             # 连接已开启远程调试的浏览器，如 http://127.0.0.1:9222（真机 Android 用）


@dataclass
class SubmitResult:
    status: str
    text: str = ""
    elapsed_ms: int = 0


class RunControl:
    """线程安全的暂停/继续/停止控制。"""

    def __init__(self) -> None:
        self._running = threading.Event()
        self._running.set()
        self._stop = threading.Event()

    @property
    def is_paused(self) -> bool:
        return not self._running.is_set()

    @property
    def is_stopped(self) -> bool:
        return self._stop.is_set()

    def pause(self) -> None:
        if not self._stop.is_set():
            self._running.clear()
            print("\n[控制] 已暂停，按 r 继续，按 q 停止。")

    def resume(self) -> None:
        if not self._stop.is_set() and not self._running.is_set():
            self._running.set()
            print("\n[控制] 已继续。")

    def stop(self) -> None:
        self._stop.set()
        self._running.set()
        print("\n[控制] 收到停止请求，正在收尾…")

    def wait(self) -> None:
        """暂停时阻塞；已停止则抛出 StopRequested。"""
        while self.is_paused:
            if self._stop.is_set():
                raise StopRequested()
            time.sleep(0.1)
        if self._stop.is_set():
            raise StopRequested()

    def sleep(self, seconds: float) -> None:
        end = time.time() + max(0.0, seconds)
        while time.time() < end:
            if self._stop.is_set():
                raise StopRequested()
            if self.is_paused:
                self.wait()
            time.sleep(min(0.1, max(0.0, end - time.time())))


def _handle_key(ch: str, control: RunControl) -> None:
    if ch in ("p", " "):
        control.pause()
    elif ch == "r":
        control.resume()
    elif ch in ("q", "s", "\x03"):
        control.stop()


def start_keyboard_listener(control: RunControl) -> Optional[threading.Thread]:
    """后台监听键盘，实现暂停/继续/停止；非交互终端下自动跳过。"""
    try:
        if not sys.stdin or not sys.stdin.isatty():
            return None
    except Exception:
        return None

    def loop() -> None:
        try:
            if os.name == "nt":
                import msvcrt

                while not control.is_stopped:
                    if msvcrt.kbhit():
                        _handle_key(msvcrt.getwch().lower(), control)
                    time.sleep(0.05)
            else:
                import select
                import termios
                import tty

                fd = sys.stdin.fileno()
                old = termios.tcgetattr(fd)
                try:
                    tty.setcbreak(fd)
                    while not control.is_stopped:
                        ready, _, _ = select.select([sys.stdin], [], [], 0.1)
                        if ready:
                            _handle_key(sys.stdin.read(1).lower(), control)
                finally:
                    termios.tcsetattr(fd, termios.TCSADRAIN, old)
        except Exception:
            return

    thread = threading.Thread(target=loop, name="keyboard-control", daemon=True)
    thread.start()
    return thread


# --------------------------------------------------------------------------- #
# 驱动
# --------------------------------------------------------------------------- #


def _classify(text: str) -> str:
    low = (text or "").strip().lower()
    for status, keywords in TEXT_RULES:
        for kw in keywords:
            if kw.lower() in low:
                return status
    return "unknown" if low else "unknown"


class BaseDriver:
    name = "base"

    def submit(self, code: str) -> SubmitResult:  # pragma: no cover - 抽象方法
        raise NotImplementedError

    def close(self) -> None:
        pass


class DryRunDriver(BaseDriver):
    """不启动浏览器的流程演练驱动：用于验证控制流、CSV 与统计逻辑。"""

    name = "dry-run"

    def __init__(self) -> None:
        self._seq = 0

    def submit(self, code: str) -> SubmitResult:
        self._seq += 1
        bucket = sum(ord(c) for c in code) % 100
        if bucket < 55:
            status = "ok"
        elif bucket < 80:
            status = "not_found"
        else:
            status = "format_error"
        time.sleep(0.01)
        return SubmitResult(status=status, text="[dry-run] %s" % STATUS_LABEL[status], elapsed_ms=10)


class PlaywrightDriver(BaseDriver):
    name = "playwright"

    INPUT_CANDIDATES = (
        "#code",
        "input[name='code']",
        "input[name='coupon']",
        "input[type='text']",
        "input:not([type])",
    )
    SUBMIT_CANDIDATES = (
        "#submit",
        "button[type='submit']",
        "button:has-text('兑换')",
        "button:has-text('提交')",
        "button",
        "input[type='submit']",
    )
    RESULT_CANDIDATES = ("#result", "[data-status]", ".result", "#message", ".message")

    def __init__(self, cfg: RunConfig) -> None:
        try:
            from playwright.sync_api import sync_playwright  # noqa: F401
        except ImportError as exc:  # pragma: no cover - 取决于本机环境
            raise RunnerError(
                "未安装 Playwright。请先执行：\n"
                "    pip install -r requirements.txt\n"
                "    python -m playwright install chromium\n"
                "或改用 --dry-run 演练流程。"
            ) from exc

        self.cfg = cfg
        self._pw = None
        self._browser = None
        self._page = None
        self._sel_input = ""
        self._sel_submit = ""
        self._sel_result = ""

    def open(self) -> None:
        from playwright.sync_api import sync_playwright

        self._pw = sync_playwright().start()

        if self.cfg.cdp_url:
            # 连接已开启远程调试的浏览器（真机 Android + adb forward 场景）
            self._browser = self._pw.chromium.connect_over_cdp(self.cfg.cdp_url)
            contexts = self._browser.contexts
            context = contexts[0] if contexts else self._browser.new_context()
            self._page = context.pages[0] if context.pages else context.new_page()
            self._page.set_default_timeout(self.cfg.timeout_ms)
            self._page.goto(self.cfg.url, wait_until="load")
        else:
            launch_kwargs = {"headless": self.cfg.headless}
            if self.cfg.browser_channel:
                launch_kwargs["channel"] = self.cfg.browser_channel
            self._browser = self._pw.chromium.launch(**launch_kwargs)

            if self.cfg.device:
                devices = self._pw.devices
                if self.cfg.device not in devices:
                    similar = [d for d in devices if self.cfg.device.lower() in d.lower()][:5]
                    raise RunnerError(
                        "未知的设备名：%r。可用的相近设备：%s"
                        % (self.cfg.device, ", ".join(similar) or "（无）")
                    )
                context = self._browser.new_context(**devices[self.cfg.device])
            else:
                context = self._browser.new_context()

            self._page = context.new_page()
            self._page.set_default_timeout(self.cfg.timeout_ms)
            self._page.goto(self.cfg.url, wait_until="load")
        self._page.set_default_timeout(self.cfg.timeout_ms)
        self._page.goto(self.cfg.url, wait_until="load")

        self._sel_input = self._pick(self.cfg.selector_input, self.INPUT_CANDIDATES, "输入框")
        self._sel_submit = self._pick(self.cfg.selector_submit, self.SUBMIT_CANDIDATES, "提交按钮")
        self._sel_result = self._pick(self.cfg.selector_result, self.RESULT_CANDIDATES, "结果区域")

    def _pick(self, explicit: str, candidates: Sequence[str], label: str) -> str:
        assert self._page is not None
        if explicit:
            if self._page.query_selector(explicit) is None:
                raise RunnerError("指定的%s选择器不存在：%s" % (label, explicit))
            return explicit
        for sel in candidates:
            try:
                if self._page.query_selector(sel) is not None:
                    return sel
            except Exception:
                continue
        raise RunnerError(
            "页面上找不到%s，请用 --selector-* 参数显式指定选择器。候选：%s"
            % (label, ", ".join(candidates))
        )

    def submit(self, code: str) -> SubmitResult:
        assert self._page is not None
        started = time.time()
        try:
            previous = ""
            try:
                previous = (self._page.inner_text(self._sel_result) or "").strip()
            except Exception:
                previous = ""

            self._page.fill(self._sel_input, code)
            self._page.click(self._sel_submit)

            # 等待结果区域文本发生变化
            try:
                self._page.wait_for_function(
                    "([sel, prev]) => { const el = document.querySelector(sel);"
                    " return el && (el.innerText || '').trim() !== prev; }",
                    arg=[self._sel_result, previous],
                    timeout=self.cfg.timeout_ms,
                )
            except Exception:
                pass

            text = ""
            try:
                text = (self._page.inner_text(self._sel_result) or "").strip()
            except Exception:
                text = ""
            elapsed = int((time.time() - started) * 1000)
            if not text:
                return SubmitResult("timeout", "结果区域为空或未及时更新", elapsed)
            return SubmitResult(_classify(text), text, elapsed)
        except Exception as exc:  # 页面异常不应中断整批任务
            elapsed = int((time.time() - started) * 1000)
            return SubmitResult("error", "%s: %s" % (type(exc).__name__, exc), elapsed)

    def close(self) -> None:
        for obj in (self._browser, self._pw):
            try:
                if obj is not None:
                    obj.close() if obj is self._browser else obj.stop()
            except Exception:
                continue


# --------------------------------------------------------------------------- #
# 运行主流程
# --------------------------------------------------------------------------- #


def _is_private_host(host: str) -> bool:
    """判断是否为本机/局域网私有地址（10/8、172.16/12、192.168/16、169.254/16）。"""
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    return bool(ip.is_private or ip.is_loopback or ip.is_link_local)


def _assert_allowed(url: str, allow_remote: bool) -> None:
    parsed = urlparse(url)
    if parsed.scheme == "file":
        return
    if parsed.scheme not in ("http", "https"):
        raise RunnerError("仅支持 http/https/file 协议，当前为：%s" % parsed.scheme)
    host = (parsed.hostname or "").lower()
    if host in LOCAL_HOSTS or _is_private_host(host):
        return
    if not allow_remote:
        raise RunnerError(
            "目标地址 %s 不是本地/局域网地址。本工具默认只允许 localhost、127.0.0.1、\n"
            "局域网私有地址（10.x / 172.16-31.x / 192.168.x）与 file://；\n"
            "如确需在自有授权测试环境运行，请显式加 --allow-remote。" % host
        )


def _ensure_parent(path: str) -> None:
    parent = os.path.dirname(os.path.abspath(path))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)


def run(cfg: RunConfig) -> Dict[str, object]:
    """执行自动化测试，返回汇总字典。"""
    _assert_allowed(cfg.url, cfg.allow_remote)
    if cfg.cdp_url:
        _assert_allowed(cfg.cdp_url, allow_remote=False)
        if cfg.device:
            print("提示：使用 --cdp 连接真实设备时，--device 模拟参数会被忽略。")

    if not os.path.isfile(cfg.codes_path):
        raise RunnerError("测试码文件不存在：%s" % cfg.codes_path)
    codes = [c.strip() for c in load_codes_from_file(cfg.codes_path) if c.strip()]
    if not codes:
        raise RunnerError("测试码文件为空：%s" % cfg.codes_path)
    if cfg.max_attempts <= 0:
        raise RunnerError("最大测试次数必须大于 0。")

    _ensure_parent(cfg.csv_path)
    control = RunControl()
    driver: BaseDriver = DryRunDriver() if cfg.dry_run else PlaywrightDriver(cfg)
    if isinstance(driver, PlaywrightDriver):
        try:
            driver.open()
        except RunnerError:
            driver.close()
            raise
        except Exception as exc:
            driver.close()
            message = str(exc)
            hint = ""
            if "Executable doesn't exist" in message or "playwright install" in message:
                hint = "\n提示：浏览器未安装，请执行 python -m playwright install chromium"
            raise RunnerError("启动浏览器失败：%s%s" % (message, hint)) from exc
    listener = start_keyboard_listener(control) if cfg.keyboard_control else None

    fields = ["seq", "timestamp", "code", "status", "status_label", "response_text", "elapsed_ms", "note"]
    records: List[Dict[str, object]] = []
    started_at = time.time()
    consecutive_errors = 0
    stop_reason = ""
    first_ok: Optional[str] = None

    print("\n=== 自动化测试开始 ===")
    print("目标地址 : %s" % cfg.url)
    print("驱动     : %s%s" % (driver.name, "（演练模式）" if cfg.dry_run else ""))
    if cfg.cdp_url:
        print("连接方式 : CDP %s（真实设备/已开远程调试的浏览器）" % cfg.cdp_url)
    elif cfg.device:
        print("设备模拟 : %s" % cfg.device)
    print("测试码   : %d 条（来自 %s）" % (len(codes), cfg.codes_path))
    print("最大次数 : %d，间隔 %.2fs" % (cfg.max_attempts, cfg.delay))
    if listener is not None:
        print("运行控制 : p 暂停 / r 继续 / q 停止（或 Ctrl+C）")
    print("-" * 68)

    fh = open(cfg.csv_path, "w", encoding="utf-8-sig", newline="")
    writer = csv.DictWriter(fh, fieldnames=fields)
    writer.writeheader()
    fh.flush()

    try:
        for code in codes:
            if len(records) >= cfg.max_attempts:
                stop_reason = "达到最大测试次数 %d" % cfg.max_attempts
                break
            control.wait()

            result = driver.submit(code)
            seq = len(records) + 1
            row = {
                "seq": seq,
                "timestamp": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
                "code": code,
                "status": result.status,
                "status_label": STATUS_LABEL.get(result.status, result.status),
                "response_text": (result.text or "").replace("\n", " ")[:200],
                "elapsed_ms": result.elapsed_ms,
                "note": "",
            }

            if result.status == "ok":
                row["note"] = "命中格式正确，已记录"
                first_ok = first_ok or code
                consecutive_errors = 0
            elif result.status in ("not_found", "format_error"):
                row["note"] = "自动切换下一个测试码"
                consecutive_errors = 0
            else:
                row["note"] = "异常/超时，已计数"
                consecutive_errors += 1

            records.append(row)
            writer.writerow(row)
            fh.flush()

            print(
                "[%3d] %-24s %-6s %5dms  %s"
                % (seq, code, row["status_label"], result.elapsed_ms,
                   (result.text or "")[:40])
            )

            if result.status == "ok" and cfg.stop_on_success:
                stop_reason = "命中「格式正确」并按配置停止"
                break
            if consecutive_errors >= cfg.max_consecutive_errors:
                stop_reason = "连续 %d 次异常/超时，自动终止" % consecutive_errors
                break
            if len(records) >= cfg.max_attempts:
                stop_reason = "达到最大测试次数 %d" % cfg.max_attempts
                break
            control.sleep(cfg.delay)
    except StopRequested:
        stop_reason = "用户手动停止"
    except KeyboardInterrupt:
        stop_reason = "用户手动停止（Ctrl+C）"
    finally:
        try:
            driver.close()
        except Exception:
            pass
        fh.close()

    elapsed = time.time() - started_at
    counter: Dict[str, int] = {}
    for row in records:
        counter[str(row["status"])] = counter.get(str(row["status"]), 0) + 1

    summary: Dict[str, object] = {
        "url": cfg.url,
        "driver": driver.name,
        "dry_run": cfg.dry_run,
        "device": cfg.device,
        "cdp_url": cfg.cdp_url,
        "tested": len(records),
        "planned": len(codes),
        "max_attempts": cfg.max_attempts,
        "counts": counter,
        "first_ok": first_ok,
        "stop_reason": stop_reason or "测试码已全部执行完毕",
        "elapsed_sec": round(elapsed, 2),
        "csv_path": os.path.abspath(cfg.csv_path),
    }
    return summary


def format_summary(summary: Dict[str, object]) -> str:
    counts: Dict[str, int] = summary.get("counts", {})  # type: ignore[assignment]
    tested = int(summary.get("tested", 0))  # type: ignore[arg-type]
    mode = ""
    if summary.get("cdp_url"):
        mode = "CDP %s" % summary.get("cdp_url")
    elif summary.get("device"):
        mode = "设备模拟 %s" % summary.get("device")
    lines = [
        "",
        "=" * 68,
        " 自动化测试汇总",
        "=" * 68,
        " 目标地址      : %s" % summary.get("url"),
        " 驱动          : %s%s" % (summary.get("driver"), "（演练模式）" if summary.get("dry_run") else ""),
        " 连接/设备     : %s" % (mode or "本机浏览器"),
        " 执行次数      : %d / 计划 %s" % (tested, summary.get("planned")),
        " 格式正确      : %d" % counts.get("ok", 0),
        " 不存在        : %d" % counts.get("not_found", 0),
        " 格式错误      : %d" % counts.get("format_error", 0),
        " 超时/异常     : %d" % (counts.get("timeout", 0) + counts.get("error", 0)),
        " 未知响应      : %d" % counts.get("unknown", 0),
        " 首个通过码    : %s" % (summary.get("first_ok") or "无"),
        " 停止原因      : %s" % summary.get("stop_reason"),
        " 总耗时        : %.2fs" % float(summary.get("elapsed_sec", 0)),  # type: ignore[arg-type]
        " CSV 报告      : %s" % summary.get("csv_path"),
        "=" * 68,
        " 说明：以上结果来自本地模拟/授权测试环境，不代表真实兑换码的有效性。",
    ]
    return "\n".join(lines)


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="自动化测试（本地模拟页面）")
    ap.add_argument("--url", default="http://127.0.0.1:8765/")
    ap.add_argument("--codes", default=os.path.join("out", "codes.txt"))
    ap.add_argument("--max", type=int, default=20)
    ap.add_argument("--delay", type=float, default=1.0)
    ap.add_argument("--csv", default=os.path.join("out", "autotest_report.csv"))
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--headed", action="store_true")
    ap.add_argument("--allow-remote", action="store_true")
    ap.add_argument("--channel", default="", help="msedge / chrome，复用本机浏览器")
    ap.add_argument("--device", default="", help='设备模拟，如 "iPhone 13"、"Pixel 5"')
    ap.add_argument("--cdp", default="", help="连接已开远程调试的浏览器，如 http://127.0.0.1:9222")
    ns = ap.parse_args()
    try:
        print(format_summary(run(RunConfig(
            url=ns.url, codes_path=ns.codes, max_attempts=ns.max, delay=ns.delay,
            csv_path=ns.csv, dry_run=ns.dry_run, headless=not ns.headed,
            allow_remote=ns.allow_remote, browser_channel=ns.channel,
            device=ns.device, cdp_url=ns.cdp,
        ))))
    except RunnerError as exc:
        print("运行失败：%s" % exc, file=sys.stderr)
        sys.exit(2)
