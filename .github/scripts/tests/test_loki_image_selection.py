#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline regression proof for same-artifact Loki registry recovery."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "check-loki-rules.py"
SPEC = importlib.util.spec_from_file_location("loki_image_gate", SCRIPT)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class LokiImageSelectionTest(unittest.TestCase):
    def check_selection(self, outcomes, expected):
        results = [
            item if isinstance(item, BaseException) else
            subprocess.CompletedProcess([], item, stdout="", stderr="registry unavailable")
            for item in outcomes
        ]
        with patch.object(GATE.subprocess, "run", side_effect=results) as run:
            image, detail = GATE.select_loki_image()
        self.assertEqual(expected, image)
        self.assertEqual(expected is None, bool(detail))
        for call in run.call_args_list:
            ref = call.args[0][-1]
            self.assertIn(ref, (GATE.LOKI_IMAGE, "mirror.gcr.io/" + GATE.LOKI_IMAGE))
            self.assertIn(call.kwargs.get("timeout"), (10, 90))
        self.assertEqual(len(outcomes), run.call_count)
        return run.call_args_list

    def test_cached_mirror_requires_no_pull(self):
        calls = self.check_selection([0], "mirror.gcr.io/" + GATE.LOKI_IMAGE)
        self.assertEqual(["docker", "image", "inspect"], calls[0].args[0][:3])

    def test_cached_upstream_requires_no_pull(self):
        calls = self.check_selection([1, 0], GATE.LOKI_IMAGE)
        self.assertTrue(all(call.args[0][1] == "image" for call in calls))

    def test_mirror_pull_succeeds(self):
        self.check_selection([1, 1, 0], "mirror.gcr.io/" + GATE.LOKI_IMAGE)

    def test_upstream_recovers_mirror_failure(self):
        self.check_selection([1, 1, 1, 0], GATE.LOKI_IMAGE)

    def test_upstream_recovers_mirror_timeout(self):
        self.check_selection([1, 1, subprocess.TimeoutExpired("docker", 90), 0], GATE.LOKI_IMAGE)

    def test_both_failures_remain_fatal(self):
        self.check_selection([1, 1, 1, 1], None)

    def test_both_timeouts_remain_fatal(self):
        timeout = subprocess.TimeoutExpired("docker", 90)
        self.check_selection([1, 1, timeout, timeout], None)


if __name__ == "__main__":
    unittest.main()
