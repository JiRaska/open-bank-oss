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
# Each site is (pattern, bare_name_allowed). Inside `(( ))` shell arithmetic a variable is written
# without `$`, so only the arithmetic shape accepts the bare name.
SITES = (
    (re.compile(r"--mutation-threshold[=\s]\s*(\S+)"), False),
    (re.compile(r"-Ppitest\.mutationThreshold=(\S+)"), False),
    # `[ "$SCORE" -lt N ]` and `[[ $SCORE -lt N ]]`, any numeric comparison operator.
    (re.compile(r'\[\[?\s*"?\$\{?SCORE\}?"?\s+-(?:lt|le|gt|ge|eq|ne)\s+(\S+?)\s*\]\]?'), False),
    # `(( SCORE < N ))`, `(( $SCORE >= N ))`.
    (re.compile(r"\(\(\s*\$?\{?SCORE\}?\s*(?:<=|>=|<|>|==|!=)\s*(\S+?)\s*\)\)"), True),
)
ENV_REF = re.compile(rf'^"?\$(\{{{ENV_NAME}\}}|{ENV_NAME})"?\\?$')
BARE_REF = re.compile(rf"^(?:\$)?{ENV_NAME}$")
# A shell assignment to the name (`PITEST_MUTATION_THRESHOLD=63`, `export ...=63`) shadows the env too.
SHELL_ASSIGN = re.compile(rf"(?<![\w$]){ENV_NAME}=")


class _UniqueKeyLoader(yaml.SafeLoader):
    """SafeLoader that refuses a repeated mapping key instead of silently keeping the last."""


def _unique_mapping(loader: yaml.SafeLoader, node: yaml.MappingNode, deep: bool = False):
    seen: set = set()
    for key_node, _ in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in seen:
            raise yaml.constructor.ConstructorError(
                None, None, f"duplicate mapping key {key!r}", key_node.start_mark)
        seen.add(key)
    return yaml.SafeLoader.construct_mapping(loader, node, deep)


_UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _unique_mapping)


def stray_env_keys(node, path: str = "") -> list[str]:
    """Paths of every ENV_NAME key that is NOT the workflow-level env (a job- or step-level `env:`
    silently overrides the top-level value for that scope, so the gate would pass on a 63)."""
    found: list[str] = []
    if isinstance(node, dict):
        for key, value in node.items():
            here = f"{path}.{key}" if path else str(key)
            if key == ENV_NAME and here != f"env.{ENV_NAME}":
                found.append(here)
            found += stray_env_keys(value, here)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            found += stray_env_keys(value, f"{path}[{index}]")
    return found


def strip_comments(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))


