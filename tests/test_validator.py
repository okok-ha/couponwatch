import unittest

from couponfmt import analyzer, validator

FIXED = ["SAVE-2026-8F3K", "SAVE-2026-QW7M", "SAVE-2026-4XZ9", "SAVE-2026-7H2P"]


class ValidateTest(unittest.TestCase):
    def setUp(self):
        self.rule = analyzer.analyze(FIXED, name="val")

    def test_pass(self):
        res = validator.validate("SAVE-2026-9A8B", self.rule)
        self.assertTrue(res.ok)
        self.assertTrue(res.regex_ok)

    def test_wrong_prefix(self):
        res = validator.validate("SAVE-2025-8F3K", self.rule)
        self.assertFalse(res.ok)
        self.assertTrue(any("固定文本" in i for i in res.issues))

    def test_wrong_length(self):
        res = validator.validate("SAVE-2026-8F3", self.rule)
        self.assertFalse(res.ok)
        self.assertFalse(res.length_ok)

    def test_empty(self):
        res = validator.validate("   ", self.rule)
        self.assertFalse(res.ok)

    def test_strict_mode(self):
        # 'A' 未在样本中出现，通用模式通过、严格模式不通过
        loose = validator.validate("SAVE-2026-AAAA", self.rule, strict=False)
        strict = validator.validate("SAVE-2026-AAAA", self.rule, strict=True)
        self.assertTrue(loose.ok)
        self.assertFalse(strict.ok)

    def test_explain_lines(self):
        lines = validator.explain(self.rule)
        self.assertTrue(lines)
        self.assertIn("SAVE", "\n".join(lines))

    def test_batch_pass_rate(self):
        codes = ["SAVE-2026-8F3K", "BAD-CODE", "SAVE-2026-QW7M"]
        results = validator.validate_batch(codes, self.rule)
        self.assertAlmostEqual(validator.pass_rate(results), 2 / 3, places=6)

    def test_row_shape(self):
        row = validator.validate("SAVE-2026-8F3K", self.rule).to_row()
        self.assertEqual(row["ok"], "PASS")
        self.assertEqual(set(row), {"code", "ok", "regex_ok", "checksum_ok", "length_ok", "mode", "issues"})


if __name__ == "__main__":
    unittest.main()
