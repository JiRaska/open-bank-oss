#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Guard: every in-cluster hostname a service's application.yaml names must resolve to a
# Kubernetes Service that gitops actually declares.
#
# THE FAILURE THIS CATCHES, AND WHY NOTHING ELSE DOES
# A hostname in application.yaml is unfalsifiable by every test layer this repo has:
#   * a unit test stubs the client, so the host is never resolved;
#   * an @QuarkusTest / IT serves a local fixture on localhost, so the host is overridden;
#   * a consumer pact answers whatever the client asks for, so the host is never sent;
#   * yamllint checks the file is YAML, not that the YAML means anything.
# So a wrong host is green everywhere until a pod tries to dial it in the cluster, where
# the symptom is a hung call or a connect timeout in one log line nobody reads.
#
# That is exactly how openbank-fx-service's CNB fixing URL was a 404 for the whole life of
# the service (root CLAUDE.md, "CI gates — exercise the failure path before trusting the
# green"). check-external-feeds.py closed that hole for THIRD-PARTY URLs by fetching them.
# An in-cluster host cannot be fetched from CI — there is no cluster — but it does not need
# to be: the set of hostnames that can possibly resolve is fully declared in
# openbank-infra/gitops/, so the claim is checkable statically.
#
# The case that forced it (issue #1916): openbank-analytics-sink's application.yaml
# documented the Apicurio schema registry as "service schema-registry:8081" and defaulted
# ANALYTICS_SCHEMA_APICURIO_URL to it. The real Service is apicurio-registry:8080 in
# namespace `messaging`. Both the host and the port were wrong, in a comment AND in a
# value, and nothing anywhere could say so.
#
# TWO RULES THIS GUARD OBEYS, BOTH LEARNED THE HARD WAY IN THIS REPO
#
# 1. IT KEEPS NO COPY OF ANY HOSTNAME. Both sides are read out of committed files: the
#    claim from the service's application.yaml, the truth from gitops. A guard holding its
#    own copy of the hostname moves with the config and keeps passing (the "second copy IS
#    the drift" rule). Consequently there is no list in this file to update when a service
#    is added, renamed, or moved to another namespace.
#
# 2. IT ASSERTS AGAINST THE PARSED YAML VALUE, NEVER THE FILE TEXT. A whole-file grep
#    cannot distinguish config from the prose ABOUT config, and this repo has been burnt in
#    both directions: a guard that flags the comment explaining the bug it exists to catch
#    (#2450), and a test that matched the five-line comment above the very line it was
#    asserting on, so deleting the line left it green (#3072). PyYAML drops comments during
#    parsing, so working from `yaml.safe_load_all` output is immune to both by construction
#    rather than by a strip-the-comments pass that can be got wrong. The self-test pins
#    this: a fixture whose ONLY occurrence of a bogus host is in a `#` comment must not be
#    flagged.
#
# WHAT COUNTS AS AN IN-CLUSTER HOSTNAME (deliberately conservative — see the honest limits
# at the bottom of this header)
#   a) `<name>.<ns>.svc` / `<name>.<ns>.svc.cluster.local`  — unambiguous, always checked
#   b) `<name>.<ns>` where <ns> is a namespace gitops declares — the short in-cluster form
#   c) a single-label host inside a `scheme://` URL (e.g. `http://openbank-product-catalog:8080`)
#      — a bare Service name, resolved by the caller's own namespace search domain
#   Forms (a) and (b) are also accepted as a bare `host:port` token (no scheme), because
#   Temporal and Kafka client config is written that way. Form (c) is NOT: a single-label
#   `host:port` with no scheme is how docker-compose and Quarkus dev-services defaults are
#   written (`kafka:9092`), and flagging those would be wrong.
#
# NEVER FLAGGED: localhost, 127.0.0.1, 0.0.0.0, ::1, host.docker.internal, any IP literal,
# and any multi-label host whose second label is not a known namespace (api.github.com,
# www.cnb.cz, kc.open-bank.tech, s3.eu-central-1.amazonaws.com, ...) — those are external
# and are check-external-feeds.py's job, not this one's.
#
# WHAT COUNTS AS A DECLARATION (all DERIVED from the gitops tree; nothing hand-kept)
#   kind: Service                 -> (namespace, metadata.name)
#   kind: Cluster (postgresql.cnpg.io)
#                                 -> <name>-rw, <name>-ro, <name>-r   (CNPG derives these;
#                                    none appears as a literal Service, exactly as
#                                    check-gitops-secret-refs.py models <name>-app)
#   kind: Kafka (strimzi)         -> <name>-kafka-bootstrap, <name>-kafka-brokers
#   a host already declared in a gitops workload's env / args
#                                 -> corroborated (see below)
#
# THE CORROBORATION TIER, AND WHY IT IS WEAKER ON PURPOSE
# Several real Services are created by a Helm chart that gitops references as an ArgoCD
# Application rather than templating into this tree: temporal-frontend (temporal chart),
# prometheus-operated / alertmanager-operated (prometheus-operator). They exist, they are
# dialled daily, and no `kind: Service` in this repo names them. The alternative to
# modelling them would be a hand-written exception list — the single most reliably rotting
# construct in this repo (a gate whose SCOPE is a hand-kept list reads as PASSING when the
# list is short, never as UNCHECKED). So instead the known set is widened with every host
# a gitops Deployment/Rollout/StatefulSet env or ConfigMap value already names: if the
# deployment manifest dials it, the platform believes it exists.
#
# This is genuinely weaker evidence than a `kind: Service`, and it is reported separately
# in the output so the distinction is never lost.
#
# THE GITOPS ENV LAYER (#3966) — and why corroboration cannot be reused for it
# The hole this header used to state — "the guard cannot catch a host that is wrong in
# application.yaml AND wrong the same way in the gitops env" — was larger than described,
# because the fleet-standard fix for a bad host MOVES the host out of application.yaml and
# into the workload env (localhost dev default + real URL in gitops). So the remedy handed
# the checked claim to an unchecked file, and four live defects sat in that gap: vop-service
# dialling `party-service.parties.svc` (namespace never existed) and `account-service…:8101`
# (ledger's port), the openbao secret-rotator dialling `keycloak.keycloak.svc` (Keycloak is
# in `iam`), psd2 + security-scanner dialling `tpp-registry-service.tpp.svc` (the namespace
# is `tpp-registry`), and clearing-simulator + standing-order dialling
# `sepa-payment-service.payments.svc` (the Service is `sepa-payment`).
#
# So workload env values are now CHECKED, not merely read. The subtlety that makes this more
# than a loop over a second file set: the corroboration tier above is derived FROM those env
# values, so reusing it here would be circular — every env host would corroborate itself and
# nothing could ever be flagged. Corroboration is evidence only when the CLAIM and the
# CORROBORATION come from independently-authored places (application.yaml vs. the manifest);
# applied to the manifest itself it is the same statement twice.
#
# The env layer therefore resolves against declared Services only, plus HELM_PROVIDED below.
# That list is the honest cost of dropping corroboration here, and it is checked BOTH ways —
# an entry that stops being needed fails the gate — so it cannot rot into permanent scope.
#
# ALSO FIXED HERE: an explicit `.svc` host in an UNDECLARED namespace used to be skipped.
# The header claimed form (a) was "unambiguous, always checked" and the code disagreed:
# `if ns not in namespaces: continue` treated it as external. But `.svc` is a cluster suffix
# by definition — an unknown namespace there means it cannot resolve, which is precisely the
# namespace-typo case (`parties`, `keycloak`, `tpp`). Now flagged in both layers. The short
# `<name>.<ns>` form keeps the old skip: there an unknown second label really is ambiguous
# with an external domain.
#
# Usage:
#   check-incluster-hostnames.py                 # gate
#   check-incluster-hostnames.py --enforce       # exit 1 on findings (advisory otherwise)
#   check-incluster-hostnames.py --self-test     # prove the gate can fail
# ---------------------------------------------------------------------------------------
from __future__ import annotations

