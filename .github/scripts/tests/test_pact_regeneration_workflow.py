# SPDX-License-Identifier: Apache-2.0
"""Execute the workflow's real scope verdict and freshness guard."""
import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

import yaml

WORKFLOW = Path(__file__).resolve().parents[2] / "workflows/pact-drift-check.yml"


class WorkflowScopeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.steps = yaml.safe_load(WORKFLOW.read_text())["jobs"]["drift-check"]["steps"]

    def test_scope_verdict_requires_successful_classification_and_expected_shards(self):
        script = self.steps[0]["run"]
        for scope, shard, decision, expected in [
            ("true", "success", "success", True),
            ("false", "skipped", "success", True),
            ("false", "failure", "success", False),
            ("true", "skipped", "success", False),
            ("", "skipped", "success", False),
            ("false", "skipped", "failure", False),
            ("false", "cancelled", "success", False),
        ]:
            with self.subTest(scope=scope, shard=shard, decision=decision):
                result = subprocess.run(["bash", "-c", script], capture_output=True,
                                        env=dict(os.environ, JVM_REQUIRED=scope,
                                                 SHARD_RESULT=shard, SCOPE_RESULT=decision),
                                        timeout=10, check=False)
                self.assertEqual(result.returncode == 0, expected, result.stdout)

    def test_ui_freshness_and_restored_jvm_archive_proof_are_required(self):
        script = next(step["run"] for step in self.steps if step.get("name") ==
                      "Verify every committed pact was actually regenerated")
        for scope, fresh_ui, jvm_verified, expected in [
            ("false", True, False, True), ("false", False, True, False),
            ("true", True, False, False), ("true", True, True, True),
        ]:
            with self.subTest(scope=scope, fresh_ui=fresh_ui, jvm_verified=jvm_verified):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    (root / "pacts").mkdir()
                    marker = root / "pact-regen-marker"
                    marker.touch()
                    now = time.time()
                    os.utime(marker, (now, now))
                    for name, fresh in [("ui.json", fresh_ui), ("jvm.json", False)]:
                        pact = root / "pacts" / name
                        pact.write_text("{}")
                        stamp = now + 10 if fresh else now - 10
                        os.utime(pact, (stamp, stamp))
                    (root / "pact-uncovered.txt").write_text("")
                    (root / "expected-js-pacts.txt").write_text("pacts/ui.json\n")
                    # Main restores JVM archives only after exact source/coverage validation.
                    # Restored timestamps may predate this run; presence is still mandatory.
                    (root / "expected-jvm-pacts.txt").write_text(
                        "pacts/jvm.json\n" if scope == "true" and jvm_verified else "")
                    result = subprocess.run(["bash", "-c", script], cwd=root,
                                            env=dict(os.environ, JVM_REQUIRED=scope,
                                                     RUNNER_TEMP=directory),
                                            capture_output=True, timeout=10, check=False)
                    self.assertEqual(result.returncode == 0, expected, result.stdout)


if __name__ == "__main__":
    unittest.main()
