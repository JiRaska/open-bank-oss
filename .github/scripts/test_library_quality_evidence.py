#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import unittest
from pathlib import Path

from check_library_quality_evidence import JOBS, PROOF_PATHS, WORKFLOWS, applicable, verdict


class EvidenceTest(unittest.TestCase):
    def runs(self):
        return [dict(id=i, path=w, head_sha="head", event="pull_request", status="completed", conclusion="success",
                     jobs=[dict(name=JOBS[w], status="completed", conclusion="success")])
                for i, w in enumerate(WORKFLOWS)]

    def test_all_current_proofs_required(self):
        self.assertEqual(verdict(self.runs(), "head")[0], "success")
        for i in range(3):
            runs = self.runs()
            runs.pop(i)
            self.assertEqual(verdict(runs, "head")[0], "pending")

    def test_failed_cancelled_skipped_and_timed_out_are_not_passes(self):
        for conclusion in ("failure", "cancelled", "skipped", "timed_out", "neutral", "action_required"):
            for i in range(3):
                runs = self.runs()
                runs[i]["conclusion"] = conclusion
                self.assertEqual(verdict(runs, "head")[0], "failure")

    def test_wrong_revision_event_and_workflow_cannot_supply_proof(self):
        for key, value in (("head_sha", "old"), ("event", "workflow_dispatch"), ("path", "spoof.yml")):
            runs = self.runs()
            runs[0][key] = value
            self.assertEqual(verdict(runs, "head")[0], "pending")

    def test_green_workflow_with_missing_or_skipped_job_is_rejected(self):
        for jobs in ([], [dict(name="unrelated", status="completed", conclusion="success")],
                     [dict(name=JOBS[WORKFLOWS[0]], status="completed", conclusion="skipped")]):
            runs = self.runs()
            runs[0]["jobs"] = jobs
            self.assertEqual(verdict(runs, "head")[0], "failure")

    def test_in_progress_is_pending(self):
        runs = self.runs()
        runs[0]["status"] = "in_progress"
        self.assertEqual(verdict(runs, "head")[0], "pending")

    def test_latest_run_supersedes_old_head_attempt(self):
        runs = self.runs()
        runs.append(dict(runs[0], id=100, conclusion="failure"))
        self.assertEqual(verdict(runs, "head")[0], "failure")
        runs.append(dict(runs[0], id=101))
        self.assertEqual(verdict(runs, "head")[0], "success")

    def test_workflow_triggers_and_required_gate_match_the_evidence_scope(self):
        root = Path(__file__).resolve().parents[2]
        for workflow in WORKFLOWS:
            source = (root / workflow).read_text()
            for pattern in ("openbank-libs*/**", "fuzz/**", ".clusterfuzzlite/**", *WORKFLOWS, *PROOF_PATHS):
                self.assertIn(f'- "{pattern}"', source, (workflow, pattern))
            self.assertNotIn("continue-on-error:", source)
            self.assertIn(JOBS[workflow], source)
        required = (root / ".github/workflows/services-ci.yml").read_text().split("  all-green:", 1)[1]
        self.assertIn("check_library_quality_evidence.py", required)
        self.assertIn("needs.changes.outputs.admission == 'true'", required)
        self.assertIn("github.event.pull_request.head.sha", required)

    def test_scope(self):
        for path in ("openbank-libs-finance/src/main/X.kt", "fuzz/ossfuzz/build.gradle.kts",
                     ".clusterfuzzlite/Dockerfile", *WORKFLOWS):
            self.assertTrue(applicable([path]), path)
        self.assertFalse(applicable(["docs/README.md", "openbank-account-service/src/main/X.kt", "openbank-libs-notes.md"]))


if __name__ == "__main__":
    unittest.main()
