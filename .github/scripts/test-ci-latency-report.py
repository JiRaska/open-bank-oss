#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression tests for measured CI latency, pagination and unknown evidence."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("ci_latency", Path(__file__).with_name("ci-latency-report.py"))
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


def at(second, day=25):
    return f"2026-10-{day:02d}T12:{second // 60:02d}:{second % 60:02d}Z"


def run(rid=1, attempt=1, event="pull_request"):
    return {"id": rid, "run_attempt": attempt, "event": event, "conclusion": "success",
            "created_at": at(0, 24), "run_started_at": at(0), "updated_at": at(1000)}


def job(jid=1, name="Validate manifests", queue=10, execution=30):
    return {"id": jid, "name": name, "conclusion": "success", "created_at": at(0),
            "started_at": at(queue), "completed_at": at(queue + execution),
            "steps": [{"name": "Set up job", "conclusion": "success", "started_at": at(queue),
                       "completed_at": at(queue + 5)},
                      {"name": "Run checks", "conclusion": "success", "started_at": at(queue + 5),
                       "completed_at": at(queue + execution)}]}


def fixture(jobs=None, attempt=1):
    return {"workflow_runs": [run(attempt=attempt)], "attempt_jobs": {f"1:{attempt}": [job()] if jobs is None else jobs}}


