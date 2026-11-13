#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise real Git checkout rejection boundaries without network or broker calls."""

import hashlib
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "proof", Path(__file__).with_name("prove-finrep-historical-checkout.py")
)
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class CheckoutProofTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.before = os.getcwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, self.before)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Synthetic fixture")
        self.git("config", "commit.gpgsign", "false")
        path = Path(proof.PACT_PATH)
        path.parent.mkdir()
        raw = json.dumps(
            {
                "consumer": {"name": "openbank-finrep-service"},
                "provider": {"name": "openbank-ledger-service"},
                "interactions": [{}, {}, {}],
            }
        ).encode()
        path.write_bytes(raw)
        Path("build.gradle.kts").write_text("// synthetic build input\n")
        shared = Path("openbank-libs-runtime/src/main/Existing.kt")
        shared.parent.mkdir(parents=True)
        shared.write_text("// synthetic shared input\n")
        module_build = Path("openbank-libs-runtime/build.gradle.kts")
        module_build.write_text("// synthetic module build\n")
        Path(".gitignore").write_text(".gradle/\nbuild/\n*/build/\n*.secret\n")
        self.git("add", proof.PACT_PATH, "build.gradle.kts", ".gitignore", str(shared), str(module_build))
        self.git("commit", "-qm", "synthetic fixture")
        self.sha = self.git("rev-parse", "HEAD").strip()
        self.git("update-ref", "refs/remotes/origin/main", self.sha)
        pins = {
            "CONSUMER_SHA": self.sha,
            "BLOB": self.git("rev-parse", f"HEAD:{proof.PACT_PATH}").strip(),
            "DIGEST": hashlib.sha256(raw).hexdigest(),
        }
        self.pin_patch = patch.multiple(proof, **pins)
        self.pin_patch.start()
        self.addCleanup(self.pin_patch.stop)
        self.env = {
            "GITHUB_ACTIONS": "true",
            "GITHUB_REPOSITORY": proof.REPOSITORY,
            "GITHUB_SERVER_URL": "https://github.com",
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_WORKFLOW_REF": proof.WORKFLOW,
            "GITHUB_WORKFLOW_SHA": self.sha,
            "GITHUB_SHA": self.sha,
            "GITHUB_RUN_ID": "123",
            "GITHUB_RUN_ATTEMPT": "1",
        }

    def git(self, *args):
        return subprocess.check_output(
            ["git", *args],
            stderr=subprocess.DEVNULL,
            env={
                key: value
                for key, value in os.environ.items()
                if not key.startswith("GIT_")
            },
        ).decode()

    def check(self):
        with patch.dict(os.environ, self.env, clear=True):
            return proof.prove()

    def test_exact_main_checkout_and_pinned_blob_pass(self):
        result = self.check()
        self.assertEqual(result["providerSha"], self.sha)
        self.assertEqual(result["interactionCount"], 3)
        self.assertNotIn(self.temp.name, json.dumps(result))

    def test_pr_off_main_wrong_repository_and_workflow_reject(self):
        for key, value in [
            ("GITHUB_EVENT_NAME", "pull_request"),
            ("GITHUB_REF", "refs/heads/other"),
            ("GITHUB_REPOSITORY", "other/repo"),
            ("GITHUB_WORKFLOW_REF", "other/workflow"),
        ]:
            with self.subTest(key=key):
                original = self.env[key]
                self.env[key] = value
                with self.assertRaises(ValueError):
                    self.check()
                self.env[key] = original

    def test_wrong_head_and_workflow_sha_reject(self):
        for key in ["GITHUB_SHA", "GITHUB_WORKFLOW_SHA"]:
            original = self.env[key]
            self.env[key] = "0" * 40
            with self.assertRaises(ValueError):
                self.check()
            self.env[key] = original

    def test_dirty_build_and_staged_changes_reject(self):
        Path("build.gradle.kts").write_text("altered\n")
        with self.assertRaises(ValueError):
            self.check()
        self.git("add", "build.gradle.kts")
        with self.assertRaises(ValueError):
            self.check()

    def test_dirty_tracked_shared_input_rejects(self):
        Path("openbank-libs-runtime/src/main/Existing.kt").write_text("altered\n")
        with self.assertRaises(ValueError):
            self.check()

    def test_untracked_shared_input_rejects(self):
        path = Path("openbank-libs-runtime/src/main/Injected.kt")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("injected\n")
        with self.assertRaises(ValueError):
            self.check()

    def test_all_ignored_outputs_and_overlays_reject(self):
        for name in [
            "build/result",
            "openbank-ledger-service/build/Injected.class",
            ".gradle/init-overlay",
            "overlay.secret",
        ]:
            with self.subTest(name=name):
                path = Path(name)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("synthetic executable overlay\n")
                with self.assertRaises(ValueError):
                    self.check()
                path.unlink()

    def test_wrong_blob_digest_pair_and_count_reject(self):
        for key, value in [("BLOB", "0" * 40), ("DIGEST", "0" * 64), ("COUNT", 4)]:
            with patch.object(proof, key, value), self.assertRaises(ValueError):
                self.check()

    def test_original_pair_is_independently_checked(self):
        with patch.object(proof, "COUNT", 3):
            original = proof.git

            def wrong_pair(*args):
                raw = original(*args)
                return (
                    raw.replace(b"openbank-finrep-service", b"wrong-consumer")
                    if args[0] == "show"
                    else raw
                )

            raw = (
                self.git("show", f"HEAD:{proof.PACT_PATH}")
                .encode()
                .replace(
                    b"openbank-finrep-service",
                    b"wrong-consumer",
                )
            )
            with (
                patch.object(proof, "git", side_effect=wrong_pair),
                patch.object(
                    proof,
                    "DIGEST",
                    hashlib.sha256(raw).hexdigest(),
                ),
                self.assertRaises(ValueError),
            ):
                self.check()

    def test_hidden_tracked_build_input_rejects(self):
        for flag in ["--assume-unchanged", "--skip-worktree"]:
            with self.subTest(flag=flag):
                self.git("update-index", flag, "build.gradle.kts")
                Path("build.gradle.kts").write_text("hidden executable change\n")
                with self.assertRaises(ValueError):
                    self.check()
                Path("build.gradle.kts").write_text("// synthetic build input\n")
                self.git(
                    "update-index",
                    "--no-assume-unchanged",
                    "--no-skip-worktree",
                    "build.gradle.kts",
                )

    def test_git_context_overrides_reject(self):
        for key in [
            "GIT_INDEX_FILE",
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_COMMON_DIR",
            "GIT_CONFIG_COUNT",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        ]:
            with self.subTest(key=key):
                self.env[key] = "synthetic-context-override"
                with self.assertRaises(ValueError):
                    self.check()
                del self.env[key]

    def test_missing_main_history_rejects(self):
        self.git("update-ref", "-d", "refs/remotes/origin/main")
        with self.assertRaises(ValueError):
            self.check()

    def test_rerun_rejects_before_build_and_publication(self):
        self.env["GITHUB_RUN_ATTEMPT"] = "2"
        with self.assertRaises(ValueError):
            self.check()

    def post_build_fixture(self):
        proof_directory = tempfile.TemporaryDirectory()
        self.addCleanup(proof_directory.cleanup)
        proof_path = Path(proof_directory.name) / "checkout.json"
        proof_path.write_text(json.dumps(self.check()))
        self.env["FINREP_CHECKOUT_PROOF"] = str(proof_path)
        return proof_path

    def post_check(self):
        with patch.dict(os.environ, self.env, clear=True):
            return proof.prove(post_build=True)

    def test_post_build_allows_generated_outputs_but_prebuild_still_rejects(self):
        self.post_build_fixture()
        for name in ["build/generated.class", ".gradle/generated.bin", "openbank-libs-runtime/build/generated.class"]:
            path = Path(name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"synthetic generated output")
        self.assertEqual(self.post_check()["providerSha"], self.sha)
        with self.assertRaises(ValueError):
            self.check()

    def test_post_build_rejects_changed_source_and_index_flags(self):
        self.post_build_fixture()
        for flag in ["--assume-unchanged", "--skip-worktree"]:
            with self.subTest(flag=flag):
                self.git("update-index", flag, "build.gradle.kts")
                with self.assertRaises(ValueError):
                    self.post_check()
                self.git("update-index", "--no-assume-unchanged", "--no-skip-worktree", "build.gradle.kts")
        Path("build.gradle.kts").write_text("changed source\n")
        with self.assertRaises(ValueError):
            self.post_check()

    def test_post_build_rejects_outside_generated_overlays_and_tampered_manifest(self):
        proof_path = self.post_build_fixture()
        for name in ["overlay.secret", "new.gradle.kts", "openbank-libs-runtime/src/main/Injected.kt", "other/build/Injected.class"]:
            with self.subTest(name=name):
                path = Path(name)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("synthetic overlay")
                with self.assertRaises(ValueError):
                    self.post_check()
                path.unlink()
        original = json.loads(proof_path.read_text())
        original["trackedInputManifestSha256"] = "0" * 64
        proof_path.write_text(json.dumps(original))
        with self.assertRaises(ValueError):
            self.post_check()

    def test_committed_blob_comparison_rejects_clean_filter_hidden_bytes(self):
        self.git("config", "filter.synthetic.clean", "printf '// synthetic build input\\n'")
        Path(".git/info/attributes").write_text("/build.gradle.kts filter=synthetic\n")
        Path("build.gradle.kts").write_text("hidden executable input\n")
        self.assertEqual(self.git("diff", "--name-only", "HEAD"), "")
        with self.assertRaises(ValueError):
            self.check()

    def test_post_build_rejects_hidden_executable_mode_change(self):
        self.post_build_fixture()
        self.git("config", "core.filemode", "false")
        Path("build.gradle.kts").chmod(0o755)
        self.assertEqual(self.git("diff", "--name-only", "HEAD"), "")
        with self.assertRaises(ValueError):
            self.post_check()


if __name__ == "__main__":
    unittest.main()
