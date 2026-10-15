# SPDX-License-Identifier: Apache-2.0
"""Negative controls for the downloaded release-evidence manifest verifier."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-release-evidence-manifest.py")
TAG = "example-v1.2.3"
REFERENCES = {"sbom": ".cdx.json", "slsa_provenance": ".slsa.json", "vex": ".vex.json"}


def not_attested_document() -> dict:
    """The honest-negative shape build-release-evidence.sh emits when no input is provided."""
    return {
        "schema_version": 1,
        "schema": "openbank.audit-provenance/v1",
        "tag": TAG,
        "audit_anchor_checkpoint": {"status": "not_attested", "reason": "no attestation input at cut time"},
        "ai_attribution": {
            "status": "not_attested",
            "required_fields": ["model_id", "prompt_hash", "policy_decision"],
            "latest_attested_run_window": None,
            "reason": "no runtime AI-attestation input at cut time",
        },
    }


def attested_document() -> dict:
    document = not_attested_document()
    document["audit_anchor_checkpoint"] = {
        "status": "attested",
        "digest": "a" * 64,
        "keyId": "arn:aws:kms:eu-north-1:000000000000:key/anchor",
        "signedAt": "2026-10-15T08:00:00Z",
        "verification_status": "VERIFIED",
    }
    document["ai_attribution"] = {
        "status": "attested",
        "required_fields": ["model_id", "prompt_hash", "policy_decision"],
        "latest_attested_run_window": {"from": "2026-10-08T00:00:00Z", "to": "2026-10-15T00:00:00Z"},
        "observed_fields": ["model_id", "prompt_hash", "policy_decision"],
    }
    return document


def fixture(directory: Path) -> dict:
    manifest: dict = {"schema": "openbank.evidence/v1", "tag": TAG}
    for key, suffix in REFERENCES.items():
        name = f"{TAG}{suffix}"
        data = f"{key} evidence".encode()
        (directory / name).write_bytes(data)
        (directory / f"{name}.sig").write_bytes(b"signature is checked by cosign")
        manifest[key] = {"file": name, "signature": f"{name}.sig", "sha256": hashlib.sha256(data).hexdigest()}
    write_audit_provenance(directory, not_attested_document())
    manifest["ai_attribution"] = {"runtime_audit_attribution": {"status": "not_attested"}}
    manifest["audit_provenance"] = audit_provenance_reference(directory)
    write_manifest(directory, manifest)
    return manifest


def write_audit_provenance(directory: Path, document: dict) -> None:
    name = f"{TAG}.audit-provenance.json"
    (directory / name).write_text(json.dumps(document), encoding="utf-8")
    (directory / f"{name}.sig").write_bytes(b"signature is checked by cosign")


def audit_provenance_reference(directory: Path) -> dict:
    name = f"{TAG}.audit-provenance.json"
    digest = hashlib.sha256((directory / name).read_bytes()).hexdigest()
    return {"file": name, "signature": f"{name}.sig", "sha256": digest}


def write_manifest(directory: Path, manifest: dict) -> None:
    (directory / f"{TAG}.evidence.json").write_text(json.dumps(manifest), encoding="utf-8")
    (directory / f"{TAG}.evidence.json.sig").write_bytes(b"signature is checked by cosign")


def run(directory: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run([sys.executable, str(SCRIPT), TAG], cwd=directory, text=True, capture_output=True, check=False)


def check(name: str, mutate, should_pass: bool) -> None:
    with tempfile.TemporaryDirectory() as temporary:
        directory = Path(temporary)
        manifest = fixture(directory)
        mutate(directory, manifest)
        result = run(directory)
        assert (result.returncode == 0) == should_pass, (name, result.stdout, result.stderr)
        print(f"ok {name}")


def main() -> None:
    check("complete signed bundle", lambda _d, _m: None, True)
    check("missing manifest", lambda d, _m: (d / f"{TAG}.evidence.json").unlink(), False)
    check("missing manifest signature", lambda d, _m: (d / f"{TAG}.evidence.json.sig").unlink(), False)
    check("missing digest", lambda d, m: (m["sbom"].pop("sha256"), write_manifest(d, m)), False)
    check("missing reference", lambda d, m: (m.pop("vex"), write_manifest(d, m)), False)
    check("wrong tag", lambda d, m: (m.update(tag="other-v1"), write_manifest(d, m)), False)
    check("wrong filename", lambda d, m: (m["sbom"].update(file="other.cdx.json"), write_manifest(d, m)), False)
    check("missing document", lambda d, _m: (d / f"{TAG}.slsa.json").unlink(), False)
    check("missing document signature", lambda d, _m: (d / f"{TAG}.vex.json.sig").unlink(), False)
    check("changed document", lambda d, _m: (d / f"{TAG}.cdx.json").write_bytes(b"changed"), False)

    # ── D5 audit-provenance document (issue #12923 gaps #4/#5) ────────────────────
    def claim_attested(directory: Path, manifest: dict) -> None:
        manifest["ai_attribution"] = {"runtime_audit_attribution": {"status": "attested"}}
        write_manifest(directory, manifest)

    check("manifest claiming attested without the document fails", claim_attested, False)

    def attested_round_trip(directory: Path, manifest: dict) -> None:
        document = attested_document()
        write_audit_provenance(directory, document)
        manifest["ai_attribution"] = {"runtime_audit_attribution": {"status": "attested"}}
        manifest["audit_provenance"] = audit_provenance_reference(directory)
        write_manifest(directory, manifest)

    check("attested document round-trips digest and window", attested_round_trip, True)

    def document_attested_without_window(directory: Path, manifest: dict) -> None:
        document = attested_document()
        document["ai_attribution"]["latest_attested_run_window"] = None
        write_audit_provenance(directory, document)
        manifest["ai_attribution"] = {"runtime_audit_attribution": {"status": "attested"}}
        manifest["audit_provenance"] = audit_provenance_reference(directory)
        write_manifest(directory, manifest)

    check("document claiming attested without run window fails", document_attested_without_window, False)

    def document_attested_without_checkpoint(directory: Path, manifest: dict) -> None:
        document = attested_document()
        document["audit_anchor_checkpoint"].pop("digest")
        write_audit_provenance(directory, document)
        manifest["audit_provenance"] = audit_provenance_reference(directory)
        write_manifest(directory, manifest)

    check("document claiming attested anchor without digest fails", document_attested_without_checkpoint, False)

    def manifest_contradicts_document(directory: Path, manifest: dict) -> None:
        write_audit_provenance(directory, attested_document())
        manifest["audit_provenance"] = audit_provenance_reference(directory)
        write_manifest(directory, manifest)

    check("manifest not_attested contradicting an attested document fails", manifest_contradicts_document, False)

    def missing_audit_provenance_document(directory: Path, manifest: dict) -> None:
        (directory / f"{TAG}.audit-provenance.json").unlink()

    check("missing audit-provenance document fails", missing_audit_provenance_document, False)

    def changed_audit_provenance_document(directory: Path, manifest: dict) -> None:
        write_audit_provenance(directory, attested_document())

    check("changed audit-provenance document fails digest check", changed_audit_provenance_document, False)


if __name__ == "__main__":
    main()
