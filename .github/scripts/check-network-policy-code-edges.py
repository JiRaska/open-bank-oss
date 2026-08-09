#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Enumerate cross-namespace edges that live ONLY in service source, not in gitops env.

WHY THIS EXISTS (issue #2691, stage 1)
--------------------------------------
`openbank-infra/scripts/gen-network-policies.py` derives every ingress allow-list
from the URLs it can see in a workload's gitops `env:` block (plus Ingress
backends, monitors, HTTPScaledObjects, Kafka bootstrap URLs). A cross-namespace
URL that exists only as a Kotlin `@ConfigProperty(defaultValue = "http://x.y.svc:N")`
or only in a service's `src/main/resources/application.yaml` produces NO edge —
the generator never sees it.

Today that is mostly latent: the fleet has ZERO default-deny NetworkPolicies, and
the VPC CNI network-policy agent in standard mode leaves a pod unselected by any
policy fully reachable. It stops being latent the moment a default-deny baseline
lands (the stage-3 goal of #2691): every edge in this report that is not already
admitted by some other declaration becomes a silent DROP.

So this checker is the PREREQUISITE LIST for default-deny, derivable from the repo
with no cluster access. It classifies each code-only edge:

  ADMITTED  - the callee's generated allow-list already names the caller namespace
              on that port (some other caller declared the same edge in gitops, so
              the allow-list happens to cover it). Safe today, but FRAGILE: it
              survives only as long as that unrelated declaration does.
  DROPPED   - the callee POD is already selected by an ingress policy and no rule
              admits this caller. Not latent: the VPC CNI agent is dropping this
              flow in the cluster right now (ADR-0060, standard mode).
  LATENT    - the callee pod is selected by NO policy at all, so standard mode
              leaves it fully reachable. It breaks the day a default-deny baseline
              lands in that namespace — which is the whole point of #2691.
  NO-CALLEE - gitops declares no Service of that name at all. The URL cannot work
              under any policy; it is dead config, not a NetworkPolicy gap.

All three are the same repo-level defect for the ratchet; the split exists because
collapsing it produces the reassuring answer, in both directions. Under a flat
comparison `platform -> vllm.copilot:8000` reads as "already dropped" — it is in
fact a service that has never been deployed (the copilot namespace is empty), so
no policy change could ever fix it. Fix a real gap by declaring the URL in the
caller's gitops Deployment env and regenerating — never by hand-editing a derived
policy.

Enabling default-deny is explicitly out of scope for stage 1 (#2691). What this
gate DOES enforce is a RATCHET: the MISSING set may not grow. Today's entries are
baselined in KNOWN_MISSING with the reason each is there, so a NEW code-only edge
fails the gate at PR time instead of being discovered by a later audit — and an
entry that gets fixed must leave the baseline, so the list cannot quietly become
permanent (the `check-pact-provider-replay.py` shape).

WHAT IT CANNOT SEE (stated so the number is not read as exhaustive)
-------------------------------------------------------------------
  * a host built by string concatenation at runtime (`"http://" + svc + ".svc"`),
  * a URL supplied by a ConfigMap/Secret rather than an `env:` value or the
    service's own `application.yaml`,
  * egress to anything outside the cluster,
  * hand-written policies outside the generated files (they are counted, since
    the scan reads every NetworkPolicy under gitops, but a policy that admits a
    caller through a podSelector this checker cannot resolve is not modelled),
  * same-namespace calls (always allowed, deliberately not reported).
"""

import argparse
import glob
import os
import re
import sys
import tempfile
from collections import defaultdict

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
COMPONENTS = os.path.join(ROOT, "openbank-infra", "gitops", "components")

# Deliberately the SAME shape as gen-network-policies.py's URL_RE: a host without a
# literal `.svc` is not a cluster DNS name the generator would ever have matched
# either, so reporting one would be reporting a non-edge.
URL_RE = re.compile(r"https?://([a-z0-9-]+)\.([a-z0-9-]+)\.svc(?:\.cluster\.local)?(?::(\d+))?")

# `localhost` defaults are the sidecar/loopback convention (OPA on 8181, the
# document renderer sidecars); they are not cross-namespace edges at all.
NS_LABEL = "kubernetes.io/metadata.name"

WANTED_KINDS = ("Service", "NetworkPolicy", "Deployment", "StatefulSet", "Rollout")


def load_gitops():
    """Return (services, deploy_env_urls, netpols, selectors) read from gitops/components."""
    services = {}          # svc-name -> namespace
    selectors = {}         # (ns, svc-name) -> Service .spec.selector labels
    deploy_env_urls = defaultdict(set)   # caller-ns -> {(svc, ns, port)}
    netpols = []
    for path in sorted(glob.glob(f"{COMPONENTS}/**/*.yaml", recursive=True)):
        try:
            text = open(path, encoding="utf-8").read()
        except OSError:
            continue
        # Cheap pre-filter: the per-service OPA bundles are multi-hundred-KB
        # ConfigMaps and parsing them dominated the run (88s -> a few seconds).
        # This can only SKIP a file containing none of the kinds we read, so it
        # cannot hide a resource — the failure direction is a wasted parse.
        if not any(f"kind: {k}" in text for k in WANTED_KINDS):
            continue
        try:
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError:
            continue
        for doc in docs:
            if not isinstance(doc, dict) or not doc.get("kind"):
                continue
            meta = doc.get("metadata") or {}
            ns = meta.get("namespace")
            kind = doc["kind"]
            if kind == "Service" and ns and meta.get("name"):
                services[meta["name"]] = ns
                selectors[(ns, meta["name"])] = (doc.get("spec") or {}).get("selector") or {}
            elif kind == "NetworkPolicy" and ns:
                netpols.append(doc)
            elif kind in ("Deployment", "StatefulSet", "Rollout") and ns:
                tpl = (doc.get("spec") or {}).get("template") or {}
                blob = []
                for c in ((tpl.get("spec") or {}).get("containers") or []):
                    for e in (c.get("env") or []):
                        if isinstance(e.get("value"), str):
                            blob.append(e["value"])
                for svc, cns, port in URL_RE.findall("\n".join(blob)):
                    deploy_env_urls[ns].add((svc, cns, int(port) if port else None))
    return services, deploy_env_urls, netpols, selectors


def pod_is_selected(ns, svc_name, selectors, netpols):
    """True when SOME ingress policy in `ns` selects the pods behind `svc_name`.

    This is the difference between DROPPED and LATENT, and it is not cosmetic: under
    the VPC CNI agent in standard mode (ADR-0060) a pod that no policy selects is
    fully reachable, so an undeclared edge into it is working today. A comparison
    that skips this step calls that flow broken and sends the reader debugging a
    non-problem.
    """
    labels = selectors.get((ns, svc_name))
    if labels is None:
        return None  # unknown Service — refuse to guess
    for np in netpols:
        if (np.get("metadata") or {}).get("namespace") != ns:
            continue
        spec = np.get("spec") or {}
        if "Ingress" not in (spec.get("policyTypes") or ["Ingress"]):
            continue
        sel = (spec.get("podSelector") or {}).get("matchLabels") or {}
        if not (spec.get("podSelector") or {}):
            return True  # namespace-wide selector matches everything
        if all(labels.get(k) == v for k, v in sel.items()):
            return True
    return False


def admitted_edges(netpols):
    """(callee_ns, caller_ns, port) triples admitted by some NetworkPolicy.

    Port is read PER RULE alongside its own `from` — flattening the two loses the
    port dimension and turns a metrics-only rule into a blanket allow (the
    keycloak/observability :9000-vs-:8080 trap in openbank-infra/CLAUDE.md).
    A rule with no `ports` admits every port, recorded as port None.
    """
    out = set()
    for np in netpols:
        ns = (np.get("metadata") or {}).get("namespace")
        for rule in ((np.get("spec") or {}).get("ingress") or []):
            ports = {p.get("port") for p in (rule.get("ports") or [])} or {None}
            for src in (rule.get("from") or []):
                sel = ((src.get("namespaceSelector") or {}).get("matchLabels") or {})
                caller = sel.get(NS_LABEL)
                if not caller:
                    continue
                for p in ports:
                    out.add((ns, caller, p if isinstance(p, int) else None))
    return out


def module_namespace(module_dir, services):
    """gitops namespace of the service whose source lives in `module_dir`.

    The module directory and the k8s Service name agree in most of the fleet but
    not all of it: `openbank-security-scanner` is served by `security-scanner-service`.
    An unresolved module contributes NO edges, so a naive exact match reads as a
    clean bill of health for the module it silently skipped — which is precisely
    what it did for security-scanner's 27 code-only edges. Try the suffix variants.
    """
    name = os.path.basename(module_dir)
    if name.startswith("openbank-"):
        name = name[len("openbank-"):]
    for cand in (name, f"{name}-service", name.removesuffix("-service")):
        if cand in services:
            return services[cand]
    return None


# Build outputs contain COPIES of src/main/resources (and of the generated Quarkus
# config), so scanning them double-counts every edge and makes the run depend on
# whether the module happens to have been built — a gate whose result changes with
# the state of `build/` is not deterministic.
PRUNE_DIRS = {"build", ".gradle", "node_modules", "target", ".git"}
SOURCE_EXTS = (".kt", ".yaml", ".yml", ".properties")


def walk_sources(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in PRUNE_DIRS]
        for fn in filenames:
            if fn.endswith(SOURCE_EXTS):
                yield os.path.join(dirpath, fn)


def scan_source(services):
    """code-only cross-namespace edges: {(caller_ns, svc, callee_ns, port): [origins]}"""
    found = defaultdict(list)
    unmapped = set()
    for module in sorted(glob.glob(os.path.join(ROOT, "openbank-*"))):
        src = os.path.join(module, "src", "main")
        if not os.path.isdir(src):
            continue
        caller_ns = module_namespace(module, services)
        for path in walk_sources(src):
            try:
                text = open(path, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            for svc, callee_ns, port in URL_RE.findall(text):
                if caller_ns is None:
                    unmapped.add(os.path.basename(module))
                    continue
                if callee_ns == caller_ns:
                    continue
                key = (caller_ns, svc, callee_ns, int(port) if port else None)
                found[key].append(os.path.relpath(path, ROOT))
    return found, unmapped


def report(found, deploy_env_urls, admitted, selected=None):
    """Classify each code-only edge. `selected` maps (callee_ns, svc) -> bool|None."""
    selected = selected or {}
    gaps = []
    for (caller_ns, svc, callee_ns, port), origins in sorted(found.items()):
        if (svc, callee_ns, port) in deploy_env_urls.get(caller_ns, set()):
            continue  # the generator saw this edge in gitops env — not a gap
        if (callee_ns, caller_ns, port) in admitted or (callee_ns, caller_ns, None) in admitted:
            status = "ADMITTED"
        elif selected.get((callee_ns, svc)) is None:
            status = "NO-CALLEE"
        elif selected.get((callee_ns, svc)) is False:
            status = "LATENT"
        else:
            status = "DROPPED"
        gaps.append((caller_ns, svc, callee_ns, port, status, origins))
    return gaps


# Baseline: the code-only edges present when this gate was introduced (#2691 stage 1),
# each with the reason it is here. The gate fails when an edge appears that is NOT on
# this list, and ALSO when a listed edge no longer reproduces — a fixed entry must be
# deleted, so the baseline can only shrink.
KNOWN_MISSING = {
    ("aml", "account-service", "accounts", 8100):
        "AccountServiceClient uses its @ConfigProperty default; no env in gitops. "
        "Best-effort sweep resolution only (the client's own KDoc says a case must "
        "never fail because account-service is unreachable), so the drop is silent.",
    ("customer-edge", "audit-service", "audit", 8113):
        "URL lives only in customer-edge's src/main/resources/application.yaml.",
    ("platform", "vllm", "copilot", 8000):
        "NO-CALLEE: gitops declares no vllm Service and the copilot namespace is "
        "empty. Dead config, not a policy gap — the fix is in the service, not here.",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    services, deploy_env_urls, netpols, selectors = load_gitops()
    admitted = admitted_edges(netpols)
    found, unmapped = scan_source(services)
    selected = {
        (callee_ns, svc): pod_is_selected(callee_ns, svc, selectors, netpols)
        for (_c, svc, callee_ns, _p) in found
    }
    gaps = report(found, deploy_env_urls, admitted, selected)

    default_deny = [
        np for np in netpols
        if not ((np.get("spec") or {}).get("podSelector") or {})
        and not (np.get("spec") or {}).get("ingress")
        and not (np.get("spec") or {}).get("egress")
    ]

    print(f"NetworkPolicies in gitops:  {len(netpols)}")
    print(f"default-deny baselines:     {len(default_deny)}")
    print(f"code-only cross-ns edges:   {len(gaps)}")
    print()
    for caller_ns, svc, callee_ns, port, status, origins in gaps:
        p = port if port is not None else "-"
        print(f"{status:8}  {caller_ns} -> {svc}.{callee_ns}:{p}")
        for o in sorted(set(origins)):
            print(f"          {o}")
    if unmapped:
        print(f"\nUNATTRIBUTABLE: {len(unmapped)} shared library module(s) carry a "
              f"cluster URL default\n  ({', '.join(sorted(unmapped))}). A library edge "
              "belongs to EVERY service that\n  depends on it, so its caller namespace "
              "is not a property of the source tree.\n  These are NOT counted above and "
              "are the enumeration's largest blind spot.")
    print("\nADMITTED = covered only because another caller declared the same edge in "
          "gitops env.\nDROPPED  = the callee pod is policy-selected and this caller is "
          "not admitted: dropped today.\nLATENT   = the callee pod is selected by no "
          "policy, so it works until default-deny lands.\nFix by declaring the URL in "
          "the caller's gitops Deployment env, then regenerating\n(never by hand-editing "
          "a derived network-policies.yaml).")

    keys = {(c, s, cn, p) for c, s, cn, p, _st, _o in gaps}
    new = keys - set(KNOWN_MISSING)
    gone = set(KNOWN_MISSING) - keys
    rc = 0
    for c, s, cn, p in sorted(new):
        print(f"::error::new code-only cross-namespace edge {c} -> {s}.{cn}:{p} — "
              f"gen-network-policies.py cannot see it. Declare the URL in the caller's "
              f"gitops Deployment env and regenerate.")
        rc = 1
    for c, s, cn, p in sorted(gone):
        print(f"::error::stale KNOWN_MISSING entry {c} -> {s}.{cn}:{p} no longer "
              f"reproduces — delete it from {os.path.basename(__file__)}.")
        rc = 1
    return rc


def self_test():
    """Falsifiability: the classifier must move MISSING -> ADMITTED -> not-a-gap.

    Built as three runs over one synthetic fixture, each differing by exactly the
    input the rule is supposed to react to.
    """
    caller, callee, port = "aml", "kyc-service", 8114
    edge = {(caller, callee, "kyc", port): ["x.kt"]}

    sel_yes = {("kyc", callee): True}
    sel_no = {("kyc", callee): False}

    # 1. callee is policy-selected, nothing admits the caller -> DROPPED today
    g = report(edge, {}, set(), sel_yes)
    assert g and g[0][4] == "DROPPED", g

    # 1b. same edge, callee selected by NO policy -> LATENT, not DROPPED.
    #     Collapsing these two is the reassuring-wrong answer this split exists for.
    g = report(edge, {}, set(), sel_no)
    assert g and g[0][4] == "LATENT", g

    # 1c. no such Service in gitops at all -> NO-CALLEE, never DROPPED. Reporting a
    #     never-deployed host as a policy drop sends the reader to fix a policy.
    g = report(edge, {}, set(), {})
    assert g and g[0][4] == "NO-CALLEE", g

    # 2. a policy admits caller ns on that port -> ADMITTED
    g = report(edge, {}, {("kyc", caller, port)}, sel_yes)
    assert g and g[0][4] == "ADMITTED", g

    # 2b. a policy admitting the caller on a DIFFERENT port must NOT admit it
    #     (the flattened-allow-list trap: port must be read with its own rule)
    g = report(edge, {}, {("kyc", caller, 9000)}, sel_yes)
    assert g and g[0][4] == "DROPPED", g

    # 3. the caller declares the URL in gitops env -> not a gap at all
    g = report(edge, {caller: {(callee, "kyc", port)}}, set(), sel_yes)
    assert g == [], g

    # 3b. pod_is_selected must distinguish a policy that selects these pods from one
    #     that selects a co-tenant workload in the same namespace.
    sels = {("kyc", "kyc-service"): {"app.kubernetes.io/name": "kyc-service"}}
    mine = {"metadata": {"namespace": "kyc"},
            "spec": {"podSelector": {"matchLabels": {"app.kubernetes.io/name": "kyc-service"}},
                     "policyTypes": ["Ingress"]}}
    other = {"metadata": {"namespace": "kyc"},
             "spec": {"podSelector": {"matchLabels": {"app.kubernetes.io/name": "redis"}},
                      "policyTypes": ["Ingress"]}}
    wide = {"metadata": {"namespace": "kyc"},
            "spec": {"podSelector": {}, "policyTypes": ["Ingress"]}}
    assert pod_is_selected("kyc", "kyc-service", sels, [mine]) is True
    assert pod_is_selected("kyc", "kyc-service", sels, [other]) is False
    assert pod_is_selected("kyc", "kyc-service", sels, [wide]) is True
    assert pod_is_selected("kyc", "kyc-service", sels, []) is False
    # a policy in ANOTHER namespace must never count
    elsewhere = dict(mine, metadata={"namespace": "aml"})
    assert pod_is_selected("kyc", "kyc-service", sels, [elsewhere]) is False
    # an unknown Service must refuse to guess rather than answer False
    assert pod_is_selected("kyc", "ghost", sels, [mine]) is None

    # 4. admitted_edges must keep ports attached to their own rule
    np = {
        "metadata": {"namespace": "kyc"},
        "spec": {"ingress": [
            {"ports": [{"port": 9000}],
             "from": [{"namespaceSelector": {"matchLabels": {NS_LABEL: "observability"}}}]},
            {"ports": [{"port": 8114}],
             "from": [{"namespaceSelector": {"matchLabels": {NS_LABEL: "aml"}}}]},
        ]},
    }
    a = admitted_edges([np])
    assert ("kyc", "observability", 9000) in a
    assert ("kyc", "observability", 8114) not in a, a
    assert ("kyc", "aml", 8114) in a

    # 5. URL_RE must require a literal `.svc`, matching the generator exactly
    assert URL_RE.findall("http://kyc-service.kyc:8114") == []
    assert URL_RE.findall("http://kyc-service.kyc.svc:8114") == [("kyc-service", "kyc", "8114")]

    # 6. module -> namespace mapping strips the openbank- prefix
    with tempfile.TemporaryDirectory() as d:
        m = os.path.join(d, "openbank-kyc-service")
        os.makedirs(m)
        assert module_namespace(m, {"kyc-service": "kyc"}) == "kyc"
        assert module_namespace(m, {}) is None
        s = os.path.join(d, "openbank-security-scanner")
        os.makedirs(s)
        # the suffix variant: module name != Service name
        assert module_namespace(s, {"security-scanner-service": "security-scanner"}) \
            == "security-scanner"

    print("self-test: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
