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


if __name__ == "__main__":
    unittest.main()
