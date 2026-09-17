#!/usr/bin/env python3
"""Select one main-branch Test Intelligence envelope from an Actions artifact ZIP."""

import io
import json
import re
import sys
import zipfile
from pathlib import Path


ENVELOPE_NAME = re.compile(r"(?:^|/)run(?:-[a-z0-9-]+)?\.json$")


def extract(archive: bytes) -> bytes:
    with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
        names = [name for name in zipped.namelist() if ENVELOPE_NAME.search(name)]
        if len(names) != 1:
            raise ValueError(f"expected one run envelope, found {len(names)}")
        payload = zipped.read(names[0])
    envelope = json.loads(payload)
    if envelope.get("schemaVersion") != 1 or not isinstance(envelope.get("run"), dict):
        raise ValueError("run envelope has no supported schema or provenance")
    if envelope["run"].get("branch") != "main":
        raise ValueError("run envelope is not main-branch evidence")
    return payload


def self_test() -> None:
    def archive(files: dict[str, bytes]) -> bytes:
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as zipped:
            for name, content in files.items():
                zipped.writestr(name, content)
        return buffer.getvalue()

    good = json.dumps({"schemaVersion": 1, "run": {"branch": "main"}}).encode()
    for name in ("run.json", "run-chromium.json", "nested/run-security-excellence-firefox.json"):
        assert extract(archive({name: good})) == good
    for files in (
        {"run.json": good, "run-firefox.json": good},
        {"report.json": good},
        {"run.json": b"not-json"},
        {"run.json": json.dumps({"schemaVersion": 1, "run": {"branch": "feature"}}).encode()},
    ):
        try:
            extract(archive(files))
        except (ValueError, json.JSONDecodeError):
            pass
        else:
            raise AssertionError(f"invalid artifact was accepted: {list(files)}")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 3:
        try:
            Path(sys.argv[2]).write_bytes(extract(Path(sys.argv[1]).read_bytes()))
        except (OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
            print(f"Test Intelligence envelope rejected: {error}", file=sys.stderr)
            sys.exit(1)
    else:
        print("usage: extract-test-intelligence-envelope.py ARCHIVE OUTPUT", file=sys.stderr)
        sys.exit(2)
