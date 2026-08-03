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
# in the output so the distinction is never lost. It also means the guard cannot catch a
# host that is wrong in application.yaml AND wrong the same way in the gitops env. That is
# a real hole; it is stated here rather than papered over.
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
    """Reduce an in-cluster hostname to (name, namespace); namespace None means bare.

    Returns None when the host is not an in-cluster candidate at all.
    `bare_ok` is decided by the caller (only URL positions accept a bare name).
    """
    h = host.rstrip(".").lower()
    if not h or h in LOOPBACK or is_ip(h):
        return None
    if h.endswith(".svc.cluster.local"):
        h = h[: -len(".svc.cluster.local")]
    elif h.endswith(".svc"):
        h = h[: -len(".svc")]
    else:
        # not an explicit cluster form; the caller decides using the namespace set
        parts = h.split(".")
        if len(parts) == 1:
            return (h, None)
        if len(parts) == 2:
            return (parts[0], parts[1])
        return None
    parts = h.split(".")
    if len(parts) == 2:
        return (parts[0], parts[1])
    if len(parts) == 1:
        # `<name>.svc` — malformed but unambiguously meant as in-cluster
        return (parts[0], None)
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
    """-> (services, namespaces, corroborated) — all derived, nothing hand-written."""
    services, namespaces, corroborated = set(), set(), set()
    if not gitops.is_dir():
        return services, namespaces, corroborated

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

            # Corroboration tier: a host the deployment manifest itself dials.
            blobs = []
            if kind in WORKLOAD_KINDS:
                spec = doc.get("spec") or {}
                tmpl = ((spec.get("jobTemplate") or {}).get("spec", {}).get("template")
                        if kind == "CronJob" else spec.get("template")) or {}
                ps = tmpl.get("spec") or {}
                for c in (ps.get("containers") or []) + (ps.get("initContainers") or []):
                    for e in c.get("env") or []:
                        if isinstance(e.get("value"), str):
                            blobs.append(e["value"])
                    blobs.extend(a for a in (c.get("args") or []) if isinstance(a, str))
            for blob in blobs:
                for host, _from_url in hosts_in(blob):
                    got = normalise(host)
                    if got and got[1]:
                        corroborated.add(got)

    return services, namespaces, corroborated


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
                    got = normalise(host)
                    if got is None:
                        continue
                    name, ns = got
                    if ns is None:
                        # bare single-label name: only a URL position implies a Service
                        if not from_url:
                            continue
                        checked += 1
                        if name not in known_bare:
                            findings.append((path, ypath, host,
                                             "no Service of this name is declared in any namespace"))
                        continue
                    if ns not in namespaces:
                        continue  # not a cluster namespace -> external host, not our business
                    checked += 1
                    if (name, ns) in services or (name, ns) in corroborated:
                        continue
                    findings.append((path, ypath, host,
                                     f"namespace '{ns}' exists in gitops but declares no "
                                     f"Service '{name}'"))
    return findings, checked


def run_gate(enforce: bool) -> int:
    services, namespaces, corroborated = gitops_facts(GITOPS)
    if not services:
        print(f"::error::no Services found under {GITOPS} — run from the repo root.")
        return 2
    files = service_yamls(REPO)
    findings, checked = scan(files, services, namespaces, corroborated)

    print(f"check-incluster-hostnames: {len(files)} application.yaml files, "
          f"{checked} in-cluster hostnames checked against {len(services)} declared "
          f"Services (+{len(corroborated)} corroborated by a gitops workload env) "
          f"across {len(namespaces)} namespaces")
    if not findings:
        print("OK: every in-cluster hostname resolves to a Service gitops declares.")
        return 0

    level = "error" if enforce else "warning"
    for path, ypath, host, reason in findings:
        rel = path.relative_to(REPO) if path.is_absolute() and REPO in path.parents else path
        print(f"::{level} file={rel}::in-cluster host '{host}' at `{ypath.lstrip('.')}` "
              f"does not resolve: {reason}. Nothing in this repo's test layers can catch "
              f"this — a unit test stubs the client and an IT serves localhost — so fix the "
              f"hostname, or declare the Service in openbank-infra/gitops/.")
    print(f"\n{len(findings)} unresolvable in-cluster hostname(s).")
    return 1 if enforce else 0


# ── falsification ──────────────────────────────────────────────────────────────────────
def self_test() -> int:
    """A gate that has only ever passed is unfalsified. Drive BOTH verdicts."""
    import tempfile

    failures = []
    services = {("apicurio-registry", "messaging"), ("keycloak", "iam"), ("admin-ui", "admin-ui")}
    namespaces = {"messaging", "iam", "admin-ui", "analytics", "temporal"}
    corroborated = {("temporal-frontend", "temporal")}

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
