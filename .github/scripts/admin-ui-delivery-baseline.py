#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Report bounded GitHub Actions timing evidence for Admin UI image builds.

This is deliberately a report, not a release or deployment control. It uses GitHub's
recorded job/step timestamps and labels combined intervals where the workflow does
not expose a finer boundary. `--input` accepts an offline fixture in the documented
shape emitted by the tests.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path
from typing import Any

WORKFLOW = "admin-ui-deploy.yml"
BUILD_JOB = "Build + push admin-ui"
BUILD_STEP = "Build, collect, push"
SOURCE_JOB = "Authorize deploy source"
COALESCE_STEP = "Coalesce evidence-only merge bursts"
VALIDATION_STEP = "Require exact-source Admin UI validation"
MAX_PAGE_SIZE = 100
MAX_ATTEMPT_PAGES = 10
API_WORKERS = 8
SCAN_BATCH_SIZE = 32


class ReportError(RuntimeError):
    """Input or GitHub API data cannot support a trustworthy report."""


def parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def seconds_between(start: Any, end: Any) -> float | None:
    before, after = parse_time(start), parse_time(end)
    if before is None or after is None or after < before:
        return None
    return round((after - before).total_seconds(), 3)


def step_named(job: dict[str, Any] | None, name: str) -> dict[str, Any] | None:
    if not job:
        return None
    steps = job.get("steps")
    if not isinstance(steps, list):
        return None
    matches = [step for step in steps if isinstance(step, dict) and step.get("name") == name]
    return matches[0] if len(matches) == 1 else None


def job_named(jobs: list[dict[str, Any]], name: str) -> dict[str, Any] | None:
    matches = [job for job in jobs if job.get("name") == name]
    return matches[0] if len(matches) == 1 else None


def attempt_row(run: dict[str, Any], attempt: dict[str, Any], jobs: list[dict[str, Any]]) -> tuple[dict[str, Any] | None, str]:
    build_job = job_named(jobs, BUILD_JOB)
    build_step = step_named(build_job, BUILD_STEP)
    if not build_step or not build_step.get("started_at"):
        return None, "no_build_step_started"

    source_job = job_named(jobs, SOURCE_JOB)
    coalesce = step_named(source_job, COALESCE_STEP)
    validation = step_named(source_job, VALIDATION_STEP)
    build_start = build_step.get("started_at")
    build_end = build_step.get("completed_at")
    workflow_created = attempt.get("created_at") or run.get("created_at")
    run_started = attempt.get("run_started_at")

    first_attempt = int(attempt.get("run_attempt", run.get("run_attempt", 1))) == 1
    row = {
        "run_id": run.get("id"),
        "attempt": int(attempt.get("run_attempt", run.get("run_attempt", 1))),
        "url": attempt.get("html_url") or run.get("html_url"),
        "source_sha": attempt.get("head_sha") or run.get("head_sha"),
        "event": attempt.get("event") or run.get("event"),
        "conclusion": attempt.get("conclusion") or build_job.get("conclusion"),
        "timestamps": {
            "workflow_created_at": workflow_created,
            "attempt_run_started_at": run_started,
            "source_job_started_at": source_job.get("started_at") if source_job else None,
            "coalesce_started_at": coalesce.get("started_at") if coalesce else None,
            "coalesce_completed_at": coalesce.get("completed_at") if coalesce else None,
            "validation_started_at": validation.get("started_at") if validation else None,
            "validation_completed_at": validation.get("completed_at") if validation else None,
            "build_job_started_at": build_job.get("started_at") if build_job else None,
            "build_step_started_at": build_start,
            "build_step_completed_at": build_end,
        },
        "durations_seconds": {
            # GitHub exposes one run-level created_at; this admission proxy is only
            # valid for attempt 1 and includes both GitHub and concurrency waiting.
            "workflow_created_to_source_job_started_proxy": (
                seconds_between(workflow_created, source_job.get("started_at"))
                if first_attempt and source_job else None
            ),
            "validation_completed_to_build_job_started_handoff_proxy": seconds_between(
                validation.get("completed_at"), build_job.get("started_at")
            ) if validation and build_job else None,
            "coalesce_step": seconds_between(
                coalesce.get("started_at"), coalesce.get("completed_at")
            ) if coalesce else None,
            "exact_source_validation_step": seconds_between(
                validation.get("started_at"), validation.get("completed_at")
            ) if validation else None,
            "build_job_to_build_step_start": seconds_between(
                build_job.get("started_at"), build_start
            ) if build_job else None,
            # This GitHub step invokes collection, BuildKit build/push, signing and
            # attestation in one shell step; its duration is intentionally combined.
            "build_collect_push_and_attest_step": seconds_between(build_start, build_end),
        },
        "unavailable": {
            "gitops_review": "not correlated by this reporter",
            "rollout": "no post-sync timestamp is recorded in this workflow",
            "build_vs_push_vs_attestation": "one workflow step; no separate timestamps",
        },
    }
    return row, "build_step_started"


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    # Nearest-rank percentile, with ranks 1..n.
    index = max(0, math.ceil(fraction * len(ordered)) - 1)
    return ordered[index]


