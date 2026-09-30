#!/usr/bin/env python3
"""Exercise the documentation gate against real PR and synthetic merge commits."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class ServiceDocsFreshnessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        temporary = tempfile.TemporaryDirectory(prefix="service-docs-git-")
        cls.addClassCleanup(temporary.cleanup)
        cls.repo = Path(temporary.name) / "repo"
        cls.repo.mkdir()
        cls.env = {
            **os.environ,
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_AUTHOR_NAME": "CI Fixture",
            "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
            "GIT_COMMITTER_NAME": "CI Fixture",
            "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
        }
        cls.git("init", "--initial-branch=main")
        cls.script = cls.repo / ".github/scripts/check-service-docs-freshness.py"
        cls.script.parent.mkdir(parents=True)
        shutil.copyfile(Path(__file__).with_name("check-service-docs-freshness.py"), cls.script)
        for name in ("alpha", "beta"):
            cls.write(f"openbank-{name}-service/build.gradle.kts", 'plugins { id("openbank.quarkus-service") }\ndependencies { implementation(project(":openbank-libs-runtime")) }\n')
            cls.write(f"openbank-{name}-service/src/main/App.kt", "initial\n")
            cls.write(f"openbank-{name}-service/src/main/resources/docs/README.md", "Initial docs\n")
        cls.write("openbank-libs-runtime/src/main/Shared.kt", "initial\n")
        cls.write("openbank-libs/docs/README.md", "Initial shared docs\n")
        cls.base = cls.commit("initial fixture")

        cls.git("checkout", "-b", "documented-pr")
        cls.write("openbank-alpha-service/src/main/App.kt", "new behavior\n")
        cls.write("openbank-alpha-service/src/main/resources/docs/README.md", "New behavior documented\n")
        cls.write("openbank-libs-runtime/src/main/Shared.kt", "new shared behavior\n")
        cls.write("openbank-libs/docs/README.md", "New shared behavior documented\n")
        cls.documented = cls.commit("document production changes")

        cls.git("checkout", "-b", "undocumented-pr", cls.base)
        cls.write("openbank-alpha-service/src/main/App.kt", "undocumented\n")
        cls.undocumented = cls.commit("missing service docs")

        cls.git("checkout", "-b", "undocumented-libs-pr", cls.base)
        cls.write("openbank-libs-runtime/src/main/Shared.kt", "undocumented shared behavior\n")
        cls.undocumented_libs = cls.commit("missing shared docs")

        cls.git("checkout", "main")
        cls.write("openbank-beta-service/src/main/App.kt", "unrelated main change\n")
        cls.commit("main advances after PR opens")
        cls.git("merge", "--no-ff", "documented-pr", "-m", "synthetic PR merge")

    @classmethod
    def git(cls, *args):
        return subprocess.check_output(
            ["git", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", *args],
            cwd=cls.repo, env=cls.env, text=True, stderr=subprocess.DEVNULL,
        ).strip()

    @classmethod
    def write(cls, relative, content):
        target = cls.repo / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    @classmethod
    def commit(cls, message):
        cls.git(
            "add", "--", ".github/scripts/check-service-docs-freshness.py",
            "openbank-alpha-service/build.gradle.kts",
            "openbank-alpha-service/src/main/App.kt",
            "openbank-alpha-service/src/main/resources/docs/README.md",
            "openbank-beta-service/build.gradle.kts",
            "openbank-beta-service/src/main/App.kt",
            "openbank-beta-service/src/main/resources/docs/README.md",
            "openbank-libs-runtime/src/main/Shared.kt", "openbank-libs/docs/README.md",
        )
        cls.git("commit", "-m", message)
        return cls.git("rev-parse", "HEAD")

    def gate(self, *args):
        return subprocess.run(
            [sys.executable, str(self.script), "--base", self.base, *args],
            cwd=self.repo.parent, env=self.env, capture_output=True, text=True,
        )

    def test_pr_head_excludes_main_changes_from_synthetic_merge(self):
        result = self.gate("--head", self.documented)
        self.assertEqual(0, result.returncode, result.stderr)
        wrong_head = self.gate("--head", "HEAD")
        self.assertEqual(1, wrong_head.returncode)
        self.assertIn("openbank-beta-service", wrong_head.stderr)

    def test_undocumented_service_change_fails(self):
        result = self.gate("--head", self.undocumented)
        self.assertEqual(1, result.returncode)
        self.assertIn("openbank-alpha-service", result.stderr)

    def test_undocumented_shared_change_fails(self):
        result = self.gate("--head", self.undocumented_libs)
        self.assertEqual(1, result.returncode)
        self.assertIn("openbank-libs/docs/*.md", result.stderr)

    def test_missing_pr_head_does_not_default_to_checkout(self):
        self.assertEqual(2, self.gate().returncode)

    def test_unknown_pr_head_is_not_a_pass(self):
        self.assertEqual(2, self.gate("--head", "missing-ref").returncode)


if __name__ == "__main__":
    unittest.main()
