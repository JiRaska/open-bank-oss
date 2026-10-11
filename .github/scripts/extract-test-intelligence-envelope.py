#!/usr/bin/env python3
"""Select one main-branch Test Intelligence envelope from an Actions artifact ZIP."""

import io
import importlib.util
import json
import re
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path


ENVELOPE_NAME = re.compile(r"(?:^|/)run(?:-[a-z0-9-]+)?\.json$")
COLLECTOR_PATH = Path(__file__).with_name("collect-test-run-evidence.py")
SPEC = importlib.util.spec_from_file_location("test_run_evidence_collector", COLLECTOR_PATH)
assert SPEC and SPEC.loader
COLLECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COLLECTOR)


def validate(payload: bytes) -> bytes:
    envelope = json.loads(payload)
    if not isinstance(envelope, dict) or envelope.get("schemaVersion") not in (1, 2) or not isinstance(envelope.get("run"), dict):
        raise ValueError("run envelope has no supported schema or provenance")
    COLLECTOR.validate_envelope(envelope)
    if envelope["run"].get("branch") != "main":
        raise ValueError("run envelope is not main-branch evidence")
    return payload


def extract(archive: bytes) -> bytes:
    with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
        names = [name for name in zipped.namelist() if ENVELOPE_NAME.search(name)]
        if len(names) != 1:
            raise ValueError(f"expected one run envelope, found {len(names)}")
        return validate(zipped.read(names[0]))


def prune_cache(directory: Path) -> int:
    """Drop pre-validator cache entries that must not enter a new image."""
    if not directory.is_dir():
        raise ValueError(f"run history is not a directory: {directory}")
    removed = 0
    for path in directory.glob("*.json"):
        try:
            validate(path.read_bytes())
        except (OSError, ValueError, UnicodeError):
            path.unlink()
            removed += 1
    return removed


def self_test() -> None:
    def archive(files: dict[str, bytes]) -> bytes:
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as zipped:
            for name, content in files.items():
                zipped.writestr(name, content)
        return buffer.getvalue()

    example = {
        "schemaVersion": 1, "component": "openbank-example-service", "suites": [],
        "coverage": None, "testInfrastructure": {"declared": [], "observed": []},
        "run": {"id": "123", "attempt": 1, "commit": "abcdef012345",
                "branch": "main", "workflow": "Services CI", "url": "",
                "observedAt": datetime.now(timezone.utc).isoformat()},
    }
    good = json.dumps(example).encode()
    v2 = json.dumps(example | {"schemaVersion": 2}).encode()
    for name in ("run.json", "run-chromium.json", "nested/run-security-excellence-firefox.json"):
        assert extract(archive({name: good})) == good
        assert extract(archive({name: v2})) == v2
    for files in (
        {"run.json": good, "run-firefox.json": good},
        {"report.json": good},
        {"run.json": b"not-json"},
        {"run.json": b"[]"},
        {"run.json": json.dumps(example | {"run": example["run"] | {"branch": "feature"}}).encode()},
        {"run.json": json.dumps(example | {"schemaVersion": 3}).encode()},
    ):
        try:
            extract(archive(files))
        except (ValueError, json.JSONDecodeError):
            pass
        else:
            raise AssertionError(f"invalid artifact was accepted: {list(files)}")
    with tempfile.TemporaryDirectory() as temp:
        cache = Path(temp)
        (cache / "valid.json").write_bytes(good)
        (cache / "wrong-branch.json").write_bytes(
            json.dumps(example | {"run": example["run"] | {"branch": "feature"}}).encode()
        )
        (cache / "malformed.json").write_bytes(b"not-json")
        (cache / ".staged-ids").write_text("42\n43\n")
        assert prune_cache(cache) == 2
        assert (cache / "valid.json").read_bytes() == good
        assert not (cache / "wrong-branch.json").exists()
        assert not (cache / "malformed.json").exists()
        assert (cache / ".staged-ids").read_text() == "42\n43\n"


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 3 and sys.argv[1] == "--prune-cache":
        try:
            removed = prune_cache(Path(sys.argv[2]))
            print(f"Pruned {removed} invalid cached Test Intelligence run envelope(s).")
        except (OSError, ValueError) as error:
            print(f"Test Intelligence cache validation failed: {error}", file=sys.stderr)
            sys.exit(1)
    elif len(sys.argv) == 3:
        try:
            Path(sys.argv[2]).write_bytes(extract(Path(sys.argv[1]).read_bytes()))
        except (OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
            print(f"Test Intelligence envelope rejected: {error}", file=sys.stderr)
            sys.exit(1)
    else:
        print("usage: extract-test-intelligence-envelope.py ARCHIVE OUTPUT | --prune-cache DIRECTORY", file=sys.stderr)
        sys.exit(2)
