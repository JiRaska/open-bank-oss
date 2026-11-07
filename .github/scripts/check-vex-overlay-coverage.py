#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Enumerate released components against VEX overlays, so a MISSING overlay is a finding.

THE DEFECT THIS EXISTS FOR
--------------------------
The VEX triage store is one file per released component, and nothing enumerates the components
against the files. The only VEX gate today, `vex-range-reasoning`, GLOBS the directory:

    VEX_GLOB = "openbank-libs/governance/vex/*.openvex.json"

A gate whose scope is the set of files that happen to exist cannot report a file that does not.
So a component with no overlay is not undispositioned-and-flagged, it is simply outside the
question -- and the gate reads as PASSING, never as UNCHECKED. That is the same shape as the
pact-drift gate that regenerated a hand-kept module list and then asserted `git diff` found
nothing, and the same shape as #7220 one floor up: absence is not a state the control can express.

It has already cost a real miss. `referral-service` has no overlay at all, so it silently missed
all 48 statements of the CVE-2025-14969 fan-out, and the omission surfaced only as a triage issue
seven months later (#6719). Measured on origin/main 2026-08-30: 60 released components, 56
overlays, FOUR components with none -- admin-ui, case-coordinator-agent, incentive-service and
referral-service. The issue that reported this named three of the four; the enumeration finds all
of them, which is the difference between a control and an audit.

WHAT THIS DOES
--------------
Derives the component set from `release-please-config.json` -- the same list that decides what is
a released component (CLAUDE.md rule 2) -- and requires each to resolve to an overlay under the
SAME two-candidate rule both consumers use (`<component>` then `<component>` minus the `openbank-`
prefix). Fails in both directions: a component with no overlay, and an overlay no component claims.

Known gaps live one-per-component in NO_OVERLAY_DIR (#14686), need an issue reference, and go stale in BOTH directions -- an
entry for a component that now has an overlay fails, and an entry for a component that is no longer
released fails. A new gap is red by default; only a human writing down why can make it green.

This deliberately does NOT invent dispositions for the four gaps. A VEX statement is a security
judgement about a specific CVE and a specific artifact; fabricating one to make a gate green would
be strictly worse than the gap it hides. #6717/#6719/#6720 are the live tickets that close them.

Usage:
    check-vex-overlay-coverage.py             # gate (exit 1 on a gap)
    check-vex-overlay-coverage.py --self-test # prove the gate can fail
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import gatelib

REPO = Path(__file__).resolve().parents[2]
VEX_DIR = Path("openbank-libs/governance/vex")
CONFIG = Path("release-please-config.json")

# Packages with a version.txt but no release-please `component` key. release-please.yml gates
# its evidence loop on `if comp and tag`, so these are skipped in SILENCE -- no SBOM, no SLSA
# provenance, no VEX document, no evidence manifest -- and the overlays written for them are
# unreachable. NOT fixed here on purpose: adding a `component` changes the release tag name and
# the changelog path, which is release-registration lockstep (CLAUDE.md rule 2) and the owner's
# call, not a gate author's. Declared so the two known cases are visible and any NEW one is red.
# Each entry names the overlay it strands, and a stale declaration fails in either direction.
NO_RELEASE_COMPONENT: dict[str, str] = {
    # openbank-campaign-service left this map in #7597. It HAD tags (componentless releases were
    # cut as bare `v0.40.0`..`v0.42.0`); the continuity tag `campaign-service-v0.42.0` sits on the
    # same commit as `v0.42.0`, so release-please's last-release lookup for the new component
    # starts from the current release. The bare tags stay as history and are no longer produced.
}

# Known gaps are DERIVED, not listed here: one file per component,
# `openbank-libs/governance/vex-no-overlay/<component>.txt`, whose text is the reason and must cite
# an issue (`#<n>`). The file name is the release-please component. This used to be a dict in this
# script that every new-service and overlay PR edited, which collided ~1.5x/day (#14686: #14588,
# #14521, #14517). Per-component files make unrelated PRs touch disjoint paths, while the rules
# are unchanged: a gap with no file is red, a file for a component that now HAS an overlay fails,
# a file for a non-released component fails, and an empty or issue-less reason fails.
# The stale check is what makes deleting the file part of the PR that writes the overlay.
NO_OVERLAY_DIR = Path("openbank-libs/governance/vex-no-overlay")
_ISSUE_REF = re.compile(r"#\d+")


def load_no_overlay_yet() -> tuple[dict[str, str], list[str]]:
    """(component -> reason, errors) read from NO_OVERLAY_DIR. Only `*.txt` is a declaration; any
    other file in the directory is an error, so a misnamed declaration cannot silently not count."""
    out: dict[str, str] = {}
    errors: list[str] = []
    d = REPO / NO_OVERLAY_DIR
    if not d.is_dir():
        return out, errors
    for p in sorted(d.iterdir()):
        if p.name.startswith(".") or p.is_dir():
            continue
        if p.suffix != ".txt":
            errors.append(f"{NO_OVERLAY_DIR}/{p.name} is not a `<component>.txt` declaration -- rename or remove it.")
            continue
        out[p.stem] = gatelib.read_text(p).strip()
    return out, errors


def released_components() -> tuple[list[str], list[str]]:
    """(component names, errors). The IDENTITY is release-please's `component`, not the
    directory name: it is what names the tag, what release-please.yml passes to
    build-release-evidence.sh as $COMPONENT, and therefore what the overlay filename must
    match. Reading the directory basename instead would report 60 phantom mismatches."""
    cfg = json.loads(gatelib.read_text(REPO / CONFIG))
    out, errors = [], []
    for path, entry in (cfg.get("packages") or {}).items():
        d = REPO / path.rstrip("/")
        # A released component is one with a version.txt (CLAUDE.md rule 2).
        if not (d / "version.txt").is_file():
            continue
        comp = (entry or {}).get("component")
        if not comp:
            # release-please.yml gates its whole evidence loop on `if comp and tag`, so a
            # package with no `component` key is skipped in silence: no SBOM, no SLSA
            # provenance, no VEX document and no evidence manifest are produced for its
            # release, and any overlay written for it reaches nothing. Nothing goes red --
            # the loop simply has one fewer iteration.
            if path not in NO_RELEASE_COMPONENT:
                errors.append(
                    f"{path} has a version.txt but no `component` in {CONFIG}. "
                    f"release-please.yml skips it (`if comp and tag`), so its release produces "
                    f"no SBOM, provenance, VEX or evidence manifest at all, and any VEX overlay "
                    f"written for it is unreachable. Add the key, or declare it in "
                    f"NO_RELEASE_COMPONENT with the overlay it strands."
                )
            continue
        out.append(comp)
    return sorted(out), errors


def resolve(component: str, overlays: set[str]) -> str | None:
    """How the consumers resolve an overlay. image-rescan.yml tries the tag/module name then the
    same name minus the `openbank-` prefix; build-release-evidence.sh uses $COMPONENT directly,
    which IS the prefix-free form for all 54 components that carry one today."""
    for cand in (component, component.removeprefix("openbank-")):
        if cand in overlays:
            return cand
    return None


def build(declared: dict[str, str] | None = None) -> tuple[list[str], list[str]]:
    components, errors = released_components()
    if declared is None:
        declared, derr = load_no_overlay_yet()
        errors += derr
    overlays = {p.name[: -len(".openvex.json")] for p in gatelib.glob(REPO / VEX_DIR, "*.openvex.json")}

    claimed: set[str] = set()
    for c in components:
        hit = resolve(c, overlays)
        if hit:
            claimed.add(hit)
            if c in declared:
                errors.append(
                    f"a no-overlay declaration exists for {c}, which HAS an overlay now "
                    f"({VEX_DIR}/{hit}.openvex.json) -- delete {NO_OVERLAY_DIR}/{c}.txt."
                )
        elif c not in declared:
            errors.append(
                f"{c} is a released component with NO VEX overlay. Every shared-artifact CVE "
                f"fan-out silently skips it and no gate can see that, because the only VEX gate "
                f"globs the files that exist. Add {VEX_DIR}/{c.removeprefix('openbank-')}"
                f".openvex.json, or declare the gap in {NO_OVERLAY_DIR}/{c}.txt (reason + issue reference)."
            )

    stranded = set(NO_RELEASE_COMPONENT.values())
    for path, overlay in NO_RELEASE_COMPONENT.items():
        entry = (json.loads(gatelib.read_text(REPO / CONFIG)).get("packages") or {}).get(path)
        if entry is None:
            errors.append(f"NO_RELEASE_COMPONENT declares {path}, not a registered package -- stale.")
        elif entry.get("component"):
            errors.append(
                f"NO_RELEASE_COMPONENT declares {path}, which HAS a `component` now -- remove the "
                f"entry (and check {overlay}.openvex.json resolves)."
            )
        elif overlay not in overlays:
            errors.append(
                f"NO_RELEASE_COMPONENT says {path} strands {overlay}.openvex.json, which does not "
                f"exist -- stale."
            )

    for name in sorted(overlays - claimed - stranded):
        errors.append(
            f"{VEX_DIR}/{name}.openvex.json is an ORPHAN -- no released component resolves to "
            f"it, so its verdicts reach no artifact. Remove it, or register the component."
        )

    for c, reason in sorted(declared.items()):
        if c not in components:
            errors.append(f"{NO_OVERLAY_DIR}/{c}.txt declares {c}, which is not a released component -- stale.")
        elif not str(reason).strip():
            errors.append(f"{NO_OVERLAY_DIR}/{c}.txt has no reason.")
        elif not _ISSUE_REF.search(reason):
            errors.append(f"{NO_OVERLAY_DIR}/{c}.txt cites no issue (`#<n>`) -- a gap needs a tracker.")

    return components, errors


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    if ap.parse_args().self_test:
        return self_test()

    components, errors = build()
    gatelib.subjects(len(components), "released components")
    if errors:
        print("VEX overlay coverage: FAIL")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"VEX overlay coverage: OK ({len(components)} released components)")
    return 0


def self_test() -> int:
    ok = True
    overlays = {"ledger-service", "openbank-odd-one"}

    # 1. the negative case: a component with no overlay MUST be reported
    if resolve("referral-service", overlays) is not None:
        print("SELF-TEST FAIL: resolved a component that has no overlay"); ok = False
    # 2. and one WITH an overlay must resolve, via either candidate
    if resolve("ledger-service", overlays) != "ledger-service":
        print("SELF-TEST FAIL: prefix-stripped candidate did not resolve"); ok = False
    if resolve("openbank-odd-one", overlays) != "openbank-odd-one":
        print("SELF-TEST FAIL: exact-name candidate did not resolve"); ok = False

    # 3. the real corpus must be clean, and must not be clean VACUOUSLY
    components, errors = build()
    if errors:
        print(f"SELF-TEST FAIL: live corpus reports {errors}"); ok = False
    if len(components) < 40:
        print(f"SELF-TEST FAIL: only {len(components)} components found -- derivation is broken")
        ok = False

    # 4. the per-component declaration form. Each case injects a declaration set and must
    #    produce the named finding; the first is the positive control (a legit gap is clean).
    real, _ = load_no_overlay_yet()
    gap = next(iter(real), None)
    if gap is None:
        print("SELF-TEST FAIL: no live declaration to use as a control"); ok = False
    else:
        cases = (
            # (label, declared set, needle or None for "must be clean of declaration findings")
            ("live gaps stay green", dict(real), None),
            ("undeclared gap is red", {k: v for k, v in real.items() if k != gap},
             f"{gap} is a released component with NO VEX overlay"),
            ("stale: component HAS overlay", {**real, "ledger-service": "#1 self-test"}, "HAS an overlay now"),
            ("stale: not a component", {**real, "not-a-component": "#1 self-test"}, "not a released component"),
            ("empty reason", {**real, gap: "  "}, "has no reason"),
            ("reason without issue", {**real, gap: "later"}, "cites no issue"),
        )
        for label, decl, needle in cases:
            gatelib.clear()
            _, errs = build(decl)
            if needle is None:
                if errs:
                    print(f"SELF-TEST FAIL: {label}: unexpected {errs}"); ok = False
            elif not any(needle in e for e in errs):
                print(f"SELF-TEST FAIL: {label}: not reported ({needle})"); ok = False
        # the loader itself: a misnamed file must be an error, and a file must map to its stem
        import tempfile
        global REPO
        saved_repo = REPO
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp) / NO_OVERLAY_DIR
            d.mkdir(parents=True)
            (d / "a-service.txt").write_text("#1 because\n")
            (d / "b-service.md").write_text("#2 wrong suffix\n")
            REPO = Path(tmp)
            try:
                got, lerrs = load_no_overlay_yet()
            finally:
                REPO = saved_repo
        if got != {"a-service": "#1 because"} or not any("not a `<component>.txt`" in e for e in lerrs):
            print(f"SELF-TEST FAIL: loader mis-derived {got} {lerrs}"); ok = False
        gatelib.clear()

    # 5. a stale NO_RELEASE_COMPONENT entry fails in both directions too. Without this the
    #    baseline could quietly outlive the condition it records, which is how a declared
    #    exception becomes permanent.
    #    The third case needs a package that genuinely lacks a `component`; the live config has
    #    none since #7597, so the self-test strips campaign-service's key from an in-memory copy.
    real_read_text = gatelib.read_text

    def _without_component(pkg: str):
        def read_text(path, errors="strict"):
            text = real_read_text(path, errors)
            if Path(path) != REPO / CONFIG:
                return text
            cfg = json.loads(text)
            cfg["packages"][pkg].pop("component", None)
            return json.dumps(cfg)
        return read_text

    for key, val, needle, strip in (
        ("openbank-not-a-package", "ghost", "not a registered package", False),
        ("openbank-ledger-service", "ledger-service", "HAS a `component` now", False),
        ("openbank-campaign-service", "no-such-overlay", "does not exist -- stale", True),
    ):
        saved = NO_RELEASE_COMPONENT.get(key)
        NO_RELEASE_COMPONENT[key] = val
        gatelib.clear()
        if strip:
            gatelib.read_text = _without_component(key)
        try:
            _, errs = build()
        finally:
            gatelib.read_text = real_read_text
        if not any(needle in e for e in errs):
            print(f"SELF-TEST FAIL: stale NO_RELEASE_COMPONENT {key} not reported ({needle})")
            ok = False
        if saved is None:
            del NO_RELEASE_COMPONENT[key]
        else:
            NO_RELEASE_COMPONENT[key] = saved
    gatelib.clear()

    print("SELF-TEST PASS" if ok else "SELF-TEST FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
