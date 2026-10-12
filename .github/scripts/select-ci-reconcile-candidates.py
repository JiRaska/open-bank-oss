#!/usr/bin/env python3
"""Select recent CI runs that need spot-retry or flake recording (#9645)."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from pathlib import Path

WATCHED = {"Services CI", "Security scan", "Dependency submission"}
SHORT_UPDATED_MINUTES = 30
DEEP_CREATED_MINUTES = 24 * 60
DEEP_UPDATED_MINUTES = 180
GITHUB_SEARCH_CEILING = 1000
MAX_CATCHUP_MINUTES = 48 * 60
QUERY_CHUNK_HOURS = 6
ACTIVE_ALARM_MINUTES = 23 * 60
ACTIVE_STATUSES = ("requested", "pending", "queued", "waiting", "in_progress", "action_required")


def parse_time(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def query_window(mode: str, now: dt.datetime, checkpoint: dt.datetime | None = None) -> dict[str, str | int | list[str]]:
    """Cover every completion since the last successful reconciliation.

    GitHub replaces a pending run in a concurrency group when another tick arrives,
    even with cancel-in-progress:false. The checkpoint is the *creation* time of
    the latest successful run, which precedes that run's discovery step and is
    therefore a conservative boundary. A candidate may have been created up to
    24 hours before completing, so extend the created range by that much too.
    """
    if mode not in {"short", "deep"}:
        raise ValueError(f"unknown reconciliation mode: {mode}")
    # The hourly deep tick may itself be replaced in the shared concurrency
    # group. Every surviving tick must be able to see a long-running job that
    # was created yesterday and only completed within its update window.
    created_minutes = DEEP_CREATED_MINUTES
    updated_minutes = SHORT_UPDATED_MINUTES if mode == "short" else DEEP_UPDATED_MINUTES
    cutoff = now - dt.timedelta(minutes=created_minutes)
    if checkpoint is not None:
        if checkpoint > now:
            raise ValueError("reconciliation checkpoint is in the future")
        if now - checkpoint > dt.timedelta(minutes=MAX_CATCHUP_MINUTES):
            raise ValueError("successful reconciliation checkpoint is more than 48 hours old; manual backfill required")
        # The normal short/deep windows remain cheap while ticks are healthy.
        # When a queued tick is replaced or a runner waits longer than its
        # overlap, the last successful tick becomes the lower bound instead.
        if checkpoint < now - dt.timedelta(minutes=updated_minutes):
            cutoff = min(cutoff, checkpoint - dt.timedelta(minutes=DEEP_CREATED_MINUTES))
            updated_minutes = max(updated_minutes, int((now - checkpoint).total_seconds() / 60) + 2)
    start = cutoff.strftime("%Y-%m-%dT%H:%M:%SZ")
    end = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    ranges = []
    cursor = cutoff
    while cursor < now:
        next_cursor = min(cursor + dt.timedelta(hours=QUERY_CHUNK_HOURS), now)
        ranges.append(f"{cursor:%Y-%m-%dT%H:%M:%SZ}..{next_cursor:%Y-%m-%dT%H:%M:%SZ}")
        cursor = next_cursor
    return {
        "created_query": f"{start}..{end}",
        "created_queries": ranges,
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


def merge_chunks(chunks: list[list[dict]]) -> list[dict]:
    """Keep the newest state when inclusive created ranges repeat a boundary run."""
    by_id: dict[int, dict] = {}
    for chunk in chunks:
        if not isinstance(chunk, list):
            raise TypeError("workflow-run chunk is not an array")
        for run in chunk:
            if not isinstance(run, dict) or type(run.get("id")) is not int or run["id"] <= 0:
                raise ValueError("workflow-run chunk has a malformed run ID")
            old = by_id.get(run["id"])
            if old is None or (
                int(run.get("run_attempt") or 1),
                run.get("status") == "completed",
                parse_time(run["updated_at"]),
            ) > (
                int(old.get("run_attempt") or 1),
                old.get("status") == "completed",
                parse_time(old["updated_at"]),
            ):
                by_id[run["id"]] = run
    return sorted(by_id.values(), key=lambda run: run["id"])


def parse_live_heads(pages: object) -> set[str]:
    """Accept only a complete paginated snapshot of open PR heads."""
    if not isinstance(pages, list) or not pages:
        raise ValueError("open-PR API returned no pages")
    # A single sub-100 page is a complete snapshot. With multiple pages, PRs
    # opening/closing during pagination can shift offsets without a total_count
    # checksum, so fail rather than silently treat an omitted head as stale.
    if len(pages) != 1 or (isinstance(pages[0], list) and len(pages[0]) >= 100):
        raise ValueError("open-PR API pagination cannot prove a stable head snapshot")
    heads = set()
    for page in pages:
        if not isinstance(page, list):
            raise TypeError("open-PR API returned malformed page")
        for pr in page:
            if not isinstance(pr, dict) or pr.get("state") != "open":
                raise ValueError("open-PR API returned malformed PR")
            head = pr.get("head")
            if not isinstance(head, dict):
                raise TypeError("open-PR API omitted head object")
            sha = head.get("sha")
            if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
                raise ValueError("open-PR API omitted head SHA")
            heads.add(sha)
    return heads


def verify_active_age(runs: list[dict], now: dt.datetime, live_pr_heads: set[str], main_sha: str) -> None:
    """Alarm before a watched run ages out of the bounded created search.

    A later completion of an older active run is invisible to GitHub's
    created-only workflow-run search. Discovery must not report a green scan
    while such a run exists; the workflow's failure job keeps the alarm open.
    This bounded guard cannot reconstruct an already-completed historical run
    or an old manually rerun attempt that starts and finishes between ticks.
    """
    # One hour of margin exceeds the normal 30-minute completion overlap.
    # Without it, a run could be 23h59m old at one successful tick, complete
    # before the next tick, and disappear from both the active query and the
    # next 24-hour created query.
    cutoff = now - dt.timedelta(minutes=ACTIVE_ALARM_MINUTES)
    if not re.fullmatch(r"[0-9a-f]{40}", main_sha):
        raise ValueError("main head SHA is missing or malformed")
    for run in runs:
        if not isinstance(run, dict) or type(run.get("id")) is not int:
            raise ValueError("active workflow-run search returned malformed run")
        event = run.get("event")
        head_sha = run.get("head_sha")
        if not isinstance(head_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", head_sha):
            raise ValueError("active workflow-run search omitted head SHA")
        if event in {"pull_request", "pull_request_target"} and head_sha not in live_pr_heads:
            continue  # Closed or superseded PR run cannot be safely retried.
        if event in {"push", "workflow_dispatch"} and head_sha != main_sha:
            continue  # A later main commit has superseded this run.
        if parse_time(run["created_at"]) <= cutoff:
            raise ValueError(f"watched run {run['id']} is approaching the 24-hour created-search bound; manual backfill required")


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
        if item["run_attempt"] == 1 and item["conclusion"] in {"failure", "cancelled"}:
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
    ]
    got = select(runs, now, 90)
    assert [x["run_id"] for x in got["retry"]] == [1, 2]
    assert [x["run_id"] for x in got["flakes"]] == [3]
    assert all(x["event"] == "pull_request" for x in got["retry"])
    short = query_window("short", now)
    deep = query_window("deep", now)
    assert short["created_query"] == "2026-09-10T00:30:00Z..2026-09-11T00:30:00Z"
    assert len(short["created_queries"]) == 4
    assert short["updated_lookback_minutes"] == 30
    assert deep["created_query"] == "2026-09-10T00:30:00Z..2026-09-11T00:30:00Z"
    assert len(deep["created_queries"]) == 4
    assert deep["updated_lookback_minutes"] == 180
    # A 65-minute hosted-runner wait and several replaced five-minute ticks
    # must recover a run completed in the otherwise unobserved middle window.
    checkpoint = parse_time("2026-09-10T23:25:00Z")
    catchup = query_window("short", now, checkpoint)
    assert catchup["created_query"] == "2026-09-09T23:25:00Z..2026-09-11T00:30:00Z"
    assert len(catchup["created_queries"]) == 5
    assert catchup["updated_lookback_minutes"] == 67
    assert catchup["created_queries"][0].startswith("2026-09-09T23:25:00Z..")
    assert catchup["created_queries"][-1].endswith("..2026-09-11T00:30:00Z")
    assert all(left.split("..")[1] == right.split("..")[0]
               for left, right in zip(catchup["created_queries"], catchup["created_queries"][1:]))
    missed = base | {"id": 9, "name": "Services CI", "conclusion": "failure", "run_attempt": 1,
                     "created_at": "2026-09-10T00:00:00Z", "updated_at": "2026-09-10T23:45:00Z"}
    assert parse_time(catchup["created_queries"][0].split("..")[0]) <= parse_time(missed["created_at"])
    assert select([missed], now, catchup["updated_lookback_minutes"])["retry"][0]["run_id"] == 9
    delayed = now + dt.timedelta(minutes=15)
    near_cutoff = missed | {"id": 10, "updated_at": "2026-09-10T23:26:00Z"}
    assert [x["run_id"] for x in select([near_cutoff], now, catchup["updated_lookback_minutes"])["retry"]] == [10]
    assert select([near_cutoff], delayed, catchup["updated_lookback_minutes"])["retry"] == []
    # Just-before-threshold active run is inside the next checkpoint-anchored
    # created range if it completes between ticks. At the threshold it must
    # raise a persistent alarm instead of silently disappearing.
    main_sha = "a" * 40
    recent_active = missed | {"id": 12, "status": "queued", "event": "pull_request", "head_sha": "b" * 40,
                              "created_at": "2026-09-10T01:31:00Z"}
    verify_active_age([recent_active], now, {"b" * 40}, main_sha)
    assert parse_time(recent_active["created_at"]) >= parse_time(catchup["created_query"].split("..")[0])
    normal_next = query_window("short", now + dt.timedelta(minutes=29), now)
    delayed_next = query_window("short", now + dt.timedelta(minutes=65), now)
    assert parse_time(recent_active["created_at"]) >= parse_time(normal_next["created_query"].split("..")[0])
    assert parse_time(recent_active["created_at"]) >= parse_time(delayed_next["created_query"].split("..")[0])
    old_active = recent_active | {"created_at": "2026-09-10T01:30:00Z"}
    try:
        verify_active_age([old_active], now, {"b" * 40}, main_sha)
    except ValueError as exc:
        assert "manual backfill" in str(exc)
    else:
        raise AssertionError("aged-out queued run produced a green scan")
    verify_active_age([old_active], now, set(), main_sha)  # Closed PR is not retryable.
    verify_active_age([old_active | {"event": "push", "head_sha": "c" * 40}], now, set(), main_sha)
    try:
        verify_active_age([old_active | {"event": "push", "head_sha": main_sha}], now, set(), main_sha)
    except ValueError as exc:
        assert "manual backfill" in str(exc)
    else:
        raise AssertionError("current-main queued run aged out without alarm")
    assert parse_live_heads([[{"state": "open", "head": {"sha": "b" * 40}}]]) == {"b" * 40}
    try:
        parse_live_heads([[{"state": "open", "head": {"sha": "b" * 40}}] * 100])
    except ValueError as exc:
        assert "pagination" in str(exc)
    else:
        raise AssertionError("unverifiable open-PR pagination was accepted")
    boundary_pending = missed | {"id": 11, "status": "in_progress", "conclusion": None,
                                 "updated_at": "2026-09-10T23:45:00Z"}
    boundary_done = missed | {"id": 11, "status": "completed", "updated_at": "2026-09-10T23:46:00Z"}
    assert [x["run_id"] for x in select(merge_chunks([[boundary_pending], [boundary_done]]), now, 67)["retry"]] == [11]
    assert [x["run_id"] for x in select(merge_chunks([[boundary_done], [boundary_pending]]), now, 67)["retry"]] == [11]
    assert query_window("short", now, parse_time("2026-09-11T00:20:00Z")) == short
    try:
        query_window("short", now, now + dt.timedelta(minutes=1))
    except ValueError:
        pass
    else:
        raise AssertionError("future checkpoint was accepted")
    try:
        query_window("short", now, now - dt.timedelta(hours=49))
    except ValueError as exc:
        assert "manual backfill" in str(exc)
    else:
        raise AssertionError("old checkpoint silently truncated catch-up")
    long_run = base | {"id": 7, "name": "Services CI", "conclusion": "cancelled", "run_attempt": 1,
                       "created_at": "2026-09-10T23:10:00Z"}
    assert parse_time(long_run["created_at"]) >= parse_time(short["created_query"].split("..")[0])
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
        [{"total_count": 1, "workflow_runs": [{"name": "Services CI"}]}],
    ):
        try:
            flatten_pages(invalid)
        except ValueError:
            pass
        else:
            raise AssertionError(f"incomplete API result passed: {invalid!r}")
    # A newly indexed run can change total_count between pages. The first
    # snapshot must fail closed; a complete second snapshot must still select
    # the candidate on its last page rather than silently dropping it.
    background = [base | {"id": 1000 + i, "name": "Services CI", "conclusion": "success", "run_attempt": 1}
                  for i in range(100)]
    candidate = long_run | {"id": 777, "updated_at": "2026-09-11T00:29:00Z"}
    first_snapshot = [{"total_count": 100, "workflow_runs": background},
                      {"total_count": 101, "workflow_runs": [candidate]}]
    second_snapshot = [{"total_count": 101, "workflow_runs": background},
                       {"total_count": 101, "workflow_runs": [candidate]}]
    try:
        flatten_pages(first_snapshot)
    except ValueError as exc:
        assert "changed total_count" in str(exc)
    else:
        raise AssertionError("changing total_count was treated as a complete snapshot")
    recovered = select(flatten_pages(second_snapshot), now, 180)
    assert [x["run_id"] for x in recovered["retry"]] == [777]
    print("SUBJECTS=30")
    print("select-ci-reconcile-candidates self-test: 30 cases passed, 0 failed")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs-file", type=Path)
    parser.add_argument("--now")
    parser.add_argument("--lookback-minutes", type=int, default=90)
    parser.add_argument("--query-mode", choices=("short", "deep"))
    parser.add_argument("--checkpoint", help="creation time of latest successful reconciliation run")
    parser.add_argument("--flatten-pages", type=Path)
    parser.add_argument("--merge-chunks", nargs="+", type=Path)
    parser.add_argument("--active-statuses", action="store_true")
    parser.add_argument("--verify-active-runs", type=Path)
    parser.add_argument("--open-pr-pages", type=Path)
    parser.add_argument("--main-sha")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.active_statuses:
        print("\n".join(ACTIVE_STATUSES))
        return 0
    if args.flatten_pages:
        try:
            print(json.dumps(flatten_pages(json.loads(args.flatten_pages.read_text())), separators=(",", ":")))
        except ValueError as exc:
            print(f"incomplete workflow-run API result: {exc}", file=sys.stderr)
            return 2
        return 0
    if args.merge_chunks:
        try:
            print(json.dumps(merge_chunks([json.loads(path.read_text()) for path in args.merge_chunks]), separators=(",", ":")))
        except (TypeError, ValueError, KeyError) as exc:
            print(f"incomplete workflow-run chunks: {exc}", file=sys.stderr)
            return 2
        return 0
    if args.verify_active_runs:
        if not args.now or not args.open_pr_pages or not args.main_sha:
            parser.error("--now, --open-pr-pages and --main-sha are required with --verify-active-runs")
        try:
            verify_active_age(json.loads(args.verify_active_runs.read_text()), parse_time(args.now),
                              parse_live_heads(json.loads(args.open_pr_pages.read_text())), args.main_sha)
        except (TypeError, ValueError, KeyError) as exc:
            print(f"active workflow-run coverage unverified: {exc}", file=sys.stderr)
            return 2
        return 0
    if args.query_mode:
        if not args.now:
            parser.error("--now is required with --query-mode")
        print(json.dumps(query_window(args.query_mode, parse_time(args.now),
                                      parse_time(args.checkpoint) if args.checkpoint else None), separators=(",", ":")))
        return 0
    if not args.runs_file or not args.now:
        parser.error("--runs-file and --now are required")
    runs = json.loads(args.runs_file.read_text())
    print(json.dumps(select(runs, parse_time(args.now), args.lookback_minutes), separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
