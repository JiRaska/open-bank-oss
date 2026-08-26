#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Quoted mixed-case Hibernate sequence guard (issue #6480).
#
# THE MECHANISM
#   `CREATE SEQUENCE "party_payees_SEQ"` keeps its case, because Postgres preserves the case of a
#   QUOTED identifier. Hibernate's pooled-id allocator then emits
#   `select nextval('party_payees_SEQ')` — the identifier sits UNQUOTED inside a string literal, so
#   Postgres folds it to lower case, looks up `party_payees_seq`, and finds nothing:
#
#       ERROR: relation "party_payees_seq" does not exist (42P01)
#
#   Every insert into that table then fails at id allocation. The migration applies cleanly, the
#   pod starts, the health probes pass — the failure only appears on the first write.
#
# WHY A GATE AND NOT ANOTHER FIX
#   `openbank-party-service` has made this exact mistake three times and fixed it twice:
#   V5 created three quoted sequences, V6 dropped and recreated them lower-case, then V16 and V18
#   reintroduced it, and V19 (#6467) fixed it again. V16 and V18 were both written after V6 had
#   documented the failure and the fix in the same directory. A defect a service reintroduces after
#   fixing it is a defect review does not see.
#
# WHY A RATCHET, NOT A FLAT FAIL
#   The offending statements are in migrations that have ALREADY BEEN APPLIED, and Flyway checksums
#   the whole file — editing one is a `checksum mismatch` startup failure. So the historical
#   statements CANNOT be removed; the live defect is instead repaired forward by V6 and V19. Today's
#   set is baselined and CI stays green; a NEW occurrence fails. A baseline entry that no longer
#   exists is also reported, so the list cannot rot in either direction.
#
# NEGATIVE CONTROL (why an empty result elsewhere is a finding, not a broken probe)
#   36 services carry `CREATE SEQUENCE` migrations and 54 migration files contain the statement, but
#   only party-service has ever used the quoted form. The same scan restricted to the lower-case
#   form hits the rest of the fleet, so the absence is real. `--self-test` re-proves this by feeding
#   the matcher a case it MUST flag and a case it MUST NOT.
#
# EXIT CODES
#   0  no new occurrences, no stale baseline entries
#   1  a new quoted mixed-case CREATE SEQUENCE, or a baseline entry that no longer exists
#   2  the check could not run (tree not found), or the self-test failed. Never conflated with 0.
#
# Run:  python3 .github/scripts/check-quoted-sequence-names.py [--root .] [--self-test]

import argparse
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

# `CREATE SEQUENCE [IF NOT EXISTS] "<ident>"` where <ident> contains at least one upper-case letter.
# An all-lower-case quoted name is harmless (folding is a no-op), so it is deliberately not matched.
# NOTE: re.IGNORECASE must NOT be used globally here. It would make `[A-Z]` match a lower-case
# letter too, rendering the "contains an upper-case letter" condition vacuous — the gate would then
# flag every quoted lower-case name, which is harmless SQL. The keywords carry their own inline
# `(?i:...)` instead, so only they are case-insensitive. The self-test's
# `"party_payees_seq"` must-not-flag case is what catches this if it is ever reintroduced.
SEQ_RE = re.compile(
    r'(?i:CREATE)\s+(?i:SEQUENCE)\s+(?:(?i:IF\s+NOT\s+EXISTS)\s+)?"([A-Za-z0-9_]*[A-Z][A-Za-z0-9_]*)"'
)

# Every occurrence as of #6480. Each entry is (migration path, sequence name). These live in
# migrations that have already been applied and so can never be edited — V6 and V19 repair them
# forward. Do not add to this list: a new entry means the defect was reintroduced.
BASELINE = {
    ("openbank-party-service/src/main/resources/db/migration/V5__hibernate_sequences.sql", "parties_SEQ"),
    ("openbank-party-service/src/main/resources/db/migration/V5__hibernate_sequences.sql", "party_documents_SEQ"),
    ("openbank-party-service/src/main/resources/db/migration/V5__hibernate_sequences.sql", "party_outbox_SEQ"),
    (
        "openbank-party-service/src/main/resources/db/migration/V16__marketing_consent_tracking.sql",
        "party_marketing_consent_SEQ",
    ),
    ("openbank-party-service/src/main/resources/db/migration/V18__party_payees.sql", "party_payees_SEQ"),
}


def strip_sql_comments(text: str) -> str:
    """Blank out `--` line comments and /* */ block comments, preserving line structure.

    V19 quotes the two offending statements in a `--` comment explaining what it repairs. Matching
    those would make the fix itself fail the gate, which is the opposite of the intent.
    """
    text = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text, flags=re.DOTALL)
    return re.sub(r"--[^\n]*", "", text)


