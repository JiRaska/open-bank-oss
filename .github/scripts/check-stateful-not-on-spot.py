#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""No stateful workload (CNPG, Kafka, Temporal server) can be scheduled onto spot capacity.

THE DEFECT THIS EXISTS FOR (2026-09-30)
--------------------------------------
The Karpenter `default` NodePool is spot, and 134 of the 140 CNPG instance pods ran on it.
41 spot interruptions in 24h meant ~13 Postgres failovers per interruption, plus Temporal and
Kafka restarts. The fix is three artefacts in two trees that only work TOGETHER:

  1. aws/envs/sandbox-platform/main.tf  `nodepool_stateful` -- on-demand only, tainted
     `openbank.io/stateful=true:NoSchedule`, weight > 0 so Karpenter prefers it;
  2. gitops/components/kyverno/stateful-on-demand-cel.yaml -- on pod CREATE adds required
     node affinity `karpenter.sh/capacity-type In [on-demand]` and the toleration for (1);
  3. the stateful manifests themselves -- none may carry its own required node affinity (the
     policy leaves those alone, since OR'ed terms cannot be narrowed by appending one) or name
     `spot` anywhere in its scheduling constraints.

Each is a one-line edit away from silently re-admitting spot: `spot` added back to (1)'s
capacity-type, the taint renamed in (1) but not the toleration in (2), a selector dropped from
(2)'s match expression, or `on-demand` retyped. None of those fails anything else in CI.

It also holds the `stateful` pool's sizing input to the fleet: `stateful_load_by_zone` in (1)
is a measurement, and the CNPG requests declared in gitops must not exceed it -- when they do,
the derived NodePool limit is sized for a fleet that no longer exists (re-measure, see (1)).

WHAT IT DOES NOT PROVE, AND WHAT DOES
The CEL is checked here as TEXT. Its behaviour -- that the Kyverno 1.19.1 engine really
produces the affinity and toleration, and leaves non-stateful pods alone -- is proven by
`--kyverno`, which runs the pinned Kyverno CLI (docker) over
openbank-infra/tests/kyverno-cel/stateful-resources.yaml and compares every fixture with its
`openbank.io/expect` annotation. The gates shard has no docker, so that half runs locally and
its result is recorded in the PR; the text checks here keep the reviewed CEL from drifting.

Usage:
    check-stateful-not-on-spot.py              # gate (exit 1 on any finding)
    check-stateful-not-on-spot.py --self-test  # prove the gate can fail
    check-stateful-not-on-spot.py --kyverno    # behavioural check via the Kyverno CLI (docker)
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import gatelib

REPO = Path(__file__).resolve().parents[2]
TF = Path("openbank-infra/aws/envs/sandbox-platform/main.tf")
POLICY = Path("openbank-infra/gitops/components/kyverno/stateful-on-demand-cel.yaml")
GITOPS = Path("openbank-infra/gitops")
FIXTURES = Path("openbank-infra/tests/kyverno-cel/stateful-resources.yaml")
KYVERNO_CLI = "ghcr.io/kyverno/kyverno-cli:v1.19.1"  # the cluster's Kyverno version
SKIP_PATH_PARTS = ("dr-restore-templates",)

CAPTYPE_RX = re.compile(r'"karpenter\.sh/capacity-type",\s*operator\s*=\s*"In",\s*values\s*=\s*\[([^\]]*)\]')
TAINT_RX = re.compile(r'taints\s*=\s*\[\s*\{\s*key\s*=\s*"([^"]+)",\s*value\s*=\s*"([^"]+)",\s*effect\s*=\s*"([^"]+)"')
WEIGHT_RX = re.compile(r"\bweight\s*=\s*(\d+)")
LOAD_RX = re.compile(r'"([a-z0-9-]+)"\s*=\s*\{\s*cpu\s*=\s*([0-9.]+),\s*memory_gib\s*=\s*([0-9.]+),\s*pods\s*=\s*(\d+)\s*\}')
AFFINITY_RX = re.compile(
    r'key:\s*"karpenter\.sh/capacity-type",\s*operator:\s*"In",\s*values:\s*\[\s*"on-demand"\s*\]')
TOLERATION_RX = re.compile(
    r'Object\.spec\.tolerations\{key:\s*"([^"]+)",\s*operator:\s*"Equal",\s*value:\s*"([^"]+)",\s*effect:\s*"([^"]+)"\}')
SELECTORS = {
    "CNPG pods": "'cnpg.io/cluster'",
    "Strimzi broker/controller pods": "'strimzipodset'",
    "Temporal server roles": "'temporal'",
}


def tf_block(text: str, header: str) -> str | None:
    i = text.find(header)
    if i < 0:
        return None
    depth, j = 0, text.index("{", i)
    for k in range(j, len(text)):
        depth += {"{": 1, "}": -1}.get(text[k], 0)
        if depth == 0:
            return text[j:k + 1]
    return None


def _cpu(v) -> float:
    v = str(v)
    return float(v[:-1]) / 1000 if v.endswith("m") else float(v)


def _gib(v) -> float:
    v = str(v)
    for suf, f in (("Ki", 1 / 1024**2), ("Mi", 1 / 1024), ("Gi", 1.0), ("Ti", 1024.0)):
        if v.endswith(suf):
            return float(v[:-2]) * f
    return float(v) / 1024**3


def _scheduling_findings(node, where: str) -> list[str]:
    """Required node affinity, or any `spot` value, anywhere under a scheduling constraint."""
    out: list[str] = []
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "nodeAffinity" and isinstance(v, dict) and v.get("requiredDuringSchedulingIgnoredDuringExecution"):
                out.append(f"{where}: declares required nodeAffinity -- the policy cannot narrow OR'ed "
                           f"terms, so this workload is not routed to on-demand; use a preferred term")
            if k in ("nodeSelector", "nodeAffinity", "affinity", "tolerations") and "spot" in json.dumps(v):
                out.append(f"{where}: `{k}` names spot capacity")
            out += _scheduling_findings(v, where)
    elif isinstance(node, list):
        for v in node:
            out += _scheduling_findings(v, where)
    return out


def check() -> tuple[list[str], int]:
    findings: list[str] = []

    # (1) the NodePool
    tf_path = REPO / TF
    tf = tf_path.read_text() if tf_path.is_file() else ""
    pool = tf_block(tf, 'resource "kubectl_manifest" "nodepool_stateful"')
    taint = None
    if pool is None:
        findings.append(f"{TF}: no `nodepool_stateful` NodePool -- stateful pods have no on-demand pool")
    else:
        caps = CAPTYPE_RX.findall(pool)
        if len(caps) != 1:
            findings.append(f"{TF}: nodepool_stateful must state exactly one capacity-type requirement, found {len(caps)}")
        else:
            vals = re.findall(r'"([^"]+)"', caps[0])
            if vals != ["on-demand"]:
                findings.append(f"{TF}: nodepool_stateful capacity-type is {vals} -- must be exactly [\"on-demand\"]")
        m = TAINT_RX.search(pool)
        if not m or m.group(3) != "NoSchedule":
            findings.append(f"{TF}: nodepool_stateful has no NoSchedule taint -- any pod could land there")
        else:
            taint = (m.group(1), m.group(2))
        w = WEIGHT_RX.search(pool)
        if not w or int(w.group(1)) <= 0:
            findings.append(f"{TF}: nodepool_stateful has no positive `weight` -- Karpenter may pick the "
                            f"`default` pool's on-demand offering instead of it")

    # (2) the policy
    pol_path = REPO / POLICY
    policy = gatelib.load_yaml(pol_path) if pol_path.is_file() else None
    if not isinstance(policy, dict) or policy.get("kind") != "MutatingPolicy":
        findings.append(f"{POLICY}: missing or not a MutatingPolicy -- nothing routes stateful pods")
    else:
        spec = policy.get("spec") or {}
        rules = (spec.get("matchConstraints") or {}).get("resourceRules") or []
        if not any("pods" in (r.get("resources") or []) and "CREATE" in (r.get("operations") or []) for r in rules):
            findings.append(f"{POLICY}: does not match pod CREATE")
        variables = {v.get("name"): v.get("expression", "") for v in spec.get("variables") or []}
        sel = variables.get("isStateful", "")
        for what, token in SELECTORS.items():
            if token not in sel:
                findings.append(f"{POLICY}: isStateful no longer selects {what} ({token} absent)")
        if not any("variables.isStateful" in (c.get("expression") or "") for c in spec.get("matchConditions") or []):
            findings.append(f"{POLICY}: no matchCondition on variables.isStateful")
        body = variables.get("onDemand", "") + "\n" + "\n".join(
            ((mu.get("jsonPatch") or {}).get("expression") or "") for mu in spec.get("mutations") or [])
        if not AFFINITY_RX.search(body.replace('\\"', '"')):
            findings.append(f"{POLICY}: no required affinity `karpenter.sh/capacity-type In [\"on-demand\"]`")
        tol = TOLERATION_RX.search(body)
        if not tol:
            findings.append(f"{POLICY}: adds no toleration")
        elif taint and (tol.group(1), tol.group(2)) != taint:
            findings.append(f"{POLICY}: tolerates {tol.group(1)}={tol.group(2)} but the stateful pool is tainted "
                            f"{taint[0]}={taint[1]} -- routed pods could never schedule there")
        if tol and tol.group(3) != "NoSchedule":
            findings.append(f"{POLICY}: toleration effect {tol.group(3)} does not match the NoSchedule taint")

    # (3) the stateful manifests, and the sizing input
    clusters = 0
    cpu = mem = 0.0
    for f in gatelib.rglob(REPO / GITOPS, "*.yaml"):
        if any(p in f.parts for p in SKIP_PATH_PARTS):
            continue
        for d in gatelib.load_yaml_all(f, errors="replace") or []:
            if not isinstance(d, dict):
                continue
            api, kind = str(d.get("apiVersion", "")), d.get("kind")
            rel = f.relative_to(REPO)
            name = (d.get("metadata") or {}).get("name")
            if api.startswith("postgresql.cnpg.io") and kind == "Cluster":
                clusters += 1
                s = d.get("spec") or {}
                n = int(s.get("instances", 1) or 1)
                rq = (s.get("resources") or {}).get("requests") or {}
                cpu += _cpu(rq.get("cpu", 0)) * n
                mem += _gib(rq.get("memory", 0)) * n
                findings += _scheduling_findings(s, f"{rel}: Cluster/{name}")
            elif api.startswith("kafka.strimzi.io") and kind in ("Kafka", "KafkaNodePool"):
                findings += _scheduling_findings(d.get("spec") or {}, f"{rel}: {kind}/{name}")
            elif kind == "Application" and ((d.get("spec") or {}).get("source") or {}).get("chart") == "temporal":
                findings += _scheduling_findings(d.get("spec") or {}, f"{rel}: Application/{name}")

    load = LOAD_RX.findall(tf_block(tf, "stateful_load_by_zone = ") or "")
    if not load:
        findings.append(f"{TF}: no stateful_load_by_zone measurement -- the stateful pool limit is not derived")
    else:
        t_cpu = sum(float(c) for _, c, _, _ in load)
        t_mem = sum(float(m) for _, _, m, _ in load)
        # 5% slack: the table is a live measurement (it also carries Temporal/Kafka), the
        # comparison is against declared CNPG requests only.
        if cpu > t_cpu * 1.05 or mem > t_mem * 1.05:
            findings.append(
                f"{TF}: gitops now declares {cpu:.2f} vCPU / {mem:.2f} GiB of CNPG requests, more than the "
                f"{t_cpu:.2f} / {t_mem:.2f} stateful_load_by_zone was measured at -- re-measure it so the "
                f"derived `stateful` NodePool limit covers the fleet")

    gatelib.subjects(clusters, "CNPG clusters (+ the stateful NodePool and the routing policy)")
    return findings, clusters


def kyverno() -> int:
    if not shutil.which("docker"):
        print("::error::--kyverno needs docker; refusing to report a behavioural result it did not measure")
        return 2
    root = REPO / "openbank-infra"
    cmd = ["docker", "run", "--rm", "-v", f"{root}:/w", "-w", "/w", KYVERNO_CLI, "apply",
           str(POLICY.relative_to("openbank-infra")), "-r", str(FIXTURES.relative_to("openbank-infra"))]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    if "panic:" in out or "\nerror" in out.lower():
        print(out[-3000:])
        print("FAIL: the Kyverno CLI errored on the policy")
        return 1
    expect = {}
    for d in gatelib.load_yaml_all(REPO / FIXTURES):
        md = d["metadata"]
        expect[f"{md['namespace']}/Pod/{md['name']}"] = md["annotations"]["openbank.io/expect"]
    got: dict[str, str] = {}
    for block in re.split(r"^policy \S+ applied to ", out, flags=re.M)[1:]:
        res, _, body = block.partition(":\n")
        doc = gatelib.loads(body.split("\n---")[0])
        sp = (doc or {}).get("spec") or {}
        req = json.dumps(((sp.get("affinity") or {}).get("nodeAffinity") or {}).get(
            "requiredDuringSchedulingIgnoredDuringExecution") or {})
        routed = '"on-demand"' in req and "karpenter.sh/capacity-type" in req and any(
            t.get("key") == "openbank.io/stateful" for t in sp.get("tolerations") or [])
        got[res.strip()] = "on-demand" if routed else "untouched"
    bad = [f"{r}: expected {e}, got {got.get(r, 'NOT EVALUATED')}" for r, e in expect.items() if got.get(r) != e]
    for b in bad:
        print(f"::error::{b}")
    print(f"{len(expect) - len(bad)}/{len(expect)} fixtures behave as expected under {KYVERNO_CLI}")
    return 1 if bad or not expect else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--kyverno", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.kyverno:
        return kyverno()
    findings, _ = check()
    for f in findings:
        print(f"::error::{f}")
    if findings:
        print(f"FAIL: {len(findings)} finding(s)")
        return 1
    print("OK: stateful workloads are routed to on-demand and no stateful manifest re-admits spot")
    return 0


def self_test() -> int:
    """Run check() over a copy of the three real artefacts, then over one mutation per way the
    guarantee can break. The unmutated copy must pass (so the cases below fail for their stated
    reason), and every mutation must produce the named finding."""
    global REPO
    real = REPO
    cluster = ("apiVersion: postgresql.cnpg.io/v1\nkind: Cluster\nmetadata:\n  name: pay-db\nspec:\n"
               "  instances: 2\n  resources:\n    requests:\n      cpu: 100m\n      memory: 256Mi\n")
    tf0, pol0 = (real / TF).read_text(), (real / POLICY).read_text()

    def run(tf: str | None, pol: str | None, extra: str = "") -> list[str]:
        global REPO
        with tempfile.TemporaryDirectory() as td:
            fake = Path(td)
            for p, body in ((TF, tf), (POLICY, pol)):
                if body is not None:
                    (fake / p).parent.mkdir(parents=True, exist_ok=True)
                    (fake / p).write_text(body)
            d = fake / GITOPS / "components" / "st"
            d.mkdir(parents=True, exist_ok=True)
            (d / "db.yaml").write_text(cluster + extra)
            REPO = fake
            gatelib.clear()
            try:
                return check()[0]
            finally:
                REPO = real
                gatelib.clear()

    ok = True
    base = run(tf0, pol0)
    if base:
        print(f"SELF-TEST FAIL: the real artefacts were reported: {base}")
        ok = False
    pool = tf_block(tf0, 'resource "kubectl_manifest" "nodepool_stateful"') or ""
    stateful_spot = tf0.replace(pool, CAPTYPE_RX.sub(
        '"karpenter.sh/capacity-type", operator = "In", values = ["spot", "on-demand"]', pool))
    cases = {
        "spot re-added to the stateful pool": (stateful_spot, pol0, "", "must be exactly"),
        "stateful NodePool deleted": (tf0.replace('"nodepool_stateful"', '"nodepool_gone"'), pol0, "", "no `nodepool_stateful`"),
        "stateful pool weight removed": (re.sub(r"\n\s*weight = 100", "", tf0), pol0, "", "positive `weight`"),
        "routing policy deleted": (tf0, None, "", "missing or not a MutatingPolicy"),
        "CNPG selector dropped": (tf0, pol0.replace("'cnpg.io/cluster' in variables.labels ||", ""), "", "CNPG pods"),
        "on-demand retyped to spot in the affinity": (tf0, pol0.replace('values: ["on-demand"]', 'values: ["spot"]'), "", "no required affinity"),
        "taint renamed without the toleration": (tf0.replace('key = "openbank.io/stateful", value = "true"', 'key = "openbank.io/db", value = "true"'),
                                                  pol0, "", "routed pods could never schedule"),
        "CNPG Cluster with its own required nodeAffinity": (
            tf0, pol0, "  affinity:\n    nodeAffinity:\n      requiredDuringSchedulingIgnoredDuringExecution:\n"
                       "        nodeSelectorTerms: [{matchExpressions: [{key: a, operator: Exists}]}]\n", "required nodeAffinity"),
        "CNPG Cluster pinned to spot": (tf0, pol0, "  affinity:\n    nodeSelector:\n      karpenter.sh/capacity-type: spot\n", "names spot"),
        "declared CNPG load outgrew the measurement": (
            tf0, pol0, "---\n" + cluster.replace("pay-db", "big-db").replace("100m", "40"), "re-measure"),
    }
    for name, (tf, pol, extra, want) in cases.items():
        # A mutation whose text replace matched nothing is the real artefact again, and would
        # "fail to be caught" for the wrong reason -- or, as a first draft of this loop did,
        # be skipped in silence. Either way it is a broken case, so it fails the self-test.
        if tf == tf0 and pol == pol0 and not extra:
            print(f"SELF-TEST FAIL: {name}: the mutation changed nothing (the artefact changed shape)")
            ok = False
            continue
        got = run(tf, pol, extra)
        if not any(want in g for g in got):
            print(f"SELF-TEST FAIL: {name}: expected a finding containing {want!r}, got {got}")
            ok = False
        else:
            print(f"self-test ok: {name}")
    print("SELF-TEST PASS" if ok else "SELF-TEST FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
