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
COMPONENT = "example"
VERSION = "1.2.3"
COMMIT = "a" * 40
REFERENCES = {"sbom": ".cdx.json", "slsa_provenance": ".slsa.json", "vex": ".vex.json"}


def fixture(directory: Path, *, identity: bool = False) -> dict:
    manifest: dict = {"schema": "openbank.evidence/v1", "tag": TAG}
    if identity:
        manifest.update(component=COMPONENT, version=VERSION, git_commit=COMMIT)
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


def run(directory: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run([sys.executable, str(SCRIPT), TAG, *args], cwd=directory, text=True, capture_output=True, check=False)


def check(name: str, mutate, should_pass: bool) -> None:
    with tempfile.TemporaryDirectory() as temporary:
        directory = Path(temporary)
        manifest = fixture(directory)
        mutate(directory, manifest)
        result = run(directory)
        assert (result.returncode == 0) == should_pass, (name, result.stdout, result.stderr)
        print(f"ok {name}")


def main() -> None:
    check("legacy digest-only bundle", lambda _d, _m: None, True)
    check("missing manifest", lambda d, _m: (d / f"{TAG}.evidence.json").unlink(), False)
    check("missing manifest signature", lambda d, _m: (d / f"{TAG}.evidence.json.sig").unlink(), False)
    check("missing digest", lambda d, m: (m["sbom"].pop("sha256"), write_manifest(d, m)), False)
    check("missing reference", lambda d, m: (m.pop("vex"), write_manifest(d, m)), False)
    check("wrong tag", lambda d, m: (m.update(tag="other-v1"), write_manifest(d, m)), False)
    check("wrong filename", lambda d, m: (m["sbom"].update(file="other.cdx.json"), write_manifest(d, m)), False)
    check("missing document", lambda d, _m: (d / f"{TAG}.slsa.json").unlink(), False)
    check("missing document signature", lambda d, _m: (d / f"{TAG}.vex.json.sig").unlink(), False)
    check("changed document", lambda d, _m: (d / f"{TAG}.cdx.json").write_bytes(b"changed"), False)

    identity_args = ("--expected-component", COMPONENT, "--expected-version", VERSION,
                     "--expected-commit", COMMIT)

    def check_identity(name: str, mutate, arguments=identity_args, should_pass: bool = False) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest = fixture(directory, identity=True)
            mutate(directory, manifest)
            result = run(directory, *arguments)
            assert (result.returncode == 0) == should_pass, (name, result.stdout, result.stderr)
            print(f"ok {name}")

    check_identity("complete release identity", lambda _d, _m: None, should_pass=True)
    for field in ("component", "version", "git_commit"):
        check_identity(
            f"missing identity {field}",
            lambda d, manifest, field=field: (manifest.pop(field), write_manifest(d, manifest)),
        )
    check_identity(
        "wrong release commit",
        lambda d, manifest: (manifest.update(git_commit="b" * 40), write_manifest(d, manifest)),
    )
    check_identity(
        "wrong manifest component",
        lambda d, manifest: (manifest.update(component="other"), write_manifest(d, manifest)),
    )
    check_identity(
        "wrong manifest version",
        lambda d, manifest: (manifest.update(version="1.2.4"), write_manifest(d, manifest)),
    )
    check_identity(
        "malformed expected commit",
        lambda _d, _m: None,
        ("--expected-component", COMPONENT, "--expected-version", VERSION,
         "--expected-commit", "not-a-sha"),
    )
    check_identity(
        "identity arguments are all-or-nothing",
        lambda _d, _m: None,
        ("--expected-component", COMPONENT),
    )
    check_identity(
        "tag must bind component and version",
        lambda _d, _m: None,
        ("--expected-component", COMPONENT, "--expected-version", "1.2.4",
         "--expected-commit", COMMIT),
    )


if __name__ == "__main__":
    main()
