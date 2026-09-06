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


VERSION_RE = re.compile(r"/(V(?P<num>\d+))__[^/]+\.sql$")


def migration_version(path: str) -> str | None:
    """The Flyway version in a migration path (`V14__x.sql` -> `14`), or None."""
    m = VERSION_RE.search(path)
    return m.group("num") if m else None


def rename_resolves_duplicate(
    old_path: str, new_path: str, status: str, sibling_versions: list[str]
) -> bool:
    """True iff this rename is a pure renumber that unblocks a DUPLICATE Flyway version.

    Renaming a committed migration is normally forbidden — Flyway keys on the version in the
    filename, so it rewrites history. There is exactly one case where forbidding it is wrong,
    and the other gate is the one that says so: when two migrations reach main carrying the
    SAME version, `check-migration-version-regress` tells you to "renumber it to the next free
    version", and this gate then refuses the only remedy it was offered. Two enforced gates
    that contradict each other leave the service unbootable and no legal way out (#5628).

    The exception is deliberately as narrow as it can be made, because each condition is what
    stops it becoming a general licence to edit migrations:

      - `R100` — git's own byte-identical similarity score. The content is untouched, so this
        is a renumber and cannot smuggle in an edit; an R099 rename is still refused.
      - same directory — a service's Flyway history is per-service, so a "collision" only
        means anything within one migration folder.
      - the OLD version genuinely collides at base (appears more than once). Without this,
        any migration could be renumbered at will.
      - the NEW version is free at base. Renaming onto another occupied version just moves
        the collision.

    Not machine-checkable, and therefore not claimed here: whether the migration has already
    been applied to a live database. If it has, renaming it is wrong no matter what this
    returns, and the caller has to know that.
    """
    if status != "R100":
        return False
    if old_path.rsplit("/", 1)[0] != new_path.rsplit("/", 1)[0]:
        return False
    old_v, new_v = migration_version(old_path), migration_version(new_path)
    if old_v is None or new_v is None:
        return False
    return sibling_versions.count(old_v) > 1 and new_v not in sibling_versions


def sibling_versions_at(base: str, path: str) -> list[str]:
    """Every Flyway version in `path`'s migration directory, as it stands at `base`."""
    directory = path.rsplit("/", 1)[0]
    try:
        listing = git("ls-tree", "--name-only", base, f"{directory}/")
    except subprocess.CalledProcessError:
        return []
    return [v for v in (migration_version(p) for p in listing.splitlines()) if v]


def changed_migrations(base: str) -> tuple[list[str], list[str]]:
    """Return (added, modified) migration paths in the diff against `base`."""
    added, modified = [], []
    # --diff-filter distinguishes the two requirements: A -> needs a note, M -> forbidden.
    out = git("diff", "--name-status", "--find-renames=100%", "--diff-filter=AMR", base, "HEAD")
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        status, path = parts[0], parts[-1]
        if not MIGRATION_RE.search(path):
            continue
        # A rename (R) of a migration is an edit of its identity — Flyway keys on the version
        # in the filename, so renaming V3 to V4 is not "adding V4", it is rewriting history.
        # The one exception is a pure renumber that resolves a duplicate version; see
        # rename_resolves_duplicate for why each of its conditions is load-bearing.
        if status.startswith("R") and len(parts) >= 3:
            old_path = parts[1]
            if rename_resolves_duplicate(
                old_path, path, status, sibling_versions_at(base, old_path)
            ):
                print(
                    f"check-db-migration: {old_path} -> {path} permitted: byte-identical "
                    f"renumber resolving a duplicate Flyway version. This is only correct if "
                    f"the migration has NOT been applied to any live database."
                )
                continue
            modified.append(path)
        elif status.startswith("A"):
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

    # --- the duplicate-version rename exception -------------------------------------------
    # Each case removes ONE condition and asserts the exception closes. An exception that
    # cannot be made to say "no" is a hole, not a carve-out.
    D = "openbank-x/src/main/resources/db/migration"

    def rn(label, old, new, status, siblings, want):
        got = rename_resolves_duplicate(old, new, status, siblings)
        if got != want:
            fails.append(f"rename {label}: expected {want}, got {got}")

    rn("a byte-identical renumber off a duplicate version is permitted",
       f"{D}/V14__dedup.sql", f"{D}/V15__dedup.sql", "R100", ["13", "14", "14"], True)
    rn("an edited rename is refused — R099 is not a pure renumber",
       f"{D}/V14__dedup.sql", f"{D}/V15__dedup.sql", "R099", ["13", "14", "14"], False)
    rn("a rename with no collision is refused — nothing to resolve",
       f"{D}/V14__dedup.sql", f"{D}/V15__dedup.sql", "R100", ["13", "14"], False)
    rn("renaming onto an occupied version is refused — it moves the collision",
       f"{D}/V14__dedup.sql", f"{D}/V15__dedup.sql", "R100", ["14", "14", "15"], False)
    rn("a cross-service rename is refused — Flyway history is per service",
       f"{D}/V14__dedup.sql",
       "openbank-y/src/main/resources/db/migration/V15__dedup.sql", "R100", ["14", "14"], False)
    rn("a non-versioned filename is refused rather than guessed at",
       f"{D}/baseline.sql", f"{D}/V15__dedup.sql", "R100", ["14", "14"], False)

    for path, want in (
        (f"{D}/V14__dedup.sql", "14"),
        (f"{D}/V7__x.sql", "7"),
        (f"{D}/README.md", None),
    ):
        got = migration_version(path)
        if got != want:
            fails.append(f"migration_version({path!r}) = {got!r}, expected {want!r}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: rollback note + duplicate-version rename exception are falsifiable (24 cases)")
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
