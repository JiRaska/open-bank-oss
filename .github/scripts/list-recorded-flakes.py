#!/usr/bin/env python3
"""Thin read layer over the flake records `record-rerun-flake.py` writes (#4878).

Not a dashboard, a gate, or an alert -- deliberately. #4878 asks only for the ABILITY to answer
"which tests fail intermittently, and how often"; deciding what to threshold or alert on is
explicit follow-up work once there is data to look at. This is the smallest thing that turns
the tracking issue's comments back into a table a human (or a later tool) can read.

Each flake is recorded as one comment on the open "Flaky test observations" issue
(label `flaky-test`), with a human table plus a `<!-- flake-record:{json} -->` payload
`record-rerun-flake.py`'s `render_comment` writes. This reads those payloads back out.

USAGE
    list-recorded-flakes.py --issue 1234                 # live: gh issue view --json comments
    list-recorded-flakes.py --comments-file bodies.json   # offline: a JSON list of comment bodies
    list-recorded-flakes.py --self-test
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

# `record-rerun-flake.py`'s filename is not a valid Python module name (hyphens), and this repo's
# existing cross-script-import convention (`check-advisory-finding-staleness.py` -> `gatelib.py`)
# is for a shared underscore-named module, not for reaching into another hyphenated CLI script.
# The function is 10 lines and has its own self-test on both sides of the round-trip (write side
# in record-rerun-flake.py, read side here) -- duplicating it is cheaper and more robust than an
# importlib.util.spec_from_file_location workaround for one function.
RECORD_MARKER = "<!-- flake-record:"

# #12296: Services CI run 37614600361 attempt 1 refused autonomous PR admission
# at 4/3; attempt 2 passed at 3/3. Preserve the original #5285 comment as evidence,
# but do not count its all-green aggregate row as a test/job flake.
HISTORICAL_ADMISSION_REFUSAL = (37614600361, 1, 2, "all-green")


def is_historical_admission_refusal(record: dict) -> bool:
    return (
        record.get("run_id"), record.get("prev_attempt"),
        record.get("final_attempt"), record.get("job")
    ) == HISTORICAL_ADMISSION_REFUSAL


def extract_records_from_comments(bodies: list[str]) -> list[dict]:
    """The read-side of `record_rerun_flake.render_comment` -- pulls the JSON payload back out."""
    out: list[dict] = []
    for body in bodies:
        idx = body.find(RECORD_MARKER)
        if idx == -1:
            continue
        start = idx + len(RECORD_MARKER)
        end = body.find("-->", start)
        if end == -1:
            continue
        raw = body[start:end].strip()
        try:
            out.append(json.loads(raw))
        except json.JSONDecodeError:
            continue
    return out


def fetch_comment_bodies(issue: int) -> list[str]:
    proc = subprocess.run(
        ["gh", "issue", "view", str(issue), "--json", "comments"],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"gh issue view {issue} failed: {proc.stderr.strip()[:400]}")
    doc = json.loads(proc.stdout)
    return [c.get("body", "") for c in doc.get("comments", [])]


def summarize(records: list[dict]) -> str:
    if not records:
        return "No flakes recorded yet."

    by_test: Counter[str] = Counter()
    by_service: Counter[str] = Counter()
    for r in records:
        service = r.get("service") or r.get("job") or "n/a"
        by_service[service] += 1
        for t in r.get("tests") or []:
            by_test[f"{t['classname']}#{t['name']}"] += 1
        if not r.get("tests"):
            by_test[f"(job-level only) {r.get('job')}"] += 1

    lines = [f"{len(records)} flake record(s) total.", "", "By service:"]
    for service, count in by_service.most_common():
        lines.append(f"  {count:4d}  {service}")
    lines.append("")
    lines.append("By test:")
    for test, count in by_test.most_common():
        lines.append(f"  {count:4d}  {test}")
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--issue", type=int)
    parser.add_argument("--comments-file", type=Path)
    parser.add_argument("--json", action="store_true", help="print raw records as JSON instead of a summary")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    if args.issue is not None:
        bodies = fetch_comment_bodies(args.issue)
    elif args.comments_file is not None:
        bodies = json.loads(args.comments_file.read_text(encoding="utf-8"))
    else:
        parser.error("one of --issue or --comments-file is required (or --self-test)")
        return 2

    records = [r for r in extract_records_from_comments(bodies) if not is_historical_admission_refusal(r)]
    if args.json:
        print(json.dumps(records, indent=2))
    else:
        print(summarize(records))
    return 0


def self_test() -> int:
    failures: list[str] = []

    def check(label: str, cond: bool) -> None:
        if not cond:
            failures.append(label)
        print(f"  [{'ok ' if cond else 'FAIL'}] {label}")

    r1 = {
        "workflow": "Services CI",
        "job": "build (openbank-transaction-service)",
        "service": "openbank-transaction-service",
        "run_id": 1,
        "run_url": "u1",
        "prev_attempt": 1,
        "final_attempt": 2,
        "head_sha": "a",
        "head_branch": "main",
        "detected_at": "t1",
        "tests": [{"classname": "com.openbank.tx.OutboxClaimIT", "name": "a stale row is reclaimed", "kind": "failure", "message": "m"}],
    }
    r2 = {
        "workflow": "Services CI",
        "job": "build (openbank-standing-order-service)",
        "service": "openbank-standing-order-service",
        "run_id": 2,
        "run_url": "u2",
        "prev_attempt": 1,
        "final_attempt": 2,
        "head_sha": "b",
        "head_branch": "main",
        "detected_at": "t2",
        "tests": [{"classname": "com.openbank.so.OutboxClaimIT", "name": "a stale row is reclaimed", "kind": "failure", "message": "m"}],
    }
    bodies = [
        f"### Flake recorded\n<!-- flake-record:{json.dumps(r1)} -->\n",
        "a human reply with no marker at all",
        f"### Flake recorded\n<!-- flake-record:{json.dumps(r2)} -->\n",
    ]

    records = extract_records_from_comments(bodies)
    check("recovers exactly the two machine-readable records, skipping the human comment", len(records) == 2)

    summary = summarize(records)
    check("summary counts 2 total", "2 flake record(s) total" in summary)
    check("summary lists both services", "openbank-transaction-service" in summary and "openbank-standing-order-service" in summary)

    check("summarize on an empty list says so rather than crashing", summarize([]) == "No flakes recorded yet.")
    check("proven historical admission refusal is excluded by exact identity",
          is_historical_admission_refusal({"run_id": 37614600361, "prev_attempt": 1,
                                           "final_attempt": 2, "job": "all-green"})
          and not is_historical_admission_refusal(r1))

    historical = {**r1, "run_id": 37614600361, "job": "all-green", "service": None, "tests": []}
    unproven = {**historical, "run_id": 37614600362}
    with tempfile.TemporaryDirectory() as temp_dir:
        comments_file = Path(temp_dir) / "comments.json"

        def run_read_side(json_output: bool) -> str:
            command = [sys.executable, __file__, "--comments-file", str(comments_file)]
            if json_output:
                command.append("--json")
            result = subprocess.run(command, capture_output=True, text=True, check=True)
            return result.stdout

        comments_file.write_text(json.dumps([
            f"<!-- flake-record:{json.dumps(historical)} -->",
            f"<!-- flake-record:{json.dumps(r1)} -->",
        ]), encoding="utf-8")
        check("summary counts one genuine flake and excludes the historical refusal",
              "1 flake record(s) total" in run_read_side(False))
        check("JSON includes only the genuine flake",
              json.loads(run_read_side(True)) == [r1])

        comments_file.write_text(json.dumps([
            f"<!-- flake-record:{json.dumps(historical)} -->",
            f"<!-- flake-record:{json.dumps(r1)} -->",
            f"<!-- flake-record:{json.dumps(unproven)} -->",
        ]), encoding="utf-8")
        check("an aggregate failure without the exact historical evidence remains counted",
              "2 flake record(s) total" in run_read_side(False)
              and json.loads(run_read_side(True)) == [r1, unproven])

    print()
    if failures:
        print(f"SELF-TEST FAILED ({len(failures)}):")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("SELF-TEST PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
