#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Synthetic artifact/provenance regressions; no network or cloud credentials."""
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SOURCE = Path(__file__).with_name("service-build-artifact.py")
SPEC = importlib.util.spec_from_file_location("service_build_artifact", SOURCE)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)
SHA = "a" * 40
REPO = "example/bank"
RUN = {"id": 123, "run_attempt": 2, "event": "push", "head_branch": "main", "head_sha": SHA,
       "path": MOD.WORKFLOW, "status": "completed", "conclusion": "success",
       "repository": {"full_name": REPO}, "head_repository": {"full_name": REPO}}


def archive(files):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", zipfile.ZIP_DEFLATED) as zipped:
        for name, data in files.items():
            zipped.writestr(name, data)
    return result.getvalue()


class BuildArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.producer = self.root / "producer"
        self.build = self.producer / MOD.SERVICE / "build"
        (self.build / "quarkus-app/lib/main").mkdir(parents=True)
        (self.build / "quarkus-app/quarkus-run.jar").write_bytes(b"runner")
        (self.build / "quarkus-app/lib/main/example.jar").write_bytes(b"library")
        (self.build / "reports").mkdir()
        (self.build / "reports/bom.json").write_text(json.dumps({"bomFormat": "CycloneDX", "components": [{"name": "example"}]}))
        self.out = self.root / "out"
        self.env = {"RUNNER_ARCH": "ARM64", "RUNNER_OS": "Linux", "ANALYTICS_SINK_TYPE": "clickhouse", "AGENT_SVID_NONCE_STORE": "redis"}
        with patch.dict(os.environ, self.env):
            MOD.pack(MOD.SERVICE, SHA, 123, 2, self.out, self.producer)
        self.bundle = (self.out / "deploy-build.zip").read_bytes()
        self.destination = self.root / "destination"
        (self.destination / MOD.SERVICE).mkdir(parents=True)
        self.item = {"id": 456, "expired": False, "size_in_bytes": 1024,
                     "name": MOD.artifact_name(SHA, 2),
                     "workflow_run": {"id": 123, "head_sha": SHA, "head_branch": "main"}}

    def validate(self, raw):
        return MOD.validate_bundle(raw, SHA, 123, 2)

    def modified_manifest(self, key, value):
        files = MOD.read_zip(self.bundle)
        manifest = json.loads(files["manifest.json"])
        manifest[key] = value
        files["manifest.json"] = json.dumps(manifest)
        return archive(files)

    def install_gh(self, *, run=None, item=None, bundle=None, fail=False):
        fixture = self.root / "fixture"
        fixture.mkdir(exist_ok=True)
        (fixture / "run.json").write_text(json.dumps(RUN if run is None else run))
        (fixture / "artifact.json").write_text(json.dumps(self.item if item is None else item))
        (fixture / "artifact.zip").write_bytes(archive({"deploy-build.zip": self.bundle if bundle is None else bundle}))
        stub = self.root / "gh"
        stub.write_text("#!/usr/bin/env python3\nimport json, os, pathlib, sys\np=pathlib.Path(os.environ['FIXTURE'])\n"
                        + ("sys.exit(1)\n" if fail else "")
                        + "endpoint=sys.argv[2]\n"
                        + "if endpoint.endswith('/zip'): sys.stdout.buffer.write((p/'artifact.zip').read_bytes())\n"
                        + "elif '/artifacts?' in endpoint: print(json.dumps({'artifacts':[json.loads((p/'artifact.json').read_text())]}))\n"
                        + "elif '/attempts/' in endpoint: print((p/'run.json').read_text())\n"
                        + "else: print(json.dumps({'workflow_runs':[json.loads((p/'run.json').read_text())]}))\n")
        stub.chmod(0o755)
        return {"PATH": str(self.root) + os.pathsep + os.environ["PATH"], "FIXTURE": str(fixture)}

    def restore_cli(self, **kwargs):
        output = self.root / "output"
        with patch.dict(os.environ, self.install_gh(**kwargs) | {"GITHUB_OUTPUT": str(output)}):
            result = subprocess.run([os.sys.executable, str(SOURCE), "restore", "--repo", REPO,
                                     "--service", MOD.SERVICE, "--sha", SHA,
                                     "--destination", str(self.destination)], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        return output.read_text()

    def test_pack_restore_roundtrip_through_stub_gh(self):
        self.assertEqual(self.restore_cli(), "restored=true\n")
        self.assertEqual((self.destination / MOD.SERVICE / "build/quarkus-app/lib/main/example.jar").read_bytes(), b"library")

    def test_manifest_identity_recipe_architecture_and_inventory(self):
        for key, value in {"sha": "b" * 40, "service": "openbank-ledger-service", "runId": 124,
                           "attempt": 1, "platform": "linux/amd64", "recipe": "other", "schemaVersion": True,
                           "files": {}}.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate(self.modified_manifest(key, value))

    def test_hash_and_unexpected_file(self):
        for name in ["quarkus-app/quarkus-run.jar", "unexpected"]:
            files = MOD.read_zip(self.bundle)
            files[name] = b"tampered"
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.validate(archive(files))

    def test_traversal_absolute_backslash_and_noncanonical_names(self):
        for name in ["../escape", "/escape", "quarkus-app/../escape", "quarkus-app\\escape", "./manifest.json", "quarkus-app//file"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                MOD.read_zip(archive({name: b"bad"}))

    def test_symlink_and_duplicate_entries(self):
        for symlink in [True, False]:
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w") as zipped:
                info = zipfile.ZipInfo("file")
                info.external_attr = (stat.S_IFLNK | 0o777) << 16 if symlink else 0
                zipped.writestr(info, b"target")
                if not symlink:
                    import warnings
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore")
                        zipped.writestr("file", b"again")
            with self.subTest(symlink=symlink), self.assertRaises(ValueError):
                MOD.read_zip(buffer.getvalue())

    def test_limits_and_duplicate_manifest_keys(self):
        with patch.object(MOD, "MAX_BYTES", 1), self.assertRaises(ValueError):
            self.validate(self.bundle)
        with self.assertRaises(ValueError):
            MOD.json_object('{"sha":"one","sha":"two"}')

    def test_untrusted_runs_are_misses(self):
        for key, value in {"event": "pull_request", "head_branch": "feature", "head_sha": "b" * 40,
                           "path": ".github/workflows/other.yml", "conclusion": "failure", "status": "in_progress",
                           "head_repository": {"full_name": "fork/bank"}, "repository": {"full_name": "other/bank"}}.items():
            run = copy.deepcopy(RUN)
            run[key] = value
            self.assertFalse(MOD.trusted_run(run, REPO, SHA), key)
        self.assertEqual(self.restore_cli(run=run), "restored=false\n")
        self.assertFalse((self.destination / MOD.SERVICE / "build").exists())

    def test_bad_artifact_does_not_publish_partial_outputs(self):
        self.assertEqual(self.restore_cli(bundle=self.modified_manifest("platform", "linux/amd64")), "restored=false\n")
        self.assertEqual(list((self.destination / MOD.SERVICE).iterdir()), [])

    def test_expired_artifact_and_api_failure_are_misses(self):
        item = self.item | {"expired": True}
        self.assertEqual(self.restore_cli(item=item), "restored=false\n")
        (self.root / "output").unlink()
        self.assertEqual(self.restore_cli(fail=True), "restored=false\n")

    def test_artifact_provenance_and_corrupt_archive_are_misses(self):
        for item in [self.item | {"workflow_run": {"id": 124, "head_sha": SHA, "head_branch": "main"}},
                     self.item | {"name": MOD.artifact_name(SHA, 1)}]:
            self.assertEqual(self.restore_cli(item=item), "restored=false\n")
            (self.root / "output").unlink()
        self.assertEqual(self.restore_cli(bundle=b"not a ZIP"), "restored=false\n")
        self.assertEqual(list((self.destination / MOD.SERVICE).iterdir()), [])

    def test_empty_sbom_and_missing_runner_are_rejected(self):
        (self.build / "reports/bom.json").write_text('{"bomFormat":"CycloneDX","components":[]}')
        with patch.dict(os.environ, self.env), self.assertRaises(ValueError):
            MOD.pack(MOD.SERVICE, SHA, 123, 2, self.out, self.producer)
        files = MOD.read_zip(self.bundle)
        del files["quarkus-app/quarkus-run.jar"]
        with self.assertRaises(ValueError):
            self.validate(archive(files))

    def test_existing_build_preserved(self):
        target = self.destination / MOD.SERVICE / "build"
        target.mkdir()
        (target / "sentinel").write_text("existing")
        self.assertEqual(self.restore_cli(), "restored=false\n")
        self.assertEqual((target / "sentinel").read_text(), "existing")

    def test_pack_rejects_wrong_producer_and_symlinks(self):
        for changed in [{"RUNNER_ARCH": "X64"}, {"RUNNER_OS": "macOS"}, {"AGENT_SVID_NONCE_STORE": "memory"}]:
            with patch.dict(os.environ, self.env | changed), self.assertRaises(ValueError):
                MOD.pack(MOD.SERVICE, SHA, 123, 2, self.out, self.producer)
        (self.build / "quarkus-app/link").symlink_to("quarkus-run.jar")
        with patch.dict(os.environ, self.env), self.assertRaises(ValueError):
            MOD.pack(MOD.SERVICE, SHA, 123, 2, self.out, self.producer)


if __name__ == "__main__":
    unittest.main()
