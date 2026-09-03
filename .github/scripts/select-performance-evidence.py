#!/usr/bin/env python3
"""Select attempt-correct GitHub Actions performance evidence from API payloads.

Artifacts from every rerun attempt share one run id. A full-matrix "re-run failed
jobs" also legitimately carries the successful sibling job and its older artifact.
This selector uses the latest-view jobs payload to distinguish that carry from a job
which ran in the current attempt and therefore needs a newly-created artifact.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

NARROWED = 3
UNKNOWN = 4
EXPECTED_GATE = (
    ("perf-reports-openbank-product-catalog", "k6 smoke (openbank-product-catalog)"),
    ("perf-reports-openbank-flaky-test-hunter", "k6 smoke (openbank-flaky-test-hunter)"),
)
BASELINE_ARTIFACT = "k6-money-path-summary"
OVERRIDE_SCOPE = re.compile(
    r"scope OVERRIDDEN by dispatch input\s*\((\d+) of (\d+) requested service\(s\)\)"
)


@dataclass(frozen=True)
class ArtifactWindow:
    not_before: datetime
    before: datetime | None = None


def instant(value: object) -> datetime:
    if not isinstance(value, str) or not value:
        raise ValueError("timestamp is absent")
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def identifier(value: object) -> int:
    if isinstance(value, bool):
        raise TypeError("boolean is not an id")
    return int(value)


def ordered_runs(payloads: object) -> list[dict]:
    pages = [payloads] if isinstance(payloads, dict) else payloads
    if not isinstance(pages, list):
        return []
    selected = []
    for page in pages:
        if not isinstance(page, dict):
            continue
        for run in page.get("workflow_runs", []):
            if not isinstance(run, dict):
                continue
            try:
                instant(run.get("run_started_at"))
                identifier(run.get("id"))
                attempt = identifier(run.get("run_attempt"))
            except (TypeError, ValueError):
                continue
            if attempt < 1:
                continue
            selected.append(run)
    selected.sort(
        key=lambda run: (instant(run["run_started_at"]), identifier(run["id"])),
        reverse=True,
    )
    return selected


def explicit_narrow_override(event: str, scope_log: str) -> bool:
    """Trust narrowing only when the dispatch log proves a smaller requested denominator."""
    if event != "workflow_dispatch":
        return False
    return any(
        int(requested) < len(EXPECTED_GATE)
        for _measured, requested in OVERRIDE_SCOPE.findall(scope_log)
    )


def gate_policies(
    jobs_payload: object,
    attempt_started_at: str,
    event: str = "unknown",
    scope_log: str = "",
) -> tuple[str, dict[str, ArtifactWindow]]:
    """Classify scope and return the artifact window attributable to each latest-view job."""
    if not isinstance(jobs_payload, dict):
        return "unknown", {}
    attempt_start = instant(attempt_started_at)
    jobs = [job for job in jobs_payload.get("jobs", []) if isinstance(job, dict)]
    perf_jobs = [job for job in jobs if str(job.get("name", "")).startswith("k6 smoke (")]
    expected_names = {job_name for _, job_name in EXPECTED_GATE}
    observed_names = {str(job.get("name", "")) for job in perf_jobs}
    if observed_names != expected_names:
        if observed_names <= expected_names and explicit_narrow_override(event, scope_log):
            return "narrowed", {}
        return "unknown", {}

    policies: dict[str, ArtifactWindow] = {}
    for artifact_name, job_name in EXPECTED_GATE:
        matches = [job for job in perf_jobs if job.get("name") == job_name]
        if len(matches) != 1:
            return "unknown", {}
        job = matches[0]
        started_at = job.get("started_at")
        if not started_at:
            continue
        job_start = instant(started_at)
        if job_start >= attempt_start:
            policies[artifact_name] = ArtifactWindow(job_start)
        elif job.get("conclusion") == "success":
            policies[artifact_name] = ArtifactWindow(job_start, attempt_start)
    return "full", policies


def newest_artifacts(
    payloads: object,
    policies: dict[str, ArtifactWindow],
) -> list[dict]:
    """Choose one non-expired artifact per name, respecting each job's attempt boundary."""
    pages = [payloads] if isinstance(payloads, dict) else payloads
    if not isinstance(pages, list):
        return []
    eligible: list[dict] = []
    for page in pages:
        if not isinstance(page, dict):
            continue
        for artifact in page.get("artifacts", []):
            if not isinstance(artifact, dict) or artifact.get("expired"):
                continue
            policy = policies.get(str(artifact.get("name", "")))
            if policy is None:
                continue
            try:
                created_at = instant(artifact.get("created_at"))
                identifier(artifact.get("id"))
                if created_at < policy.not_before:
                    continue
                if policy.before is not None and created_at >= policy.before:
                    continue
            except (TypeError, ValueError):
                continue
            eligible.append(artifact)

    eligible.sort(
        key=lambda artifact: (instant(artifact["created_at"]), identifier(artifact["id"])),
        reverse=True,
    )
    selected = []
    seen: set[str] = set()
    for artifact in eligible:
        name = str(artifact["name"])
        if name not in seen:
            seen.add(name)
            selected.append(artifact)
    return selected


