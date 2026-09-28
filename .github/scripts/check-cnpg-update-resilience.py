#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every CNPG cluster survives its own image update, and every money-path cluster survives a node.

THE DEFECT THIS EXISTS FOR (ADR-0325, 2026-09-28)
------------------------------------------------
A fleet-wide PostgreSQL minor bump (18.1 -> 18.6) reached ~70 CNPG clusters in one ArgoCD sync.
No Cluster set `spec.primaryUpdateMethod`, so every one ran CNPG's default, `restart`: the
primary is restarted IN PLACE and is down for the whole restart, even when a streaming standby
is sitting next to it ready to take over. At the same moment Karpenter was draining a drifted
node; 22 EBS volumes never detached from it, the replacement pods could not attach them, and
Karpenter waited on the detach indefinitely. Clusters whose only instance was on that node were
simply down -- among them `card-issuance-db`, a money-path database running `instances: 1`
although ADR-0159 had said every money-path cluster runs two.

ADR-0159 was right and had no control behind it: it enumerated the 17 money-path services of
its day, and the eight added to `rules.yaml: money_path_services` since then were never
reconciled against it. Seven of them shipped single-instance. Prose is not a control.

WHAT THIS ENFORCES
------------------
1. EVERY CNPG Cluster sets `primaryUpdateMethod: switchover`, so an image/config update moves
   the primary role to an already-updated standby before touching the old primary. On a
   single-instance cluster the operator has nothing to switch to and restarts, so the rule costs
   nothing there -- and it means a cluster that later grows a standby is already correct.
2. EVERY money-path cluster has `instances >= 2`, required hostname anti-affinity, and does not
   disable its PodDisruptionBudget. Without a second instance on a second node, neither
   switchover nor failover has a target, and a stuck node is a stuck database.

The money-path cluster set is DERIVED, never listed: for each service in
`rules.yaml: money_path_services`, find the workload whose container image is that service and
collect every `<cluster>-rw|-ro|-r.<namespace>.svc` it connects to. A money-path service that
resolves to no workload, or whose workload names no CNPG cluster, is itself a finding unless it
is declared in NO_DATABASE with a reason -- so a renamed image or a new service cannot quietly
shrink the set this gate checks. NO_DATABASE entries go stale in both directions.

Usage:
    check-cnpg-update-resilience.py              # gate (exit 1 on any finding)
    check-cnpg-update-resilience.py --self-test  # prove the gate can fail
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

import gatelib
import yaml

REPO = Path(__file__).resolve().parents[2]
GITOPS = Path("openbank-infra/gitops")
RULES = Path("openbank-libs/governance/rules.yaml")
SKIP_PATH_PARTS = ("dr-restore-templates",)
WORKLOAD_KINDS = ("Deployment", "Rollout", "StatefulSet")
SVC_RX = re.compile(r"\b([a-z0-9][a-z0-9-]*?)-(?:rw|ro|r)\.([a-z0-9-]+)\.svc\b")
IMAGE_RX = re.compile(r'"image":\s*"([^"]+)"')

# Money-path services that genuinely own no database. Reason required; an entry for a service
# that does resolve to a cluster, or that is no longer money-path, is a stale finding.
NO_DATABASE: dict[str, str] = {}


def _ns_from_kustomization(path: Path) -> str | None:
    for parent in path.parents:
        k = parent / "kustomization.yaml"
        if k.is_file():
            doc = gatelib.load_yaml(k, errors="replace")
            if isinstance(doc, dict) and doc.get("namespace"):
                return str(doc["namespace"])
        if parent == REPO:
            break
    return None


def _image_name(ref: str) -> str:
    return ref.split("@", 1)[0].rsplit("/", 1)[-1].split(":", 1)[0]


def scan() -> tuple[dict[str, dict], dict[str, set], list[str]]:
    """Return (clusters by ns/name, cluster refs by image name, parse errors)."""
    clusters: dict[str, dict] = {}
    refs: dict[str, set] = {}
    errors: list[str] = []
    for path in sorted(gatelib.rglob(REPO / GITOPS, "*.yaml")):
        rel = path.relative_to(REPO)
        if any(p in rel.parts for p in SKIP_PATH_PARTS):
            continue
        try:
            docs = gatelib.load_yaml_all(path, errors="replace")
        except yaml.YAMLError as exc:
            errors.append(f"{rel}: could not be parsed, so anything in it is unchecked ({exc})")
            continue
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            kind = doc.get("kind")
            meta = doc.get("metadata") or {}
            if kind == "Cluster" and "cnpg.io" in str(doc.get("apiVersion", "")):
                ns = meta.get("namespace") or _ns_from_kustomization(path)
                if not meta.get("name") or not ns:
                    errors.append(f"{rel}: CNPG Cluster without a resolvable name/namespace")
                    continue
                clusters[f"{ns}/{meta['name']}"] = {"spec": doc.get("spec") or {}, "path": str(rel)}
            elif kind in WORKLOAD_KINDS:
                text = json.dumps(doc)
                ns = meta.get("namespace") or _ns_from_kustomization(path) or ""
                found = {f"{n}/{c}" for c, n in SVC_RX.findall(text)}
                for img in IMAGE_RX.findall(text):
                    refs.setdefault(_image_name(img), set()).update(found)
                    refs[_image_name(img)].add(f"@{ns}")  # marker: workload exists
    return clusters, refs, errors


