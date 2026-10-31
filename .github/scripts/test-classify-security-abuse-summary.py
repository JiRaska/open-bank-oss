#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression cases for hard status failures versus advisory latency."""

import copy
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "security_abuse_summary", Path(__file__).with_name("classify-security-abuse-summary.py")
)
classifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(classifier)


def summary(count=0, latency=False):
    return {"metrics": {
        "security_contract_breaches": {"count": count, "thresholds": {"count==0": count != 0}},
        "security_probes_total": {"count": 1},
        "http_reqs": {"count": 1},
        "security_enumeration_probes_total": {"count": 60},
        "enumeration_ms": {"p(95)": 1, "thresholds": {"p(95)<800": latency}},
    }}


class SecurityAbuseSummaryTest(unittest.TestCase):
    def test_cli_missing_malformed_and_valid_export(self):
        script = Path(__file__).with_name("classify-security-abuse-summary.py")
        with tempfile.TemporaryDirectory() as directory:
            export = Path(directory) / "summary.json"
            for contents, expected in ((None, 1), ("{", 1), (json.dumps(summary()), 0)):
                if contents is not None:
                    export.write_text(contents)
                result = subprocess.run(
                    [sys.executable, str(script), "--summary", str(export), "--k6-exit", "0"],
                    capture_output=True, text=True, check=False,
                )
                with self.subTest(contents=contents):
                    self.assertEqual(result.returncode, expected, result.stderr)

    def test_empty_or_no_http_execution_is_failure(self):
        for name in ("security_probes_total", "security_enumeration_probes_total", "http_reqs"):
            for count in (None, 0, -1, True, "1", 0.5, float("nan"), float("inf")):
                value = summary()
                value["metrics"][name] = {"count": count}
                with self.subTest(name=name, count=count), self.assertRaises(ValueError):
                    classifier.classify(value, 0)
            value = summary()
            del value["metrics"][name]
            with self.assertRaises(ValueError):
                classifier.classify(value, 0)

    def test_identity_traffic_cannot_hide_failed_enumeration_scenario(self):
        value = summary()
        value["metrics"]["security_probes_total"]["count"] = 109932
        value["metrics"]["http_reqs"]["count"] = 109932
        value["metrics"]["security_enumeration_probes_total"]["count"] = 0
        with self.assertRaisesRegex(ValueError, "security_enumeration_probes_total"):
            classifier.classify(value, 0)

    def test_native_legacy_zero_count_passes(self):
        self.assertEqual(classifier.classify(summary(), 0), "pass")

    def test_positive_breach_is_hard_failure_even_with_zero_exit(self):
        for code in (0, 99, 107):
            with self.subTest(code=code), self.assertRaises(ValueError):
                classifier.classify(summary(1), code)

    def test_only_proven_latency_failure_with_exit_99_is_advisory(self):
        self.assertEqual(classifier.classify(summary(latency=True), 99), "advisory-latency")

    def test_runtime_errors_are_hard_failures_with_or_without_latency_breach(self):
        for code in (1, 2, 107, 137):
            for latency in (False, True):
                with self.subTest(code=code, latency=latency), self.assertRaises(ValueError):
                    classifier.classify(summary(latency=latency), code)

    def test_unexplained_threshold_exit_is_failure(self):
        with self.assertRaises(ValueError):
            classifier.classify(summary(), 99)

    def test_failed_threshold_with_zero_exit_is_inconsistent(self):
        with self.assertRaises(ValueError):
            classifier.classify(summary(latency=True), 0)

    def test_unknown_failed_threshold_is_not_advisory(self):
        value = summary(latency=True)
        value["metrics"]["other"] = {"thresholds": {"count==0": True}}
        with self.assertRaises(ValueError):
            classifier.classify(value, 99)

    def test_missing_or_malformed_metric_structure_is_failure(self):
        for value in (None, [], {}, {"metrics": []}, {"metrics": {}},
                      {"metrics": {"security_contract_breaches": []}}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                classifier.classify(value, 0)

    def test_missing_or_invalid_count_types_are_failure(self):
        for count in (None, True, False, "0", [], {}, -1, 0.5, float("nan"), float("inf")):
            value = summary()
            value["metrics"]["security_contract_breaches"]["count"] = count
            with self.subTest(count=count), self.assertRaises(ValueError):
                classifier.classify(value, 0)
        value = summary()
        del value["metrics"]["security_contract_breaches"]["count"]
        with self.assertRaises(ValueError):
            classifier.classify(value, 0)

    def test_modern_nested_count_is_not_the_pinned_export_format(self):
        value = summary()
        value["metrics"]["security_contract_breaches"] = {"values": {"count": 0}}
        with self.assertRaises(ValueError):
            classifier.classify(value, 0)

    def test_malformed_or_modern_threshold_results_are_failure(self):
        for thresholds in (None, [], {"p(95)<800": {"ok": False}}, {"p(95)<800": "true"}):
            value = summary()
            value["metrics"]["enumeration_ms"]["thresholds"] = copy.deepcopy(thresholds)
            with self.subTest(thresholds=thresholds), self.assertRaises(ValueError):
                classifier.classify(value, 99)


if __name__ == "__main__":
    unittest.main()
