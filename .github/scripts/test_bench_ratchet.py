#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("bench", Path(__file__).with_name("check-bench-alloc-baseline.py"))
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class RatchetTest(unittest.TestCase):
    def test_invalid_profiler_values_cannot_be_a_green_comparison(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "result.json"
            for value in (float("nan"), float("inf"), -1):
                path.write_text(bench._jmh({"xml": value}))
                with self.assertRaisesRegex(ValueError, "invalid allocation"):
                    bench.load_results(path)

    def test_only_measured_budget_changes_and_regression_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "baseline.json"
            original = {"benchmarks": {"xml": 63000.0, "money": 48.0}, "latency_bounds": {"money": {"p99_ms": 2}}}
            path.write_text(json.dumps(original))
            bench.ratchet_baseline(path, {"xml": 20832.1})
            got = json.loads(path.read_text())
            self.assertEqual(got["benchmarks"], {"xml": 20832.1, "money": 48.0})
            self.assertEqual(got["latency_bounds"], original["latency_bounds"])
            self.assertTrue(bench.compare({"xml", "money"}, got["benchmarks"], {"xml": 30000, "money": 48})[0])
            saved = path.read_bytes()
            for measurements in ({"xml": 63000}, {"unknown": 1}, {"xml": float("nan")}, {"xml": -1}, {}):
                with self.assertRaises(ValueError):
                    bench.ratchet_baseline(path, measurements)
                self.assertEqual(path.read_bytes(), saved)


if __name__ == "__main__":
    unittest.main()
