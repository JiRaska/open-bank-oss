#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Report NetworkPolicy coverage PER POD, live, read-only (issue #2691 stage 2).

Why per pod and not per namespace
---------------------------------
"This namespace has NetworkPolicies" is the reassuring flattening. A policy
selects specific pods by label, so a namespace can hold a dozen allow-lists and
still leave a co-tenant workload — most importantly its CloudNativePG Postgres
instances, which are operator-managed and carry no Deployment for
`gen-network-policies.py` to derive from — selected by nothing at all.

Under the VPC CNI in `standard` mode (ADR-0060) that distinction is the whole
question a default-deny asks:

  * a pod that IS selected by an Ingress policy is already deny-by-default for
    ingress — a namespace default-deny changes nothing for it;
  * a pod that is selected by NO policy is fully reachable, and a namespace
    default-deny severs every flow into it that is not separately allow-listed.

So the set this prints under `uncovered` is, exactly, the set a default-deny
would newly cut off. That is the number stage 2 needs, and it is not derivable
from the repo: CNPG pods exist only in the cluster.

This tool MUTATES NOTHING. It runs `kubectl get pods/networkpolicies -A -o json`
and reads them. Pass `--from <dir>` to read `pods.json` / `netpol.json` captured
earlier instead of contacting a cluster.

    python3 openbank-infra/scripts/netpol-coverage.py
    python3 openbank-infra/scripts/netpol-coverage.py --direction Egress
"""

import argparse
import json
import os
import subprocess
import sys
from collections import defaultdict

# Namespaces whose pods are not app workloads and are out of scope for the
# app-plane rollout (they get their own assessment, issue #2691 stage 4).
INFRA_NS_HINT = {
    "kube-system", "argocd", "keda", "kyverno", "falco", "vault", "cnpg-system",
    "cert-manager", "external-secrets", "external-dns", "ingress-nginx", "vpa",
    "arc-runners", "arc-systems", "registry-cache", "gradle-build-cache",
    "reposilite", "finops-scaledown",
}


def selects(pod_selector, labels):
    """True if a NetworkPolicy podSelector selects a pod with these labels.

    Implements the subset of the selector grammar the fleet actually uses:
    matchLabels (AND) plus In/NotIn/Exists/DoesNotExist matchExpressions. An
    empty selector selects every pod in the namespace — that is the shape a
    namespace-wide default-deny takes, so getting it right is the point.
    """
    sel = pod_selector or {}
    for k, v in (sel.get("matchLabels") or {}).items():
        if labels.get(k) != v:
            return False
    for expr in sel.get("matchExpressions") or []:
        key, op = expr.get("key"), expr.get("operator")
        vals = expr.get("values") or []
        present = key in labels
        if op == "In" and labels.get(key) not in vals:
            return False
        if op == "NotIn" and labels.get(key) in vals:
            return False
        if op == "Exists" and not present:
            return False
        if op == "DoesNotExist" and present:
            return False
    return True


def coverage(pods, policies, direction="Ingress"):
    """-> {ns: {"covered": [names], "uncovered": [names]}} for running pods."""
    by_ns = defaultdict(list)
    for p in policies:
        by_ns[p["metadata"]["namespace"]].append(p)

    out = defaultdict(lambda: {"covered": [], "uncovered": []})
    for pod in pods:
        if (pod.get("status") or {}).get("phase") in ("Succeeded", "Failed"):
            continue  # completed Jobs are not a live attack surface
        ns = pod["metadata"]["namespace"]
        labels = pod["metadata"].get("labels") or {}
        name = labels.get("app.kubernetes.io/name") or pod["metadata"]["name"]
        hit = any(
            direction in (pol["spec"].get("policyTypes") or [])
            and selects(pol["spec"].get("podSelector"), labels)
            for pol in by_ns[ns]
        )
        out[ns]["covered" if hit else "uncovered"].append(name)
    return out


def _kubectl(kind):
    return json.loads(subprocess.run(
        ["kubectl", "get", kind, "-A", "-o", "json"],
        check=True, capture_output=True, text=True).stdout)["items"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--direction", default="Ingress", choices=["Ingress", "Egress"])
    ap.add_argument("--from", dest="src", help="dir holding pods.json + netpol.json")
    args = ap.parse_args()

    if args.src:
        pods = json.load(open(os.path.join(args.src, "pods.json")))["items"]
        pols = json.load(open(os.path.join(args.src, "netpol.json")))["items"]
    else:
        pods, pols = _kubectl("pods"), _kubectl("networkpolicies")

    cov = coverage(pods, pols, args.direction)
    tot_c = tot_u = 0
    print(f"{'NAMESPACE':30} {'COV':>4} {'UNCOV':>6}  workloads a default-deny-"
          f"{args.direction.lower()} would newly cut off")
    for ns in sorted(cov):
        c, u = len(cov[ns]["covered"]), len(cov[ns]["uncovered"])
        tot_c, tot_u = tot_c + c, tot_u + u
        tag = " [infra]" if ns in INFRA_NS_HINT else ""
        names = ",".join(sorted(set(cov[ns]["uncovered"])))
        print(f"{ns:30} {c:4} {u:6}  {names}{tag}")
    print(f"\ntotal: {tot_c} pods covered, {tot_u} uncovered "
          f"({tot_c + tot_u} running)")
    print("A namespace at UNCOV=0 is one where a default-deny is a no-op for "
          "every pod that exists today — the only safe place to start.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
