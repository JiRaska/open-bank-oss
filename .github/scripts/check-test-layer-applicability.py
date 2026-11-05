#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""The test-layer applicability register may not turn a missing test into "not applicable".

WHY THIS EXISTS
---------------
`openbank-libs/governance/test-layer-applicability.yaml` is the denominator of the Test
Intelligence e2e / trace / simulation layers (ADR-0273). A component the register leaves out of a
layer reads as "not applicable", which is not a gap. So the register is exactly where a missing
test can be laundered: an unlisted money-path service, a typo in a component name, or an exclusion
with no reason all look like a decision while being none. The collector only warns about these;
this gate fails on them.

THE RULE
--------
- The register parses with unique keys and declares the layers e2e, trace and simulation.
- Every include/exclude entry names an existing `openbank-*` directory and has a non-empty reason.
- No component is both included and excluded in one layer.
- A money-path component (`rules.yaml: money_path_services`) is applicable on every layer by
  default; the collector does that. The register can remove one only through `exclude`, so the
  check is that every exclusion is reasoned (above). Additionally, a layer without `derive` must
  name every money-path component explicitly in `include` or `exclude`, so leaving one out is a
  visible omission in review rather than an implicit default.

Usage:  check-test-layer-applicability.py [--repo DIR]
        check-test-layer-applicability.py --self-test
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import tempfile

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
REGISTER = pathlib.Path("openbank-libs/governance/test-layer-applicability.yaml")
RULES = pathlib.Path("openbank-libs/governance/rules.yaml")
LAYERS = ("e2e", "trace", "simulation")


class UniqueKeyLoader(yaml.SafeLoader):
    pass


def _unique_mapping(loader: UniqueKeyLoader, node: yaml.MappingNode, deep: bool = False) -> dict:
    keys = [loader.construct_object(key, deep=deep) for key, _ in node.value]
    duplicates = sorted({key for key in keys if keys.count(key) > 1}, key=str)
    if duplicates:
        raise yaml.constructor.ConstructorError(None, None, f"duplicate key(s) {duplicates}", node.start_mark)
    return loader.construct_mapping(node, deep=deep)


UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _unique_mapping)


def evaluate(root: pathlib.Path) -> tuple[list[str], int]:
    """Findings, and the number of register entries checked (the gate's subjects)."""
    try:
        register = yaml.load((root / REGISTER).read_text(encoding="utf-8"), Loader=UniqueKeyLoader) or {}
        rules = yaml.safe_load((root / RULES).read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError) as error:
        return [f"cannot read the register or rules.yaml: {error}"], 0
    known = {path.name for path in root.glob("openbank-*") if path.is_dir()}
    money_path = list(rules.get("money_path_services") or [])
    layers = register.get("layers") if isinstance(register, dict) else None
    if not isinstance(layers, dict):
        return [f"{REGISTER}: expected a `layers` mapping"], 0
    findings: list[str] = []
    subjects = 0
    for kind in LAYERS:
        layer = layers.get(kind)
        if not isinstance(layer, dict):
            findings.append(f"{kind}: layer is not declared")
            continue
        listed: dict[str, set[str]] = {"include": set(), "exclude": set()}
        for section in listed:
            entries = layer.get(section) or []
            if not isinstance(entries, list):
                findings.append(f"{kind}.{section}: expected a list")
                continue
            for entry in entries:
                subjects += 1
                component = entry.get("component") if isinstance(entry, dict) else None
                reason = entry.get("reason") if isinstance(entry, dict) else None
                if not isinstance(component, str) or not component:
                    findings.append(f"{kind}.{section}: entry without a component: {entry!r}")
                    continue
                if component not in known:
                    findings.append(f"{kind}.{section}: unknown component {component}")
                if not isinstance(reason, str) or not reason.strip():
                    findings.append(f"{kind}.{section}: {component} has no reason")
                listed[section].add(component)
        for component in sorted(listed["include"] & listed["exclude"]):
            findings.append(f"{kind}: {component} is both included and excluded")
        if not layer.get("derive"):
            for component in money_path:
                if component not in listed["include"] | listed["exclude"]:
                    findings.append(
                        f"{kind}: money-path {component} is neither included nor excluded with a reason",
                    )
    return findings, subjects


def self_test() -> int:
    good_register = """version: 1
layers:
  e2e:
    include:
      - component: openbank-money-service
        reason: Customer journey.
  trace:
    derive: cross-service
  simulation:
    exclude:
      - component: openbank-money-service
        reason: No time-driven process.
"""
    cases = {
        "valid register": (good_register, True),
        "unknown component": (good_register.replace("openbank-money-service\n        reason: Customer", "openbank-typo-service\n        reason: Customer"), False),
        "missing reason": (good_register.replace("        reason: Customer journey.\n", ""), False),
        "blank reason": (good_register.replace("reason: Customer journey.", "reason: '  '"), False),
        "money-path omitted from an allow-list layer": (good_register.replace("""  simulation:
    exclude:
      - component: openbank-money-service
        reason: No time-driven process.
""", "  simulation: {}\n"), False),
        "layer missing": (good_register.replace("  trace:\n    derive: cross-service\n", ""), False),
        "duplicate key": (good_register.replace("  trace:\n", "  e2e: {}\n  trace:\n"), False),
        "included and excluded": (good_register.replace("""  trace:
    derive: cross-service
""", """  trace:
    derive: cross-service
    include:
      - component: openbank-other-service
        reason: x
    exclude:
      - component: openbank-other-service
        reason: y
"""), False),
    }
    failures = 0
    for name, (register, should_pass) in cases.items():
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for module in ("openbank-money-service", "openbank-other-service"):
                (root / module).mkdir()
            (root / RULES).parent.mkdir(parents=True)
            (root / RULES).write_text("money_path_services:\n  - openbank-money-service\n", encoding="utf-8")
            (root / REGISTER).write_text(register, encoding="utf-8")
            findings, _ = evaluate(root)
            passed = not findings
            if passed != should_pass:
                failures += 1
                print(f"SELF-TEST FAIL: {name}: expected {'pass' if should_pass else 'findings'}, got {findings}")
    if failures:
        return 1
    print(f"check-test-layer-applicability self-test: {len(cases)} cases behave as expected")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", type=pathlib.Path, default=REPO)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    findings, subjects = evaluate(args.repo)
    print(f"SUBJECTS={subjects}  # register entries checked")
    for finding in findings:
        print(f"::error file={REGISTER}::{finding}")
    if findings:
        print(f"test-layer applicability: {len(findings)} finding(s)")
        return 1
    print("test-layer applicability: register is complete and every exclusion is reasoned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
