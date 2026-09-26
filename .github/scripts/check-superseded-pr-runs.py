#!/usr/bin/env python3
"""Select queued pull-request runs whose exact head is no longer current."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def candidates(runs: list[dict[str, Any]], pulls: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
    current = {
        int(pull["number"]): str(pull["head"]["sha"])
        for pull in pulls
        if pull.get("number") is not None and isinstance(pull.get("head"), dict) and pull["head"].get("sha")
    }
    selected: list[dict[str, Any]] = []
    for run in sorted(runs, key=lambda item: (item.get("created_at", ""), int(item.get("id", 0)))):
        linked = run.get("pull_requests") or []
        if run.get("event") != "pull_request" or not linked:
            continue
        number = linked[0].get("number")
        run_id = run.get("id")
        head_sha = run.get("head_sha")
        if not isinstance(number, int) or not isinstance(run_id, int) or not isinstance(head_sha, str):
            continue
        if current.get(number) == head_sha:
            continue
        selected.append(
            {
                "runId": run_id,
                "pr": number,
                "headSha": head_sha,
                "workflow": str(run.get("name", "")),
                "createdAt": str(run.get("created_at", "")),
            }
        )
        if len(selected) >= limit:
            break
    return selected


def self_test() -> None:
    pulls = [{"number": 7, "head": {"sha": "new"}}, {"number": 8, "head": {"sha": "same"}}]
    runs = [
        {"id": 4, "event": "push", "head_sha": "old", "pull_requests": [{"number": 7}]},
        {"id": 3, "event": "pull_request", "head_sha": "new", "pull_requests": [{"number": 7}]},
        {"id": 2, "event": "pull_request", "head_sha": "old", "pull_requests": [{"number": 7}], "name": "CI", "created_at": "2026-01-02"},
        {"id": 1, "event": "pull_request", "head_sha": "closed", "pull_requests": [{"number": 9}], "name": "Security", "created_at": "2026-01-01"},
        {"id": 5, "event": "pull_request", "head_sha": "same", "pull_requests": [{"number": 8}]},
        {"id": 6, "event": "pull_request", "head_sha": "unknown", "pull_requests": []},
    ]
    actual = candidates(runs, pulls, 25)
    assert [item["runId"] for item in actual] == [1, 2], actual
    assert candidates(runs, pulls, 1) == [actual[0]]
    assert candidates([], pulls, 25) == []
    print("self-test ok: current/non-PR/unlinked runs stay; stale and closed heads are bounded")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=Path)
    parser.add_argument("--pulls", type=Path)
    parser.add_argument("--limit", type=int, default=25)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.runs is None or args.pulls is None:
        parser.error("--runs and --pulls are required")
    if args.limit < 1 or args.limit > 100:
        parser.error("--limit must be between 1 and 100")
    runs = json.loads(args.runs.read_text())
    pulls = json.loads(args.pulls.read_text())
    if not isinstance(runs, list) or not isinstance(pulls, list):
        raise ValueError("runs and pulls inputs must both be JSON arrays")
    print(json.dumps(candidates(runs, pulls, args.limit)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
