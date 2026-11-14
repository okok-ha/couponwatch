import json
import os
import tempfile
import unittest

from couponfmt import analyzer, storage

FIXED = ["SAVE-2026-8F3K", "SAVE-2026-QW7M", "SAVE-2026-4XZ9"]


class StorageTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="couponfmt_")
        self.rule = analyzer.analyze(FIXED, name="store")

    def test_save_and_load(self):
        path = os.path.join(self.tmp, "r.json")
        storage.save_rule(self.rule, path=path)
        self.assertTrue(os.path.isfile(path))
        loaded = storage.load_rule(path)
        self.assertEqual(loaded.to_regex(), self.rule.to_regex())
        self.assertEqual(loaded.samples, self.rule.samples)

    def test_saved_json_contains_expected_keys(self):
        path = os.path.join(self.tmp, "r.json")
        storage.save_rule(self.rule, path=path)
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        for key in ("schema_version", "regex", "fields", "samples", "checksum"):
            self.assertIn(key, data)

    def test_load_missing(self):
        with self.assertRaises(storage.RuleFileError):
            storage.load_rule(os.path.join(self.tmp, "nope.json"))

    def test_load_broken_json(self):
        path = os.path.join(self.tmp, "bad.json")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("{not json")
        with self.assertRaises(storage.RuleFileError):
            storage.load_rule(path)

    def test_load_missing_fields(self):
        path = os.path.join(self.tmp, "empty.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"name": "x"}, fh)
        with self.assertRaises(storage.RuleFileError):
            storage.load_rule(path)

    def test_list_rules(self):
        storage.save_rule(self.rule, path=os.path.join(self.tmp, "a.json"))
        items = storage.list_rules(self.tmp)
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]["name"], "store")


if __name__ == "__main__":
    unittest.main()
