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


VERSION_RE = re.compile(r"/V(?P<version>\d+)__[^/]+\.sql$")


def migration_version(path: str) -> int | None:
    """The Flyway version encoded in a migration's filename, or None if it carries none."""
    m = VERSION_RE.search(path)
    return int(m.group("version")) if m else None


def service_of(path: str) -> str:
    """The owning service directory — the scope a Flyway version has to be unique within."""
    return path.split("/src/main/resources/db/migration/", 1)[0]


def migrations_at(ref: str) -> list[str]:
    """Every migration path in the tree at `ref`."""
    out = git("ls-tree", "-r", "--name-only", ref)
    return [p for p in out.splitlines() if MIGRATION_RE.search(p)]


def resolves_version_collision(old_path: str, new_path: str, base_paths: list[str]) -> bool:
    """True iff renaming `old_path` to `new_path` is the repair for a duplicate Flyway version.

    Two migrations in one service sharing a version is not an ordering problem that
    QUARKUS_FLYWAY_OUT_OF_ORDER can absorb — measured against Flyway 11 with this repo's own
    migrations, the resolver refuses before touching the database:

        ERROR: Found more than one migration with version 14

    So the service cannot start at all, and the forward-only rule this gate enforces has no
    forward: adding V<n+1> leaves the duplicate pair in place. Renumbering one of the two is
    the only repair, which is why this narrow case is permitted.

    Narrow on purpose, and decidable from the tree alone (no database access, which CI does not
    have): the OLD version must actually be duplicated in the same service at the PR base, and
    the NEW version must be free there. A rename that meets neither is still an edit of an
    applied migration's identity and is still refused.
    """
    old_version, new_version = migration_version(old_path), migration_version(new_path)
    if old_version is None or new_version is None:
        return False
    service = service_of(old_path)
    if service_of(new_path) != service:
        return False
    same_service = [
        p for p in base_paths if service_of(p) == service and p != old_path
    ]
    collides = any(migration_version(p) == old_version for p in same_service)
    new_is_free = all(migration_version(p) != new_version for p in same_service)
    return collides and new_is_free


def changed_migrations(base: str) -> tuple[list[str], list[str]]:
    """Return (added, modified) migration paths in the diff against `base`."""
    added, modified = [], []
    # --diff-filter distinguishes the two requirements: A -> needs a note, M -> forbidden.
    out = git("diff", "--name-status", "--diff-filter=AMR", base, "HEAD")
    base_paths: list[str] | None = None
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        status, path = parts[0], parts[-1]
        if not MIGRATION_RE.search(path):
            continue
        # A rename (R) of a migration is an edit of its identity — Flyway keys on the version
        # in the filename, so renaming V3 to V4 is not "adding V4", it is rewriting history.
        # The one exception is a rename that resolves a duplicate version, where leaving the
        # file alone is what breaks the service; see resolves_version_collision().
        if status.startswith("R") and len(parts) >= 3:
            if base_paths is None:
                base_paths = migrations_at(base)
            if resolves_version_collision(parts[1], path, base_paths):
                print(
                    f"::notice file={path}::Renumbering accepted: {parts[1]} collided with "
                    f"another migration of the same version in this service, which Flyway "
                    f"refuses to resolve at startup. Treated as an added migration."
                )
                added.append(path)
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

    # --- the collision-repair rename ------------------------------------------------------
    # The rename exception must stay narrow: it exists because a duplicate version stops Flyway
    # before it reads the database, so "add V<n+1> instead" is not available. A version of this
    # predicate that answers True for an ordinary rename would silently re-open the exact hole
    # the forward-only rule closes, and nothing else in CI would notice.
    svc = "openbank-notification-service/src/main/resources/db/migration"
    other = "openbank-other-service/src/main/resources/db/migration"
    base_with_collision = [f"{svc}/V13__a.sql", f"{svc}/V14__taint.sql", f"{svc}/V14__dedup.sql"]
    base_no_collision = [f"{svc}/V13__a.sql", f"{svc}/V14__taint.sql"]

    for label, old_p, new_p, base_paths, want in (
        ("a rename resolving a duplicate version is allowed",
         f"{svc}/V14__dedup.sql", f"{svc}/V15__dedup.sql", base_with_collision, True),
        # THE DEFECT this must catch: an ordinary renumber, where the forward-only rule applies
        # and an already-applied migration would silently change identity.
        ("a rename with no collision is still refused",
         f"{svc}/V14__taint.sql", f"{svc}/V15__taint.sql", base_no_collision, False),
        ("renaming onto a version already taken is refused",
         f"{svc}/V14__dedup.sql", f"{svc}/V13__dedup.sql", base_with_collision, False),
        ("a collision in a DIFFERENT service does not license this rename",
         f"{svc}/V14__taint.sql", f"{svc}/V15__taint.sql",
         [f"{svc}/V14__taint.sql", f"{other}/V14__x.sql", f"{other}/V14__y.sql"], False),
        ("a cross-service move is refused",
         f"{svc}/V14__dedup.sql", f"{other}/V15__dedup.sql", base_with_collision, False),
        ("a migration with no version in its name is refused",
         f"{svc}/baseline.sql", f"{svc}/V15__baseline.sql", base_with_collision, False),
    ):
        got = resolves_version_collision(old_p, new_p, base_paths)
        if got != want:
            fails.append(f"{label}: expected {want}, got {got}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: db-migration rollback note and collision-repair rename "
          "are falsifiable (21 cases)")
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
