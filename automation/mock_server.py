"""本地模拟兑换页面与 HTTP 服务。

默认只监听 127.0.0.1；传 host="0.0.0.0" 时同一 Wi-Fi 下的手机也能访问。
不连接任何真实兑换平台。
"""

from __future__ import annotations

import json
import os
import re
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional, Set

HOST = "127.0.0.1"
PAGE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mock_page", "index.html")

STATUS_OK = "ok"
STATUS_NOT_FOUND = "not_found"
STATUS_FORMAT_ERROR = "format_error"

STATUS_TEXT = {
    STATUS_OK: "格式正确",
    STATUS_NOT_FOUND: "不存在",
    STATUS_FORMAT_ERROR: "格式错误",
}


class MockState:
    """模拟平台状态：格式规则 + 已发行码清单。"""

    def __init__(self, issued: Optional[Set[str]] = None, pattern: Optional[re.Pattern] = None,
                 delay: float = 0.0) -> None:
        self.issued = issued or set()
        self.pattern = pattern
        self.delay = delay
        self.hits = 0
        self._lock = threading.Lock()

    def check(self, code: str) -> str:
        text = (code or "").strip()
        with self._lock:
            self.hits += 1
        if not text:
            return STATUS_FORMAT_ERROR
        if self.pattern is not None and not self.pattern.fullmatch(text):
            return STATUS_FORMAT_ERROR
        if self.issued and text not in self.issued:
            return STATUS_NOT_FOUND
        if not self.issued:
            # 未提供清单时，所有格式正确的码都返回「格式正确」
            return STATUS_OK
        return STATUS_OK


def _load_page() -> bytes:
    try:
        with open(PAGE_FILE, "rb") as fh:
            return fh.read()
    except OSError:
        return (
            "<!doctype html><meta charset='utf-8'><title>mock</title>"
            "<h3>模拟兑换页面文件缺失</h3>".encode("utf-8")
        )


def build_pattern_from_file(path: Optional[str]) -> Optional[re.Pattern]:
    """用本工具的格式分析器从清单文件归纳正则，使「格式错误」分支可被触发。"""
    if not path or not os.path.isfile(path):
        return None
    try:
        from couponfmt import analyzer
        from couponfmt.inputs import load_codes_from_file

        codes = load_codes_from_file(path)
        if len(codes) < 2:
            return None
        rule = analyzer.analyze(codes, name="mock", detect_checksum=False)
        return re.compile(rule.to_regex(), re.ASCII)
    except Exception:
        return None


