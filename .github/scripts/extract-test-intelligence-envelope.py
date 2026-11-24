#!/usr/bin/env python3
"""Select one main-branch Test Intelligence envelope from an Actions artifact ZIP."""

import io
import importlib.util
import json
import re
import sys
import tempfile
import zipfile
from collections.abc import Iterator
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


def validate_component(path: Path, component: str) -> None:
    envelope = json.loads(validate(path.read_bytes()))
    if envelope["component"] != component:
        raise ValueError("run envelope component differs from the staged service")


def extract(archive: bytes) -> bytes:
    with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
        names = [name for name in zipped.namelist() if ENVELOPE_NAME.search(name)]
        if len(names) != 1:
            raise ValueError(f"expected one run envelope, found {len(names)}")
        return validate(zipped.read(names[0]))


def prune_history(directory: Path) -> int:
    """Drop invalid retained envelopes before deployment."""
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


def main_artifacts(inventory: dict, repository_id: int, prefix: str) -> Iterator[tuple[int, str]]:
    """Yield same-repository main artifacts with names safe to stage locally."""
    for artifact in inventory["artifacts"]:
        run = artifact.get("workflow_run") or {}
        name = artifact["name"]
        artifact_id = artifact["id"]
        if (
            artifact["expired"] is False
            and run.get("head_branch") == "main"
            and run.get("head_repository_id") == repository_id
            and type(artifact_id) is int
            and artifact_id > 0
            and name.startswith(prefix)
            and re.fullmatch(r"test-intelligence-run-[a-z0-9-]+", name)
        ):
            yield artifact_id, name


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
    with tempfile.TemporaryDirectory() as staged:
        path = Path(staged) / "run.json"
        for payload in (good, v2):
            path.write_bytes(payload)
            validate_component(path, "openbank-example-service")
            try:
                validate_component(path, "openbank-other-service")
            except ValueError:
                pass
            else:
                raise AssertionError("wrong service envelope was accepted")
        path.write_bytes(json.dumps(example | {"schemaVersion": 3}).encode())
        try:
            validate_component(path, "openbank-example-service")
        except ValueError:
            pass
        else:
            raise AssertionError("future schema was accepted by deploy staging")
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
    entry = {"id": 42, "name": "test-intelligence-run-openbank-test-42-a1", "expired": False}
    main = {"head_branch": "main", "head_repository_id": 17}
    inventory = {"artifacts": [
        entry | {"workflow_run": main},
        entry | {"workflow_run": main | {"head_repository_id": 99}},
        entry | {"workflow_run": main | {"head_branch": "feature"}},
        entry | {"workflow_run": main, "expired": True},
        entry | {"workflow_run": main, "name": "test-intelligence-run-../invalid"},
        entry | {"workflow_run": main, "name": "test-intelligence-run-other-project"},
        entry,
    ]}
    assert list(main_artifacts(inventory, 17, "test-intelligence-run-openbank-")) == [(42, entry["name"])]
    with tempfile.TemporaryDirectory() as temp:
        cache = Path(temp)
        (cache / "valid.json").write_bytes(good)
        (cache / "wrong-branch.json").write_bytes(
            json.dumps(example | {"run": example["run"] | {"branch": "feature"}}).encode()
        )
        (cache / "malformed.json").write_bytes(b"not-json")
        assert prune_history(cache) == 2
        assert (cache / "valid.json").read_bytes() == good
        assert not (cache / "wrong-branch.json").exists()
        assert not (cache / "malformed.json").exists()


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 4 and sys.argv[1] == "--validate-component":
        try:
            validate_component(Path(sys.argv[2]), sys.argv[3])
        except (OSError, ValueError, UnicodeError, TypeError) as error:
            print(f"Test Intelligence envelope rejected: {error}", file=sys.stderr)
            sys.exit(1)
    elif len(sys.argv) == 4 and sys.argv[1] == "--main-artifacts":
        for artifact_id, name in main_artifacts(json.load(sys.stdin), int(sys.argv[2]), sys.argv[3]):
            print(artifact_id, name)
    elif len(sys.argv) == 3 and sys.argv[1] == "--prune-history":
        try:
            removed = prune_history(Path(sys.argv[2]))
            print(f"Pruned {removed} invalid retained Test Intelligence run envelope(s).")
        except (OSError, ValueError) as error:
            print(f"Test Intelligence history validation failed: {error}", file=sys.stderr)
            sys.exit(1)
    elif len(sys.argv) == 3:
        try:
            Path(sys.argv[2]).write_bytes(extract(Path(sys.argv[1]).read_bytes()))
        except (OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
            print(f"Test Intelligence envelope rejected: {error}", file=sys.stderr)
            sys.exit(1)
    else:
        print("usage: extract-test-intelligence-envelope.py ARCHIVE OUTPUT | --prune-history DIRECTORY | --main-artifacts REPOSITORY_ID PREFIX | --validate-component PATH COMPONENT", file=sys.stderr)
        sys.exit(2)
