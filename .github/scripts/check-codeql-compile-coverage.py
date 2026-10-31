#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Fail a manual-build CodeQL run unless every source-bearing module was traced."""

import argparse
import re
from pathlib import Path

TASK = re.compile(r"> Task :([^ :]+):(compileKotlin|compileJava)(?: (FROM-CACHE|UP-TO-DATE|NO-SOURCE|SKIPPED))?$")
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def expected_modules(root: Path) -> set[str]:
    return {
        module.name
        for module in root.glob("openbank-*")
        if (module / "build.gradle.kts").is_file()
        and any((module / "src/main/kotlin").rglob("*.kt"))
    }


def check(root: Path, log: str) -> tuple[bool, str]:
    expected = {f"{module}:compileKotlin" for module in expected_modules(root)}
    expected.update(
        f"{module.name}:compileJava"
        for module in root.glob("openbank-*")
        if (module / "build.gradle.kts").is_file()
        and any((module / "src/main/java").rglob("*.java"))
    )
    if not expected:
        return False, "no production Kotlin or Java modules found; CodeQL coverage is unknown"

    observed: dict[str, list[str]] = {}
    for line in log.splitlines():
        match = TASK.search(ANSI.sub("", line).strip())
        if match:
            task = f"{match.group(1)}:{match.group(2)}"
            observed.setdefault(task, []).append(match.group(3) or "executed")

    missing = sorted(expected - observed.keys())
    # Parallel Gradle reprints a task header when its warnings interleave, so one executed
    # compile can appear more than once; any cached or skipped outcome still disqualifies it.
    untraced = sorted(module for module in expected & observed.keys() if set(observed[module]) != {"executed"})
    if missing or untraced:
        def sample(values: list[str]) -> str:
            suffix = f" (+{len(values) - 8} more)" if len(values) > 8 else ""
            return ", ".join(values[:8]) + suffix

        detail = "; ".join(
            part for part in (
                f"missing tasks: {sample(missing)}" if missing else "",
                "not traced: " + sample([f"{m}={observed[m]}" for m in untraced])
                if untraced else "",
            ) if part
        )
        return False, f"CodeQL did not trace all {len(expected)} production compiler tasks: {detail}"
    return True, f"CodeQL traced all {len(expected)} production Kotlin/Java compiler tasks"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--log", type=Path, required=True)
    args = parser.parse_args()
    valid, message = check(args.root, args.log.read_text(errors="replace"))
    print(message if valid else f"::error::{message}")
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