def make_handler(state: MockState):
    page = _load_page()

    class Handler(BaseHTTPRequestHandler):
        server_version = "CouponMock/1.0"

        def log_message(self, fmt: str, *args) -> None:  # noqa: A003
            if not getattr(self.server, "quiet", False):
                print("[mock] " + fmt % args)

        def _send(self, code: int, body: bytes, content_type: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:  # noqa: N802
            if self.path in ("/", "/index.html"):
                self._send(200, page, "text/html; charset=utf-8")
            elif self.path == "/api/health":
                self._send(200, b'{"status":"ok"}', "application/json; charset=utf-8")
            else:
                self._send(404, b"not found", "text/plain; charset=utf-8")

        def do_POST(self) -> None:  # noqa: N802
            if self.path != "/api/redeem":
                self._send(404, b"not found", "text/plain; charset=utf-8")
                return
            try:
                length = int(self.headers.get("Content-Length") or 0)
                payload = json.loads(self.rfile.read(length) or b"{}")
                code = str(payload.get("code", ""))
            except (ValueError, json.JSONDecodeError):
                self._send(400, b'{"status":"bad_request"}', "application/json; charset=utf-8")
                return

            if state.delay:
                time.sleep(state.delay)
            status = state.check(code)
            body = json.dumps(
                {"status": status, "message": STATUS_TEXT[status], "code": code},
                ensure_ascii=False,
            ).encode("utf-8")
            self._send(200, body, "application/json; charset=utf-8")

    return Handler


def lan_ip() -> str:
    """获取本机在局域网中的 IPv4 地址（用于手机访问）。"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            # UDP connect 不会真正发包，仅用于让系统选出默认出口网卡的地址
            sock.connect(("8.8.8.8", 80))
            return str(sock.getsockname()[0])
        finally:
            sock.close()
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"


def access_urls(host: str, port: int) -> list:
    """返回可访问地址列表：本机地址在前，局域网地址在后。"""
    urls = ["http://127.0.0.1:%d/" % port]
    if host in ("0.0.0.0", "::", ""):
        ip = lan_ip()
        if ip and ip != "127.0.0.1":
            urls.append("http://%s:%d/" % (ip, port))
    elif host not in ("127.0.0.1", "localhost"):
        urls = ["http://%s:%d/" % (host, port)]
    return urls


def _build_state(issued_path: Optional[str], delay: float) -> MockState:
    issued: Set[str] = set()
    if issued_path and os.path.isfile(issued_path):
        from couponfmt.inputs import load_codes_from_file

        issued = set(load_codes_from_file(issued_path))
    pattern = build_pattern_from_file(issued_path)
    return MockState(issued=issued, pattern=pattern, delay=delay)


def serve(port: int = 8765, issued_path: Optional[str] = None, quiet: bool = False,
          delay: float = 0.0, host: str = HOST) -> None:
    """前台启动服务（阻塞）。

    host 默认 127.0.0.1（仅本机）；传 0.0.0.0 可让同一 Wi-Fi 下的手机访问。
    """
    state = _build_state(issued_path, delay)

    httpd = ThreadingHTTPServer((host, port), make_handler(state))
    httpd.daemon_threads = True
    httpd.quiet = quiet  # type: ignore[attr-defined]
    urls = access_urls(host, port)
    print("本地模拟兑换页面已启动：")
    for url in urls:
        print("  %s%s" % (url, "   <- 手机浏览器打开这个地址" if url is not urls[0] else "   (本机)"))
    print("有效码清单：%s" % (issued_path if state.issued else "（空，所有格式正确的码均返回「格式正确」）"))
    print("格式正则：%s" % (state.pattern.pattern if state.pattern else "（未启用，任意非空字符串视为格式正确）"))
    if len(urls) > 1:
        print("提示：手机需与本机在同一局域网；若打不开，请在 Windows 防火墙放行 TCP %d。" % port)
    print("按 Ctrl+C 停止。")
    try:
        httpd.serve_forever()
    finally:
        httpd.server_close()


_BACKGROUND_SERVERS: list = []


def start_background(port: int = 8765, issued_path: Optional[str] = None,
                     delay: float = 0.0, host: str = HOST) -> str:
    """后台线程启动服务，返回首选访问地址（本机地址）。"""
    state = _build_state(issued_path, delay)

    httpd = ThreadingHTTPServer((host, port), make_handler(state))
    httpd.daemon_threads = True
    httpd.quiet = True  # type: ignore[attr-defined]
    thread = threading.Thread(target=httpd.serve_forever, name="mock-server", daemon=True)
    thread.start()
    _BACKGROUND_SERVERS.append(httpd)
    return access_urls(host, port)[0]


def shutdown_background() -> None:
    """停止所有由 start_background 启动的服务（测试与退出时使用）。"""
    while _BACKGROUND_SERVERS:
        httpd = _BACKGROUND_SERVERS.pop()
        try:
            httpd.shutdown()
            httpd.server_close()
        except Exception:
            continue


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="本地模拟兑换页面")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--issued", default=None)
    ap.add_argument("--delay", type=float, default=0.0)
    ap.add_argument("--host", default=HOST, help="绑定地址，默认 127.0.0.1；填 0.0.0.0 允许手机访问")
    ns = ap.parse_args()
    serve(port=ns.port, issued_path=ns.issued, delay=ns.delay, host=ns.host)
