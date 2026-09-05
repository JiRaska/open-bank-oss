#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Ratchet the gate estate's OWN observability declarations (ADR-0255 follow-up).

WHY THIS EXISTS
----------------
`run-gates.py` already supports two per-gate self-observations, and both are optional:

  budget_seconds  a wall-time ceiling. Without it a gate can slow down without limit and
                  nothing objects. gates.yaml's own header records exactly this happening
                  once already: the pre-shard `validate` job went 0.7 -> 2.4 min over four
                  weeks, one gate at a time, and the only reason anyone noticed was that a
                  human eventually looked. Sharding changed the shape of that drift, not
                  its detectability.

  min_subjects    a floor on how many things the gate actually examined. Without it, a gate
                  whose scope silently collapses to zero subjects reports `ok` -- the repo's
                  single most-repeated defect class (#4339 and the whole "a gate green about
                  work it never did" family in CLAUDE.md).

Measured on the live ci_gate_runs warehouse (ADR-0255 Tier 2, 1.13M rows, 2026-08-10 ..
2026-09-02): of 198 gate ids seen in the last 7 days, 92 emit a subject count and 106 do
not, while only 15 of 153 manifest entries declare `budget_seconds`. So the two things
run-gates.py can enforce per gate are, today, declared for a small minority of gates -- the
enforcement exists, the declarations do not.

WHAT THIS GATE DOES
--------------------
It does NOT demand a declaration on every gate at once; that would be a 138-entry edit with
no way to pick good numbers for the gates nobody has measured. It RATCHETS: the number of
gates missing each declaration may fall or hold, never rise. A new gate therefore arrives
with both declarations or it is red, and the existing tail can be paid down entry by entry
(`derive-gate-observability-budgets.py` proposes numbers from the warehouse).

The floor ratchet is scoped to gates OBSERVED emitting a subject count, because "should this
gate have a min_subjects" is not answerable from the manifest -- a gate that examines a
single fixed file has no meaningful subject count and must not be forced to invent one. The
observed set is carried in the baseline with its provenance, and only shrinks in scope when
a gate stops emitting (which is itself visible in the diff).

Both directions are checked. A baseline that has drifted BELOW the real count is stale and
is reported too, so paying an entry down without regenerating cannot quietly re-open room.

USAGE
    check-gate-observability-declarations.py [--enforce]
    check-gate-observability-declarations.py --self-test
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import tempfile

try:
    import yaml
except ImportError:  # pragma: no cover - PyYAML is a run-gates.py prerequisite
    print("::error::PyYAML is required", file=sys.stderr)
    raise

ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = ROOT / ".github" / "gates" / "gates.yaml"
BASELINE = ROOT / ".github" / "gates" / "observability-baseline.json"


def load(manifest_path: pathlib.Path, baseline_path: pathlib.Path):
    gates = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))["gates"]
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    return gates, baseline


def evaluate(gates, baseline):
    """Return (findings, counts). findings is a list of human-readable strings."""
    emitting = set(baseline.get("subject_emitting_gate_ids", []))
    ids = [g["id"] for g in gates]

    missing_budget = sorted(g["id"] for g in gates if g.get("budget_seconds") is None)
    # Only gates OBSERVED emitting a subject count can be asked for a floor.
    missing_floor = sorted(
        g["id"] for g in gates
        if g.get("min_subjects") is None and g["id"] in emitting
    )

    counts = {
        "gates_examined": len(ids),
        "missing_budget_seconds": len(missing_budget),
        "missing_min_subjects": len(missing_floor),
    }
    allowed = baseline.get("allowed", {})
    findings = []

    for key, actual, names in (
        ("missing_budget_seconds", missing_budget, missing_budget),
        ("missing_min_subjects", missing_floor, missing_floor),
    ):
        cap = allowed.get(key)
        if cap is None:
            findings.append(f"baseline has no `allowed.{key}` -- cannot ratchet what is not declared")
            continue
        n = len(actual)
        if n > cap:
            new = [x for x in names if x not in set(baseline.get("known", {}).get(key, []))]
            findings.append(
                f"{key}: {n} gates, baseline allows {cap}. "
                f"A new gate must declare it. Undeclared and not in the baseline: "
                f"{', '.join(new) or '(none -- an existing gate lost its declaration)'}"
            )
        elif n < cap:
            findings.append(
                f"{key}: {n} gates, baseline still allows {cap} -- STALE. "
                f"Lower `allowed.{key}` to {n} so the paid-down slack cannot be re-used silently."
            )

    # A baseline naming a gate that no longer exists is dead weight that inflates the cap.
    known_ids = set(ids)
    for key, names in baseline.get("known", {}).items():
        gone = sorted(set(names) - known_ids)
        if gone:
            findings.append(f"baseline.known.{key} names gates that no longer exist: {', '.join(gone)}")

    stale_emitting = sorted(emitting - known_ids)
    if stale_emitting:
        findings.append(
            "baseline.subject_emitting_gate_ids names gates that no longer exist: "
            + ", ".join(stale_emitting)
        )
    return findings, counts


