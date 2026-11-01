#!/usr/bin/env python3
"""Regression proof for the actual required Admin UI workflow verdict."""
import os
from pathlib import Path
import subprocess
import unittest
import yaml

class UiRequiredBuildTest(unittest.TestCase):
    def test_workflow_verdicts(self):
        root = Path(__file__).resolve().parents[2]
        workflow = yaml.safe_load((root / ".github/workflows/ci.yml").read_text())
        script = workflow["jobs"]["ui"]["steps"][0]["run"]
        base = dict(RESULT="success", DETECTION_RESULT="success", UI_CHANGED="true",
                    RBAC_TESTS="", REGISTRY_TEST="false", RBAC_RESULT="skipped",
                    EVENT="pull_request", REF="refs/pull/1/merge")
        cases = [
            ("changed success", {}, 0),
            ("unchanged skipped", dict(UI_CHANGED="false", RESULT="skipped"), 0),
            ("failed detection", dict(DETECTION_RESULT="failure"), 1),
            ("failed cross guard", dict(UI_CHANGED="false", RESULT="skipped",
                RBAC_TESTS="some-test", RBAC_RESULT="failure"), 1),
            ("failed build", dict(RESULT="failure"), 1),
            ("cancelled PR", dict(RESULT="cancelled"), 1),
            ("changed skipped build", dict(RESULT="skipped"), 1),
        ]
        for name, overrides, expected in cases:
            with self.subTest(name=name):
                result = subprocess.run(["bash", "-c", script],
                    env={**os.environ, **base, **overrides},
                    capture_output=True, text=True, timeout=5)
                self.assertEqual(expected, result.returncode, result.stdout + result.stderr)

if __name__ == "__main__":
    unittest.main()
