#!/usr/bin/env python3
"""Select recent CI runs that need spot-retry or flake recording (#9645)."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path

WATCHED = {"Services CI", "Security scan", "Dependency submission"}


def parse_time(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


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
    print("SUBJECTS=3")
    print("select-ci-reconcile-candidates self-test: 3 passed, 0 failed")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs-file", type=Path)
    parser.add_argument("--now")
    parser.add_argument("--lookback-minutes", type=int, default=90)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.runs_file or not args.now:
        parser.error("--runs-file and --now are required")
    runs = json.loads(args.runs_file.read_text())
    print(json.dumps(select(runs, parse_time(args.now), args.lookback_minutes), separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
