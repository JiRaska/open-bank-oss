#!/usr/bin/env python3
"""Record a flake at the one moment its evidence exists: a re-run turning a failure green (#4878).

WHY THIS EXISTS
----------------
A re-run flips a run's `conclusion` away from `failure`, so any later query over run
conclusions structurally cannot see a flake that someone re-ran green -- which is the entire
population of interest. `auto-retry-cancelled.yml` already re-runs some failed runs (issue
#2330/#2841); this script is invoked from that same workflow, at the moment it observes the
RESULT of a re-run, to write the fact down before it is lost.

THE DISTINCTION THIS SCRIPT MUST NOT COLLAPSE
-----------------------------------------------
`auto-retry-cancelled.yml` re-runs a job for one of two reasons, and only one of them is a
flake candidate:

  1. SPOT-KILL SIGNATURE: the job's `conclusion` is `failure`, it has at least one `cancelled`
     step, and NO `failure` step. That is a runner reclaim, not the test disagreeing with
     itself -- explained by infrastructure, not worth recording as a flake.
  2. GENUINE FAILURE: the job has a `failure` step (with or without an accompanying `cancelled`
     step from an unrelated sibling -- "mixed runs are the normal case", per that workflow's own
     header). If THIS shape of job later succeeds on a later attempt of the same run, that is
     exactly "was genuinely red, now green" -- record it.

`has_spot_kill_signature` below is the same predicate `auto-retry-cancelled.yml` uses inline
(bash, `select(.conclusion == "failure") | select(cancelled>0) | select(failure==0)`); kept as
one Python predicate here so the two never drift silently apart.

WHAT GETS WRITTEN, AND WHERE
------------------------------
Not a new dashboard, gate, or alert (out of scope for #4878 step 2 -- that is deferred to a
follow-up once there is data to look at). Reuses the pattern this repo already has for
cross-run bookkeeping that isn't a metric: `auto-retry-cancelled.yml`'s own `raise-issue` job
and `main-red-watch.yml` both open-or-refresh a single tracking issue via
`gh issue list --search "$TITLE in:title"`. This script renders a comment body for that same
idiom -- one open "Flaky test observations" issue (label `flaky-test`, added to labels.yml),
refreshed with one comment per detected flake. Each comment carries a human-readable table row
AND a machine-readable JSON payload in an HTML comment, so `list-recorded-flakes.py` can read
the population back out without re-parsing markdown prose.

USAGE (invoked from the workflow; every subcommand is offline and side-effect-free)
    record-rerun-flake.py classify   --jobs-file prev-jobs.json
    record-rerun-flake.py parse-junit --path <dir-or-file>
    record-rerun-flake.py render     --record run.json --tests tests.json --job job.json
    record-rerun-flake.py --self-test
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

RECORD_MARKER = "<!-- flake-record:"


# --------------------------------------------------------------------------------------------
# Pure classification -- mirrors the bash predicate in auto-retry-cancelled.yml on purpose
# --------------------------------------------------------------------------------------------


def has_spot_kill_signature(job: dict) -> bool:
    """True iff this job's failure is explained by a runner reclaim, not a real test result.

    Same shape as the `killed` jq filter in auto-retry-cancelled.yml: `conclusion == failure`,
    at least one `cancelled` step, and zero `failure` steps.
    """
    if (job.get("conclusion") or "").lower() != "failure":
        return False
    steps = job.get("steps") or []
    cancelled = sum(1 for s in steps if (s.get("conclusion") or "").lower() == "cancelled")
    failed = sum(1 for s in steps if (s.get("conclusion") or "").lower() == "failure")
    return cancelled > 0 and failed == 0


def find_flake_candidates(prev_jobs: list[dict]) -> list[dict]:
    """Jobs from the PRIOR attempt that failed for a real reason (not a spot kill).

    Called once the run's FINAL attempt has concluded `success` -- by construction, every job
    returned here went from a genuine failure to a green run. That transition is the flake
    signal; this function only identifies which prior-attempt jobs qualify.
    """
    return [
        j
        for j in prev_jobs
        if (j.get("conclusion") or "").lower() == "failure" and not has_spot_kill_signature(j)
    ]


SERVICE_RE = re.compile(r"build \(([^)]+)\)")


def extract_service(job_name: str) -> str | None:
    """`build (openbank-transaction-service)` -> `openbank-transaction-service`.

    Only Services CI job names carry this shape (the JUnit-XML-producing lane from #4983); a
    Security-scan or Dependency-submission job name returns None, and the caller records the
    flake at job granularity without a per-test breakdown rather than guessing.
    """
    m = SERVICE_RE.search(job_name or "")
    return m.group(1) if m else None


# --------------------------------------------------------------------------------------------
# JUnit XML -- read only, tolerant of a directory of files (an extracted artifact zip)
# --------------------------------------------------------------------------------------------


def parse_junit_failures(xml_text: str) -> list[dict]:
    """testcase elements carrying a <failure> or <error> child -> [{classname, name, message}]."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []
    out: list[dict] = []
    for tc in root.iter("testcase"):
        fail = tc.find("failure")
        err = tc.find("error")
        node = fail if fail is not None else err
        if node is None:
            continue
        message = (node.get("message") or "").strip()
        out.append(
            {
                "classname": tc.get("classname") or "",
                "name": tc.get("name") or "",
                "kind": "failure" if fail is not None else "error",
                "message": message[:200],
            }
        )
    return out


def parse_junit_path(path: Path) -> list[dict]:
    """A single .xml file or a directory (an extracted artifact download) -> failing testcases."""
    if path.is_file():
        return parse_junit_failures(path.read_text(encoding="utf-8", errors="replace"))
    out: list[dict] = []
    for f in sorted(path.rglob("*.xml")):
        out.extend(parse_junit_failures(f.read_text(encoding="utf-8", errors="replace")))
    return out


def build_records(candidates: list[dict], context: dict, detected_at: str) -> list[dict]:
    """Flake candidate jobs + run context -> one record per candidate, `tests` empty for now.

    Pure: no artifact download happens here. The workflow step fills `tests` in afterwards
    (via `merge_tests`) once it has tried to fetch and parse the matching JUnit XML artifact --
    that part needs `gh run download`, which this function deliberately does not do, so it stays
    testable offline like everything else here.
    """
    return [
        {
            "workflow": context["workflow"],
            "job": job["name"],
            "service": extract_service(job["name"]),
            "run_id": context["run_id"],
            "run_url": context["run_url"],
            "prev_attempt": context["prev_attempt"],
            "final_attempt": context["final_attempt"],
            "head_sha": context.get("head_sha"),
            "head_branch": context.get("head_branch"),
            "detected_at": detected_at,
            "tests": [],
        }
        for job in candidates
    ]


def merge_tests(record: dict, tests: list[dict]) -> dict:
    """Attach parsed JUnit failures to a record built by `build_records`. Pure, order-preserving."""
    merged = dict(record)
    merged["tests"] = tests
    return merged


# --------------------------------------------------------------------------------------------
# Rendering -- one issue comment per detected flake, human table + machine JSON
# --------------------------------------------------------------------------------------------


def render_comment(record: dict) -> str:
    tests = record.get("tests") or []
    if tests:
        test_lines = "\n".join(f"  - `{t['classname']}#{t['name']}` ({t['kind']})" for t in tests)
    else:
        test_lines = "  - (no JUnit XML artifact found for this job/attempt -- job-level only)"

    body = (
        f"### Flake recorded: `{record['job']}`\n\n"
        f"| | |\n|---|---|\n"
        f"| workflow | `{record['workflow']}` |\n"
        f"| service | `{record.get('service') or 'n/a'}` |\n"
        f"| run | [{record['run_id']}]({record['run_url']}) |\n"
        f"| failed at attempt | {record['prev_attempt']} |\n"
        f"| green at attempt | {record['final_attempt']} |\n"
        f"| head | `{(record.get('head_sha') or '')[:9]}` on `{record.get('head_branch') or ''}` |\n"
        f"| detected | {record['detected_at']} |\n\n"
        f"Tests found failing at the prior attempt:\n{test_lines}\n\n"
        f"{RECORD_MARKER}{json.dumps(record, sort_keys=True)} -->\n"
    )
    return body


def extract_records_from_comments(bodies: list[str]) -> list[dict]:
    """The read-side of `render_comment` -- pulls the JSON payload back out of each comment."""
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


# --------------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    sub = parser.add_subparsers(dest="cmd")

    p_classify = sub.add_parser("classify")
    p_classify.add_argument("--jobs-file", required=True, type=Path)

    p_junit = sub.add_parser("parse-junit")
    p_junit.add_argument("--path", required=True, type=Path)

    p_render = sub.add_parser("render")
    p_render.add_argument("--record", required=True, type=Path)

    p_build = sub.add_parser("build-records")
    p_build.add_argument("--candidates", required=True, type=Path)
    p_build.add_argument("--context", required=True, type=Path)
    p_build.add_argument("--detected-at", required=True)

    p_merge = sub.add_parser("merge-tests")
    p_merge.add_argument("--record", required=True, type=Path)
    p_merge.add_argument("--tests", required=True, type=Path)

    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    if args.cmd == "classify":
        jobs = json.loads(args.jobs_file.read_text(encoding="utf-8"))
        print(json.dumps(find_flake_candidates(jobs), indent=2))
        return 0

    if args.cmd == "parse-junit":
        print(json.dumps(parse_junit_path(args.path), indent=2))
        return 0

    if args.cmd == "render":
        record = json.loads(args.record.read_text(encoding="utf-8"))
        print(render_comment(record))
        return 0

    if args.cmd == "build-records":
        candidates = json.loads(args.candidates.read_text(encoding="utf-8"))
        context = json.loads(args.context.read_text(encoding="utf-8"))
        print(json.dumps(build_records(candidates, context, args.detected_at), indent=2))
        return 0

    if args.cmd == "merge-tests":
        record = json.loads(args.record.read_text(encoding="utf-8"))
        tests = json.loads(args.tests.read_text(encoding="utf-8"))
        print(json.dumps(merge_tests(record, tests), indent=2))
        return 0

    parser.print_help()
    return 2


# --------------------------------------------------------------------------------------------
# Self-test
# --------------------------------------------------------------------------------------------


def _job(name: str, conclusion: str, steps: list[tuple[str, str]]) -> dict:
    return {"name": name, "conclusion": conclusion, "steps": [{"name": n, "conclusion": c} for n, c in steps]}


def self_test() -> int:
    failures: list[str] = []

    def check(label: str, cond: bool) -> None:
        if not cond:
            failures.append(label)
        print(f"  [{'ok ' if cond else 'FAIL'}] {label}")

    print("has_spot_kill_signature / find_flake_candidates")
    spot_killed = _job(
        "build (openbank-billing-service)",
        "failure",
        [("Set up job", "success"), ("Test", "cancelled")],
    )
    genuine = _job(
        "build (openbank-transaction-service)",
        "failure",
        [("Set up job", "success"), ("Test", "failure")],
    )
    mixed_but_still_genuine = _job(
        "build (openbank-standing-order-service)",
        "failure",
        [("Set up job", "success"), ("Test", "failure"), ("Cleanup", "cancelled")],
    )
    green = _job("build (openbank-ledger-service)", "success", [("Test", "success")])

    check("a pure spot-kill (cancelled, no failure step) is NOT a flake candidate",
          not has_spot_kill_signature(genuine) and has_spot_kill_signature(spot_killed))
    check("a genuine failure step, even alongside an unrelated cancelled step, IS a candidate",
          has_spot_kill_signature(mixed_but_still_genuine) is False)
    check("a green job is never a candidate", not has_spot_kill_signature(green))

    candidates = find_flake_candidates([spot_killed, genuine, mixed_but_still_genuine, green])
    names = {c["name"] for c in candidates}
    check(
        "find_flake_candidates keeps only the genuinely-failed jobs, dropping the spot kill and "
        "the green job",
        names == {genuine["name"], mixed_but_still_genuine["name"]},
    )

    print("extract_service")
    check(
        "extracts the service from a Services CI job name",
        extract_service("build (openbank-transaction-service)") == "openbank-transaction-service",
    )
    check("returns None for a job name with no build(...) shape", extract_service("Trivy fs scan") is None)

    print("parse_junit_failures")
    xml = """<?xml version="1.0"?>
<testsuite name="s">
  <testcase classname="com.openbank.tx.OutboxClaimIT" name="a stale row is reclaimed">
    <failure message="expected DISPATCHED but was DISPATCHING">stack...</failure>
  </testcase>
  <testcase classname="com.openbank.tx.OutboxClaimIT" name="a fresh row is untouched"/>
  <testcase classname="com.openbank.tx.OtherIT" name="boom">
    <error message="connection reset"/>
  </testcase>
</testsuite>
"""
    parsed = parse_junit_failures(xml)
    check("finds exactly the two failing/erroring testcases, not the passing one", len(parsed) == 2)
    check(
        "captures classname, name, and kind for a <failure>",
        parsed[0] == {
            "classname": "com.openbank.tx.OutboxClaimIT",
            "name": "a stale row is reclaimed",
            "kind": "failure",
            "message": "expected DISPATCHED but was DISPATCHING",
        },
    )
    check("captures an <error> as kind=error", parsed[1]["kind"] == "error")
    check("malformed XML returns an empty list rather than raising", parse_junit_failures("<not-xml") == [])

    print("build_records / merge_tests")
    context = {
        "workflow": "Services CI",
        "run_id": 999,
        "run_url": "https://example/run/999",
        "prev_attempt": 1,
        "final_attempt": 2,
        "head_sha": "deadbeef01234567",
        "head_branch": "main",
    }
    records = build_records([genuine, mixed_but_still_genuine], context, "2026-08-16T00:00:00Z")
    check("one record per candidate job", len(records) == 2)
    check(
        "each record carries its job's extracted service and the shared run context",
        records[0]["service"] == "openbank-transaction-service"
        and records[0]["run_id"] == 999
        and records[0]["prev_attempt"] == 1
        and records[0]["final_attempt"] == 2,
    )
    check("tests starts empty -- filled in later by merge_tests, not here", records[0]["tests"] == [])

    merged = merge_tests(records[0], [{"classname": "C", "name": "t", "kind": "failure", "message": "m"}])
    check("merge_tests attaches tests without mutating other fields",
          merged["service"] == records[0]["service"] and len(merged["tests"]) == 1)
    check("merge_tests does not mutate its input record", records[0]["tests"] == [])

    print("render_comment / extract_records_from_comments round-trip")
    record = {
        "workflow": "Services CI",
        "job": "build (openbank-transaction-service)",
        "service": "openbank-transaction-service",
        "run_id": 123,
        "run_url": "https://example/run/123",
        "prev_attempt": 1,
        "final_attempt": 2,
        "head_sha": "abcdef0123456789",
        "head_branch": "main",
        "detected_at": "2026-08-16T00:00:00Z",
        "tests": parsed[:1],
    }
    comment = render_comment(record)
    check("rendered comment names the job", record["job"] in comment)
    check("rendered comment embeds a machine-readable JSON payload", RECORD_MARKER in comment)
    roundtrip = extract_records_from_comments([comment, "an unrelated human comment with no marker"])
    check("exactly one record recovered from two comments, one with no marker", len(roundtrip) == 1)
    check("the recovered record matches what was rendered", roundtrip[0] == record)

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
