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
3. EVERY cluster runs a standby (rule 4 below) and EVERY cluster with a standby pins it to a
   different node (required hostname anti-affinity, rule 5). `preferred` is a hint the scheduler
   drops under pressure -- exactly during the node churn a standby exists for -- so a two-instance
   cluster whose instances share a node pays for HA and gets none of it.

The money-path cluster set is DERIVED, never listed: for each service in
`rules.yaml: money_path_services`, find the workload whose container image is that service and
collect every `<cluster>-rw|-ro|-r.<namespace>.svc` it connects to. A money-path service that
resolves to no workload, or whose workload names no CNPG cluster, is itself a finding unless it
is declared in NO_DATABASE with a reason -- so a renamed image or a new service cannot quietly
shrink the set this gate checks. NO_DATABASE entries go stale in both directions.

PLATFORM DATABASES (`rules.yaml: money_path_platform_databases`)
A money-path service can be down without its own database being down: Temporal (every payment
saga's state lives in temporal-db) and Keycloak (every money-path call carries a token it mints
from keycloak-db) sit on the path one hop away. That hop is NOT derived: at namespace granularity
"a money-path workload calls a Service in namespace N" also pulls in goalert-db (observability),
apicurio-db (messaging) and the databases of every non-money-path peer -- 9 clusters, measured
2026-09-28, most of them wrong. So the set is an explicit list with a reason per entry, and the
derivation is used to keep it honest instead: an entry whose cluster does not exist, whose
namespace no money-path workload calls, or that the service derivation already covers, is stale.

THE CONSUMER OF A PLATFORM DATABASE (rule 6, 2026-09-29)
An HA temporal-db buys nothing if the Temporal server in front of it is one pod per role: every
roll of that pod empties the membership ring for its role, and every caller gets "Not enough
hosts to serve the request" until the successor joins. So every Temporal Helm Application whose
persistence points at a money-path platform database runs frontend, history and matching with
`replicaCount >= 2` and a `podDisruptionBudget` -- read from `server.<role>`, where the chart
looks. A role block written at the TOP level of the values is inert (the chart never reads it;
the sandbox ran that way for months), so that placement is itself a finding.

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
HOST_RX = re.compile(r"\b([a-z0-9][a-z0-9-]*)\.([a-z0-9-]+)\.svc\b")
SVC_RX = re.compile(r"\b([a-z0-9][a-z0-9-]*?)-(?:rw|ro|r)\.([a-z0-9-]+)\.svc\b")
IMAGE_RX = re.compile(r'"image":\s*"([^"]+)"')

# ADR-0325 staged rollout. Required anti-affinity changes the pod spec, so CNPG ROLLS the
# instances; applying it in the same sync that raises instances 1 -> 2 rolls the only primary in
# place before the new standby exists. A cluster whose standby is being added carries this
# annotation (with a reason) and is excused the affinity checks ONLY -- never instances >= 2 or
# switchover. It is stale the moment the affinity is present, so the excuse cannot outlive the
# wave that removes it. Valid on any cluster since rule 5 extended the affinity fleet-wide.
PENDING_ANN = "openbank.io/affinity-rollout-pending"

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


TEMPORAL_HA_ROLES = ("frontend", "history", "matching")
TEMPORAL_ROLES = TEMPORAL_HA_ROLES + ("worker", "internal-frontend")


def temporal_apps() -> list[tuple[str, dict]]:
    """(path, parsed helm values) for every ArgoCD Application installing the Temporal chart."""
    out = []
    for path in sorted(gatelib.rglob(REPO / GITOPS, "*.yaml")):
        try:
            docs = gatelib.load_yaml_all(path, errors="replace")
        except yaml.YAMLError:
            continue  # scan() already reports unparseable files
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "Application":
                continue
            src = (doc.get("spec") or {}).get("source") or {}
            if src.get("chart") != "temporal":
                continue
            helm = src.get("helm") or {}
            values = helm.get("valuesObject")
            if values is None:
                try:
                    values = yaml.safe_load(helm.get("values") or "") or {}
                except yaml.YAMLError as exc:
                    values = {"__error__": str(exc)}
            out.append((str(path.relative_to(REPO)), values if isinstance(values, dict) else {}))
    return out