def scan(root: pathlib.Path):
    """Return (list of migration files examined, {(path, sequence name): line number})."""
    files = sorted(root.glob("*/src/main/resources/db/migration/*.sql"))
    found = {}
    for path in files:
        rel = path.relative_to(root).as_posix()
        cleaned = strip_sql_comments(path.read_text(encoding="utf-8"))
        for match in SEQ_RE.finditer(cleaned):
            line = cleaned.count("\n", 0, match.start()) + 1
            found[(rel, match.group(1))] = line
    return files, found


def self_test() -> int:
    """Prove the matcher by what it REJECTS, not by what it prints."""
    must_flag = [
        'CREATE SEQUENCE "party_payees_SEQ" INCREMENT BY 50;',
        'CREATE SEQUENCE IF NOT EXISTS "party_marketing_consent_SEQ" INCREMENT BY 50;',
        'create sequence if not exists "Foo_Seq";',
        'CREATE   SEQUENCE\n  IF NOT EXISTS   "parties_SEQ";',
    ]
    must_not_flag = [
        "CREATE SEQUENCE IF NOT EXISTS party_payees_seq INCREMENT BY 50;",  # unquoted: folds, fine
        'CREATE SEQUENCE IF NOT EXISTS "party_payees_seq";',  # quoted but lower: folding is a no-op
        "ALTER SEQUENCE party_payees_seq RESTART WITH 100;",
        "CREATE TABLE \"Party_SEQ_audit\" (id bigint);",  # not a CREATE SEQUENCE
    ]
    failures = []
    for sql in must_flag:
        if not SEQ_RE.search(strip_sql_comments(sql)):
            failures.append(f"MUST FLAG but did not: {sql!r}")
    for sql in must_not_flag:
        if SEQ_RE.search(strip_sql_comments(sql)):
            failures.append(f"MUST NOT FLAG but did: {sql!r}")
    # The comment-stripping is load-bearing: V19 quotes both offending statements in `--` comments.
    commented = '-- CREATE SEQUENCE IF NOT EXISTS "party_payees_SEQ" INCREMENT BY 50;'
    if SEQ_RE.search(strip_sql_comments(commented)):
        failures.append("MUST NOT FLAG a commented-out statement (V19 quotes both to explain the fix)")
    block = '/* CREATE SEQUENCE "party_payees_SEQ"; */'
    if SEQ_RE.search(strip_sql_comments(block)):
        failures.append("MUST NOT FLAG a block-commented statement")
    if failures:
        print("SELF-TEST FAILED:", file=sys.stderr)
        for f in failures:
            print(f"  {f}", file=sys.stderr)
        return 2
    print(f"self-test OK ({len(must_flag)} must-flag, {len(must_not_flag) + 2} must-not-flag cases)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    if not root.is_dir():
        print(f"ERROR: --root {root} is not a directory", file=sys.stderr)
        return 2

    files, found = scan(root)

    # The corpus is every Flyway migration in the tree, not just the ones that match. A moved
    # source root, a renamed directory or a changed glob would otherwise turn this gate into a
    # green no-op that examines nothing and therefore passes everything.
    gatelib.subjects(len(files), "Flyway migration files")

    new = sorted(k for k in found if k not in BASELINE)
    stale = sorted(BASELINE - set(found))

    if new:
        print("FAIL: quoted mixed-case CREATE SEQUENCE (Hibernate can never find it):", file=sys.stderr)
        for path, name in new:
            print(f'  {path}:{found[(path, name)]}  "{name}"', file=sys.stderr)
        print(
            '\n  Hibernate emits `select nextval(\'<name>\')` unquoted, which Postgres folds to lower\n'
            "  case, so it looks up the lower-case name and every insert fails 42P01.\n"
            "  Write the sequence name UNQUOTED and all lower-case:\n"
            "    CREATE SEQUENCE IF NOT EXISTS <table>_seq INCREMENT BY 50;\n"
            "  See issue #6480 and openbank-party-service V6/V19, which repair this forward.",
            file=sys.stderr,
        )
    if stale:
        print("FAIL: baseline entries that no longer exist — remove them:", file=sys.stderr)
        for path, name in stale:
            print(f'  {path}  "{name}"', file=sys.stderr)

    if new or stale:
        return 1

    print(f"OK: no new quoted mixed-case sequences ({len(found)} baselined occurrence(s) unchanged)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