def evaluate(text: str) -> tuple[list[str], int]:
    """Findings and the number of threshold sites inspected (the gate's subjects)."""
    errors: list[str] = []
    try:
        doc = yaml.load(text, Loader=_UniqueKeyLoader)  # noqa: S506 - SafeLoader subclass
    except yaml.YAMLError as exc:
        return [f"pitest.yml does not parse (a repeated mapping key counts): {exc}"], 0
    for stray in stray_env_keys(doc):
        errors.append(f"{stray} redefines {ENV_NAME} below the workflow level and overrides it for "
                      f"that scope; only the top-level env.{ENV_NAME} may exist")
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
    for match in SHELL_ASSIGN.finditer(code):
        errors.append(f"a shell assignment `{match.group(0)}` shadows the workflow env inside a step")
    for pattern, bare_allowed in SITES:
        for match in pattern.finditer(code):
            sites += 1
            token = match.group(1)
            if not (ENV_REF.match(token) or (bare_allowed and BARE_REF.match(token))):
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

    def case(label: str, text: str, expected_errors: int, reason: str = "") -> None:
        """`reason` must appear in the findings, so a case cannot go red for the wrong cause."""
        nonlocal ok
        errors = evaluate(text)[0]
        good = len(errors) == expected_errors and (not reason or reason in "\n".join(errors))
        ok = ok and good
        print(f"  [{'ok ' if good else 'FAIL'}] {label}: errors={len(errors)} expected={expected_errors}"
              + (f" reason~{reason!r}" if reason else ""))

    literal = '--mutation-threshold "$PITEST_MUTATION_THRESHOLD"'
    compare = 'if [ "$SCORE" -lt "$PITEST_MUTATION_THRESHOLD" ]; then'
    gradle_opts = '          GRADLE_OPTS: "-Dorg.gradle.daemon=false -Xmx2g"\n'
    env_line = f'  {ENV_NAME}: "70"\n'
    assert gradle_opts in real, "fixture anchor: a step-level env block must exist"
    authz = real.index("  pitest-authz:")
    authz_envelope = real.index(literal, authz)
    assert authz < authz_envelope < real.index("  pitest-libs:"), "the located envelope must be the authz job's"

    case("the real workflow — must pass", real, 0)
    case("authz lane's OWN envelope rewritten to a literal 63 (the 2026-10-17 defect) — MUST flag",
         real[:authz_envelope] + real[authz_envelope:].replace(literal, "--mutation-threshold 63", 1),
         1, "does not read")
    case("envelope written as --mutation-threshold=63 — MUST flag",
         real.replace(literal, "--mutation-threshold=63", 1), 1, "does not read")
    case("envelope written as --mutation-threshold=\"$PITEST_MUTATION_THRESHOLD\" — must pass",
         real.replace(literal, '--mutation-threshold="$PITEST_MUTATION_THRESHOLD"', 1), 0)
    case("Gradle lane rewritten to a literal 70 — MUST flag",
         real.replace('"-Ppitest.mutationThreshold=$PITEST_MUTATION_THRESHOLD"', "-Ppitest.mutationThreshold=70"),
         1, "does not read")
    case("shell comparison rewritten to a literal — MUST flag",
         real.replace(compare, 'if [ "$SCORE" -lt "70" ]; then', 1), 1, "does not read")
    case("[[ ]] comparison against a literal — MUST flag",
         real.replace(compare, 'if [[ "$SCORE" -lt 63 ]]; then', 1), 1, "does not read")
    case("(( SCORE < N )) comparison against a literal — MUST flag",
         real.replace(compare, "if (( SCORE < 63 )); then", 1), 1, "does not read")
    case("(( SCORE < PITEST_MUTATION_THRESHOLD )) — must pass",
         real.replace(compare, "if (( SCORE < PITEST_MUTATION_THRESHOLD )); then", 1), 0)
    case("job-level env override of the threshold — MUST flag",
         real.replace("  pitest-authz:\n", f'  pitest-authz:\n    env:\n      {ENV_NAME}: "63"\n', 1),
         1, "redefines")
    case("step-level env override of the threshold (inside an existing step env) — MUST flag",
         real.replace(gradle_opts, gradle_opts + f'          {ENV_NAME}: "63"\n', 1), 1, "redefines")
    case("duplicate top-level env key (the last one would silently win) — MUST flag",
         real.replace(env_line, env_line + f'  {ENV_NAME}: "63"\n', 1), 1, "duplicate mapping key")
    case("shell assignment inside a step shadows the env — MUST flag",
         real.replace("          XML=", f"          {ENV_NAME}=63\n          XML=", 1), 1, "shadows")
    case("env removed — MUST flag", real.replace(env_line, ""), 1, "declares no workflow-level")
    case("env set to a non-number — MUST flag",
         real.replace(env_line, f'  {ENV_NAME}: "seventy"\n'), 1, "must be an integer")
    case("env out of range — MUST flag",
         real.replace(env_line, f'  {ENV_NAME}: "170"\n'), 1, "1..100")
    case("a literal hidden in a comment is not a site — must pass",
         real + "\n# --mutation-threshold 63 (historical note)\n", 0)
    case("every site deleted — MUST flag (the gate must not pass on an empty corpus)",
         "\n".join(line for line in real.splitlines() if ENV_NAME not in line or line.strip().startswith(ENV_NAME)),
         1, "found only")
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
