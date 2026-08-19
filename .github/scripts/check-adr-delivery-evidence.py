#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# ADR delivery-status evidence gate (ADR-0253, ADR-0029).
#
# WHY THIS EXISTS
#   ADR-0253 requires every control that claims to do work to expose a countable
#   artifact proving it did, and to be covered by a reader that fails when the
#   count is zero. The ADR registry is itself such a control: `delivery-status`
#   claims what is and is not built, `DIGEST.md` is what a reviewer, an auditor
#   or an agent reads instead of the 1.6 MB fleet, and nothing ever compared the
#   claim against the estate.
#
#   It drifted, twice, silently. ADR-0179 and ADR-0181 carried
#   `delivery-status: planned` while their code was live and versioned (#5602).
#   ADR-0237 carried `planned` while its own adoption gate
#   (`scheduler-liveness-adoption`) had been ENFORCED in `.github/gates/gates.yaml`
#   for weeks, 63 registration sites were live and its PrometheusRule was
#   deployed. Neither was visible to any check: `check-adr-registry.sh` validates
#   the schema, not the truth of the value, and `planned` is a perfectly
#   well-formed lie.
#
#   This gate reads one specific, high-confidence piece of evidence in the
#   opposite direction to the usual one — from the artifact to the ADR.
#
# WHAT IT CHECKS
#   An ADR whose `delivery-status` is `planned` may not be cited by an ENFORCED
#   gate in `.github/gates/gates.yaml`. An enforced gate is code that runs on
#   every PR and blocks a merge; a mechanism that has reached that state is not
#   "planned" under any reading of the word.
#
#   Advisory gates are deliberately NOT evidence: ADR-0144 lets a gate land
#   advisory ahead of the decision it will eventually enforce, so an advisory
#   citation is compatible with `planned`.
#
#   A gate may legitimately cite an ADR it PREPARES FOR rather than implements —
#   `ai-act-high-risk-inventory-vs-code` exists precisely to fire on the day
#   ADR-0142's credit engine is wired, and is meaningless until then. Those are
#   declared in EXCEPTIONS below, one reason each.
#
#   The EXCEPTIONS list is checked BOTH WAYS (the kafka-dotted-keys ratchet
#   shape): a new violation fails, and an exception that no longer applies —
#   because the ADR moved off `planned`, or because no enforced gate cites it
#   any more — is reported too. A list that can only shrink cannot rot in either
#   direction, and a stale exemption reads as a discharged obligation, which is
#   worse than no exemption at all.
#
# WHAT IT DELIBERATELY DOES NOT CHECK
#   Not "does a file this ADR mentions exist". Measured over the 51 planned ADRs:
#   15 of them cite an existing repo path, almost always as PRIOR ART, and the
#   two ADRs whose drift motivated this gate cite none of their own artifacts at
#   all. A probe that would not have caught the defect it was written for is
#   decoration.
#
# Run:  python3 .github/scripts/check-adr-delivery-evidence.py --root .
#       python3 .github/scripts/check-adr-delivery-evidence.py --self-test

import argparse
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib

ADR_RE = re.compile(r"ADR-(\d{4})")
PLACEHOLDER_RE = re.compile(r"\b(tbd|todo|fixme|xxx|reason|placeholder)\b", re.IGNORECASE)
MIN_REASON = 40

# ADR number -> why an enforced gate may cite it while it is still `planned`.
# The gate must be about the ADR's FUTURE subject, not its delivered mechanism.
EXCEPTIONS = {
    "0142": (
        "ai-act-high-risk-inventory-vs-code fires on the day model inference is wired into the "
        "credit path; it exists to catch ADR-0142 being built without being declared, so it is "
        "evidence about the absence of the engine, not about its delivery."
    ),
    "0251": (
        "agent-review-*-falsifiable gate the proof-of-review scripts, not the review programme: "
        "GitHub Models was retired and no agent review has ever run, so the enforced half is the "
        "falsifiability of the evidence ADR-0251 would produce once a reviewer exists."
    ),
}


