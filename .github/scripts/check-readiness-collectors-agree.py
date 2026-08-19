#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""
Assert the two production-readiness collectors score the fleet identically.

There are two implementations of the C1-C9 scorecard and BOTH ship:

  * openbank-infra/scripts/prod-readiness-collector.py — run by build-push-admin-ui.sh
  * openbank-admin-ui/scripts/collect-prod-readiness.mjs — run by `npm run prebuild` and by
    admin-ui-deploy.yml, because the admin-ui build image is node:26-alpine and has no python3

A second implementation of a scorer is a second copy of a list, and it drifted exactly the way
CLAUDE.md says a second copy always does. Measured on 2026-08-19, before this gate existed:

    20 of 44 services scored differently — 17 GO from the Python collector against 11 GO
    from the Node one, for the same repo, on the same day.

Every one of the differences was a correction that had landed on one side only. The Python
collector had #2255's namespace-resolved C8, #2364's `<short>-service` shapes, the
artifact-based (not prose-based) contract-test probe and the stateless C4/C5 handling; the Node
collector had the exact-calendar TTL arithmetic (#2365) the Python one lacked, so an expired
21-day pentest attestation still scored consent C7=Bank-grade there. Neither side was uniformly
right, which is why "just delete one" was not available and why nobody had noticed: each
collector looked correct on its own, and no artifact anywhere compared them.

Deleting the duplicate is not possible (no python3 in the build image) and hand-auditing it is
what already failed, so the equivalence itself is the thing that gets enforced. The gate runs
both collectors over the real repo at a FIXED date and requires byte-identical scores, evidence
strings and gates for every service.

Usage:
    check-readiness-collectors-agree.py             # gate (exit 1 on any divergence)
    check-readiness-collectors-agree.py --self-test # prove the gate can fail
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402  (path must be set before the import)

REPO = Path(__file__).resolve().parents[2]
PY_COLLECTOR = REPO / "openbank-infra" / "scripts" / "prod-readiness-collector.py"
NODE_COLLECTOR = REPO / "openbank-admin-ui" / "scripts" / "collect-prod-readiness.mjs"

# A fixed date, so the TTL decay of attestations.yaml cannot make this gate flap: an
# attestation expiring between two runs would otherwise look like a collector divergence.
# Both collectors read it the same way (--today / READINESS_TODAY).
FIXED_TODAY = "2026-01-15"


def run_python(repo: Path, out: Path) -> list[dict]:
    subprocess.run(
        [sys.executable, str(repo / "openbank-infra" / "scripts" / "prod-readiness-collector.py"),
         "--all", "--today", FIXED_TODAY, "--json", str(out)],
        cwd=repo, check=True, capture_output=True, text=True,
    )
    return json.loads(out.read_text())["services"]


def run_node(repo: Path, out: Path) -> list[dict]:
    env = dict(os.environ, READINESS_TODAY=FIXED_TODAY)
    subprocess.run(
        ["node", str(repo / "openbank-admin-ui" / "scripts" / "collect-prod-readiness.mjs"),
         "--repo", str(repo), "--out", str(out)],
        cwd=repo, check=True, capture_output=True, text=True, env=env,
    )
    return json.loads(out.read_text())["services"]


def compare(repo: Path, count: list[int] | None = None) -> list[str]:
    """-> human-readable differences; empty when the two collectors agree.

    `count`, when given, receives the number of services compared, so the caller can declare it
    as the gate's subject count even on the failure path.
    """
    with tempfile.TemporaryDirectory() as tmp:
        py = {s["service"]: s for s in run_python(repo, Path(tmp) / "py.json")}
        node = {s["service"]: s for s in run_node(repo, Path(tmp) / "node.json")}

    diffs: list[str] = []
    for only_in, missing in (("python", set(py) - set(node)), ("node", set(node) - set(py))):
        for svc in sorted(missing):
            diffs.append(f"{svc}: scored only by the {only_in} collector")

    if count is not None:
        count.append(len(set(py) & set(node)))
    for svc in sorted(set(py) & set(node)):
        a, b = py[svc], node[svc]
        if a["gate"] != b["gate"]:
            diffs.append(f"{svc}: gate python={a['gate']} node={b['gate']}")
        if a["money_path"] != b["money_path"]:
            diffs.append(f"{svc}: money_path python={a['money_path']} node={b['money_path']}")
        for dim in sorted(set(a["scores"]) | set(b["scores"])):
            pa, nb = a["scores"].get(dim), b["scores"].get(dim)
            if pa != nb:
                diffs.append(f"{svc}: {dim} score python={pa} node={nb}")
            ea, eb = a["evidence"].get(dim, ""), b["evidence"].get(dim, "")
            if ea != eb:
                diffs.append(f"{svc}: {dim} evidence\n      python: {ea}\n      node:   {eb}")
    return diffs


def self_test() -> int:
    """Prove the gate can fail: perturb one scorer in a throwaway copy and require a diff.

    A gate that only ever runs against a repo where the two agree cannot distinguish "they
    agree" from "the comparison never happened" — the failure this repo keeps finding. So the
    self-test breaks the Node collector's C6 scorer on purpose and requires the comparison to
    report it.
    """
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "repo"
        # A full copy is too slow; the collectors only read these trees.
        work.mkdir()
        for rel in ("openbank-infra/scripts", "openbank-admin-ui/scripts",
                    "openbank-libs/governance", "docs/runbooks", "docs/threat-models"):
            src = REPO / rel
            if src.exists():
                dst = work / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copytree(src, dst)
        # Enough of the fleet for both collectors to produce a non-empty, comparable population.
        for svc in sorted(REPO.glob("openbank-*-service")):
            if (svc / "governance.yaml").exists():
                shutil.copytree(svc, work / svc.name,
                                ignore=shutil.ignore_patterns("build", ".gradle", "node_modules"))
        shutil.copytree(REPO / "openbank-infra" / "gitops", work / "openbank-infra" / "gitops")

        clean = compare(work)
        if clean:
            print("SELF-TEST FAILED: the untouched copy already diverges:", file=sys.stderr)
            for d in clean:
                print(f"  {d}", file=sys.stderr)
            return 1

        node = work / "openbank-admin-ui" / "scripts" / "collect-prod-readiness.mjs"
        text = node.read_text()
        marker = "function scoreC6Dr(short) {"
        assert marker in text, "self-test needs updating: scoreC6Dr no longer exists"
        node.write_text(text.replace(
            marker, marker + "\n  return { score: 0, evidence: 'self-test perturbation' }", 1))

        broken = compare(work)
        if not broken:
            print("SELF-TEST FAILED: a deliberately broken C6 scorer produced NO divergence — "
                  "the comparison is not reaching the collectors", file=sys.stderr)
            return 1
        print(f"self-test: perturbing the Node C6 scorer produced {len(broken)} divergence(s) — "
              f"the gate can fail")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    if not PY_COLLECTOR.exists() or not NODE_COLLECTOR.exists():
        print(f"ERROR: expected both collectors to exist:\n  {PY_COLLECTOR}\n  {NODE_COLLECTOR}",
              file=sys.stderr)
        return 1

    compared: list[int] = []
    diffs = compare(REPO, compared)
    # Unconditional, including on the failure path: a gate that found its corpus and then failed
    # on it must not also be reported as having lost its corpus.
    gatelib.subjects(compared[0] if compared else 0, "services scored by both collectors")
    if diffs:
        print("Production-readiness collectors DISAGREE — the scorecard the deploy bakes into the "
              "admin-ui image depends on which of the two ran:\n", file=sys.stderr)
        for d in diffs:
            print(f"  {d}", file=sys.stderr)
        print(f"\n{len(diffs)} divergence(s). Fix the scorer on whichever side is wrong — both "
              f"have been the wrong one. Do not silence this by deleting a collector: the "
              f"admin-ui build image (node:26-alpine) has no python3, and build-push-admin-ui.sh "
              f"has no node guarantee.", file=sys.stderr)
        return 1

    print("readiness collectors agree: identical scores, evidence and gates for every service")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
