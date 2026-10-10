#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression fixtures for frozen Admin UI context and immutable image binding."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


def load(name: str, file: str):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    module = importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(module)
    return module


freeze_mod = load("freeze_context", "freeze-admin-ui-context.py")
verify_mod = load("verify_build", "verify-admin-ui-build-inputs.py")


class AdminUiImageInputsTest(unittest.TestCase):
    def test_every_host_collector_output_is_allowlisted(self):
        root = Path(__file__).resolve().parents[2]
        producer = (root / "openbank-infra/scripts/build-push-admin-ui.sh").read_text()
        dockerfile = (root / "openbank-admin-ui/Dockerfile").read_text()
        outputs = set(re.findall(r'^\w+_OUT="openbank-admin-ui/([\w-]+\.json)"', producer, re.M))
        self.assertGreaterEqual(len(outputs), 15)
        self.assertEqual(outputs - set(freeze_mod.GENERATED_ROOT_JSON), set())
        self.assertIn("for f in /repo/admin-ui-sbom-inputs/openbank-*/bom.json", dockerfile)

    def test_frozen_context_and_digest_binding(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            repo = base / "repo"
            repo.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Test"], check=True)
            dockerfile = repo / "openbank-admin-ui/Dockerfile"
            changelog = repo / "openbank-notification-service/CHANGELOG.md"
            dockerfile.parent.mkdir()
            changelog.parent.mkdir()
            dockerfile.write_text("FROM scratch\n")
            changelog.write_text("old release\n")
            (repo / ".gitignore").write_text("**/build/\n")
            subprocess.run(["git", "-C", str(repo), "add", "--", "openbank-admin-ui/Dockerfile",
                            "openbank-notification-service/CHANGELOG.md", ".gitignore"], check=True)
            subprocess.run(["git", "-C", str(repo), "-c", "commit.gpgsign=false", "commit", "-qm", "fixture"], check=True)
            source = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"],
                                    check=True, capture_output=True, text=True).stdout.strip()
            generated = repo / "openbank-admin-ui/catalog.json"
            generated.write_text('{"generated":true}\n')
            generated_evidence = repo / "openbank-admin-ui/client-test-evidence/run.json"
            generated_evidence.parent.mkdir()
            generated_evidence.write_text('{"completed":true}\n')
            staged_sbom = repo / "openbank-notification-service/build/reports/bom.json"
            staged_sbom.parent.mkdir(parents=True)
            staged_sbom.write_text('{"bomFormat":"CycloneDX","version":1}\n')
            for name in ("governance.json", "cost-footprints.json", "cluster-topology.json"):
                (repo / "openbank-admin-ui" / name).write_text('{"generated":true}\n')
            context = base / "context"
            manifest_path = base / "manifest.json"
            freeze_mod.freeze(repo, context, manifest_path)
            original_manifest = manifest_path.read_bytes()
            self.assertEqual((context / "openbank-notification-service/CHANGELOG.md").read_text(), "old release\n")
            inventory = json.loads(original_manifest)["files"]
            self.assertEqual((context / "openbank-admin-ui/client-test-evidence/run.json").read_text(),
                             '{"completed":true}\n')
            self.assertTrue(any(item["path"] == "openbank-admin-ui/client-test-evidence/run.json"
                                for item in inventory))
            frozen_sbom = context / "admin-ui-sbom-inputs/openbank-notification-service/bom.json"
            self.assertEqual(frozen_sbom.read_bytes(), staged_sbom.read_bytes())
            sbom_entry = next(item for item in inventory if item["path"] ==
                              "admin-ui-sbom-inputs/openbank-notification-service/bom.json")
            self.assertEqual(sbom_entry["sha256"], hashlib.sha256(staged_sbom.read_bytes()).hexdigest())
            self.assertEqual(subprocess.run(["git", "-C", str(repo), "ls-files", "--others",
                                             "--exclude-standard", "--", str(staged_sbom.relative_to(repo))],
                                            check=True, capture_output=True).stdout, b"")
            for name in ("governance.json", "cost-footprints.json", "cluster-topology.json"):
                self.assertTrue(any(item["path"] == "openbank-admin-ui/" + name for item in inventory))
                self.assertTrue((context / "openbank-admin-ui" / name).is_file())
            changelog.write_text("new release\n")
            self.assertEqual((context / "openbank-notification-service/CHANGELOG.md").read_text(), "old release\n")
            staged_sbom.write_text('{"bomFormat":"CycloneDX","version":2}\n')
            self.assertEqual(frozen_sbom.read_bytes(), b'{"bomFormat":"CycloneDX","version":1}\n')
            with self.assertRaisesRegex(ValueError, "tracked source input differs"):
                freeze_mod.freeze(repo, base / "context2", base / "manifest2.json")
            blobs, object_format = freeze_mod.committed_blobs(repo)
            with self.assertRaisesRegex(ValueError, "frozen tracked input differs"):
                freeze_mod.require_committed_content(
                    Path("openbank-notification-service/CHANGELOG.md"), b"new release\n",
                    blobs, object_format)
            changelog.write_text("old release\n")
            freeze_mod.freeze(repo, base / "context2", base / "manifest2.json")
            self.assertNotEqual(manifest_path.read_bytes(), (base / "manifest2.json").read_bytes())
            staged_sbom.write_text('{"bomFormat":"CycloneDX","version":1}\n')

            tag = "sandbox-" + source[:8]
            image = "example.invalid/openbank-admin-ui"
            record = {"schema": "openbank.admin-ui.image-build/v1", "image": image, "tag": tag,
                      "digest": "sha256:" + "a" * 64, "sourceCommit": source,
                      "contextManifestSha256": hashlib.sha256(original_manifest).hexdigest(),
                      "contextManifest": json.loads(original_manifest),
                      "buildArgs": {"BUILD_VERSION": "1.0.0", "BUILD_GIT_SHA": source[:8],
                                    "BUILD_DATE": "2026-10-08T00:00:00Z"}, "platform": "linux/arm64"}
            self.assertEqual(verify_mod.validate(record, original_manifest, image, tag), source)
            verify_mod.check_tag_digest(record, record["digest"])
            with self.assertRaises(ValueError):
                verify_mod.check_tag_digest(record, "sha256:" + "b" * 64)
            signed = [{"verificationResult": {"statement": {"predicate": record,
                       "subject": [{"name": image, "digest": {"sha256": "a" * 64}}]}}}]
            self.assertTrue(verify_mod.attestation_matches(record, signed))
            signed[0]["verificationResult"]["statement"]["subject"][0]["digest"]["sha256"] = "b" * 64
            self.assertFalse(verify_mod.attestation_matches(record, signed))
            with self.assertRaises(ValueError):
                verify_mod.validate(record, b"{}\n", image, tag)
            with self.assertRaises(ValueError):
                verify_mod.validate(record, original_manifest, image, "sandbox-deadbeef")
            (repo / "untracked-source.ts").write_text("unexpected\n")
            with self.assertRaisesRegex(ValueError, "unknown untracked"):
                freeze_mod.freeze(repo, base / "context3", base / "manifest3.json")
            (repo / "untracked-source.ts").unlink()
            staged_sbom.unlink()
            staged_sbom.symlink_to(changelog)
            with self.assertRaisesRegex(ValueError, "staged SBOM must not be a symlink"):
                freeze_mod.freeze(repo, base / "context4", base / "manifest4.json")

    def test_hostile_registry_is_refused_before_credentials_or_docker(self):
        with patch.dict("os.environ", {"ADMIN_UI_IMAGE_VERIFY_REGISTRY":
                                    "0" * 12 + ".dkr.ecr.example-1.amazonaws.com"}):
            with patch.object(verify_mod.subprocess, "run") as calls:
                with self.assertRaisesRegex(ValueError, "outside the trusted registry"):
                    verify_mod.verify(Path("."), "JiRaska/open-bank-oss",
                                      "attacker.invalid/openbank-admin-ui", "sandbox-aaaaaaaa")
                calls.assert_not_called()

    def test_missing_trusted_registry_fails_before_external_calls(self):
        with patch.dict("os.environ", {}, clear=True):
            with patch.object(verify_mod.subprocess, "run") as calls:
                with self.assertRaisesRegex(ValueError, "trusted Admin UI registry"):
                    verify_mod.verify(Path("."), "JiRaska/open-bank-oss",
                                      "attacker.invalid/openbank-admin-ui", "sandbox-aaaaaaaa")
                calls.assert_not_called()

    def test_image_readback_targets_validated_registry(self):
        account = "1" * 12  # synthetic account, never a live registry
        region = "test-1"
        registry = f"{account}.dkr.ecr.{region}.amazonaws.com"
        calls = []

        def command(*argv):
            calls.append(argv)
            if argv[:2] == ("git", "-C") and "rev-parse" in argv:
                return "a" * 40
            if argv[:2] == ("git", "-C") and "merge-base" in argv:
                return ""
            if argv[:3] == ("aws", "ecr", "describe-images"):
                raise RuntimeError("stop before external registry access")
            raise AssertionError(f"unexpected command: {argv}")

        with (
            patch.dict("os.environ", {"ADMIN_UI_IMAGE_VERIFY_REGISTRY": registry}, clear=True),
            patch.object(verify_mod, "run", side_effect=command),
            self.assertRaisesRegex(RuntimeError, "stop before external registry"),
        ):
            verify_mod.verify(Path("."), "JiRaska/open-bank-oss",
                              f"{registry}/openbank-admin-ui", "sandbox-aaaaaaaa")
        readback = calls[-1]
        self.assertEqual(readback[readback.index("--registry-id") + 1], account)
        self.assertEqual(readback[readback.index("--region") + 1], region)


if __name__ == "__main__":
    unittest.main()