def summarize(rows: list[dict[str, Any]], exclusions: dict[str, int], *, target: int,
              scanned: int, source: str) -> dict[str, Any]:
    metric_names = (
        "workflow_created_to_source_job_started_proxy",
        "validation_completed_to_build_job_started_handoff_proxy",
        "coalesce_step",
        "exact_source_validation_step",
        "build_job_to_build_step_start",
        "build_collect_push_and_attest_step",
    )
    metrics = {}
    for name in metric_names:
        values = [row["durations_seconds"][name] for row in rows
                  if row["durations_seconds"].get(name) is not None]
        metrics[name] = {
            "n": len(values),
            "p50_seconds": percentile(values, 0.50),
            "p95_seconds": percentile(values, 0.95),
        }
    started = len(rows)
    return {
        "schema_version": 1,
        "workflow": WORKFLOW,
        "source": source,
        "sample_target_build_steps_started": target,
        "sample_actual_build_steps_started": started,
        "sample_complete": started >= target,
        "scanned_run_attempts": scanned,
        "excluded_attempts_without_started_build_step": exclusions,
        "outcomes_among_actual_build_steps_started": {
            "success": sum(row.get("conclusion") == "success" for row in rows),
            "failure": sum(row.get("conclusion") == "failure" for row in rows),
            "cancelled": sum(row.get("conclusion") == "cancelled" for row in rows),
            "other_or_incomplete": sum(row.get("conclusion") not in {"success", "failure", "cancelled"}
                                        for row in rows),
        },
        "metrics": metrics,
        "metric_notes": {
            "workflow_created_to_source_job_started_proxy": "run creation to source-job start, attempt 1 only; includes GitHub/concurrency admission and GitHub timestamp precision",
            "coalesce_step": "duration of the explicit evidence-burst coalescing step; skipped steps have no duration",
            "exact_source_validation_step": "duration of the exact-source validation step",
            "validation_completed_to_build_job_started_handoff_proxy": "validation step completion to privileged build-job start; scheduling handoff proxy, not pure runner queue time",
            "build_job_to_build_step_start": "build-job start to the combined build step start; includes setup and evidence preparation",
            "build_collect_push_and_attest_step": "one workflow step combines collection, BuildKit build/push, signing and attestation",
        },
        "gitops_review": {"status": "unavailable", "reason": "PR correlation omitted"},
        "rollout": {"status": "unavailable", "reason": "no post-sync timestamp recorded"},
        "rows": rows,
    }