import argparse
import ipaddress
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - CI always has PyYAML
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

REPO = Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"

WORKLOAD_KINDS = {"Deployment", "Rollout", "StatefulSet", "DaemonSet", "Job", "CronJob"}

# Kinds this guard reads anything from. Used twice: to decide what to extract, and as a
# cheap TEXT pre-filter that decides whether a file is worth handing to PyYAML at all.
#
# The pre-filter is not an optimisation for its own sake. gitops is ~13 MB of YAML, and
# ~12 MB of that is the 26 generated `*-opa-bundle.yaml` ConfigMaps, which embed rego and
# rules.yaml verbatim. Parsing the whole tree with pure-Python PyYAML costs ~90 s (measured
# on this tree; libyaml's CSafeLoader is not available on every runner, so it cannot be
# relied on). Skipping files that declare none of these kinds takes that to ~7 s and cannot
# change the result: a file with no such object contributes nothing to the known set.
READ_KINDS = WORKLOAD_KINDS | {"Service", "Cluster", "Kafka", "Namespace"}
KIND_LINE = re.compile(
    r"^kind:\s*(" + "|".join(sorted(READ_KINDS)) + r")\s*$", re.MULTILINE
)

# A host in one of these forms is a local/dev target, never an in-cluster Service.
LOOPBACK = {"localhost", "0.0.0.0", "::1", "host.docker.internal", "docker.host.internal"}