def select_gate_candidate(candidates: list[dict]) -> tuple[str, int | None, list[dict]]:
    """Pure model of the workflow loop: skip proven narrow runs, stop at every other scope."""
    for candidate in ordered_runs({"workflow_runs": candidates}):
        scope, policies = gate_policies(
            candidate.get("jobs"),
            str(candidate["run_started_at"]),
            str(candidate.get("event", "unknown")),
            str(candidate.get("scope_log", "")),
        )
        run_id = identifier(candidate["id"])
        if scope == "narrowed":
            continue
        if scope != "full":
            return "barrier", run_id, []
        return "full", run_id, newest_artifacts(candidate.get("artifacts", []), policies)
    return "none", None, []


def prepare_job_identifier(jobs_payload: object) -> int | None:
    if not isinstance(jobs_payload, dict):
        return None
    matches = [
        job
        for job in jobs_payload.get("jobs", [])
        if isinstance(job, dict) and job.get("name") == "derive perf-gate scope"
    ]
    if len(matches) != 1:
        return None
    try:
        return identifier(matches[0].get("id"))
    except (TypeError, ValueError):
        return None


def artifact_page_size(payload: object) -> int:
    if not isinstance(payload, dict) or not isinstance(payload.get("artifacts"), list):
        raise TypeError("artifact page is malformed")
    return len(payload["artifacts"])


def run_page_size(payload: object) -> int:
    if not isinstance(payload, dict) or not isinstance(payload.get("workflow_runs"), list):
        raise TypeError("workflow run page is malformed")
    return len(payload["workflow_runs"])


def read_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"))


def read_json_pages(paths: list[Path]) -> list[object]:
    return [read_json(path) for path in paths]


def emit_artifacts(artifacts: list[dict]) -> None:
    for artifact in artifacts:
        print(identifier(artifact["id"]), artifact["name"])


