#!/usr/bin/env python3
"""Select recent CI runs that need spot-retry or flake recording (#9645)."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path

WATCHED = {"Services CI", "Security scan", "Dependency submission"}
SHORT_MINUTES = 30
DEEP_CREATED_MINUTES = 24 * 60
DEEP_UPDATED_MINUTES = 180
GITHUB_SEARCH_CEILING = 1000


def parse_time(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def query_window(mode: str, now: dt.datetime) -> dict[str, str | int]:
    """Pair a cheap frequent scan with an hourly scan for long-running workflows."""
    if mode not in {"short", "deep"}:
        raise ValueError(f"unknown reconciliation mode: {mode}")
    created_minutes = SHORT_MINUTES if mode == "short" else DEEP_CREATED_MINUTES
    updated_minutes = SHORT_MINUTES if mode == "short" else DEEP_UPDATED_MINUTES
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
    if len({run.get("id") for run in runs if isinstance(run, dict)}) != len(runs):
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
            "head_sha": run.get("head_sha") or "",
            "head_branch": run.get("head_branch") or "",
        }
        if item["run_attempt"] == 1 and item["conclusion"] in {"failure", "cancelled"}:
            retry[item["run_id"]] = item
        elif item["run_attempt"] > 1 and item["conclusion"] == "success":
            flakes[item["run_id"]] = item
    key = lambda item: item["run_id"]
    return {"retry": sorted(retry.values(), key=key), "flakes": sorted(flakes.values(), key=key)}


def self_test() -> int:
    now = parse_time("2026-09-11T00:30:00Z")
    base = {"status": "completed", "updated_at": "2026-09-11T00:29:00Z", "html_url": "u", "head_sha": "s", "head_branch": "b"}
    runs = [
        base | {"id": 1, "name": "Services CI", "conclusion": "cancelled", "run_attempt": 1},
        base | {"id": 2, "name": "Security scan", "conclusion": "failure", "run_attempt": 1},
        base | {"id": 3, "name": "Dependency submission", "conclusion": "success", "run_attempt": 2},
        base | {"id": 4, "name": "Services CI", "conclusion": "success", "run_attempt": 1},
        base | {"id": 5, "name": "Other", "conclusion": "failure", "run_attempt": 1},
        (base | {"id": 6, "name": "Services CI", "conclusion": "failure", "run_attempt": 1, "updated_at": "2026-09-10T22:00:00Z"}),
        base | {"id": 1, "name": "Services CI", "conclusion": "cancelled", "run_attempt": 1},
    ]
    got = select(runs, now, 90)
    assert [x["run_id"] for x in got["retry"]] == [1, 2]
    assert [x["run_id"] for x in got["flakes"]] == [3]
    short = query_window("short", now)
    deep = query_window("deep", now)
    assert short == {"created_query": "2026-09-11T00:00:00Z..2026-09-11T00:30:00Z", "updated_lookback_minutes": 30}
    assert deep == {"created_query": "2026-09-10T00:30:00Z..2026-09-11T00:30:00Z", "updated_lookback_minutes": 180}
    long_run = base | {"id": 7, "name": "Services CI", "conclusion": "cancelled", "run_attempt": 1,
                       "created_at": "2026-09-10T23:10:00Z"}
    assert parse_time(long_run["created_at"]) < parse_time(short["created_query"].split("..")[0])
    assert parse_time(long_run["created_at"]) >= parse_time(deep["created_query"].split("..")[0])
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
    ):
        try:
            flatten_pages(invalid)
        except ValueError:
            pass
        else:
            raise AssertionError(f"incomplete API result passed: {invalid!r}")
    print("SUBJECTS=15")
    print("select-ci-reconcile-candidates self-test: 15 cases passed, 0 failed")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs-file", type=Path)
    parser.add_argument("--now")
    parser.add_argument("--lookback-minutes", type=int, default=90)
    parser.add_argument("--query-mode", choices=("short", "deep"))
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
