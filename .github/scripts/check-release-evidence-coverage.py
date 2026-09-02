#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every released package must resolve to a component, or its releases produce no evidence.

Issue #7597. `.github/workflows/release-please.yml`'s "Map released components -> tags" step
builds `released.tsv` from `release-please-config.json`'s per-package `component` field:

    comp = (cfg.get(p) or {}).get("component")
    ...
    if comp and tag:
        print("\t".join([p, comp, ver, tag, upurl]))

A package with NO `component` key produces no row in `released.tsv` — silently, no error. Every
downstream step (SBOM generation, cosign provenance attestation, VEX) reads only `released.tsv`,
so that package's release ships with NO supply-chain evidence at all, while release-please still
cuts the tag and the release, and everything else about the release LOOKS normal (green CI, a
GitHub Release, a tag). Nothing in the pipeline says "this one has no evidence" — the gap is
visible only by reading `released.tsv` on a specific run, or by diffing every `version.txt`
against `release-please-config.json`'s `packages` map by hand.

Measured today: `openbank-campaign-service` and `openbank-tax-reporting-service` both carry a
`version.txt` (so release-please treats them as released, versioned components) and both have a
`packages` entry with `"release-type": "simple"` and NO `component` key.

THIS SCRIPT DOES NOT FIX THAT. Adding a `component` key changes the release TAG NAME
(`include-component-in-tag: true`), and `version.txt` / `release-please-config.json` /
`.release-please-manifest.json` are a three-way lockstep for an ALREADY-RELEASED package
(ADR-0029 rule 2) — retagging an existing package's release history is a deliberate call for the
repo owner, not a drive-by fix bundled with a CI gate. What this script guards is the DURABLE half
of the issue: a package with a `version.txt` must resolve to a component that reaches
`released.tsv`, derived from the same config the workflow reads — never from a hand-kept list —
so a THIRD package cannot join the two above silently.

DERIVED, NOT LISTED: "released package" is discovered the same way
`check-test-intelligence-ecosystem.py` counts released packages — `openbank-*/version.txt` on
disk — and cross-referenced against `release-please-config.json`'s `packages` map, read fresh
every run. Nothing here re-encodes which packages exist.

    python3 .github/scripts/check-release-evidence-coverage.py --enforce
    python3 .github/scripts/check-release-evidence-coverage.py --self-test
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "release-please-config.json"

# Packages known TODAY to have a version.txt but no `component` in release-please-config.json, so
# their releases carry no SBOM/provenance/VEX (#7597). Adding a `component` key is a deliberate,
# separate decision for the repo owner (it changes the release tag name — ADR-0029 rule 2 lockstep
# for an already-released package). This baseline keeps the gate GREEN today and red the moment a
# THIRD package joins without either a component or a declared reason here.
#
# Kept here rather than in rules.yaml on purpose, same reasoning as check-pact-provider-replay.py:
# most gen-*opa-bundle*.sh scripts hash rules.yaml into every service's OPA bundle checksum, and
# this list belongs next to the code that reads it, not in a file that restamps ~40 unrelated
# artifacts when it changes.
KNOWN_UNCOVERED: dict[str, str] = {
    "openbank-campaign-service": "#7597 — release-please-config.json has no `component`; adding one "
    "changes the release tag name, a repo-owner decision, not a drive-by fix",
    "openbank-tax-reporting-service": "#7597 — same as openbank-campaign-service",
}

errors: list[str] = []


def fail(msg: str) -> None:
    errors.append(msg)


def discover_released_packages(root: Path) -> set[str]:
    """Package directories release-please treats as released: `openbank-*/version.txt` on disk.

    Same discovery `check-test-intelligence-ecosystem.py` uses to count released packages — never
    a hand-kept list, so a new service with a version.txt is picked up automatically.
    """
    return {f.parent.name for f in root.glob("openbank-*/version.txt") if f.is_file()}


def load_packages_config(config_path: Path) -> dict[str, dict]:
    data = json.loads(config_path.read_text(encoding="utf-8"))
    return data.get("packages") or {}


def check_coverage(
    released: set[str],
    packages_cfg: dict[str, dict],
    known_uncovered: dict[str, str],
) -> None:
    for pkg in sorted(released):
        cfg = packages_cfg.get(pkg)
        component = (cfg or {}).get("component")
        covered = bool(component)
        baselined = pkg in known_uncovered

        if covered and baselined:
            fail(
                f"{pkg} is listed in KNOWN_UNCOVERED but release-please-config.json now declares "
                f"component={component!r} — delete the stale entry, #7597 is what should empty "
                "this list"
            )
            continue
        if covered:
            continue
        if baselined:
            continue

        if cfg is None:
            fail(
                f"{pkg} has a version.txt (release-please treats it as released) but no entry at "
                "all in release-please-config.json's `packages` map — it cannot be released, let "
                "alone produce evidence. Register it, or if it is not meant to release, drop "
                "version.txt / .release-please-manifest.json instead."
            )
        else:
            fail(
                f"{pkg} has a version.txt and a release-please-config.json entry, but that entry "
                "has no `component` key — its releases produce NO row in released.tsv, so no "
                "SBOM, no cosign provenance, and no VEX ship with the release (#7597). Either add "
                "`component` (repo-owner decision: it changes the release tag name, ADR-0029 rule "
                "2 lockstep) or add it to KNOWN_UNCOVERED here with a reason."
            )


