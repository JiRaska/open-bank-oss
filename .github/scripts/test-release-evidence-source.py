# SPDX-License-Identifier: Apache-2.0
"""Exact release source differs from the workflow invocation source."""
from __future__ import annotations
import json
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

# The gate runner supplies an isolated GIT_INDEX_FILE for the repository under
# test. Fixture repositories must never inherit that unrelated index or git dir.
CLEAN_ENV = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
SCRIPTS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("release_source", SCRIPTS / "prepare-release-evidence.py")
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)

class ReleaseSourceTest(unittest.TestCase):
    def test_workflow_prepares_and_rechecks_source_before_sign_and_upload(self):
        import yaml
        workflow = yaml.safe_load((SCRIPTS.parent / "workflows/release-please.yml").read_text())
        steps = workflow["jobs"]["release-evidence"]["steps"]
        script = next(x["run"] for x in steps if x.get("name") == "Build, sign and attach the release evidence bundle")
        prepared = script.index('source_commit="$(python3 .github/scripts/prepare-release-evidence.py')
        signed = script.index('sign "$sbom_out"', prepared)
        verified = script.index('python3 .github/scripts/verify-release-evidence-manifest.py', signed)
        rechecked = script.index('--check-remote "$tag" "$source_commit"', verified)
        uploaded = script.index('upload_asset "$upurl"', rechecked)
        self.assertLess(prepared, signed)
        self.assertLess(signed, verified)
        self.assertLess(verified, rechecked)
        self.assertLess(rechecked, uploaded)
        self.assertFalse(any(x.get("name") == "Generate per-component SBOMs" for x in steps))


    def test_builder_uses_actual_release_checkout_not_trigger_sha(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, env=CLEAN_ENV, text=True).strip()
            git("init", "-q")
            git("config", "user.email", "test@example.invalid")
            git("config", "user.name", "Fixture")
            (root / "module").mkdir()
            version = root / "module/version.txt"
            version.write_text("1.0.0\n")
            git("add", "module/version.txt")
            git("-c", "commit.gpgsign=false", "commit", "-qm", "trigger")
            trigger = git("rev-parse", "HEAD")
            version.write_text("1.1.0\n")
            git("-c", "commit.gpgsign=false", "commit", "-qam", "release")
            release = git("rev-parse", "HEAD")
            git("tag", "fixture-v1.1.0")
            (root / "fixture-v1.1.0.cdx.json").write_text('{"bomFormat":"CycloneDX"}')
            binaries = root / "bin"
            binaries.mkdir()
            (binaries / "trivy").write_text("#!/bin/sh\nexit 1\n")
            (binaries / "trivy").chmod(0o755)
            env = {**CLEAN_ENV, "PATH": str(binaries) + os.pathsep + os.environ["PATH"], "GITHUB_SHA": trigger, "RELEASE_EVIDENCE_SOURCE_SHA": release}
            subprocess.run(["bash", str(SCRIPTS / "build-release-evidence.sh"), "fixture", "1.1.0", "fixture-v1.1.0", "module", "fixture-v1.1.0.cdx.json"], cwd=root, env=env, check=True, capture_output=True)
            manifest = json.loads((root / "fixture-v1.1.0.evidence.json").read_text())
            self.assertEqual(manifest["git_commit"], release)


class IsolatedSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.remote = self.root / "remote"
        self.remote.mkdir()
        self.git(self.remote, "init", "-q")
        self.git(self.remote, "config", "user.email", "test@example.invalid")
        self.git(self.remote, "config", "user.name", "Fixture")
        self.module = self.remote / "module"
        self.module.mkdir()
        (self.module / "version.txt").write_text("1.0.0\n")
        (self.module / "build.gradle.kts").write_text("// fixture\n")
        (self.remote / "release-please-config.json").write_text(json.dumps({"packages": {"module": {"component": "fixture"}}}))
        (self.remote / "gradlew").write_text("""#!/usr/bin/env python3
import json, pathlib, subprocess, os, sys
if os.environ.get('FAIL_BUILD'):
    sys.exit(7)
r=pathlib.Path('module/build/reports');r.mkdir(parents=True)
version=pathlib.Path('module/version.txt').read_text().strip()
sha=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
(r/'bom.json').write_text(json.dumps({'bomFormat':'CycloneDX','metadata':{'component':{'version':version}},'source':sha,'sourceEnvironment':os.environ.get('SOURCE_COMMIT'),'invocationEnvironment':os.environ.get('GITHUB_SHA')}))
if os.environ.get('MOVE_REMOTE'):
    subprocess.run(['git','-C',os.environ['MOVE_REMOTE'],'tag','-f','fixture-v1.1.0',os.environ['TRIGGER']],check=True,stdout=sys.stderr)
""")
        (self.remote / "gradlew").chmod(0o755)
        self.git(self.remote, "add", "module", "gradlew", "release-please-config.json")
        self.git(self.remote, "-c", "commit.gpgsign=false", "commit", "-qm", "trigger")
        self.trigger = self.git(self.remote, "rev-parse", "HEAD")
        (self.module / "version.txt").write_text("1.1.0\n")
        self.git(self.remote, "-c", "commit.gpgsign=false", "commit", "-qam", "release")
        self.release = self.git(self.remote, "rev-parse", "HEAD")
        self.git(self.remote, "tag", "fixture-v1.1.0")
        self.checkout = self.root / "producer"
        self.git(self.root, "clone", "-q", str(self.remote), str(self.checkout))
        self.git(self.checkout, "checkout", "-q", self.trigger)
        stale = self.checkout / "module/build/reports"
        stale.mkdir(parents=True)
        (stale / "bom.json").write_text('{"stale":true}')
        binaries = self.root / "bin"
        binaries.mkdir()
        (binaries / "trivy").write_text("#!/bin/sh\nexit 1\n")
        (binaries / "trivy").chmod(0o755)
        self.env = {**CLEAN_ENV, "PATH": str(binaries) + os.pathsep + os.environ["PATH"], "GITHUB_SHA": self.trigger, "GITHUB_REPOSITORY": "example/fixture"}

    def git(self, cwd, *args):
        return subprocess.check_output(["git", *args], cwd=cwd, env=CLEAN_ENV, text=True).strip()

    def prepare(self, component="fixture", version="1.1.0", tag="fixture-v1.1.0", **env):
        return subprocess.run(["python3", str(SCRIPTS / "prepare-release-evidence.py"), "module", component, version, tag], cwd=self.checkout, env={**self.env, **env}, capture_output=True, text=True)

    def test_tag_checkout_drives_actual_version_and_sbom(self):
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), self.release)
        bom = json.loads((self.checkout / "fixture-v1.1.0.cdx.json").read_text())
        self.assertEqual(bom["source"], self.release)
        self.assertEqual(bom["sourceEnvironment"], self.release)
        self.assertEqual(bom["invocationEnvironment"], self.trigger)
        self.assertEqual(bom["metadata"]["component"]["version"], "1.1.0")
        manifest = json.loads((self.checkout / "fixture-v1.1.0.evidence.json").read_text())
        self.assertEqual(manifest["git_commit"], self.release)
        self.assertEqual(self.git(self.checkout, "rev-parse", "HEAD"), self.trigger)
        self.assertEqual(len(self.git(self.checkout, "worktree", "list", "--porcelain").split("worktree "))-1, 1)

    def test_failed_jvm_build_cannot_reuse_stale_bom_or_fallback(self):
        result = self.prepare(FAIL_BUILD="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.checkout / "fixture-v1.1.0.evidence.json").exists())

    def test_wrong_source_version_rejected(self):
        self.git(self.remote, "tag", "fixture-v9.0.0")
        result = self.prepare(version="9.0.0", tag="fixture-v9.0.0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("version differs", result.stderr)

    def test_wrong_component_rejected(self):
        self.git(self.remote, "tag", "other-v1.1.0")
        result = self.prepare(component="other", tag="other-v1.1.0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("component differs", result.stderr)

    def test_missing_tag_rejected(self):
        self.assertNotEqual(self.prepare(version="2.0.0", tag="fixture-v2.0.0").returncode, 0)

    def test_moved_remote_tag_rejected_before_output(self):
        result = self.prepare(MOVE_REMOTE=str(self.remote), TRIGGER=self.trigger)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("tag moved", result.stderr)
        self.assertFalse((self.checkout / "fixture-v1.1.0.evidence.json").exists())

    def test_annotated_release_tag_is_peeled_to_commit(self):
        self.git(self.remote, "-c", "tag.gpgSign=false", "tag", "-fa", "fixture-v1.1.0", "-m", "release")
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), self.release)

    def test_blob_tag_is_not_a_buildable_source(self):
        blob = self.git(self.remote, "rev-parse", "HEAD:module/version.txt")
        self.git(self.remote, "tag", "-f", "fixture-v1.1.0", blob)
        self.assertNotEqual(self.prepare().returncode, 0)
        self.assertFalse((self.checkout / "fixture-v1.1.0.evidence.json").exists())

    def test_second_release_uses_its_own_source(self):
        first = self.prepare()
        self.assertEqual(first.returncode, 0, first.stderr)
        (self.module / "version.txt").write_text("1.2.0\n")
        self.git(self.remote, "-c", "commit.gpgsign=false", "commit", "-qam", "next release")
        next_commit = self.git(self.remote, "rev-parse", "HEAD")
        self.git(self.remote, "tag", "fixture-v1.2.0")
        second = self.prepare(version="1.2.0", tag="fixture-v1.2.0")
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(second.stdout.strip(), next_commit)
        self.assertEqual(json.loads((self.checkout / "fixture-v1.2.0.cdx.json").read_text())["source"], next_commit)
        self.assertEqual(json.loads((self.checkout / "fixture-v1.1.0.cdx.json").read_text())["source"], self.release)

    def test_unsafe_tag_is_rejected_without_git_interpretation(self):
        self.assertNotEqual(self.prepare(component="../fixture", tag="../fixture-v1.1.0").returncode, 0)

    def test_non_gradle_source_uses_trivy_in_exact_checkout(self):
        self.git(self.remote, "rm", "module/build.gradle.kts")
        self.git(self.remote, "-c", "commit.gpgsign=false", "commit", "-qm", "non Gradle fixture")
        source_commit = self.git(self.remote, "rev-parse", "HEAD")
        self.git(self.remote, "tag", "-f", "fixture-v1.1.0")
        (self.root / "bin/trivy").write_text("""#!/usr/bin/env python3
import json, pathlib, subprocess, sys
if sys.argv[1] != 'fs':
    sys.exit(1)
output=pathlib.Path(sys.argv[sys.argv.index('--output')+1])
output.write_text(json.dumps({'bomFormat':'CycloneDX','source':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()}))
""")
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads((self.checkout / "fixture-v1.1.0.cdx.json").read_text())["source"], source_commit)

    def test_empty_or_malformed_explicit_source_never_falls_back(self):
        for value in ("", "not-a-sha"):
            with self.subTest(value=value):
                result = subprocess.run(["bash", str(SCRIPTS / "build-release-evidence.sh"), "fixture", "1.1.0", "fixture-v1.1.0", "module", "missing.cdx.json"], cwd=self.checkout, env={**self.env, "RELEASE_EVIDENCE_SOURCE_SHA": value}, capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("does not match", result.stderr)
                self.assertFalse((self.checkout / "fixture-v1.1.0.evidence.json").exists())

    def test_explicit_builder_source_mismatch_rejected(self):
        result = subprocess.run(["bash", str(SCRIPTS / "build-release-evidence.sh"), "fixture", "1.1.0", "fixture-v1.1.0", "module", "missing.cdx.json"], cwd=self.checkout, env={**self.env, "RELEASE_EVIDENCE_SOURCE_SHA": self.release}, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not match", result.stderr)
        self.assertFalse((self.checkout / "fixture-v1.1.0.evidence.json").exists())


if __name__ == "__main__":
    unittest.main()