class TimingTests(unittest.TestCase):
    def test_queue_execution_setup_and_verdict_are_distinct(self):
        row = REPORT.analyze(fixture(), max_runs=20)["rows"][0]["jobs"][0]
        self.assertEqual(row["metrics"], {"queue_seconds": 10, "execution_seconds": 30,
                         "setup_seconds": 5, "nonsetup_seconds": 25, "attempt_start_to_verdict_seconds": 40})

    def test_rerun_uses_exact_attempt_start_not_original_creation(self):
        data = fixture(attempt=2)
        data["workflow_runs"][0]["run_started_at"] = at(0, 24)
        data["attempts"] = {"1:2": run(attempt=2)}
        row = REPORT.analyze(data, max_runs=20)["rows"][0]
        self.assertEqual(row["attempt"], 2)
        self.assertEqual(row["jobs"][0]["metrics"]["attempt_start_to_verdict_seconds"], 40)

    def test_skipped_with_timestamps_and_unstarted_are_excluded(self):
        skipped, queued = job(), job(2)
        skipped["conclusion"] = "skipped"
        queued["started_at"] = None
        report = REPORT.analyze(fixture([skipped, queued]), max_runs=20)
        stats = report["events"]["pull_request"]
        self.assertEqual(stats["unexecuted_jobs"], 2)
        self.assertEqual(stats["metrics"]["execution_seconds"]["n"], 0)
        self.assertIsNone(stats["metrics"]["execution_seconds"]["p50_seconds"])

    def test_cancelled_started_work_is_kept_not_success_filtered(self):
        data = fixture()
        data["workflow_runs"][0]["conclusion"] = "cancelled"
        data["attempt_jobs"]["1:1"][0]["conclusion"] = "cancelled"
        report = REPORT.analyze(data, max_runs=20)
        self.assertEqual(report["events"]["pull_request"]["outcomes"], {"cancelled": 1})
        self.assertEqual(report["events"]["pull_request"]["metrics"]["execution_seconds"]["n"], 1)

    def test_missing_finish_never_uses_updated_at(self):
        data = fixture()
        data["attempt_jobs"]["1:1"][0]["completed_at"] = None
        data["attempt_jobs"]["1:1"][0]["updated_at"] = at(50)
        row = REPORT.analyze(data, max_runs=20)["rows"][0]["jobs"][0]
        self.assertIsNone(row["metrics"]["execution_seconds"])
        self.assertIsNone(row["metrics"]["attempt_start_to_verdict_seconds"])
        self.assertIn("execution_seconds", row["unknown"])

    def test_malformed_naive_and_negative_timestamps_are_unknown(self):
        for start, end in (("bad", at(10)), (at(10), "bad"), (at(20), at(10)),
                           ("2026-10-25T12:00:00", at(10)), (None, at(10)), ([], at(10))):
            with self.subTest(start=start, end=end):
                self.assertIsNone(REPORT.duration(start, end))
        self.assertEqual(REPORT.duration(at(10), at(10)), 0)
        data = fixture()
        data["attempt_jobs"]["1:1"][0]["created_at"] = at(20)
        data["attempt_jobs"]["1:1"][0]["completed_at"] = at(5)
        row = REPORT.analyze(data, max_runs=20)["rows"][0]["jobs"][0]
        self.assertIsNone(row["metrics"]["queue_seconds"])
        self.assertIsNone(row["metrics"]["execution_seconds"])
        self.assertIsNone(row["metrics"]["setup_seconds"])
        self.assertIsNone(row["metrics"]["attempt_start_to_verdict_seconds"])

    def test_missing_setup_timestamps_are_unknown_and_overlap_not_double_counted(self):
        data = fixture()
        steps = data["attempt_jobs"]["1:1"][0]["steps"]
        steps.append(copy.deepcopy(steps[0]))
        self.assertEqual(REPORT.analyze(data, max_runs=20)["rows"][0]["jobs"][0]["metrics"]["setup_seconds"], 5)
        steps[0]["completed_at"] = None
        self.assertIsNone(REPORT.analyze(data, max_runs=20)["rows"][0]["jobs"][0]["metrics"]["setup_seconds"])

    def test_event_samples_separate_and_percentiles_correct(self):
        data = fixture([job(1, queue=10), job(2, queue=20)])
        data["workflow_runs"].append(run(2, event="push"))
        data["attempt_jobs"]["2:1"] = [job(3, queue=100)]
        events = REPORT.analyze(data, max_runs=20)["events"]
        self.assertEqual(events["pull_request"]["metrics"]["queue_seconds"]["p50_seconds"], 15)
        self.assertEqual(events["pull_request"]["metrics"]["queue_seconds"]["p95_seconds"], 20)
        self.assertEqual(events["push"]["metrics"]["queue_seconds"]["p50_seconds"], 100)

    def test_unavailable_jobs_and_missing_aggregate_are_explicit(self):
        data = fixture([job(name="work")])
        data["workflow_runs"].append(run(2))
        stats = REPORT.analyze(data, max_runs=20)["events"]["pull_request"]
        self.assertEqual(stats["jobs_unavailable_runs"], 1)
        self.assertEqual(stats["aggregate_jobs"], 0)
        self.assertIsNone(stats["metrics"]["attempt_start_to_verdict_seconds"]["p50_seconds"])

    def test_identity_mismatch_is_not_merged_into_sample(self):
        data = fixture()
        data["attempts"] = {"1:1": run(attempt=2)}
        with self.assertRaises(REPORT.ReportError):
            REPORT.analyze(data, max_runs=20)
        data = fixture()
        data["attempt_jobs"]["1:1"][0]["run_attempt"] = 2
        with self.assertRaises(REPORT.ReportError):
            REPORT.analyze(data, max_runs=20)

    def test_no_arbitrary_metadata_reaches_output(self):
        data = fixture([job(name="SENSITIVE")])
        data["workflow_runs"][0].update(actor="SENSITIVE", head_branch="SENSITIVE", html_url="SENSITIVE")
        data["attempt_jobs"]["1:1"][0]["steps"][0]["name"] = "SENSITIVE"
        report = REPORT.analyze(data, max_runs=20)
        self.assertNotIn("SENSITIVE", json.dumps(report))
        self.assertNotIn("SENSITIVE", REPORT.markdown(report))

    def test_cli_offline_json_and_markdown(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "in.json", Path(directory) / "out.txt"
            source.write_text(json.dumps(fixture()))
            for fmt in ("json", "markdown"):
                with patch.object(REPORT, "gh_json", side_effect=AssertionError("offline must not read API")):
                    self.assertEqual(REPORT.main(["--input", str(source), "--output", str(output), "--format", fmt]), 0)
                self.assertIn("queue_seconds", output.read_text())
            source.write_text("[]")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(REPORT.main(["--input", str(source)]), 1)


class FetchTests(unittest.TestCase):
    def fake(self, endpoint):
        self.calls.append(endpoint)
        if "/workflows/" in endpoint:
            return {"workflow_runs": [run(attempt=2), run(2)]}
        if endpoint.endswith("/attempts/2"):
            return run(attempt=2)
        if endpoint.endswith("page=1"):
            return {"total_count": 101, "jobs": [job(n + 1) for n in range(100)]}
        if endpoint.endswith("page=2"):
            return {"total_count": 101, "jobs": [job(101)]}
        raise AssertionError(endpoint)

    def setUp(self):
        self.calls = []

    def test_all_pages_of_exact_attempt_and_run_bound(self):
        with patch.object(REPORT, "gh_json", side_effect=self.fake):
            data = REPORT.fetch_live("owner/repo", "ci.yml", max_runs=1, max_job_pages=2)
        self.assertEqual(len(data["attempt_jobs"]["1:2"]), 101)
        self.assertEqual(len(data["workflow_runs"]), 1)
        self.assertEqual(len(self.calls), 4)
        self.assertTrue(all("/attempts/2" in call for call in self.calls[1:]))
        self.assertTrue(self.calls[-1].endswith("page=2"))

    def test_page_limit_is_error_not_silent_partial_percentile(self):
        with patch.object(REPORT, "gh_json", side_effect=self.fake):
            with self.assertRaisesRegex(REPORT.ReportError, "bound exceeded"):
                REPORT.fetch_live("owner/repo", "ci.yml", max_runs=1, max_job_pages=1)

    def test_short_page_with_more_jobs_is_error(self):
        def fake(endpoint):
            if "/jobs?" in endpoint:
                return {"total_count": 2, "jobs": [job()]}
            return self.fake(endpoint)
        with patch.object(REPORT, "gh_json", side_effect=fake):
            with self.assertRaisesRegex(REPORT.ReportError, "incomplete"):
                REPORT.fetch_live("owner/repo", "ci.yml", max_runs=1, max_job_pages=2)

    def test_duplicate_job_across_pages_is_error(self):
        def fake(endpoint):
            if endpoint.endswith("page=2"):
                return {"total_count": 101, "jobs": [job(1)]}
            return self.fake(endpoint)
        with patch.object(REPORT, "gh_json", side_effect=fake):
            with self.assertRaisesRegex(REPORT.ReportError, "duplicate"):
                REPORT.fetch_live("owner/repo", "ci.yml", max_runs=1, max_job_pages=2)

    def test_api_failure_does_not_print_partial_sample_or_stderr_payload(self):
        with patch.object(REPORT.subprocess, "run", side_effect=REPORT.subprocess.CalledProcessError(1, "gh", stderr="SENSITIVE")):
            with self.assertRaises(REPORT.ReportError) as caught:
                REPORT.gh_json("repos/owner/repo/actions/runs")
        self.assertNotIn("SENSITIVE", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
