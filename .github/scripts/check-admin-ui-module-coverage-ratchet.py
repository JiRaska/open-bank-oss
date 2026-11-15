#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ADR-0370 U3: Admin UI Vitest coverage floors only go up.

`openbank-admin-ui/vitest.config.ts` carries a global floor and one floor per `src/lib`
decision module (`'src/lib/<module>/**': { statements, branches, functions, lines }`). CI runs
the suite with `--coverage`, so Vitest fails a PR that drops below a floor. What Vitest cannot
see is the floor itself being lowered or deleted in the same PR, so this gate compares every
floor at HEAD with the PR's merge base. A lowered or removed floor fails unless the PR body or a
commit in the range carries `coverage-floor-lowered: #<issue>`, the same escape the Kover
ratchet (`check-coverage-floor-ratchet.py`, ADR-0020) uses, and its trailer reader is reused.

The thresholds block is parsed with a strict shape: a value that is not an integer literal is
refused, never guessed.
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import pathlib
import re
import subprocess
import sys

CONFIG = "openbank-admin-ui/vitest.config.ts"
METRICS = ("statements", "branches", "functions", "lines")
HERE = pathlib.Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location("kover_ratchet", HERE / "check-coverage-floor-ratchet.py")
assert _spec and _spec.loader
kover = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(kover)

GLOB_RE = re.compile(r"'([^']+)':\s*\{([^}]*)\}")
PAIR_RE = re.compile(r"(\w+)\s*:\s*([^,}\s]+)")


class Refused(ValueError):
    pass


def parse(text: str) -> dict[str, int]:
    start = text.find("thresholds: {")
    if start < 0:
        raise Refused("no `thresholds: {` block")
    depth, i = 0, text.index("{", start)
    for j in range(i, len(text)):
        depth += {"{": 1, "}": -1}.get(text[j], 0)
        if depth == 0:
            block = text[i + 1 : j]
            break
    else:
        raise Refused("unterminated thresholds block")
    floors: dict[str, int] = {}

    def take(scope: str, body: str) -> None:
        for key, value in PAIR_RE.findall(body):
            if key not in METRICS:
                continue
            if not value.isdigit():
                raise Refused(f"{scope}.{key} = {value!r} is not an integer literal")
            floors[f"{scope}.{key}"] = int(value)

    for glob, body in GLOB_RE.findall(block):
        take(glob, body)
    take("global", GLOB_RE.sub("", block))
    return floors


def compare(base: dict[str, int], head: dict[str, int]) -> list[str]:
    out = []
    for key, old in sorted(base.items()):
        new = head.get(key)
        if new is None:
            out.append(f"{key}: floor {old} removed")
        elif new < old:
            out.append(f"{key}: floor lowered {old} -> {new}")
    return out


def main_check(base_arg: str, body: str | None) -> int:
    base = (base_arg or os.environ.get("PR_DIFF_BASE", "")).strip()
    if not base:
        print("::error::PR_DIFF_BASE is empty but this gate requires it — refusing to run vacuously")
        return 2
    try:
        old_text = subprocess.run(["git", "show", f"{base}:{CONFIG}"], check=True, capture_output=True, text=True).stdout
    except subprocess.CalledProcessError:
        print(f"::error::cannot read {CONFIG} at base `{base}` — refusing to run vacuously")
        return 2
    try:
        head = parse(pathlib.Path(CONFIG).read_text(encoding="utf-8"))
        old = parse(old_text)
    except Refused as exc:
        print(f"::error file={CONFIG}::{exc}")
        return 1
    print(f"SUBJECTS={len(head)}")
    lowered = compare(old, head)
    if not lowered:
        print(f"admin-ui-module-coverage-ratchet: {len(head)} floor(s), none lowered")
        return 0
    if kover.lowering_justified(kover.pr_trailer_text(base, body)):
        print(f"admin-ui-module-coverage-ratchet: {len(lowered)} floor(s) lowered under a `{kover.TRAILER}` trailer")
        return 0
    for line in lowered:
        print(f"::error file={CONFIG}::{line} (ADR-0370 U3). To re-baseline deliberately, add `{kover.TRAILER} #<issue>`.")
    return 1


def self_test() -> None:
    base = """thresholds: {
        statements: 72,
        branches: 61,
        'src/lib/approvals/**': { statements: 94, branches: 92, functions: 99, lines: 99 },
      },"""
    floors = parse(base)
    assert floors == {
        "global.statements": 72, "global.branches": 61,
        "src/lib/approvals/**.statements": 94, "src/lib/approvals/**.branches": 92,
        "src/lib/approvals/**.functions": 99, "src/lib/approvals/**.lines": 99,
    }, floors
    assert compare(floors, floors) == []
    raised = parse(base.replace("statements: 72", "statements: 80"))
    assert compare(floors, raised) == []
    lowered = parse(base.replace("branches: 92", "branches: 50"))
    assert compare(floors, lowered) == ["src/lib/approvals/**.branches: floor lowered 92 -> 50"]
    removed = parse(base.replace("        'src/lib/approvals/**': { statements: 94, branches: 92, functions: 99, lines: 99 },\n", ""))
    assert len(compare(floors, removed)) == 4 and all("removed" in x for x in compare(floors, removed))
    try:
        parse(base.replace("statements: 72", "statements: FLOOR"))
    except Refused:
        pass
    else:
        raise AssertionError("a non-literal floor must be refused")
    assert kover.lowering_justified("coverage-floor-lowered: #123")
    assert not kover.lowering_justified("coverage-floor-lowered: soon")
    print("self-test: ok")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="")
    parser.add_argument("--body", default=None)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    sys.exit(main_check(args.base, args.body))


if __name__ == "__main__":
    main()
