#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""pitest.yml carries ONE mutation-score threshold, and every lane reads it.

WHY THIS EXISTS
---------------
The mutation threshold was a literal in nine places of `.github/workflows/pitest.yml`, and on
2026-10-17 two of them disagreed: the enforced authz lane ran Gradle with
`-Ppitest.mutationThreshold=70` and then built its Test Intelligence envelope with
`--mutation-threshold 63`. The envelope is what the admin dashboard projects, so a run the lane
had FAILED at 65% rendered as a PASS. Nothing could see it: the ecosystem gate asserts the string
`--mutation-threshold 70` exists in the file, and it did — in the other lanes.

THE RULE
--------
`env.PITEST_MUTATION_THRESHOLD` at workflow level is the only numeric threshold in the file. Every
`--mutation-threshold`, every `-Ppitest.mutationThreshold=` and every `[ "$SCORE" -lt ... ]`
comparison must reference `$PITEST_MUTATION_THRESHOLD` (bare or braced) and never a number.
The value itself must be an integer in 1..100.

Usage:  check-pitest-threshold-single-source.py [--workflow PATH]
        check-pitest-threshold-single-source.py --self-test
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github/workflows/pitest.yml"
ENV_NAME = "PITEST_MUTATION_THRESHOLD"

# Every way a threshold reaches a tool or a comparison in this workflow.
SITES = (
    re.compile(r"--mutation-threshold\s+(\S+)"),
    re.compile(r"-Ppitest\.mutationThreshold=(\S+)"),
    re.compile(r'\[\s*"\$SCORE"\s+-lt\s+(\S+)\s*\]'),
)
ENV_REF = re.compile(rf'^"?\$(\{{{ENV_NAME}\}}|{ENV_NAME})"?\\?$')


def strip_comments(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))


def evaluate(text: str) -> tuple[list[str], int]:
    """Findings and the number of threshold sites inspected (the gate's subjects)."""
    errors: list[str] = []
    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError as exc:  # pragma: no cover - malformed workflow fails actionlint first
        return [f"pitest.yml does not parse: {exc}"], 0
    env = doc.get("env") if isinstance(doc, dict) else None
    value = env.get(ENV_NAME) if isinstance(env, dict) else None
    if value is None:
        errors.append(f"pitest.yml declares no workflow-level env.{ENV_NAME}")
    else:
        try:
            number = int(str(value))
            if not 1 <= number <= 100:
                errors.append(f"env.{ENV_NAME} must be a percentage in 1..100, got {value}")
        except ValueError:
            errors.append(f"env.{ENV_NAME} must be an integer, got {value!r}")
    code = strip_comments(text)
    sites = 0
    for pattern in SITES:
        for match in pattern.finditer(code):
            sites += 1
            token = match.group(1)
            if not ENV_REF.match(token):
                errors.append(
                    f"threshold site `{match.group(0).strip()}` does not read ${ENV_NAME}: "
                    f"a literal here can disagree with the lane that enforces it"
                )
    if sites < 3:
        errors.append(f"expected the Gradle lane, the envelope and the comparison to each read the "
                      f"threshold; found only {sites} threshold site(s)")
    return errors, sites


def self_test() -> int:
    ok = True
    real = WORKFLOW.read_text()

    def case(label: str, text: str, expected_errors: int) -> None:
        nonlocal ok
        got = len(evaluate(text)[0])
        good = got == expected_errors
        ok = ok and good
        print(f"  [{'ok ' if good else 'FAIL'}] {label}: errors={got} expected={expected_errors}")

    case("the real workflow — must pass", real, 0)
    case("authz envelope rewritten to a literal 63 (the 2026-10-17 defect) — MUST flag",
         real.replace('--mutation-threshold "$PITEST_MUTATION_THRESHOLD"', "--mutation-threshold 63", 1), 1)
    case("Gradle lane rewritten to a literal 70 — MUST flag",
         real.replace('"-Ppitest.mutationThreshold=$PITEST_MUTATION_THRESHOLD"', "-Ppitest.mutationThreshold=70"), 1)
    case("shell comparison rewritten to a literal — MUST flag",
         real.replace('-lt "$PITEST_MUTATION_THRESHOLD"', '-lt "70"', 1), 1)
    case("env removed — MUST flag",
         real.replace(f'  {ENV_NAME}: "70"\n', ""), 1)
    case("env set to a non-number — MUST flag",
         real.replace(f'  {ENV_NAME}: "70"\n', f'  {ENV_NAME}: "seventy"\n'), 1)
    case("env out of range — MUST flag",
         real.replace(f'  {ENV_NAME}: "70"\n', f'  {ENV_NAME}: "170"\n'), 1)
    case("a literal hidden in a comment is not a site — must pass",
         real + "\n# --mutation-threshold 63 (historical note)\n", 0)
    case("every site deleted — MUST flag (the gate must not pass on an empty corpus)",
         "\n".join(l for l in real.splitlines() if "PITEST_MUTATION_THRESHOLD" not in l
                   or l.strip().startswith(ENV_NAME)), 1)
    sites = evaluate(real)[1]
    enough = sites >= 7
    ok = ok and enough
    print(f"  [{'ok ' if enough else 'FAIL'}] the real workflow has >= 7 threshold sites (found {sites})")
    print("self-test:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--workflow", default=str(WORKFLOW))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    errors, sites = evaluate(pathlib.Path(args.workflow).read_text())
    print(f"SUBJECTS={sites}  # threshold sites in pitest.yml")
    for e in errors:
        print(f"::error file=.github/workflows/pitest.yml::{e}")
    if errors:
        return 1
    print(f"pitest.yml: {sites} threshold sites all read env.{ENV_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
