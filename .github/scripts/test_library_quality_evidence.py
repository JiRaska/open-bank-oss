#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import copy
import io
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

import check_library_quality_evidence as checker
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
        self.assertIn("test-check-service-build-results.py ResultsTest", required)
        self.assertIn("run: python3 .github/scripts/check-service-build-results.py", required)

    def test_scope(self):
        for path in ("openbank-libs-finance/src/main/X.kt", "fuzz/ossfuzz/build.gradle.kts",
                     ".clusterfuzzlite/Dockerfile", *WORKFLOWS):
            self.assertTrue(applicable([path]), path)
        self.assertFalse(applicable(["docs/README.md", "openbank-account-service/src/main/X.kt", "openbank-libs-notes.md"]))


class PollingEvidenceTest(unittest.TestCase):
    runs = EvidenceTest.runs
    def poll(self, frames, *, timeout=900, job_overrides=None):
        now = [0]
        reads = []
        polls = [0]

        def api(endpoint):
            reads.append(endpoint)
            if '/pulls/' in endpoint:
                return [[{'filename': 'openbank-libs/src/main/Fixture.kt'}]]
            if '/actions/runs?' in endpoint:
                frame = frames[min(polls[0], len(frames) - 1)]
                polls[0] += 1
                # Every read is a fresh API response, not a reused mutable fixture.
                return [{'workflow_runs': copy.deepcopy(frame)}]
            if '/jobs?' in endpoint:
                if job_overrides and endpoint in job_overrides:
                    return [{'jobs': copy.deepcopy(job_overrides[endpoint])}]
                run_id = int(endpoint.split('/runs/')[1].split('/')[0])
                workflow = WORKFLOWS[run_id % 10]
                return [{'jobs': [{'name': JOBS[workflow], 'status': 'completed', 'conclusion': 'success'}]}]
            raise AssertionError(endpoint)

        with patch.object(checker, 'api', side_effect=api), patch.object(checker.time, 'monotonic', side_effect=lambda: now[0]), \
                patch.object(checker.time, 'sleep', side_effect=lambda seconds: now.__setitem__(0, now[0] + seconds)), \
                patch('sys.argv', ['checker', '--repo', 'owner/repo', '--pr', '1', '--head', 'head', '--timeout', str(timeout)]), \
                redirect_stdout(io.StringIO()):
            result = checker.main()
        return result, reads

    def frame(self):
        return [{k: v for k, v in run.items() if k != 'jobs'} for run in self.runs()]

    def test_terminal_proofs_are_read_once_while_another_producer_waits(self):
        frame = self.frame()
        frame[2]['status'] = 'in_progress'
        result, reads = self.poll([frame])
        self.assertEqual(result, 1)
        self.assertEqual(sum('/actions/runs?' in p for p in reads), 31)
        self.assertEqual(sum('/jobs?' in p for p in reads), 2)

    def test_running_producer_is_not_cached_and_is_read_when_completed(self):
        first = self.frame()
        first[0]['status'] = 'in_progress'
        result, reads = self.poll([first, self.frame()])
        self.assertEqual(result, 0)
        self.assertEqual(sum('/jobs?' in p for p in reads), 3)

    def test_new_attempt_requires_new_jobs_and_missing_proof_fails(self):
        first = self.frame()
        first[2]['status'] = 'in_progress'
        second = self.frame()
        second[0]['run_attempt'] = 2
        endpoint = 'repos/owner/repo/actions/runs/0/attempts/2/jobs?per_page=100'
        result, reads = self.poll([first, second], job_overrides={endpoint: []})
        self.assertEqual(result, 1)
        self.assertIn(endpoint, reads)

    def test_newer_failed_or_running_attempt_cannot_use_cached_success(self):
        for status, conclusion in [('completed', 'failure'), ('in_progress', None)]:
            with self.subTest(status=status):
                first = self.frame()
                first[2]['status'] = 'in_progress'
                second = self.frame()
                second[0].update(run_attempt=2, status=status, conclusion=conclusion)
                result, _reads = self.poll([first, second], timeout=30)
                self.assertEqual(result, 1)

    def test_changed_head_event_or_workflow_cannot_reuse_cached_proof(self):
        for field, value in [('head_sha', 'other'), ('event', 'workflow_dispatch'), ('path', 'untrusted.yml')]:
            with self.subTest(field=field):
                first = self.frame()
                first[2]['status'] = 'in_progress'
                second = self.frame()
                second[0][field] = value
                result, _reads = self.poll([first, second], timeout=30)
                self.assertEqual(result, 1)

    def test_new_run_id_requires_fresh_jobs(self):
        first = self.frame()
        first[2]['status'] = 'in_progress'
        second = self.frame()
        second[0]['id'] = 10
        endpoint = 'repos/owner/repo/actions/runs/10/attempts/1/jobs?per_page=100'
        result, reads = self.poll([first, second], job_overrides={endpoint: [
            {'name': JOBS[WORKFLOWS[0]], 'status': 'completed', 'conclusion': 'skipped'}]})
        self.assertEqual(result, 1)
        self.assertIn(endpoint, reads)


if __name__ == "__main__":
    unittest.main()
