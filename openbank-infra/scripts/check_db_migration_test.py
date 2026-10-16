#!/usr/bin/env python3
"""Unit tests for check-db-migration.py — the db_change gate (rules.yaml, ADR-0144).

The gate discharges both halves of `db_change.require`:
  - an ADDED migration must carry a `-- Rollback:` note with actual content;
  - an already-committed migration must not be EDITED (Flyway checksums the whole file).

This suite proves:
  - a new migration with a single-line note passes;
  - a new migration with a MULTI-LINE note passes — the real shape of half the fleet's
    notes, and the case a naive same-line regex would wrongly reject;
  - a bare `-- Rollback:` with no content is a finding (a note that says nothing looks like
    the box is ticked);
  - a new migration with no note at all is a finding, and exits 1 under --enforce;
  - editing an existing migration is a finding;
  - non-migration SQL and unrelated files are ignored;
  - advisory mode exits 0 even with findings.

The module has a hyphenated filename, so it is loaded via importlib — same technique as
check_threat_model_diff_test.py / gen_network_policies_test.py.

Run:  python3 -m unittest openbank-infra/scripts/check_db_migration_test.py -v
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_SCRIPT_PATH = Path(__file__).parent / "check-db-migration.py"
_spec = importlib.util.spec_from_file_location("check_db_migration", _SCRIPT_PATH)
assert _spec and _spec.loader
gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gate)

MIGRATION_DIR = "openbank-demo-service/src/main/resources/db/migration"


class HasRollbackNoteTest(unittest.TestCase):
    """The note parser, in isolation — no git required."""

    def test_single_line_note(self):
        self.assertTrue(gate.has_rollback_note("-- Rollback: DROP TABLE foo;\nCREATE TABLE foo();"))

    def test_multi_line_note(self):
        # The real shape used by document-service V6 / statement-service V4. A naive regex
        # demanding content on the marker line would reject these perfectly good notes.
        sql = (
            "-- Rollback:\n"
            "--   DROP INDEX IF EXISTS uq_documents_idempotency_key;\n"
            "--   ALTER TABLE documents DROP COLUMN IF EXISTS idempotency_key;\n"
            "ALTER TABLE documents ADD COLUMN idempotency_key TEXT;\n"
        )
        self.assertTrue(gate.has_rollback_note(sql))

    def test_blank_comment_lines_between_marker_and_content(self):
        self.assertTrue(gate.has_rollback_note("-- Rollback:\n--\n--   DROP TABLE foo;\nSELECT 1;"))

    def test_bare_marker_with_no_content_is_not_a_note(self):
        # A note that says nothing is worse than no note: it looks like the box is ticked.
        self.assertFalse(gate.has_rollback_note("-- Rollback:\nCREATE TABLE foo();"))

    def test_marker_followed_by_sql_not_comments_is_not_a_note(self):
        self.assertFalse(gate.has_rollback_note("-- Rollback:\nDROP TABLE foo;\n"))

    def test_no_marker_at_all(self):
        self.assertFalse(gate.has_rollback_note("-- ADR-0036 mandate vault.\nCREATE TABLE foo();"))

    def test_case_and_punctuation_variants(self):
        self.assertTrue(gate.has_rollback_note("--Rollback: DROP TABLE foo;"))
        self.assertTrue(gate.has_rollback_note("-- ROLLBACK - DROP TABLE foo;"))
        self.assertTrue(gate.has_rollback_note("-- rollback  DROP TABLE foo;"))


class GateAgainstGitTest(unittest.TestCase):
    """End-to-end against a real throwaway git repo."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        self._git("init", "-q", "-b", "main")
        self._git("config", "user.email", "test@example.com")
        self._git("config", "user.name", "test")
        self._git("config", "commit.gpgsign", "false")
        self._write("README.md", "base\n")
        self._git("add", "-A")
        self._git("commit", "-q", "-m", "base")
        self.base = self._git("rev-parse", "HEAD").strip()
        self.addCleanup(self.tmp.cleanup)

    def _git(self, *args: str) -> str:
        return subprocess.run(
            ["git", "-C", str(self.repo), *args], check=True, capture_output=True, text=True
        ).stdout

    def _write(self, rel: str, text: str) -> None:
        p = self.repo / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def _run(self, enforce: bool = False) -> tuple[int, str]:
        cmd = [sys.executable, str(_SCRIPT_PATH), "--base", self.base]
        if enforce:
            cmd.append("--enforce")
        r = subprocess.run(cmd, cwd=str(self.repo), capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr

    def _commit(self, msg: str) -> None:
        self._git("add", "-A")
        self._git("commit", "-q", "-m", msg)

    def test_new_migration_with_note_passes(self):
        self._write(f"{MIGRATION_DIR}/V1__init.sql", "-- Rollback: DROP TABLE foo;\nCREATE TABLE foo();\n")
        self._commit("add V1")
        rc, out = self._run()
        self.assertEqual(rc, 0)
        self.assertIn("all with a rollback note", out)

    def test_new_migration_without_note_is_a_finding(self):
        self._write(f"{MIGRATION_DIR}/V1__init.sql", "CREATE TABLE foo();\n")
        self._commit("add V1")
        rc, out = self._run()
        self.assertEqual(rc, 0, "advisory mode must not fail the build")
        self.assertIn("::warning", out)
        self.assertIn("without a rollback note", out)

    def test_new_migration_without_note_fails_under_enforce(self):
        self._write(f"{MIGRATION_DIR}/V1__init.sql", "CREATE TABLE foo();\n")
        self._commit("add V1")
        rc, out = self._run(enforce=True)
        self.assertEqual(rc, 1)
        self.assertIn("::error", out)

    def test_editing_a_committed_migration_is_a_finding(self):
        # Flyway checksums the whole file; editing an applied migration breaks startup.
        self._write(f"{MIGRATION_DIR}/V1__init.sql", "-- Rollback: DROP TABLE foo;\nCREATE TABLE foo();\n")
        self._commit("add V1")
        self.base = self._git("rev-parse", "HEAD").strip()
        self._write(
            f"{MIGRATION_DIR}/V1__init.sql",
            "-- Rollback: DROP TABLE foo;\nCREATE TABLE foo(id INT);\n",
        )
        self._commit("edit V1")
        rc, out = self._run(enforce=True)
        self.assertEqual(rc, 1)
        self.assertIn("must not be edited", out)

    def test_non_migration_sql_is_ignored(self):
        self._write("openbank-demo-service/src/main/resources/clickhouse/V1__analytics.sql", "CREATE TABLE t();\n")
        self._commit("add analytics sql")
        rc, out = self._run(enforce=True)
        self.assertEqual(rc, 0)
        self.assertIn("nothing to check", out)

    def test_unrelated_change_is_ignored(self):
        self._write("README.md", "changed\n")
        self._commit("touch readme")
        rc, out = self._run(enforce=True)
        self.assertEqual(rc, 0)
        self.assertIn("nothing to check", out)

    def _noted(self, name: str, directory: str = MIGRATION_DIR) -> None:
        self._write(f"{directory}/{name}", "-- Rollback: DROP TABLE fixture;\nSELECT 1;\n")

    def _base_with(self, *names: str) -> None:
        for name in names:
            self._noted(name)
        self._commit("base migrations")
        self.base = self._git("rev-parse", "HEAD").strip()

    def test_new_version_must_exceed_existing_service_maximum(self):
        self._base_with("V27__existing.sql")
        for version in (26, 27):
            with self.subTest(version=version):
                self._noted(f"V{version}__candidate.sql")
                self._commit("candidate migration")
                rc, out = self._run(enforce=True)
                self.assertEqual(1, rc)
                self.assertIn("must exceed", out)
                self.assertIn("27", out)
                self._git("reset", "--hard", self.base)

    def test_higher_version_and_other_module_maximum_are_independent(self):
        other = "openbank-other-service/src/main/resources/db/migration"
        self._noted("V99__other.sql", other)
        self._base_with("V27__existing.sql")
        self._noted("V28__candidate.sql")
        self._noted("V100__candidate.sql", other)
        self._commit("forward migrations")
        rc, out = self._run(enforce=True)
        self.assertEqual(0, rc, out)

    def test_existing_migrations_unchanged_do_not_become_new_candidates(self):
        self._base_with("V26__existing.sql", "V27__existing.sql")
        self._write("README.md", "unrelated change\n")
        self._commit("unrelated change")
        rc, out = self._run(enforce=True)
        self.assertEqual(0, rc, out)
        self.assertIn("nothing to check", out)

    def test_multiple_new_versions_pass_but_duplicate_added_version_fails(self):
        self._base_with("V27__existing.sql")
        self._noted("V28__first.sql")
        self._noted("V29__second.sql")
        self._commit("two forward migrations")
        rc, out = self._run(enforce=True)
        self.assertEqual(0, rc, out)
        self._noted("V028__duplicate.sql")
        self._commit("duplicate integer version")
        rc, out = self._run(enforce=True)
        self.assertEqual(1, rc)
        self.assertIn("duplicate added Flyway version 28", out)

    def test_unknown_or_noncommit_base_fails_closed(self):
        self._noted("V1__candidate.sql")
        self._commit("candidate")
        for base in ("missing-base", "f" * 40, "HEAD:README.md"):
            with self.subTest(base=base):
                self.base = base
                rc, _ = self._run(enforce=True)
                self.assertNotEqual(0, rc)

    def test_nested_migrations_share_the_service_maximum(self):
        self._noted("V27__existing.sql", MIGRATION_DIR + "/nested")
        self._commit("nested base migration")
        self.base = self._git("rev-parse", "HEAD").strip()
        self._noted("V26__candidate.sql")
        self._commit("lower candidate")
        rc, out = self._run(enforce=True)
        self.assertEqual(1, rc)
        self.assertIn("must exceed", out)

    def test_unsupported_versioned_names_fail_but_repeatable_scope_is_preserved(self):
        self._base_with("V27__existing.sql")
        for name in ("V28_1__candidate.sql", "V28.1__candidate.sql", "Vfoo__candidate.sql", "V28__.sql"):
            with self.subTest(name=name):
                self._noted(name)
                self._commit("unsupported version shape")
                rc, out = self._run(enforce=True)
                self.assertEqual(1, rc)
                self.assertIn("unsupported integer Flyway version", out)
                self._git("reset", "--hard", self.base)
        self._noted("R__refresh_view.sql")
        self._commit("repeatable migration")
        rc, out = self._run(enforce=True)
        self.assertEqual(0, rc, out)

    def test_unknown_base_version_prevents_assuming_a_maximum(self):
        self._base_with("V2_1__unsupported.sql")
        self._noted("V3__candidate.sql")
        self._commit("candidate")
        rc, out = self._run(enforce=True)
        self.assertEqual(1, rc)
        self.assertIn("unsupported integer Flyway version", out)

    def test_existing_duplicate_repair_exception_remains_unchanged(self):
        self._base_with("V1__first.sql", "V1__second.sql")
        self._git("mv", f"{MIGRATION_DIR}/V1__second.sql", f"{MIGRATION_DIR}/V2__second.sql")
        self._commit("repair unapplied duplicate by exact rename")
        rc, out = self._run(enforce=True)
        self.assertEqual(0, rc, out)
        self.assertIn("renumbers a DUPLICATE version", out)

    def test_duplicate_repair_below_base_maximum_is_forbidden(self):
        self._base_with("V1__first.sql", "V1__second.sql", "V27__existing.sql")
        self._git("mv", f"{MIGRATION_DIR}/V1__second.sql", f"{MIGRATION_DIR}/V2__second.sql")
        self._commit("duplicate repair below existing maximum")
        rc, out = self._run(enforce=True)
        self.assertEqual(1, rc, out)
        self.assertIn("must not be edited", out)
        self.assertNotIn("renumbers a DUPLICATE version", out)

    def test_duplicate_repair_above_base_maximum_is_permitted(self):
        self._base_with("V1__first.sql", "V1__second.sql", "V27__existing.sql")
        self._git("mv", f"{MIGRATION_DIR}/V1__second.sql", f"{MIGRATION_DIR}/V28__second.sql")
        self._commit("duplicate repair above existing maximum")
        rc, out = self._run(enforce=True)
        self.assertEqual(0, rc, out)
        self.assertIn("renumbers a DUPLICATE version", out)

    def test_duplicate_repair_unknown_version_formats_are_forbidden(self):
        self._base_with("V1__first.sql", "V1__second.sql", "V2_1__unsupported.sql")
        self._git("mv", f"{MIGRATION_DIR}/V1__second.sql", f"{MIGRATION_DIR}/V28__second.sql")
        self._commit("duplicate repair with unknown base maximum")
        rc, out = self._run(enforce=True)
        self.assertEqual(1, rc, out)
        self.assertIn("must not be edited", out)
        self._git("reset", "--hard", self.base)
        self._git("mv", f"{MIGRATION_DIR}/V1__second.sql", f"{MIGRATION_DIR}/V28_1__second.sql")
        self._commit("duplicate repair with unsupported destination")
        rc, out = self._run(enforce=True)
        self.assertEqual(1, rc, out)
        self.assertIn("must not be edited", out)


if __name__ == "__main__":
    unittest.main()
