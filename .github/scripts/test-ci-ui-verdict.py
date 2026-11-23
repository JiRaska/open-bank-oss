#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual anchored verdict without needing a runner or YAML dependency."""
import os
from pathlib import Path
import re
import subprocess
import unittest

WORKFLOW = Path(__file__).resolve().parents[1] / "workflows/ci.yml"


class VerdictTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text()
        step = cls.workflow.split("      - &admin-ui-verdict\n", 1)[1].split("\n  # ---", 1)[0]
        cls.command = "\n".join(line[10:] for line in step.split("        run: |\n", 1)[1].splitlines())

    def verdict(self, expected, **overrides):
        env = dict(os.environ, RESULT="success", COVERAGE_RESULT="success", E2E_RESULT="success", DETECTION_RESULT="success", UI_CHANGED="true",
                   RBAC_TESTS="", REGISTRY_TEST="false", RBAC_RESULT="skipped",
                   EVENT="pull_request", REF="refs/pull/1/merge")
        env.update(overrides)
        result = subprocess.run(["bash", "-c", self.command], env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, expected, result.stdout + result.stderr)

    def test_build_required_for_changed_ui(self):
        for result in ("success", "failure", "cancelled", "skipped", "", "unknown"):
            with self.subTest(result=result):
                self.verdict(result == "success", RESULT=result)

    def test_unchanged_ui_may_skip(self):
        self.verdict(True, UI_CHANGED="false", RESULT="skipped", COVERAGE_RESULT="skipped", E2E_RESULT="skipped")

    def test_coverage_required_for_changed_ui(self):
        # ADR-0370 U3: the coverage floors are part of the same required verdict as the build.
        for result in ("success", "failure", "cancelled", "skipped", "", "unknown"):
            with self.subTest(result=result):
                self.verdict(result == "success", COVERAGE_RESULT=result)
        self.verdict(True, EVENT="push", COVERAGE_RESULT="cancelled")
        self.verdict(False, EVENT="push", COVERAGE_RESULT="failure")

    def test_every_e2e_shard_required_for_changed_ui(self):
        # The matrix aggregate is `failure` if ANY shard failed, `cancelled` if any was cancelled and
        # `skipped` if the job vanished: only `success` may pass, and nothing else may be waved through.
        for result in ("success", "failure", "cancelled", "skipped", "", "unknown"):
            with self.subTest(result=result):
                self.verdict(result == "success", E2E_RESULT=result)
        self.verdict(True, EVENT="push", E2E_RESULT="cancelled")
        self.verdict(False, EVENT="push", E2E_RESULT="failure")

    def test_sharded_e2e_job_is_a_fail_closed_matrix(self):
        job = self.workflow.split("\n  ui-e2e:\n", 1)[1].split("\n  # Gate job", 1)[0]
        self.assertIn("fail-fast: false", job)  # all shards report; none is silently abandoned
        shards = re.search(r"shard: \[([0-9, ]+)\]", job).group(1).split(", ")
        self.assertEqual(shards, [str(i) for i in range(1, len(shards) + 1)])
        # the --shard denominator must equal the matrix size, or part of the suite never runs
        self.assertEqual(set(re.findall(r"--shard=\$\{\{ matrix.shard \}\}/(\d+)", job)), {str(len(shards))})
        self.assertNotIn("continue-on-error: true\n        id: browser_e2e", job)

    def test_unit_tests_run_once_in_the_coverage_job(self):
        build = self.workflow.split("\n  ui-build:\n", 1)[1].split("\n  ui-e2e:", 1)[0]
        coverage = self.workflow.split("\n  ui-coverage:\n", 1)[1].split("\n  ui-evidence:", 1)[0]
        self.assertNotIn("npm run test --", build)
        self.assertIn("npm run test -- --coverage", coverage)

    def test_detection_is_required_and_decision_must_be_known(self):
        for result in ("failure", "cancelled", "skipped", ""):
            self.verdict(False, DETECTION_RESULT=result, UI_CHANGED="false", RESULT="skipped")
        for decision in ("", "unknown"):
            self.verdict(False, UI_CHANGED=decision, RESULT="skipped")

    def test_cross_package_guards_cannot_disappear(self):
        for selector in ({"RBAC_TESTS": "rbac.test.ts"}, {"REGISTRY_TEST": "true"}):
            for result in ("success", "failure", "cancelled", "skipped", ""):
                self.verdict(result == "success", UI_CHANGED="false", RESULT="skipped",
                             RBAC_RESULT=result, **selector)

    def test_push_cancellation_keeps_existing_semantics(self):
        self.verdict(True, EVENT="push", RESULT="cancelled")
        self.verdict(False, EVENT="push", RESULT="failure")

    def test_aggregates_share_one_verdict_without_serial_queue(self):
        validate = self.workflow.split("  validate:\n", 1)[1].split("\n  app-status:", 1)[0]
        needs = re.search(r"    needs: \[(.+)\]", validate).group(1).split(", ")
        self.assertEqual(set(needs), {"agent-admission", "ruleset-evidence", "gates", "changes-ui", "ui-build", "ui-e2e", "ui-coverage", "ui-rbac-contract"})
        self.assertIn("      - *admin-ui-verdict", validate)
        self.assertNotIn("needs.ui.result", validate)
        self.assertIn("    name: Admin UI\n", self.workflow)
        self.assertIn("    name: Validate manifests\n", validate)
        self.assertEqual(self.workflow.count("&admin-ui-verdict"), 1)


if __name__ == "__main__":
    unittest.main()