def report(findings, counts, enforce: bool) -> int:
    print(
        f"[gate-observability] budget_seconds missing on {counts['missing_budget_seconds']} gates; "
        f"min_subjects missing on {counts['missing_min_subjects']} subject-emitting gates"
    )
    # run-gates.py's min_subjects contract: this gate's SUBJECTS are the manifest entries it
    # examined -- not the findings. A manifest that shrank to nothing must not read as clean.
    print(f"SUBJECTS={counts['gates_examined']}  # gate manifest entries examined")
    if not findings:
        print("[gate-observability] OK -- both ratchets hold")
        return 0
    for f in findings:
        print(f"{'::error' if enforce else '::warning'}::{f}")
    return 1 if enforce else 0


# --------------------------------------------------------------------------- self-test
SELFTEST_MANIFEST = """gates:
  - id: alpha
    name: alpha
    group: t
    budget_seconds: 5
    min_subjects: 3
    run: "true"
  - id: beta
    name: beta
    group: t
    run: "true"
"""


def _write(tmp: pathlib.Path, manifest: str, baseline: dict):
    m = tmp / "gates.yaml"
    b = tmp / "baseline.json"
    m.write_text(manifest, encoding="utf-8")
    b.write_text(json.dumps(baseline), encoding="utf-8")
    return m, b


def self_test() -> int:
    """Falsify this gate: it must go red for each distinct failure, and green for the pass."""
    bad = []
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)

        # 1. HOLDING: beta lacks a budget, baseline allows exactly 1, beta emits no subjects.
        m, b = _write(tmp, SELFTEST_MANIFEST, {
            "allowed": {"missing_budget_seconds": 1, "missing_min_subjects": 0},
            "known": {"missing_budget_seconds": ["beta"], "missing_min_subjects": []},
            "subject_emitting_gate_ids": ["alpha"],
        })
        f, c = evaluate(*load(m, b))
        if f:
            bad.append(f"holding baseline should be clean, got {f}")
        if c != {"gates_examined": 2, "missing_budget_seconds": 1, "missing_min_subjects": 0}:
            bad.append(f"holding baseline counts wrong: {c}")

        # 2. A NEW undeclared gate must go red (the case this gate exists for).
        m, b = _write(tmp, SELFTEST_MANIFEST + (
            "  - id: gamma\n    name: gamma\n    group: t\n    run: \"true\"\n"
        ), {
            "allowed": {"missing_budget_seconds": 1, "missing_min_subjects": 0},
            "known": {"missing_budget_seconds": ["beta"], "missing_min_subjects": []},
            "subject_emitting_gate_ids": ["alpha"],
        })
        f, _ = evaluate(*load(m, b))
        if not any("gamma" in x for x in f):
            bad.append(f"a new undeclared gate was not reported: {f}")

        # 3. A subject-emitting gate that loses min_subjects must go red.
        m, b = _write(tmp, SELFTEST_MANIFEST.replace("    min_subjects: 3\n", ""), {
            "allowed": {"missing_budget_seconds": 1, "missing_min_subjects": 0},
            "known": {"missing_budget_seconds": ["beta"], "missing_min_subjects": []},
            "subject_emitting_gate_ids": ["alpha"],
        })
        f, _ = evaluate(*load(m, b))
        if not any("missing_min_subjects" in x for x in f):
            bad.append(f"a dropped min_subjects on a subject-emitting gate was not reported: {f}")

        # 4. A NON-emitting gate without min_subjects must NOT be reported (scope guard:
        #    without this the ratchet would demand an invented number from every gate).
        m, b = _write(tmp, SELFTEST_MANIFEST, {
            "allowed": {"missing_budget_seconds": 1, "missing_min_subjects": 0},
            "known": {"missing_budget_seconds": ["beta"], "missing_min_subjects": []},
            "subject_emitting_gate_ids": [],
        })
        f, _ = evaluate(*load(m, b))
        if f:
            bad.append(f"a non-emitting gate was wrongly required to declare a floor: {f}")

        # 5. A STALE baseline (cap above the real count) must be reported, or paid-down
        #    slack silently becomes room for a future regression.
        m, b = _write(tmp, SELFTEST_MANIFEST, {
            "allowed": {"missing_budget_seconds": 5, "missing_min_subjects": 0},
            "known": {"missing_budget_seconds": ["beta"], "missing_min_subjects": []},
            "subject_emitting_gate_ids": ["alpha"],
        })
        f, _ = evaluate(*load(m, b))
        if not any("STALE" in x for x in f):
            bad.append(f"a stale baseline was not reported: {f}")

        # 6. A baseline naming a deleted gate must be reported.
        m, b = _write(tmp, SELFTEST_MANIFEST, {
            "allowed": {"missing_budget_seconds": 1, "missing_min_subjects": 0},
            "known": {"missing_budget_seconds": ["beta", "ghost"], "missing_min_subjects": []},
            "subject_emitting_gate_ids": ["alpha"],
        })
        f, _ = evaluate(*load(m, b))
        if not any("ghost" in x for x in f):
            bad.append(f"a baseline entry for a deleted gate was not reported: {f}")

    if bad:
        for x in bad:
            print(f"::error::self-test: {x}")
        return 1
    print("[gate-observability] self-test OK -- 6 cases, 5 red + 1 green")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings, counts = evaluate(*load(MANIFEST, BASELINE))
    return report(findings, counts, args.enforce)


if __name__ == "__main__":
    sys.exit(main())
