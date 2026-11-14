import unittest

from couponfmt import analyzer, generator, validator

FIXED = ["SAVE-2026-8F3K", "SAVE-2026-QW7M", "SAVE-2026-4XZ9", "SAVE-2026-7H2P"]


class GenerateTest(unittest.TestCase):
    def setUp(self):
        self.rule = analyzer.analyze(FIXED, name="gen")

    def test_count_and_uniqueness(self):
        codes = generator.generate(self.rule, count=50, seed=1)
        self.assertEqual(len(codes), 50)
        self.assertEqual(len(set(codes)), 50)

    def test_all_match_rule(self):
        codes = generator.generate(self.rule, count=30, seed=2)
        results = validator.validate_batch(codes, self.rule)
        self.assertTrue(all(r.ok for r in results))

    def test_deterministic_with_seed(self):
        a = generator.generate(self.rule, count=10, seed=99)
        b = generator.generate(self.rule, count=10, seed=99)
        self.assertEqual(a, b)

    def test_seed_changes_output(self):
        a = generator.generate(self.rule, count=10, seed=1)
        b = generator.generate(self.rule, count=10, seed=2)
        self.assertNotEqual(a, b)

    def test_avoid_ambiguous(self):
        codes = generator.generate(self.rule, count=20, seed=3, avoid_ambiguous=True)
        for code in codes:
            tail = code.split("-")[-1]
            for ch in "0O1I":
                self.assertNotIn(ch, tail, code)

    def test_search_space(self):
        space = generator.search_space(self.rule)
        self.assertEqual(space, 36 ** 4)

    def test_zero_count_raises(self):
        with self.assertRaises(generator.GenerateError):
            generator.generate(self.rule, count=0)

    def test_impossible_uniqueness_raises(self):
        rule = analyzer.analyze(["AB-1", "AB-2", "AB-3"], name="tiny")
        with self.assertRaises(generator.GenerateError):
            generator.generate(rule, count=10 ** 6, unique=True)


class ChecksumGenerationTest(unittest.TestCase):
    def test_generated_codes_carry_valid_checksum(self):
        import random

        from couponfmt.checksum import luhn_digit

        rng = random.Random(8)
        samples = []
        for _ in range(6):
            body = "".join(rng.choice("0123456789") for _ in range(11))
            samples.append(body + luhn_digit(body))
        rule = analyzer.analyze(samples, name="luhn")
        self.assertIsNotNone(rule.checksum)

        codes = generator.generate(rule, count=20, seed=4)
        results = validator.validate_batch(codes, rule)
        self.assertTrue(all(r.ok for r in results), [r.to_row() for r in results if not r.ok])


if __name__ == "__main__":
    unittest.main()
