# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Recognize a coverage-floor-only edit to the shared domain build script.

This is deliberately narrow: any other change to this build script can affect every
service, so the Services CI selector must retain its full-fleet fallback.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys

PATH = "openbank-libs-domain/build.gradle.kts"
FLOOR = re.compile(r"^(\s*)minValue = (\d+)(\s*)$")


def normalized(source: str) -> tuple[str, int] | None:
    lines = source.splitlines(keepends=True)
    starts = [i for i, line in enumerate(lines) if line.strip() == "kover {"]
    floors = [(i, FLOOR.fullmatch(line.rstrip("\n"))) for i, line in enumerate(lines)]
    floors = [(i, match) for i, match in floors if match]
    if len(starts) != 1 or len(floors) != 1 or floors[0][0] <= starts[0]:
        return None
    start, (floor_line, match) = starts[0], floors[0]
    assert match is not None
    reports = [i for i in range(start + 1, floor_line) if lines[i].strip() == "reports {"]
    verifies = [i for i in range(start + 1, floor_line) if lines[i].strip() == "verify {"]
    if len(reports) != 1 or len(verifies) != 1 or not reports[0] < verifies[0] < floor_line:
        return None
    # Only prose immediately before `verify` may vary with the measurement.
    comment_start = verifies[0]
    while comment_start > reports[0] + 1 and lines[comment_start - 1].lstrip().startswith("//"):
        comment_start -= 1
    for i in range(comment_start, verifies[0]):
        lines[i] = ""
    lines[floor_line] = f"{match.group(1)}minValue = <floor>{match.group(3)}\n"
    return "".join(lines), int(match.group(2))


def safe(old: str, new: str) -> bool:
    before, after = normalized(old), normalized(new)
    return bool(before and after and before[0] == after[0] and before[1] != after[1]
                and before[1] < after[1] <= 100)


def blob(ref: str) -> str:
    return subprocess.check_output(["git", "show", f"{ref}:{PATH}"], text=True)


def self_test() -> int:
    old = """plugins { id("x") }
kover {
    reports {
        // Measured old coverage.
        verify {
            rule {
                bound {
                    minValue = 85
                    coverageUnits = LINE
                }
            }
        }
    }
}
"""
    good = old.replace("Measured old", "Measured new").replace("minValue = 85", "minValue = 87")
    cases = {
        "raised floor with updated measurement": (good, True),
        "same floor": (old, False),
        "lowered floor": (old.replace("minValue = 85", "minValue = 84"), False),
        "build plugin changed": (good.replace('id("x")', 'id("y")'), False),
        "coverage units changed": (good.replace("coverageUnits = LINE", "coverageUnits = BRANCH"), False),
        "extra build statement": (good + "dependencies { implementation(\"x:y:z\") }\n", False),
        "floor relocated": (good.replace("minValue = 87", "minValue = 87\n                    extra = true"), False),
    }
    for name, (candidate, expected) in cases.items():
        if safe(old, candidate) != expected:
            print(f"FAIL: {name}", file=sys.stderr)
            return 1
    print(f"coverage-floor-only-diff: {len(cases)} controls passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.base:
        parser.error("--base is required")
    try:
        return 0 if safe(blob(args.base), blob(args.head)) else 1
    except (subprocess.CalledProcessError, UnicodeError):
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
