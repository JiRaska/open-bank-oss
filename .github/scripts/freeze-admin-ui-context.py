#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Freeze the Admin UI Docker context after collectors have written their inputs.

Only tracked files and explicitly named generated evidence enter this context. A
local untracked source file therefore cannot silently enter a released image.
The output directory must be outside the repository and is consumed by buildx.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path


GENERATED_DIRS = (
    "openbank-admin-ui/client-test-evidence",
    "openbank-admin-ui/perf-artifacts",
    "openbank-admin-ui/test-intelligence-history",
    "openbank-admin-ui/test-run-history",
)
FROZEN_SBOM_DIR = Path("admin-ui-sbom-inputs")
GENERATED_ROOT_JSON = (
    "ai-governance-snapshot.json", "app-status.json", "card-capabilities.json",
    "catalog.json", "cluster-topology.json", "cost-footprints.json",
    "cost-report.json", "dora.json", "events.json", "gate-catalog.json",
    "gate-health.json", "governance.json", "infra-lifecycle.json",
    "infra-vulns.json", "origination-graph.json", "prod-readiness.json",
    "quality-report.json", "security-graph.json", "service-graph.json",
    "test-intelligence.json", "test-results.json",
)


def paths(root: Path) -> set[Path]:
    proc = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True)
    found = {Path(os.fsdecode(p)) for p in proc.stdout.split(b"\0") if p}
    for name in GENERATED_ROOT_JSON:
        p = Path("openbank-admin-ui") / name
        if (root / p).is_file():
            found.add(p)
    for name in GENERATED_DIRS:
        directory = root / name
        if directory.exists():
            found.update(p.relative_to(root) for p in directory.rglob("*") if p.is_file())
    # The deploy workflow stages these gitignored reports after checkout. The
    # Dockerfile consumes them, so their exact bytes must enter the inventory.
    for sbom in root.glob("openbank-*/build/reports/bom.json"):
        if sbom.is_symlink() or not sbom.resolve().is_relative_to(root.resolve()):
            raise ValueError("staged SBOM must not be a symlink")
        if sbom.is_file():
            found.add(sbom.relative_to(root))
    others = subprocess.run(["git", "ls-files", "--others", "--exclude-standard", "-z"],
                            cwd=root, check=True, capture_output=True)
    non_inputs = (".app-src/", "sbom-downloads/")
    unknown = [os.fsdecode(p) for p in others.stdout.split(b"\0") if p
               and Path(os.fsdecode(p)) not in found
               and not os.fsdecode(p).startswith(non_inputs)]
    if unknown:
        raise ValueError(f"{len(unknown)} unknown untracked build input(s)")
    return found


def is_staged_sbom(relative: Path) -> bool:
    parts = relative.parts
    return (len(parts) == 4 and parts[0].startswith("openbank-")
            and parts[1:] == ("build", "reports", "bom.json"))


def require_clean_source_inputs(root: Path) -> None:
    """Only explicitly generated evidence may differ from the source commit."""
    changed = subprocess.run(
        ["git", "diff", "HEAD", "--name-only", "-z"],
        cwd=root, check=True, capture_output=True,
    )
    generated = {Path("openbank-admin-ui") / name for name in GENERATED_ROOT_JSON}
    dirty = [Path(os.fsdecode(name)) for name in changed.stdout.split(b"\0") if name]
    if any(name not in generated for name in dirty):
        raise ValueError("tracked source input differs from source commit")


def committed_blobs(root: Path) -> tuple[dict[Path, str], str]:
    listing = subprocess.run(["git", "ls-tree", "-rz", "HEAD"], cwd=root,
                             check=True, capture_output=True).stdout
    blobs = {}
    for entry in listing.split(b"\0"):
        if not entry:
            continue
        header, name = entry.split(b"\t", 1)
        mode, kind, object_id = header.split(b" ")
        if kind == b"blob":
            blobs[Path(os.fsdecode(name))] = object_id.decode("ascii")
    object_format = subprocess.run(["git", "rev-parse", "--show-object-format=storage"],
                                   cwd=root, check=True, capture_output=True,
                                   text=True).stdout.strip()
    if object_format not in ("sha1", "sha256"):
        raise ValueError("unknown source commit object format")
    return blobs, object_format


def require_committed_content(relative: Path, content: bytes, blobs: dict[Path, str],
                              object_format: str) -> None:
    if relative in {Path("openbank-admin-ui") / name for name in GENERATED_ROOT_JSON}:
        return
    expected = blobs.get(relative)
    # These four evidence directories are produced after checkout and need not
    # have Git objects. Their bytes still enter the signed frozen inventory.
    if expected is None and any(relative.is_relative_to(Path(directory))
                                and relative != Path(directory) for directory in GENERATED_DIRS):
        return
    if expected is None and is_staged_sbom(relative):
        return
    digest = hashlib.new(object_format, b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest()
    if expected != digest:
        raise ValueError(f"frozen tracked input differs from source commit: {relative}")


def freeze(root: Path, output: Path, manifest: Path) -> dict:
    if output.exists() or manifest.exists():
        raise ValueError("output and manifest must not already exist")
    if output.is_relative_to(root) or manifest.is_relative_to(root):
        raise ValueError("frozen context and manifest must be outside the source tree")
    if (root / FROZEN_SBOM_DIR).exists():
        raise ValueError("reserved frozen SBOM input path exists in source tree")
    require_clean_source_inputs(root)
    blobs, object_format = committed_blobs(root)
    output.mkdir(parents=True)
    entries = []
    for relative in sorted(paths(root), key=str):
        source = root / relative
        frozen_relative = (FROZEN_SBOM_DIR / relative.parts[0] / "bom.json"
                           if is_staged_sbom(relative) else relative)
        if source.is_symlink():
            link = os.readlink(source)
            resolved = (source.parent / link).resolve()
            if not resolved.is_relative_to(root):
                raise ValueError(f"external symlink in build context: {relative}")
            require_committed_content(relative, os.fsencode(link), blobs, object_format)
            target = output / frozen_relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.symlink_to(link)
            entries.append({"path": frozen_relative.as_posix(), "symlink": link})
            continue
        if not source.is_file():
            raise ValueError(f"missing tracked build input: {relative}")
        target = output / frozen_relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        content = target.read_bytes()
        require_committed_content(relative, content, blobs, object_format)
        entries.append({"path": frozen_relative.as_posix(), "sha256": hashlib.sha256(content).hexdigest(),
                        "size": len(content), "executable": bool(target.stat().st_mode & 0o111)})
    require_clean_source_inputs(root)
    source_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                capture_output=True, text=True).stdout.strip()
    record = {"schema": "openbank.admin-ui.build-context/v1", "sourceCommit": source_sha,
              "files": entries}
    manifest.write_text(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
    return record


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    record = freeze(args.root.resolve(), args.output.resolve(), args.manifest.resolve())
    print(f"frozen Admin UI context: {len(record['files'])} files from {record['sourceCommit']}")


if __name__ == "__main__":
    main()
