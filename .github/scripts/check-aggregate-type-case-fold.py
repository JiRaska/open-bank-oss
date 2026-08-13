#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# aggregate_type is a domain discriminator, not content — compare it folded (issue #4604, the
# #4553 follow-up).
#
# WHY THIS GATE EXISTS. bronze_events.aggregate_type used to survive at ingest exactly as a
# producer spelled it (fixed at the source in #4576), so bronze holds both `ACCOUNT`/`Account` and
# `Transaction`/`Consent`-only spellings for the same domains. Every literal comparison written
# against the unfolded column is one producer rename away from silently matching nothing — which is
# exactly what happened to a Grafana tile that read 0 against a true 4 for its whole life (#4553),
# and to `silver_current_state` grouping one account into two current-state rows that never
# reconcile (fixed via a shared view in #4520, at the reconciliation reader in #4604).
#
# WHAT THIS CHECKS. Every `aggregate_type = '...'`, `aggregate_type != '...'` or
# `aggregate_type IN (...)` comparison across `.sql` files, production `.kt` (src/main only — test
# files legitimately assert the UNFOLDED string does not appear, see the exclusion below) and the
# observability dashboard YAML, must be wrapped in `upper(...)` or `lower(...)`.
#
# WHAT THIS DELIBERATELY DOES NOT FLAG. A bare `SELECT aggregate_type` / `GROUP BY aggregate_type`
# with no comparison — that is a DISPLAY dimension, not a filter, and #4556 left the "Events by
# aggregate type" Grafana panel exactly that way on purpose so the case split stayed visible while
# #4553 was open. Flagging every appearance of the column name would be a text-matching gate that
# cannot tell "compares against" from "shows", which is the trap this repo's own CLAUDE.md names
# for Pact contract tests (grepping the word "contract" instead of the artifact) — the shape here is
# the same: match the SQL operator, not the column name.
#
# RATCHET, NOT ZERO. `.sql` migration files are DDL history, not live state (ADR-0022: no Flyway,
# no checksum). A view is redefined by naming it again with `CREATE OR REPLACE`, which fully
# supersedes every prior definition on both a fresh cluster and an existing one — V4's own header
# already documents why editing an old file's body in place is a no-op. So V2/V3/V4/V6's own
# unfolded text is dead once V7 redefines the same view names, and this gate does not require
# rewriting history to prove that — it baselines the specific occurrences that #4604 confirmed are
# superseded, in `check-aggregate-type-case-fold-baseline.txt`, shrink-only, same shape as
# `deploy-coverage-baseline.txt`.
#
#   python3 .github/scripts/check-aggregate-type-case-fold.py --root .
#   python3 .github/scripts/check-aggregate-type-case-fold.py --self-test

import argparse
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib

EXCLUDE_DIR_PARTS = {".git", "build", "node_modules", ".gradle", "dist", ".next"}

# `aggregate_type = '...'` / `!=` / `<>`, or `aggregate_type IN (...)`. Deliberately does NOT match
# a bare column reference (SELECT/GROUP BY) — see the header for why that must stay unflagged.
COMPARISON_RE = re.compile(r"aggregate_type\s*(=|!=|<>)\s*'|aggregate_type\s+IN\s*\(")
# A fold immediately wrapping the match, allowing for the open-paren and whitespace between it and
# the column name (`upper(aggregate_type)`, `upper( aggregate_type )`).
FOLDED_PREFIX_RE = re.compile(r"(upper|lower)\(\s*$", re.IGNORECASE)

BASELINE = Path(__file__).resolve().parent / "check-aggregate-type-case-fold-baseline.txt"


def find_violations(text: str) -> list[tuple[int, str]]:
    """(line, matched text) for every unfolded comparison. Excludes commented-out SQL lines, same
    convention as check-clickhouse-ddl-in-configmap.py — these files quote their own statements in
    prose, and a commented example must not count as a live comparison."""
    live_lines = {
        i + 1
        for i, line in enumerate(text.splitlines())
        if not line.lstrip().startswith(("--", "#", "//"))
    }
    out = []
    for m in COMPARISON_RE.finditer(text):
        line_no = text.count("\n", 0, m.start()) + 1
        if line_no not in live_lines:
            continue
        prefix = text[max(0, m.start() - 20) : m.start()]
        if FOLDED_PREFIX_RE.search(prefix):
            continue
        out.append((line_no, m.group(0)))
    return out


def scan_file(path: Path) -> list[tuple[int, str]]:
    try:
        text = path.read_text(errors="ignore")
    except OSError:
        return []
    if "aggregate_type" not in text:
        return []
    return find_violations(text)


def subject_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for pattern in ("**/*.sql",):
        files.extend(p for p in root.glob(pattern) if not EXCLUDE_DIR_PARTS & set(p.parts))
    for pattern in ("**/src/main/**/*.kt",):
        files.extend(p for p in root.glob(pattern) if not EXCLUDE_DIR_PARTS & set(p.parts))
    dash_dir = root / "openbank-infra/gitops/components/observability"
    if dash_dir.is_dir():
        files.extend(dash_dir.glob("*.yaml"))
    return sorted(set(files))


