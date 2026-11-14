import csv
import json
import os
import socket
import tempfile
import unittest
import urllib.request

from automation import mock_server, runner

ISSUED = ["SAVE-2026-8F3K", "SAVE-2026-QW7M", "SAVE-2026-4XZ9"]


def _free_port() -> int:
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


def _write(path: str, lines) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        for line in lines:
            fh.write(line + "\n")


class MockServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="couponfmt_mock_")
        cls.issued_path = os.path.join(cls.tmp, "issued.txt")
        _write(cls.issued_path, ISSUED)
        cls.port = _free_port()
        cls.base = mock_server.start_background(port=cls.port, issued_path=cls.issued_path)

    @classmethod
    def tearDownClass(cls):
        mock_server.shutdown_background()

    def _redeem(self, code: str) -> dict:
        payload = json.dumps({"code": code}).encode("utf-8")
        req = urllib.request.Request(
            self.base + "api/redeem",
            data=payload,
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def test_page_served(self):
        with urllib.request.urlopen(self.base, timeout=5) as resp:
            html = resp.read().decode("utf-8")
        self.assertIn('id="code"', html)
        self.assertIn('id="submit"', html)
        self.assertIn('id="result"', html)

    def test_issued_code_ok(self):
        data = self._redeem("SAVE-2026-8F3K")
        self.assertEqual(data["status"], "ok")
        self.assertEqual(data["message"], "格式正确")

    def test_format_valid_but_not_issued(self):
        data = self._redeem("SAVE-2026-ZZZZ")
        self.assertEqual(data["status"], "not_found")
        self.assertEqual(data["message"], "不存在")

    def test_format_error(self):
        data = self._redeem("BAD-CODE-1234")
        self.assertEqual(data["status"], "format_error")
        self.assertEqual(data["message"], "格式错误")


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="couponfmt_run_")
        self.codes = os.path.join(self.tmp, "codes.txt")
        _write(self.codes, ISSUED + ["SAVE-2026-ZZZZ", "SAVE-2026-YYYY"])
        self.csv = os.path.join(self.tmp, "report.csv")

    def test_dry_run_respects_max_attempts(self):
        summary = runner.run(runner.RunConfig(
            url="http://127.0.0.1:8765/",
            codes_path=self.codes,
            max_attempts=3,
            delay=0,
            csv_path=self.csv,
            dry_run=True,
            keyboard_control=False,
        ))
        self.assertEqual(summary["tested"], 3)
        self.assertIn(summary["stop_reason"], ("达到最大测试次数 3", "测试码已全部执行完毕"))
        with open(self.csv, "r", encoding="utf-8-sig", newline="") as fh:
            rows = list(csv.DictReader(fh))
        self.assertEqual(len(rows), 3)
        self.assertTrue({"seq", "timestamp", "code", "status"} <= set(rows[0]))

    def test_remote_url_blocked(self):
        with self.assertRaises(runner.RunnerError):
            runner.run(runner.RunConfig(
                url="http://example.com/",
                codes_path=self.codes,
                dry_run=True,
                csv_path=self.csv,
                keyboard_control=False,
            ))

    def test_missing_codes_file(self):
        with self.assertRaises(runner.RunnerError):
            runner.run(runner.RunConfig(
                url="http://127.0.0.1:8765/",
                codes_path=os.path.join(self.tmp, "nope.txt"),
                dry_run=True,
                csv_path=self.csv,
                keyboard_control=False,
            ))

    def test_classify_text(self):
        self.assertEqual(runner._classify("格式正确"), "ok")
        self.assertEqual(runner._classify("兑换码不存在"), "not_found")
        self.assertEqual(runner._classify("格式错误：长度不符"), "format_error")
        self.assertEqual(runner._classify("嗯？"), "unknown")

    def test_summary_text(self):
        summary = {
            "url": "http://127.0.0.1:8765/", "driver": "dry-run", "dry_run": True,
            "tested": 2, "planned": 5, "max_attempts": 5, "counts": {"ok": 1, "not_found": 1},
            "first_ok": "A", "stop_reason": "完成", "elapsed_sec": 0.1, "csv_path": "x.csv",
        }
        text = runner.format_summary(summary)
        self.assertIn("自动化测试汇总", text)
        self.assertIn("格式正确      : 1", text)


