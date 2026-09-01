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

Known gaps live in NO_OVERLAY_YET, need an issue reference, and go stale in BOTH directions -- an
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
    "openbank-campaign-service": "campaign-service",
    "openbank-tax-reporting-service": "tax-reporting-service",
}

# component -> issue that tracks writing its overlay. Reason required; stale either direction.
NO_OVERLAY_YET: dict[str, str] = {
    # case-coordinator-agent, incentive-service and referral-service were removed here when
    # #7981 wrote their overlays. Their entries went stale the moment that PR merged, and this
    # gate reported it in the direction that matters -- a declaration outliving its subject --
    # which turned main red for every PR until this landed. That is the check working, not a
    # defect in it: the failure was leaving the baseline out of the PR that made it obsolete.
    "admin-ui": "#6719 - released component, no triage overlay written yet. Node/Next.js, so none "
                "of the JVM dependency evidence used for the other three reaches it and there is "
                "no npm triage material in the repository to write a disposition from.",
}


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


def build() -> tuple[list[str], list[str]]:
    components, errors = released_components()
    overlays = {p.name[: -len(".openvex.json")] for p in gatelib.glob(REPO / VEX_DIR, "*.openvex.json")}

    claimed: set[str] = set()
    for c in components:
        hit = resolve(c, overlays)
        if hit:
            claimed.add(hit)
            if c in NO_OVERLAY_YET:
                errors.append(
                    f"NO_OVERLAY_YET declares {c}, which HAS an overlay now "
                    f"({VEX_DIR}/{hit}.openvex.json) -- remove the entry."
                )
        elif c not in NO_OVERLAY_YET:
            errors.append(
                f"{c} is a released component with NO VEX overlay. Every shared-artifact CVE "
                f"fan-out silently skips it and no gate can see that, because the only VEX gate "
                f"globs the files that exist. Add {VEX_DIR}/{c.removeprefix('openbank-')}"
                f".openvex.json, or declare it in NO_OVERLAY_YET with an issue reference."
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

    for c, reason in NO_OVERLAY_YET.items():
        if c not in components:
            errors.append(f"NO_OVERLAY_YET declares {c}, which is not a released component -- stale.")
        elif not str(reason).strip():
            errors.append(f"NO_OVERLAY_YET entry {c} has no reason.")

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

    # 4. a stale NO_OVERLAY_YET entry fails in BOTH directions
    for key, needle in (
        ("ledger-service", "HAS an overlay now"),
        ("not-a-component", "not a released component"),
    ):
        NO_OVERLAY_YET[key] = "self-test"
        gatelib.clear()
        _, errs = build()
        if not any(needle in e for e in errs):
            print(f"SELF-TEST FAIL: stale entry {key} not reported ({needle})"); ok = False
        del NO_OVERLAY_YET[key]
    gatelib.clear()

    # 5. a stale NO_RELEASE_COMPONENT entry fails in both directions too. Without this the
    #    baseline could quietly outlive the condition it records, which is how a declared
    #    exception becomes permanent.
    for key, val, needle in (
        ("openbank-not-a-package", "ghost", "not a registered package"),
        ("openbank-ledger-service", "ledger-service", "HAS a `component` now"),
        ("openbank-campaign-service", "no-such-overlay", "does not exist -- stale"),
    ):
        saved = NO_RELEASE_COMPONENT.get(key)
        NO_RELEASE_COMPONENT[key] = val
        gatelib.clear()
        _, errs = build()
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
