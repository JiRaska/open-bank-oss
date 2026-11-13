#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Byte-backed negative fixtures for the bounded original-artifact claim."""
import copy
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import re
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("verifier", Path(__file__).with_name("verify-packaged-library-sbom.py"))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


class PackagedLibraryEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.package = self.root / "quarkus-app"
        (self.package / "lib/main").mkdir(parents=True)
        (self.package / "quarkus").mkdir()
        (self.package / "quarkus-run.jar").write_bytes(b"runner")
        self.artifact = self.root / "original.jar"
        self.jar(self.artifact, {"com/openbank/libs/Example.class": b"original class"})
        self.packaged = self.package / "lib/main/arbitrary-name.jar"
        self.packaged.write_bytes(self.artifact.read_bytes())
        self.digest = hashlib.sha256(self.artifact.read_bytes()).hexdigest()
        self.component = {"group": "com.openbank", "name": "openbank-libs-domain", "version": "0.1.0-SNAPSHOT",
                          "purl": "pkg:maven/com.openbank/openbank-libs-domain@0.1.0-SNAPSHOT?project_path=%3Aopenbank-libs-domain",
                          "hashes": [{"alg": "SHA-256", "content": self.digest}]}
        self.sbom = {"bomFormat": "CycloneDX", "components": [self.component]}
        self.resolved = [{"projectPath": ":openbank-libs-domain", "file": str(self.artifact)}]

    @staticmethod
    def jar(path, entries):
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)

    def run_verifier(self):
        return v.verify(self.sbom, self.resolved, self.package)

    def rejected(self, expected):
        with self.assertRaisesRegex(v.EvidenceError, expected):
            self.run_verifier()

    def test_original_bytes_are_verified_without_filename_matching(self):
        result = self.run_verifier()
        self.assertEqual(result["libraries"], [{"projectPath": ":openbank-libs-domain", "sha256": self.digest, "packagedPath": "lib/main/arbitrary-name.jar"}])
        self.assertFalse(result["effectiveRuntimeIdentityVerified"])
        self.assertFalse(result["deploymentVerified"])

    def test_transformed_classes_are_reported_not_claimed_identical(self):
        self.jar(self.package / "quarkus/transformed-bytecode.jar", {"com/openbank/libs/Example.class": b"different augmented class"})
        result = self.run_verifier()
        self.assertEqual(result["augmentation"][0]["overlappingLibraryClasses"], 1)
        self.assertFalse(result["effectiveRuntimeIdentityVerified"])

    def test_receipt_rejects_omitted_and_duplicated_rows(self):
        for mode in ("omit", "duplicate"):
            receipt = self.run_verifier()
            receipt["libraries"] = [] if mode == "omit" else receipt["libraries"] * 2
            with self.assertRaises(v.EvidenceError):
                v.verify_receipt(self.sbom, receipt, self.package)

    def test_receipt_rejects_claim_schema_and_path_changes(self):
        for field, value in (("schemaVersion", True), ("effectiveRuntimeIdentityVerified", 0), ("deploymentVerified", 0), ("schemaVersion", 2), ("claim", "runtime"), ("effectiveRuntimeIdentityVerified", True), ("deploymentVerified", True)):
            receipt = self.run_verifier()
            receipt[field] = value
            with self.assertRaises(v.EvidenceError):
                v.verify_receipt(self.sbom, receipt, self.package)
        receipt = self.run_verifier()
        receipt["libraries"][0]["packagedPath"] = "../original.jar"
        with self.assertRaises(v.EvidenceError):
            v.verify_receipt(self.sbom, receipt, self.package)

    def test_restored_receipt_rechecks_bytes_and_augmentation(self):
        receipt = self.run_verifier()
        self.assertEqual(v.verify_receipt(self.sbom, receipt, self.package), receipt)
        self.jar(self.package / "quarkus/transformed-bytecode.jar", {"com/openbank/libs/Example.class": b"changed"})
        with self.assertRaises(v.EvidenceError):
            v.verify_receipt(self.sbom, receipt, self.package)

    def test_receipt_is_independent_of_augmentation_enumeration_order(self):
        self.jar(self.package / "quarkus/a.jar", {"com/openbank/libs/A.class": b"a"})
        self.jar(self.package / "quarkus/z.jar", {"com/openbank/libs/Z.class": b"z"})
        receipt = self.run_verifier()
        original = Path.rglob
        def reversed_walk(path, pattern):
            return iter(reversed(list(original(path, pattern))))
        with patch.object(Path, "rglob", reversed_walk):
            self.assertEqual(v.verify_receipt(self.sbom, receipt, self.package), receipt)

    def test_every_auto_deploy_jvm_consumer_has_shared_verifier_convention(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/auto-deploy.yml").read_text()
        services = re.search(r"ALL_SERVICES='([^']+)'", workflow).group(1).split()
        excluded = re.search(r'SELF_BUILT_SERVICES: "([^"]+)"', workflow).group(1).split()
        for service in set(services) - set(excluded):
            with self.subTest(service=service):
                source = (root / service / "build.gradle.kts").read_text()
                self.assertTrue('id("openbank.quarkus-service")' in source or
                                'id("openbank.packaged-library-sbom")' in source)
        analytics = (root / "openbank-analytics-sink/build.gradle.kts").read_text()
        self.assertNotIn('id("openbank.quarkus-service")', analytics)
        self.assertIn('id("openbank.packaged-library-sbom")', analytics)

    def test_resolution_only_sbom_is_incomplete(self):
        self.component.pop("hashes")
        self.rejected("missing library hashes")

    def test_changed_packaged_bytes_with_same_coordinate_are_rejected(self):
        self.jar(self.packaged, {"com/openbank/libs/Example.class": b"changed"})
        self.rejected("missing or ambiguous")

    def test_stale_sbom_hash_is_rejected(self):
        self.component["hashes"][0]["content"] = "0" * 64
        self.rejected("hash mismatch")

    def test_duplicate_hash_is_rejected(self):
        self.component["hashes"].append(copy.deepcopy(self.component["hashes"][0]))
        self.rejected("duplicate library SHA")

    def test_malformed_hash_is_rejected(self):
        self.component["hashes"][0]["content"] = "not a digest"
        self.rejected("invalid or duplicate")

    def test_duplicate_component_is_rejected(self):
        self.sbom["components"].append(copy.deepcopy(self.component))
        self.rejected("duplicate library component")

    def test_missing_transitive_component_is_rejected(self):
        self.sbom["components"] = []
        self.rejected("inventory mismatch")

    def test_duplicate_resolved_artifact_is_rejected(self):
        self.resolved.append(copy.deepcopy(self.resolved[0]))
        self.rejected("duplicate or missing resolved")

    def test_ambiguous_packaged_bytes_are_rejected(self):
        (self.package / "lib/main/second.jar").write_bytes(self.artifact.read_bytes())
        self.rejected("missing or ambiguous")

    def test_unexpected_internal_artifact_is_rejected_by_content(self):
        self.jar(self.package / "lib/main/opaque.jar", {"com/openbank/libs/Unexpected.class": b"extra"})
        self.rejected("unexpected internal")

    def test_external_dependency_does_not_become_an_internal_library(self):
        self.jar(self.package / "lib/main/external.jar", {"org/example/External.class": b"external"})
        self.assertEqual(len(self.run_verifier()["libraries"]), 1)

    def test_duplicate_purl_qualifier_is_rejected(self):
        self.component["purl"] += "&project_path=%3Aopenbank-libs-domain"
        self.rejected("duplicate purl qualifier")

    def test_coordinate_disagreement_is_rejected(self):
        self.component["name"] = "openbank-libs-runtime"
        self.rejected("coordinate mismatch")

    def test_symlink_in_package_is_rejected(self):
        (self.package / "lib/main/link.jar").symlink_to(self.artifact)
        self.rejected("symlink")

    def test_duplicate_json_key_is_rejected(self):
        path = self.root / "duplicate.json"
        path.write_text('{"components":[],"components":[]}')
        with self.assertRaisesRegex(v.EvidenceError, "duplicate JSON key"):
            v.read_json(path)


if __name__ == "__main__":
    unittest.main()
