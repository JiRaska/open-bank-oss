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


def version_of(path: str) -> "int | None":
    m = re.search(r"/V(\d+)__", path)
    return int(m.group(1)) if m else None


def resolves_a_duplicate_version(base: str, old_path: str, new_path: str) -> bool:
    """True when a rename exists ONLY to undo a duplicate version inside one service.

    The rule this narrows is right about the ordinary case and wrong about this one. Its premise
    is "Flyway checksums an APPLIED migration, so renaming it breaks startup" — but a migration
    whose version collides with another in the same directory can never have been applied:
    Flyway refuses to resolve the set (`Found more than one migration with version N`) and the
    service does not boot. There is no checksum to invalidate, so renumbering is the only fix
    and it is safe. Measured on notification-service 2026-09-06 (two V14s merged 13 days apart).

    Deliberately narrow, so it cannot become a way to edit history in general: the OLD name's
    version must still be claimed by a DIFFERENT migration in the same directory at `base`, the
    new version must be free there, and the file's CONTENT must be untouched by the rename.
    """
    old_v, new_v = version_of(old_path), version_of(new_path)
    if old_v is None or new_v is None or old_v == new_v:
        return False
    old_dir, new_dir = old_path.rsplit("/", 1)[0], new_path.rsplit("/", 1)[0]
    if old_dir != new_dir:
        return False
    try:
        siblings = git("ls-tree", "--name-only", f"{base}:{old_dir}").splitlines()
    except Exception:
        return False
    others = [n for n in siblings if n != old_path.rsplit("/", 1)[1]]
    collides = any(version_of(f"{old_dir}/{n}") == old_v for n in others)
    free = all(version_of(f"{old_dir}/{n}") != new_v for n in others)
    if not (collides and free):
        return False
    # Content must be identical — a rename that also edits the SQL is an edit, whatever it
    # renames. `git diff` between the two blobs is empty for a pure rename.
    return git("diff", f"{base}:{old_path}", f"HEAD:{new_path}").strip() == ""


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
        # The one exception is a rename that UNDOES a duplicate version; see
        # resolves_a_duplicate_version for why the checksum premise does not hold there.
        if status.startswith("R") and len(parts) >= 3 and resolves_a_duplicate_version(
            base, parts[1], parts[-1],
        ):
            print(f"check-db-migration: {path} renumbers a DUPLICATE version — permitted, "
                  f"because a colliding migration cannot have been applied and so has no "
                  f"checksum to invalidate.")
            continue
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


def self_test() -> int:
    """Falsify the rollback-note reader and the migration path matcher.

    A Flyway migration is applied ONCE and then checksummed, so after it reaches a live
    database it can never be edited — the rollback plan has to exist before the merge or it
    never exists at all. What a missing note costs is not visible in CI: everything is green,
    the migration applies, and the gap appears only during an incident, at the moment nobody
    has time to design a reversal.

    The reader tolerates a multi-line note, which is where it can go wrong in the direction
    that passes: accept a marker with NO content and every `-- rollback` line, however empty,
    satisfies the rule.
    """
    fails: list[str] = []

    def case(label, text, want):
        got = has_rollback_note(text)
        if got != want:
            fails.append(f"{label}: expected {want}, got {got}")

    # Inline content — the common form.
    case("an inline rollback note counts", "-- rollback: DROP TABLE x;\nCREATE TABLE x();\n", True)
    case("case does not matter", "-- ROLLBACK: DROP TABLE x;\n", True)
    case("a dash separator is tolerated", "-- rollback - DROP TABLE x;\n", True)

    # Multi-line: the marker on one line, the plan on the next comment lines.
    case("a following comment line counts",
         "-- rollback:\n-- DROP TABLE x;\nCREATE TABLE x();\n", True)
    case("a blank comment line between marker and content is tolerated",
         "-- rollback:\n--\n-- DROP TABLE x;\n", True)

    # THE DEFECT this must catch: a marker with nothing behind it. Accepting it turns the rule
    # into "type the word rollback", which is the failure that looks exactly like compliance.
    case("a bare marker with no content does NOT count", "-- rollback:\nCREATE TABLE x();\n", False)
    case("a marker followed only by SQL does not count",
         "-- rollback:\nDROP TABLE x;\n", False)
    case("a marker followed by an empty comment only does not count",
         "-- rollback:\n--\n--   \nCREATE TABLE x();\n", False)
    case("no marker at all", "CREATE TABLE x();\n", False)
    # A non-comment line ENDS the note block: content further down the file is not the note.
    case("content after a non-comment line does not count",
         "-- rollback:\nCREATE TABLE x();\n-- DROP TABLE x;\n", False)

    # --- which files are migrations at all ------------------------------------------------
    for path, want in (
        ("openbank-x/src/main/resources/db/migration/V1__init.sql", True),
        ("openbank-x/src/main/resources/db/migration/nested/V2__more.sql", True),
        # Not migrations: a test fixture, another resources subtree, and a non-.sql file. A
        # matcher that is too eager demands rollback notes of files that never run.
        ("openbank-x/src/test/resources/db/migration/V1__init.sql", False),
        ("openbank-x/src/main/resources/db/seed/V1__init.sql", False),
        ("openbank-x/src/main/resources/db/migration/README.md", False),
    ):
        got = bool(MIGRATION_RE.search(path))
        if got != want:
            fails.append(f"MIGRATION_RE({path!r}) = {got}, expected {want}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: db-migration rollback note is falsifiable (15 cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--base", required=False, help="git sha of the PR base")
    ap.add_argument("--enforce", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if not args.base:
        ap.error("--base is required")

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
