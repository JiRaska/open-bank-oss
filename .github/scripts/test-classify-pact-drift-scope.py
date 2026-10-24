#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression tests for fail-closed UI-only Pact scope classification."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("classify-pact-drift-scope.py")
SPEC = importlib.util.spec_from_file_location("classify_pact_drift_scope", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ClassifierTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Pact scope fixture")
        self.git("config", "user.email", "pact-scope@example.test")
        self.write("openbank-admin-ui/src/page.tsx", "initial\n")
        self.write("pacts/openbank-admin-ui-provider.json", "{}\n")
        self.git("add", "--all")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "fixture base")
        self.base = self.git("rev-parse", "HEAD").decode().strip()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo)

    def write(self, path, content):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def commit(self):
        self.git("add", "--all")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "fixture change")
        return self.git("rev-parse", "HEAD").decode().strip()

    def test_only_admin_ui_changes_select_scoped_mode(self):
        self.write("openbank-admin-ui/src/page.tsx", "updated\n")
        self.write("openbank-admin-ui/src/new.tsx", "new\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.UI_ONLY)

    def test_pact_and_shared_paths_force_full_mode(self):
        for path in ("pacts/openbank-admin-ui-provider.json", "gradle.properties",
                     ".github/workflows/pact-drift-check.yml", "openbank-libs-core/src/A.kt"):
            with self.subTest(path=path):
                self.write(path, "changed\n")
                head = self.commit()
                self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)
                # Each subtest starts from a new base to keep diff paths isolated.
                self.base = head

    def test_empty_diff_forces_full_mode(self):
        self.assertEqual(MODULE.classify(self.repo, self.base, self.base)[0], MODULE.FULL)

    def test_unavailable_revision_forces_full_mode(self):
        self.assertEqual(MODULE.classify(self.repo, self.base, "missing-revision")[0], MODULE.FULL)

    def test_non_ancestor_base_forces_full_mode(self):
        self.git("checkout", "--orphan", "unrelated")
        self.git("rm", "-rf", ".")
        self.write("openbank-admin-ui/src/page.tsx", "unrelated history\n")
        self.git("add", "--all")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "unrelated head")
        head = self.git("rev-parse", "HEAD").decode().strip()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_classification_outputs_single_ui_shard(self):
        self.write("openbank-admin-ui/src/page.tsx", "updated\n")
        head = self.commit()
        output = self.repo / "github-output"
        result = subprocess.run(
            ["python3", str(SCRIPT), "--repo", str(self.repo), "--base", self.base,
             "--head", head, "--output", str(output)],
            check=True, capture_output=True, text=True,
        )
        self.assertIn("ui-only", result.stdout)
        self.assertEqual(output.read_text(), 'mode=ui-only\nshards=["ui"]\n')

    def test_full_fallback_outputs_all_four_shards(self):
        output = self.repo / "github-output"
        subprocess.run(
            ["python3", str(SCRIPT), "--repo", str(self.repo), "--base", self.base,
             "--head", "missing-revision", "--output", str(output)],
            check=True, capture_output=True, text=True,
        )
        self.assertEqual(output.read_text(), 'mode=full\nshards=["0", "1", "2", "3"]\n')

    def test_rename_forces_full_mode_even_inside_ui_tree(self):
        self.git("mv", "openbank-admin-ui/src/page.tsx", "openbank-admin-ui/src/renamed.tsx")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_copy_forces_full_mode_even_inside_ui_tree(self):
        self.write("openbank-admin-ui/src/copy.tsx", "initial\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)


if __name__ == "__main__":
    unittest.main()
