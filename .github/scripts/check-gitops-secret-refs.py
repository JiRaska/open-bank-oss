#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Guard: every Secret/ConfigMap a gitops workload consumes must be declared by something
# in gitops (rules.yaml: gitops_ref_integrity).
#
# THE FAILURE THIS CATCHES
# A service is merged with a complete-looking manifest that reads a Secret nobody creates.
# Nothing fails at merge: the YAML is valid, ArgoCD syncs it happily (the Rollout is a
# legal object), and CI never runs the pod. Kyverno admits it. The pod then sits in
# CreateContainerConfigError forever, and the only signal is `kubectl describe` on a pod
# nobody is watching.
#
# vop-service, 2026-07-16: shipped in #1195 with `OIDC_CLIENT_SECRET <- secretKeyRef:
# vop-oidc`, but no ExternalSecret ever declared `vop-oidc`. It appeared exactly once in
# the whole repo -- at the point of consumption. The pod restarted 26 times over 10
# minutes with `Error: secret "vop-oidc" not found` while every dashboard stayed green,
# and it was found by hand (#1232). Its sibling gap -- absent from ALL_SERVICES -- already
# has a guard (check-deploy-coverage.sh); this is the same shape one layer down, and it
# only surfaced after that one was fixed. Two of three vop gaps were "a name referenced
# but never declared"; that is the invariant worth enforcing.
#
# WHAT COUNTS AS A DECLARATION
# There are no literal `kind: Secret` objects in gitops -- every Secret is produced by an
# operator from a declaration. So a bare name-existence grep would flag all of them. The
# producers, and the names they yield:
#   ExternalSecret  -> spec.target.name (falls back to metadata.name, per the ESO default)
#   ConfigMap       -> metadata.name
#   Certificate     -> spec.secretName                        (cert-manager)
#   Cluster (CNPG)  -> <name>-app, <name>-superuser, <name>-ca, <name>-server,
#                      <name>-replication                      (postgresql.cnpg.io)
# CNPG is why `vop-db-app` must NOT be flagged: it is real, and correct, and appears
# nowhere as a declaration -- the operator derives it from the Cluster name. A guard that
# cannot model that fails every database-backed service, gets muted, and protects nothing
# (see check-deploy-coverage.sh on why a Dockerfile rule was rejected for the same reason).
#
# Matching is by (namespace, name). A workload with no explicit namespace is matched
# against declarations in its own file's directory component -- ArgoCD sets the namespace
# per Application, so cross-component collisions are not a concern.
# ---------------------------------------------------------------------------------------
import sys
from pathlib import Path

import yaml

import gatelib

GITOPS = Path("openbank-infra/gitops")

# Kinds whose pod template consumes Secrets/ConfigMaps.
WORKLOAD_KINDS = {"Deployment", "Rollout", "StatefulSet", "DaemonSet", "Job", "CronJob"}

# Secrets an operator or the platform materialises without a gitops declaration.
# Keep this SHORT and justified -- every entry is a hole in the guard.
IGNORED_NAMES = {
    # kubernetes.io/dockerconfigjson pull secrets are provisioned per-namespace by the
    # platform bootstrap, not by a gitops object.
    "ecr-pull-secret",
    "regcred",
    # openbao-db-rotation-sync (ADR-0099 Tier 1) reads the per-database admin passwords
    # OpenBao itself rotates service credentials with. It CANNOT be an ExternalSecret:
    # ClusterSecretStore `vault-kv` is backed by http://openbao.vault.svc:8200, so
    # projecting it would make OpenBao bootstrap from OpenBao. Genuinely out-of-band,
    # hand-created, no ownerReferences -- verified live 2026-07-16. Note the flip side:
    # nothing recreates it if the vault namespace is rebuilt, and the weekly rotation
    # CronJob would then fail quietly. Tracked as fragility, not as a guard bug.
    "openbao-db-admin-passwords",
}


def pod_specs(doc):
    """Yield every PodSpec inside a workload doc (CronJob nests one level deeper)."""
    kind = doc.get("kind")
    spec = doc.get("spec") or {}
    if kind == "CronJob":
        tmpl = (spec.get("jobTemplate") or {}).get("spec", {}).get("template") or {}
    else:
        tmpl = spec.get("template") or {}
    ps = tmpl.get("spec")
    if isinstance(ps, dict):
        yield ps


