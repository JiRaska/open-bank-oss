#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only, bounded CI timing sample. JSON or Markdown; no logs or actor metadata.

Offline fixture: workflow_runs[], attempts{"ID:N": attempt},
attempt_jobs{"ID:N": [job, ...]}. Only the latest attempt of each sampled run
is measured, using the exact attempt endpoint. Missing jobs remain unknown.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys

AGGREGATES = ("Admin UI", "Validate manifests", "all-green")
EVENTS = {"push", "pull_request", "pull_request_target", "workflow_dispatch", "schedule", "workflow_run", "merge_group"}
CONCLUSIONS = {"success", "failure", "cancelled", "timed_out", "action_required", "neutral", "skipped", "stale", "startup_failure"}
METRICS = ("queue_seconds", "execution_seconds", "setup_seconds", "nonsetup_seconds", "attempt_start_to_verdict_seconds")


class ReportError(ValueError):
    pass


def timestamp(value):
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed if parsed.tzinfo is not None else None
    except ValueError:
        return None


def duration(start, end):
    a, b = timestamp(start), timestamp(end)
    return (b - a).total_seconds() if a is not None and b is not None and b >= a else None


def positive_int(value):
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def setup_step(name):
    # Conservative heuristic; nonsetup includes cleanup and unrecognized setup.
    return isinstance(name, str) and (name == "Set up job" or bool(re.match(
        r"(?:Run )?actions/(?:checkout|setup-[a-z0-9-]+)@", name
    )) or bool(re.match(r"(?:Set up|Setup) (?:Java|JDK|Node|Python|Gradle|Temurin)(?:\b|$)", name, re.I)))


def job_row(job, attempt, aggregates):
    conclusion = job.get("conclusion")
    row = {"job_id": job.get("id") if positive_int(job.get("id")) else None,
           "category": "aggregate" if job.get("name") in aggregates else "work",
           "conclusion": conclusion if conclusion in CONCLUSIONS else "unknown",
           "unexecuted": conclusion == "skipped" or timestamp(job.get("started_at")) is None,
           "metrics": {}, "unknown": []}
    if row["unexecuted"]:
        row["reason"] = "skipped" if conclusion == "skipped" else "missing_start"
        return row
    values = row["metrics"]
    values["queue_seconds"] = duration(job.get("created_at"), job.get("started_at"))
    values["execution_seconds"] = duration(job.get("started_at"), job.get("completed_at"))
    values["setup_seconds"] = values["nonsetup_seconds"] = None
    steps = job.get("steps")
    if values["execution_seconds"] is not None and isinstance(steps, list) and steps:
        intervals, valid = [], True
        start, end = timestamp(job.get("started_at")), timestamp(job.get("completed_at"))
        for step in steps:
            if not isinstance(step, dict):
                valid = False
                break
            if not setup_step(step.get("name")) or step.get("conclusion") == "skipped":
                continue
            a, b = timestamp(step.get("started_at")), timestamp(step.get("completed_at"))
            if a is None or b is None or b < a or a < start or b > end:
                valid = False
                break
            intervals.append((a, b))
        if valid:
            merged = []
            for a, b in sorted(intervals):
                if merged and a <= merged[-1][1]:
                    merged[-1] = (merged[-1][0], max(b, merged[-1][1]))
                else:
                    merged.append((a, b))
            values["setup_seconds"] = sum((b - a).total_seconds() for a, b in merged)
            values["nonsetup_seconds"] = values["execution_seconds"] - values["setup_seconds"]
    if row["category"] == "aggregate":
        values["attempt_start_to_verdict_seconds"] = (
            duration(attempt.get("run_started_at"), job.get("completed_at"))
            if values["execution_seconds"] is not None
            and duration(attempt.get("run_started_at"), job.get("started_at")) is not None else None
        )
    row["unknown"] = [key for key, value in values.items() if value is None]
    return row


def summarize_values(values):
    known = sorted(value for value in values if value is not None)
    return {"n": len(known), "unknown": len(values) - len(known),
            "p50_seconds": statistics.median(known) if known else None,
            "p95_seconds": known[max(0, math.ceil(len(known) * .95) - 1)] if known else None}


