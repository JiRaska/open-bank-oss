#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Detector: does openbank-mcp-service's charter still declare the data controls its code
# unconditionally implements? (issue #2412, third residual)
#
# WHY THIS EXISTS
#   #2412's premise was "agents.yaml declares `data_scope.pii: masked` and nothing reads it".
#   PR #2481 fixed the leak the right way — masking is applied at McpToolRegistry.call, one
#   response-shaping seam every tool passes through, and it is UNCONDITIONAL. Deliberately so: a
#   switch that can turn a declared control off is exactly how the declaration became fiction in
#   the first place, and "the charter said masked, so we masked" is not a property you want a
#   runtime lookup to be able to falsify at 3am.
#
#   But unconditional code leaves the charter and the implementation as two unbound sources
#   pointing the same way by coincidence. Flip `mcp-anonymous`'s `pii` to `full` and NOTHING
#   changes: the code keeps masking, the charter now advertises a control the server does not
#   honour in the direction the reader expects, and no test anywhere goes red. The premise of the
#   issue survives its own fix, just inverted — before, the declaration over-promised; after, it
#   can silently under-promise.
#
#   So the binding is asserted HERE, at build time, rather than read at runtime. The charter is
#   pinned to what the code actually does. Changing the charter is then a decision someone has to
#   defend by also changing the code, which is the whole point.
#
# WHAT IT CHECKS
#   For each (charter id -> required declarations) below:
#     - the charter exists at all (a rename silently deletes the binding otherwise — see the
#       `mcp-anonymous` KDoc in agents.yaml: renaming it also defaults every tools/call to deny)
#     - each required key holds the required value
#
#   It does NOT try to prove "every charter's declared data_scope has a code path implementing
#   it" in general. That check cannot be written honestly — there is no mechanical link from
#   `pii: masked` to a masking implementation, and a gate that pretends otherwise would pass by
#   matching a comment, which is precisely the failure `check-pact-provider-replay.py`'s
#   prose-vs-artifact lesson warns about. One real binding beats a general one that proves nothing.
#
# Run:
#   python3 .github/scripts/check-mcp-charter-data-scope.py [--enforce]

import argparse
import pathlib
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    print("::warning::PyYAML not available — skipping charter data_scope check")
    sys.exit(0)

REPO = pathlib.Path(__file__).resolve().parents[2]
AGENTS_YAML = REPO / "openbank-libs" / "governance" / "agents.yaml"

# charter id -> {data_scope key: required value, ...}, with the code that makes it true.
REQUIRED = {
    "mcp-anonymous": {
        "pii": (
            "masked",
            "McpToolRegistry.call applies McpPiiMasker to EVERY tool result unconditionally "
            "(openbank-mcp-service/src/main/kotlin/com/openbank/mcp/application/McpToolRegistry.kt). "
            "The code cannot return unmasked PII, so the charter must not advertise that it can. "
            "To change this declaration, change that seam first.",
        ),
    },
}


def evaluate(charters: dict) -> list[str]:
    """The comparison, separated from file loading so a self-test can drive it.

    Inline in main() it could only be exercised by editing the real agents.yaml, which is how
    a branch stays unfalsified — and this one binds a CHARTER CLAIM to a code seam, so a
    silent failure means the charter advertises a control the code does not implement.
    """
    findings: list[str] = []
    for charter_id, requirements in REQUIRED.items():
        charter = charters.get(charter_id)
        if charter is None:
            findings.append(
                f"charter '{charter_id}' is absent from agents.yaml — it was renamed or removed, "
                f"which also silently unbinds it from the code that implements its declarations "
                f"(and, for mcp-anonymous, defaults every tools/call to deny). Update this script "
                f"in the same change."
            )
            continue
        scope = charter.get("data_scope") or {}
        for key, (expected, why) in requirements.items():
            actual = scope.get(key)
            if actual != expected:
                findings.append(
                    f"charter '{charter_id}': data_scope.{key} is {actual!r}, expected {expected!r}. {why}"
                )
    return findings


def self_test() -> int:
    """Falsify the comparison against fixture charters."""
    fails: list[str] = []

    def case(label, charters, want_findings):
        got = evaluate(charters)
        if bool(got) != want_findings:
            fails.append(f"{label}: expected findings={want_findings}, got {got}")

    cid = next(iter(REQUIRED))
    key, (expected, _why) = next(iter(REQUIRED[cid].items()))

    # The only clean shape: the charter declares exactly what the code enforces.
    case("a matching declaration is clean", {cid: {"data_scope": {key: expected}}}, False)
    # THE DEFECT: the charter advertises a weaker control than the code implements.
    case("a differing declaration is FLAGGED", {cid: {"data_scope": {key: "unmasked"}}}, True)
    # ABSENCE of the key is not agreement — a missing declaration must not read as the right one.
    case("a missing data_scope key is FLAGGED", {cid: {"data_scope": {}}}, True)
    case("a missing data_scope block is FLAGGED", {cid: {}}, True)
    # The charter disappearing entirely is the quiet case: a rename unbinds the claim from the
    # code, and nothing else notices.
    case("an absent charter is FLAGGED", {}, True)
    case("an empty charter map is FLAGGED", {"someone-else": {}}, True)

    # And the real agents.yaml must still parse and still contain the charter — a fixture-only
    # self-test cannot tell that this script's REQUIRED keys still refer to anything.
    if AGENTS_YAML.exists():
        live = yaml.safe_load(AGENTS_YAML.read_text()) or {}
        ids = {a.get("id") for a in (live.get("agents") or [])}
        for cid_ in REQUIRED:
            if cid_ not in ids:
                fails.append(f"REQUIRED names charter {cid_!r}, which agents.yaml no longer declares")
    else:
        fails.append("agents.yaml not found from this script's location")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: mcp charter data_scope binding is falsifiable (6 cases + a live read)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enforce", action="store_true", help="fail the build instead of warning")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if not AGENTS_YAML.exists():
        print(f"::error::{AGENTS_YAML} not found — this check cannot pass vacuously.")
        return 1

    doc = yaml.safe_load(AGENTS_YAML.read_text())
    charters = {a.get("id"): a for a in (doc or {}).get("agents", []) or []}

    findings = evaluate(charters)

    checked = sum(len(v) for v in REQUIRED.values())
    if not findings:
        print(
            f"mcp charter data_scope: {checked} declaration(s) across {len(REQUIRED)} charter(s) "
            f"match the controls the code unconditionally implements."
        )
        return 0

    level = "error" if args.enforce else "warning"
    for f in findings:
        print(f"::{level}::check-mcp-charter-data-scope: {f}")
    if not args.enforce:
        print("check-mcp-charter-data-scope: advisory — no --enforce, so this is a warning.")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
