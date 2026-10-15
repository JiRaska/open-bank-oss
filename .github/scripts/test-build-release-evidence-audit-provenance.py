# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"""Offline regression tests for the D5 audit-provenance document (issue #12923 gaps #4/#5).

Exercises .github/scripts/build-release-evidence.sh end to end with a synthetic SBOM:
(a) no attestation input -> honest not_attested shape that passes the manifest verifier,
(b) real attestation inputs -> digest/keyId/signedAt and the run window round-trip into the
    document and the manifest self-declaration,
(c) malformed attestation input -> the build fails loudly instead of silently dropping,
(d) a non-VERIFIED anchor verdict is recorded verbatim but never reads as attested.

The build runs in a scratch directory INSIDE the repository so the git-derived fields
(git_commit, released_on, contributors) resolve against this checkout; nothing is committed.
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/build-release-evidence.sh"
VERIFIER = ROOT / ".github/scripts/verify-release-evidence-manifest.py"
TAG = "fixture-v0.0.1"

VERDICT = {
    "verdict": "VERIFIED",
    "verifier_exit": 0,
    "generations_verified": 2,
    "export_url": "http://audit-service.audit.svc:8113/api/v1/audit/anchors?limit=200",
    "fetched_at": "2026-10-15T09:00:00Z",
    "anchor_checkpoint": {
        "digest": "a" * 64,
        "keyId": "arn:aws:kms:eu-north-1:000000000000:key/anchor-generation",
        "signedAt": "2026-10-15T08:00:00Z",
    },
}

AI_ATTRIBUTION = {
    "observed_fields": ["model_id", "prompt_hash", "policy_decision", "run_id"],
    "latest_attested_run_window": {
        "from": "2026-10-08T00:00:00Z",
        "to": "2026-10-15T00:00:00Z",
        "sampled_events": 42,
    },
    "attested_at": "2026-10-15T09:05:00Z",
}


class BuildAuditProvenanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="tmp-evidence-test-", dir=ROOT)
        self.work = Path(self._tmp.name)
        self.sbom = self.work / f"{TAG}.cdx.json"
        self.sbom.write_text('{"bomFormat": "CycloneDX", "specVersion": "1.6"}', encoding="utf-8")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def build(self, env_extra: dict | None = None) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env.pop("EVIDENCE_ANCHOR_ATTESTATION_JSON", None)
        env.pop("EVIDENCE_AI_ATTRIBUTION_JSON", None)
        if env_extra:
            env.update(env_extra)
        return subprocess.run(
            ["bash", str(SCRIPT), "fixture", "0.0.1", TAG, str(self.work), str(self.sbom)],
            cwd=self.work,
            env=env,
            capture_output=True,
            text=True,
            check=False,
            timeout=120,
        )

    def document(self) -> dict:
        return json.loads((self.work / f"{TAG}.audit-provenance.json").read_text(encoding="utf-8"))

    def manifest(self) -> dict:
        return json.loads((self.work / f"{TAG}.evidence.json").read_text(encoding="utf-8"))

    def verify_manifest(self) -> subprocess.CompletedProcess[str]:
        for name in (
            f"{TAG}.cdx.json",
            f"{TAG}.slsa.json",
            f"{TAG}.vex.json",
            f"{TAG}.audit-provenance.json",
            f"{TAG}.evidence.json",
        ):
            (self.work / f"{name}.sig").write_bytes(b"signature is checked by cosign")
        return subprocess.run(
            [os.environ.get("PYTHON", "python3"), str(VERIFIER), TAG],
            cwd=self.work,
            capture_output=True,
            text=True,
            check=False,
            timeout=30,
        )

    def test_no_inputs_yields_honest_not_attested(self) -> None:
        result = self.build()
        self.assertEqual(result.returncode, 0, result.stderr)
        document = self.document()
        self.assertEqual(document["schema_version"], 1)
        self.assertEqual(
            document["audit_anchor_checkpoint"],
            {"status": "not_attested", "reason": "no attestation input at cut time"},
        )
        self.assertEqual(document["ai_attribution"]["status"], "not_attested")
        self.assertIsNone(document["ai_attribution"]["latest_attested_run_window"])
        self.assertEqual(
            document["ai_attribution"]["required_fields"],
            ["model_id", "prompt_hash", "policy_decision"],
        )
        manifest = self.manifest()
        self.assertEqual(
            manifest["ai_attribution"]["runtime_audit_attribution"]["status"], "not_attested"
        )
        reference = manifest["audit_provenance"]
        self.assertEqual(reference["file"], f"{TAG}.audit-provenance.json")
        actual = hashlib.sha256(
            (self.work / f"{TAG}.audit-provenance.json").read_bytes()
        ).hexdigest()
        self.assertEqual(reference["sha256"], actual)
        # The honest negative must pass the weekly manifest verifier.
        verified = self.verify_manifest()
        self.assertEqual(verified.returncode, 0, verified.stderr)

    def test_attested_inputs_round_trip_into_document_and_manifest(self) -> None:
        verdict_path = self.work / "verdict.json"
        verdict_path.write_text(json.dumps(VERDICT), encoding="utf-8")
        ai_path = self.work / "ai-attribution.json"
        ai_path.write_text(json.dumps(AI_ATTRIBUTION), encoding="utf-8")
        result = self.build(
            {
                "EVIDENCE_ANCHOR_ATTESTATION_JSON": str(verdict_path),
                "EVIDENCE_AI_ATTRIBUTION_JSON": str(ai_path),
            }
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        checkpoint = self.document()["audit_anchor_checkpoint"]
        self.assertEqual(checkpoint["status"], "attested")
        self.assertEqual(checkpoint["digest"], VERDICT["anchor_checkpoint"]["digest"])
        self.assertEqual(checkpoint["keyId"], VERDICT["anchor_checkpoint"]["keyId"])
        self.assertEqual(checkpoint["signedAt"], VERDICT["anchor_checkpoint"]["signedAt"])
        self.assertEqual(checkpoint["verification_status"], "VERIFIED")
        attribution = self.document()["ai_attribution"]
        self.assertEqual(attribution["status"], "attested")
        self.assertEqual(
            attribution["latest_attested_run_window"],
            AI_ATTRIBUTION["latest_attested_run_window"],
        )
        runtime = self.manifest()["ai_attribution"]["runtime_audit_attribution"]
        self.assertEqual(runtime["status"], "attested")
        self.assertTrue(runtime["promotion_eligible"])
        verified = self.verify_manifest()
        self.assertEqual(verified.returncode, 0, verified.stderr)

    def test_malformed_anchor_json_fails_loud(self) -> None:
        broken = self.work / "broken.json"
        broken.write_text('{"verdict": "VERIFIED"', encoding="utf-8")
        result = self.build({"EVIDENCE_ANCHOR_ATTESTATION_JSON": str(broken)})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("malformed", result.stderr)

    def test_malformed_ai_json_fails_loud(self) -> None:
        broken = self.work / "broken-ai.json"
        broken.write_text("not json at all", encoding="utf-8")
        result = self.build({"EVIDENCE_AI_ATTRIBUTION_JSON": str(broken)})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("malformed", result.stderr)

    def test_anchor_input_without_checkpoint_fails_loud(self) -> None:
        verdict = dict(VERDICT)
        verdict.pop("anchor_checkpoint")
        path = self.work / "verdict-no-checkpoint.json"
        path.write_text(json.dumps(verdict), encoding="utf-8")
        result = self.build({"EVIDENCE_ANCHOR_ATTESTATION_JSON": str(path)})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("anchor_checkpoint", result.stderr)

    def test_ai_input_missing_required_field_fails_loud(self) -> None:
        ai = json.loads(json.dumps(AI_ATTRIBUTION))
        ai["observed_fields"] = ["model_id", "prompt_hash"]  # policy_decision missing
        path = self.work / "ai-incomplete.json"
        path.write_text(json.dumps(ai), encoding="utf-8")
        result = self.build({"EVIDENCE_AI_ATTRIBUTION_JSON": str(path)})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("observed_fields", result.stderr)

    def test_non_verified_verdict_is_recorded_but_not_attested(self) -> None:
        verdict = json.loads(json.dumps(VERDICT))
        verdict["verdict"] = "TAMPERED"
        verdict["verifier_exit"] = 2
        path = self.work / "verdict-tampered.json"
        path.write_text(json.dumps(verdict), encoding="utf-8")
        result = self.build({"EVIDENCE_ANCHOR_ATTESTATION_JSON": str(path)})
        self.assertEqual(result.returncode, 0, result.stderr)
        checkpoint = self.document()["audit_anchor_checkpoint"]
        self.assertEqual(checkpoint["status"], "not_attested")
        self.assertEqual(checkpoint["verification_status"], "TAMPERED")
        self.assertEqual(checkpoint["digest"], VERDICT["anchor_checkpoint"]["digest"])
        self.assertIn("TAMPERED", checkpoint["reason"])

    def test_missing_input_file_fails_loud(self) -> None:
        result = self.build(
            {"EVIDENCE_ANCHOR_ATTESTATION_JSON": str(self.work / "does-not-exist.json")}
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not exist", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
