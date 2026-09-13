#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Money-path journey accountability (ADR-0252 phase 2, issue #4348).
#
# WHY THIS EXISTS
#   check-journey-catalog.py makes every claim IN the catalog checkable. It cannot say anything
#   about what the catalog never mentions — and for a coverage claim, the omissions are the
#   dangerous part. A catalog listing three journeys is not distinguishable, from the inside,
#   from a platform that watches three things out of three or three out of two hundred.
#
#   So this gate holds the catalog against an EXTERNAL, independently-maintained list: the
#   money-path services in `openbank-libs/governance/rules.yaml`. Every one of them must be
#   accounted for — covered by a journey, or listed with the reason it is not.
#
#   The two directions matter equally:
#     * a money-path service absent from the catalog is RED. A new money-path service is then
#       red from the day it is declared until a human decides what watches it, instead of
#       being silently outside a coverage claim nobody re-derives.
#     * an accountability entry for a service that is no longer money-path is ALSO red. A
#       stale entry is how a list grows a comfortable margin of things that are not true.
#
#   That is the property the repo keeps paying for elsewhere: never let a gate's scope be
#   maintained separately from the thing it covers. Here the scope is not maintained at all —
#   it is read from rules.yaml, which is maintained for its own reasons by other people.
#
# WHAT IT DELIBERATELY DOES NOT CLAIM
#   That a service marked `covered_by` is WELL covered — a journey's assertions are a review
#   question, not a static one. And that the money path is the whole customer-visible surface:
#   deriving the full capability set from the fleet's OpenAPI documents is the unbuilt half
#   (#4348). This closes the gap that matters most, and says so rather than implying the rest.
#
# EXIT CODES
#   0 — every money-path service is accounted for, and nothing stale
#   1 — the gate could not answer (a file missing or unparseable, or an empty money-path list).
#       A scan that read nothing must never report green.
#   2 — findings (with --enforce; without it they are ::warning and the exit is 0)
#
# Run:  python3 .github/scripts/check-journey-coverage.py --root . [--enforce]
#       python3 .github/scripts/check-journey-coverage.py --self-test

import argparse
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import gatelib  # noqa: E402  (path insert must precede the import)

try:
    import yaml
except ImportError:  # pragma: no cover - environment guard
    print("::error::check-journey-coverage: PyYAML is required")
    sys.exit(1)

CATALOG = "openbank-libs/governance/journeys.yaml"
RULES = "openbank-libs/governance/rules.yaml"


def load(root: pathlib.Path, rel: str):
    path = root / rel
    if not path.is_file():
        return None, f"not found: {rel}"
    try:
        return yaml.safe_load(path.read_text(encoding="utf-8")) or {}, None
    except yaml.YAMLError as exc:
        return None, f"{rel} is not valid YAML ({exc})"


def check(root: pathlib.Path):
    """(findings, fatal, subjects)."""
    rules, err = load(root, RULES)
    if err:
        return [], err, 0
    catalog, err = load(root, CATALOG)
    if err:
        return [], err, 0

    money_path = rules.get("money_path_services") or []
    if not money_path:
        return [], "rules.yaml declares no money_path_services — nothing to check against", 0

    findings = []

    # A service may also be accounted for by a journey that names it, which is the state every
    # entry is trying to reach. Read it off the journeys so the two halves cannot disagree.
    covered_by_journey = set()
    for journey in catalog.get("journeys") or []:
        # A target that exists only on paper is useful planning metadata, not coverage.
        # Counting planned entries here would let the numerator reach 100% without a scheduler.
        if isinstance(journey, dict) and journey.get("status") == "active":
            covered_by_journey.update(journey.get("covers") or [])

    block = catalog.get("money_path_accountability") or {}
    entries = block.get("services") or []
    uncovered = [n for n in money_path if n not in covered_by_journey]
    if not entries and uncovered:
        # Only a finding when something actually needs accounting for. Once every money-path
        # service is covered by a journey the block is legitimately empty, and demanding it
        # anyway would make the gate red at the exact moment the platform got it right.
        findings.append(
            f"{CATALOG} has no money_path_accountability.services block, but "
            f"{len(uncovered)} money-path service(s) are covered by no journey"
        )

    accounted = {}
    for entry in entries:
        if not isinstance(entry, dict) or not entry.get("service"):
            findings.append("a money_path_accountability.services entry has no `service`")
            continue
        name = entry["service"]
        if name in accounted:
            findings.append(f"{name}: listed twice in money_path_accountability")
        accounted[name] = entry

    default_blocker = (block.get("default_blocker") or "").strip()

    for name in money_path:
        if name in covered_by_journey:
            continue
        if name not in accounted:
            findings.append(
                f"{name} is a money-path service with no journey and no accountability entry — "
                "a service outside the catalog is outside its coverage claim, silently"
            )
            continue
        entry = accounted[name]
        if not default_blocker and not (entry.get("note") or entry.get("blocked_by")):
            findings.append(
                f"{name}: accounted for but nothing says why it is unwatched — set the block's "
                "`default_blocker`, or give this entry its own `note`/`blocked_by`"
            )

    for name in sorted(accounted):
        if name not in money_path:
            findings.append(
                f"{name}: listed in money_path_accountability but rules.yaml no longer calls it "
                "money-path — a stale entry is how a list grows a margin that is not true"
            )

    watched = sum(1 for n in money_path if n in covered_by_journey)
    # Printed on every run, pass or fail: the number IS the finding when it is small, and a
    # coverage claim that is never quantified is the thing this gate exists to prevent.
    print(
        f"check-journey-coverage: {watched}/{len(money_path)} money-path services are covered by "
        f"a journey; {len(accounted)} accounted for as unwatched."
    )
    return findings, None, len(money_path)