def analyze(fixture, *, max_runs, aggregates=AGGREGATES):
    runs, attempts, jobs = fixture.get("workflow_runs"), fixture.get("attempts", {}), fixture.get("attempt_jobs")
    if not isinstance(runs, list) or not isinstance(attempts, dict) or not isinstance(jobs, dict):
        raise ReportError("fixture requires workflow_runs[], attempts{} and attempt_jobs{}")
    rows = []
    for run in runs[:max_runs]:
        if not isinstance(run, dict) or not positive_int(run.get("id")) or not positive_int(run.get("run_attempt")):
            raise ReportError("run requires positive numeric id and run_attempt")
        key = f'{run["id"]}:{run["run_attempt"]}'
        attempt = attempts.get(key, run)
        if not isinstance(attempt, dict) or attempt.get("id") != run["id"] or attempt.get("run_attempt") != run["run_attempt"]:
            raise ReportError("attempt identity does not match sampled run")
        batch = jobs.get(key)
        if batch is not None and (not isinstance(batch, list) or any(not isinstance(job, dict) for job in batch)):
            raise ReportError("attempt jobs must be an array of objects")
        if batch and any(job.get("run_attempt", run["run_attempt"]) != run["run_attempt"] for job in batch):
            raise ReportError("jobs contain a different attempt")
        event, conclusion = attempt.get("event", run.get("event")), attempt.get("conclusion")
        rows.append({"run_id": run["id"], "attempt": run["run_attempt"],
                     "event": event if event in EVENTS else "other",
                     "conclusion": conclusion if conclusion in CONCLUSIONS else "unknown",
                     "jobs_unavailable": batch is None,
                     "jobs": [job_row(job, attempt, aggregates) for job in batch or []]})
    events = {}
    for event in sorted({row["event"] for row in rows}):
        selected = [row for row in rows if row["event"] == event]
        all_jobs = [job for row in selected for job in row["jobs"]]
        executed = [job for job in all_jobs if not job["unexecuted"]]
        metrics = {name: summarize_values([job["metrics"].get(name) for job in executed
                   if name != "attempt_start_to_verdict_seconds" or job["category"] == "aggregate"])
                   for name in METRICS}
        events[event] = {"runs": len(selected), "jobs_unavailable_runs": sum(row["jobs_unavailable"] for row in selected),
                         "outcomes": dict(Counter(row["conclusion"] for row in selected)),
                         "executed_jobs": len(executed), "unexecuted_jobs": len(all_jobs) - len(executed),
                         "aggregate_jobs": sum(job["category"] == "aggregate" for job in executed), "metrics": metrics}
    return {"schema_version": 1, "sampled_runs": len(rows), "max_runs": max_runs,
            "sampling": "latest attempt of each sampled run; no success-only filter",
            "notes": ["Queue is job started_at minus created_at; updated_at is never a finish timestamp.",
                      "Verdict latency starts at run_started_at of the exact attempt, including reruns, not initial workflow creation.",
                      "Setup uses a conservative step-name allowlist; nonsetup includes cleanup and overhead, not just tests.",
                      "Distributions weight jobs equally; aggregate latency is per aggregate job, not the full required-check DAG.",
                      "Missing, malformed or reversed timestamps are unknown, never zero. Unexecuted jobs are excluded.",
                      "Only numeric identities and timing categories are retained; absent aggregates yield no verdict sample."],
            "events": events, "rows": rows}


def gh_json(endpoint):
    try:
        result = subprocess.run(["gh", "api", endpoint], check=True, capture_output=True, text=True, timeout=30)
        value = json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        raise ReportError("GitHub read failed; no partial report emitted") from error
    if not isinstance(value, dict):
        raise ReportError("GitHub returned a non-object")
    return value


