#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Record exact staged files proven byte-for-byte present in an Actions ZIP."""
from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path


def record(archive: Path, ledger: Path, source: str, artifact_id: str,
           root: Path, paths: list[Path]) -> None:
    if not artifact_id.isdecimal() or not paths:
        raise ValueError("artifact receipt requires an ID and staged paths")
    with zipfile.ZipFile(archive) as zipped:
        upstream = {hashlib.sha256(zipped.read(item)).hexdigest()
                    for item in zipped.namelist() if not item.endswith("/")}
    receipts = []
    archive_digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    for path in paths:
        if not path.is_file() or path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError(f"invalid staged artifact file: {path}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest not in upstream:
            raise ValueError(f"staged file differs from Actions artifact: {path}")
        receipts.append({"source": source, "artifactId": artifact_id,
                         "archiveSha256": archive_digest,
                         "path": path.relative_to(root).as_posix(), "sha256": digest})
    # A failed validation cannot append a partial receipt set.
    with ledger.open("a") as output:
        for item in receipts:
            output.write(json.dumps(item, sort_keys=True, separators=(",", ":")) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--artifact-id", required=True)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--member-directory", type=Path,
                        help="Record each ZIP member staged below this directory")
    parser.add_argument("--flatten", action="store_true",
                        help="The staging command discarded ZIP member directories")
    parser.add_argument("--suffix", help="Only record ZIP members with this suffix")
    parser.add_argument("paths", nargs="*", type=Path)
    args = parser.parse_args()
    paths = args.paths
    if args.member_directory is not None:
        if paths:
            parser.error("explicit paths and --member-directory are exclusive")
        with zipfile.ZipFile(args.archive) as zipped:
            names = [Path(name) for name in zipped.namelist()
                     if not name.endswith("/") and (not args.suffix or name.endswith(args.suffix))]
        if any(path.is_absolute() or ".." in path.parts for path in names):
            raise ValueError("unsafe artifact ZIP member")
        paths = [args.member_directory / (path.name if args.flatten else path) for path in names]
    record(args.archive, args.ledger, args.source, args.artifact_id,
           args.root.resolve(), paths)


if __name__ == "__main__":
    main()
