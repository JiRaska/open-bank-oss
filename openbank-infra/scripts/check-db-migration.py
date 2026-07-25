#!/usr/bin/env python3
"""db-migration gate (rules.yaml: change_requirements.db_change, ADR-0144 graduation).

Discharges the two things `db_change.require` asks of a PR that touches a Flyway migration:

  1. "new Flyway migration (forward)" — an ALREADY-COMMITTED migration must not be edited.
     Flyway checksums the whole file, comments included, so any edit to a migration that has
     been applied to a live DB fails startup with `checksum mismatch`. Forward-only, always:
     add V<n+1>, never touch V<n>.

  2. "rollback note in PR" — an ADDED migration must say how to undo itself.

Where the rollback note lives
-----------------------------
In the migration file, as a `-- Rollback:` comment. `blocked_on` in rules.yaml assumed a
PR-BODY-parsing check, but the established practice is in-file (96 of 214 migrations at the
time of writing, and every recent one), and the file IS part of the PR — so an in-file note
satisfies "rollback note in PR" and is strictly better: it lives next to the migration
forever, where the person actually rolling it back is looking, rather than in a PR body they
would have to go find.

The note may be single-line (`-- Rollback: DROP TABLE foo;`) or span following comment lines:

    -- Rollback:
    --   DROP INDEX IF EXISTS uq_documents_idempotency_key;
    --   ALTER TABLE documents DROP COLUMN IF EXISTS idempotency_key;

Both are accepted. A bare `-- Rollback:` with nothing after it is NOT — a note that says
nothing is worse than no note, because it looks like the box is ticked.

Usage:
    check-db-migration.py --base <sha> [--enforce]

    default    advisory — findings are ::warning annotations, exit 0
    --enforce  findings are ::error annotations, exit 1
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys

MIGRATION_RE = re.compile(r"/src/main/resources/db/migration/.+\.sql$")

# `-- Rollback` / `--Rollback:` / `-- ROLLBACK -` … the marker, however it is punctuated.
ROLLBACK_MARKER_RE = re.compile(r"^\s*--\s*rollback\b[:\s-]*(?P<inline>.*)$", re.IGNORECASE)
COMMENT_LINE_RE = re.compile(r"^\s*--\s?(?P<body>.*)$")


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], check=True, capture_output=True, text=True
    ).stdout


def changed_migrations(base: str) -> tuple[list[str], list[str]]:
    """Return (added, modified) migration paths in the diff against `base`."""
    added, modified = [], []
    # --diff-filter distinguishes the two requirements: A -> needs a note, M -> forbidden.
    out = git("diff", "--name-status", "--diff-filter=AMR", base, "HEAD")
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        status, path = parts[0], parts[-1]
        if not MIGRATION_RE.search(path):
            continue
        # A rename (R) of a migration is an edit of its identity — Flyway keys on the version
        # in the filename, so renaming V3 to V4 is not "adding V4", it is rewriting history.
        if status.startswith("A"):
            added.append(path)
        else:
            modified.append(path)
    return added, modified


def has_rollback_note(text: str) -> bool:
    """True iff the file carries a `-- Rollback` note with actual content.

    Content may be on the marker line, or on the comment lines that follow it (the multi-line
    form). Blank comment lines between the marker and the content are tolerated; a non-comment
    line ends the note.
    """
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = ROLLBACK_MARKER_RE.match(line)
        if not m:
            continue
        if m.group("inline").strip():
            return True
        # Multi-line: scan the following comment lines for the first non-empty body.
        for follow in lines[i + 1 :]:
            c = COMMENT_LINE_RE.match(follow)
            if not c:
                break  # a non-comment line ends the note block
            if c.group("body").strip():
                return True
    return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="git sha of the PR base")
    ap.add_argument("--enforce", action="store_true")
    args = ap.parse_args()

    level = "error" if args.enforce else "warning"
    added, modified = changed_migrations(args.base)

    if not added and not modified:
        print("check-db-migration: no Flyway migration touched — nothing to check.")
        return 0

    findings = 0

    for path in modified:
        findings += 1
        print(
            f"::{level} file={path}::This migration is already committed and must not be edited "
            "(rules.yaml: db_change requires a forward migration). Flyway checksums the whole "
            "file — comments included — so once it has been applied to any live DB, an edit "
            "fails startup with `checksum mismatch`. Add a new V<n+1> migration instead."
        )

    for path in added:
        try:
            text = git("show", f"HEAD:{path}")
        except subprocess.CalledProcessError:
            # Added then deleted within the same range — nothing to check.
            continue
        if not has_rollback_note(text):
            findings += 1
            print(
                f"::{level} file={path}::New Flyway migration without a rollback note "
                "(rules.yaml: db_change requires one). Add a `-- Rollback:` comment saying how "
                "to undo this migration — single-line (`-- Rollback: DROP TABLE foo;`) or "
                "spanning the following comment lines. If it is genuinely irreversible, say so "
                "and say what the recovery is instead (e.g. restore from backup)."
            )

    checked = len(added) + len(modified)
    if findings == 0:
        print(
            f"check-db-migration: {checked} migration change(s) checked — "
            "all forward-only, all with a rollback note."
        )
        return 0

    print(
        f"check-db-migration: {checked} migration change(s) checked, {findings} finding(s) above."
    )
    if args.enforce:
        return 1
    print(
        "::notice::check-db-migration is ADVISORY until rules.yaml's db_change "
        "target_enforce_date; it will become a hard gate."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
