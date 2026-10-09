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
           root: Path, bindings: list[tuple[str, Path]]) -> None:
    if not artifact_id.isdecimal() or not bindings:
        raise ValueError("artifact receipt requires an ID and member bindings")
    receipts = []
    archive_digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    with zipfile.ZipFile(archive) as zipped:
        names = [item.filename for item in zipped.infolist()]
        if len(names) != len(set(names)):
            raise ValueError("Actions artifact contains duplicate members")
        for member, path in bindings:
            member_path = Path(member)
            if (member_path.is_absolute() or ".." in member_path.parts or member not in names
                    or member.endswith("/")):
                raise ValueError(f"invalid Actions artifact member: {member}")
            staged = path if path.is_absolute() else root / path
            if not staged.is_file() or staged.is_symlink() or not staged.resolve().is_relative_to(root.resolve()):
                raise ValueError(f"invalid staged artifact file: {path}")
            content = staged.read_bytes()
            if content != zipped.read(member):
                raise ValueError(f"staged file differs from named Actions artifact member: {path}")
            receipts.append({"source": source, "artifactId": artifact_id,
                             "archiveSha256": archive_digest, "member": member,
                             "path": staged.relative_to(root).as_posix(),
                             "sha256": hashlib.sha256(content).hexdigest()})
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
    parser.add_argument("--bind", nargs=2, action="append", metavar=("MEMBER", "STAGED_PATH"), default=[])
    args = parser.parse_args()
    bindings = [(member, Path(path)) for member, path in args.bind]
    if args.member_directory is not None:
        if bindings:
            parser.error("--bind and --member-directory are exclusive")
        with zipfile.ZipFile(args.archive) as zipped:
            names = [name for name in zipped.namelist()
                     if not name.endswith("/") and (not args.suffix or name.endswith(args.suffix))]
        if any(Path(name).is_absolute() or ".." in Path(name).parts for name in names):
            raise ValueError("unsafe artifact ZIP member")
        bindings = [(name, args.member_directory / (Path(name).name if args.flatten else name))
                    for name in names]
    record(args.archive, args.ledger, args.source, args.artifact_id,
           args.root.resolve(), bindings)


if __name__ == "__main__":
    main()
