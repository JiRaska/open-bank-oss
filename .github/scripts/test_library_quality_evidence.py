#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import unittest
import json
import os
import subprocess
import tempfile
import time
from unittest.mock import patch, Mock
from pathlib import Path

from check_library_quality_evidence import JOBS, PROOF_PATHS, WORKFLOWS, applicable, verdict, api, attach_jobs


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
        self.assertIn("test-check-service-build-results.py ResultsTest", required)
        self.assertIn("run: python3 .github/scripts/check-service-build-results.py", required)

    def test_scope(self):
        for path in ("openbank-libs-finance/src/main/X.kt", "fuzz/ossfuzz/build.gradle.kts",
                     ".clusterfuzzlite/Dockerfile", *WORKFLOWS):
            self.assertTrue(applicable([path]), path)
        self.assertFalse(applicable(["docs/README.md", "openbank-account-service/src/main/X.kt", "openbank-libs-notes.md"]))


    def test_completed_proof_reads_are_cached_but_run_inventory_is_fresh(self):
        cache = {}
        with patch("check_library_quality_evidence.api", return_value=[{"jobs": []}]) as read:
            for _ in range(20):
                waiting = self.runs()
                waiting[2].update(status="in_progress", conclusion=None)
                attach_jobs(waiting, "head", "org/repo", cache, 100)
            self.assertEqual(read.call_count, 2)
            attach_jobs(self.runs(), "head", "org/repo", cache, 100)
            self.assertEqual(read.call_count, 3)
            rerun = self.runs()
            rerun[0]["run_attempt"] = 2
            attach_jobs(rerun, "head", "org/repo", cache, 100)
            self.assertEqual(read.call_count, 4)
            self.assertIn("/attempts/2/jobs", read.call_args.args[0])
            newer = self.runs()
            newer[0]["id"] = 100
            attach_jobs(newer, "head", "org/repo", cache, 100)
            self.assertEqual(read.call_count, 5)

    def test_cached_success_never_hides_later_attempt_failure_or_pending(self):
        cache = {}
        with patch("check_library_quality_evidence.api", side_effect=lambda path, deadline: [
                {"jobs": [dict(name=JOBS[WORKFLOWS[int(path.split('/runs/')[1].split('/')[0])]],
                               status="completed", conclusion="success")]}]):
            runs = self.runs()
            attach_jobs(runs, "head", "org/repo", cache, 100)
            self.assertEqual(verdict(runs, "head")[0], "success")
        for state, conclusion, expected in (("completed", "failure", "failure"),
                                            ("in_progress", None, "pending")):
            runs = self.runs()
            runs[0].update(run_attempt=2, status=state, conclusion=conclusion)
            attach_jobs(runs, "head", "org/repo", cache, 100)
            self.assertEqual(verdict(runs, "head")[0], expected)

    def test_unreadable_jobs_never_enter_cache(self):
        cache = {}
        with patch("check_library_quality_evidence.api", side_effect=RuntimeError("denied")):
            with self.assertRaises(RuntimeError):
                attach_jobs(self.runs(), "head", "org/repo", cache, 100)
        self.assertEqual(cache, {})


class ApiRetryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        stub = self.root / "gh"
        stub.write_text(r'''#!/bin/bash
if [[ "$2" == rate_limit ]]; then echo 0; exit 0; fi
n=$(cat "$STUB_COUNT" 2>/dev/null || echo 0)
n=$((n+1)); echo "$n" > "$STUB_COUNT"
if [[ "$STUB_MODE" == permission ]]; then echo 'Resource not accessible by integration (HTTP 403)' >&2; exit 1; fi
if [[ "$STUB_MODE" == exhausted || "$n" -eq 1 ]]; then
  echo '[{"partial":"must not survive"}]'
  echo 'API rate limit exceeded for installation (HTTP 403)' >&2
  exit 1
fi
if [[ "$STUB_MODE" == malformed ]]; then echo 'not-json'; exit 0; fi
echo '[{"jobs":[{"name":"proof","conclusion":"success"}]}]'
''')
        stub.chmod(0o755)
        sleeper = self.root / "sleep"
        sleeper.write_text("#!/bin/sh\nexit 0\n")
        sleeper.chmod(0o755)
        self.count = self.root / "count"
        env = patch.dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ["PATH"],
                         GH_RETRY_GH_BIN=str(stub), STUB_COUNT=str(self.count), STUB_MODE="recover")
        env.start()
        self.addCleanup(env.stop)

    def test_rate_limit_recovers_with_complete_paginated_output_only(self):
        self.assertEqual(api("repos/org/repo/jobs", time.monotonic() + 5),
                         [{"jobs": [{"name": "proof", "conclusion": "success"}]}])
        self.assertEqual(self.count.read_text().strip(), "2")

    def test_permission_denial_is_final_and_exhausted_quota_is_not_success(self):
        for mode, calls in (("permission", "1"), ("exhausted", "3")):
            self.count.unlink(missing_ok=True)
            with patch.dict(os.environ, STUB_MODE=mode):
                with self.assertRaises(RuntimeError):
                    api("repos/org/repo/jobs", time.monotonic() + 5)
            self.assertEqual(self.count.read_text().strip(), calls)

    def test_successful_transport_with_malformed_json_is_not_evidence(self):
        with patch.dict(os.environ, STUB_MODE="malformed"):
            with self.assertRaises(json.JSONDecodeError):
                api("repos/org/repo/jobs", time.monotonic() + 5)

    def test_expired_global_deadline_does_not_start_request(self):
        with patch("check_library_quality_evidence.subprocess.Popen") as start:
            with self.assertRaises(RuntimeError):
                api("repos/org/repo/jobs", time.monotonic() - 1)
            start.assert_not_called()

    def test_timeout_kills_wrapper_and_children_and_remains_failure(self):
        process = Mock(pid=12345)
        process.communicate.side_effect = [subprocess.TimeoutExpired("gh", 1), ("", "")]
        with patch("check_library_quality_evidence.subprocess.Popen", return_value=process), \
                patch("check_library_quality_evidence.os.killpg") as kill:
            with self.assertRaises(RuntimeError):
                api("repos/org/repo/jobs", time.monotonic() + 1)
            kill.assert_called_once()
            self.assertEqual(kill.call_args.args[0], 12345)


if __name__ == "__main__":
    unittest.main()
