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
    others = subprocess.run(["git", "ls-files", "--others", "--exclude-standard", "-z"],
                            cwd=root, check=True, capture_output=True)
    non_inputs = (".app-src/", "sbom-downloads/")
    unknown = [os.fsdecode(p) for p in others.stdout.split(b"\0") if p
               and Path(os.fsdecode(p)) not in found
               and not os.fsdecode(p).startswith(non_inputs)]
    if unknown:
        raise ValueError(f"{len(unknown)} unknown untracked build input(s)")
    return found


def freeze(root: Path, output: Path, manifest: Path) -> dict:
    if output.exists() or manifest.exists():
        raise ValueError("output and manifest must not already exist")
    if output.is_relative_to(root) or manifest.is_relative_to(root):
        raise ValueError("frozen context and manifest must be outside the source tree")
    output.mkdir(parents=True)
    entries = []
    for relative in sorted(paths(root), key=str):
        source = root / relative
        if source.is_symlink():
            link = os.readlink(source)
            resolved = (source.parent / link).resolve()
            if not resolved.is_relative_to(root):
                raise ValueError(f"external symlink in build context: {relative}")
            target = output / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.symlink_to(link)
            entries.append({"path": relative.as_posix(), "symlink": link})
            continue
        if not source.is_file():
            raise ValueError(f"missing tracked build input: {relative}")
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        content = target.read_bytes()
        entries.append({"path": relative.as_posix(), "sha256": hashlib.sha256(content).hexdigest(),
                        "size": len(content), "executable": bool(target.stat().st_mode & 0o111)})
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
