#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression tests for immutable GitOps deploy source-freshness checks."""
from __future__ import annotations

import importlib.util
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "check_deploy_source_freshness", SCRIPT_DIR / "check-deploy-source-freshness.py"
)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load source freshness checker")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)
VERIFY_SCRIPT = SCRIPT_DIR / "pact-version-tree-equivalent.sh"
DEPLOY_SCRIPT = SCRIPT_DIR / "deploy-window.py"


class DeploySourceFreshnessTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="deploy-source-freshness-")
        self.root = Path(self.temp.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "freshness-test@example.invalid")
        self.git("config", "user.name", "Freshness Fixture")
        self.git("config", "commit.gpgsign", "false")
        (self.root / ".github/scripts").mkdir(parents=True)
        shutil.copy2(VERIFY_SCRIPT, self.root / ".github/scripts/pact-version-tree-equivalent.sh")
        shutil.copy2(DEPLOY_SCRIPT, self.root / ".github/scripts/deploy-window.py")
        (self.root / "openbank-example-service/src/main").mkdir(parents=True)
        (self.root / "openbank-other-service/src/main").mkdir(parents=True)
        (self.root / "openbank-libs-domain/src/main").mkdir(parents=True)
        (self.root / "openbank-infra/gitops/components/example").mkdir(parents=True)
        (self.root / "gradle").mkdir()
        (self.root / "config").mkdir()
        (self.root / "build-logic").mkdir()
        (self.root / ".github/workflows").mkdir()
        self.write("openbank-example-service/build.gradle.kts", "plugins {}\n")
        self.write("openbank-example-service/src/main/Example.kt", "class Example\n")
        self.write("openbank-other-service/build.gradle.kts", "plugins {}\n")
        self.write("openbank-other-service/src/main/Other.kt", "class Other\n")
        self.write("openbank-libs-domain/build.gradle.kts", "plugins {}\n")
        self.write("openbank-libs-domain/src/main/Domain.kt", "class Domain\n")
        self.write("gradle/libs.versions.toml", "[versions]\n")
        self.write("config/detekt.yml", "config: {}\n")
        self.write("build-logic/build.gradle.kts", "plugins {}\n")
        self.write(".github/workflows/Dockerfile.deploy", "FROM scratch\n")
        self.write("build.gradle.kts", "plugins {}\n")
        self.write("settings.gradle.kts", "rootProject.name = \"fixture\"\n")
        self.write("openbank-infra/gitops/components/example/deployment.yaml",
                   self.manifest("sandbox-00000000"))
        self.source = self.commit("source image")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.root, check=True,
                              capture_output=True, text=True).stdout.strip()

    def write(self, relative: str, content: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    @staticmethod
    def manifest(tag: str) -> str:
        return ("image: ghcr.io/openbank/openbank-example-service:" + tag + "\n")

    def commit(self, message: str) -> str:
        self.git("add", "-A")
        self.git("commit", "-qm", message)
        return self.git("rev-parse", "HEAD")

    def candidate(self, pin: str | None = None, extra_path: str | None = None) -> tuple[str, str]:
        base = self.git("rev-parse", "HEAD")
        self.write("openbank-infra/gitops/components/example/deployment.yaml",
                   self.manifest(pin or "sandbox-11111111"))
        if extra_path:
            self.write(extra_path, "candidate change\n")
        head = self.commit("deploy candidate")
        self.git("checkout", "--detach", base)
        return base, head

    def test_current_pin_passes_using_the_selected_commit_pair(self) -> None:
        base, head = self.candidate(f"sandbox-{self.source[:8]}")
        self.assertEqual([], CHECKER.verify(self.root, base, head))

    def test_new_image_pin_passes_when_its_source_is_current(self) -> None:
        base = self.git("rev-parse", "HEAD")
        self.write("openbank-infra/gitops/components/example/added-image.yaml",
                   "image: ghcr.io/openbank/openbank-other-service:" + f"sandbox-{self.source[:8]}\n")
        head = self.commit("add current image pin")
        self.git("checkout", "--detach", base)
        self.assertEqual([], CHECKER.verify(self.root, base, head))

    def test_freshness_is_cached_per_image_tag_only_within_one_verification(self) -> None:
        image = "openbank/openbank-example-service"
        tag = f"sandbox-{self.source[:8]}"
        base = self.git("rev-parse", "HEAD")
        self.write("openbank-infra/gitops/components/example/deployment.yaml",
                   f"image: ghcr.io/{image}:{tag}\n")
        head = self.commit("replace image pin")
        self.git("checkout", "--detach", base)

        checked: list[tuple[str, str]] = []

        def fresh(checked_image: str, checked_tag: str) -> bool:
            checked.append((checked_image, checked_tag))
            return True

        self.assertEqual([], CHECKER.verify(self.root, base, head, fresh))
        self.assertEqual([(image, tag)], checked)

        # A subsequent verification gets a fresh cache and asks its callback again.
        self.assertEqual([], CHECKER.verify(self.root, base, head, fresh))
        self.assertEqual([(image, tag), (image, tag)], checked)

    def test_freshness_cache_keeps_distinct_tags_separate(self) -> None:
        image = "openbank/openbank-example-service"
        tag_a, tag_b = f"sandbox-{self.source[:8]}", "sandbox-11111111"
        base = self.git("rev-parse", "HEAD")
        self.write("openbank-infra/gitops/components/example/deployment.yaml",
                   f"image: ghcr.io/{image}:{tag_a}\n")
        self.write("openbank-infra/gitops/components/example/second-pin.yaml",
                   f"image: ghcr.io/{image}:{tag_b}\n")
        head = self.commit("replace and add image pins")
        self.git("checkout", "--detach", base)

        checked: list[tuple[str, str]] = []
        failures = CHECKER.verify(
            self.root, base, head,
            lambda checked_image, checked_tag: checked.append((checked_image, checked_tag)) or True,
        )
        self.assertEqual([], failures)
        self.assertCountEqual([(image, tag_a), (image, tag_b)], checked)

    def test_stale_new_pin_is_checked_alongside_fresh_replacement(self) -> None:
        self.write("openbank-other-service/src/main/Other.kt", "class Other { fun changed() = true }\n")
        self.commit("other service source changed after image build")
        base = self.git("rev-parse", "HEAD")
        self.write("openbank-infra/gitops/components/example/deployment.yaml",
                   self.manifest(f"sandbox-{self.source[:8]}"))
        self.write("openbank-infra/gitops/components/example/added-image.yaml",
                   "image: ghcr.io/openbank/openbank-other-service:" + f"sandbox-{self.source[:8]}\n")
        head = self.commit("fresh replacement and stale new image")
        self.git("checkout", "--detach", base)
        failures = CHECKER.verify(self.root, base, head)
        self.assertTrue(any("openbank-other-service" in failure for failure in failures), failures)

    def test_service_source_change_after_image_build_is_stale(self) -> None:
        self.write("openbank-example-service/src/main/Example.kt", "class Example { fun changed() = true }\n")
        self.commit("service source changed after image build")
        base, head = self.candidate(f"sandbox-{self.source[:8]}")
        self.assertTrue(CHECKER.verify(self.root, base, head))

    def test_shared_build_logic_change_after_image_build_is_stale(self) -> None:
        self.write("build-logic/build.gradle.kts", "plugins { id(\"changed\") }\n")
        self.commit("shared build logic changed after image build")
        base, head = self.candidate(f"sandbox-{self.source[:8]}")
        self.assertTrue(CHECKER.verify(self.root, base, head))

    def test_bad_or_missing_commit_sha_fails_closed(self) -> None:
        base, head = self.candidate(f"sandbox-{self.source[:8]}")
        self.assertTrue(CHECKER.verify(self.root, "not-a-sha", head))
        self.assertTrue(CHECKER.verify(self.root, base, "f" * 40))

    def test_checkout_must_be_at_exact_base(self) -> None:
        base, head = self.candidate(f"sandbox-{self.source[:8]}")
        self.git("checkout", "--detach", head)
        self.assertTrue(CHECKER.verify(self.root, base, head))
        self.git("checkout", "--detach", base)
        self.assertEqual([], CHECKER.verify(self.root, base, head))

    def test_base_must_be_ancestor_of_candidate_head(self) -> None:
        source = self.source
        self.write("openbank-infra/gitops/components/example/deployment.yaml",
                   self.manifest(f"sandbox-{source[:8]}"))
        head = self.commit("candidate from old base")
        self.git("checkout", "-q", "main")
        self.write("README.md", "new main\n")
        base = self.commit("main advanced")
        self.assertTrue(CHECKER.verify(self.root, base, head))

    def test_no_image_pin_change_fails_closed(self) -> None:
        base = self.git("rev-parse", "HEAD")
        self.write("openbank-infra/gitops/components/example/labels.yaml", "labels: {team: test}\n")
        head = self.commit("non-pin GitOps change")
        self.assertTrue(CHECKER.verify(self.root, base, head))

    def test_non_gitops_change_fails_closed(self) -> None:
        base, head = self.candidate(f"sandbox-{self.source[:8]}", "README.md")
        self.assertTrue(CHECKER.verify(self.root, base, head))


if __name__ == "__main__":
    unittest.main(verbosity=2)
