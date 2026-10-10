#!/usr/bin/env python3
"""A workload the internet reaches must survive losing one pod.

Why this exists
---------------
customer-edge — the public entry point of the customer app — ran ONE replica. On 2026-10-03 at
04:40Z the journey-public-edge synthetic probe failed because that pod rotated (04:39:30-04:40:13,
a Karpenter spot move): with one replica every rollout, eviction and node consolidation is a
customer-visible outage, and nothing in the manifest said so. Its own code carried a WARN that it
was single-replica by design (in-memory nearby-pay sessions, issue #4728); nothing else in the
fleet would have noticed if it had not.

What it checks
--------------
The scope is DERIVED, never listed: every Service that a `networking.k8s.io` Ingress or a Gateway
API HTTPRoute sends traffic to, resolved to the Deployment / Rollout / StatefulSet whose pod
template that Service selects. For each such public workload:

  * replicas >= 2 (or an HPA / KEDA ScaledObject targeting it with a minimum >= 2);
  * a PodDisruptionBudget in its namespace that selects its pods — without one a node drain may
    evict every replica at once;
  * a `kubernetes.io/hostname` topologySpreadConstraint (or required pod anti-affinity on the
    hostname) — two replicas on one spot node are one replica with extra steps.

A backend Service that is not declared in gitops (a chart renders it) cannot be checked, and is
reported as its own finding rather than skipped: an unresolvable subject is "unchecked", not
"clean".

Today's gaps are recorded in a baseline (two-way: a NEW finding fails, and so does a baseline line
that no longer matches anything — the debt was paid, delete the line).

Falsifiability
--------------
`--self-test` builds a small manifest set in memory — a compliant workload plus one shape for each
rule — and asserts the exact findings in both directions, including the unresolvable-backend case.
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
    raise SystemExit(1) from None

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"
WORKLOAD_KINDS = {"Deployment", "Rollout", "StatefulSet"}
HOSTNAME = "kubernetes.io/hostname"


def _ns(doc: dict) -> str | None:
    return (doc.get("metadata") or {}).get("namespace")


def _name(doc: dict) -> str:
    return (doc.get("metadata") or {}).get("name", "?")


def _subset(sel: dict, labels: dict) -> bool:
    return bool(sel) and all(labels.get(k) == v for k, v in sel.items())


def public_backends(docs: list[tuple[str, dict]]) -> list[tuple[str, str | None, str]]:
    """(route file, namespace, service name) for every Service an Ingress or HTTPRoute targets."""
    out = []
    for rel, doc in docs:
        kind, api = doc.get("kind"), str(doc.get("apiVersion", ""))
        spec = doc.get("spec") or {}
        if kind == "Ingress" and api.startswith("networking.k8s.io"):
            backends = [spec.get("defaultBackend")]
            for rule in spec.get("rules") or []:
                for p in ((rule or {}).get("http") or {}).get("paths") or []:
                    backends.append((p or {}).get("backend"))
            for b in backends:
                svc = ((b or {}).get("service") or {}).get("name")
                if svc:
                    out.append((rel, _ns(doc), svc))
        elif kind == "HTTPRoute" and api.startswith("gateway.networking.k8s.io"):
            for rule in spec.get("rules") or []:
                for ref in (rule or {}).get("backendRefs") or []:
                    if (ref or {}).get("kind", "Service") == "Service" and ref.get("name"):
                        out.append((rel, ref.get("namespace", _ns(doc)), ref["name"]))
    return sorted(set(out))


def _min_scaled(docs: list[tuple[str, dict]], ns: str | None, kind: str, name: str) -> int:
    best = 0
    for _, doc in docs:
        if _ns(doc) != ns:
            continue
        spec = doc.get("spec") or {}
        if doc.get("kind") == "HorizontalPodAutoscaler":
            ref, floor = spec.get("scaleTargetRef") or {}, spec.get("minReplicas", 1)
        elif doc.get("kind") == "ScaledObject":
            ref, floor = spec.get("scaleTargetRef") or {}, spec.get("minReplicaCount", 0)
        else:
            continue
        if ref.get("name") == name and ref.get("kind", "Deployment") == kind and isinstance(floor, int):
            best = max(best, floor)
    return best


def _spreads_on_hostname(pod_spec: dict) -> bool:
    for c in pod_spec.get("topologySpreadConstraints") or []:
        if (c or {}).get("topologyKey") == HOSTNAME:
            return True
    anti = ((pod_spec.get("affinity") or {}).get("podAntiAffinity") or {})
    return any(
        (t or {}).get("topologyKey") == HOSTNAME
        for t in anti.get("requiredDuringSchedulingIgnoredDuringExecution") or []
    )


def findings_for(docs: list[tuple[str, dict]]) -> tuple[list[str], int]:
    services = {(_ns(d), _name(d)): (rel, d) for rel, d in docs if d.get("kind") == "Service"}
    workloads = [(rel, d) for rel, d in docs if d.get("kind") in WORKLOAD_KINDS]
    pdbs = [d for _, d in docs if d.get("kind") == "PodDisruptionBudget"]
    out: list[str] = []
    checked: dict[tuple, tuple[str, dict]] = {}
    for route_rel, ns, svc in public_backends(docs):
        hit = services.get((ns, svc))
        if hit is None:
            out.append(f"{route_rel}: routes to Service {ns}/{svc}, which no gitops manifest declares — cannot check")
            continue
        selector = (hit[1].get("spec") or {}).get("selector") or {}
        for wrel, w in workloads:
            labels = ((((w.get("spec") or {}).get("template") or {}).get("metadata") or {}).get("labels")) or {}
            if _ns(w) == ns and _subset(selector, labels):
                checked[(wrel, w.get("kind"), _name(w))] = (wrel, w)
    for (wrel, kind, name), (_, w) in sorted(checked.items(), key=lambda kv: kv[0]):
        spec = w.get("spec") or {}
        pod = ((spec.get("template") or {}).get("spec")) or {}
        labels = ((spec.get("template") or {}).get("metadata") or {}).get("labels") or {}
        replicas = max(spec.get("replicas", 1) or 0, _min_scaled(docs, _ns(w), kind, name))
        subject = f"{wrel}: {kind}/{name}"
        if replicas < 2:
            out.append(f"{subject} is publicly routed and runs {replicas} replica(s), needs >= 2")
        sel_of = lambda p: ((p.get("spec") or {}).get("selector") or {}).get("matchLabels") or {}  # noqa: E731
        if not any(_ns(p) == _ns(w) and _subset(sel_of(p), labels) for p in pdbs):
            out.append(f"{subject} is publicly routed and no PodDisruptionBudget selects its pods")
        if not _spreads_on_hostname(pod):
            out.append(f"{subject} is publicly routed and does not spread its pods across nodes")
    return out, len(checked)


def load(root: pathlib.Path) -> list[tuple[str, dict]]:
    docs = []
    for path in gatelib.rglob(root, "*.yaml"):
        try:
            loaded = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        rel = str(path.relative_to(REPO))
        docs.extend((rel, d) for d in loaded if isinstance(d, dict))
    return docs


def _fixture(replicas=2, pdb=True, spread=True, service=True, hpa_min=None):
    labels = {"app": "edge"}
    pod = {"containers": [{"name": "c"}]}
    if spread:
        pod["topologySpreadConstraints"] = [{"maxSkew": 1, "topologyKey": HOSTNAME}]
    docs = [
        ("r.yaml", {"apiVersion": "networking.k8s.io/v1", "kind": "Ingress", "metadata": {"namespace": "n"},
                    "spec": {"rules": [{"http": {"paths": [{"backend": {"service": {"name": "edge"}}}]}}]}}),
        ("w.yaml", {"kind": "Rollout", "metadata": {"name": "edge", "namespace": "n"},
                    "spec": {"replicas": replicas, "template": {"metadata": {"labels": labels}, "spec": pod}}}),
        # An unrouted single-replica workload must stay out of scope.
        ("w.yaml", {"kind": "Deployment", "metadata": {"name": "redis", "namespace": "n"},
                    "spec": {"replicas": 1, "template": {"metadata": {"labels": {"app": "redis"}}, "spec": {}}}}),
    ]
    if service:
        docs.append(("w.yaml", {"kind": "Service", "metadata": {"name": "edge", "namespace": "n"},
                                "spec": {"selector": labels}}))
    if pdb:
        docs.append(("w.yaml", {"kind": "PodDisruptionBudget", "metadata": {"namespace": "n"},
                                "spec": {"maxUnavailable": 1, "selector": {"matchLabels": labels}}}))
    if hpa_min is not None:
        docs.append(("w.yaml", {"kind": "HorizontalPodAutoscaler", "metadata": {"namespace": "n"},
                                "spec": {"minReplicas": hpa_min, "scaleTargetRef": {"kind": "Rollout", "name": "edge"}}}))
    return docs


SELF_TEST_CASES = [
    (_fixture(), [], "compliant: 2 replicas, PDB, hostname spread"),
    (_fixture(replicas=1), ["replica"], "one replica"),
    (_fixture(replicas=1, hpa_min=2), [], "one declared replica, HPA floor 2"),
    (_fixture(pdb=False), ["PodDisruptionBudget"], "no PDB"),
    (_fixture(spread=False), ["across nodes"], "no hostname spread"),
    (_fixture(replicas=1, pdb=False, spread=False), ["replica", "PodDisruptionBudget", "across nodes"], "all three"),
    (_fixture(service=False), ["cannot check"], "backend Service not in gitops is a finding, not a pass"),
]


def self_test() -> int:
    failed = 0
    for docs, expected, label in SELF_TEST_CASES:
        got, _ = findings_for(docs)
        ok = len(got) == len(expected) and all(any(e in g for g in got) for e in expected)
        if not ok:
            failed += 1
            print(f"::error::public-workload-ha self-test FAILED [{label}]: expected {expected}, got {got}")
    print(f"public-workload-ha self-test: {len(SELF_TEST_CASES) - failed}/{len(SELF_TEST_CASES)} cases, both directions.")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--baseline", help="declared-debt file, two-way (new findings AND stale lines fail)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if self_test():
        return 1

    hits, subjects = findings_for(load(GITOPS))
    gatelib.subjects(subjects, "publicly routed workloads")
    baseline: set[str] = set()
    if args.baseline and pathlib.Path(args.baseline).exists():
        baseline = {
            ln.strip() for ln in pathlib.Path(args.baseline).read_text(encoding="utf-8").splitlines()
            if ln.strip() and not ln.startswith("#")
        }
    new = [h for h in hits if h not in baseline]
    stale = sorted(baseline - set(hits))
    marker = "::error::" if args.enforce else "::warning::"
    for h in new:
        print(f"{marker}public-workload-ha: {h}")
    for b in stale:
        print(f"{marker}public-workload-ha: baseline line no longer matches anything — delete it: {b}")
    print(
        f"check-public-workload-ha: {subjects} publicly routed workload(s), {len(hits)} finding(s), "
        f"{len(new)} NEW vs baseline, {len(stale)} stale baseline line(s)."
    )
    return 1 if args.enforce and (new or stale) else 0


if __name__ == "__main__":
    sys.exit(main())
