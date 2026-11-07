#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Offline tests for the bounded Admin UI delivery timing report."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("admin-ui-delivery-baseline.py")
spec = importlib.util.spec_from_file_location("admin_ui_delivery_baseline", SCRIPT)
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


def run(run_id, created, latest=1):
    return {
        "id": run_id,
        "run_attempt": latest,
        "created_at": f"2026-10-{created:02d}T00:00:00Z",
        "html_url": f"https://github.com/example/repo/actions/runs/{run_id}",
        "head_sha": f"{run_id:040x}",
        "event": "push",
    }


def build_jobs(started, completed, conclusion="success", *, source=True, coalesce=False):
    day = started[:10]
    build_job_started = f"{day}T00:00:25Z"
    jobs = [{
        "name": baseline.BUILD_JOB,
        "started_at": build_job_started,
        "conclusion": conclusion,
        "steps": [{"name": baseline.BUILD_STEP, "started_at": started,
                   "completed_at": completed, "conclusion": conclusion}],
    }]
    if source:
        jobs.append({
            "name": baseline.SOURCE_JOB,
            "started_at": f"{day}T00:00:00Z",
            "completed_at": f"{day}T00:00:20Z",
            "steps": [
                {"name": baseline.COALESCE_STEP,
                 "started_at": f"{day}T00:00:00Z" if coalesce else None,
                 "completed_at": f"{day}T00:00:10Z" if coalesce else None},
                {"name": baseline.VALIDATION_STEP,
                 "started_at": f"{day}T00:00:10Z",
                 "completed_at": f"{day}T00:00:20Z"},
            ],
        })
    return jobs


def fixture_for(rows):
    runs = {row[0]["id"]: row[0] for row in rows}
    return {"workflow_runs": list(runs.values()),
            "attempt_jobs": {f"{row[0]['id']}:{row[1]}": {"jobs": row[2]} for row in rows},
            "attempts": {f"{row[0]['id']}:{row[1]}": {"run_attempt": row[1],
                         "run_started_at": "2026-10-18T00:00:00Z"} for row in rows}}


class AdminUiDeliveryBaselineTest(unittest.TestCase):
    def test_skipped_attempt_does_not_enter_sample_or_denominator(self):
        skipped = [{"name": baseline.BUILD_JOB, "conclusion": "skipped", "steps": [
            {"name": baseline.BUILD_STEP, "started_at": None, "completed_at": None}]}]
        actual = build_jobs("2026-10-18T00:00:30Z", "2026-10-18T00:01:30Z")
        data = fixture_for([(run(2, 18), 1, skipped), (run(1, 17), 1, actual)])

        report = baseline.analyze_fixture(data, target=1, max_attempts=10)

        self.assertEqual(report["sample_actual_build_steps_started"], 1)
        self.assertEqual(report["excluded_attempts_without_started_build_step"]["skipped_or_not_started"], 1)
        self.assertEqual(report["outcomes_among_actual_build_steps_started"]["success"], 1)

    def test_failed_started_step_is_an_actual_build_sample(self):
        jobs = build_jobs("2026-10-18T00:00:30Z", "2026-10-18T00:01:30Z", "failure")
        report = baseline.analyze_fixture(fixture_for([(run(3, 18), 1, jobs)]),
                                          target=1, max_attempts=10)

        self.assertEqual(report["sample_actual_build_steps_started"], 1)
        self.assertEqual(report["outcomes_among_actual_build_steps_started"]["failure"], 1)
        self.assertEqual(report["metrics"]["build_collect_push_and_attest_step"]["p50_seconds"], 60.0)

    def test_distinct_retry_attempts_keep_run_id_and_attempt_number(self):
        attempts = run(4, 17, latest=2)
        first = build_jobs("2026-10-17T00:00:30Z", "2026-10-17T00:01:30Z", "failure")
        retry = build_jobs("2026-10-18T00:00:30Z", "2026-10-18T00:02:30Z", "success")
        data = fixture_for([(attempts, 1, first), (attempts, 2, retry)])
        data["attempts"]["4:1"] = {"run_attempt": 1, "run_started_at": "2026-10-17T00:00:00Z"}
        data["attempts"]["4:2"] = {"run_attempt": 2, "run_started_at": "2026-10-18T00:00:00Z"}

        report = baseline.analyze_fixture(data, target=2, max_attempts=10)

        self.assertEqual([(row["run_id"], row["attempt"]) for row in report["rows"]], [(4, 2), (4, 1)])
        self.assertEqual(report["outcomes_among_actual_build_steps_started"]["failure"], 1)
        self.assertEqual(report["metrics"]["workflow_created_to_source_job_started_proxy"]["n"], 1)
        self.assertEqual(report["metrics"]["validation_completed_to_build_job_started_handoff_proxy"]["n"], 2)

    def test_nearest_rank_percentiles_and_per_metric_sample_count(self):
        values = [10.0, 20.0, 30.0, 40.0]
        self.assertEqual(baseline.percentile(values, 0.50), 20.0)
        self.assertEqual(baseline.percentile(values, 0.95), 40.0)
        self.assertIsNone(baseline.percentile([], 0.50))

    def test_empty_history_reports_incomplete_sample_and_unavailable_phases(self):
        report = baseline.analyze_fixture({"workflow_runs": [], "attempt_jobs": {}},
                                          target=2, max_attempts=10)

        self.assertFalse(report["sample_complete"])
        self.assertEqual(report["sample_actual_build_steps_started"], 0)
        self.assertEqual(report["gitops_review"]["status"], "unavailable")
        self.assertEqual(report["rollout"]["status"], "unavailable")
        self.assertIsNone(report["metrics"]["build_collect_push_and_attest_step"]["p95_seconds"])

    def test_cli_reads_offline_fixture(self):
        data = fixture_for([(run(9, 18), 1, build_jobs(
            "2026-10-18T00:00:30Z", "2026-10-18T00:01:30Z"))])
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "fixture.json"
            output_path = Path(directory) / "report.json"
            input_path.write_text(json.dumps(data), encoding="utf-8")
            result = baseline.main(["--input", str(input_path), "--target", "1",
                                    "--output", str(output_path)])
            self.assertEqual(result, 0)
            self.assertEqual(json.loads(output_path.read_text())["sample_actual_build_steps_started"], 1)


if __name__ == "__main__":
    unittest.main()