def analyze_fixture(fixture: dict[str, Any], *, target: int, max_attempts: int) -> dict[str, Any]:
    runs = fixture.get("workflow_runs")
    jobs_by_attempt = fixture.get("attempt_jobs")
    if not isinstance(runs, list) or not isinstance(jobs_by_attempt, dict):
        raise ReportError("offline input requires workflow_runs[] and attempt_jobs{}")

    candidates: list[tuple[dict[str, Any], int]] = []
    for run in sorted((x for x in runs if isinstance(x, dict)),
                      key=lambda x: x.get("created_at", ""), reverse=True):
        run_id = run.get("id")
        try:
            latest = int(run.get("run_attempt", 1))
        except (TypeError, ValueError):
            latest = 1
        for attempt_number in range(max(1, latest), 0, -1):
            candidates.append((run, attempt_number))
    rows: list[dict[str, Any]] = []
    exclusions: dict[str, int] = {"skipped_or_not_started": 0, "unavailable_attempt_data": 0}
    scanned = 0
    for run, number in candidates[:max_attempts]:
        scanned += 1
        run_id = run.get("id")
        key = f"{run_id}:{number}"
        attempt = fixture.get("attempts", {}).get(key, {}) if isinstance(fixture.get("attempts", {}), dict) else {}
        jobs_entry = jobs_by_attempt.get(key)
        if not isinstance(jobs_entry, dict) or not isinstance(jobs_entry.get("jobs"), list):
            exclusions["unavailable_attempt_data"] += 1
            continue
        row, _status = attempt_row(run, {**attempt, "run_attempt": number}, jobs_entry["jobs"])
        if row is None:
            exclusions["skipped_or_not_started"] += 1
            continue
        rows.append(row)
        if len(rows) >= target:
            break
    rows.sort(key=lambda row: row["timestamps"].get("build_step_started_at") or "", reverse=True)
    return summarize(rows, exclusions, target=target, scanned=scanned, source="offline_fixture")


