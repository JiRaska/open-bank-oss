#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Derive library proof tasks from source-bearing modules; never silently omit a new library."""
from __future__ import annotations

import argparse
from pathlib import Path

# Build-time tools, never linked into a service. Their own build/test lanes remain independent.
TOOLS = {"openbank-libs-benchmarks", "openbank-libs-detekt-rules"}


def tasks(root: Path) -> list[str]:
    modules = []
    for module in sorted(root.glob("openbank-libs*")):
        if module.name in TOOLS or not module.is_dir():
            continue
        sources = module / "src/main"
        if not any(sources.rglob("*.kt")) and not any(sources.rglob("*.java")):
            continue  # umbrella/resources-only modules have no JVM surface
        build = module / "build.gradle.kts"
        if not build.is_file() or "abiValidation()" not in build.read_text():
            raise ValueError(f"{module.name}: source-bearing library must enable ABI validation")
        snapshot = module / "api" / f"{module.name}.api"
        if not snapshot.is_file() or not snapshot.read_text().strip():
            raise ValueError(f"{module.name}: missing/empty ABI snapshot; review and run updateKotlinAbi")
        modules.append(module.name)
    if not modules:
        raise ValueError("no source-bearing libraries found; refusing an empty quality run")
    return [f":{module}:{task}" for module in modules for task in ("build", "koverVerify", "checkKotlinAbi")]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    try:
        print("\n".join(tasks(args.root)))
    except ValueError as failure:
        print(f"::error::{failure}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