# `scheme://HOST` for any scheme (http, https, jdbc:postgresql, grpc, ...). The host stops
# at the first `/`, `:`, `?` or `@`.
#
# The lookbehind must NOT exclude `:`. The overwhelmingly common shape in this fleet is a
# SmallRye default, `${SCHEMA_URL:http://host:8080}`, where the scheme is preceded by the
# `:` that separates the variable from its default. An earlier version of this regex
# required whitespace or a quote there and silently matched nothing in exactly the
# position that matters most — caught only because the self-test drives that shape
# explicitly. Excluding scheme-name characters is enough to stop a partial match inside a
# longer token.
SCHEME_HOST = re.compile(r"(?<![A-Za-z0-9+.\-/])([a-z][a-z0-9+.\-]*)://([A-Za-z0-9_.\-]+)")

# A bare `host:port` token. Anchored so it cannot match the tail of a URL already handled
# above, nor a `${VAR:default}` boundary.
BARE_HOST_PORT = re.compile(r"(?<![A-Za-z0-9_.:/\-])([a-z][a-z0-9\-]*(?:\.[a-z0-9\-]+)+):(\d{2,5})(?![0-9])")


# Config keys whose value is NOT a host this process ever dials, so "does it resolve?" is the
# wrong question to ask of it. `quarkus.http.cors.origins` is a list of browser Origin header
# values compared as STRINGS against an incoming request — no DNS lookup happens, and the
# comparand is whatever hostname the user's browser was pointed at, never a Service name. Three
# services list `http://openbank-admin-ui:3000` there; that entry is dead config, but it is dead
# in a different way from a rest-client URL and reporting the two together is a category error.
# A guard that mislabels a third of its findings is a guard people learn to skim.
#
# This is matched on the PARSED key path, so it exempts the value, not the file.
NON_DIALLED_KEY_SUFFIXES = ("cors.origins",)