def fetch_live(repo, workflow, *, max_runs, max_job_pages):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ReportError("repo must be owner/name")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", workflow):
        raise ReportError("workflow must be a filename or numeric ID")
    root, runs = f"repos/{repo}/actions", []
    for page in range(1, math.ceil(max_runs / 100) + 1):
        payload = gh_json(f"{root}/workflows/{workflow}/runs?per_page=100&page={page}")
        batch = payload.get("workflow_runs")
        if not isinstance(batch, list):
            raise ReportError("malformed workflow listing")
        runs.extend(batch[:max_runs - len(runs)])
        if len(runs) >= max_runs or len(batch) < 100:
            break
    fixture = {"workflow_runs": runs, "attempts": {}, "attempt_jobs": {}}
    for run in runs:
        if not isinstance(run, dict) or not positive_int(run.get("id")) or not positive_int(run.get("run_attempt")):
            raise ReportError("malformed run identity")
        rid, number = run["id"], run["run_attempt"]
        key, path = f"{rid}:{number}", f"{root}/runs/{rid}/attempts/{number}"
        fixture["attempts"][key] = gh_json(path)
        collected, seen = [], set()
        for page in range(1, max_job_pages + 1):
            payload = gh_json(f"{path}/jobs?per_page=100&page={page}")
            batch, total = payload.get("jobs"), payload.get("total_count")
            if not isinstance(batch, list) or not isinstance(total, int) or isinstance(total, bool) or total < 0:
                raise ReportError("malformed jobs page")
            for job in batch:
                if not isinstance(job, dict) or not positive_int(job.get("id")) or job["id"] in seen:
                    raise ReportError("malformed or duplicate job identity")
                seen.add(job["id"])
                collected.append(job)
            if len(collected) == total:
                break
            if len(collected) > total or len(batch) < 100:
                raise ReportError("incomplete or inconsistent jobs listing")
        else:
            raise ReportError("job page bound exceeded; refusing truncated report")
        fixture["attempt_jobs"][key] = collected
    return fixture


def markdown(report):
    lines = ["# CI latency sample", "", f'Sampled runs: {report["sampled_runs"]}. Latest attempt per run.', "",
             "| Event | Metric | Known | Unknown | p50 seconds | p95 seconds |",
             "| --- | --- | ---: | ---: | ---: | ---: |"]
    def cell(value):
        return "unknown" if value is None else f"{value:g}"
    for event, data in report["events"].items():
        for metric, stats in data["metrics"].items():
            lines.append(f'| {event} | {metric} | {stats["n"]} | {stats["unknown"]} | {cell(stats["p50_seconds"])} | {cell(stats["p95_seconds"])} |')
    lines += ["", "Counts and limitations:", ""]
    for event, data in report["events"].items():
        lines.append(f'- {event}: {data["runs"]} runs, {data["executed_jobs"]} executed jobs, {data["unexecuted_jobs"]} unexecuted, {data["jobs_unavailable_runs"]} runs missing jobs; outcomes: {json.dumps(data["outcomes"], sort_keys=True)}.')
    lines.extend(f"- {note}" for note in report["notes"])
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--workflow", default="ci.yml")
    parser.add_argument("--max-runs", type=int, default=20)
    parser.add_argument("--max-job-pages", type=int, default=10)
    parser.add_argument("--aggregate", action="append", help="exact aggregate job name; repeatable")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--format", choices=("json", "markdown"), default="json")
    args = parser.parse_args(argv)
    if not 1 <= args.max_runs <= 100 or not 1 <= args.max_job_pages <= 20:
        parser.error("max-runs must be 1..100 and max-job-pages 1..20")
    try:
        fixture = json.loads(args.input.read_text()) if args.input else fetch_live(
            args.repo, args.workflow, max_runs=args.max_runs, max_job_pages=args.max_job_pages)
        if not isinstance(fixture, dict):
            raise ReportError("fixture root must be an object")
        report = analyze(fixture, max_runs=args.max_runs, aggregates=tuple(args.aggregate or AGGREGATES))
        rendered = markdown(report) if args.format == "markdown" else json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        else:
            sys.stdout.write(rendered)
    except (OSError, ValueError) as error:
        print(f"ci-latency-report: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