def load_gates(root):
    """Return [(gate_id, mode, blob)] without a YAML dependency.

    gates.yaml is a flat list of mappings under `gates:`; the fields this gate
    reads (`id`, `mode`, `name`) are always scalars on their own line. Parsing
    them directly keeps the check runnable with a bare python3, which every
    other gate in this repo can also assume.
    """
    text = (root / ".github/gates/gates.yaml").read_text(encoding="utf-8")
    gates, cur = [], None
    for line in text.splitlines():
        m = re.match(r"^  - id:\s*(\S+)", line)
        if m:
            if cur:
                gates.append(cur)
            cur = {"id": m.group(1), "mode": None, "blob": line}
            continue
        if cur is None:
            continue
        if re.match(r"^\S", line):        # left the `gates:` block entirely
            gates.append(cur)
            cur = None
            continue
        if re.match(r"^\s*#", line):
            # A comment block sits BETWEEN two gates and is indented like the
            # body of the previous one. Attributing it to that gate cites ADRs
            # the gate has nothing to do with (`shellcheck` "citing" ADR-0237).
            continue
        cur["blob"] += "\n" + line
        m = re.match(r"^    mode:\s*(\S+)", line)
        if m:
            cur["mode"] = m.group(1)
    if cur:
        gates.append(cur)
    return gates


def enforced_citations(root):
    """ADR number -> sorted gate ids of ENFORCED gates citing it."""
    out = {}
    for g in load_gates(root):
        if g["mode"] != "enforced":
            continue
        for n in set(ADR_RE.findall(g["blob"])):
            out.setdefault(n, set()).add(g["id"])
    return {n: sorted(v) for n, v in out.items()}


def planned_adrs(root):
    index = json.loads((root / "docs/adr/index.json").read_text(encoding="utf-8"))
    return {a["number"]: a for a in index["adrs"] if a["delivery_status"] == "planned"}


def check(root):
    root = pathlib.Path(root)
    cited = enforced_citations(root)
    planned = planned_adrs(root)
    errors, stale = [], []

    for number, adr in sorted(planned.items()):
        gates = cited.get(number)
        if not gates:
            continue
        reason = EXCEPTIONS.get(number)
        if reason is None:
            errors.append(
                f"ADR-{number} is delivery-status: planned, but the ENFORCED gate(s) "
                f"{', '.join(gates)} cite it. An enforced gate blocks every PR — the "
                f"mechanism is built. Set the real delivery-status (with a `followup:` "
                f"if partial), or declare an exception in EXCEPTIONS with a reason.\n"
                f"           {adr['file']}"
            )
        elif len(reason) < MIN_REASON or PLACEHOLDER_RE.search(reason):
            errors.append(
                f"ADR-{number} exception reason is a placeholder or too short "
                f"(< {MIN_REASON} chars). An unfilled exemption reads as a discharged "
                f"obligation."
            )

    for number, reason in sorted(EXCEPTIONS.items()):
        if number not in planned:
            stale.append(
                f"ADR-{number} is no longer delivery-status: planned — remove its EXCEPTIONS entry."
            )
        elif not cited.get(number):
            stale.append(
                f"ADR-{number} is no longer cited by any ENFORCED gate — remove its EXCEPTIONS entry."
            )

    return errors, stale