def refs_in(ps):
    """Yield (kind, name) for every Secret/ConfigMap the PodSpec consumes."""
    for c in (ps.get("containers") or []) + (ps.get("initContainers") or []):
        for e in c.get("env") or []:
            vf = e.get("valueFrom") or {}
            for key, kind in (("secretKeyRef", "Secret"), ("configMapKeyRef", "ConfigMap")):
                ref = vf.get(key)
                if isinstance(ref, dict) and ref.get("name") and not ref.get("optional"):
                    yield kind, ref["name"]
        for ef in c.get("envFrom") or []:
            for key, kind in (("secretRef", "Secret"), ("configMapRef", "ConfigMap")):
                ref = ef.get(key)
                if isinstance(ref, dict) and ref.get("name") and not ref.get("optional"):
                    yield kind, ref["name"]
    for v in ps.get("volumes") or []:
        sec = v.get("secret") or {}
        if sec.get("secretName") and not sec.get("optional"):
            yield "Secret", sec["secretName"]
        cm = v.get("configMap") or {}
        if cm.get("name") and not cm.get("optional"):
            yield "ConfigMap", cm["name"]


def declarations(doc):
    """Yield (kind, name) for everything this doc causes to exist."""
    kind, meta = doc.get("kind"), doc.get("metadata") or {}
    name, spec = meta.get("name"), doc.get("spec") or {}
    if kind == "ConfigMap" and name:
        yield "ConfigMap", name
    elif kind == "ExternalSecret":
        yield "Secret", (spec.get("target") or {}).get("name") or name
    elif kind == "SealedSecret" and name:
        yield "Secret", name
    elif kind == "Secret" and name:
        yield "Secret", name
    elif kind == "Certificate" and spec.get("secretName"):
        yield "Secret", spec["secretName"]
    elif kind == "Cluster" and name and "cnpg" in str(doc.get("apiVersion", "")):
        # CloudNativePG derives these from the Cluster name; none appear in gitops.
        for suffix in ("app", "superuser", "ca", "server", "replication"):
            yield "Secret", f"{name}-{suffix}"


def component_of(path):
    """gitops/components/<component>/... -> <component>; the namespace proxy."""
    parts = path.parts
    return parts[parts.index("components") + 1] if "components" in parts else str(path.parent)


