#!/usr/bin/env python3
"""Fleet-scoped VEX + overlay conflict/promotion gate (issues #6988, #7987).

WHY THIS EXISTS
  VEX statements here are written per component
  (`openbank-libs/governance/vex/<component>.openvex.json`). That model breaks
  on the two most common cases:

  1. A CVE in a SHARED artifact (base image, libs-runtime, the platform BOM).
     One CVE then costs one hand-written statement per component — measured at
     42-336 statements for a single shared-artifact CVE (#6988) — and the copies
     drift: after the opentelemetry platform bump landed, 49 of 56 overlays
     still said "blocked on the platform bump" (#7987). N hand copies of one
     fact are N places for the fact to rot.

  2. A CVE in ONE component's own dependency. Fine per component — untouched
     by this change.

  The fix is a fleet scope: `openbank-libs/governance/vex/_fleet.vex.json`
  carries statements whose products are fleet-wide purls
  (`pkg:generic/openbank-fleet/...`). build-release-evidence.sh folds the
  expansion into every release VEX document (see --expand), so downstream
  consumers see the same document as today; only the AUTHORING is scoped.

WHAT THIS GATE ENFORCES
  a. `_fleet.vex.json` is a valid OpenVEX doc and every statement product
     is a fleet purl (a component purl in the fleet doc defeats the purpose).
  b. CONFLICT (hard fail): the same CVE appears in the fleet doc AND in a
     component overlay with a different status — two answers to one question.
     (Same status in both = redundant; flagged as a promotion hint, not red.)
  c. PROMOTION CANDIDATES (warning): the same CVE + identical status appears in
     >= PROMOTE_THRESHOLD component overlays — that is a fleet fact being
     maintained N times; promote it to the fleet doc and delete the copies.
  d. STALE-BLOCKED (warning, the #7987 shape): an overlay statement still
     `under_investigation` whose detail/impact text says it is "blocked on"
     a bump/bump PR. These rot silently; list them so vex-triage can re-check.

Usage:
  python3 .github/scripts/check-vex-fleet-scope.py            # gate
  python3 .github/scripts/check-vex-fleet-scope.py --expand   # merged view (JSON)
  python3 .github/scripts/check-vex-fleet-scope.py --self-test
"""
import glob
import json
import os
import re
import sys

VEX_DIR = "openbank-libs/governance/vex"
# `_fleet.vex.json` — deliberately NOT `*.openvex.json`: the vex-overlay-coverage
# gate globs that suffix per released component, and a fleet-scope document is
# reachable through ALL of them, not orphaned from one.
FLEET_DOC = os.path.join(VEX_DIR, "_fleet.vex.json")
FLEET_PURL_PREFIX = "pkg:generic/openbank-fleet/"
PROMOTE_THRESHOLD = 5
STATUSES = {"not_affected", "affected", "fixed", "under_investigation"}
BLOCKED_RE = re.compile(r"blocked on .*bump", re.IGNORECASE)


def load_doc(path):
    with open(path) as f:
        return json.load(f)


def validate_doc(doc, path):
    errs = []
    for key in ("@context", "@id", "version", "statements"):
        if key not in doc:
            errs.append(f"{path}: missing OpenVEX key {key!r}")
    for i, st in enumerate(doc.get("statements", [])):
        vuln = (st.get("vulnerability") or {}).get("name")
        if not vuln:
            errs.append(f"{path}: statement {i} has no vulnerability.name")
        if st.get("status") not in STATUSES:
            errs.append(f"{path}: statement {i} ({vuln}): unknown status "
                        f"{st.get('status')!r}")
    return errs


def overlay_statements(path):
    """CVE -> statement for one overlay file."""
    return {(s.get("vulnerability") or {}).get("name"): s
            for s in load_doc(path).get("statements", [])
            if (s.get("vulnerability") or {}).get("name")}