def self_test():
    """Negative cases first: a probe that cannot fail proves nothing.

    Each case removes the protection (or feeds the checker the thing it must
    reject) and asserts the checker goes red.
    """
    import tempfile
    import textwrap

    def build(tmp, mode, number, status):
        root = pathlib.Path(tmp)
        (root / ".github/gates").mkdir(parents=True)
        (root / "docs/adr").mkdir(parents=True)
        (root / ".github/gates/gates.yaml").write_text(textwrap.dedent(f"""\
            gates:
              - id: some-gate
                name: "a mechanism (ADR-{number})"
                group: gitops
                mode: {mode}
                run: |
                  true
            """), encoding="utf-8")
        (root / "docs/adr/index.json").write_text(json.dumps({"adrs": [
            {"number": number, "file": f"{number}-x.md", "delivery_status": status},
        ]}), encoding="utf-8")
        return root

    failures = []

    # 1. MUST FAIL: planned ADR cited by an enforced gate, not declared.
    with tempfile.TemporaryDirectory() as tmp:
        errors, _ = check(build(tmp, "enforced", "9001", "planned"))
        if not errors:
            failures.append("case 1: an enforced gate citing a `planned` ADR was not reported")

    # 2. MUST PASS: the same citation from an ADVISORY gate (ADR-0144 lets a gate
    #    land ahead of the decision it will enforce).
    with tempfile.TemporaryDirectory() as tmp:
        errors, _ = check(build(tmp, "advisory", "9001", "planned"))
        if errors:
            failures.append(f"case 2: an advisory citation was wrongly reported: {errors}")

    # 3. MUST PASS: enforced citation of an ADR that is honestly `partial`.
    with tempfile.TemporaryDirectory() as tmp:
        errors, _ = check(build(tmp, "enforced", "9001", "partial"))
        if errors:
            failures.append(f"case 3: a non-planned ADR was wrongly reported: {errors}")

    # 4. MUST REPORT STALE: a declared exception whose ADR is no longer planned.
    with tempfile.TemporaryDirectory() as tmp:
        root = build(tmp, "enforced", "9001", "shipped")
        saved = dict(EXCEPTIONS)
        EXCEPTIONS.clear()
        EXCEPTIONS["9001"] = "x" * (MIN_REASON + 1)
        _, stale = check(root)
        EXCEPTIONS.clear()
        EXCEPTIONS.update(saved)
        if not stale:
            failures.append("case 4: an exception for an already-delivered ADR was not reported stale")

    # 5. MUST FAIL: an exception with a placeholder reason.
    with tempfile.TemporaryDirectory() as tmp:
        root = build(tmp, "enforced", "9001", "planned")
        saved = dict(EXCEPTIONS)
        EXCEPTIONS.clear()
        EXCEPTIONS["9001"] = "TODO — fill this in later, it is fine for now really"
        errors, _ = check(root)
        EXCEPTIONS.clear()
        EXCEPTIONS.update(saved)
        if not errors:
            failures.append("case 5: a placeholder exception reason was accepted")

    # 6. The real tree must parse: gates.yaml yields enforced gates and the index
    #    yields planned ADRs. A checker that silently reads nothing is the exact
    #    green-because-it-never-reached-its-subject defect it exists to prevent.
    here = pathlib.Path(__file__).resolve().parents[2]
    if (here / ".github/gates/gates.yaml").exists():
        gates = load_gates(here)
        if len([g for g in gates if g["mode"] == "enforced"]) < 20:
            failures.append(f"case 6: parsed only {len(gates)} gates from the real gates.yaml")
        if not planned_adrs(here):
            failures.append("case 6: parsed no planned ADRs from the real index.json")

    for f in failures:
        print(f"SELF-TEST FAILED: {f}", file=sys.stderr)
    print("self-test: 6 cases, "
          f"{'FAILED' if failures else 'all passed (3 negative cases go red as required)'}")
    return 1 if failures else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    errors, stale = check(args.root)
    # Declare the corpus unconditionally, failure path included: a gate that examines
    # nothing passes everything, and this one's corpus is the enforced-gate set.
    gatelib.subjects(
        len([g for g in load_gates(pathlib.Path(args.root)) if g["mode"] == "enforced"]),
        "enforced gates in the manifest")
    for e in errors:
        print(f"ERROR:   {e}", file=sys.stderr)
    for s in stale:
        print(f"STALE:   {s}", file=sys.stderr)
    if errors or stale:
        print(f"\nADR delivery-status evidence: {len(errors)} violation(s), "
              f"{len(stale)} stale exception(s).", file=sys.stderr)
        return 1
    print("ADR delivery-status evidence: OK — no `planned` ADR is backed by an enforced gate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
