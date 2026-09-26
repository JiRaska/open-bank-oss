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


def fixture(directory: Path) -> dict:
    manifest: dict = {"schema": "openbank.evidence/v1", "tag": TAG}
    for key, suffix in REFERENCES.items():
        name = f"{TAG}{suffix}"
        data = f"{key} evidence".encode()
        (directory / name).write_bytes(data)
        (directory / f"{name}.sig").write_bytes(b"signature is checked by cosign")
        manifest[key] = {"file": name, "signature": f"{name}.sig", "sha256": hashlib.sha256(data).hexdigest()}
    write_manifest(directory, manifest)
    return manifest


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


if __name__ == "__main__":
    main()
