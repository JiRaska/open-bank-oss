#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A compliance row claiming `ok` on a database column must cite a column the code actually uses.

Why (issue #2370)
-----------------
`openbank-admin-ui/src/app/docs/compliance/page.tsx` renders a regulatory conformance matrix from a
hardcoded literal. Nothing verifies any row. The page has been wrong in BOTH directions, which is
what shows the statuses are uncorrelated with the code rather than merely stale.

32 rows marked `ok` cite a snake_case database column as their evidence. That claim is the one
shape here a machine CAN check: a column that exists only in a Flyway migration is evidence of a
schema, not of a control. The page already admits three such columns itself
(`data_retention_until`, `data_sensitivity`, `gdpr_consent_at` — all marked `warn`, "sloupec bez
čtenáře"), which is where this check's known-negatives come from.

What this deliberately does NOT check
-------------------------------------
Rows whose evidence is a judgement — "sca-service implements OTP + FIDO2", "requires legal
documentation". No script settles those, and pretending otherwise would put a green tick on the
exact rows that need a human. They are counted and listed as UNVERIFIABLE, so the page's real
verified fraction is visible instead of implied.

The ORM trap this exists to avoid
---------------------------------
Hibernate/Panache map `pepFlag` to `pep_flag` with no annotation anywhere. Searching the codebase
for the snake_case name alone would report "no reader" for columns that are read on every request —
the check would confidently flag working controls. Every column is therefore searched in BOTH
forms, and a hit in either counts.

Usage:  check-compliance-evidence.py [--enforce] [--selftest]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
PAGE = REPO / "openbank-admin-ui/src/app/docs/compliance/page.tsx"

ROW = re.compile(
    r"\{ req: \[[^\]]*'(?P<req>[^']+)'\], status: '(?P<status>\w+)', note: \['[^']*', '(?P<note>[^']*)'\]"
)
# snake_case with at least one underscore; the fleet's column names all match this
SNAKE = re.compile(r"\b([a-z][a-z0-9]*(?:_[a-z0-9]+){1,4})\b")

# Words that look like columns but are not: table names, index names, module prefixes, file types.
NOT_A_COLUMN = re.compile(
    r"^(openbank_|api_|idx_|uq_|fk_|pk_|no_delete|no_update|src_main|application_yaml)"
    r"|_(service|table|index|yaml|json|md)$"
)


def snake_to_camel(s: str) -> str:
    head, *rest = s.split("_")
    return head + "".join(p.capitalize() for p in rest)


def code_corpus() -> str:
    """Every application source file — NOT migrations, which are what the claim must exceed."""
    files = subprocess.run(
        ["git", "ls-files", "*.kt", "*.ts", "*.tsx", "*.sql"],
        cwd=REPO, capture_output=True, text=True,
    ).stdout.splitlines()
    parts = []
    for f in files:
        # A Flyway migration DECLARES the column. Citing it as evidence of a control is the
        # defect, so migrations are excluded from what counts as a reader.
        if "/db/migration/" in f or "/migration/" in f:
            continue
        # And the docs pages DESCRIBE the system rather than use it — the compliance page's own
        # evidence text names every column it cites. Counting that as a reader made the first
        # version of this check pass all 32 rows on its own prose. The selftest caught it: two
        # columns the page itself admits are unread were "found" in exactly one file, the page.
        if "/src/app/docs/" in f:
            continue
        try:
            parts.append((REPO / f).read_text(encoding="utf-8", errors="ignore"))
        except OSError:
            continue
    return "\n".join(parts)


def rows() -> list[tuple[str, str, str]]:
    text = gatelib.read_text(PAGE)
    return [(m.group("req"), m.group("status"), m.group("note")) for m in ROW.finditer(text)]


def columns_in(note: str) -> set[str]:
    return {c for c in SNAKE.findall(note) if not NOT_A_COLUMN.search(c)}


def analyse(corpus: str):
    ok_with_col, unverifiable, unread = [], [], []
    for req, status, note in rows():
        if status != "ok":
            continue
        cols = columns_in(note)
        if not cols:
            unverifiable.append(req)
            continue
        ok_with_col.append(req)
        missing = [c for c in sorted(cols) if c not in corpus and snake_to_camel(c) not in corpus]
        if missing:
            unread.append((req, missing))
    return ok_with_col, unverifiable, unread


def selftest(corpus: str) -> int:
    """Both directions, from the page's own admissions — a gate that only passes is unfalsified."""
    ok = True
    # KNOWN-NEGATIVE: the page marks these `warn` precisely because nothing reads them.
    for col in ("data_retention_until", "data_sensitivity"):
        if col in corpus or snake_to_camel(col) in corpus:
            print(f"selftest FAIL: {col!r} is admitted unread by the page but the corpus contains it")
            ok = False
    # KNOWN-POSITIVE: a column the fleet certainly uses.
    for col in ("correlation_id", "actor_id"):
        if col not in corpus and snake_to_camel(col) not in corpus:
            print(f"selftest FAIL: {col!r} is certainly used but the corpus scan missed it")
            ok = False
    # The ORM trap itself: snake_case absent, camelCase present must count as READ.
    if snake_to_camel("pep_flag") != "pepFlag":
        print("selftest FAIL: snake_to_camel is wrong")
        ok = False
    if not ok:
        return 1
    print("selftest ok: page-admitted unread columns absent, known-used columns found, ORM mapping applied")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    corpus = code_corpus()
    if len(corpus) < 1_000_000:
        print("::error::check-compliance-evidence: source corpus implausibly small — the scan is "
              "broken, not the fleet clean.")
        return 1
    if args.selftest:
        return selftest(corpus)

    checked, unverifiable, unread = analyse(corpus)
    total_ok = len(checked) + len(unverifiable)
    print(f"check-compliance-evidence: {total_ok} rows claim `ok`.")
    print(f"  {len(checked)} cite a database column — machine-checkable")
    print(f"  {len(unverifiable)} cite a judgement — NOT checkable here, and not counted as verified")
    print(f"  {len(unread)} cite a column no application code reads or writes")
    for req, cols in unread:
        print(f"    UNREAD  {req} -> {', '.join(cols)}")
    if unverifiable:
        print("\n  unverifiable rows (a human decides these; listing them so the verified fraction "
              "is visible rather than implied):")
        for r in unverifiable:
            print(f"    - {r}")
    if not unread:
        return 0
    detail = ("A row marked `ok` cites a column that exists only in a migration. That is evidence of "
              "a schema, not of a control — the page says the bank complies on the strength of a "
              "column nothing uses. Either wire the column, or change the row's status and note.")
    print(f"::{'error' if args.enforce else 'warning'}::check-compliance-evidence: "
          f"{len(unread)} unsupported `ok` row(s). {detail}")
    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