def self_test() -> int:
    runs = {
        "workflow_runs": [
            {"id": 100, "run_started_at": "2026-09-01T10:00:00Z", "run_attempt": 1},
            {"id": 99, "run_started_at": "2026-09-01T12:00:00Z", "run_attempt": 1},
            {"id": 101, "run_started_at": "2026-09-01T12:00:00Z", "run_attempt": 2},
            {"id": 102, "run_started_at": None, "run_attempt": 1},
        ]
    }
    assert [run["id"] for run in ordered_runs(runs)] == [101, 99, 100]
    assert [run["id"] for run in ordered_runs([
        {"workflow_runs": runs["workflow_runs"][:2]},
        {"workflow_runs": runs["workflow_runs"][2:]},
    ])] == [101, 99, 100]
    assert run_page_size({"workflow_runs": [{}] * 100}) == 100

    attempt_start = "2026-09-01T12:00:00Z"
    jobs = {
        "jobs": [
            {
                "name": "k6 smoke (openbank-product-catalog)",
                "started_at": "2026-09-01T10:00:00Z",
                "conclusion": "success",
            },
            {
                "name": "k6 smoke (openbank-flaky-test-hunter)",
                "started_at": "2026-09-01T12:05:00Z",
                "conclusion": "failure",
            },
        ]
    }
    scope, policies = gate_policies(jobs, attempt_start)
    assert scope == "full"
    assert policies == {
        "perf-reports-openbank-product-catalog": ArtifactWindow(
            instant("2026-09-01T10:00:00Z"), instant(attempt_start)
        ),
        "perf-reports-openbank-flaky-test-hunter": ArtifactWindow(
            instant("2026-09-01T12:05:00Z")
        ),
    }
    artifacts = {
        "artifacts": [
            {
                "id": 1,
                "name": "perf-reports-openbank-product-catalog",
                "created_at": "2026-09-01T11:00:00Z",
                "expired": False,
            },
            {
                "id": 2,
                "name": "perf-reports-openbank-flaky-test-hunter",
                "created_at": "2026-09-01T11:30:00Z",
                "expired": False,
            },
            {
                "id": 3,
                "name": "perf-reports-openbank-product-catalog",
                "created_at": "2026-09-01T12:15:00Z",
                "expired": False,
            },
            {
                "id": 4,
                "name": "perf-reports-openbank-product-catalog",
                "created_at": "2026-09-01T09:59:59Z",
                "expired": False,
            },
            {
                "id": 8,
                "name": "perf-reports-openbank-flaky-test-hunter",
                "created_at": "2026-09-01T12:10:00Z",
                "expired": False,
            },
            {
                "id": 9,
                "name": "perf-reports-openbank-flaky-test-hunter",
                "created_at": "2026-09-01T12:10:00Z",
                "expired": False,
            },
            {
                "id": 10,
                "name": "perf-reports-openbank-flaky-test-hunter",
                "created_at": "2026-09-01T12:11:00Z",
                "expired": True,
            },
        ]
    }
    assert [(row["id"], row["name"]) for row in newest_artifacts(artifacts, policies)] == [
        (9, "perf-reports-openbank-flaky-test-hunter"),
        (1, "perf-reports-openbank-product-catalog"),
    ]
    paged = [
        {"artifacts": artifacts["artifacts"][:5]},
        {"artifacts": artifacts["artifacts"][5:]},
    ]
    assert [row["id"] for row in newest_artifacts(paged, policies)] == [9, 1]
    assert artifact_page_size({"artifacts": [{}] * 100}) == 100

    cancelled_jobs = json.loads(json.dumps(jobs))
    cancelled_jobs["jobs"][1]["started_at"] = None
    cancelled_jobs["jobs"][1]["conclusion"] = "cancelled"
    scope, cancelled = gate_policies(cancelled_jobs, attempt_start)
    assert scope == "full"
    assert [row["name"] for row in newest_artifacts(artifacts, cancelled)] == [
        "perf-reports-openbank-product-catalog"
    ]

    narrowed = {"jobs": [jobs["jobs"][1]]}
    override_log = (
        "perf-gate: scope OVERRIDDEN by dispatch input "
        "(1 of 1 requested service(s))"
    )
    assert gate_policies(narrowed, attempt_start, "schedule", override_log)[0] == "unknown"
    assert gate_policies(narrowed, attempt_start, "workflow_dispatch", "")[0] == "unknown"
    assert gate_policies(narrowed, attempt_start, "workflow_dispatch", override_log)[0] == "narrowed"
    assert gate_policies({"jobs": []}, attempt_start, "schedule", "")[0] == "unknown"
    extra = {"jobs": jobs["jobs"] + [{"name": "k6 smoke (openbank-extra)"}]}
    assert gate_policies(extra, attempt_start)[0] == "unknown"

    baseline = {
        "artifacts": [
            {"id": 20, "name": BASELINE_ARTIFACT, "created_at": "2026-09-01T11:59:59Z", "expired": False},
            {"id": 21, "name": BASELINE_ARTIFACT, "created_at": "2026-09-01T12:00:00Z", "expired": False},
        ]
    }
    selected = newest_artifacts(baseline, {BASELINE_ARTIFACT: ArtifactWindow(instant(attempt_start))})
    assert [row["id"] for row in selected] == [21]
    assert newest_artifacts(
        baseline,
        {BASELINE_ARTIFACT: ArtifactWindow(instant("2026-09-01T12:00:01Z"))},
    ) == []

    old_jobs = json.loads(json.dumps(jobs))
    old_jobs["jobs"][1]["conclusion"] = "success"
    old_full = {
        "id": 200,
        "run_started_at": "2026-08-31T12:00:00Z",
        "run_attempt": 1,
        "event": "schedule",
        "jobs": old_jobs,
        "scope_log": "",
        "artifacts": artifacts,
    }
    new_full_incomplete = {
        **old_full,
        "id": 201,
        "run_started_at": attempt_start,
        "jobs": cancelled_jobs,
    }
    state, run_id, rows = select_gate_candidate([old_full, new_full_incomplete])
    assert (state, run_id) == ("full", 201)
    assert [row["name"] for row in rows] == ["perf-reports-openbank-product-catalog"]

    new_explicit_narrow = {
        **old_full,
        "id": 202,
        "run_started_at": "2026-09-02T12:00:00Z",
        "event": "workflow_dispatch",
        "jobs": narrowed,
        "scope_log": override_log,
    }
    assert select_gate_candidate([old_full, new_explicit_narrow])[1] == 200

    new_default_incomplete = {
        **new_explicit_narrow,
        "id": 203,
        "event": "schedule",
        "scope_log": "",
    }
    assert select_gate_candidate([old_full, new_default_incomplete])[:2] == ("barrier", 203)
    print(
        "performance evidence selector self-test: ordering, positive narrowing, "
        "rerun carry windows, pagination and fail-closed candidate barriers proven"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    subparsers = parser.add_subparsers(dest="command")
    runs_parser = subparsers.add_parser("runs")
    runs_parser.add_argument("--latest", action="store_true")
    runs_parser.add_argument("--runs", type=Path, action="append")
    gate_parser = subparsers.add_parser("gate")
    gate_parser.add_argument("--attempt-start", required=True)
    gate_parser.add_argument("--event", required=True)
    gate_parser.add_argument("--jobs", type=Path, required=True)
    gate_parser.add_argument("--scope-log", type=Path)
    gate_parser.add_argument("--artifacts", type=Path, action="append")
    gate_parser.add_argument("--scope-only", action="store_true")
    baseline_parser = subparsers.add_parser("baseline")
    baseline_parser.add_argument("--attempt-start", required=True)
    baseline_parser.add_argument("--artifacts", type=Path, action="append", required=True)
    page_size_parser = subparsers.add_parser("artifact-page-size")
    page_size_parser.add_argument("--artifacts", type=Path, required=True)
    prepare_job_parser = subparsers.add_parser("prepare-job")
    prepare_job_parser.add_argument("--jobs", type=Path, required=True)
    run_page_size_parser = subparsers.add_parser("run-page-size")
    run_page_size_parser.add_argument("--runs", type=Path, required=True)
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    try:
        if args.command == "runs":
            payloads = read_json_pages(args.runs) if args.runs else json.load(sys.stdin)
            selected = ordered_runs(payloads)
            if args.latest:
                selected = selected[:1]
            for run in selected:
                print(
                    identifier(run["id"]),
                    run["run_started_at"],
                    identifier(run["run_attempt"]),
                    run.get("event") or "unknown",
                )
            return 0
        if args.command == "gate":
            scope_log = args.scope_log.read_text(encoding="utf-8") if args.scope_log else ""
            scope, policies = gate_policies(
                read_json(args.jobs), args.attempt_start, args.event, scope_log
            )
            if scope == "narrowed":
                print(scope)
                return NARROWED
            if scope != "full":
                print("unknown")
                return UNKNOWN
            if args.scope_only:
                print("full")
                return 0
            if args.artifacts is None:
                return UNKNOWN
            emit_artifacts(newest_artifacts(read_json_pages(args.artifacts), policies))
            return 0
        if args.command == "baseline":
            emit_artifacts(
                newest_artifacts(
                    read_json_pages(args.artifacts),
                    {BASELINE_ARTIFACT: ArtifactWindow(instant(args.attempt_start))},
                )
            )
            return 0
        if args.command == "artifact-page-size":
            print(artifact_page_size(read_json(args.artifacts)))
            return 0
        if args.command == "prepare-job":
            job_id = prepare_job_identifier(read_json(args.jobs))
            if job_id is None:
                return UNKNOWN
            print(job_id)
            return 0
        if args.command == "run-page-size":
            print(run_page_size(read_json(args.runs)))
            return 0
    except (OSError, json.JSONDecodeError, TypeError, ValueError) as exc:
        print(f"performance evidence selector: {exc}", file=sys.stderr)
        return UNKNOWN
    parser.error("a command or --self-test is required")
    return UNKNOWN


if __name__ == "__main__":
    raise SystemExit(main())
