#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Money-path databases (and temporal-db) cannot be scheduled onto spot capacity.

THE DEFECT THIS EXISTS FOR (2026-09-30, #11608)
----------------------------------------------
The Karpenter `default` NodePool is spot, and 134 of the 140 CNPG instance pods ran on it:
41 spot interruptions in 24h meant ~13 Postgres failovers per interruption. The owner's scope
(2026-09-30) is the CNPG clusters backing `rules.yaml: money_path_services` plus
temporal/temporal-db; every other cluster, Kafka and the Temporal server stay on spot. The
guarantee is three artefacts in two trees that only work TOGETHER:

  1. aws/envs/sandbox-platform/main.tf `nodepool_stateful` -- on-demand only, tainted
     `openbank.io/stateful=true:NoSchedule`, weight > 0 so Karpenter prefers it;
  2. gitops/components/kyverno/stateful-on-demand-cel.yaml -- on pod CREATE, for a CNPG pod of a
     cluster in its GENERATED `targets` list, adds required node affinity
     `karpenter.sh/capacity-type In [on-demand]` and the toleration for (1);
  3. the target Clusters themselves -- none may carry its own required node affinity (the policy
     leaves those alone: OR'ed terms cannot be narrowed by appending one) or name `spot`.

THE TARGET SET IS DERIVED, NEVER LISTED BY HAND
For each service in money_path_services, the workload running that image and every
`<cluster>-rw|-ro|-r.<ns>.svc` it connects to -- the derivation check-cnpg-update-resilience.py
already owns (imported, not copied, so the two can never disagree about what "money-path
cluster" means). Plus temporal/temporal-db. FAIL CLOSED: a money-path service with no workload,
or whose workload names no CNPG cluster (and is not in that script's NO_DATABASE), is a finding
-- never a silently smaller set. The policy's `targets` block must equal the derived set EXACTLY:
missing entries leave a money-path DB on spot, extra ones put a non-money-path DB on on-demand
(the owner's cost decision). `--write` regenerates the block.

It also holds the pool's sizing input: the CNPG requests the target clusters declare must not
exceed `stateful_load_by_zone`, the measurement the derived NodePool limit is computed from.

WHAT IT DOES NOT PROVE, AND WHAT DOES
The CEL is checked here as TEXT. `--kyverno` runs the pinned Kyverno CLI (docker) over
openbank-infra/tests/kyverno-cel/stateful-resources.yaml and compares every fixture with its
`openbank.io/expect` annotation. The gates shard has no docker, so that half runs locally.

Usage:
    check-stateful-not-on-spot.py                # gate (exit 1 on any finding)
    check-stateful-not-on-spot.py --self-test    # prove the gate can fail
    check-stateful-not-on-spot.py --write        # regenerate the policy's targets block
    check-stateful-not-on-spot.py --list-targets # print the derived set (ns/cluster per line)
    check-stateful-not-on-spot.py --kyverno      # behavioural check via the Kyverno CLI (docker)
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import gatelib

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
TF = Path("openbank-infra/aws/envs/sandbox-platform/main.tf")
POLICY = Path("openbank-infra/gitops/components/kyverno/stateful-on-demand-cel.yaml")
FIXTURES = Path("openbank-infra/tests/kyverno-cel/stateful-resources.yaml")
KYVERNO_CLI = "ghcr.io/kyverno/kyverno-cli:v1.19.1"  # the cluster's Kyverno version
EXTRA_TARGETS = ("temporal/temporal-db",)  # owner decision 2026-09-30: every saga's state

_spec = importlib.util.spec_from_file_location("cnpg_resilience", HERE / "check-cnpg-update-resilience.py")
RES = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(RES)

CAPTYPE_RX = re.compile(r'"karpenter\.sh/capacity-type",\s*operator\s*=\s*"In",\s*values\s*=\s*\[([^\]]*)\]')
TAINT_RX = re.compile(r'taints\s*=\s*\[\s*\{\s*key\s*=\s*"([^"]+)",\s*value\s*=\s*"([^"]+)",\s*effect\s*=\s*"([^"]+)"')
WEIGHT_RX = re.compile(r"\bweight\s*=\s*(\d+)")
LOAD_RX = re.compile(r'"([a-z0-9-]+)"\s*=\s*\{\s*cpu\s*=\s*([0-9.]+),\s*memory_gib\s*=\s*([0-9.]+),\s*pods\s*=\s*(\d+)\s*\}')
AFFINITY_RX = re.compile(
    r'key:\s*"karpenter\.sh/capacity-type",\s*operator:\s*"In",\s*values:\s*\[\s*"on-demand"\s*\]')
TOLERATION_RX = re.compile(
    r'Object\.spec\.tolerations\{key:\s*"([^"]+)",\s*operator:\s*"Equal",\s*value:\s*"([^"]+)",\s*effect:\s*"([^"]+)"\}')
BLOCK_RX = re.compile(
    r"(    # BEGIN GENERATED targets[^\n]*\n)(.*?)(    # END GENERATED targets\n)", re.S)
ENTRY_RX = re.compile(r"'([a-z0-9-]+/[a-z0-9-]+)'")
SELECTOR_TOKENS = ("'cnpg.io/cluster'", "variables.targets", "request.namespace")


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
    out: list[str] = []
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "nodeAffinity" and isinstance(v, dict) and v.get("requiredDuringSchedulingIgnoredDuringExecution"):
                out.append(f"{where}: declares required nodeAffinity -- the policy cannot narrow OR'ed "
                           f"terms, so this money-path database is not routed to on-demand")
            if k in ("nodeSelector", "nodeAffinity", "affinity", "tolerations") and "spot" in json.dumps(v):
                out.append(f"{where}: `{k}` names spot capacity")
            out += _scheduling_findings(v, where)
    elif isinstance(node, list):
        for v in node:
            out += _scheduling_findings(v, where)
    return out


def derive() -> tuple[dict[str, str], dict[str, dict], list[str]]:
    """(target ns/cluster -> why, all clusters, findings). Fail closed on anything unresolved."""
    RES.REPO = REPO
    gatelib.clear()
    clusters, refs, findings, _ = RES.scan()
    rules = gatelib.load_yaml(REPO / RES.RULES) or {}
    money = list(rules.get("money_path_services") or [])
    if not money:
        findings.append(f"{RES.RULES}: money_path_services is empty or missing -- refusing to pass vacuously")
    targets: dict[str, str] = {}
    for svc in money:
        found = refs.get(svc)
        owned = sorted(k for k in (found or ()) if not k.startswith("@") and k in clusters)
        if svc in RES.NO_DATABASE:
            continue
        if found is None:
            findings.append(f"money-path service {svc}: no workload runs an image named {svc}, so its "
                            f"database cannot be resolved -- it would silently stay on spot")
        elif not owned:
            findings.append(f"money-path service {svc}: its workload connects to no declared CNPG cluster "
                            f"(<cluster>-rw.<ns>.svc) -- its database cannot be resolved")
        for k in owned:
            targets.setdefault(k, svc)
    for k in EXTRA_TARGETS:
        if k not in clusters:
            findings.append(f"{k}: listed as an on-demand target but no such CNPG Cluster exists in gitops")
        targets.setdefault(k, "platform: temporal")
    return targets, clusters, findings


def render_block(targets) -> str:
    body = "".join(f"          '{k}',\n" for k in sorted(targets))
    return ("    - name: targets\n      expression: >-\n        [\n" + body + "        ]\n")


def check() -> tuple[list[str], int]:
    targets, clusters, findings = derive()

    # (1) the NodePool
    tf_path = REPO / TF
    tf = tf_path.read_text() if tf_path.is_file() else ""
    pool = tf_block(tf, 'resource "kubectl_manifest" "nodepool_stateful"')
    taint = None
    if pool is None:
        findings.append(f"{TF}: no `nodepool_stateful` NodePool -- money-path databases have no on-demand pool")
    else:
        caps = CAPTYPE_RX.findall(pool)
        vals = re.findall(r'"([^"]+)"', caps[0]) if len(caps) == 1 else None
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
    raw = pol_path.read_text() if pol_path.is_file() else ""
    policy = gatelib.loads(raw) if raw else None
    if not isinstance(policy, dict) or policy.get("kind") != "MutatingPolicy":
        findings.append(f"{POLICY}: missing or not a MutatingPolicy -- nothing routes money-path databases")
    else:
        spec = policy.get("spec") or {}
        rules = (spec.get("matchConstraints") or {}).get("resourceRules") or []
        if not any("pods" in (r.get("resources") or []) and "CREATE" in (r.get("operations") or []) for r in rules):
            findings.append(f"{POLICY}: does not match pod CREATE")
        variables = {v.get("name"): v.get("expression", "") for v in spec.get("variables") or []}
        for tok in SELECTOR_TOKENS:
            if tok not in variables.get("isStateful", ""):
                findings.append(f"{POLICY}: isStateful no longer keys on {tok}")
        if not any("variables.isStateful" in (c.get("expression") or "") for c in spec.get("matchConditions") or []):
            findings.append(f"{POLICY}: no matchCondition on variables.isStateful")
        listed = set(ENTRY_RX.findall(variables.get("targets", "")))
        if not BLOCK_RX.search(raw):
            findings.append(f"{POLICY}: the GENERATED targets block markers are gone")
        for k in sorted(set(targets) - listed):
            findings.append(f"{POLICY}: money-path database {k} ({targets[k]}) is NOT in `targets` -- it can "
                            f"land on spot; run check-stateful-not-on-spot.py --write")
        for k in sorted(listed - set(targets)):
            findings.append(f"{POLICY}: {k} is in `targets` but is not a money-path database or temporal-db "
                            f"-- it would be put on on-demand; run --write")
        body = variables.get("onDemand", "") + "\n" + "\n".join(
            ((mu.get("jsonPatch") or {}).get("expression") or "") for mu in spec.get("mutations") or [])
        if not AFFINITY_RX.search(body):
            findings.append(f"{POLICY}: no required affinity `karpenter.sh/capacity-type In [\"on-demand\"]`")
        tol = TOLERATION_RX.search(body)
        if not tol:
            findings.append(f"{POLICY}: adds no toleration")
        else:
            if taint and (tol.group(1), tol.group(2)) != taint:
                findings.append(f"{POLICY}: tolerates {tol.group(1)}={tol.group(2)} but the stateful pool is "
                                f"tainted {taint[0]}={taint[1]} -- routed pods could never schedule there")
            if tol.group(3) != "NoSchedule":
                findings.append(f"{POLICY}: toleration effect {tol.group(3)} does not match the NoSchedule taint")

    # (3) the target clusters, and the sizing input
    cpu = mem = 0.0
    for k in sorted(targets):
        c = clusters.get(k)
        if not c:
            continue
        s = c["spec"]
        n = int(s.get("instances", 1) or 1)
        rq = (s.get("resources") or {}).get("requests") or {}
        cpu += _cpu(rq.get("cpu", 0)) * n
        mem += _gib(rq.get("memory", 0)) * n
        findings += _scheduling_findings(s, f"{c['path']}: Cluster {k}")
    load = LOAD_RX.findall(tf_block(tf, "stateful_load_by_zone = ") or "")
    if not load:
        findings.append(f"{TF}: no stateful_load_by_zone measurement -- the stateful pool limit is not derived")
    else:
        t_cpu = sum(float(x[1]) for x in load)
        t_mem = sum(float(x[2]) for x in load)
        if cpu > t_cpu * 1.05 or mem > t_mem * 1.05:
            findings.append(
                f"{TF}: the {len(targets)} target clusters now declare {cpu:.2f} vCPU / {mem:.2f} GiB, more than "
                f"the {t_cpu:.2f} / {t_mem:.2f} stateful_load_by_zone was measured at -- re-measure it so the "
                f"derived `stateful` NodePool limit still covers them")

    gatelib.subjects(len(targets), f"money-path CNPG clusters + temporal-db routed to on-demand (of {len(clusters)})")
    return findings, len(targets)


def write() -> int:
    targets, _, findings = derive()
    if findings:
        for f in findings:
            print(f"::error::{f}")
        print("refusing to write a targets list derived from an unresolved set")
        return 1
    p = REPO / POLICY
    raw = p.read_text()
    new = BLOCK_RX.sub(lambda m: m.group(1) + render_block(targets) + m.group(3), raw, count=1)
    if new == raw and not BLOCK_RX.search(raw):
        print(f"::error::{POLICY}: GENERATED markers not found")
        return 1
    p.write_text(new)
    print(f"wrote {len(targets)} targets to {POLICY}")
    return 0


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
    g = ap.add_mutually_exclusive_group()
    for flag in ("--self-test", "--kyverno", "--write", "--list-targets"):
        g.add_argument(flag, action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.kyverno:
        return kyverno()
    if args.write:
        return write()
    if args.list_targets:
        targets, _, findings = derive()
        for f in findings:
            print(f"::error::{f}", file=sys.stderr)
        for k in sorted(targets, key=lambda k: (k == "temporal/temporal-db", k)):
            print(k)
        return 1 if findings else 0
    findings, _ = check()
    for f in findings:
        print(f"::error::{f}")
    if findings:
        print(f"FAIL: {len(findings)} finding(s)")
        return 1
    print("OK: every money-path database and temporal-db is routed to on-demand; nothing else is")
    return 0


_CLUSTER = ("apiVersion: postgresql.cnpg.io/v1\nkind: Cluster\nmetadata:\n  name: {name}\n  namespace: {ns}\n"
            "spec:\n  instances: 2\n  resources:\n    requests:\n      cpu: {cpu}\n      memory: 256Mi\n{extra}")
_DEPLOY = ("apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: pay\n  namespace: st\nspec:\n  template:\n"
           "    spec:\n      containers:\n        - name: app\n          image: r.example/openbank-pay-service:1\n"
           "          env:\n            - name: JDBC\n              value: jdbc:postgresql://{db}-rw.st.svc:5432/pay\n")


def self_test() -> int:
    """Run check() over a synthetic fleet (one money-path service, its DB, a non-money-path DB,
    temporal-db) with the REAL NodePool and policy files, their targets block regenerated for the
    synthetic set. The unmutated corpus must pass; every mutation must produce the named finding."""
    global REPO
    real = REPO
    tf0 = (real / TF).read_text()
    pol_real = (real / POLICY).read_text()
    pol0 = BLOCK_RX.sub(lambda m: m.group(1) + render_block(["st/pay-db", "temporal/temporal-db"]) + m.group(3),
                        pol_real, count=1)
    pool = tf_block(tf0, 'resource "kubectl_manifest" "nodepool_stateful"') or ""

    def fleet(pay_extra="", pay_cpu="100m", db="pay-db", money="openbank-pay-service"):
        return {
            "openbank-libs/governance/rules.yaml": f"money_path_services:\n  - {money}\n",
            "openbank-infra/gitops/components/st/db.yaml":
                _CLUSTER.format(name="pay-db", ns="st", cpu=pay_cpu, extra=pay_extra)
                + "---\n" + _CLUSTER.format(name="aml-db", ns="st", cpu="100m", extra=""),
            "openbank-infra/gitops/components/st/deploy.yaml": _DEPLOY.format(db=db),
            "openbank-infra/gitops/components/temporal/db.yaml":
                _CLUSTER.format(name="temporal-db", ns="temporal", cpu="100m", extra=""),
        }

    def run(tf, pol, files) -> list[str]:
        global REPO
        with tempfile.TemporaryDirectory() as td:
            fake = Path(td)
            for rel, body in {**files, str(TF): tf, str(POLICY): pol}.items():
                if body is None:
                    continue
                (fake / rel).parent.mkdir(parents=True, exist_ok=True)
                (fake / rel).write_text(body)
            REPO = fake
            try:
                return check()[0]
            finally:
                REPO = real
                gatelib.clear()

    ok = True
    base = run(tf0, pol0, fleet())
    if base:
        print(f"SELF-TEST FAIL: the control corpus was reported: {base}")
        ok = False
    extra_row = "'st/pay-db',\n          'st/aml-db',"
    cases = {
        "money-path cluster removed from the targets list": (
            tf0, pol0.replace("          'st/pay-db',\n", ""), fleet(), "st/pay-db (openbank-pay-service) is NOT in `targets`"),
        "temporal-db removed from the targets list": (
            tf0, pol0.replace("          'temporal/temporal-db',\n", ""), fleet(), "temporal/temporal-db (platform: temporal) is NOT"),
        "non-money-path cluster added to the targets list": (
            tf0, pol0.replace("'st/pay-db',", extra_row), fleet(), "st/aml-db is in `targets` but is not"),
        "money-path service whose database cannot be resolved (fail closed)": (
            tf0, pol0, fleet(db="nowhere"), "its database cannot be resolved"),
        "spot re-added to the stateful pool": (
            tf0.replace(pool, CAPTYPE_RX.sub('"karpenter.sh/capacity-type", operator = "In", values = ["spot", "on-demand"]', pool)),
            pol0, fleet(), "must be exactly"),
        "stateful NodePool deleted": (tf0.replace('"nodepool_stateful"', '"nodepool_gone"'), pol0, fleet(), "no `nodepool_stateful`"),
        "stateful pool weight removed": (re.sub(r"\n\s*weight = 100", "", tf0), pol0, fleet(), "positive `weight`"),
        "routing policy deleted": (tf0, None, fleet(), "missing or not a MutatingPolicy"),
        "selector no longer consults the targets list": (
            tf0, pol0.replace("in variables.targets", "in ['x/y']"), fleet(), "no longer keys on variables.targets"),
        "on-demand retyped to spot in the affinity": (tf0, pol0.replace('values: ["on-demand"]', 'values: ["spot"]'), fleet(), "no required affinity"),
        "taint renamed without the toleration": (
            tf0.replace('key = "openbank.io/stateful", value = "true"', 'key = "openbank.io/db", value = "true"'),
            pol0, fleet(), "routed pods could never schedule"),
        "money-path Cluster with its own required nodeAffinity": (
            tf0, pol0, fleet(pay_extra="  affinity:\n    nodeAffinity:\n      requiredDuringSchedulingIgnoredDuringExecution:\n"
                                       "        nodeSelectorTerms: [{matchExpressions: [{key: a, operator: Exists}]}]\n"),
            "required nodeAffinity"),
        "money-path Cluster pinned to spot": (
            tf0, pol0, fleet(pay_extra="  affinity:\n    nodeSelector:\n      karpenter.sh/capacity-type: spot\n"), "names spot"),
        "declared target load outgrew the measurement": (tf0, pol0, fleet(pay_cpu="40"), "re-measure"),
    }
    # The control must NOT flag a non-money-path Cluster that pins itself to spot: out of scope.
    ctrl = run(tf0, pol0, {**fleet(), "openbank-infra/gitops/components/st/db.yaml":
                           _CLUSTER.format(name="pay-db", ns="st", cpu="100m", extra="")
                           + "---\n" + _CLUSTER.format(name="aml-db", ns="st", cpu="100m",
                                                       extra="  affinity:\n    nodeSelector:\n      karpenter.sh/capacity-type: spot\n")})
    if ctrl:
        print(f"SELF-TEST FAIL: a non-money-path cluster on spot was reported, it is out of scope: {ctrl}")
        ok = False
    else:
        print("self-test ok: a non-money-path cluster on spot is not required to move")
    for name, (tf, pol, files, want) in cases.items():
        if tf == tf0 and pol == pol0 and files == fleet():
            print(f"SELF-TEST FAIL: {name}: the mutation changed nothing (the artefact changed shape)")
            ok = False
            continue
        got = run(tf, pol, files)
        if not any(want in g for g in got):
            print(f"SELF-TEST FAIL: {name}: expected a finding containing {want!r}, got {got}")
            ok = False
        else:
            print(f"self-test ok: {name}")
    print("SELF-TEST PASS" if ok else "SELF-TEST FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
