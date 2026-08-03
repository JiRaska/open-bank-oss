#!/usr/bin/env python3
"""A one-replica workload must DECLARE its rollout strategy (issue #3545).

Why this exists
---------------
A Deployment with `replicas: 1` and no `strategy` block gets the defaults: `maxSurge: 25%` -> 1
and `maxUnavailable: 25%` -> **0**. Kubernetes must therefore schedule the new pod BEFORE it may
stop the old one, so the rollout needs room for TWO pods — and the old pod is holding exactly the
resources the new one needs. On a cluster with no spare room that is a deadlock the rollout never
escapes.

Measured 2026-08-03: the delegation SCA-ceremony fix (#3537) built, got pinned, and sat `Pending`
for hours behind its own predecessor while the `default` Karpenter nodepool was at its CPU limit
(48/48, 23 nodes) and no node had 448Mi free. It did not drain — the overnight CI queue emptied and
the pod stayed Pending. Throughout, `kubectl get deploy` reported `1/1 READY`, because the OLD pod
was healthy: the failure is silent, and it bites hardest when the cluster is under pressure, which
is exactly when you are shipping a fix.

What this gate does NOT do
--------------------------
It does not impose an answer, because there isn't one answer:

* **Single-replica Deployment, no HA** (most of the fleet's infra: redis, small services).
  `maxSurge: 0` / `maxUnavailable: 1` costs a few seconds of unavailability per deploy and makes
  the rollout always possible. Usually right.
* **Canary Argo Rollout** (every money-path service here). The canary pod alongside stable IS the
  strategy — terminate-first would defeat it. The real fix for those is `replicas >= 2`, which also
  buys the HA a single-pod money path does not have today.

So the rule is: *say which one you chose*. An explicit `strategy` block is a decision; the default
is an accident that reads identically until the day the cluster is full.

Falsifiability
--------------
`--self-test` feeds the matcher a workload of each shape and asserts the verdict in both
directions, so a run that reports "clean" has been shown capable of reporting "dirty".
"""

from __future__ import annotations

import argparse
import pathlib
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - the runner image always has it
    print("::error::pyyaml unavailable — add it to the runner base image", file=sys.stderr)
    raise SystemExit(1)

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"
WORKLOAD_KINDS = {"Deployment", "Rollout"}


def declares_strategy(doc: dict) -> bool:
    """True when the workload states its rollout behaviour instead of inheriting a default.

    A `Rollout` with a canary/blueGreen block has said what it wants. A `Deployment` must carry a
    `strategy` with either `type: Recreate` or a `rollingUpdate` that names at least one of
    maxSurge/maxUnavailable — an empty `strategy: {}` is the default wearing a hat.
    """
    spec = doc.get("spec") or {}
    strategy = spec.get("strategy")
    if not isinstance(strategy, dict) or not strategy:
        return False
    if doc.get("kind") == "Rollout":
        return bool(strategy.get("canary") or strategy.get("blueGreen"))
    if strategy.get("type") == "Recreate":
        return True
    rolling = strategy.get("rollingUpdate")
    if not isinstance(rolling, dict):
        return False
    return "maxSurge" in rolling or "maxUnavailable" in rolling


def findings(root: pathlib.Path) -> list[str]:
    out: list[str] = []
    for path in sorted(root.rglob("*.yaml")):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") not in WORKLOAD_KINDS:
                continue
            if (doc.get("spec") or {}).get("replicas") != 1:
                continue
            if declares_strategy(doc):
                continue
            rel = path.relative_to(REPO)
            name = (doc.get("metadata") or {}).get("name", "?")
            out.append(f"{rel}: {doc['kind']}/{name} has replicas 1 and no declared rollout strategy")
    return out


SELF_TEST_CASES = [
    ({"kind": "Deployment", "spec": {"replicas": 1}}, False, "bare one-replica Deployment"),
    ({"kind": "Deployment", "spec": {"replicas": 1, "strategy": {}}}, False, "empty strategy block"),
    (
        {"kind": "Deployment", "spec": {"replicas": 1, "strategy": {"type": "RollingUpdate", "rollingUpdate": {}}}},
        False,
        "rollingUpdate naming neither bound",
    ),
    (
        {
            "kind": "Deployment",
            "spec": {"replicas": 1, "strategy": {"type": "RollingUpdate", "rollingUpdate": {"maxUnavailable": 1}}},
        },
        True,
        "terminate-first",
    ),
    (
        {
            "kind": "Deployment",
            "spec": {"replicas": 1, "strategy": {"type": "RollingUpdate", "rollingUpdate": {"maxSurge": 1}}},
        },
        True,
        "surge, declared deliberately",
    ),
    ({"kind": "Deployment", "spec": {"replicas": 1, "strategy": {"type": "Recreate"}}}, True, "Recreate"),
    ({"kind": "Rollout", "spec": {"replicas": 1, "strategy": {"canary": {"steps": []}}}}, True, "canary Rollout"),
    ({"kind": "Rollout", "spec": {"replicas": 1, "strategy": {}}}, False, "Rollout with no strategy"),
]


def self_test() -> int:
    bad = 0
    for doc, want, label in SELF_TEST_CASES:
        got = declares_strategy(doc)
        if got != want:
            print(f"  SELF-TEST FAIL: {label} — wanted {want}, got {got}")
            bad += 1
    if bad:
        print(f"::error::check-single-replica-rollout-strategy self-test failed on {bad} case(s)")
        return 1
    print(f"selftest OK: {len(SELF_TEST_CASES)} cases, both directions.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true", help="fail on findings instead of warning")
    ap.add_argument(
        "--baseline",
        help="declared-debt file, one finding per line. A finding NOT in it fails (new debt); a "
        "baseline line with no matching finding ALSO fails (the debt was paid — delete the line, "
        "or the file outlives it and starts lying about what is covered).",
    )
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if self_test():
        return 1

    hits = findings(GITOPS)
    baseline: set[str] = set()
    if args.baseline:
        bp = pathlib.Path(args.baseline)
        if bp.exists():
            baseline = {
                ln.strip()
                for ln in bp.read_text(encoding="utf-8").splitlines()
                if ln.strip() and not ln.startswith("#")
            }

    new_debt = [h for h in hits if h not in baseline]
    paid_off = sorted(baseline - set(hits))

    for h in new_debt:
        print(f"{'::error::' if args.enforce else '::warning::'}single-replica-rollout-strategy: {h}")
    for b in paid_off:
        print(f"{'::error::' if args.enforce else '::warning::'}single-replica-rollout-strategy: baseline line no longer matches anything — delete it: {b}")

    print(
        f"check-single-replica-rollout-strategy: {len(hits)} one-replica workload(s) inherit the default "
        f"strategy, {len(new_debt)} NEW vs baseline, {len(paid_off)} stale baseline line(s)."
    )
    if new_debt:
        print(
            "  Add an explicit `strategy:` — terminate-first (maxSurge 0 / maxUnavailable 1) for a "
            "no-HA service, or surge stated deliberately. A canary Rollout already counts as declared. "
            "See issue #3545."
        )
    if not (new_debt or paid_off):
        return 0
    if not args.enforce:
        print("check-single-replica-rollout-strategy: advisory — no --enforce, so this is a warning.")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
