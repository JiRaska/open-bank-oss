#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import copy
import io
import json
import subprocess
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import check_library_quality_evidence as checker
from check_library_quality_evidence import (
    JOBS,
    PROOF_PATHS,
    WORKFLOWS,
    applicable,
    verdict,
)


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

        def api(endpoint, **_kwargs):
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


class TransportEvidenceTest(unittest.TestCase):
    """Exercise the actual gh framing/retry path; no API mock, network or real sleep."""

    @staticmethod
    def response(status, body, headers=()):
        return f"HTTP/2.0 {status} fixture\n" + "".join(f"{k}: {v}\n" for k, v in headers) + "\n" + json.dumps(body)

    def quota(self, now, *, status=403, secondary=False, headers=None, message=None):
        if headers is None:
            headers = (("Retry-After", "2"),) if secondary else (
                ("X-RateLimit-Remaining", "0"), ("X-RateLimit-Reset", str(int(1000 + now + 5))))
        message = message or ("You have exceeded a secondary rate limit." if secondary
                              else "API rate limit exceeded for installation.")
        return subprocess.CompletedProcess([], 1, self.response(status, {"message": message}, headers), "quota")

    def poll(self, *, faults=None, frames=None, timeout=60, verdict_cost=0, paths=None):
        now = [0.0]
        reads, sleeps, per_endpoint = [], [], {}
        frames = frames or [PollingEvidenceTest().frame()]
        polls = [0]
        original_verdict = checker.verdict

        def command(args, **kwargs):
            self.assertEqual(args[:4], ["gh", "api", "--include", "--paginate"])
            self.assertEqual(kwargs["check"], False)
            self.assertGreater(kwargs["timeout"], 0)
            self.assertLessEqual(kwargs["timeout"], min(30, timeout - now[0]))
            endpoint = args[-1]
            per_endpoint[endpoint] = per_endpoint.get(endpoint, 0) + 1
            reads.append((endpoint, now[0], kwargs["timeout"]))
            if faults:
                special = faults(endpoint, per_endpoint[endpoint], now)
                if special is not None:
                    return special
            if '/pulls/' in endpoint:
                data = paths if paths is not None else [{'filename': 'openbank-libs-runtime/src/main/Fixture.kt'}]
            elif '/actions/runs?' in endpoint:
                data = {'workflow_runs': copy.deepcopy(frames[min(polls[0], len(frames) - 1)])}
                polls[0] += 1
            elif '/jobs?' in endpoint:
                run_id = int(endpoint.split('/runs/')[1].split('/')[0])
                data = {'jobs': [{'name': JOBS[WORKFLOWS[run_id % 10]],
                                  'status': 'completed', 'conclusion': 'success'}]}
            else:
                raise AssertionError(endpoint)
            return subprocess.CompletedProcess(args, 0, self.response(200, data), '')

        def sleep(seconds):
            sleeps.append(seconds)
            now[0] += seconds

        def verdict(runs, head):
            result = original_verdict(runs, head)
            now[0] += verdict_cost
            return result

        output = io.StringIO()
        with patch.object(checker.subprocess, 'run', side_effect=command), \
                patch.object(checker.time, 'monotonic', side_effect=lambda: now[0]), \
                patch.object(checker.time, 'time', side_effect=lambda: 1000 + now[0]), \
                patch.object(checker.time, 'sleep', side_effect=sleep), \
                patch.object(checker, 'verdict', side_effect=verdict), \
                patch('sys.argv', ['checker', '--repo', 'owner/repo', '--pr', '1', '--head', 'head',
                                   '--timeout', str(timeout)]), redirect_stdout(output):
            result = checker.main()
        return result, reads, sleeps, output.getvalue()

    def test_primary_recovery_reselects_after_each_roster_including_last(self):
        for index in range(3):
            with self.subTest(roster=index):
                def faults(endpoint, count, now, index=index):
                    if f'/runs/{index}/attempts/1/jobs?' in endpoint and count == 1:
                        return self.quota(now[0])
                    return None
                rc, reads, sleeps, _out = self.poll(faults=faults)
                self.assertEqual(rc, 0)
                self.assertEqual(sleeps, [6])
                self.assertEqual(sum('/actions/runs?' in e for e, *_ in reads), 2)
                self.assertEqual(sum('/jobs?' in e for e, *_ in reads), 4)
                first, retry = [t for e, t, _ in reads if f'/runs/{index}/attempts/1/jobs?' in e]
                self.assertEqual(retry - first, 6)

    def test_secondary_and_both_wait_signals_use_the_later_bound(self):
        cases = [(self.quota(0, status=429, secondary=True), 2),
                 (self.quota(0, headers=(('X-RateLimit-Remaining', '0'), ('X-RateLimit-Reset', '1005'),
                                        ('Retry-After', '9'))), 9)]
        for response, expected in cases:
            with self.subTest(wait=expected):
                rc, _reads, sleeps, _out = self.poll(
                    faults=lambda e, n, _now, response=response: response if '/jobs?' in e and '/runs/2/' in e and n == 1 else None)
                self.assertEqual(rc, 0)
                self.assertEqual(sleeps, [expected])

    def test_permission_unknown_and_invalid_quota_never_retry(self):
        responses = [
            self.quota(0, message='Resource not accessible by integration'),
            self.quota(0, message='Unknown error'),
            self.quota(0, status=429, message='Unrelated error'),
            self.quota(0, headers=()),
            self.quota(0, headers=(('X-RateLimit-Remaining', '1'), ('X-RateLimit-Reset', '1005'))),
            self.quota(0, headers=(('X-RateLimit-Remaining', '0'), ('X-RateLimit-Reset', 'invalid'))),
            self.quota(0, headers=(('X-RateLimit-Remaining', '0'), ('X-RateLimit-Reset', '1000'))),
            self.quota(0, headers=(('X-RateLimit-Remaining', '0'), ('X-RateLimit-Reset', '999'))),
            self.quota(0, secondary=True, headers=()),
            self.quota(0, secondary=True, headers=(('Retry-After', '0'),)),
            self.quota(0, secondary=True, headers=(('Retry-After', '-1'),)),
            self.quota(0, secondary=True, headers=(('Retry-After', '1.2'),)),
            self.quota(0, secondary=True, headers=(('Retry-After', 'Thu, 01 Jan 1970 00:20:00 GMT'),)),
            self.quota(0, headers=(('X-RateLimit-Remaining', '0'), ('X-RateLimit-Reset', '1005'),
                                   ('x-ratelimit-reset', '1006'))),
        ]
        for status in (401, 404, 422, 500):
            responses.append(self.quota(0, status=status))
        for response in responses:
            with self.subTest(response=response.stdout):
                rc, reads, sleeps, output = self.poll(faults=lambda *_, response=response: response)
                self.assertEqual(rc, 1)
                self.assertEqual(len(reads), 1)
                self.assertEqual(sleeps, [])
                self.assertIn('NOT_EVALUATED', output)

    def test_retry_attempts_and_total_deadline_are_bounded(self):
        rc, reads, sleeps, _out = self.poll(faults=lambda _e, _n, now: self.quota(now[0], secondary=True))
        self.assertEqual((rc, len(reads), sleeps), (1, 3, [2, 2]))
        rc, reads, sleeps, _out = self.poll(faults=lambda *_: self.quota(0), timeout=6)
        self.assertEqual((rc, len(reads), sleeps), (1, 1, []))
        rc, reads, sleeps, _out = self.poll(
            faults=lambda _e, n, now: self.quota(now[0]) if n == 1 else None, timeout=10)
        self.assertEqual((rc, len(reads), sleeps), (1, 3, [6]))
        self.assertEqual(reads[-1][2], 4)

    def test_command_timeout_and_response_after_deadline_are_not_passes(self):
        def timeout(args_endpoint, _count, _now):
            raise subprocess.TimeoutExpired(['gh', 'api', args_endpoint], 30)
        rc, reads, sleeps, _out = self.poll(faults=timeout)
        self.assertEqual((rc, len(reads), sleeps), (1, 1, []))
        def late(_endpoint, _count, now):
            now[0] = 61
            return subprocess.CompletedProcess([], 0, self.response(200, []), '')
        rc, reads, sleeps, _out = self.poll(faults=late)
        self.assertEqual((rc, len(reads), sleeps), (1, 1, []))
        rc, _reads, _sleeps, _out = self.poll(verdict_cost=61)
        self.assertEqual(rc, 1)

    def test_partial_pages_nonzero_status_and_malformed_framing_fail_closed(self):
        successful_page = self.response(200, [{'filename': 'docs/README.md'}])
        responses = [subprocess.CompletedProcess([], 1, successful_page, 'transport ended'),
                     subprocess.CompletedProcess([], 0, successful_page + '\ntruncated', ''),
                     subprocess.CompletedProcess([], 1, 'HTTP/2.0 403 Forbidden\n\n{bad', ''),
                     subprocess.CompletedProcess([], 0, '[]', ''),
                     subprocess.CompletedProcess([], 0, '', ''),
                     subprocess.CompletedProcess([], 0, self.quota(0).stdout, '')]
        for response in responses:
            with self.subTest(response=response):
                rc, reads, sleeps, _out = self.poll(faults=lambda *_, response=response: response)
                self.assertEqual((rc, len(reads), sleeps), (1, 1, []))

    def test_partial_attempt_is_discarded_and_complete_retry_pages_are_used(self):
        observed = []
        first = self.response(200, {'jobs': [{'id': 'discarded'}]}) + '\n' + self.quota(0).stdout
        second = self.response(200, {'jobs': [{'id': 'new-1', 'name': 'HTTP/2.0 403\\n\\ninside JSON'}]})
        second += '\n' + self.response(200, {'jobs': [{'id': 'new-2'}]})
        now = [0.0]
        def command(_args, **kwargs):
            observed.append(kwargs['timeout'])
            return subprocess.CompletedProcess([], 1 if len(observed) == 1 else 0,
                                               first if len(observed) == 1 else second, '')
        with patch.object(checker.subprocess, 'run', side_effect=command), \
                patch.object(checker.time, 'monotonic', side_effect=lambda: now[0]), \
                patch.object(checker.time, 'time', side_effect=lambda: 1000 + now[0]), \
                patch.object(checker.time, 'sleep', side_effect=lambda s: now.__setitem__(0, now[0] + s)):
            budget = checker.ReadBudget(20)
            pages = checker.api('repos/o/r/actions/runs/1/attempts/1/jobs', budget=budget)
        self.assertEqual([job['id'] for page in pages for job in page['jobs']], ['new-1', 'new-2'])
        self.assertEqual(budget.recoveries, 1)
        self.assertEqual(observed, [20, 14])

    def test_recovery_cannot_reuse_a_superseded_attempt_or_run(self):
        for change in [{'run_attempt': 2, 'status': 'in_progress', 'conclusion': None},
                       {'run_attempt': 2, 'conclusion': 'failure'}, {'run_attempt': 2, 'conclusion': 'skipped'},
                       {'id': 12, 'conclusion': 'failure'}, {'head_sha': 'old'},
                       {'event': 'workflow_dispatch'}, {'path': 'wrong.yml'}]:
            with self.subTest(change=change):
                first = PollingEvidenceTest().frame()
                second = copy.deepcopy(first)
                second[2].update(change)
                def faults(endpoint, count, now):
                    if '/runs/2/attempts/1/jobs?' in endpoint and count == 1:
                        return self.quota(now[0])
                    return None
                rc, reads, _sleeps, _out = self.poll(faults=faults, frames=[first, second], timeout=10)
                self.assertEqual(rc, 1)
                self.assertGreaterEqual(sum('/actions/runs?' in e for e, *_ in reads), 2)

    def test_recovered_inventory_and_missing_new_attempt_roster_are_rechecked(self):
        first = PollingEvidenceTest().frame()
        second = copy.deepcopy(first)
        second[2]['run_attempt'] = 2
        def faults(endpoint, count, now):
            if '/actions/runs?' in endpoint and count == 1:
                return self.quota(now[0])
            if '/runs/2/attempts/2/jobs?' in endpoint:
                return subprocess.CompletedProcess([], 0, self.response(200, {'jobs': []}), '')
            return None
        rc, reads, _sleeps, _out = self.poll(faults=faults, frames=[first, second], timeout=10)
        self.assertEqual(rc, 1)
        self.assertTrue(any('/runs/2/attempts/2/jobs?' in e for e, *_ in reads))

    def test_normal_success_and_unrelated_scope_do_not_add_reads(self):
        rc, reads, sleeps, _out = self.poll()
        self.assertEqual((rc, len(reads), sleeps), (0, 5, []))
        rc, reads, sleeps, _out = self.poll(paths=[{'filename': 'docs/README.md'}])
        self.assertEqual((rc, len(reads), sleeps), (0, 1, []))


if __name__ == "__main__":
    unittest.main()
