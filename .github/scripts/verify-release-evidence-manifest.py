# SPDX-License-Identifier: Apache-2.0
"""Check that a downloaded release manifest binds the required evidence files."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path


def _claimed_statuses(manifest: dict) -> tuple[str | None, str | None]:
    """Attestation statuses claimed by the manifest and its audit-provenance document.

    Returns (anchor_status, ai_status); None when the declaring block is absent (an
    old-shape bundle predating the D5 audit-provenance document declares nothing).
    """
    ai = manifest.get("ai_attribution")
    runtime = ai.get("runtime_audit_attribution") if isinstance(ai, dict) else None
    manifest_ai = runtime.get("status") if isinstance(runtime, dict) else None
    return None, manifest_ai if manifest_ai in {"attested", "not_attested"} else None


def _document_statuses(document: dict) -> tuple[str | None, str | None]:
    anchor = document.get("audit_anchor_checkpoint")
    anchor_status = anchor.get("status") if isinstance(anchor, dict) else None
    ai = document.get("ai_attribution")
    ai_status = ai.get("status") if isinstance(ai, dict) else None
    return (
        anchor_status if anchor_status in {"attested", "not_attested"} else None,
        ai_status if ai_status in {"attested", "not_attested"} else None,
    )


def verify(tag: str, directory: Path) -> list[str]:
    errors: list[str] = []
    manifest_path = directory / f"{tag}.evidence.json"
    signature_path = directory / f"{tag}.evidence.json.sig"
    if not manifest_path.is_file() or not manifest_path.stat().st_size:
        return [f"required evidence manifest {manifest_path.name} is missing or empty"]
    if not signature_path.is_file() or not signature_path.stat().st_size:
        errors.append(f"required manifest signature {signature_path.name} is missing or empty")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        return errors + [f"cannot parse evidence manifest {manifest_path.name}: {exc}"]
    if not isinstance(manifest, dict) or manifest.get("schema") != "openbank.evidence/v1":
        errors.append("evidence manifest has an unexpected schema")
    if not isinstance(manifest, dict) or manifest.get("tag") != tag:
        errors.append("evidence manifest tag does not match the requested release")
    if not isinstance(manifest, dict):
        return errors
    for key, suffix in (("sbom", ".cdx.json"), ("slsa_provenance", ".slsa.json"), ("vex", ".vex.json")):
        expected = f"{tag}{suffix}"
        ref = manifest.get(key)
        if not isinstance(ref, dict):
            errors.append(f"manifest has no {key} reference")
            continue
        if ref.get("file") != expected or ref.get("signature") != f"{expected}.sig":
            errors.append(f"manifest {key} reference must name {expected} and its signature")
            continue
        wanted = ref.get("sha256")
        if not isinstance(wanted, str) or re.fullmatch(r"[0-9a-f]{64}", wanted) is None:
            errors.append(f"manifest {key} has no valid SHA-256 digest")
            continue
        document = directory / expected
        signature = directory / f"{expected}.sig"
        if not document.is_file() or not document.stat().st_size:
            errors.append(f"required evidence document {expected} is missing or empty")
            continue
        if not signature.is_file() or not signature.stat().st_size:
            errors.append(f"required evidence signature {signature.name} is missing or empty")
        actual = hashlib.sha256(document.read_bytes()).hexdigest()
        if actual != wanted:
            errors.append(f"manifest {key} digest does not match {expected}")

    # ── D5 audit-provenance document (issue #12923 gaps #4/#5) ────────────────────
    # The document is OPTIONAL for old-shape bundles (cut before it existed): absence is an
    # honest negative only while the manifest does not claim attestation. A manifest that
    # CLAIMS attested without binding the document, or binds a document whose attested
    # sections lack the fields that make them mean anything, is LYING — fail it.
    expected = f"{tag}.audit-provenance.json"
    ref = manifest.get("audit_provenance")
    _anchor_claimed, ai_claimed = _claimed_statuses(manifest)
    if not isinstance(ref, dict):
        if ai_claimed == "attested":
            errors.append(
                "manifest claims attested runtime AI attribution but has no audit_provenance "
                f"document reference ({expected})"
            )
    else:
        if ref.get("file") != expected or ref.get("signature") != f"{expected}.sig":
            errors.append(
                f"manifest audit_provenance reference must name {expected} and its signature"
            )
        else:
            wanted = ref.get("sha256")
            if not isinstance(wanted, str) or re.fullmatch(r"[0-9a-f]{64}", wanted) is None:
                errors.append("manifest audit_provenance has no valid SHA-256 digest")
            else:
                document_path = directory / expected
                signature_path = directory / f"{expected}.sig"
                if not document_path.is_file() or not document_path.stat().st_size:
                    errors.append(f"required evidence document {expected} is missing or empty")
                else:
                    if not signature_path.is_file() or not signature_path.stat().st_size:
                        errors.append(
                            f"required evidence signature {signature_path.name} is missing or empty"
                        )
                    actual = hashlib.sha256(document_path.read_bytes()).hexdigest()
                    if actual != wanted:
                        errors.append(f"manifest audit_provenance digest does not match {expected}")
                    try:
                        document = json.loads(document_path.read_text(encoding="utf-8"))
                    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
                        errors.append(f"cannot parse evidence document {expected}: {exc}")
                    else:
                        if not isinstance(document, dict):
                            errors.append(f"evidence document {expected} is not a JSON object")
                        else:
                            doc_anchor, doc_ai = _document_statuses(document)
                            ai_block = document.get("ai_attribution")
                            if doc_ai == "attested" and not (
                                isinstance(ai_block, dict)
                                and isinstance(ai_block.get("latest_attested_run_window"), dict)
                            ):
                                errors.append(
                                    f"{expected} claims attested AI attribution but has no "
                                    "latest_attested_run_window"
                                )
                            anchor_block = document.get("audit_anchor_checkpoint")
                            if doc_anchor == "attested" and not (
                                isinstance(anchor_block, dict)
                                and all(
                                    isinstance(anchor_block.get(field), str) and anchor_block[field]
                                    for field in ("digest", "keyId", "signedAt")
                                )
                            ):
                                errors.append(
                                    f"{expected} claims an attested anchor checkpoint but is "
                                    "missing digest/keyId/signedAt"
                                )
                            if ai_claimed is not None and ai_claimed != doc_ai:
                                errors.append(
                                    f"manifest ai_attribution status '{ai_claimed}' contradicts "
                                    f"{expected} status '{doc_ai}'"
                                )
    return errors


def main() -> int:
    if len(sys.argv) != 2 or not sys.argv[1] or "/" in sys.argv[1] or sys.argv[1] in {".", ".."}:
        print("usage: verify-release-evidence-manifest.py <release-tag>", file=sys.stderr)
        return 2
    errors = verify(sys.argv[1], Path.cwd())
    for error in errors:
        print(f"::error::{error}", file=sys.stderr)
    if errors:
        return 1
    print(f"OK manifest digests and required files: {sys.argv[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