# Services that exist in the cluster and CANNOT be declared here: they are created by a Helm
# chart that gitops references as an ArgoCD Application (kube-prometheus-stack, loki, tempo,
# pyroscope, temporal, openbao, holmesgpt), so no `kind: Service` in this repo names them.
#
# For application.yaml the corroboration tier covers these without a list. The env layer has
# no such option (corroboration is derived from the env — see the header), so they are named.
# Each was verified against the live sandbox cluster on 2026-08-07 with
# `kubectl -n <ns> get svc <name>`; the port column is recorded so a future reader can re-run
# the same check rather than trusting the line.
#
# CHECKED BOTH WAYS: an entry no workload env dials any more is reported as stale and fails
# the gate. That is what stops this from becoming the hand-kept scope list the header rejects
# — it can only shrink by being noticed, never rot silently. Adding an entry means asserting
# a Service exists that this repo does not create; do it only with the kubectl output in the PR.
HELM_PROVIDED = {
    ("alertmanager-operated", "observability"),              # 9093/9094 — prometheus-operator
    ("holmesgpt-holmes", "observability"),                   # 80        — holmesgpt chart
    ("kube-prometheus-stack-alertmanager", "observability"),  # 9093/8080 — kube-prometheus-stack
    ("kube-prometheus-stack-prometheus", "observability"),   # 9090/8080 — kube-prometheus-stack
    ("loki", "observability"),                               # 3100/9095 — loki chart
    ("openbao", "vault"),                                    # 8200/8201 — openbao chart
    ("prometheus-operated", "observability"),                # 9090      — prometheus-operator
    ("pyroscope", "observability"),                          # 4040      — pyroscope chart
    ("tempo", "observability"),                              # 4317/3200 — tempo chart
    ("temporal-frontend", "temporal"),                       # 7233/7243 — temporal chart
}


def is_dialled(ypath: str) -> bool:
    p = ypath.lstrip(".")
    return not any(p == s or p.endswith("." + s) for s in NON_DIALLED_KEY_SUFFIXES)


def is_ip(host: str) -> bool:
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


def normalise(host: str):
    """Reduce an in-cluster hostname to (name, namespace, explicit_svc).

    `namespace` None means a bare single-label name. `explicit_svc` is True when the host
    carried a literal `.svc` / `.svc.cluster.local` suffix, which makes it in-cluster BY
    DEFINITION — the caller must then treat an undeclared namespace as unresolvable rather
    than as an external domain. Without the suffix a two-label host is ambiguous with a real
    domain (`api.github.com` is not `api` in namespace `github`), so the caller stays quiet.

    Returns None when the host is not an in-cluster candidate at all.
    """
    h = host.rstrip(".").lower()
    if not h or h in LOOPBACK or is_ip(h):
        return None
    if h.endswith(".svc.cluster.local"):
        h, explicit = h[: -len(".svc.cluster.local")], True
    elif h.endswith(".svc"):
        h, explicit = h[: -len(".svc")], True
    else:
        # not an explicit cluster form; the caller decides using the namespace set
        parts = h.split(".")
        if len(parts) == 1:
            return (h, None, False)
        if len(parts) == 2:
            return (parts[0], parts[1], False)
        return None
    parts = h.split(".")
    if len(parts) == 2:
        return (parts[0], parts[1], explicit)
    if len(parts) == 1:
        # `<name>.svc` — malformed but unambiguously meant as in-cluster
        return (parts[0], None, explicit)
    return None


def scalars(node, path=""):
    """Yield (dotted-path, string) for every string scalar in a parsed YAML doc."""
    if isinstance(node, dict):
        for k, v in node.items():
            yield from scalars(v, f"{path}.{k}")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from scalars(v, f"{path}[{i}]")
    elif isinstance(node, str):
        yield path, node


def hosts_in(text: str):
    """Yield (host, from_url) for every hostname-looking token in one scalar value."""
    for _scheme, host in SCHEME_HOST.findall(text):
        yield host, True
    for host, _port in BARE_HOST_PORT.findall(text):
        yield host, False