SELF_TEST_RULES = """
money_path_services:
  - openbank-alpha-service
  - openbank-beta-service
"""

SELF_TEST_CATALOG = """
version: 1
journeys:
  - id: alpha
    title: Alpha
    capability: proves alpha works
    status: active
    severity: page
    money_moving: true
    covers: [openbank-alpha-service]
    falsification: point it at a dead host
money_path_accountability:
  default_blocker: "needs synthetic parties (#4348)"
  services:
    - service: openbank-beta-service
"""


def self_test():
    cases = []

    def run(label, rules, catalog, expect_finding):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "openbank-libs/governance").mkdir(parents=True, exist_ok=True)
            (root / RULES).write_text(rules, encoding="utf-8")
            (root / CATALOG).write_text(catalog, encoding="utf-8")
            findings, fatal, _ = check(root)
            got = bool(findings) or bool(fatal)
            cases.append((label, got == expect_finding, findings or ([fatal] if fatal else [])))

    # Control first: without a case that stays clean, a checker that flags everything passes.
    run("control: one covered, one accounted — clean",
        SELF_TEST_RULES, SELF_TEST_CATALOG, expect_finding=False)

    run("a money-path service in neither the journeys nor the accountability block",
        SELF_TEST_RULES + "  - openbank-gamma-service\n", SELF_TEST_CATALOG, expect_finding=True)

    run("an accountability entry for a service that is no longer money-path",
        "money_path_services:\n  - openbank-alpha-service\n", SELF_TEST_CATALOG, expect_finding=True)

    run("covered by a journey needs no accountability entry",
        SELF_TEST_RULES,
        SELF_TEST_CATALOG.replace("covers: [openbank-alpha-service]",
                                  "covers: [openbank-alpha-service, openbank-beta-service]")
                         .replace("    - service: openbank-beta-service\n", ""),
        expect_finding=False)

    run("a planned journey does not count as active coverage",
        SELF_TEST_RULES,
        SELF_TEST_CATALOG.replace("status: active", "status: planned")
                         .replace("    falsification:", "    blocked_by: not deployed\n    falsification:"),
        expect_finding=True)

    run("accounted for with no blocker anywhere",
        SELF_TEST_RULES,
        SELF_TEST_CATALOG.replace('  default_blocker: "needs synthetic parties (#4348)"\n', ""),
        expect_finding=True)

    run("the same service listed twice",
        SELF_TEST_RULES,
        SELF_TEST_CATALOG + "    - service: openbank-beta-service\n", expect_finding=True)

    run("an empty money-path list is fatal, not a pass",
        "money_path_services: []\n", SELF_TEST_CATALOG, expect_finding=True)

    run("a missing accountability block is a finding, not a silent pass",
        SELF_TEST_RULES,
        SELF_TEST_CATALOG.split("money_path_accountability:")[0], expect_finding=True)

    failed = [c for c in cases if not c[1]]
    for label, ok, detail in cases:
        print(f"  {'ok  ' if ok else 'FAIL'} {label}")
        if not ok and detail:
            for line in detail:
                print(f"         {line}")
    print(f"check-journey-coverage self-test: {len(cases) - len(failed)}/{len(cases)} passed")
    return 0 if not failed else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true", dest="selftest")
    args = parser.parse_args()

    if args.selftest:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    findings, fatal, subjects = check(root)
    gatelib.subjects(subjects, "money-path services")
    if fatal:
        print(f"::error::check-journey-coverage: {fatal}")
        return 1
    if not findings:
        print("check-journey-coverage: OK — every money-path service is accounted for.")
        return 0
    level = "error" if args.enforce else "warning"
    for finding in findings:
        print(f"::{level}::check-journey-coverage: {finding}")
    print(f"check-journey-coverage: {len(findings)} finding(s)")
    return 2 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
