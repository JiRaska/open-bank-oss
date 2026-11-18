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

    def _result(self, directory, forks):
        path = Path(directory) / "result.json"
        path.write_text(json.dumps([{"benchmark": "tiny", "primaryMetric": {"score": 1.0},
                                     "secondaryMetrics": {bench.METRIC: {"score": 621.3, "rawData": forks}}}]))
        return bench.load_results(path)["tiny"]

    def test_a_high_plateau_fork_does_not_turn_unchanged_code_red(self):
        # The CI shape that failed unrelated PRs: one fork on the 656 plateau, baseline 592.
        with tempfile.TemporaryDirectory() as directory:
            measured = self._result(directory, [[656.0] * 3, [592.0] * 3, [616.0] * 3])
            self.assertEqual(measured, 592.0)
            self.assertFalse([f for f in bench.compare({"tiny"}, {"tiny": 592.0}, {"tiny": measured})[0] if f.startswith("tiny")])

    def test_an_extra_allocation_on_every_plateau_still_breaches(self):
        # Negative: one more 24-byte BigDecimal and one 40-byte array per op, on every fork.
        with tempfile.TemporaryDirectory() as directory:
            measured = self._result(directory, [[656.0 + 64] * 3, [592.0 + 64] * 3, [616.0 + 64] * 3])
            findings = [f for f in bench.compare({"tiny"}, {"tiny": 592.0}, {"tiny": measured})[0] if f.startswith("tiny")]
            self.assertTrue(any("exceeds baseline" in f for f in findings), findings)

    def test_without_raw_data_the_reported_score_is_used(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(self._result(directory, []), 621.3)


if __name__ == "__main__":
    unittest.main()