def check() -> tuple[list[str], int]:
    clusters, refs, findings = scan()
    rules = gatelib.load_yaml(REPO / RULES)
    money = list(rules.get("money_path_services") or [])
    if not money:
        findings.append(f"{RULES}: money_path_services is empty or missing -- refusing to pass vacuously")

    # 1. fleet-wide: switchover, never an in-place primary restart
    for key, c in sorted(clusters.items()):
        method = c["spec"].get("primaryUpdateMethod")
        if method != "switchover":
            findings.append(
                f"{c['path']}: Cluster {key} primaryUpdateMethod={method or 'unset (CNPG default: restart)'}"
                " -- set `primaryUpdateMethod: switchover` so an update never restarts the primary in place"
            )

    # 2. derive the money-path cluster set
    mp_clusters: dict[str, str] = {}
    for svc in money:
        found = refs.get(svc)
        owned = sorted(k for k in (found or ()) if not k.startswith("@") and k in clusters)
        if svc in NO_DATABASE:
            if owned:
                findings.append(f"NO_DATABASE[{svc}] is stale: it connects to {owned}")
            continue
        if found is None:
            findings.append(
                f"money-path service {svc}: no Deployment/Rollout/StatefulSet in {GITOPS} runs an image"
                f" named {svc}, so its database cannot be derived -- fix the derivation or declare it"
                " in NO_DATABASE with a reason"
            )
            continue
        if not owned:
            findings.append(
                f"money-path service {svc}: its workload connects to no declared CNPG cluster"
                " (<cluster>-rw.<ns>.svc) -- declare it in NO_DATABASE with a reason if it truly has none"
            )
        for k in owned:
            mp_clusters.setdefault(k, svc)
    for svc in NO_DATABASE:
        if svc not in money:
            findings.append(f"NO_DATABASE[{svc}] is stale: not in money_path_services")

    # 3. money-path: a second instance, on a second node, with its PDB
    for key, svc in sorted(mp_clusters.items()):
        c = clusters[key]
        spec = c["spec"]
        where = f"{c['path']}: money-path Cluster {key} ({svc})"
        inst = spec.get("instances", 1)
        if not isinstance(inst, int) or inst < 2:
            findings.append(f"{where} has instances={inst}; ADR-0159 requires >= 2 (primary + standby)")
        aff = spec.get("affinity") or {}
        if aff.get("enablePodAntiAffinity") is not True:
            findings.append(f"{where} lacks affinity.enablePodAntiAffinity: true")
        if aff.get("topologyKey") != "kubernetes.io/hostname":
            findings.append(f"{where} affinity.topologyKey must be kubernetes.io/hostname")
        if aff.get("podAntiAffinityType") != "required":
            findings.append(f"{where} affinity.podAntiAffinityType must be `required`")
        if spec.get("enablePDB") is False:
            findings.append(f"{where} disables its PodDisruptionBudget (enablePDB: false)")

    gatelib.subjects(len(clusters), f"CNPG clusters; {len(mp_clusters)} money-path")
    return findings, len(mp_clusters)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings, _ = check()
    for f in findings:
        print(f"::error::{f}")
    if findings:
        print(f"FAIL: {len(findings)} finding(s)")
        return 1
    print("OK: every CNPG cluster updates by switchover; every money-path cluster is HA")
    return 0


_CLUSTER = """apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: {name}
  namespace: st
spec:
  instances: {inst}
{extra}"""
_HA = """  primaryUpdateMethod: switchover
  affinity:
    enablePodAntiAffinity: true
    topologyKey: kubernetes.io/hostname
    podAntiAffinityType: required
"""
_DEPLOY = """apiVersion: apps/v1
kind: Deployment
metadata:
  name: pay
  namespace: st
spec:
  template:
    spec:
      containers:
        - name: app
          image: registry.example/openbank-pay-service:1.0.0
          env:
            - name: JDBC
              value: jdbc:postgresql://pay-db-rw.st.svc:5432/pay
"""


def self_test() -> int:
    global REPO
    ok = True

    def run(files: dict[str, str]) -> list[str]:
        global REPO
        with tempfile.TemporaryDirectory() as td:
            fake = Path(td)
            d = fake / GITOPS / "components" / "st"
            d.mkdir(parents=True)
            (fake / RULES).parent.mkdir(parents=True)
            (fake / RULES).write_text("money_path_services:\n  - openbank-pay-service\n")
            for n, body in files.items():
                (d / n).write_text(body)
            REPO = fake
            gatelib.clear()
            try:
                return check()[0]
            finally:
                REPO = Path(__file__).resolve().parents[2]

    good = {
        "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra=_HA)
        + "---\n" + _CLUSTER.format(name="other-db", inst=1, extra="  primaryUpdateMethod: switchover\n"),
        "deploy.yaml": _DEPLOY,
    }
    if run(good):
        print(f"SELF-TEST FAIL: a compliant corpus was reported: {run(good)}"); ok = False

    cases = {
        "single-instance money-path cluster (the card-issuance-db shape)": (
            {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=1, extra=_HA)}, "instances=1"),
        "restart update method on a non-money-path cluster": (
            {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra=_HA)
             + "---\n" + _CLUSTER.format(name="other-db", inst=1, extra="")}, "other-db primaryUpdateMethod=unset"),
        "money-path cluster without hostname anti-affinity": (
            {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra="  primaryUpdateMethod: switchover\n")},
            "enablePodAntiAffinity"),
        "money-path service whose workload vanished": (
            {"db.yaml": good["db.yaml"]}, "cannot be derived"),
        "money-path cluster with PDB disabled": (
            {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra=_HA + "  enablePDB: false\n")},
            "enablePDB"),
    }
    for label, (files, needle) in cases.items():
        got = run(files)
        if not any(needle in f for f in got):
            print(f"SELF-TEST FAIL: {label} not reported (wanted '{needle}', got {got})"); ok = False
    print("SELF-TEST", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
