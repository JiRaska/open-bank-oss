#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Propose `budget_seconds` / `min_subjects` for gates.yaml from the ci_gate_runs warehouse.

This is the paying-down half of `check-gate-observability-declarations.py`. That gate ratchets
the number of undeclared gates; this proposes the numbers, so nobody has to guess them.

WHY IT IS NOT RUN IN CI
    The warehouse (ADR-0255 Tier 2) lives in-cluster with no public ingress, exactly like the
    Pact Broker (ADR-0056). So this is a maintainer tool run from a kubectl context, and its
    OUTPUT is what gets committed -- never a live query from a gate. A gate that needed the
    cluster to be reachable would be a gate that fails open the day the cluster is not.

METHOD, and why these multipliers
    budget_seconds = ceil(max(p95 * 3, max_observed * 1.5, 5))
        A budget is a RUNAWAY detector, not a performance target. Set at p95 the gate reddens
        on ordinary runner variance -- the fastest way to teach everyone to ignore it. Three
        gate estates' worth of lore in this repo says a noisy control is worse than none.
        The `max_observed * 1.5` arm keeps a gate with a fat tail from being budgeted below a
        run that has already happened, and the 5s floor keeps sub-second gates from getting a
        budget that a cold Python interpreter can breach.

    min_subjects = floor(min_observed * 0.8), only where the gate emitted a count on EVERY
        observed run and that count never went below 2. A floor derived from a single sample,
        or from a gate whose subject count legitimately reaches 1, is a false alarm waiting
        for its first quiet week.

USAGE
    kubectl -n analytics exec clickhouse-0 -- clickhouse-client --user analytics \
      --password "$CHPW" -q "$(derive-gate-observability-budgets.py --print-query)" \
      --format TSVWithNames > stats.tsv
    derive-gate-observability-budgets.py --stats stats.tsv            # print proposals
    derive-gate-observability-budgets.py --stats stats.tsv --baseline # rewrite the baseline
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import pathlib
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    print("::error::PyYAML is required", file=sys.stderr)
    raise

ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = ROOT / ".github" / "gates" / "gates.yaml"
BASELINE = ROOT / ".github" / "gates" / "observability-baseline.json"

QUERY = """
SELECT gate_id, count() n,
       round(quantile(0.5)(seconds),2) p50,
       round(quantile(0.95)(seconds),2) p95,
       round(max(seconds),2) mx,
       min(subjects) subj_min, max(subjects) subj_max,
       countIf(subjects IS NULL) subj_null,
       any(budget_seconds) declared_budget, any(min_subjects) declared_floor
FROM openbank_analytics.ci_gate_runs
WHERE run_created_at > now() - INTERVAL 14 DAY AND status IN ('ok','warned','failed')
GROUP BY gate_id ORDER BY gate_id
""".strip()

NULL = "\\N"


def _num(v):
    return None if v in (NULL, "", None) else float(v)


def propose(row):
    """Return (budget_seconds | None, min_subjects | None) for one warehouse row."""
    p95, mx = _num(row.get("p95")), _num(row.get("mx"))
    budget = None
    if p95 is not None and mx is not None:
        budget = int(math.ceil(max(p95 * 3, mx * 1.5, 5)))

    floor = None
    smin, smax = _num(row.get("subj_min")), _num(row.get("subj_max"))
    nulls = _num(row.get("subj_null")) or 0
    # Every observed run must have reported a count; a gate that sometimes reports and
    # sometimes does not would get a floor it cannot always satisfy.
    if smin is not None and smax is not None and nulls == 0 and smin >= 2:
        floor = int(math.floor(smin * 0.8)) or None
    return budget, floor


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--print-query", action="store_true")
    ap.add_argument("--stats", type=pathlib.Path)
    ap.add_argument("--baseline", action="store_true", help="rewrite observability-baseline.json")
    args = ap.parse_args()

    if args.print_query:
        print(QUERY)
        return 0
    if not args.stats:
        print("::error::--stats <tsv> is required (see --print-query)", file=sys.stderr)
        return 2

    with args.stats.open(encoding="utf-8") as fh:
        rows = {r["gate_id"]: r for r in csv.DictReader(fh, delimiter="\t")}

    gates = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))["gates"]
    ids = {g["id"] for g in gates}

    emitting = sorted(i for i, r in rows.items() if i in ids and r.get("subj_max") not in (NULL, "", None))

    print(f"# {len(rows)} gate ids observed; {len(ids)} in the manifest; {len(emitting)} emit a subject count\n")
    for g in gates:
        r = rows.get(g["id"])
        if r is None:
            print(f"{g['id']}: NOT OBSERVED in the window -- run it before proposing a budget")
            continue
        budget, floor = propose(r)
        want = []
        if g.get("budget_seconds") is None and budget is not None:
            want.append(f"budget_seconds: {budget}   # p95 {r['p95']}s, max {r['mx']}s over {r['n']} runs")
        if g.get("min_subjects") is None and floor is not None:
            want.append(f"min_subjects: {floor}   # observed {r['subj_min']}..{r['subj_max']}")
        if want:
            print(f"{g['id']}:")
            for w in want:
                print(f"    {w}")

    if args.baseline:
        mb = sorted(g["id"] for g in gates if g.get("budget_seconds") is None)
        mf = sorted(g["id"] for g in gates if g.get("min_subjects") is None and g["id"] in set(emitting))
        base = json.loads(BASELINE.read_text(encoding="utf-8"))
        base["allowed"] = {"missing_budget_seconds": len(mb), "missing_min_subjects": len(mf)}
        base["known"] = {"missing_budget_seconds": mb, "missing_min_subjects": mf}
        base["subject_emitting_gate_ids"] = emitting
        BASELINE.write_text(json.dumps(base, indent=2) + "\n", encoding="utf-8")
        print(f"\n[derive] baseline rewritten: {len(mb)} without a budget, {len(mf)} without a floor")
    return 0


if __name__ == "__main__":
    sys.exit(main())
