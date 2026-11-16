#!/usr/bin/env python3
"""Select recent CI runs that need spot-retry or flake recording (#9645)."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path

WATCHED = {"Services CI", "Security scan", "Dependency submission", "CI"}
# `CI` (ci.yml) is watched for FAILURES only: its gate shards go red NOT EVALUATED when the
# installation API quota runs out, which spot-kill-retry.sh re-runs after the quota resets. Its
# cancellations are concurrency supersessions on GitHub-hosted runners, never a spot reclaim, so
# a `cancelled` CI run is not a candidate at all.
FAILURE_ONLY = {"CI"}
# The frequent scan asks "what COMPLETED in the last 30 minutes", but GitHub can only filter
# runs by CREATION time. With one 30-minute bound for both, a run was a candidate only if it
# was created AND finished inside the same half hour — and a main Services CI run that fans
# out to the self-hosted pool does not: measured over 2026-10-30..11-12, 54 of the 60 failed
# main push runs took longer than that from creation to completion, so the frequent scan
# could not see 90% of them (runs 43210743668 and 43331225900 sat red for ~16 h). Four hours
# of creations is still one page per watched workflow on a busy day (60 runs measured).
SHORT_CREATED_MINUTES = 4 * 60
SHORT_UPDATED_MINUTES = 30
DEEP_CREATED_MINUTES = 24 * 60
DEEP_UPDATED_MINUTES = 180
DEEP_CRON = "7 * * * *"
# 16 of those 60 runs took more than four hours (queue backlog), so the deep scan stays the
# only reader for them — and it was reachable from the hourly cron alone, which the platform
# delivers unreliably (2 deliveries in 17 h on 2026-11-12/13, none inside the three-hour
# overlap of either run above). A dispatched tick therefore takes the deep window whenever it
# starts in the first DEEP_SLOT_MINUTES of the hour: any dispatcher ticking at least that
# often runs one deep scan per hour with no state to keep.
DEEP_SLOT_MINUTES = 25
GITHUB_SEARCH_CEILING = 1000


def parse_time(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def resolve_mode(schedule: str, dispatch_mode: str, now: dt.datetime) -> str:
    """Decide the scan window from the trigger; an explicit request always wins."""
    if dispatch_mode in {"short", "deep"}:
        return dispatch_mode
    if dispatch_mode not in {"", "auto"}:
        raise ValueError(f"unknown reconciliation mode: {dispatch_mode}")
    if schedule == DEEP_CRON:
        return "deep"
    if schedule:
        return "short"  # the frequent cron: its window is priced per tick (#9645)
    return "deep" if now.minute < DEEP_SLOT_MINUTES else "short"


def query_window(mode: str, now: dt.datetime) -> dict[str, str | int]:
    """Pair a cheap frequent scan with an hourly scan for long-running workflows."""
    if mode not in {"short", "deep"}:
        raise ValueError(f"unknown reconciliation mode: {mode}")
    created_minutes = SHORT_CREATED_MINUTES if mode == "short" else DEEP_CREATED_MINUTES
    updated_minutes = SHORT_UPDATED_MINUTES if mode == "short" else DEEP_UPDATED_MINUTES
    cutoff = now - dt.timedelta(minutes=created_minutes)
    start = cutoff.strftime("%Y-%m-%dT%H:%M:%SZ")
    end = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "created_query": f"{start}..{end}",
        "updated_lookback_minutes": updated_minutes,
    }


def flatten_pages(pages: object) -> list[dict]:
    """Reject an incomplete GitHub search instead of reporting an empty candidate set."""
    if not isinstance(pages, list) or not pages:
        raise ValueError("workflow-run API returned no pages")
    counts = []
    runs = []
    for page in pages:
        if not isinstance(page, dict) or type(page.get("total_count")) is not int:
            raise ValueError("workflow-run API omitted total_count")
        if page["total_count"] < 0 or not isinstance(page.get("workflow_runs"), list):
            raise ValueError("workflow-run API returned malformed results")
        counts.append(page["total_count"])
        runs.extend(page["workflow_runs"])
    if len(set(counts)) != 1:
        raise ValueError("workflow-run API changed total_count during pagination")
    if counts[0] >= GITHUB_SEARCH_CEILING:
        raise ValueError("workflow-run search reached GitHub's 1,000-result ceiling")
    if len(runs) != counts[0]:
        raise ValueError(f"workflow-run API returned {len(runs)} of {counts[0]} runs")
    ids = [run.get("id") for run in runs if isinstance(run, dict)]
    if len(ids) != len(runs) or any(type(run_id) is not int or run_id <= 0 for run_id in ids) or len(set(ids)) != len(ids):
        raise ValueError("workflow-run API returned duplicate or malformed run IDs")
    return runs


def select(runs: list[dict], now: dt.datetime, lookback_minutes: int) -> dict[str, list[dict]]:
    cutoff = now - dt.timedelta(minutes=lookback_minutes)
    retry: dict[int, dict] = {}
    flakes: dict[int, dict] = {}
    for run in runs:
        if run.get("name") not in WATCHED or run.get("status") != "completed":
            continue
        if parse_time(run["updated_at"]) < cutoff:
            continue
        item = {
            "run_id": int(run["id"]),
            "run_url": run["html_url"],
            "conclusion": run.get("conclusion") or "",
            "run_attempt": int(run.get("run_attempt") or 1),
            "workflow_name": run["name"],
            "event": run.get("event") or "",
            "head_sha": run.get("head_sha") or "",
            "head_branch": run.get("head_branch") or "",
        }
        retryable = {"failure"} if item["workflow_name"] in FAILURE_ONLY else {"failure", "cancelled"}
        if item["run_attempt"] == 1 and item["conclusion"] in retryable:
            retry[item["run_id"]] = item
        elif item["run_attempt"] > 1 and item["conclusion"] == "success":
            flakes[item["run_id"]] = item
    key = lambda item: item["run_id"]
    return {"retry": sorted(retry.values(), key=key), "flakes": sorted(flakes.values(), key=key)}


def self_test() -> int:
    now = parse_time("2026-09-11T00:30:00Z")
    base = {"status": "completed", "updated_at": "2026-09-11T00:29:00Z", "html_url": "u", "head_sha": "s", "head_branch": "b", "event": "pull_request"}
    runs = [
        base | {"id": 1, "name": "Services CI", "conclusion": "cancelled", "run_attempt": 1},
        base | {"id": 2, "name": "Security scan", "conclusion": "failure", "run_attempt": 1},
        base | {"id": 3, "name": "Dependency submission", "conclusion": "success", "run_attempt": 2},
        base | {"id": 4, "name": "Services CI", "conclusion": "success", "run_attempt": 1},
        base | {"id": 5, "name": "Other", "conclusion": "failure", "run_attempt": 1},
        (base | {"id": 6, "name": "Services CI", "conclusion": "failure", "run_attempt": 1, "updated_at": "2026-09-10T22:00:00Z"}),
        base | {"id": 1, "name": "Services CI", "conclusion": "cancelled", "run_attempt": 1},
        base | {"id": 9, "name": "CI", "conclusion": "failure", "run_attempt": 1},
        base | {"id": 10, "name": "CI", "conclusion": "cancelled", "run_attempt": 1},
        base | {"id": 11, "name": "CI", "conclusion": "failure", "run_attempt": 2},
    ]
    got = select(runs, now, 90)
    assert [x["run_id"] for x in got["retry"]] == [1, 2, 9], got["retry"]
    assert [x["run_id"] for x in got["flakes"]] == [3]
    assert all(x["event"] == "pull_request" for x in got["retry"])
    short = query_window("short", now)
    deep = query_window("deep", now)
    assert short == {"created_query": "2026-09-10T20:30:00Z..2026-09-11T00:30:00Z", "updated_lookback_minutes": 30}
    assert deep == {"created_query": "2026-09-10T00:30:00Z..2026-09-11T00:30:00Z", "updated_lookback_minutes": 180}
    short_start = parse_time(short["created_query"].split("..")[0])
    # Known-positive (runs 43210743668 / 43331225900): created 71-95 min before it finished.
    # The frequent scan must be able to read it, and select it on the 30-minute lookback.
    main_run = base | {"id": 12, "name": "Services CI", "conclusion": "failure", "run_attempt": 1,
                       "event": "push", "head_branch": "main", "created_at": "2026-09-10T23:00:00Z"}
    assert parse_time(main_run["created_at"]) >= short_start
    assert [x["run_id"] for x in select([main_run], now, short["updated_lookback_minutes"])["retry"]] == [12]
    # Known-negative: the wider CREATED bound must not widen what counts as recently completed.
    stale = main_run | {"id": 13, "updated_at": "2026-09-10T23:55:00Z"}
    assert select([stale], now, short["updated_lookback_minutes"])["retry"] == []
    long_run = base | {"id": 7, "name": "Services CI", "conclusion": "cancelled", "run_attempt": 1,
                       "created_at": "2026-09-10T19:10:00Z"}
    assert parse_time(long_run["created_at"]) < short_start
    assert parse_time(long_run["created_at"]) >= parse_time(deep["created_query"].split("..")[0])
    at = lambda minute: parse_time(f"2026-09-11T00:{minute:02d}:00Z")
    for schedule, dispatch, minute, want in (
        ("", "auto", 0, "deep"),          # dispatched, inside the hourly deep slot
        ("", "auto", 24, "deep"),
        ("", "auto", 25, "short"),        # dispatched, outside it
        ("", "auto", 59, "short"),
        ("", "", 10, "deep"),             # a dispatcher that sends no input at all
        ("", "short", 0, "short"),        # explicit requests win over the slot
        ("", "deep", 59, "deep"),
        (DEEP_CRON, "", 59, "deep"),      # the hourly cron, whenever it is delivered
        ("*/10 * * * *", "", 0, "short"),  # the frequent cron never pays for a deep window
    ):
        assert resolve_mode(schedule, dispatch, at(minute)) == want, (schedule, dispatch, minute)
    # Any cadence of at most DEEP_SLOT_MINUTES lands in the slot at least once an hour.
    for offset in range(60):
        ticks = {(offset + step * DEEP_SLOT_MINUTES) % 60 for step in range(3)}
        assert any(resolve_mode("", "auto", at(minute)) == "deep" for minute in ticks), offset
    try:
        resolve_mode("", "weekly", now)
    except ValueError:
        pass
    else:
        raise AssertionError("an unknown dispatch mode was accepted")
    assert [x["run_id"] for x in select(flatten_pages([{"total_count": 1, "workflow_runs": [long_run]}]),
                                            now, 180)["retry"]] == [7]
    later_rerun = long_run | {"id": 8, "conclusion": "success", "run_attempt": 2}
    assert [x["run_id"] for x in select([later_rerun], now, 180)["flakes"]] == [8]
    assert flatten_pages([{"total_count": 0, "workflow_runs": []}]) == []
    for invalid in (
        [],
        [{"workflow_runs": []}],
        [{"total_count": 2, "workflow_runs": [long_run]}],
        [{"total_count": GITHUB_SEARCH_CEILING, "workflow_runs": []}],
        [{"total_count": 2, "workflow_runs": [long_run]}, {"total_count": 3, "workflow_runs": [long_run]}],
        [{"total_count": 2, "workflow_runs": [long_run, long_run]}],
        [{"total_count": 1, "workflow_runs": [{"name": "Services CI"}]}],
    ):
        try:
            flatten_pages(invalid)
        except ValueError:
            pass
        else:
            raise AssertionError(f"incomplete API result passed: {invalid!r}")
    print("SUBJECTS=33")
    print("select-ci-reconcile-candidates self-test: 33 cases passed, 0 failed")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs-file", type=Path)
    parser.add_argument("--now")
    parser.add_argument("--lookback-minutes", type=int, default=90)
    parser.add_argument("--query-mode", choices=("short", "deep"))
    parser.add_argument("--resolve-mode", action="store_true")
    parser.add_argument("--schedule", default="")
    parser.add_argument("--dispatch-mode", default="")
    parser.add_argument("--flatten-pages", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.flatten_pages:
        try:
            print(json.dumps(flatten_pages(json.loads(args.flatten_pages.read_text())), separators=(",", ":")))
        except ValueError as exc:
            print(f"incomplete workflow-run API result: {exc}", file=sys.stderr)
            return 2
        return 0
    if args.resolve_mode:
        if not args.now:
            parser.error("--now is required with --resolve-mode")
        try:
            print(resolve_mode(args.schedule, args.dispatch_mode, parse_time(args.now)))
        except ValueError as exc:
            print(str(exc), file=sys.stderr)
            return 2
        return 0
    if args.query_mode:
        if not args.now:
            parser.error("--now is required with --query-mode")
        print(json.dumps(query_window(args.query_mode, parse_time(args.now)), separators=(",", ":")))
        return 0
    if not args.runs_file or not args.now:
        parser.error("--runs-file and --now are required")
    runs = json.loads(args.runs_file.read_text())
    print(json.dumps(select(runs, parse_time(args.now), args.lookback_minutes), separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