def check_baseline_is_live(
    released: set[str], packages_cfg: dict[str, dict], known_uncovered: dict[str, str]
) -> None:
    for pkg in sorted(known_uncovered):
        if pkg not in released:
            fail(
                f"KNOWN_UNCOVERED lists {pkg}, which has no version.txt anymore — it is not a "
                "released package, drop the stale entry"
            )
        elif pkg not in packages_cfg:
            fail(
                f"KNOWN_UNCOVERED lists {pkg}, which has no release-please-config.json entry at "
                "all — that is a worse problem than a missing component, see the 'no entry' error"
            )


def self_test() -> int:
    """Feed every check an input it MUST flag. A gate whose failure path never ran is unfalsified."""
    print("== self-test: each check must reject a known-bad input ==")
    results: list[tuple[str, bool]] = []

    def run(name: str, fn) -> None:
        global errors
        saved, errors = errors, []
        try:
            fn()
            caught = errors
        finally:
            errors = saved
        results.append((name, bool(caught)))
        print(f"  {name}: {'PASS (rejected)' if caught else 'FAIL (accepted bad input!)'}")
        if caught:
            print(f"      first message: {caught[0][:150]}")

    covered_cfg = {"openbank-a": {"component": "a"}}
    uncovered_cfg = {"openbank-b": {"release-type": "simple"}}
    missing_cfg: dict[str, dict] = {}

    # 1. A package with a component must NOT be flagged (the positive control) — checked via the
    # inverse of `run`: this call must produce NO errors, so assert that directly rather than with
    # the "must reject" helper.
    def clean(fn) -> bool:
        global errors
        saved, errors = errors, []
        try:
            fn()
            return not errors
        finally:
            errors = saved

    covered_clean = clean(lambda: check_coverage({"openbank-a"}, covered_cfg, {}))
    results.append(("covered package (has component) passes clean", covered_clean))
    print(f"  covered package (has component) passes clean: {'PASS' if covered_clean else 'FAIL'}")

    # 2. A package with no component and no baseline entry must be flagged.
    run(
        "uncovered package with NO baseline entry is flagged",
        lambda: check_coverage({"openbank-b"}, uncovered_cfg, {}),
    )

    # 3. A package with no component but a baseline entry is accepted (the #7597 shape today).
    baselined_clean = clean(lambda: check_coverage({"openbank-b"}, uncovered_cfg, {"openbank-b": "reason"}))
    results.append(("baselined uncovered package is accepted", baselined_clean))
    print(f"  baselined uncovered package is accepted: {'PASS' if baselined_clean else 'FAIL'}")

    # 4. A package present on disk (version.txt) but entirely absent from packages_cfg.
    run(
        "released package with no config entry at all is flagged",
        lambda: check_coverage({"openbank-c"}, missing_cfg, {}),
    )

    # 5. A baseline entry that has since gained a component must be flagged as stale.
    run(
        "baseline entry that is now covered is flagged as stale",
        lambda: check_coverage({"openbank-a"}, covered_cfg, {"openbank-a": "stale reason"}),
    )

    # 6. A baseline entry naming a package with no version.txt must be flagged as stale.
    run(
        "baseline entry for a package that no longer has version.txt is flagged",
        lambda: check_baseline_is_live(set(), {}, {"openbank-gone": "reason"}),
    )

    ok = all(flagged for _, flagged in results)
    print()
    print("self-test: ALL CHECKS CAN FAIL" if ok else "self-test: SOME CHECK IS UNFALSIFIED")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    released = discover_released_packages(ROOT)
    packages_cfg = load_packages_config(CONFIG)

    check_coverage(released, packages_cfg, KNOWN_UNCOVERED)
    check_baseline_is_live(released, packages_cfg, KNOWN_UNCOVERED)

    covered = sum(
        1
        for p in released
        if (packages_cfg.get(p) or {}).get("component")
    )
    gatelib.subjects(len(released), "released packages (version.txt on disk)")
    print(f"Released packages (version.txt on disk): {len(released)}")
    print(f"Resolve to a component that reaches released.tsv: {covered}")
    print(f"Declared uncovered (backlog, #7597): {len(KNOWN_UNCOVERED)}")
    print()

    if errors:
        for e in errors:
            print(f"::error::{e}")
        print(f"\nFAIL: {len(errors)} release-evidence-coverage problem(s).")
        return 1
    print("OK — every released package resolves to a component, or is a declared, still-accurate exception.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