def self_test():
    """Falsify the reference extractor and the declaration index.

    What this prevents: a workload consuming a Secret or ConfigMap that nothing in the repo
    declares. The pod does not crash-loop visibly — it fails to start with a
    CreateContainerConfigError that reads like a transient scheduling problem, and in a
    progressive rollout the OLD replicaset keeps serving, so the deploy looks healthy.

    The two silent directions: an extractor that misses a reference SHAPE reports full
    coverage of the shapes it does see, and `optional: true` refs must be excluded or every
    deliberately-optional mount becomes a finding and the gate gets switched off.
    """
    fails = []

    def refs(ps):
        return sorted(set(refs_in(ps)))

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want}, got {got}")

    # Every consumption shape, because missing one is invisible: the gate keeps reporting
    # clean about the shapes it still reads.
    case("env.valueFrom.secretKeyRef",
         refs({"containers": [{"env": [{"valueFrom": {"secretKeyRef": {"name": "s1", "key": "k"}}}]}]}),
         [("Secret", "s1")])
    case("env.valueFrom.configMapKeyRef",
         refs({"containers": [{"env": [{"valueFrom": {"configMapKeyRef": {"name": "c1", "key": "k"}}}]}]}),
         [("ConfigMap", "c1")])
    case("envFrom.secretRef",
         refs({"containers": [{"envFrom": [{"secretRef": {"name": "s2"}}]}]}), [("Secret", "s2")])
    case("envFrom.configMapRef",
         refs({"containers": [{"envFrom": [{"configMapRef": {"name": "c2"}}]}]}), [("ConfigMap", "c2")])
    case("volumes.secret",
         refs({"containers": [], "volumes": [{"secret": {"secretName": "s3"}}]}), [("Secret", "s3")])

    # INIT CONTAINERS are the classic miss: they run before the app and fail the pod just the
    # same, but a loop over `containers` alone never sees them.
    case("initContainers are read too",
         refs({"initContainers": [{"envFrom": [{"secretRef": {"name": "s4"}}]}], "containers": []}),
         [("Secret", "s4")])

    # `optional: true` is a DECLARED intent to tolerate absence. Flagging it turns every
    # deliberate optional mount into a finding, which is how a gate gets disabled.
    case("an optional secretKeyRef is not a requirement",
         refs({"containers": [{"env": [{"valueFrom": {"secretKeyRef": {"name": "s5", "key": "k", "optional": True}}}]}]}),
         [])
    case("an optional volume secret is not a requirement",
         refs({"containers": [], "volumes": [{"secret": {"secretName": "s6", "optional": True}}]}), [])

    # A ref with no name declares nothing to look up.
    case("a nameless ref yields nothing",
         refs({"containers": [{"envFrom": [{"secretRef": {}}]}]}), [])

    # --- pod_specs: CronJob nests one level deeper than every other workload ---------------
    dep = {"kind": "Deployment", "spec": {"template": {"spec": {"containers": [{"name": "x"}]}}}}
    if [ps.get("containers")[0]["name"] for ps in pod_specs(dep)] != ["x"]:
        fails.append("a Deployment PodSpec was not found")
    cron = {"kind": "CronJob", "spec": {"jobTemplate": {"spec": {"template": {"spec": {"containers": [{"name": "y"}]}}}}}}
    if [ps.get("containers")[0]["name"] for ps in pod_specs(cron)] != ["y"]:
        fails.append("a CronJob PodSpec was not found — it nests one level deeper, and a "
                     "walker written for Deployments alone silently skips every CronJob")
    # A doc with no template yields nothing rather than raising.
    if list(pod_specs({"kind": "Service", "spec": {}})) != []:
        fails.append("a non-workload doc produced a PodSpec")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: gitops secret/configmap ref extractor is falsifiable (12 cases)")
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()

    if not GITOPS.is_dir():
        print(f"ERROR: {GITOPS} not found -- run from the repo root.", file=sys.stderr)
        return 2

    # Measured 2026-09-03: with GITOPS/components renamed away this printed
    # "OK: every referenced Secret/ConfigMap is declared" and exited 0. The count lets
    # run-gates' min_subjects floor tell an empty scan from a clean one.
    manifests = list(gatelib.rglob(GITOPS, "*.yaml"))
    gatelib.subjects(len(manifests), "gitops manifests scanned")

    declared, consumed = set(), []
    for path in manifests:
        try:
            docs = [d for d in yaml.safe_load_all(path.read_text()) if isinstance(d, dict)]
        except yaml.YAMLError as exc:
            print(f"ERROR: {path}: unparseable YAML: {exc}", file=sys.stderr)
            return 2
        comp = component_of(path)
        for doc in docs:
            ns = (doc.get("metadata") or {}).get("namespace") or comp
            for kind, name in declarations(doc):
                declared.add((ns, kind, name))
            if doc.get("kind") in WORKLOAD_KINDS:
                for ps in pod_specs(doc):
                    for kind, name in refs_in(ps):
                        consumed.append((ns, kind, name, path, (doc.get("metadata") or {}).get("name")))

    missing = [
        (ns, kind, name, path, owner)
        for ns, kind, name, path, owner in consumed
        if name not in IGNORED_NAMES and (ns, kind, name) not in declared
    ]

    print(f"gitops ref integrity: {len(declared)} declared, {len(consumed)} references checked")
    if not missing:
        print("OK: every referenced Secret/ConfigMap is declared.")
        return 0

    seen, out = set(), []
    for ns, kind, name, path, owner in missing:
        if (ns, kind, name) in seen:
            continue
        seen.add((ns, kind, name))
        out.append((ns, kind, name, path, owner))

    print(f"\nFAIL: {len(out)} referenced {'name is' if len(out) == 1 else 'names are'} "
          f"declared nowhere in gitops:\n", file=sys.stderr)
    for ns, kind, name, path, owner in out:
        print(f"  {kind} {ns}/{name}", file=sys.stderr)
        print(f"    consumed by {owner} in {path}", file=sys.stderr)
        print("    nothing declares it -- the pod will sit in CreateContainerConfigError.",
              file=sys.stderr)
        if kind == "Secret":
            print(f"    fix: add an ExternalSecret with target.name: {name} "
                  f"(see the component's oidc-externalsecrets.yaml for the pattern)",
                  file=sys.stderr)
        print(file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
