#!/usr/bin/env python3
"""Classify a genuinely stalled GitHub-hosted service-CI job (issue #7477).

This deliberately answers a narrower question than "is a workflow old?". A service CI
build can spend legitimate time uploading Kover, generating its envelope or publishing
artifacts. We cancel only two bounded shapes: the mandatory `Build + test` step itself,
or a `Post Run …` action that has exceeded the interval after every substantive step
already completed. The latter catches runner cleanup hangs without cancelling Kover,
envelope creation or an active test. A different job, malformed API data or a fresh step
is never an implicit cancellation candidate.

The workflow owns reading the GitHub Actions API and cancellation. Keeping this classifier
pure makes both the positive and the "do not cancel normal work" paths executable in CI.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


BUILD_PREFIX = "build (openbank-"
STEP_PREFIX = "Build + test (:openbank-"
VERIFICATION_JOB = "verification-metadata"
POST_RUN_PREFIX = "Post Run "


def parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)
    except ValueError:
        return None


def classify(payload: dict[str, Any], now: datetime, timeout_minutes: int) -> list[dict[str, str]]:
    jobs = payload.get("jobs")
    if not isinstance(jobs, list):
        raise ValueError("Actions jobs payload has no jobs list")
    stalled: list[dict[str, str]] = []
    for job in jobs:
        if not isinstance(job, dict):
            raise ValueError("Actions jobs payload contains a non-object job")
        name = job.get("name")
        if not isinstance(name, str) or (not name.startswith(BUILD_PREFIX) and name != VERIFICATION_JOB):
            continue
        if job.get("status") != "in_progress":
            continue
        job_started = parse_time(job.get("started_at"))
        if job_started is None:
            raise ValueError(f"{name} is in progress but has no valid started_at")
        steps = job.get("steps")
        if not isinstance(steps, list):
            raise ValueError(f"{name} is in progress but has no steps list")
        build_step = next((step for step in steps if isinstance(step, dict) and isinstance(step.get("name"), str)
                           and step["name"].startswith(STEP_PREFIX)), None)
        candidate_step = None
        candidate_started = job_started
        reason = "mandatory-build"
        if build_step is not None and build_step.get("status") == "in_progress":
            # The service build has reached its mandatory command and that command is still live.
            candidate_step = build_step
        else:
            # A post-action may be cancelled only after every meaningful job step has completed.
            # This deliberately excludes an in-progress Kover/envelope/upload step even on a
            # long-lived job, and requires the post-action's own clock rather than job age.
            substantive = [step for step in steps if isinstance(step, dict)
                           and isinstance(step.get("name"), str) and not step["name"].startswith(POST_RUN_PREFIX)]
            post_steps = [step for step in steps if isinstance(step, dict)
                          and isinstance(step.get("name"), str) and step["name"].startswith(POST_RUN_PREFIX)
                          and step.get("status") == "in_progress"]
            if not substantive or any(step.get("status") != "completed" for step in substantive) or not post_steps:
                continue
            candidate_step = post_steps[0]
            candidate_started = parse_time(candidate_step.get("started_at"))
            if candidate_started is None:
                raise ValueError(f"{name} has an in-progress post-action without valid started_at")
            reason = "post-action"
        age_minutes = (now - candidate_started).total_seconds() / 60
        if age_minutes < timeout_minutes:
            continue
        job_id = job.get("id")
        html_url = job.get("html_url")
        if not isinstance(job_id, int) or not isinstance(html_url, str):
            raise ValueError(f"{name} has no stable job id or URL")
        stalled.append({
            "jobId": str(job_id), "job": name, "step": str(candidate_step["name"]), "reason": reason,
            "startedAt": candidate_started.isoformat().replace("+00:00", "Z"), "ageMinutes": str(int(age_minutes)),
            "url": html_url,
        })
    return stalled


def self_test() -> int:
    now = datetime(2026, 8, 28, 12, 0, tzinfo=timezone.utc)
    base = {"id": 42, "name": "build (openbank-example-service) / openbank-example-service (build)",
            "status": "in_progress", "started_at": "2026-08-28T11:00:00Z",
            "html_url": "https://github.com/JiRaska/open-bank-oss/actions/runs/1/job/42",
            "steps": [{"name": "Build + test (:openbank-example-service)", "status": "in_progress"}]}
    cases = [
        ("stalled mandatory build is selected", {"jobs": [base]}, 1),
        ("fresh mandatory build is not selected", {"jobs": [{**base, "started_at": "2026-08-28T11:40:00Z"}]}, 0),
        ("long Kover report is not selected", {"jobs": [{**base, "steps": [{"name": "Build + test (:openbank-example-service)", "status": "completed"}, {"name": "Generate Kover XML report", "status": "in_progress"}]}]}, 0),
        ("stalled post-action after completed build is selected", {"jobs": [{**base, "steps": [{"name": "Build + test (:openbank-example-service)", "status": "completed"}, {"name": "Post Run actions/setup-java", "status": "in_progress", "started_at": "2026-08-28T11:00:00Z"}]}]}, 1),
        ("fresh post-action is not selected", {"jobs": [{**base, "steps": [{"name": "Build + test (:openbank-example-service)", "status": "completed"}, {"name": "Post Run actions/setup-java", "status": "in_progress", "started_at": "2026-08-28T11:40:00Z"}]}]}, 0),
        ("post-action beside active envelope is not selected", {"jobs": [{**base, "steps": [{"name": "Build + test (:openbank-example-service)", "status": "completed"}, {"name": "Build Test Intelligence run envelope", "status": "in_progress"}, {"name": "Post Run actions/setup-java", "status": "in_progress", "started_at": "2026-08-28T11:00:00Z"}]}]}, 0),
        ("verification post-action is selected", {"jobs": [{**base, "name": VERIFICATION_JOB, "steps": [{"name": "Check verification metadata", "status": "completed"}, {"name": "Post Run actions/setup-java", "status": "in_progress", "started_at": "2026-08-28T11:00:00Z"}]}]}, 1),
        ("other workflow job is not selected", {"jobs": [{**base, "name": "CodeQL (java-kotlin, manual)"}]}, 0),
    ]
    failures = 0
    for label, payload, expected in cases:
        actual = len(classify(payload, now, 45))
        if actual != expected:
            print(f"FAIL {label}: expected {expected}, got {actual}")
            failures += 1
        else:
            print(f"ok {label}")
    try:
        classify({"jobs": [{**base, "started_at": None}]}, now, 45)
        print("FAIL invalid started_at was accepted")
        failures += 1
    except ValueError:
        print("ok invalid in-progress metadata refuses a verdict")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jobs-json", type=Path)
    parser.add_argument("--now")
    parser.add_argument("--timeout-minutes", type=int, default=45)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.jobs_json or not args.now or args.timeout_minutes <= 0:
        parser.error("--jobs-json, --now and positive --timeout-minutes are required")
    now = parse_time(args.now)
    if now is None:
        parser.error("--now must be ISO-8601 UTC")
    try:
        print(json.dumps(classify(json.loads(args.jobs_json.read_text()), now, args.timeout_minutes)))
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"::error title=CI stalled-build watchdog::{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
