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
import zipfile
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
receipt_mod = load("record_artifact", "record-admin-ui-artifact.py")


class AdminUiImageInputsTest(unittest.TestCase):
    def test_archive_receipt_is_bound_to_exact_staged_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "upstream.zip"
            staged = root / "openbank-admin-ui/perf-artifacts/summary.json"
            staged.parent.mkdir(parents=True)
            with zipfile.ZipFile(archive, "w") as zipped:
                zipped.writestr("summary.json", b'{"state":"passed"}\n')
                zipped.writestr("unrelated.json", b'{"state":"failed"}\n')
            staged.write_bytes(b'{"state":"passed"}\n')
            ledger = root / "receipts.jsonl"
            receipt_mod.record(archive, ledger, "performance-actions-artifact", "42", root,
                               [("summary.json", staged.relative_to(root))])
            item = json.loads(ledger.read_text())
            self.assertEqual(item["path"], "openbank-admin-ui/perf-artifacts/summary.json")
            self.assertEqual(item["artifactId"], "42")
            self.assertEqual(item["member"], "summary.json")
            self.assertEqual(item["archiveSha256"], hashlib.sha256(archive.read_bytes()).hexdigest())
            staged.write_bytes(b'{"state":"tampered"}\n')
            with self.assertRaisesRegex(ValueError, "differs from named Actions artifact member"):
                receipt_mod.record(archive, ledger, "performance-actions-artifact", "42", root,
                                   [("summary.json", staged)])
            # The same bytes exist in the archive, but at the wrong member.
            staged.write_bytes(b'{"state":"failed"}\n')
            with self.assertRaisesRegex(ValueError, "differs from named Actions artifact member"):
                receipt_mod.record(archive, ledger, "performance-actions-artifact", "42", root,
                                   [("summary.json", staged)])
            self.assertEqual(len(ledger.read_text().splitlines()), 1)
            with self.assertRaisesRegex(ValueError, "invalid staged artifact file"):
                receipt_mod.record(archive, ledger, "performance-actions-artifact", "42", root,
                                   [("summary.json", root / "openbank-admin-ui/perf-artifacts/absent.json")])

    def test_every_host_collector_output_is_allowlisted(self):
        root = Path(__file__).resolve().parents[2]
        producer = (root / "openbank-infra/scripts/build-push-admin-ui.sh").read_text()
        outputs = set(re.findall(r'^\w+_OUT="openbank-admin-ui/([\w-]+\.json)"', producer, re.M))
        self.assertGreaterEqual(len(outputs), 15)
        self.assertEqual(outputs - set(freeze_mod.GENERATED_ROOT_JSON), set())

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
            pin = repo / "openbank-infra/gitops/components/admin-ui/admin-ui.yaml"
            dockerfile.parent.mkdir()
            changelog.parent.mkdir()
            pin.parent.mkdir(parents=True)
            dockerfile.write_text("FROM scratch\n")
            changelog.write_text("old release\n")
            pin.write_text("image: example.invalid/openbank-admin-ui:sandbox-old\n")
            subprocess.run(["git", "-C", str(repo), "add", "--", "openbank-admin-ui/Dockerfile",
                            "openbank-notification-service/CHANGELOG.md",
                            "openbank-infra/gitops/components/admin-ui/admin-ui.yaml"], check=True)
            subprocess.run(["git", "-C", str(repo), "-c", "commit.gpgsign=false", "commit", "-qm", "fixture"], check=True)
            source = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"],
                                    check=True, capture_output=True, text=True).stdout.strip()
            generated = repo / "openbank-admin-ui/catalog.json"
            generated.write_text('{"generated":true}\n')
            generated_evidence = repo / "openbank-admin-ui/client-test-evidence/openbank-app-123.json"
            generated_evidence.parent.mkdir()
            generated_evidence.write_text('{"completed":true}\n')
            sbom = repo / "openbank-notification-service/build/reports/bom.json"
            sbom.parent.mkdir(parents=True)
            sbom.write_text('{"bom":true}\n')
            flat_sbom = repo / "sbom-staging/openbank-notification-service.json"
            flat_sbom.parent.mkdir()
            flat_sbom.write_bytes(sbom.read_bytes())
            service_inputs = (
                "openbank-notification-service/build/test-intelligence/run.json",
                "openbank-notification-service/build/test-results/test/TEST-Sample.xml",
                "openbank-notification-service/build/reports/kover/report.xml",
                "openbank-notification-service/build/reports/pitest/mutations.xml",
                "openbank-notification-service/build/reports/pitest/test-intelligence-run.json",
            )
            for path in service_inputs:
                staged_input = repo / path
                staged_input.parent.mkdir(parents=True, exist_ok=True)
                staged_input.write_text(f"evidence for {path}\n")
            history = repo / "openbank-admin-ui/test-run-history"
            history.mkdir()
            (history / ".staged-ids").write_text("789\n")
            (history / "test-intelligence-run-example.json").write_text('{"run":1}\n')
            for name in ("governance.json", "cost-footprints.json", "cluster-topology.json"):
                (repo / "openbank-admin-ui" / name).write_text('{"generated":true}\n')
            context = base / "context"
            manifest_path = base / "manifest.json"
            receipts = base / "receipts.jsonl"
            def receipt(source_name, artifact_id, path):
                return {"source": source_name, "artifactId": artifact_id,
                        "archiveSha256": "a" * 64, "member": Path(path).name, "path": path,
                        "sha256": hashlib.sha256((repo / path).read_bytes()).hexdigest()}
            receipts.write_text('\n'.join(json.dumps(item) for item in (
                receipt("client-actions-artifact", "123", "openbank-admin-ui/client-test-evidence/openbank-app-123.json"),
                receipt("security-actions-artifact", "456", "openbank-notification-service/build/reports/bom.json"),
                receipt("security-actions-artifact", "456", "sbom-staging/openbank-notification-service.json"),
                receipt("test-intelligence-actions-artifact", "789", "openbank-admin-ui/test-run-history/test-intelligence-run-example.json"),
                *(receipt("pitest-actions-artifact" if "/pitest/" in path else "per-service-actions-artifact",
                          "901" if "/pitest/" in path else "900", path) for path in service_inputs),
            )) + '\n')
            with patch.dict("os.environ", {"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "12345",
                                        "GITHUB_RUN_ATTEMPT": "1", "GITHUB_WORKFLOW_REF":
                                        "JiRaska/open-bank-oss/.github/workflows/admin-ui-deploy.yml@refs/heads/main",
                                        "ADMIN_UI_FEED_RECEIPTS": str(receipts)}):
                freeze_mod.freeze(repo, context, manifest_path)
            original_manifest = manifest_path.read_bytes()
            self.assertEqual((context / "openbank-notification-service/CHANGELOG.md").read_text(), "old release\n")
            inventory = json.loads(original_manifest)["files"]
            self.assertEqual((context / "openbank-admin-ui/client-test-evidence/openbank-app-123.json").read_text(),
                             '{"completed":true}\n')
            self.assertTrue(any(item["path"] == "openbank-admin-ui/client-test-evidence/openbank-app-123.json"
                                for item in inventory))
            self.assertTrue(any(item["path"] == "openbank-notification-service/build/reports/bom.json"
                                for item in inventory))
            self.assertTrue(any(item["path"] == "sbom-staging/openbank-notification-service.json"
                                for item in inventory))
            self.assertTrue(all(any(item["path"] == path for item in inventory) for path in service_inputs))
            self.assertEqual((context / "openbank-notification-service/build/reports/bom.json").read_bytes(),
                             sbom.read_bytes())
            self.assertEqual((context / "sbom-staging/openbank-notification-service.json").read_bytes(),
                             sbom.read_bytes())
            # An expired cache entry has no currently verifiable upstream archive.
            # It must not inherit the receipt of another history file.
            stale = history / "expired-but-cached.json"
            stale.write_text('{"state":"passed"}\n')
            with patch.dict("os.environ", {"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "12345",
                                        "GITHUB_RUN_ATTEMPT": "1", "GITHUB_WORKFLOW_REF":
                                        "JiRaska/open-bank-oss/.github/workflows/admin-ui-deploy.yml@refs/heads/main",
                                        "ADMIN_UI_FEED_RECEIPTS": str(receipts)}):
                with self.assertRaisesRegex(ValueError, "lacks a source artifact receipt"):
                    freeze_mod.freeze(repo, base / "stale-context", base / "stale-manifest.json")
            stale.unlink()
            full_ledger = receipts.read_text()
            for missing_path in (service_inputs[1], service_inputs[3]):
                receipts.write_text("\n".join(line for line in full_ledger.splitlines()
                                              if json.loads(line)["path"] != missing_path) + "\n")
                with patch.dict("os.environ", {"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "12345",
                                            "GITHUB_RUN_ATTEMPT": "1", "GITHUB_WORKFLOW_REF":
                                            "JiRaska/open-bank-oss/.github/workflows/admin-ui-deploy.yml@refs/heads/main",
                                            "ADMIN_UI_FEED_RECEIPTS": str(receipts)}):
                    with self.assertRaisesRegex(ValueError, "lacks a source artifact receipt"):
                        freeze_mod.freeze(repo, base / f"missing-{Path(missing_path).name}",
                                          base / f"missing-{Path(missing_path).name}.json")
            receipts.write_text(full_ledger)
            malicious = json.loads(original_manifest)
            forged = next(item for item in malicious["files"] if item["path"] ==
                          "openbank-admin-ui/test-run-history/test-intelligence-run-example.json")
            forged["sha256"] = "0" * 64
            forged["material"]["sha256"] = "0" * 64
            self.assertFalse(freeze_mod.receipts_cover(malicious["files"], malicious["externalFeeds"]))
            malicious = json.loads(original_manifest)
            forged = next(item for item in malicious["files"] if item["path"] ==
                          "openbank-admin-ui/test-run-history/test-intelligence-run-example.json")
            forged["path"] = "openbank-admin-ui/test-run-history/attacker.json"
            self.assertFalse(freeze_mod.receipts_cover(malicious["files"], malicious["externalFeeds"]))
            malicious = json.loads(original_manifest)
            forged = next(item for item in malicious["files"] if item["path"] ==
                          "openbank-admin-ui/test-run-history/test-intelligence-run-example.json")
            forged["material"]["artifactId"] = "123"
            self.assertFalse(freeze_mod.receipts_cover(malicious["files"], malicious["externalFeeds"]))
            for name in ("governance.json", "cost-footprints.json", "cluster-topology.json"):
                self.assertTrue(any(item["path"] == "openbank-admin-ui/" + name for item in inventory))
                self.assertTrue((context / "openbank-admin-ui" / name).is_file())
            changelog.write_text("new release\n")
            self.assertEqual((context / "openbank-notification-service/CHANGELOG.md").read_text(), "old release\n")
            with self.assertRaisesRegex(ValueError, "tracked source input differs"):
                freeze_mod.freeze(repo, base / "context2", base / "manifest2.json")
            blobs, object_format = freeze_mod.committed_blobs(repo)
            with self.assertRaisesRegex(ValueError, "frozen tracked input differs"):
                freeze_mod.require_committed_content(
                    Path("openbank-notification-service/CHANGELOG.md"), b"new release\n",
                    blobs, object_format)
            changelog.write_text("old release\n")

            tag = "sandbox-" + source[:8]
            image = "example.invalid/openbank-admin-ui"
            record = {"schema": "openbank.admin-ui.image-build/v1", "image": image, "tag": tag,
                      "digest": "sha256:" + "a" * 64, "sourceCommit": source,
                      "contextManifestSha256": hashlib.sha256(original_manifest).hexdigest(),
                      "contextManifest": json.loads(original_manifest),
                      "buildArgs": {"BUILD_VERSION": "1.0.0", "BUILD_GIT_SHA": source[:8],
                                    "BUILD_DATE": "2026-10-08T00:00:00Z"}, "platform": "linux/arm64"}
            self.assertEqual(verify_mod.validate(record, original_manifest, image, tag), source)
            self.assertTrue(verify_mod.materialized_context_matches(record, repo))
            dockerfile.write_text("FROM busybox\n")
            self.assertFalse(verify_mod.materialized_context_matches(record, repo))
            dockerfile.write_text("FROM scratch\n")
            record["contextManifest"]["externalFeeds"] = []
            self.assertFalse(verify_mod.materialized_context_matches(record, repo))
            record["contextManifest"]["externalFeeds"] = json.loads(original_manifest)["externalFeeds"]
            generated.write_text('{"generated":false}\n')
            self.assertTrue(verify_mod.materialized_context_matches(record, repo))
            generated.write_text('{"generated":true}\n')
            generated_evidence.write_text('{"completed":false}\n')
            self.assertTrue(verify_mod.materialized_context_matches(record, repo))
            generated_evidence.write_text('{"completed":true}\n')
            sbom.write_text('{"bom":false}\n')
            self.assertTrue(verify_mod.materialized_context_matches(record, repo))
            with self.assertRaisesRegex(ValueError, "flat SBOM differs"):
                freeze_mod.paths(repo)
            sbom.write_text('{"bom":true}\n')
            generated.unlink()
            generated_evidence.unlink()
            sbom.unlink()
            flat_sbom.unlink()
            self.assertTrue(verify_mod.materialized_context_matches(record, repo))
            pin.write_text("image: example.invalid/openbank-admin-ui:sandbox-new\n")
            subprocess.run(["git", "-C", str(repo), "add", "--",
                            "openbank-infra/gitops/components/admin-ui/admin-ui.yaml"], check=True)
            subprocess.run(["git", "-C", str(repo), "-c", "commit.gpgsign=false", "commit", "-qm",
                            "chore(admin-ui): deploy sandbox-new"], check=True)
            self.assertTrue(verify_mod.materialized_context_matches(record, repo))
            record["contextManifest"]["files"][0]["material"] = {"kind": "unverified-local"}
            self.assertFalse(verify_mod.materialized_context_matches(record, repo))
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
            receipts.write_text(json.dumps(json.loads(original_manifest)["externalFeeds"][0]) + '\n')
            with patch.dict("os.environ", {"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "12345",
                                        "GITHUB_RUN_ATTEMPT": "1", "GITHUB_WORKFLOW_REF":
                                        "JiRaska/open-bank-oss/.github/workflows/admin-ui-deploy.yml@refs/heads/main",
                                        "ADMIN_UI_FEED_RECEIPTS": str(receipts)}):
                with self.assertRaisesRegex(ValueError, "lacks a source artifact receipt"):
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


if __name__ == "__main__":
    unittest.main()
