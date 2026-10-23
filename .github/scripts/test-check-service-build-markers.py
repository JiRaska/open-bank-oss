#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline tests for check-service-build-markers.py, negative cases first."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "markers", Path(__file__).with_name("check-service-build-markers.py"))
markers = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(markers)
SHA = "a" * 40


def make(root, svc, **over):
    d = Path(root)
    body = {"service": svc, "sha": SHA, "result": "success"}
    body.update(over)
    (d / f"marker-{svc}.json").write_text(json.dumps(body))


class T(unittest.TestCase):
    def run_case(self, services, build):
        with tempfile.TemporaryDirectory() as d:
            build(d)
            return markers.verify(services, d, SHA)

    def test_missing_marker_fails(self):  # cancelled / skipped / failed leg
        self.assertTrue(self.run_case(["a", "b"], lambda d: make(d, "a")))

    def test_no_markers_fails(self):
        self.assertTrue(self.run_case(["a"], lambda d: None))

    def test_missing_directory_fails(self):
        self.assertTrue(markers.verify(["a"], "/nonexistent-dir", SHA))

    def test_started_marker_fails(self):  # stale success overwritten at leg start
        self.assertTrue(self.run_case(["a"], lambda d: make(d, "a", result="started")))

    def test_failure_and_cancelled_markers_fail(self):
        for r in ("failure", "cancelled"):
            self.assertTrue(self.run_case(["a"], lambda d, r=r: make(d, "a", result=r)))

    def test_non_success_marker_fails(self):
        self.assertTrue(self.run_case(["a"], lambda d: make(d, "a", result="failure")))

    def test_foreign_sha_fails(self):
        self.assertTrue(self.run_case(["a"], lambda d: make(d, "a", sha="b" * 40)))

    def test_mismatched_service_name_fails(self):
        self.assertTrue(self.run_case(["a"], lambda d: make(d, "a", service="z")))

    def test_extra_marker_fails(self):
        def b(d):
            make(d, "a"); make(d, "x")
        self.assertTrue(self.run_case(["a"], b))

    def test_corrupt_marker_fails(self):
        def b(d):
            make(d, "a"); (Path(d) / "marker-a.json").write_text("{")
        self.assertTrue(self.run_case(["a"], b))

    def test_invalid_matrix_fails(self):
        for s in ([], ["a", "a"], "a"):
            self.assertTrue(self.run_case(s, lambda d: make(d, "a")))

    def test_single_artifact_flat_layout_passes(self):  # regression: 1 match extracts flat
        self.assertEqual(self.run_case(["a"], lambda d: make(d, "a")), [])

    def test_nested_per_artifact_directory_is_rejected(self):
        def b(d):
            make(d, "a")
            n = Path(d) / "service-build-ok-a"
            n.mkdir()
            (n / "marker.json").write_text("{}")
        self.assertTrue(self.run_case(["a"], b))

    def test_unknown_file_fails(self):
        def b(d):
            make(d, "a"); (Path(d) / "marker.json").write_text("{}")
        self.assertTrue(self.run_case(["a"], b))

    def test_filename_content_disagreement_fails(self):
        def b(d):
            make(d, "a"); (Path(d) / "marker-a.json").write_text(
                json.dumps({"service": "b", "sha": SHA, "result": "success"}))
        self.assertTrue(self.run_case(["a"], b))

    def test_non_object_json_fails(self):
        def b(d):
            make(d, "a"); (Path(d) / "marker-a.json").write_text("[]")
        self.assertTrue(self.run_case(["a"], b))

    def test_exact_set_passes(self):
        def b(d):
            make(d, "a"); make(d, "b")
        self.assertEqual(self.run_case(["a", "b"], b), [])

    def test_negative_control_weakened_check_is_detected(self):
        src = Path(markers.__file__).read_text().replace("if missing:", "if False:")
        ns = {}
        exec(compile(src, "m", "exec"), ns)
        with tempfile.TemporaryDirectory() as d:
            make(d, "a")
            self.assertEqual(ns["verify"](["a", "b"], d, SHA), [], "mutant must pass the unsafe case")
            self.assertTrue(markers.verify(["a", "b"], d, SHA))


if __name__ == "__main__":
    unittest.main()
