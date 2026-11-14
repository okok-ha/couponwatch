import csv
import os
import tempfile
import unittest

from couponfmt import analyzer, exporter, generator, reporter

FIXED = ["SAVE-2026-8F3K", "SAVE-2026-QW7M", "SAVE-2026-4XZ9"]


class ReporterTest(unittest.TestCase):
    def setUp(self):
        self.rule = analyzer.analyze(FIXED, name="rep")
        self.codes = generator.generate(self.rule, count=40, seed=5)

    def test_stats_keys(self):
        stats = reporter.analyze_codes(self.codes, self.rule)
        for key in ("total", "length_distribution", "char_frequency", "match_rate", "char_entropy"):
            self.assertIn(key, stats)
        self.assertEqual(stats["total"], 40)
        self.assertAlmostEqual(stats["match_rate"], 1.0, places=6)

    def test_report_text_sections(self):
        text = reporter.format_report(reporter.analyze_codes(self.codes, self.rule), self.rule)
        for section in ("【1】基本信息", "【2】长度分布", "【3】字符出现频率", "【4】字符类别分布", "【5】格式匹配率"):
            self.assertIn(section, text)
        self.assertIn("不代表任何兑换码在真实平台上的有效性", text)

    def test_report_without_rule(self):
        text = reporter.format_report(reporter.analyze_codes(["ABC", "ABCD"]))
        self.assertIn("【1】基本信息", text)
        self.assertNotIn("【5】格式匹配率", text)


class ExporterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="couponfmt_exp_")
        self.rule = analyzer.analyze(FIXED, name="exp")
        self.codes = generator.generate(self.rule, count=12, seed=6)

    def test_export_txt(self):
        path = os.path.join(self.tmp, "codes.txt")
        exporter.export_txt(path, self.codes, rule=self.rule)
        with open(path, "r", encoding="utf-8") as fh:
            lines = [ln.strip() for ln in fh if ln.strip() and not ln.startswith("#")]
        self.assertEqual(lines, self.codes)

    def test_export_csv_roundtrip(self):
        path = os.path.join(self.tmp, "codes.csv")
        exporter.export_csv(path, exporter.codes_to_rows(self.codes, self.rule))
        with open(path, "r", encoding="utf-8-sig", newline="") as fh:
            rows = list(csv.DictReader(fh))
        self.assertEqual(len(rows), len(self.codes))
        self.assertEqual(rows[0]["code"], self.codes[0])
        self.assertEqual(rows[0]["match"], "PASS")

    def test_export_empty_csv_raises(self):
        with self.assertRaises(ValueError):
            exporter.export_csv(os.path.join(self.tmp, "e.csv"), [])

    def test_export_report(self):
        path = os.path.join(self.tmp, "report.txt")
        exporter.export_report_txt(path, "hello 报告")
        with open(path, "r", encoding="utf-8") as fh:
            self.assertIn("报告", fh.read())


if __name__ == "__main__":
    unittest.main()
