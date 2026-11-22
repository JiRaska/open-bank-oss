#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Snapshot measured per-gate wall time from CI's own ADR-0255 `--json` artifacts.

`check-gate-observability-declarations.py` rejects a `budget_seconds` below the measured p95
runtime of its gate (times a headroom factor). This writes the measurement it compares against:
`.github/gates/gate-runtime-measurements.json`.

WHY A COMMITTED SNAPSHOT, NOT A LIVE QUERY
    The ci_gate_runs warehouse has no public ingress (see derive-gate-observability-budgets.py),
    and a gate that downloads artifacts at run time fails open the day the API is rate limited.
    The source used here is the `gate-results-*` artifact `ci.yml` uploads on every run: the same
    record run-gates.py wrote, on the runner class that enforces the budget. The OUTPUT is
    committed with its exact query in `_provenance`, so the number can be re-derived.

POPULATION
    Completed `ci.yml` runs on `main` triggered by `push` (one per merge, every shard runs and no
    self-test is skipped by PR scoping), newest first, `--runs` of them. A gate's sample is its
    `seconds` field (wall, self-test included -- the same value the budget is compared with).
    A TIMEOUT record is kept at its wall (the timeout): dropping it would remove exactly the
    slowest samples. `setup` / `not_evaluated` records are dropped -- the gate did not do its work.

USAGE
    derive-gate-runtime-measurements.py --runs 30          # rewrite the snapshot (needs gh)
"""
from __future__ import annotations

import argparse
import datetime
import json
import math
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / ".github" / "gates" / "gate-runtime-measurements.json"
REPO = "JiRaska/open-bank-oss"
MIN_RUNS = 10


def p95(values):
    s = sorted(values)
    return s[max(0, math.ceil(len(s) * 0.95) - 1)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=30, choices=range(MIN_RUNS, 101),
                    metavar=f"{{{MIN_RUNS}..100}}")
    args = ap.parse_args()
    query = (f"repos/{REPO}/actions/workflows/ci.yml/runs?branch=main&event=push"
             f"&status=completed&per_page={args.runs}")
    runs = json.loads(subprocess.run(["gh", "api", query], check=True, capture_output=True,
                                     text=True).stdout)["workflow_runs"][: args.runs]
    samples, used = {}, []
    for run in runs:
        with tempfile.TemporaryDirectory() as d:
            p = subprocess.run(["gh", "run", "download", str(run["id"]), "-R", REPO,
                                "-p", "gate-results-*", "-D", d], capture_output=True, text=True)
            files = sorted(pathlib.Path(d).glob("*/*.json"))
            if p.returncode != 0 or not files:
                sys.stderr.write(f"skip run {run['id']}: no gate-results artifact\n")
                continue
            used.append(run["id"])
            for f in files:
                for rec in json.loads(f.read_text()):
                    if rec.get("failure_kind") in ("setup", "not_evaluated") or rec.get("seconds") is None:
                        continue
                    samples.setdefault(rec["id"], []).append(float(rec["seconds"]))
    if len(used) < MIN_RUNS:
        sys.exit(f"::error::only {len(used)} runs yielded gate-results artifacts (< {MIN_RUNS}); "
                 "refusing to write a snapshot from too few samples")
    gates = {g: {"n": len(v), "p95_seconds": round(p95(v), 2), "max_seconds": round(max(v), 2)}
             for g, v in sorted(samples.items())}
    doc = {
        "_comment": "DERIVED, not hand-written. Regenerate with "
                    ".github/scripts/derive-gate-runtime-measurements.py.",
        "_provenance": {
            "source": "ci.yml gate-results-* artifacts (ADR-0255 --json), field `seconds`",
            "query": f"gh api '{query}'",
            "runs_used": len(used),
            "newest_run": used[0],
            "oldest_run": used[-1],
            "derived_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        },
        "gates": gates,
    }
    OUT.write_text(json.dumps(doc, indent=2) + "\n")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(gates)} gates from {len(used)} runs")


if __name__ == "__main__":
    main()
