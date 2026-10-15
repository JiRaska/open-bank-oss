# SPDX-License-Identifier: Apache-2.0
"""Check that a downloaded release manifest binds the required evidence files."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path


def verify(
    tag: str,
    directory: Path,
    expected_component: str | None = None,
    expected_version: str | None = None,
    expected_commit: str | None = None,
) -> list[str]:
    errors: list[str] = []
    expected_identity = (expected_component, expected_version, expected_commit)
    if any(value is not None for value in expected_identity) and not all(
        value is not None for value in expected_identity
    ):
        errors.append("expected component, version and commit must be supplied together")
    if all(value is not None for value in expected_identity):
        assert expected_component is not None
        assert expected_version is not None
        assert expected_commit is not None
        if not expected_component or not expected_version:
            errors.append("expected component and version must be nonempty")
        if re.fullmatch(r"[0-9a-f]{40}", expected_commit) is None:
            errors.append("expected commit must be a full 40-character lowercase SHA-1")
        if f"{expected_component}-v{expected_version}" != tag:
            errors.append("expected component and version do not match the release tag")
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
    if all(value is not None for value in expected_identity):
        if manifest.get("component") != expected_component:
            errors.append("manifest component does not match the expected component")
        if manifest.get("version") != expected_version:
            errors.append("manifest version does not match the expected version")
        if manifest.get("git_commit") != expected_commit:
            errors.append("manifest git_commit does not match the expected release commit")
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
    import argparse

    parser = argparse.ArgumentParser(
        description="Verify release evidence digests and, when supplied, tag identity."
    )
    parser.add_argument("tag", help="release tag")
    parser.add_argument("--expected-component")
    parser.add_argument("--expected-version")
    parser.add_argument("--expected-commit")
    args = parser.parse_args()
    if not args.tag or "/" in args.tag or args.tag in {".", ".."}:
        parser.error("release tag must be a safe single path component")
    expected = (args.expected_component, args.expected_version, args.expected_commit)
    if any(value is not None for value in expected) and not all(
        value is not None for value in expected
    ):
        parser.error("--expected-component, --expected-version and --expected-commit are required together")
    if not all(value is not None for value in expected):
        print(
            "::notice::legacy digest-only verification; manifest release identity was not checked",
            file=sys.stderr,
        )
    errors = verify(
        args.tag,
        Path.cwd(),
        args.expected_component,
        args.expected_version,
        args.expected_commit,
    )
    for error in errors:
        print(f"::error::{error}", file=sys.stderr)
    if errors:
        return 1
    if all(value is not None for value in expected):
        print(f"OK manifest identity and digests: {args.tag}")
    else:
        print(f"OK manifest digests only (identity unchecked): {args.tag}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