def load_baseline() -> set[str]:
    if not BASELINE.is_file():
        return set()
    out = set()
    for line in BASELINE.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            out.add(line)
    return out


def check(root: Path) -> tuple[list[str], list[str], int]:
    """Returns (new_violation_lines, stale_baseline_entries, files_examined)."""
    files = subject_files(root)
    baseline = load_baseline()
    seen_baseline: set[str] = set()
    new_violations: list[str] = []

    for f in files:
        for line_no, matched in scan_file(f):
            rel = str(f.relative_to(root))
            key = f"{rel}:{line_no}"
            if key in baseline:
                seen_baseline.add(key)
                continue
            new_violations.append(f"{key}: unfolded {matched.strip()!r} — wrap in upper(...)/lower(...)")

    stale = sorted(baseline - seen_baseline)
    return (new_violations, stale, len(files))


def self_test() -> int:
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "a.sql").write_text("SELECT 1 FROM t WHERE aggregate_type = 'ACCOUNT'\n")
        (root / "b.sql").write_text("SELECT 1 FROM t WHERE upper(aggregate_type) = 'ACCOUNT'\n")
        (root / "c.sql").write_text("-- SELECT 1 FROM t WHERE aggregate_type = 'ACCOUNT'\n")
        (root / "d.sql").write_text("SELECT aggregate_type, count() FROM t GROUP BY aggregate_type\n")
        (root / "e.sql").write_text("SELECT 1 FROM t WHERE aggregate_type IN ('A', 'B')\n")

        violations, _stale, examined = check(root)

        if examined != 5:
            print(f"SELF-TEST FAIL: expected 5 files examined, got {examined}")
            ok = False
        if not any("a.sql:1" in v for v in violations):
            print("SELF-TEST FAIL: unfolded '=' comparison (a.sql) was not flagged")
            ok = False
        if any("b.sql" in v for v in violations):
            print("SELF-TEST FAIL: a folded comparison (b.sql) was flagged")
            ok = False
        if any("c.sql" in v for v in violations):
            print("SELF-TEST FAIL: a commented-out comparison (c.sql) was flagged")
            ok = False
        if any("d.sql" in v for v in violations):
            print("SELF-TEST FAIL: a bare display GROUP BY (d.sql) was flagged — this must stay unflagged")
            ok = False
        if not any("e.sql:1" in v for v in violations):
            print("SELF-TEST FAIL: unfolded IN(...) comparison (e.sql) was not flagged")
            ok = False

        # Baseline path: a known violation entered in the baseline must not re-flag, and removing it
        # from the tree must report it as stale (the ratchet direction).
        bfile = root / "baseline.txt"
        bfile.write_text("a.sql:1\n")
        global BASELINE
        real_baseline = BASELINE
        BASELINE = bfile
        try:
            violations2, _stale2, _ = check(root)
            if any("a.sql:1" in v for v in violations2):
                print("SELF-TEST FAIL: a baselined violation was still flagged as new")
                ok = False
            (root / "a.sql").write_text("SELECT 1 FROM t WHERE upper(aggregate_type) = 'ACCOUNT'\n")
            _, stale3, _ = check(root)
            if "a.sql:1" not in stale3:
                print("SELF-TEST FAIL: a fixed baseline entry was not reported as stale (ratchet debt)")
                ok = False
        finally:
            BASELINE = real_baseline

        # Absent-subject case: no matching files at all must not read as clean.
        empty_root = root / "empty"
        empty_root.mkdir()
        _, _, examined_empty = check(empty_root)
        if examined_empty != 0:
            print("SELF-TEST FAIL: unexpected files examined in an empty tree")
            ok = False

    print("SELF-TEST PASS" if ok else "SELF-TEST FAILED")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = Path(args.root)
    violations, stale, examined = check(root)
    gatelib.subjects(examined, "files scanned for aggregate_type comparisons")

    rc = 0
    if violations:
        print(f"::error::{len(violations)} unfolded aggregate_type comparison(s):")
        for v in violations:
            print(f"::error::{v}")
        print(
            "\nFix: wrap the comparison in upper(aggregate_type) = '...' (or lower(), matching the "
            "literal's case). If the occurrence is DEAD — a superseded migration file's own text, "
            "not what a live CREATE OR REPLACE actually runs — add it to "
            f"{BASELINE.name} with a comment saying what supersedes it."
        )
        rc = 1
    if stale:
        print(f"\n::error::{len(stale)} baseline entr(y/ies) no longer violating — the ratchet only tightens:")
        for s in stale:
            print(f"::error::  {s}")
        rc = 1

    if rc == 0:
        print(f"OK: no unfolded aggregate_type comparisons ({examined} file(s) scanned).")
    return rc


if __name__ == "__main__":
    sys.exit(main())
