import unittest

from couponfmt import analyzer
from couponfmt.inputs import InputError
from couponfmt.models import FormatRule

FIXED = ["SAVE-2026-8F3K", "SAVE-2026-QW7M", "SAVE-2026-4XZ9", "SAVE-2026-7H2P"]
VARIABLE = ["GC-8K3M2", "GC-7QP", "GC-9XZ4L", "GC-5RT"]


class PositionalTest(unittest.TestCase):
    def setUp(self):
        self.rule = analyzer.analyze(FIXED, name="fixed")

    def test_mode(self):
        self.assertEqual(self.rule.mode, "positional")

    def test_regex_matches_samples(self):
        pattern = self.rule.compile()
        for code in FIXED:
            self.assertTrue(pattern.fullmatch(code), code)

    def test_prefix_and_separator(self):
        self.assertEqual(self.rule.prefix, "SAVE")
        self.assertEqual(self.rule.separators, ["-"])
        self.assertEqual(self.rule.length_min, 14)
        self.assertEqual(self.rule.length_max, 14)

    def test_fields(self):
        kinds = [f.kind for f in self.rule.fields]
        self.assertEqual(kinds.count("literal"), 4)  # SAVE, -, 2026, -
        var = self.rule.var_fields
        self.assertEqual(len(var), 1)
        self.assertEqual(var[0].min_len, 4)
        self.assertIn("A-Z", var[0].regex_class)

    def test_rejects_wrong_shape(self):
        pattern = self.rule.compile()
        for bad in ("SAVE-2025-8F3K", "SAVE-2026-8F3", "save-2026-8f3k", "SAVE_2026_8F3K"):
            self.assertFalse(pattern.fullmatch(bad), bad)


class VariableLengthTest(unittest.TestCase):
    def setUp(self):
        self.rule = analyzer.analyze(VARIABLE, name="var")

    def test_mode(self):
        self.assertEqual(self.rule.mode, "segmented")

    def test_length_range(self):
        self.assertEqual(self.rule.length_min, 6)
        self.assertEqual(self.rule.length_max, 8)

    def test_regex_matches(self):
        pattern = self.rule.compile()
        for code in VARIABLE:
            self.assertTrue(pattern.fullmatch(code), code)
        self.assertFalse(pattern.fullmatch("GC-1A"))
        self.assertFalse(pattern.fullmatch("GC-1A2B3C4D"))


class CoarseTest(unittest.TestCase):
    def test_no_common_structure(self):
        rule = analyzer.analyze(["XY12AB", "XY9C", "XY77Z"], name="coarse")
        self.assertEqual(rule.mode, "coarse")
        self.assertTrue(rule.compile().fullmatch("XY12AB"))
        self.assertTrue(rule.compile().fullmatch("XY9C"))
        self.assertLessEqual(rule.confidence, 0.45)


class SingleSampleTest(unittest.TestCase):
    def test_single(self):
        rule = analyzer.analyze(["PRO-7X9K"], name="one")
        self.assertEqual(rule.mode, "single")
        self.assertTrue(rule.compile().fullmatch("PRO-7X9K"))
        self.assertTrue(any("仅 1 个样本" in n for n in rule.notes))


class ReanalyzeTest(unittest.TestCase):
    def test_rule_updates_when_samples_change(self):
        first = analyzer.analyze(FIXED, name="r1")
        second = analyzer.analyze(["TRIAL2026ABCD", "TRIAL2026WXYZ", "TRIAL2026MNOP"], name="r2")
        self.assertNotEqual(first.to_regex(), second.to_regex())
        self.assertTrue(second.compile().fullmatch("TRIAL2026ABCD"))
        self.assertFalse(second.compile().fullmatch("SAVE-2026-8F3K"))


class ChecksumIntegrationTest(unittest.TestCase):
    def test_checksum_detected(self):
        import random

        from couponfmt.checksum import luhn_digit

        rng = random.Random(5)
        codes = []
        for _ in range(6):
            body = "".join(rng.choice("0123456789") for _ in range(11))
            codes.append(body + luhn_digit(body))
        rule = analyzer.analyze(codes, name="luhn")
        self.assertIsNotNone(rule.checksum)
        self.assertEqual(rule.checksum["algorithm"], "luhn")
        self.assertEqual(rule.checksum["length"], 1)


class InputValidationTest(unittest.TestCase):
    def test_empty_raises(self):
        with self.assertRaises(InputError):
            analyzer.analyze(["   ", ""])

    def test_too_long_raises(self):
        with self.assertRaises(InputError):
            analyzer.analyze(["A" * 300, "B" * 300])

    def test_duplicates_collapsed(self):
        rule = analyzer.analyze(["AB-12", "AB-12", "AB-34"], name="dup")
        self.assertEqual(len(rule.samples), 2)


class RoundTripTest(unittest.TestCase):
    def test_json_roundtrip(self):
        rule = analyzer.analyze(FIXED, name="rt")
        restored = FormatRule.from_dict(rule.to_dict())
        self.assertEqual(restored.to_regex(), rule.to_regex())
        self.assertEqual(restored.separators, rule.separators)
        self.assertEqual(restored.mode, rule.mode)


if __name__ == "__main__":
    unittest.main()
