#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Focused tests for the capacity evidence integrity verifier."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / ".github/scripts/verify-capacity-evidence.py"
SPEC = importlib.util.spec_from_file_location("verify_capacity_evidence", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class CapacityEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 28, 12, 0, tzinfo=timezone.utc)
        self.temp = tempfile.TemporaryDirectory()
        self.evidence_dir = Path(self.temp.name)
        self.manifest_path = self.evidence_dir / "manifest.json"
        self.artifact = self.evidence_dir / "results.json"
        self.artifact.write_text('{"sample":"synthetic"}\n', encoding="utf-8")
        self.configuration = self.evidence_dir / "effective-configuration.json"
        self.configuration.write_text('{"mode":"fixture"}\n', encoding="utf-8")
        self.topology = self.evidence_dir / "topology.json"
        self.topology.write_text('{"nodes":1}\n', encoding="utf-8")
        self.dataset = self.evidence_dir / "dataset-descriptor.json"
        self.dataset.write_text(json.dumps({
            "schema": "openbank.capacity-dataset-descriptor/v1",
            "seed_sha256": "f" * 64,
            "generator_version": "fixture-generator/1",
        }) + "\n", encoding="utf-8")
        profile, profile_bytes = CHECKER.load_profile(ROOT)
        self.profile = profile
        self.profile_bytes = profile_bytes
        self.manifest = self.make_manifest()
        self.write_manifest()

    def tearDown(self):
        self.temp.cleanup()

    def make_manifest(self):
        def entry(path):
            contents = path.read_bytes()
            return {"path": path.name, "size": len(contents), "sha256": hashlib.sha256(contents).hexdigest()}

        return {
            "schema": "openbank.capacity-evidence/v1",
            "profile": {
                "id": self.profile["profile_id"],
                "version": self.profile["schema_version"],
                "sha256": hashlib.sha256(self.profile_bytes).hexdigest(),
            },
            "source": {
                "repository": CHECKER.TRUSTED_REPOSITORY,
                "commit": "a" * 40,
                "ref": CHECKER.TRUSTED_REF,
                "workflow_path": CHECKER.TRUSTED_WORKFLOW_PATH,
            },
            "run": {
                "id": 12345,
                "attempt": 2,
                "started_at": "2026-10-28T11:00:00Z",
                "ended_at": "2026-10-28T11:30:00Z",
                "observed_at": "2026-10-28T11:31:00Z",
            },
            "campaign": {"scope": "bounded-sandbox-observation"},
            "bindings": {
                "application_image_digests": ["sha256:" + "b" * 64],
                "k6_image_digest": "sha256:" + "c" * 64,
                "effective_configuration": {
                    "artifact": self.configuration.name,
                    "sha256": hashlib.sha256(self.configuration.read_bytes()).hexdigest(),
                },
                "topology": {
                    "artifact": self.topology.name,
                    "sha256": hashlib.sha256(self.topology.read_bytes()).hexdigest(),
                },
                "dataset": {
                    "descriptor_artifact": self.dataset.name,
                    "descriptor_sha256": hashlib.sha256(self.dataset.read_bytes()).hexdigest(),
                    "seed_sha256": "f" * 64,
                    "generator_version": "fixture-generator/1",
                },
            },
            "artifacts": [entry(self.artifact), entry(self.configuration), entry(self.topology), entry(self.dataset)],
        }

    def write_manifest(self):
        self.manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")

    def certificate(self):
        signer_uri = f"https://github.com/{CHECKER.TRUSTED_SIGNER_WORKFLOW}@{CHECKER.TRUSTED_REF}"
        return {
            "issuer": CHECKER.OIDC_ISSUER,
            "subjectAlternativeName": {"type": "URI", "value": signer_uri},
            "sourceRepositoryURI": f"https://github.com/{CHECKER.TRUSTED_REPOSITORY}",
            "sourceRepositoryDigest": "a" * 40,
            "sourceRepositoryRef": CHECKER.TRUSTED_REF,
            "buildSignerURI": signer_uri,
            "githubWorkflowRepository": CHECKER.TRUSTED_REPOSITORY,
            "githubWorkflowRef": CHECKER.TRUSTED_REF,
            "runnerEnvironment": "github-hosted",
            "buildTrigger": "workflow_dispatch",
            "runInvocationURI": "https://github.com/"
            f"{CHECKER.TRUSTED_REPOSITORY}/actions/runs/12345/attempts/2",
        }

    def attestation(self, certificate=None, invocation_id=None):
        manifest_hash = hashlib.sha256(self.manifest_path.read_bytes()).hexdigest()
        return [{
            "verificationResult": {
                "statement": {
                    "predicateType": CHECKER.PREDICATE_TYPE,
                    "subject": [{"name": "manifest.json", "digest": {"sha256": manifest_hash}}],
                    "predicate": {"runDetails": {"metadata": {
                        "invocationId": invocation_id or (
                            f"https://github.com/{CHECKER.TRUSTED_REPOSITORY}/actions/runs/12345/attempts/2"
                        )
                    }}},
                },
                "signature": {"certificate": certificate or self.certificate()},
                "verifiedTimestamps": [{"type": "Tlog", "timestamp": "2026-10-28T11:35:00Z"}],
            }
        }]

    def api_run(self, updated_at="2026-10-28T11:31:00Z"):
        return {
            "id": 12345,
            "run_attempt": 2,
            "head_repository": {"full_name": CHECKER.TRUSTED_REPOSITORY},
            "path": CHECKER.TRUSTED_WORKFLOW_PATH,
            "head_branch": "main",
            "event": "workflow_dispatch",
            "status": "completed",
            "conclusion": "success",
            "head_sha": "a" * 40,
            "run_started_at": "2026-10-28T11:00:00Z",
            "updated_at": updated_at,
        }

    def runner(self, *, attestation=None, api=None, attestation_code=0, api_code=0, on_call=None):
        if attestation is None:
            attestation = self.attestation()
        if api is None:
            api = self.api_run()
        responses = [
            subprocess.CompletedProcess([], attestation_code, json.dumps(attestation).encode(), b""),
            subprocess.CompletedProcess([], api_code, json.dumps(api).encode(), b""),
        ]

        def run(command, **kwargs):
            self.commands.append((command, kwargs))
            response = responses.pop(0)
            if on_call is not None:
                on_call(len(self.commands), command)
            return response

        self.commands = []
        return run

    def verify(self, runner=None):
        with patch.object(CHECKER.subprocess, "run", side_effect=runner or self.runner()):
            return CHECKER.verify_evidence(self.evidence_dir, ROOT, now=self.now)

    def test_integrity_pass_is_never_a_capacity_qualification(self):
        result = self.verify()
        self.assertEqual(result["evidence_integrity"], "verified")
        self.assertEqual(result["qualification_disposition"], "not-evaluated-by-this-tool")
        self.assertFalse(result["capacity_qualified"])
        self.assertFalse(result["financial_invariants_verified"])
        self.assertFalse(result["slo_verified"])
        self.assertEqual(result["profile"]["decision"], "proposed")
        self.assertEqual(result["attestation"]["deny_self_hosted_runners_scope"],
                         "signer only; not workload isolation proof")

    def test_cli_uses_fixed_crypto_and_exact_attempt_commands(self):
        runner = self.runner()
        self.verify(runner)
        attestation_command, attestation_kwargs = self.commands[0]
        self.assertEqual(attestation_command[:3], ["gh", "attestation", "verify"])
        self.assertIn("--deny-self-hosted-runners", attestation_command)
        self.assertIn("--source-digest", attestation_command)
        self.assertIn("a" * 40, attestation_command)
        self.assertNotIn("shell", attestation_kwargs)
        self.assertEqual(self.commands[1][0], [
            "gh", "api", "--hostname", "github.com",
            f"repos/{CHECKER.TRUSTED_REPOSITORY}/actions/runs/12345/attempts/2",
        ])

    def test_rejects_attestation_for_different_manifest_digest(self):
        manifest_hash = "0" * 64
        attestation = [{"verificationResult": {
            "statement": {
                "predicateType": CHECKER.PREDICATE_TYPE,
                "subject": [{"name": "manifest.json", "digest": {"sha256": manifest_hash}}],
                "predicate": {"runDetails": {"metadata": {"invocationId":
                    f"https://github.com/{CHECKER.TRUSTED_REPOSITORY}/actions/runs/12345/attempts/2"}}},
            },
            "signature": {"certificate": {
                **self.certificate(),
                "runInvocationURI": "https://github.com/"
                f"{CHECKER.TRUSTED_REPOSITORY}/actions/runs/12345/attempts/1",
            }},
            "verifiedTimestamps": [{"type": "Tlog", "timestamp": "2026-10-28T11:35:00Z"}],
        }}]
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(attestation=attestation))

    def test_rejects_predicate_run_that_differs_from_signed_certificate(self):
        attestation = self.attestation(invocation_id=(
            f"https://github.com/{CHECKER.TRUSTED_REPOSITORY}/actions/runs/99999/attempts/1"
        ))
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(attestation=attestation))

    def test_rejects_signed_run_a_even_when_predicate_and_api_claim_successful_run_b(self):
        certificate = self.certificate()
        certificate["runInvocationURI"] = (
            f"https://github.com/{CHECKER.TRUSTED_REPOSITORY}/actions/runs/99999/attempts/1"
        )
        # The workflow-controlled predicate and GitHub API both name run B (12345/2).
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(attestation=self.attestation(certificate=certificate)))

    def test_rejects_missing_signed_certificate_run_identity(self):
        certificate = self.certificate()
        del certificate["runInvocationURI"]
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(attestation=self.attestation(certificate=certificate)))

    def test_rejects_malformed_verified_timestamp(self):
        attestation = self.attestation()
        attestation[0]["verificationResult"]["verifiedTimestamps"] = [{"type": "Tlog", "timestamp": "bad"}]
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(attestation=attestation))

    def test_rejects_untrusted_signed_certificate_claims(self):
        for field, value in (
            ("issuer", "https://untrusted.example"),
            ("sourceRepositoryURI", "https://github.com/other/repo"),
            ("sourceRepositoryDigest", "0" * 40),
            ("sourceRepositoryRef", "refs/heads/feature"),
            ("buildSignerURI", "https://github.com/other/repo/workflow.yml@refs/heads/main"),
            ("githubWorkflowRepository", "other/repo"),
            ("githubWorkflowRef", "refs/heads/feature"),
            ("runnerEnvironment", "self-hosted"),
            ("buildTrigger", "push"),
        ):
            with self.subTest(field=field):
                certificate = self.certificate()
                certificate[field] = value
                with self.assertRaises(CHECKER.EvidenceError):
                    self.verify(self.runner(attestation=self.attestation(certificate=certificate)))

    def test_rejects_untrusted_signed_certificate_san(self):
        certificate = self.certificate()
        certificate["subjectAlternativeName"] = {"type": "URI", "value": "https://untrusted.example"}
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(attestation=self.attestation(certificate=certificate)))

    def test_rejects_wrong_run_attempt_api_join(self):
        api = {
            "id": 12345, "run_attempt": 3,
            "head_repository": {"full_name": CHECKER.TRUSTED_REPOSITORY},
            "path": CHECKER.TRUSTED_WORKFLOW_PATH, "head_branch": "main",
            "event": "workflow_dispatch", "status": "completed", "conclusion": "success",
            "head_sha": "a" * 40, "run_started_at": "2026-10-28T11:00:00Z",
            "updated_at": "2026-10-28T11:31:00Z",
        }
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(api=api))

    def test_rejects_failed_api_attempt(self):
        api = {
            "id": 12345, "run_attempt": 2,
            "head_repository": {"full_name": CHECKER.TRUSTED_REPOSITORY},
            "path": CHECKER.TRUSTED_WORKFLOW_PATH, "head_branch": "main",
            "event": "workflow_dispatch", "status": "completed", "conclusion": "failure",
            "head_sha": "a" * 40, "run_started_at": "2026-10-28T11:00:00Z",
            "updated_at": "2026-10-28T11:31:00Z",
        }
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(api=api))

    def test_rejects_nonzero_attestation_cli(self):
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(attestation_code=1))

    def test_rejects_duplicate_keys_and_nonfinite_json(self):
        for raw in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":Infinity}', b'{"x":1e999}'):
            with self.subTest(raw=raw), self.assertRaises(CHECKER.EvidenceError):
                CHECKER.load_json(raw)

    def test_rejects_unknown_manifest_field(self):
        self.manifest["verified"] = True
        self.write_manifest()
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify()

    def test_rejects_profile_digest_mismatch(self):
        self.manifest["profile"]["sha256"] = "0" * 64
        self.write_manifest()
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify()

    def test_rejects_unsafe_artifact_paths(self):
        for path in ("../escape", "/absolute", "a\\b", "C:/drive", "a/../b"):
            with self.subTest(path=path):
                self.manifest["artifacts"][0]["path"] = path
                self.write_manifest()
                with self.assertRaises(CHECKER.EvidenceError):
                    self.verify()
        self.manifest["artifacts"][0]["path"] = "results.json"
        self.write_manifest()

    def test_rejects_artifact_tampering(self):
        self.artifact.write_text("changed\n", encoding="utf-8")
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify()

    def test_rejects_unlisted_file_with_valid_manifest_and_artifacts(self):
        (self.evidence_dir / "unlisted.bin").write_bytes(b"extra")
        with self.assertRaisesRegex(
            CHECKER.EvidenceError, "^bundle files do not exactly match the signed artifact inventory$"
        ):
            self.verify()

    def test_rechecks_artifact_bytes_after_external_verification(self):
        def mutate_after_api(call_number, _command):
            if call_number == 2:
                self.artifact.write_text("changed during gh", encoding="utf-8")

        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(on_call=mutate_after_api))

    def test_rechecks_manifest_bytes_after_external_verification(self):
        def mutate_after_api(call_number, _command):
            if call_number == 2:
                self.manifest_path.write_text("{}", encoding="utf-8")

        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(on_call=mutate_after_api))

    def test_rejects_missing_binding_artifact_link(self):
        self.manifest["artifacts"] = [
            item for item in self.manifest["artifacts"] if item["path"] != self.configuration.name
        ]
        self.write_manifest()
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify()

    def test_rejects_binding_digest_mismatch(self):
        self.manifest["bindings"]["topology"]["sha256"] = "0" * 64
        self.write_manifest()
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify()

    def test_rejects_dataset_descriptor_seed_or_generator_mismatch(self):
        descriptor = {
            "schema": "openbank.capacity-dataset-descriptor/v1",
            "seed_sha256": "0" * 64,
            "generator_version": "other-generator/1",
        }
        self.dataset.write_text(json.dumps(descriptor) + "\n", encoding="utf-8")
        self.manifest["artifacts"][-1] = {
            "path": self.dataset.name,
            "size": self.dataset.stat().st_size,
            "sha256": hashlib.sha256(self.dataset.read_bytes()).hexdigest(),
        }
        self.manifest["bindings"]["dataset"]["descriptor_sha256"] = self.manifest["artifacts"][-1]["sha256"]
        self.write_manifest()
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify()

    def test_rejects_symlink_artifact(self):
        self.artifact.unlink()
        target = self.evidence_dir / "target.json"
        target.write_text("{}", encoding="utf-8")
        self.artifact.symlink_to(target)
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify()

    def test_rejects_symlink_manifest_before_open(self):
        original = self.evidence_dir / "manifest-original.json"
        self.manifest_path.replace(original)
        self.manifest_path.symlink_to(original)
        with patch.object(CHECKER, "checked_open", side_effect=AssertionError("must not open symlink")):
            with self.assertRaises(CHECKER.EvidenceError):
                CHECKER.verify_evidence(self.evidence_dir, ROOT, now=self.now)

    def test_rejects_fifo_manifest_before_open(self):
        self.manifest_path.unlink()
        os.mkfifo(self.manifest_path)
        with patch.object(CHECKER, "checked_open", side_effect=AssertionError("must not open FIFO")):
            with self.assertRaises(CHECKER.EvidenceError):
                CHECKER.verify_evidence(self.evidence_dir, ROOT, now=self.now)

    def test_rejects_oversized_manifest_before_open(self):
        with self.manifest_path.open("wb") as stream:
            stream.truncate(CHECKER.MAX_MANIFEST_BYTES + 1)
        with patch.object(CHECKER, "checked_open", side_effect=AssertionError("must not open oversized file")):
            with self.assertRaises(CHECKER.EvidenceError):
                CHECKER.verify_evidence(self.evidence_dir, ROOT, now=self.now)

    def test_rejects_file_replaced_between_stat_and_open(self):
        replacement = self.evidence_dir / "replacement.json"
        replacement.write_text(self.manifest_path.read_text(encoding="utf-8") + " ", encoding="utf-8")
        original_open = CHECKER.os.open

        def replace_before_open(path, flags, *args, **kwargs):
            if Path(path) == self.manifest_path:
                replacement.replace(self.manifest_path)
            return original_open(path, flags, *args, **kwargs)

        with patch.object(CHECKER.os, "open", side_effect=replace_before_open):
            with self.assertRaises(CHECKER.EvidenceError):
                self.verify()

    def test_rejects_stale_observation_with_consistent_run_and_attestation_times(self):
        for field in ("started_at", "ended_at", "observed_at"):
            self.manifest["run"][field] = self.manifest["run"][field].replace("2026-10-28", "2026-09-01")
        self.write_manifest()
        attestation = self.attestation()
        attestation[0]["verificationResult"]["verifiedTimestamps"][0]["timestamp"] = "2026-09-01T11:35:00Z"
        api = self.api_run(updated_at="2026-09-01T11:31:00Z")
        api["run_started_at"] = self.manifest["run"]["started_at"]
        with self.assertRaisesRegex(CHECKER.EvidenceError, "^capacity evidence is stale$"):
            self.verify(self.runner(attestation=attestation, api=api))

    def test_rejects_api_updated_at_before_start_or_too_far_in_future(self):
        for updated_at in ("2026-10-28T10:59:00Z", "2026-10-28T12:10:00Z"):
            with self.subTest(updated_at=updated_at):
                api = {
                    "id": 12345, "run_attempt": 2,
                    "head_repository": {"full_name": CHECKER.TRUSTED_REPOSITORY},
                    "path": CHECKER.TRUSTED_WORKFLOW_PATH, "head_branch": "main",
                    "event": "workflow_dispatch", "status": "completed", "conclusion": "success",
                    "head_sha": "a" * 40, "run_started_at": "2026-10-28T11:00:00Z",
                    "updated_at": updated_at,
                }
                with self.assertRaises(CHECKER.EvidenceError):
                    self.verify(self.runner(api=api))

    def test_accepts_observation_inside_whole_second_api_interval(self):
        self.manifest["run"]["observed_at"] = "2026-10-28T11:31:00.999Z"
        self.write_manifest()
        self.assertEqual(self.verify()["evidence_integrity"], "verified")

    def test_rejects_observation_after_whole_second_api_interval(self):
        self.manifest["run"]["observed_at"] = "2026-10-28T11:32:00Z"
        self.write_manifest()
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(api=self.api_run("2026-10-28T11:31:00Z")))

    def test_fractional_api_timestamp_is_an_exact_upper_bound(self):
        self.manifest["run"]["observed_at"] = "2026-10-28T11:31:00.500Z"
        self.write_manifest()
        self.assertEqual(self.verify(self.runner(api=self.api_run("2026-10-28T11:31:00.500Z")))
                         ["evidence_integrity"], "verified")

        self.manifest["run"]["observed_at"] = "2026-10-28T11:31:00.501Z"
        self.write_manifest()
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify(self.runner(api=self.api_run("2026-10-28T11:31:00.500Z")))
        self.manifest["run"]["observed_at"] = (self.now + timedelta(hours=1)).isoformat()
        self.write_manifest()
        with self.assertRaises(CHECKER.EvidenceError):
            self.verify()


if __name__ == "__main__":
    unittest.main()
