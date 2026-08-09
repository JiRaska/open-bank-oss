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

The second question: is the declared canary REALISABLE? (issue #3806)
---------------------------------------------------------------------
The paragraph above counts a canary block as "declared" and stops there, which is how all 21
canary Rollouts in this fleet — 17 of them money-path — were never findings. Measured on
`origin/main` and live (`kubectl get rollout -A`) 2026-08-08: **21 Rollouts, 21 at `replicas: 1`,
21 canary, 0 with `trafficRouting`, and every one carrying `setWeight` steps of 10/30**.

Without a traffic router, Argo realises a canary weight by POD COUNT. With N replicas the only
expressible weights are the multiples of 100/N — at N=1 that is {0, 100} and nothing else. So
`setWeight: 10` on a one-replica Rollout does not send 10% of traffic anywhere: it sends either
all of it or none, and the `pause` that follows observes a state the weights do not describe.
Raising `replicas` to 2 does not by itself fix it either — it makes `setWeight: 10` mean 50%.

Nothing in the Rollout object says so. `status.phase` is `Healthy` throughout, and a canary that
cannot split traffic presents identically to one that is splitting it correctly. That is the
"green about work it never did" class: a declaration check cannot tell these 21 from a working
fleet, so it reads as passing rather than as unchecked.

This gate therefore asks a second, independent question of every canary Rollout: can the weights
it declares actually be produced by the replica count it declares? `trafficRouting` answers it
outright (weights are then real at any replica count) and short-circuits the check. The findings
carry their own baseline file so #3545's — deliberately empty — stays that way.

What this gate still does NOT do
--------------------------------
It does not raise anybody's `replicas`. That is a capacity decision (+9.6% of the `default`
nodepool's CPU cap to double all 21) and, for at least `lending-service`, a correctness one first:
#3467 records per-pod in-memory compliance-pack state, so a second replica ARMS a four-eyes
divergence. Recording today's set as declared debt is the honest state until an owner picks
between fixing the weights, wiring a router, or paying for replicas.

Falsifiability
--------------
`--self-test` feeds both matchers a workload of each shape and asserts the verdict in both
directions, so a run that reports "clean" has been shown capable of reporting "dirty".
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import gatelib

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


def unrealisable_weights(doc: dict) -> list[int] | None:
    """The `setWeight` steps this canary declares that its replica count cannot produce.

    Returns None when the question does not apply (not a canary Rollout, or a `trafficRouting`
    provider is wired in — a router realises any weight independently of pod count). Returns the
    offending weights otherwise, empty list meaning "realisable".

    Without a router the canary share is pods-out-of-pods, so with N replicas only the multiples
    of 100/N exist. `w * N % 100 == 0` is exactly that test: at N=1 it admits only 0 and 100.
    """
    if doc.get("kind") != "Rollout":
        return None
    canary = ((doc.get("spec") or {}).get("strategy") or {}).get("canary")
    if not isinstance(canary, dict):
        return None
    if canary.get("trafficRouting"):
        return None
    replicas = (doc.get("spec") or {}).get("replicas")
    if not isinstance(replicas, int) or isinstance(replicas, bool) or replicas < 1:
        replicas = 1  # an absent `replicas` is 1, the same default that makes this bite
    bad: list[int] = []
    for step in canary.get("steps") or []:
        if not isinstance(step, dict) or "setWeight" not in step:
            continue
        weight = step["setWeight"]
        if not isinstance(weight, int) or isinstance(weight, bool):
            continue
        if weight * replicas % 100 != 0:
            bad.append(weight)
    return bad


def canary_findings(root: pathlib.Path) -> list[str]:
    """One line per canary Rollout whose declared weights its replica count cannot express."""
    out: list[str] = []
    for path in gatelib.rglob(root, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            bad = unrealisable_weights(doc)
            if not bad:
                continue
            rel = path.relative_to(REPO)
            name = (doc.get("metadata") or {}).get("name", "?")
            replicas = (doc.get("spec") or {}).get("replicas", 1)
            weights = ",".join(str(w) for w in bad)
            out.append(
                f"{rel}: Rollout/{name} canary declares setWeight {weights} that {replicas} replica(s) "
                f"cannot express and has no trafficRouting"
            )
    return out


def findings(root: pathlib.Path) -> list[str]:
    out: list[str] = []
    for path in gatelib.rglob(root, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
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


def _canary(replicas, weights, routing=False):
    canary: dict = {"steps": [{"setWeight": w} for w in weights]}
    if routing:
        canary["trafficRouting"] = {"nginx": {"stableIngress": "x"}}
    spec: dict = {"strategy": {"canary": canary}}
    if replicas is not None:
        spec["replicas"] = replicas
    return {"kind": "Rollout", "spec": spec}


# (doc, expected offending weights or None, label) — None means "the question does not apply".
CANARY_SELF_TEST_CASES = [
    (_canary(1, [10, 30, 100]), [10, 30], "the live fleet shape: 1 replica, 10/30/100"),
    (_canary(1, [10, 30]), [10, 30], "1 replica, no terminal 100"),
    (_canary(1, [100]), [], "1 replica, only 0/100 — realisable"),
    (_canary(1, [0, 100]), [], "1 replica, 0 and 100 — realisable"),
    (_canary(None, [10]), [10], "absent replicas defaults to 1"),
    (_canary(2, [10, 30, 100]), [10, 30], "2 replicas still cannot express 10 or 30"),
    (_canary(2, [50, 100]), [], "2 replicas CAN express 50 — realisable"),
    (_canary(4, [25, 50, 75, 100]), [], "4 replicas express every quarter — realisable"),
    (_canary(4, [10]), [10], "4 replicas cannot express 10"),
    (_canary(1, [10, 30], routing=True), None, "trafficRouting makes any weight real"),
    ({"kind": "Deployment", "spec": {"replicas": 1}}, None, "a Deployment is not asked"),
    (
        {"kind": "Rollout", "spec": {"replicas": 1, "strategy": {"blueGreen": {}}}},
        None,
        "blueGreen has no setWeight",
    ),
    (
        {"kind": "Rollout", "spec": {"replicas": 1, "strategy": {"canary": {"steps": [{"pause": {}}]}}}},
        [],
        "canary of pauses only — nothing to express",
    ),
]


def self_test() -> int:
    bad = 0
    for doc, want, label in SELF_TEST_CASES:
        got = declares_strategy(doc)
        if got != want:
            print(f"  SELF-TEST FAIL: {label} — wanted {want}, got {got}")
            bad += 1
    for doc, want_w, label in CANARY_SELF_TEST_CASES:
        got_w = unrealisable_weights(doc)
        if got_w != want_w:
            print(f"  SELF-TEST FAIL (canary): {label} — wanted {want_w}, got {got_w}")
            bad += 1
    if bad:
        print(f"::error::check-single-replica-rollout-strategy self-test failed on {bad} case(s)")
        return 1
    print(
        f"selftest OK: {len(SELF_TEST_CASES)} strategy-declaration + {len(CANARY_SELF_TEST_CASES)} "
        f"canary-realisability cases, both directions."
    )
    return 0


def _load_baseline(path: str | None) -> set[str]:
    if not path:
        return set()
    bp = pathlib.Path(path)
    if not bp.exists():
        return set()
    return {
        ln.strip()
        for ln in bp.read_text(encoding="utf-8").splitlines()
        if ln.strip() and not ln.startswith("#")
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true", help="fail on findings instead of warning")
    ap.add_argument(
        "--baseline",
        help="declared-debt file, one finding per line. A finding NOT in it fails (new debt); a "
        "baseline line with no matching finding ALSO fails (the debt was paid — delete the line, "
        "or the file outlives it and starts lying about what is covered).",
    )
    ap.add_argument(
        "--canary-baseline",
        help="declared-debt file for the canary-realisability half (issue #3806). Same two-way "
        "contract as --baseline: a new unrealisable canary fails, and so does a line that has "
        "stopped matching. Separate file so #3545's baseline stays empty.",
    )
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if self_test():
        return 1

    marker = "::error::" if args.enforce else "::warning::"
    rc = 0

    hits = findings(GITOPS)
    baseline = _load_baseline(args.baseline)
    new_debt = [h for h in hits if h not in baseline]
    paid_off = sorted(baseline - set(hits))

    for h in new_debt:
        print(f"{marker}single-replica-rollout-strategy: {h}")
    for b in paid_off:
        print(f"{marker}single-replica-rollout-strategy: baseline line no longer matches anything — delete it: {b}")

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
    if new_debt or paid_off:
        rc = 1

    canary_hits = canary_findings(GITOPS)
    canary_baseline = _load_baseline(args.canary_baseline)
    canary_new = [h for h in canary_hits if h not in canary_baseline]
    canary_paid = sorted(canary_baseline - set(canary_hits))

    for h in canary_new:
        print(f"{marker}canary-rollout-realisable: {h}")
    for b in canary_paid:
        print(f"{marker}canary-rollout-realisable: baseline line no longer matches anything — delete it: {b}")

    print(
        f"check-canary-rollout-realisable: {len(canary_hits)} canary Rollout(s) declare a setWeight "
        f"their replica count cannot express, {len(canary_new)} NEW vs baseline, "
        f"{len(canary_paid)} stale baseline line(s)."
    )
    if canary_new:
        print(
            "  Without `trafficRouting` a canary weight is pods-out-of-pods, so N replicas can only "
            "express multiples of 100/N — at N=1 that is 0 or 100 and nothing else. Either make the "
            "`setWeight` steps match what the replica count can produce, wire a trafficRouting "
            "provider, or raise `replicas` (a capacity AND per-pod-state decision — see #3467). "
            "See issue #3806."
        )
    if canary_new or canary_paid:
        rc = 1

    if not rc:
        return 0
    if not args.enforce:
        print("check-single-replica-rollout-strategy: advisory — no --enforce, so this is a warning.")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