def scan() -> tuple[dict[str, dict], dict[str, set], list[str], dict[str, set]]:
    """Return (clusters by ns/name, cluster refs by image, parse errors, called namespaces by image)."""
    clusters: dict[str, dict] = {}
    refs: dict[str, set] = {}
    calls: dict[str, set] = {}
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
                clusters[f"{ns}/{meta['name']}"] = {
                    "spec": doc.get("spec") or {},
                    "path": str(rel),
                    "pending": str((meta.get("annotations") or {}).get(PENDING_ANN, "")).strip(),
                }
            elif kind in WORKLOAD_KINDS:
                text = json.dumps(doc)
                ns = meta.get("namespace") or _ns_from_kustomization(path) or ""
                found = {f"{n}/{c}" for c, n in SVC_RX.findall(text)}
                called = {n for h, n in HOST_RX.findall(text) if not re.search(r"-(?:rw|ro|r)$", h)}
                for img in IMAGE_RX.findall(text):
                    calls.setdefault(_image_name(img), set()).update(called)
                    refs.setdefault(_image_name(img), set()).update(found)
                    refs[_image_name(img)].add(f"@{ns}")  # marker: workload exists
    return clusters, refs, errors, calls


def check() -> tuple[list[str], int]:
    clusters, refs, findings, calls = scan()
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

    # 2b. platform databases a money-path service depends on one hop away (explicit, kept honest)
    money_calls = set().union(*(calls.get(s, set()) for s in money)) if money else set()
    for i, entry in enumerate(rules.get("money_path_platform_databases") or []):
        key = (entry or {}).get("cluster") if isinstance(entry, dict) else None
        where = f"{RULES}: money_path_platform_databases[{i}]"
        if not key or not str((entry or {}).get("reason", "")).strip():
            findings.append(f"{where} needs both `cluster: <ns>/<name>` and a non-empty `reason`")
            continue
        if key not in clusters:
            findings.append(f"{where} is stale: no CNPG Cluster {key} in {GITOPS}")
            continue
        if key in mp_clusters:
            findings.append(f"{where} is stale: {key} is already derived from {mp_clusters[key]}")
            continue
        if key.split("/", 1)[0] not in money_calls:
            findings.append(
                f"{where} is stale: no money-path workload calls any Service in namespace"
                f" {key.split('/', 1)[0]}, so {key} is not on the money path"
            )
            continue
        mp_clusters[key] = "platform dependency"

    # 3. money-path: a second instance, on a second node, with its PDB
    def affinity(c: dict, where: str) -> None:
        aff = c["spec"].get("affinity") or {}
        aff_gaps = []
        if aff.get("enablePodAntiAffinity") is not True:
            aff_gaps.append("lacks affinity.enablePodAntiAffinity: true")
        if aff.get("topologyKey") != "kubernetes.io/hostname":
            aff_gaps.append("affinity.topologyKey must be kubernetes.io/hostname")
        if aff.get("podAntiAffinityType") != "required":
            aff_gaps.append("affinity.podAntiAffinityType must be `required`")
        if c["pending"]:
            if not aff_gaps:
                findings.append(f"{where} carries {PENDING_ANN} but already has the affinity -- remove the annotation")
            else:
                print(f"::notice::{where}: affinity deferred to a staged wave ({c['pending']})")
        else:
            findings.extend(f"{where} {g}" for g in aff_gaps)

    for key, svc in sorted(mp_clusters.items()):
        c = clusters[key]
        spec = c["spec"]
        where = f"{c['path']}: money-path Cluster {key} ({svc})"
        inst = spec.get("instances", 1)
        if not isinstance(inst, int) or inst < 2:
            findings.append(f"{where} has instances={inst}; ADR-0159 requires >= 2 (primary + standby)")
        affinity(c, where)
        if spec.get("enablePDB") is False:
            findings.append(f"{where} disables its PodDisruptionBudget (enablePDB: false)")

    # 4. EVERY cluster has a standby, money-path or not (2026-09-28: five spot interruptions in
    #    one day took down each single-instance primary on the reclaimed node, while every HA
    #    cluster failed over in seconds). A cluster may run one instance only if it is listed in
    #    rules.yaml: cnpg_single_instance_exceptions with a reason; a listing that no longer names
    #    a single-instance cluster is stale, so an exception cannot outlive its cause.
    exc: dict[str, str] = {}
    for i, entry in enumerate(rules.get("cnpg_single_instance_exceptions") or []):
        key = str((entry or {}).get("cluster", "")).strip()
        reason = str((entry or {}).get("reason", "")).strip()
        where = f"{RULES}: cnpg_single_instance_exceptions[{i}]"
        if not key or len(reason) < 20:
            findings.append(f"{where} needs `cluster: <ns>/<name>` and a reason of >= 20 chars")
            continue
        exc[key] = reason
    for key in sorted(exc):
        if key not in clusters:
            findings.append(f"{RULES}: cnpg_single_instance_exceptions names {key}, which is no CNPG Cluster -- stale")
        elif isinstance(clusters[key]["spec"].get("instances", 1), int) and clusters[key]["spec"].get("instances", 1) >= 2:
            findings.append(f"{RULES}: cnpg_single_instance_exceptions names {key}, which already runs >= 2 instances -- stale")
    for key, c in sorted(clusters.items()):
        if key in mp_clusters or key in exc:
            continue
        inst = c["spec"].get("instances", 1)
        if not isinstance(inst, int) or inst < 2:
            findings.append(
                f"{c['path']}: Cluster {key} has instances={inst}; every CNPG cluster needs >= 2 "
                f"(a standby survives node loss) or a reasoned entry in cnpg_single_instance_exceptions"
            )

    # 5. EVERY cluster with a standby keeps it on another node (required hostname anti-affinity).
    #    A single-instance exception has nothing to separate, so it is not checked here.
    for key, c in sorted(clusters.items()):
        if key in mp_clusters:
            continue
        inst = c["spec"].get("instances", 1)
        if isinstance(inst, int) and inst >= 2:
            affinity(c, f"{c['path']}: Cluster {key} (instances={inst})")

    # 6. the Temporal server in front of a money-path platform database is itself HA
    for path, values in temporal_apps():
        if "__error__" in values:
            findings.append(f"{path}: Temporal helm values do not parse ({values['__error__']})")
            continue
        text = json.dumps(values)
        backs = sorted({f"{n}/{c}" for c, n in SVC_RX.findall(text)} & set(mp_clusters))
        if not backs:
            continue
        where = f"{path}: Temporal server on money-path platform database {', '.join(backs)}"
        for role in TEMPORAL_ROLES:
            if role in values:
                findings.append(
                    f"{where} declares `{role}:` at the top level of its values -- the chart reads "
                    f"only `server.{role}`, so that block is inert"
                )
        server = values.get("server") or {}
        for role in TEMPORAL_HA_ROLES:
            rv = server.get(role) or {}
            reps = rv.get("replicaCount", server.get("replicaCount", 1))
            if not isinstance(reps, int) or reps < 2:
                findings.append(
                    f"{where}: server.{role}.replicaCount={reps}; a single pod empties the "
                    f"membership ring on every roll (Not enough hosts) -- needs >= 2"
                )
            if not rv.get("podDisruptionBudget"):
                findings.append(f"{where}: server.{role} has no podDisruptionBudget")

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
_PLATFORM = """money_path_platform_databases:
  - cluster: wf/wf-db
    reason: sagas
"""
_WF = """apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: wf-db
  namespace: wf
spec:
  instances: {inst}
{extra}"""
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
            - name: WF
              value: wf-frontend.wf.svc:7233