def gitops_facts(gitops: Path):
    """-> (services, namespaces, corroborated, env_sites) — all derived from the tree.

    `env_sites` is every host a workload env/arg names, WITH provenance, so the same host
    can be both corroboration (for application.yaml) and a checked claim (for itself).
    Each entry is (path, ypath, host, workload-name).
    """
    services, namespaces, corroborated, env_sites = set(), set(), set(), []
    if not gitops.is_dir():
        return services, namespaces, corroborated, env_sites

    for path in sorted(gitops.rglob("*.yaml")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        if not KIND_LINE.search(text):
            continue  # declares nothing this guard reads — see READ_KINDS
        try:
            docs = [d for d in yaml.safe_load_all(text) if isinstance(d, dict)]
        except yaml.YAMLError:
            continue  # unparseable YAML is yamllint's problem, not this gate's
        for doc in docs:
            kind = doc.get("kind")
            meta = doc.get("metadata") or {}
            name, ns = meta.get("name"), meta.get("namespace")
            if ns:
                namespaces.add(ns)
            if kind == "Namespace" and name:
                namespaces.add(name)
            if not name:
                continue
            if kind == "Service":
                services.add((name, ns))
            elif kind == "Cluster" and "cnpg" in str(doc.get("apiVersion", "")):
                # CloudNativePG synthesises the read/write endpoints from the Cluster name.
                for suffix in ("rw", "ro", "r"):
                    services.add((f"{name}-{suffix}", ns))
            elif kind == "Kafka" and "strimzi" in str(doc.get("apiVersion", "")):
                for suffix in ("kafka-bootstrap", "kafka-brokers"):
                    services.add((f"{name}-{suffix}", ns))

            # Corroboration tier (for application.yaml) AND the checked env layer (#3966):
            # the same values serve both, which is exactly why corroboration must not be
            # reused when checking them — see the header.
            blobs = []  # (ypath, value)
            if kind in WORKLOAD_KINDS:
                spec = doc.get("spec") or {}
                tmpl = ((spec.get("jobTemplate") or {}).get("spec", {}).get("template")
                        if kind == "CronJob" else spec.get("template")) or {}
                ps = tmpl.get("spec") or {}
                for c in (ps.get("containers") or []) + (ps.get("initContainers") or []):
                    cn = c.get("name") or "?"
                    for e in c.get("env") or []:
                        if isinstance(e.get("value"), str):
                            blobs.append((f"{cn}.env.{e.get('name') or '?'}", e["value"]))
                    for i, a in enumerate(c.get("args") or []):
                        if isinstance(a, str):
                            blobs.append((f"{cn}.args[{i}]", a))
            for ypath, blob in blobs:
                for host, from_url in hosts_in(blob):
                    got = normalise(host)
                    if not got:
                        continue
                    if got[1]:
                        corroborated.add((got[0], got[1]))
                    env_sites.append((path, f"{name}.{ypath}", host, from_url))

    return services, namespaces, corroborated, env_sites


def service_yamls(root: Path):
    """Every Quarkus service's main application.yaml (never src/test)."""
    return sorted(
        p for p in root.glob("*/src/main/resources/application.yaml") if p.is_file()
    )


def scan(files, services, namespaces, corroborated):
    """-> (findings, checked_count). A finding is (path, yaml_path, host, reason)."""
    findings, checked = [], 0
    known_bare = {n for n, _ns in services}
    for path in files:
        try:
            docs = [d for d in yaml.safe_load_all(path.read_text(encoding="utf-8")) if isinstance(d, dict)]
        except yaml.YAMLError as exc:
            findings.append((path, "<file>", "-", f"unparseable YAML: {exc}"))
            continue
        for doc in docs:
            for ypath, value in scalars(doc):
                if not is_dialled(ypath):
                    continue
                for host, from_url in hosts_in(value):
                    verdict = classify(host, from_url, services, namespaces,
                                       known_bare, corroborated)
                    if verdict is None:
                        continue
                    checked += 1
                    if verdict:
                        findings.append((path, ypath, host, verdict))
    return findings, checked


def classify(host, from_url, services, namespaces, known_bare, believed):
    """-> None (not our business) | "" (resolves) | reason string (does not resolve).

    `believed` is whatever the CALLER accepts as existing beyond declared Services: the
    corroboration tier for application.yaml, HELM_PROVIDED for the gitops env. Keeping it a
    parameter is what stops the env layer from corroborating itself (header).
    """
    got = normalise(host)
    if got is None:
        return None
    name, ns, explicit = got
    if ns is None:
        # bare single-label name: only a URL position implies a Service
        if not from_url:
            return None
        return "" if name in known_bare else (
            "no Service of this name is declared in any namespace")
    if ns not in namespaces:
        if not explicit:
            return None  # `<a>.<b>` with an unknown second label -> an external domain
        # A literal `.svc` is a cluster suffix, so an unknown namespace cannot resolve.
        # This is the namespace-typo case the gate used to skip (#3966).
        return (f"namespace '{ns}' is not declared anywhere in gitops, and a `.svc` host "
                f"can only resolve inside the cluster")
    if (name, ns) in services or (name, ns) in believed:
        return ""
    return f"namespace '{ns}' exists in gitops but declares no Service '{name}'"


def scan_env(env_sites, services, namespaces):
    """Check the gitops workload env layer against declared Services + HELM_PROVIDED.

    Deliberately NOT given the corroboration set: it is derived from these very values, so
    passing it here would let every host vouch for itself (header). `used` is returned so
    run_gate can report a HELM_PROVIDED entry nothing dials any more.
    """
    findings, checked, used = [], 0, set()
    known_bare = {n for n, _ns in services}
    for path, ypath, host, from_url in env_sites:
        verdict = classify(host, from_url, services, namespaces, known_bare, HELM_PROVIDED)
        if verdict is None:
            continue
        checked += 1
        got = normalise(host)
        if got and (got[0], got[1]) in HELM_PROVIDED:
            used.add((got[0], got[1]))
        if verdict:
            findings.append((path, ypath, host, verdict))
    return findings, checked, used


def run_gate(enforce: bool) -> int:
    services, namespaces, corroborated, env_sites = gitops_facts(GITOPS)
    if not services:
        print(f"::error::no Services found under {GITOPS} — run from the repo root.")
        return 2
    files = service_yamls(REPO)
    app_findings, app_checked = scan(files, services, namespaces, corroborated)
    env_findings, env_checked, helm_used = scan_env(env_sites, services, namespaces)
    findings = app_findings + env_findings

    print(f"check-incluster-hostnames: {len(files)} application.yaml files "
          f"({app_checked} hostnames) + {len(env_sites)} gitops workload env/arg values "
          f"({env_checked} hostnames), against {len(services)} declared Services "
          f"(+{len(corroborated)} corroborated, application.yaml layer only; "
          f"+{len(HELM_PROVIDED)} Helm-provided, env layer) across {len(namespaces)} namespaces")

    # Both-ways: a HELM_PROVIDED entry nothing dials is scope that has stopped being needed.
    # Reported as a finding in its own right so the list cannot rot into permanent breadth.
    stale = sorted(HELM_PROVIDED - helm_used)
    level = "error" if enforce else "warning"
    for name, ns in stale:
        print(f"::{level}::STALE HELM_PROVIDED entry in {Path(__file__).name}: "
              f"('{name}', '{ns}') is dialled by no gitops workload env any more — delete "
              f"the line. A declaration that outlives its use is how an exception list turns "
              f"into permanent unchecked scope.")

    if not findings and not stale:
        print("OK: every in-cluster hostname resolves — in application.yaml AND in the "
              "gitops workload env.")
        return 0

    for path, ypath, host, reason in findings:
        rel = path.relative_to(REPO) if path.is_absolute() and REPO in path.parents else path
        print(f"::{level} file={rel}::in-cluster host '{host}' at `{ypath.lstrip('.')}` "
              f"does not resolve: {reason}. Nothing in this repo's test layers can catch "
              f"this — a unit test stubs the client and an IT serves localhost — so fix the "
              f"hostname, or declare the Service in openbank-infra/gitops/.")
    if findings:
        print(f"\n{len(findings)} unresolvable in-cluster hostname(s) "
              f"({len(app_findings)} in application.yaml, {len(env_findings)} in a gitops "
              f"workload env).")
    if stale:
        print(f"{len(stale)} stale HELM_PROVIDED entr(y/ies).")
    return 1 if enforce else 0


# ── falsification ──────────────────────────────────────────────────────────────────────
def self_test() -> int:
    """A gate that has only ever passed is unfalsified. Drive BOTH verdicts."""
    import tempfile

    failures = []
    services = {("apicurio-registry", "messaging"), ("keycloak", "iam"), ("admin-ui", "admin-ui")}
    # `observability` must be here for the HELM_PROVIDED cases below to exercise the Helm
    # tier rather than the undeclared-namespace rule; `tpp` and `keda` are deliberately
    # absent, since those are the real undeclared-namespace defects (#3966).
    namespaces = {"messaging", "iam", "admin-ui", "analytics", "temporal", "observability"}
    # Two entries on purpose: one that is ALSO in HELM_PROVIDED (temporal-frontend) and one
    # that is not (only-corroborated). The pair is what proves the two layers judge against
    # different sets — see the circularity assertion at the end.
    corroborated = {("temporal-frontend", "temporal"), ("only-corroborated", "temporal")}

    def check(label, body, must_flag):
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "application.yaml"
            f.write_text(body, encoding="utf-8")
            found, _ = scan([f], services, namespaces, corroborated)
        ok = bool(found) == must_flag
        print(f"  {'ok  ' if ok else 'FAIL'} {label}"
              + ("" if ok else f"  (findings={[x[2] for x in found]})"))
        if not ok:
            failures.append(label)

    # ---- MUST FLAG -------------------------------------------------------------------
    # The #1916 defect itself, as a value: right namespace, Service that does not exist.
    check("a .svc host naming a Service that does not exist IS flagged",
          "openbank:\n  schema:\n    url: http://schema-registry.messaging.svc:8081\n", True)
    check("the .svc.cluster.local form is flagged too",
          "a:\n  b: http://schema-registry.messaging.svc.cluster.local:8081\n", True)
    check("the short <name>.<ns> form is flagged",
          "a:\n  b: http://schema-registry.messaging:8081\n", True)
    check("a bare host:port (no scheme) in <name>.<ns> form is flagged",
          "a:\n  b: schema-registry.messaging:8081\n", True)
    check("a bare single-label name in a URL position is flagged",
          "a:\n  b: http://openbank-product-catalog:8080\n", True)
    check("a host inside a ${VAR:default} default is flagged",
          "a:\n  b: ${SCHEMA_URL:http://schema-registry.messaging.svc:8081}\n", True)

    # ---- MUST NOT FLAG ---------------------------------------------------------------
    # THE code-about-code case: the only occurrence of the bogus host is a comment.
    # PyYAML drops it, so the parsed value is clean. This is the assertion that stops the
    # guard flagging the prose that explains the config (#2450 / #3072).
    check("a bogus host that appears ONLY in a comment is NOT flagged",
          "# provisioned in openbank-infra as service schema-registry.messaging.svc:8081\n"
          "a:\n  b: http://apicurio-registry.messaging.svc:8080\n", False)
    check("a real Service resolves clean", "a:\n  b: http://apicurio-registry.messaging.svc:8080\n", False)
    check("localhost is never flagged", "a:\n  b: http://localhost:8081\n", False)
    check("127.0.0.1 is never flagged", "a:\n  b: http://127.0.0.1:8081\n", False)
    check("an external public host is never flagged", "a:\n  b: https://api.github.com/repos\n", False)
    check("an external host with a deep prefix is never flagged",
          "a:\n  b: https://s3.eu-central-1.amazonaws.com/bucket\n", False)
    check("a dev-services style bare host:port with no scheme is NOT flagged",
          "a:\n  b: kafka:9092\n", False)
    check("a Helm-chart Service corroborated by a gitops env is NOT flagged",
          "a:\n  b: temporal-frontend.temporal.svc.cluster.local:7233\n", False)
    check("a host in an unknown (non-cluster) namespace-looking domain is NOT flagged",
          "a:\n  b: http://kc.open-bank.tech/realms/openbank\n", False)

    # The guard must also survive shapes that are not hostnames at all.
    check("a CORS origin is NOT treated as a dialled host",
          "quarkus:\n  http:\n    cors:\n      origins: \"http://openbank-admin-ui:3000\"\n", False)
    check("but the SAME host under a rest-client url IS flagged",
          "quarkus:\n  rest-client:\n    x:\n      url: http://openbank-admin-ui:3000\n", True)
    check("a plain word is not treated as a host", "a:\n  b: earliest\n", False)
    check("a duration/number scalar is not treated as a host", "a:\n  b: PT24H\n", False)

    # ---- the namespace-typo hole this gate used to skip (#3966) -----------------------
    # `.svc` is a cluster suffix by definition, so an undeclared namespace cannot resolve.
    # Both real cases: vop's `parties` (should be `party`) and openbao's `keycloak` (`iam`).
    check("an explicit .svc host in an UNDECLARED namespace IS flagged",
          "a:\n  b: http://party-service.parties.svc:8100\n", True)
    check("the .svc.cluster.local form of the same typo is flagged",
          "a:\n  b: http://keycloak.keycloak.svc.cluster.local:8080\n", True)
    # ...but the short form stays quiet, because there the second label is ambiguous with a
    # real domain. This asymmetry is the whole reason `explicit_svc` exists.
    check("the SHORT <name>.<ns> form with an unknown ns is NOT flagged (external domain)",
          "a:\n  b: http://something.keda:8080\n", False)

    # ---- the gitops env layer ---------------------------------------------------------
    def check_env(label, sites, must_flag):
        found, _, _ = scan_env(sites, services, namespaces)
        ok = bool(found) == must_flag
        print(f"  {'ok  ' if ok else 'FAIL'} {label}"
              + ("" if ok else f"  (findings={[x[2] for x in found]})"))
        if not ok:
            failures.append(label)

    P = Path("openbank-infra/gitops/components/x/x.yaml")
    check_env("a workload env naming a Service that does not exist IS flagged",
              [(P, "x.c.env.URL", "schema-registry.messaging.svc", True)], True)
    check_env("a workload env naming a real Service is clean",
              [(P, "x.c.env.URL", "apicurio-registry.messaging.svc", True)], False)
    check_env("a workload env in an undeclared namespace IS flagged",
              [(P, "x.c.env.URL", "tpp-registry-service.tpp.svc", True)], True)
    check_env("a Helm-provided Service is NOT flagged in the env layer",
              [(P, "x.c.env.URL", "tempo.observability.svc", True)], False)
    check_env("localhost in a workload env is never flagged",
              [(P, "x.c.env.URL", "localhost", True)], False)
    check_env("an external host in a workload env is never flagged",
              [(P, "x.c.env.URL", "api.github.com", True)], False)

    # THE circularity assertion, and the reason this gate is more than a second file loop.
    # `temporal-frontend.temporal` is corroborated for application.yaml BY a workload env.
    # If scan_env were handed that corroboration set, this host would vouch for itself and
    # the env layer could never flag anything. It must be judged on HELM_PROVIDED alone —
    # so a corroborated-but-NOT-Helm-provided host is a finding here while staying clean in
    # the application.yaml layer above.
    check("a corroborated-only host is clean in the application.yaml layer",
          "a:\n  b: http://only-corroborated.temporal.svc:1234\n", False)
    check_env("...and the SAME host IS flagged in the env layer (no self-corroboration)",
              [(P, "x.c.env.URL", "only-corroborated.temporal.svc", True)], True)

    # The stale half of both-ways: an entry nothing dials must be reported, or the list rots.
    _, _, used = scan_env([(P, "x.c.env.URL", "tempo.observability.svc", True)],
                          services, namespaces)
    stale_ok = used == {("tempo", "observability")} and bool(HELM_PROVIDED - used)
    print(f"  {'ok  ' if stale_ok else 'FAIL'} scan_env reports which HELM_PROVIDED entries "
          f"were actually dialled (stale detection)")
    if not stale_ok:
        failures.append("HELM_PROVIDED stale detection")

    if failures:
        print(f"\n::error::self-test failed: {', '.join(failures)}")
        return 1
    print("\nself-test passed: the gate flags an unresolvable in-cluster host, ignores a "
          "host that exists only in a comment, and leaves localhost/external hosts alone.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--self-test", action="store_true", help="prove the gate can fail")
    ap.add_argument("--enforce", action="store_true", help="exit 1 on findings")
    args = ap.parse_args()
    return self_test() if args.self_test else run_gate(args.enforce)


if __name__ == "__main__":
    sys.exit(main())