def evaluate(fleet, overlays):
    """Pure core. fleet: fleet doc dict; overlays: {path: {cve: statement}}.
    Returns (failures, warnings)."""
    failures, warnings = [], []

    failures += validate_doc(fleet, FLEET_DOC)
    fleet_map = {}
    for st in fleet.get("statements", []):
        cve = (st.get("vulnerability") or {}).get("name")
        fleet_map[cve] = st
        for p in st.get("products", []):
            purl = p.get("@id", "") if isinstance(p, dict) else str(p)
            if not purl.startswith(FLEET_PURL_PREFIX):
                failures.append(f"{FLEET_DOC}: {cve}: product {purl!r} is not a "
                                f"fleet purl ({FLEET_PURL_PREFIX}...) — a "
                                f"component-scoped statement belongs in that "
                                f"component's overlay")

    # Per-CVE aggregation across overlays.
    seen = {}  # cve -> [(path, statement)]
    for path, stmts in sorted(overlays.items()):
        failures += validate_doc({"@context": 1, "@id": 1, "version": 1,
                                  "statements": list(stmts.values())}, path)
        for cve, st in stmts.items():
            seen.setdefault(cve, []).append((path, st))

    for cve, occ in sorted(seen.items()):
        fst = fleet_map.get(cve)
        for path, st in occ:
            detail = " ".join(str(st.get(k, "")) for k in
                              ("impact_statement", "detail", "action_statement"))
            if st.get("status") == "under_investigation" and BLOCKED_RE.search(detail):
                warnings.append(f"stale-blocked (#7987 shape): {cve} in "
                                f"{os.path.basename(path)} is under_investigation "
                                f"with a 'blocked on ... bump' rationale — verify "
                                f"the bump has not already landed")
            if fst is not None:
                if st.get("status") != fst.get("status"):
                    failures.append(
                        f"CONFLICT: {cve} is {fst.get('status')!r} in the fleet "
                        f"doc but {st.get('status')!r} in "
                        f"{os.path.basename(path)} — one CVE, two answers; the "
                        f"overlay must defer to the fleet scope or the fleet "
                        f"statement must be narrowed")
                else:
                    warnings.append(f"redundant: {cve} in {os.path.basename(path)} "
                                    f"duplicates the fleet statement "
                                    f"({st.get('status')}) — delete the copy")
        if fst is None and len(occ) >= PROMOTE_THRESHOLD:
            statuses = {st.get("status") for _, st in occ}
            if len(statuses) == 1:
                warnings.append(
                    f"promotion candidate (#6988 shape): {cve} has the IDENTICAL "
                    f"status {statuses.pop()!r} in {len(occ)} component overlays — "
                    f"maintain it once in _fleet.vex.json instead")

    return failures, warnings


def load_all(vex_dir=VEX_DIR):
    fleet = load_doc(os.path.join(vex_dir, os.path.basename(FLEET_DOC))) \
        if os.path.exists(os.path.join(vex_dir, os.path.basename(FLEET_DOC))) \
        else {"statements": []}
    overlays = {p: overlay_statements(p)
                for p in glob.glob(os.path.join(vex_dir, "*.openvex.json"))
                if not os.path.basename(p).startswith("_")}
    return fleet, overlays


def expand(fleet, overlays):
    """Merged per-component view for release-evidence folding."""
    out = {}
    for path, stmts in sorted(overlays.items()):
        comp = os.path.basename(path).replace(".openvex.json", "")
        merged = {**fleet_map_as_cve(fleet), **stmts}
        out[comp] = sorted(merged)
    return out


def fleet_map_as_cve(fleet):
    return {(s.get("vulnerability") or {}).get("name"): True
            for s in fleet.get("statements", [])}


def self_test():
    fleet = {"@context": "c", "@id": "f", "version": 1, "statements": [
        {"vulnerability": {"name": "CVE-1"}, "status": "not_affected",
         "justification": "vulnerable_code_not_present",
         "products": [{"@id": FLEET_PURL_PREFIX + "base-image@*"}]}]}
    good = {"a": {"CVE-2": {"vulnerability": {"name": "CVE-2"},
                            "status": "fixed"}}}
    conflict = {"a": {"CVE-1": {"vulnerability": {"name": "CVE-1"},
                                "status": "affected"}}}
    blocked = {"a": {"CVE-9": {"vulnerability": {"name": "CVE-9"},
                               "status": "under_investigation",
                               "impact_statement": "blocked on opentelemetry bump"}}}
    promoted = {f"c{i}": {"CVE-7": {"vulnerability": {"name": "CVE-7"},
                                    "status": "not_affected"}}
                for i in range(6)}
    cases = [
        ("clean fleet passes", fleet, good, False),
        ("fleet/overlay status conflict FAILS", fleet, conflict, True),
        ("component purl in fleet doc FAILS",
         {**fleet, "statements": [{**fleet["statements"][0],
                                   "products": [{"@id": "pkg:generic/ledger@1.0"}]}]},
         good, True),
        ("stale-blocked warns but does not fail", fleet, blocked, False),
        ("promotion candidate warns but does not fail", fleet, promoted, False),
    ]
    bad = 0
    for name, f, o, expect_fail in cases:
        failures, warns = evaluate(f, o)
        if bool(failures) != expect_fail:
            print(f"SELF-TEST FAIL: {name}: failures={failures}")
            bad += 1
        else:
            print(f"self-test ok: {name} (warnings={len(warns)})")
    if bad:
        sys.exit(f"self-test: {bad} case(s) wrong")
    print("self-test: all cases behaved")


def main():
    if "--self-test" in sys.argv:
        self_test()
        return
    fleet, overlays = load_all()
    if "--expand" in sys.argv:
        print(json.dumps(expand(fleet, overlays), indent=1))
        return
    failures, warnings = evaluate(fleet, overlays)
    for w in warnings:
        print(f"::warning::{w}")
    if failures:
        for f in failures:
            print(f"::error::{f}")
        sys.exit(1)
    print(f"vex fleet scope ok: {len(overlays)} overlays, "
          f"{len(fleet.get('statements', []))} fleet statements, "
          f"{len(warnings)} advisory warnings")


if __name__ == "__main__":
    main()