def gh_json(endpoint: str) -> dict[str, Any]:
    try:
        completed = subprocess.run(["gh", "api", endpoint], check=True, capture_output=True,
                                   text=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as error:
        raise ReportError(f"GitHub API request failed for {endpoint}: {error}") from error
    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise ReportError(f"GitHub API returned invalid JSON for {endpoint}") from error
    if not isinstance(payload, dict):
        raise ReportError(f"GitHub API returned a non-object for {endpoint}")
    return payload


def fetch_live(repo: str, *, target: int, max_runs: int, max_attempts: int) -> dict[str, Any]:
    if not repo or "/" not in repo:
        raise ReportError("--repo owner/name or GITHUB_REPOSITORY is required")
    runs: list[dict[str, Any]] = []
    pages = math.ceil(max_runs / MAX_PAGE_SIZE)
    for page in range(1, pages + 1):
        payload = gh_json(
            f"repos/{repo}/actions/workflows/{WORKFLOW}/runs?branch=main&per_page={MAX_PAGE_SIZE}&page={page}"
        )
        batch = payload.get("workflow_runs", [])
        if not isinstance(batch, list):
            raise ReportError("workflow run listing is malformed")
        runs.extend(x for x in batch if isinstance(x, dict))
        if len(batch) < MAX_PAGE_SIZE:
            break
    runs = runs[:max_runs]
    exclusions: dict[str, int] = {"skipped_or_not_started": 0, "unavailable_attempt_data": 0}
    rows: list[dict[str, Any]] = []
    scanned = 0
    candidates: list[tuple[dict[str, Any], int]] = []
    for run in sorted(runs, key=lambda x: (x.get("run_started_at") or x.get("created_at") or ""), reverse=True):
        try:
            latest = int(run.get("run_attempt", 1))
        except (TypeError, ValueError):
            latest = 1
        for attempt_number in range(max(1, latest), 0, -1):
            candidates.append((run, attempt_number))

    def fetch_attempt_data(run: dict[str, Any], number: int) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        run_id = run.get("id")
        if not isinstance(run_id, int):
            raise ReportError("workflow run has no numeric id")
        try:
            latest_number = int(run.get("run_attempt", 1))
        except (TypeError, ValueError):
            latest_number = 1
        # The run listing already carries metadata for the latest attempt. Fetch the
        # attempt resource only for older retries, avoiding a second request per run.
        attempt = run if number == latest_number else gh_json(
            f"repos/{repo}/actions/runs/{run_id}/attempts/{number}"
        )
        all_jobs: list[dict[str, Any]] = []
        for page in range(1, MAX_ATTEMPT_PAGES + 1):
            job_payload = gh_json(
                f"repos/{repo}/actions/runs/{run_id}/attempts/{number}/jobs?per_page={MAX_PAGE_SIZE}&page={page}"
            )
            batch = job_payload.get("jobs", [])
            if not isinstance(batch, list):
                raise ReportError(f"run {run_id} attempt {number} jobs payload is malformed")
            all_jobs.extend(x for x in batch if isinstance(x, dict))
            if len(batch) < MAX_PAGE_SIZE:
                break
        else:
            raise ReportError(f"run {run_id} attempt {number} exceeded {MAX_ATTEMPT_PAGES} job pages")
        return attempt, all_jobs

    limited_candidates = candidates[:max_attempts]
    # Query bounded batches concurrently: up to eight read-only API requests are in
    # flight, while preserving newest-first sample selection and a hard attempt cap.
    for offset in range(0, len(limited_candidates), SCAN_BATCH_SIZE):
        batch = limited_candidates[offset:offset + SCAN_BATCH_SIZE]
        fetched: dict[int, tuple[dict[str, Any], list[dict[str, Any]]]] = {}
        with ThreadPoolExecutor(max_workers=API_WORKERS) as pool:
            futures = {pool.submit(fetch_attempt_data, run, number): index
                       for index, (run, number) in enumerate(batch)}
            for future in as_completed(futures):
                fetched[futures[future]] = future.result()
        for index, (run, number) in enumerate(batch):
            scanned += 1
            attempt, jobs = fetched[index]
            row, _status = attempt_row(run, {**attempt, "run_attempt": number}, jobs)
            if row is None:
                exclusions["skipped_or_not_started"] += 1
                continue
            rows.append(row)
            if len(rows) >= target:
                break
        if len(rows) >= target:
            break
    rows.sort(key=lambda row: row["timestamps"].get("build_step_started_at") or "", reverse=True)
    return summarize(rows, exclusions, target=target, scanned=scanned, source=f"github:{repo}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"), help="GitHub owner/name")
    parser.add_argument("--target", type=int, default=30, help="actual started build steps to sample (default: 30)")
    parser.add_argument("--max-runs", type=int, default=200, help="maximum workflow runs to inspect (default: 200)")
    parser.add_argument("--max-attempts", type=int, default=400, help="maximum distinct run attempts to inspect")
    parser.add_argument("--input", type=Path, help="offline JSON fixture; bypass GitHub API")
    parser.add_argument("--output", type=Path, help="write report here; default stdout")
    args = parser.parse_args(argv)
    if not (1 <= args.target <= args.max_attempts):
        parser.error("--target must be between 1 and --max-attempts")
    if not (1 <= args.max_runs <= 1000):
        parser.error("--max-runs must be between 1 and 1000")
    if not (1 <= args.max_attempts <= 2000):
        parser.error("--max-attempts must be between 1 and 2000")
    try:
        if args.input:
            fixture = json.loads(args.input.read_text(encoding="utf-8"))
            if not isinstance(fixture, dict):
                raise ReportError("offline input root must be an object")
            report = analyze_fixture(fixture, target=args.target, max_attempts=args.max_attempts)
        else:
            report = fetch_live(args.repo or "", target=args.target, max_runs=args.max_runs,
                                max_attempts=args.max_attempts)
    except (OSError, json.JSONDecodeError, ReportError) as error:
        print(f"admin-ui-delivery-baseline: {error}", file=sys.stderr)
        return 1
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