class ControlTest(unittest.TestCase):
    def test_pause_blocks_and_resume_releases(self):
        import threading
        import time

        control = runner.RunControl()
        control.pause()
        self.assertTrue(control.is_paused)
        done = []

        def worker():
            control.wait()
            done.append(True)

        thread = threading.Thread(target=worker, daemon=True)
        thread.start()
        time.sleep(0.2)
        self.assertEqual(done, [])
        control.resume()
        thread.join(timeout=2)
        self.assertEqual(done, [True])

    def test_stop_raises(self):
        control = runner.RunControl()
        control.stop()
        with self.assertRaises(runner.StopRequested):
            control.wait()

    def test_sleep_interrupted_by_stop(self):
        import threading
        import time

        control = runner.RunControl()
        threading.Timer(0.1, control.stop).start()
        started = time.time()
        with self.assertRaises(runner.StopRequested):
            control.sleep(5)
        self.assertLess(time.time() - started, 2)


class MobileAccessTest(unittest.TestCase):
    def test_lan_ip_is_routable_string(self):
        ip = mock_server.lan_ip()
        self.assertTrue(ip)
        self.assertRegex(ip, r"^\d{1,3}(\.\d{1,3}){3}$")

    def test_access_urls_local_only(self):
        urls = mock_server.access_urls("127.0.0.1", 8765)
        self.assertEqual(urls, ["http://127.0.0.1:8765/"])

    def test_access_urls_lan(self):
        urls = mock_server.access_urls("0.0.0.0", 8765)
        self.assertEqual(urls[0], "http://127.0.0.1:8765/")
        self.assertGreaterEqual(len(urls), 1)

    def test_private_host_allowlist(self):
        for url in ("http://10.1.2.3:80/", "http://192.168.1.9:8765/", "http://172.20.0.5:80/",
                    "http://127.0.0.1:8765/", "file:///tmp/x.html"):
            runner._assert_allowed(url, allow_remote=False)
        for url in ("http://example.com/", "http://8.8.8.8/"):
            with self.assertRaises(runner.RunnerError):
                runner._assert_allowed(url, allow_remote=False)

    def test_lan_binding_serves_page(self):
        port = _free_port()
        url = mock_server.start_background(port=port, issued_path=None, host="0.0.0.0")
        try:
            with urllib.request.urlopen(url, timeout=5) as resp:
                html = resp.read().decode("utf-8")
            self.assertIn("width=device-width", html)
        finally:
            mock_server.shutdown_background()


class DeviceConfigTest(unittest.TestCase):
    def test_runconfig_defaults(self):
        cfg = runner.RunConfig()
        self.assertEqual(cfg.device, "")
        self.assertEqual(cfg.cdp_url, "")

    def test_cdp_url_must_be_local(self):
        # CDP 调试端口只应在本机/局域网内使用，公网地址必须被拦截
        runner._assert_allowed("http://127.0.0.1:9222", allow_remote=False)
        runner._assert_allowed("http://10.0.0.5:9222", allow_remote=False)
        with self.assertRaises(runner.RunnerError):
            runner._assert_allowed("http://example.com:9222", allow_remote=False)

    def test_summary_shows_device(self):
        summary = {
            "url": "http://127.0.0.1:8765/", "driver": "playwright", "dry_run": False,
            "device": "iPhone 13", "cdp_url": "", "tested": 1, "planned": 1,
            "max_attempts": 1, "counts": {"ok": 1}, "first_ok": "A",
            "stop_reason": "完成", "elapsed_sec": 0.1, "csv_path": "x.csv",
        }
        self.assertIn("设备模拟 iPhone 13", runner.format_summary(summary))


if __name__ == "__main__":
    unittest.main()