"""


_TEMPORAL = """apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: wf-helm
  namespace: argocd
spec:
  source:
    chart: temporal
    helm:
      values: |
        server:
          config:
            persistence:
              datastores:
                default:
                  sql:
                    connectAddr: "wf-db-rw.wf.svc.cluster.local:5432"
{roles}"""
_ROLE = """          {role}:
            replicaCount: {n}
            podDisruptionBudget:
              maxUnavailable: 1
"""
_ROLES_OK = "".join(_ROLE.format(role=r, n=2) for r in ("frontend", "history", "matching"))


def self_test() -> int:
    ok = True

    def run(files: dict[str, str], extra_rules: str = _PLATFORM) -> list[str]:
        global REPO
        with tempfile.TemporaryDirectory() as td:
            fake = Path(td)
            d = fake / GITOPS / "components" / "st"
            d.mkdir(parents=True)
            (fake / RULES).parent.mkdir(parents=True)
            (fake / RULES).write_text("money_path_services:\n  - openbank-pay-service\n" + extra_rules)
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
        + "---\n" + _CLUSTER.format(name="other-db", inst=2, extra=_HA),
        "deploy.yaml": _DEPLOY,
        "wf.yaml": _WF.format(inst=2, extra=_HA),
        "temporal.yaml": _TEMPORAL.format(roles=_ROLES_OK),
    }
    if run(good):
        print(f"SELF-TEST FAIL: a compliant corpus was reported: {run(good)}"); ok = False

    cases = {
        "single-instance money-path cluster (the card-issuance-db shape)": (
            {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=1, extra=_HA)}, "instances=1"),
        "restart update method on a non-money-path cluster": (
            {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra=_HA)
             + "---\n" + _CLUSTER.format(name="other-db", inst=2, extra="")}, "other-db primaryUpdateMethod=unset"),
        "money-path cluster without hostname anti-affinity": (
            {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra="  primaryUpdateMethod: switchover\n")},
            "enablePodAntiAffinity"),
        "money-path service whose workload vanished": (
            {"db.yaml": good["db.yaml"]}, "cannot be derived"),
        "pending annotation on a cluster that already has the affinity": (
            {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra=_HA).replace(
                "  namespace: st\n", "  namespace: st\n  annotations:\n    openbank.io/affinity-rollout-pending: wave\n", 1)},
            "already has the affinity"),
        "pending annotation does not excuse a single instance": (
            {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=1, extra="  primaryUpdateMethod: switchover\n").replace(
                "  namespace: st\n", "  namespace: st\n  annotations:\n    openbank.io/affinity-rollout-pending: wave\n", 1)},
            "instances=1"),
        "money-path cluster with PDB disabled": (
            {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra=_HA + "  enablePDB: false\n")},
            "enablePDB"),
    }
    deferred = {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra="  primaryUpdateMethod: switchover\n").replace(
        "  namespace: st\n", "  namespace: st\n  annotations:\n    openbank.io/affinity-rollout-pending: wave\n", 1)
        + "---\n" + _CLUSTER.format(name="other-db", inst=2, extra="  primaryUpdateMethod: switchover\n").replace(
        "  namespace: st\n", "  namespace: st\n  annotations:\n    openbank.io/affinity-rollout-pending: wave\n", 1)}
    if run(deferred):
        print(f"SELF-TEST FAIL: a deferred-affinity cluster was reported: {run(deferred)}"); ok = False
    cases["non-money-path HA cluster with only preferred anti-affinity"] = (
        {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra=_HA) + "---\n"
         + _CLUSTER.format(name="other-db", inst=2, extra=_HA.replace("required", "preferred"))},
        "st/other-db (instances=2) affinity.podAntiAffinityType must be `required`")
    cases["pending annotation on a non-money-path cluster that already has the affinity"] = (
        {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra=_HA) + "---\n"
         + _CLUSTER.format(name="other-db", inst=2, extra=_HA).replace(
             "  name: other-db\n", "  name: other-db\n  annotations:\n    openbank.io/affinity-rollout-pending: wave\n", 1)},
        "st/other-db (instances=2) carries")
    cases["single-instance platform database (the temporal-db shape)"] = (
        {**good, "wf.yaml": _WF.format(inst=1, extra=_HA)}, "wf/wf-db (platform dependency) has instances=1")
    cases["platform entry naming a namespace no money-path workload calls"] = (
        {**good, "deploy.yaml": _DEPLOY.replace("wf-frontend.wf.svc", "wf-frontend.elsewhere.svc")},
        "no money-path workload calls any Service in namespace wf")
    cases["platform entry naming a cluster that does not exist"] = (
        {k: v for k, v in good.items() if k != "wf.yaml"}, "no CNPG Cluster wf/wf-db")
    single_other = {**good, "db.yaml": _CLUSTER.format(name="pay-db", inst=2, extra=_HA)
                    + "---\n" + _CLUSTER.format(name="other-db", inst=1, extra="  primaryUpdateMethod: switchover\n")}
    cases["single-instance non-money-path cluster (the audit-db shape)"] = (single_other, "st/other-db has instances=1")
    exc_ok = _PLATFORM + "cnpg_single_instance_exceptions:\n  - cluster: st/other-db\n    reason: scratch database, rebuilt from scratch on loss\n"
    if run(single_other, exc_ok):
        print(f"SELF-TEST FAIL: an excepted single-instance cluster was reported: {run(single_other, exc_ok)}"); ok = False
    exc_stale = _PLATFORM + "cnpg_single_instance_exceptions:\n  - cluster: st/other-db\n    reason: scratch database, rebuilt from scratch on loss\n"
    got = run(good, exc_stale)
    if not any("already runs >= 2 instances -- stale" in f for f in got):
        print(f"SELF-TEST FAIL: a stale exception (cluster now HA) was not reported: {got}"); ok = False
    exc_gone = _PLATFORM + "cnpg_single_instance_exceptions:\n  - cluster: st/ghost-db\n    reason: scratch database, rebuilt from scratch on loss\n"
    got = run(good, exc_gone)
    if not any("which is no CNPG Cluster -- stale" in f for f in got):
        print(f"SELF-TEST FAIL: an exception naming no cluster was not reported: {got}"); ok = False
    exc_short = _PLATFORM + "cnpg_single_instance_exceptions:\n  - cluster: st/other-db\n    reason: meh\n"
    got = run(single_other, exc_short)
    if not any("reason of >= 20 chars" in f for f in got):
        print(f"SELF-TEST FAIL: an exception without a real reason was accepted: {got}"); ok = False
    cases["single-replica Temporal matching in front of a platform database"] = (
        {**good, "temporal.yaml": _TEMPORAL.format(roles=_ROLES_OK.replace(
            "matching:\n            replicaCount: 2", "matching:\n            replicaCount: 1"))},
        "server.matching.replicaCount=1")
    cases["Temporal role without a PDB"] = (
        {**good, "temporal.yaml": _TEMPORAL.format(
            roles=_ROLE.format(role="frontend", n=2) + _ROLE.format(role="history", n=2)
            + "          matching:\n            replicaCount: 2\n")},
        "server.matching has no podDisruptionBudget")
    cases["Temporal role block at the top level (the inert sandbox shape)"] = (
        {**good, "temporal.yaml": _TEMPORAL.format(roles=_ROLES_OK) + "        history:\n          replicaCount: 3\n"},
        "declares `history:` at the top level")
    for label, (files, needle) in cases.items():
        got = run(files)
        if not any(needle in f for f in got):
            print(f"SELF-TEST FAIL: {label} not reported (wanted '{needle}', got {got})"); ok = False
    print("SELF-TEST", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
