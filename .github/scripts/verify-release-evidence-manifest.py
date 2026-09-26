# SPDX-License-Identifier: Apache-2.0
"""Check that a downloaded release manifest binds the required evidence files."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path


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
