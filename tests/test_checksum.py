import unittest

from couponfmt import checksum as c


class LuhnTest(unittest.TestCase):
    def test_known_vector(self):
        self.assertEqual(c.luhn_digit("7992739871"), "3")

    def test_verify_roundtrip(self):
        payload = "123456789"
        full = payload + c.luhn_digit(payload)
        self.assertTrue(c.verify_checksum(full, "luhn", 1))
        self.assertFalse(c.verify_checksum(full[:-1] + "0" if full[-1] != "0" else full[:-1] + "1", "luhn", 1))


class DammTest(unittest.TestCase):
    def test_known_vector(self):
        self.assertEqual(c.damm_digit("572"), "4")

    def test_verify(self):
        self.assertTrue(c.verify_checksum("5724", "damm", 1))


class VerhoeffTest(unittest.TestCase):
    def test_known_vector(self):
        self.assertEqual(c.verhoeff_digit("236"), "3")

    def test_verify(self):
        self.assertTrue(c.verify_checksum("2363", "verhoeff", 1))
        self.assertFalse(c.verify_checksum("2364", "verhoeff", 1))


class Mod97Test(unittest.TestCase):
    def test_self_consistent(self):
        for payload in ("ABC123", "0001", "ZZZZ"):
            full = payload + c.mod97_10(payload)
            self.assertTrue(c.verify_checksum(full, "mod97_10", 2), full)


class DetectTest(unittest.TestCase):
    def _luhn_codes(self, n=5):
        import random

        rng = random.Random(11)
        out = []
        for _ in range(n):
            body = "".join(rng.choice("0123456789") for _ in range(11))
            out.append(body + c.luhn_digit(body))
        return out

    def test_detect_luhn(self):
        found = c.detect(self._luhn_codes(6))
        self.assertIsNotNone(found)
        self.assertEqual(found["algorithm"], "luhn")
        self.assertGreater(found["confidence"], 0.99)

    def test_too_few_samples(self):
        self.assertIsNone(c.detect(self._luhn_codes(2)))

    def test_random_data_no_false_positive(self):
        import random

        rng = random.Random(3)
        codes = ["".join(rng.choice("0123456789") for _ in range(12)) for _ in range(6)]
        found = c.detect(codes)
        self.assertIsNone(found, "随机数字串不应稳定命中校验位算法：%r" % (found,))


class ApplyTest(unittest.TestCase):
    def test_apply_keeps_separators(self):
        code = "12-3456-7890"
        fixed = c.apply_checksum(code, "luhn", 1)
        self.assertEqual(len(fixed), len(code))
        self.assertEqual(fixed[2], "-")
        self.assertEqual(fixed[7], "-")
        self.assertTrue(c.verify_checksum(fixed, "luhn", 1))
        self.assertEqual(c.compact(fixed)[:9], "123456789")

    def test_non_digit_payload_is_noop(self):
        # Luhn 只适用于数字；载荷含字母时应原样返回而不是抛异常
        self.assertEqual(c.apply_checksum("AB-1234", "luhn", 1), "AB-1234")

    def test_unknown_algorithm_is_noop(self):
        self.assertEqual(c.apply_checksum("ABC123", "nope", 1), "ABC123")


if __name__ == "__main__":
    unittest.main()
