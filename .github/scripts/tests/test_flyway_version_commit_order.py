# SPDX-License-Identifier: Apache-2.0
"""Missing mainline history must produce a diagnostic, never a clean scan."""
import contextlib
import importlib.util
import io
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
SCRIPT = SCRIPTS / "check-flyway-version-commit-order.py"
SPEC = importlib.util.spec_from_file_location("flyway_version_commit_order", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
with patch.object(sys, "path", [str(SCRIPTS), *sys.path]):
    SPEC.loader.exec_module(MODULE)


class MissingMainHistoryTests(unittest.TestCase):
    def setUp(self):
        self.path = MODULE.REPO / "openbank-demo/src/main/resources/db/migration/V1__init.sql"

    def unavailable_git(self, command, **kwargs):
        if command == ["git", "rev-parse", "--is-shallow-repository"]:
            return subprocess.CompletedProcess(command, 0, stdout="false\n", stderr="")
        if command == ["git", "fetch", "--quiet", "origin", "main"]:
            return subprocess.CompletedProcess(command, 1, stdout="", stderr="fetch unavailable")
        if command[:3] == ["git", "rev-list", "--reverse"]:
            self.assertIn(command[3], ("FETCH_HEAD", "origin/main", "main"))
            self.assertTrue(kwargs["check"])
            raise subprocess.CalledProcessError(128, command, stderr="unknown revision")
        self.fail(f"unexpected subprocess command: {command}")

    def test_missing_all_mainline_candidates_returns_unresolved_history(self):
        with patch.object(MODULE.subprocess, "run", side_effect=self.unavailable_git) as run:
            order, provenance = MODULE.first_commit_order([self.path])
        self.assertEqual(order, {})
        self.assertEqual(provenance, "unresolved — TOO SHALLOW for 1 migrations")
        self.assertEqual([call.args[0] for call in run.call_args_list], [
            ["git", "rev-parse", "--is-shallow-repository"],
            ["git", "fetch", "--quiet", "origin", "main"],
            ["git", "rev-list", "--reverse", "FETCH_HEAD"],
            ["git", "rev-list", "--reverse", "origin/main"],
            ["git", "rev-list", "--reverse", "main"],
        ])

    def test_missing_history_is_fatal_even_without_enforce(self):
        for flags in ([], ["--enforce"]):
            with self.subTest(flags=flags):
                output = io.StringIO()
                with patch.object(MODULE.subprocess, "run", side_effect=self.unavailable_git), \
                     patch.object(MODULE, "migration_files", return_value=[(self.path, 1)]), \
                     patch.object(sys, "argv", [str(SCRIPT), *flags]), \
                     contextlib.redirect_stdout(output):
                    result = MODULE.main()
                self.assertEqual(result, 1)
                self.assertIn("mainline resolved as: unresolved", output.getvalue())
                self.assertIn("::error::could not obtain a usable history for main", output.getvalue())
                self.assertIn("SUBJECTS=0", output.getvalue())
                self.assertNotIn("— clean.", output.getvalue())


if __name__ == "__main__":
    unittest.main()
