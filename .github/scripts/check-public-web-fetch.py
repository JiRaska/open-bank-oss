#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Detector: is every agent charter that may read the public web bounded the way ADR-0387 decides?
#
# WHY THIS EXISTS
#   ADR-0387 gives agents exactly one way to read the public web: the `web.fetch` MCP tool on the
#   fetch broker, authorised as resource `read.public_web`. What makes that safe is not the tool
#   but three properties of the charter that grants it, and each is a line in agents.yaml that a
#   later edit can quietly remove:
#
#     1. The charter names its hosts. `data_scope.public_web.hosts` is an explicit list of HTTPS
#        hostnames. A wildcard, an IP literal or an empty list turns the allowlist into "the
#        internet", and OPA and the broker would both enforce that faithfully.
#     2. The charter cannot act on what it reads. Fetched content is untrusted and may carry
#        instructions (prompt injection). The blast radius stays a draft issue only while the
#        same charter holds no `write` or `execute` allow.
#     3. Only one workload holds the renderer credential. The Browser Run token is mounted into
#        the broker and nowhere else; a second mount is a second, unbounded egress path.
#
#   ADR-0387 also states which of these the NETWORK layer cannot carry: the cluster's
#   aws-network-policy-agent matches CIDRs, not hostnames, so the host allowlist is enforced in the
#   application layer only. That makes the charter the thing to check, at build time.
#
# WHAT IT CHECKS
#   - every charter allowing `read.public_web` declares a non-empty `data_scope.public_web.hosts`;
#   - every host is a lower-case DNS name with at least one dot: no `*`, no IP literal, no scheme,
#     no port, no path; an entry may be a string or a mapping {host, render: static|js};
#   - such a charter allows no tier `write` or `execute`;
#   - no charter declares `data_scope.public_web` WITHOUT allowing the resource (a dead allowlist
#     reads as a control and is not one);
#   - under openbank-infra/gitops, the Browser Run secret name is referenced by at most one
#     workload, and that workload is the fetch broker;
#   - (ADR-0387 D7) no NetworkPolicy selecting an agent-plane pod reaches the internet, except the
#     broker's own `openbank-fetch-broker-egress`. "Internet" is judged on parsed CIDRs: an ipBlock
#     whose cidr-minus-except leaves anything outside RFC 1918 / link-local / loopback / ULA, and
#     any egress rule with no `to` (which admits every destination). Pod/namespace selector peers,
#     `namespaceSelector: {}` included, are in-cluster and out of scope here;
#   - every agent-plane workload is selected by >=1 NetworkPolicy whose policyTypes lists Egress
#     (an unselected pod has unrestricted egress). Ratchet: today's uncovered workloads are listed
#     in .github/gates/agent-egress-netpol-baseline.txt with a tracking issue; a new uncovered one
#     fails, and a baseline entry that is now covered or gone fails as stale;
#   - the broker's policy lives in the broker's namespace, selects it by matchLabels, selects no
#     other workload, lists Egress, and allows the internet on exactly TCP 443 with no endPort;
#   - across ALL egress rules of ALL policies selecting the broker (policies are additive), no
#     ipBlock reaches 10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10, 0/8, 127/8 or the IPv6
#     ULA / link-local / loopback / IPv4-embedding prefixes, and no rule omits `to`;
#   - namespaces are resolved from the nearest ancestor kustomization.yaml `namespace:` (which
#     overrides metadata.namespace), else metadata.namespace; an agent-plane workload or a
#     NetworkPolicy with neither is a finding — never assumed `default`;
#   - any other *NetworkPolicy kind or apiVersion (CiliumNetworkPolicy, Calico, ...) is a finding:
#     this gate cannot judge it, so it cannot clear it;
#   - a gitops manifest that does not parse is a finding (fail closed).
#
# Run:
#   python3 .github/scripts/check-public-web-fetch.py [--self-test]

import argparse
import ipaddress
import json
import pathlib
import posixpath
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parents[2]
AGENTS_YAML = REPO / "openbank-libs" / "governance" / "agents.yaml"
GITOPS = REPO / "openbank-infra" / "gitops"

RESOURCE = "read.public_web"
SECRET_NAME = "browser-run-api-token"
BROKER = "openbank-fetch-broker"
FORBIDDEN_TIERS = {"write", "execute"}
RENDER_MODES = {"static", "js"}
HOST_RE = re.compile(r"(?=.{4,253})([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}")
# Names that resolve inside the cluster or the host, never on the public web.
INTERNAL_SUFFIXES = (".svc", ".local", ".internal", ".localhost", ".lan", ".home", ".corp", ".intranet", ".cluster.local")
IPV4_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")


def glob_grants(pattern: str, value: str) -> bool:
    """Mirror agents.rego glob_match: exact, or glob.match(pattern, ["."], value).

    OPA's glob with delimiter "." lets `*` and `?` match within one segment and `**` across
    segments, so `read.*` and `*.public_web` both grant read.public_web while a bare `*` does
    not. Deciding "grants" by exact membership would let a glob grant pass this gate unchecked.
    """
    if pattern == value:
        return True
    out = []
    i = 0
    while i < len(pattern):
        if pattern.startswith("**", i):
            out.append(".*")
            i += 2
        elif pattern[i] == "*":
            out.append("[^.]*")
            i += 1
        elif pattern[i] == "?":
            out.append("[^.]")
            i += 1
        else:
            out.append(re.escape(pattern[i]))
            i += 1
    return re.fullmatch("".join(out), value) is not None


def _allows(agent: dict) -> list[dict]:
    tools = agent.get("tools") or {}
    return [a for a in (tools.get("allow") or []) if isinstance(a, dict)]


def _host_of(entry) -> tuple[str | None, str | None]:
    """Return (host, error) for one hosts[] entry."""
    if isinstance(entry, str):
        return entry, None
    if isinstance(entry, dict):
        render = entry.get("render", "static")
        if render not in RENDER_MODES:
            return None, f"render must be one of {sorted(RENDER_MODES)}, got {render!r}"
        host = entry.get("host")
        if not isinstance(host, str):
            return None, "mapping entry needs a string `host`"
        return host, None
    return None, f"host entry must be a string or a mapping, got {type(entry).__name__}"


def check_host(host: str) -> str | None:
    if "*" in host:
        return "wildcard"
    if IPV4_RE.match(host) or ":" in host:
        return "IP literal, scheme or port"
    if "/" in host:
        return "path or scheme"
    if host != host.lower():
        return "not lower-case"
    if not HOST_RE.fullmatch(host):
        return "not a DNS hostname"
    if host.endswith(INTERNAL_SUFFIXES):
        return "internal (non-public) name"
    return None


def evaluate_agents(doc: dict) -> tuple[list[str], int]:
    findings: list[str] = []
    agents = [a for a in (doc.get("agents") or []) if isinstance(a, dict)]
    for agent in agents:
        aid = agent.get("id", "<no id>")
        allows = _allows(agent)
        grants = any(
            glob_grants(str(r), RESOURCE) for a in allows for r in (a.get("resources") or [])
        )
        scope = ((agent.get("data_scope") or {}).get("public_web")) or None

        if not grants:
            if scope is not None:
                findings.append(f"{aid}: declares data_scope.public_web but does not allow {RESOURCE} — a dead allowlist")
            continue

        hosts = scope.get("hosts") if isinstance(scope, dict) else None
        if not isinstance(hosts, list) or not hosts:
            findings.append(f"{aid}: allows {RESOURCE} but data_scope.public_web.hosts is missing or empty")
        else:
            seen: set[str] = set()
            for entry in hosts:
                host, err = _host_of(entry)
                if err:
                    findings.append(f"{aid}: {err}")
                    continue
                why = check_host(host)
                if why:
                    findings.append(f"{aid}: host {host!r} rejected ({why})")
                elif host in seen:
                    findings.append(f"{aid}: host {host!r} listed twice")
                seen.add(host)

        bad = sorted({a.get("tier") for a in allows} & FORBIDDEN_TIERS)
        if bad:
            findings.append(f"{aid}: allows {RESOURCE} together with tier(s) {bad} — fetched content could drive an action")
    return findings, len(agents)


BROKER_DIR = f"openbank-infra/gitops/components/{BROKER}/"


def evaluate_gitops(files: dict[str, str]) -> list[str]:
    """files: repo-relative path -> text.

    Judged per YAML DOCUMENT, not per file: only an ExternalSecret document is exempt (it creates
    the secret, it does not consume it), so bundling one beside a Deployment cannot hide the
    Deployment's mount. A consumer is allowed only inside the broker's own component directory
    AND only when the document's metadata.name is the broker — a path substring is not identity.
    """
    findings: list[str] = []
    for path, text in sorted(files.items()):
        try:
            docs = gatelib.loads_all(text)
        except Exception as e:  # noqa: BLE001 — an unparseable manifest cannot be cleared
            findings.append(f"{path}: cannot parse while it references {SECRET_NAME} ({e})")
            continue
        for doc in docs:
            if not isinstance(doc, dict) or SECRET_NAME not in repr(doc):
                continue
            if doc.get("kind") == "ExternalSecret":
                continue
            name = (doc.get("metadata") or {}).get("name")
            if not (path.startswith(BROKER_DIR) and name == BROKER):
                findings.append(
                    f"{path}: {doc.get('kind')}/{name} references {SECRET_NAME} — only {BROKER} in {BROKER_DIR} may"
                )
    return findings


# --- network layer (ADR-0387 D7) ---------------------------------------------------------
# The agent plane is DERIVED, never listed here: a workload is agent-plane when one of its
# containers runs an image of an AGPL agent module (rules.yaml agpl_modules, the canonical list
# of that plane). Its pods' labels decide which NetworkPolicies are agent-plane.
WORKLOAD_KINDS = {"Deployment", "Rollout", "StatefulSet", "DaemonSet", "Job", "CronJob"}
REQUIRED_EXCEPT = {"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "169.254.169.254/32",
                   "100.64.0.0/10", "0.0.0.0/8", "127.0.0.0/8"}
KUSTOMIZATION_FILES = {"kustomization.yaml", "kustomization.yml", "Kustomization"}
BASELINE = REPO / ".github" / "gates" / "agent-egress-netpol-baseline.txt"
BROKER_EGRESS = f"{BROKER}-egress"
RULES_YAML = REPO / "openbank-libs" / "governance" / "rules.yaml"


def agpl_modules(rules: dict) -> set[str]:
    exc = ((rules.get("dependencies") or {}).get("license_boundary_exceptions") or [{}])[0] or {}
    return {str(m) for m in (exc.get("agpl_modules") or [])}


def _image_module(image: str) -> str:
    name = image.rsplit("/", 1)[-1]
    return re.split(r"[:@]", name, maxsplit=1)[0]


def _pod_template(doc: dict) -> dict:
    spec = doc.get("spec") or {}
    if doc.get("kind") == "CronJob":
        spec = ((spec.get("jobTemplate") or {}).get("spec")) or {}
    return spec.get("template") or {}


def agent_plane_pods(docs: list[dict], modules: set[str]) -> list[tuple[str, dict]]:
    """(namespace, pod labels) of every workload running an agent-plane image."""
    out = []
    for doc in docs:
        if doc.get("kind") not in WORKLOAD_KINDS:
            continue
        tpl = _pod_template(doc)
        pspec = tpl.get("spec") or {}
        images = [c.get("image", "") for c in (pspec.get("containers") or []) + (pspec.get("initContainers") or [])
                  if isinstance(c, dict)]
        if any(_image_module(str(i)) in modules for i in images):
            ns = (doc.get("metadata") or {}).get("namespace", "default")
            out.append((ns, (tpl.get("metadata") or {}).get("labels") or {}))
    return out


def _selects(selector: dict | None, labels: dict) -> bool:
    selector = selector or {}
    if selector.get("matchExpressions"):
        return True  # conservative: an expression selector is treated as matching
    return all(labels.get(k) == v for k, v in (selector.get("matchLabels") or {}).items())


# Destinations that are NOT the public internet. An ipBlock is "internet egress" when anything is
# left of it, after its `except`, outside these ranges — so splitting 0.0.0.0/0 into 0.0.0.0/1 +
# 128.0.0.0/1, or writing 8.0.0.0/7 or 2000::/3, is internet egress exactly like 0.0.0.0/0.
NON_INTERNET = tuple(
    ipaddress.ip_network(n)
    for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8",
              "fc00::/7", "fe80::/10", "::1/128")
)
REQUIRED_EXCEPT_NETS = tuple(ipaddress.ip_network(n) for n in REQUIRED_EXCEPT)
# What the BROKER's internet rule must leave unreachable, both families. IPv6 is judged by effect
# rather than forbidden: nothing in this repo pins the cluster's IP family, so a v6 block is
# allowed only when it reaches none of ULA, link-local, loopback or IPv4-mapped space (which
# would carry every IPv4 private range back in).
BROKER_FORBIDDEN_NETS = REQUIRED_EXCEPT_NETS + tuple(
    ipaddress.ip_network(n)
    for n in (
        "fc00::/7", "fe80::/10", "::1/128", "::/128",
        # IPv4-embedding and translation prefixes: each can carry a private or link-local IPv4
        # destination inside an IPv6 address (mapped, translated, NAT64 well-known and local-use,
        # 6to4, discard-only).
        "::ffff:0:0/96", "::ffff:0:0:0/96", "64:ff9b::/96", "64:ff9b:1::/48", "2002::/16", "100::/64",
    )
)


class PolicyError(ValueError):
    """A NetworkPolicy this gate cannot read is a finding, never a skip."""


def _residual(ip: dict) -> list:
    """The networks an ipBlock admits: its cidr minus every except entry."""
    try:
        nets = [ipaddress.ip_network(str(ip.get("cidr")), strict=False)]
        excepts = [ipaddress.ip_network(str(e), strict=False) for e in (ip.get("except") or [])]
    except ValueError as e:
        raise PolicyError(f"unparseable ipBlock {ip!r}: {e}") from e
    for ex in excepts:
        out = []
        for n in nets:
            if n.version != ex.version or not n.overlaps(ex):
                out.append(n)
            elif n.subnet_of(ex):
                continue
            elif ex.subnet_of(n):
                out.extend(n.address_exclude(ex))
            else:
                out.append(n)
        nets = out
    return nets


def _is_internet(nets: list) -> bool:
    return any(not any(n.version == m.version and n.subnet_of(m) for m in NON_INTERNET) for n in nets)


def _internet_rules(policy: dict) -> list[tuple[list, list, bool]]:
    """(residual networks, ports, open_to) for every egress rule that reaches the internet.

    A rule with a missing or empty `to` admits every destination, so it is internet egress with no
    except at all (open_to=True). A namespaceSelector/podSelector peer — including
    `namespaceSelector: {}` — selects in-cluster pods only and is never internet egress here; the
    ingress side of every callee bounds those edges.
    """
    found = []
    for rule in ((policy.get("spec") or {}).get("egress") or []):
        if not isinstance(rule, dict):
            raise PolicyError(f"egress rule is not a mapping: {rule!r}")
        peers = rule.get("to")
        ports = rule.get("ports") or []
        if not peers:
            found.append(([ipaddress.ip_network("0.0.0.0/0"), ipaddress.ip_network("::/0")], ports, True))
            continue
        for peer in peers:
            ip = (peer or {}).get("ipBlock")
            if ip is None:
                continue
            nets = _residual(ip)
            if _is_internet(nets):
                found.append((nets, ports, False))
    return found


def _port_set(ports: list) -> set | None:
    """{(protocol, port)}; None when the rule is not a plain list of single ports (no ports = all,
    endPort = a range, a named or missing port = unknowable here)."""
    if not ports:
        return None
    out = set()
    for p in ports:
        if not isinstance(p, dict) or "endPort" in p or not isinstance(p.get("port"), int):
            return None
        out.add((str(p.get("protocol", "TCP")), p["port"]))
    return out


def _kustomize_namespaces(docs_by_path: dict[str, list[dict] | None]) -> dict[str, str]:
    """directory -> the `namespace:` a kustomization.yaml in that directory sets."""
    out = {}
    for path, docs in docs_by_path.items():
        if posixpath.basename(path) not in KUSTOMIZATION_FILES:
            continue
        for doc in docs or []:
            if isinstance(doc, dict) and isinstance(doc.get("namespace"), str) and doc["namespace"]:
                out[posixpath.dirname(path)] = doc["namespace"]
    return out


def _resolve_ns(path: str, meta: dict, kns: dict[str, str]) -> str | None:
    """Effective namespace: the nearest ancestor kustomization's `namespace:` (kustomize
    overrides metadata.namespace), else metadata.namespace, else None — unresolvable. Never
    "default": a guessed namespace is how a policy and the pod it is meant for stop meeting."""
    d = posixpath.dirname(path)
    while True:
        if d in kns:
            return kns[d]
        if not d:
            break
        d = posixpath.dirname(d)
    ns = meta.get("namespace")
    return ns if isinstance(ns, str) and ns else None


def _forbidden_overlap(policy: dict) -> list[str]:
    """Every forbidden range ANY egress rule of this policy can reach — ipBlock residuals of all
    rules, internet or not, plus a rule with no `to` (which reaches everything)."""
    hit: set[str] = set()
    for rule in ((policy.get("spec") or {}).get("egress") or []):
        if not isinstance(rule, dict):
            raise PolicyError(f"egress rule is not a mapping: {rule!r}")
        peers = rule.get("to")
        if not peers:
            hit.update(str(r) for r in BROKER_FORBIDDEN_NETS)
            continue
        for peer in peers:
            ip = (peer or {}).get("ipBlock")
            if ip is None:
                continue
            for n in _residual(ip):
                hit.update(str(r) for r in BROKER_FORBIDDEN_NETS if n.version == r.version and n.overlaps(r))
    return sorted(hit)


def _has_egress_type(policy: dict) -> bool:
    return "Egress" in (((policy.get("spec") or {}).get("policyTypes")) or [])


def load_baseline(path: pathlib.Path) -> tuple[dict[str, str], list[str]]:
    """name -> tracking ref. Every entry must carry an issue reference (`#<n>`)."""
    entries: dict[str, str] = {}
    errors: list[str] = []
    if not path.is_file():
        return entries, [f"{path.relative_to(REPO)} is missing"]
    for i, raw in enumerate(gatelib.read_text(path).splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        name, _, ref = line.partition(" ")
        m = re.search(r"#\d+", ref)
        if not m:
            errors.append(f"{path.relative_to(REPO)}:{i}: {name} has no tracking issue (#<n>)")
        entries[name] = m.group(0) if m else ""
    return entries, errors


def evaluate_network(docs_by_path: dict[str, list[dict] | None], modules: set[str],
                     baseline: dict[str, str] | None = None) -> list[str]:
    findings: list[str] = []
    baseline = baseline or {}
    for path, docs in sorted(docs_by_path.items()):
        if docs is None:
            findings.append(f"{path}: cannot be parsed — a manifest this gate cannot read cannot be cleared")
    kns = _kustomize_namespaces(docs_by_path)
    # Broker identity is the WORKLOAD, never a label: a manifest under BROKER_DIR whose
    # metadata.name is the broker and whose every openbank image is the broker module. Any other
    # workload wearing the broker's name is itself a finding and counts as an "other" pod, so a
    # look-alike cannot borrow the broker's internet egress.
    broker_pods: list[tuple[str, dict]] = []
    other_pods: list[tuple[str, dict]] = []
    all_other_pods: list[tuple[str, dict]] = []
    agent_workloads: list[tuple[str, str, str, dict]] = []  # (path, name, ns, labels)
    policies: list[tuple[str, dict, str]] = []  # (path, doc, ns)
    for path, docs in sorted(docs_by_path.items()):
        for doc in docs or []:
            if not isinstance(doc, dict):
                continue
            kind = str(doc.get("kind") or "")
            meta = doc.get("metadata") or {}
            if kind.endswith("NetworkPolicy"):
                if kind != "NetworkPolicy" or doc.get("apiVersion", "networking.k8s.io/v1") != "networking.k8s.io/v1":
                    findings.append(f"{path}: {doc.get('apiVersion')}/{kind} {meta.get('name')} — this gate judges only "
                                    f"networking.k8s.io/v1 NetworkPolicy and cannot clear another policy engine's rules")
                    continue
                ns = _resolve_ns(path, meta, kns)
                if ns is None:
                    findings.append(f"{path}: NetworkPolicy {meta.get('name')} has no resolvable namespace "
                                    f"(no metadata.namespace, no kustomization namespace:)")
                    continue
                policies.append((path, doc, ns))
                continue
            if kind not in WORKLOAD_KINDS:
                continue
            tpl = _pod_template(doc)
            labels = (tpl.get("metadata") or {}).get("labels") or {}
            pspec = tpl.get("spec") or {}
            mods = {_image_module(str(c.get("image", ""))) for c in
                    (pspec.get("containers") or []) + (pspec.get("initContainers") or []) if isinstance(c, dict)}
            app_mods = {m for m in mods if m.startswith("openbank-")}
            wears_name = BROKER in (labels.get("app.kubernetes.io/name"), meta.get("name"))
            is_agent = bool(mods & modules) or wears_name
            ns = _resolve_ns(path, meta, kns)
            if ns is None:
                if is_agent:
                    findings.append(f"{path}: {kind}/{meta.get('name')} is agent-plane and has no resolvable namespace "
                                    f"(no metadata.namespace, no kustomization namespace:) — cannot be judged")
                continue
            if path.startswith(BROKER_DIR) and meta.get("name") == BROKER and app_mods == {BROKER}:
                broker_pods.append((ns, labels))
                continue
            if wears_name:
                findings.append(f"{path}: {kind}/{meta.get('name')} carries the broker's name but is not the broker workload")
            # EVERY non-broker workload is an "other" pod for the broker selector check; the
            # agent-plane subset decides which other policies this gate judges at all.
            all_other_pods.append((ns, labels))
            if is_agent:
                other_pods.append((ns, labels))
                agent_workloads.append((path, str(meta.get("name")), ns, labels))

    # Coverage (ratchet): an agent-plane pod selected by NO Egress policy has unrestricted egress,
    # which no rule check above can see — there is no rule to read.
    uncovered = set()
    for path, name, ns, labels in agent_workloads:
        if not any(pns == ns and _has_egress_type(p) and _selects((p.get("spec") or {}).get("podSelector"), labels)
                   for _, p, pns in policies):
            uncovered.add(name)
            if name not in baseline:
                findings.append(f"{path}: agent-plane workload {ns}/{name} is selected by no NetworkPolicy with "
                                f"policyTypes Egress — its egress is unrestricted (ADR-0387 D7)")
    present = {n for _, n, _, _ in agent_workloads}
    for name in sorted(set(baseline) - uncovered):
        why = "is now covered by an Egress policy" if name in present else "is no longer an agent-plane workload"
        findings.append(f"{BASELINE.relative_to(REPO)}: stale entry {name} ({baseline[name]}) {why} — remove it")

    broker_ns = {ns for ns, _ in broker_pods}
    broker_seen = False
    for path, doc, ns in policies:
        meta = doc.get("metadata") or {}
        name = meta.get("name")
        sel = (doc.get("spec") or {}).get("podSelector")
        hits_other = any(pns == ns and _selects(sel, labels) for pns, labels in other_pods)
        hits_broker = any(pns == ns and _selects(sel, labels) for pns, labels in broker_pods)
        is_broker = path.startswith(BROKER_DIR) and name == BROKER_EGRESS
        if not (hits_other or hits_broker or is_broker):
            continue
        try:
            rules = _internet_rules(doc)
            # Every policy selecting the broker, every rule: policies are additive, so one extra
            # rule anywhere reopens what the broker policy's except closed.
            leaked = _forbidden_overlap(doc) if (hits_broker or is_broker) else []
        except PolicyError as e:
            findings.append(f"{path}: {ns}/{name}: {e}")
            continue
        if leaked:
            findings.append(f"{path}: {ns}/{name} selects the broker and lets it reach forbidden range(s) {leaked}")
        if not is_broker:
            if rules:
                findings.append(
                    f"{path}: NetworkPolicy {ns}/{name} gives agent-plane pods internet egress — "
                    f"only {BROKER_EGRESS} in {BROKER_DIR} may (ADR-0387 D7)"
                )
            continue
        broker_seen = True
        # Identity: the broker's policy must select the broker and nothing else.
        match_labels = (sel or {}).get("matchLabels") or {}
        if ns not in broker_ns:
            findings.append(f"{path}: {name} is in namespace {ns}, the broker runs in {sorted(broker_ns)}")
        if match_labels.get("app.kubernetes.io/name") != BROKER or (sel or {}).get("matchExpressions"):
            findings.append(f"{path}: {name} must select by matchLabels including app.kubernetes.io/name: {BROKER}, not {sel!r}")
        if not hits_broker:
            findings.append(f"{path}: {name} selects no identified broker workload")
        if any(pns == ns and _selects(sel, labels) for pns, labels in all_other_pods):
            findings.append(f"{path}: {name} also selects a workload that is not the broker — internet egress would reach it")
        if not _has_egress_type(doc):
            findings.append(f"{path}: {name} does not list Egress in policyTypes")
        if not rules:
            findings.append(f"{path}: {name} has no internet egress rule — the broker cannot fetch")
        for nets, ports, open_to in rules:
            if open_to:
                findings.append(f"{path}: {name} has an egress rule with no `to` — every destination, no except")
                continue
            got = _port_set(ports)
            if got != {("TCP", 443)}:
                findings.append(f"{path}: {name} internet egress must be exactly TCP 443 (no endPort), got {ports or 'all ports'}")
    if broker_pods and not broker_seen:
        findings.append(f"{BROKER} is deployed but {BROKER_DIR} declares no NetworkPolicy {BROKER_EGRESS}")
    return findings


def _gitops_docs() -> dict[str, list[dict] | None]:
    """path -> documents, or None when the file does not parse (a finding, fail closed)."""
    out: dict[str, list[dict] | None] = {}
    if not GITOPS.is_dir():
        return out
    for p in (*gatelib.rglob(GITOPS, "*.yaml"), *gatelib.rglob(GITOPS, "*.yml")):
        rel = str(p.relative_to(REPO))
        try:
            docs = gatelib.loads_all(gatelib.read_text(p, errors="replace"))
        except Exception:  # noqa: BLE001 — reported by evaluate_network
            out[rel] = None
            continue
        out[rel] = [d for d in docs if isinstance(d, dict)]
    return out


def _gitops_files() -> dict[str, str]:
    if not GITOPS.is_dir():
        return {}
    out = {}
    for p in (*gatelib.rglob(GITOPS, "*.yaml"), *gatelib.rglob(GITOPS, "*.yml")):
        text = gatelib.read_text(p, errors="replace")
        if SECRET_NAME in text:
            out[str(p.relative_to(REPO))] = text
    return out


def self_test() -> int:
    def agent(aid, hosts=None, tiers=("read",), resource=RESOURCE, scope=True):
        a = {"id": aid, "tools": {"allow": [{"tier": t, "resources": [resource]} for t in tiers]}}
        if scope:
            a["data_scope"] = {"public_web": {"hosts": hosts}}
        return a

    cases = [
        ("good static + js", [agent("a", ["eur-lex.europa.eu", {"host": "www.cnb.cz", "render": "js"}])], 0),
        ("wildcard", [agent("a", ["*.europa.eu"])], 1),
        ("ip literal", [agent("a", ["10.0.0.1"])], 1),
        ("scheme in host", [agent("a", ["https://cnb.cz"])], 1),
        ("upper case", [agent("a", ["CNB.cz"])], 1),
        ("empty hosts", [agent("a", [])], 1),
        ("missing scope", [agent("a", scope=False)], 1),
        ("write tier", [agent("a", ["cnb.cz"], tiers=("read", "write"))], 1),
        ("execute tier", [agent("a", ["cnb.cz"], tiers=("read", "execute"))], 1),
        ("bad render", [agent("a", [{"host": "cnb.cz", "render": "full"}])], 1),
        ("duplicate", [agent("a", ["cnb.cz", "cnb.cz"])], 1),
        ("dead allowlist", [agent("a", ["cnb.cz"], resource="read.governance")], 1),
        ("no web, no scope", [agent("a", resource="read.governance", scope=False)], 0),
        ("glob read.* grants, no scope", [agent("a", resource="read.*", scope=False)], 1),
        ("glob *.public_web grants, no scope", [agent("a", resource="*.public_web", scope=False)], 1),
        ("glob ** grants, no scope", [agent("a", resource="**", scope=False)], 1),
        ("bare * does not grant (OPA . delimiter)", [agent("a", resource="*", scope=False)], 0),
        ("trailing newline host", [agent("a", ["cnb.cz\n"])], 1),
        ("cluster-internal host", [agent("a", ["litellm.ai-platform.svc"])], 1),
        ("cluster.local host", [agent("a", ["x.default.svc.cluster.local"])], 1),
        ("localhost host", [agent("a", ["app.localhost"])], 1),
    ]
    failed = 0
    for name, agents, want in cases:
        got = len(evaluate_agents({"agents": agents})[0])
        ok = (got > 0) == (want > 0)
        failed += not ok
        print(f"  {'ok ' if ok else 'BAD'} agents/{name}: findings={got}")

    def dep(name):
        return f"kind: Deployment\nmetadata:\n  name: {name}\nspec:\n  secretName: {SECRET_NAME}\n"

    es = f"kind: ExternalSecret\nmetadata:\n  name: x\nspec:\n  target:\n    name: {SECRET_NAME}\n"
    bdir = BROKER_DIR
    gcases = [
        ("broker only", {bdir + "deployment.yaml": dep(BROKER)}, 0),
        ("externalsecret alone ignored", {"openbank-infra/gitops/x/es.yaml": es}, 0),
        ("second mount", {bdir + "d.yaml": dep(BROKER), "openbank-infra/gitops/components/agent/d.yaml": dep("agent")}, 1),
        ("ES bundled with foreign Deployment", {"openbank-infra/gitops/x/all.yaml": es + "---\n" + dep("agent")}, 1),
        ("path substring is not identity", {f"openbank-infra/gitops/components/not-{BROKER}/d.yaml": dep(BROKER)}, 1),
        ("other workload in broker dir", {bdir + "d.yaml": dep("sidecar-thief")}, 1),
    ]
    for name, files, want in gcases:
        got = len(evaluate_gitops(files))
        ok = (got > 0) == (want > 0)
        failed += not ok
        print(f"  {'ok ' if ok else 'BAD'} gitops/{name}: findings={got}")

    mods = {"openbank-docs-truth-agent", BROKER}

    def wl(name, ns, module):
        return {"kind": "Deployment", "metadata": {"name": name, "namespace": ns},
                "spec": {"template": {"metadata": {"labels": {"app.kubernetes.io/name": name}},
                                      "spec": {"containers": [{"image": f"reg.example/{module}:sandbox-1@sha256:ab"}]}}}}

    def np(name, ns, app=None, cidr="0.0.0.0/0", except_=None, port=443):
        ip = {"cidr": cidr}
        if except_ is not None:
            ip["except"] = except_
        sel = {"matchLabels": {"app.kubernetes.io/name": app}} if app else {}
        return {"kind": "NetworkPolicy", "metadata": {"name": name, "namespace": ns},
                "spec": {"podSelector": sel, "policyTypes": ["Egress"],
                         "egress": [{"to": [{"ipBlock": ip}], "ports": [{"protocol": "TCP", "port": port}]}]}}

    full = sorted(REQUIRED_EXCEPT)
    broker_wl = wl(BROKER, "fetch-broker", BROKER)
    good_broker = np(BROKER_EGRESS, "fetch-broker", BROKER, except_=full)
    agent_wl = wl("docs-truth-agent", "docs-truth-agent", "openbank-docs-truth-agent")
    ncases = [
        ("broker egress with all exceptions", {bdir + "w.yaml": [broker_wl], bdir + "np.yaml": [good_broker]}, 0),
        ("broker without the /32 is still covered by 169.254.0.0/16 (effect, not text)", {bdir + "w.yaml": [broker_wl],
                                            bdir + "np.yaml": [np(BROKER_EGRESS, "fetch-broker", BROKER, except_=[c for c in full if c != "169.254.169.254/32"])]}, 0),
        ("broker missing link-local /16 except", {bdir + "w.yaml": [broker_wl],
                                                  bdir + "np.yaml": [np(BROKER_EGRESS, "fetch-broker", BROKER, except_=[c for c in full if c != "169.254.0.0/16"])]}, 1),
        ("broker missing RFC1918 except", {bdir + "w.yaml": [broker_wl],
                                           bdir + "np.yaml": [np(BROKER_EGRESS, "fetch-broker", BROKER, except_=["169.254.0.0/16", "169.254.169.254/32"])]}, 1),
        ("broker no except at all", {bdir + "w.yaml": [broker_wl], bdir + "np.yaml": [np(BROKER_EGRESS, "fetch-broker", BROKER)]}, 1),
        ("broker internet on port 80", {bdir + "w.yaml": [broker_wl],
                                        bdir + "np.yaml": [np(BROKER_EGRESS, "fetch-broker", BROKER, except_=full, port=80)]}, 1),
        ("broker deployed, egress policy absent", {bdir + "w.yaml": [broker_wl]}, 1),
        ("agent pod with internet egress", {"x/w.yaml": [agent_wl],
                                            "x/np.yaml": [np("a-egress", "docs-truth-agent", "docs-truth-agent")]}, 1),
        ("agent ns-wide (empty selector) internet egress", {"x/w.yaml": [agent_wl],
                                                            "x/np.yaml": [np("a-egress", "docs-truth-agent")]}, 1),
        ("agent ::/0 egress", {"x/w.yaml": [agent_wl], "x/np.yaml": [np("a", "docs-truth-agent", cidr="::/0")]}, 1),
        ("agent egress even WITH the exceptions", {"x/w.yaml": [agent_wl],
                                                   "x/np.yaml": [np("a", "docs-truth-agent", except_=full)]}, 1),
        ("broker-named policy outside broker dir", {"x/w.yaml": [agent_wl],
                                                    "x/np.yaml": [np(BROKER_EGRESS, "docs-truth-agent")]}, 1),
        ("non-agent workload internet egress ignored", {"x/w.yaml": [wl("litellm", "ai-platform", "litellm")],
                                                        "x/np.yaml": [np("litellm-egress", "ai-platform", "litellm")]}, 0),
        ("agent pod with a private-only ipBlock", {"x/w.yaml": [agent_wl],
                                                   "x/np.yaml": [np("a", "docs-truth-agent", cidr="10.0.0.0/8")]}, 0),
    ]

    def raw_np(name, ns, sel, egress):
        return {"kind": "NetworkPolicy", "metadata": {"name": name, "namespace": ns},
                "spec": {"podSelector": sel, "policyTypes": ["Egress"], "egress": egress}}

    bsel = {"matchLabels": {"app.kubernetes.io/name": BROKER}}
    asel = {"matchLabels": {"app.kubernetes.io/name": "docs-truth-agent"}}
    t443 = [{"protocol": "TCP", "port": 443}]

    def ipb(cidr, except_=None):
        b = {"cidr": cidr}
        if except_ is not None:
            b["except"] = except_
        return {"ipBlock": b}

    def tagged(w):
        """A copy of workload w whose pods also carry part-of=fetch-broker."""
        w = json.loads(json.dumps(w))
        w["spec"]["template"]["metadata"]["labels"]["app.kubernetes.io/part-of"] = "fetch-broker"
        return w

    awl = {"x/w.yaml": [agent_wl]}

    def nons(w):
        w = json.loads(json.dumps(w))
        del w["metadata"]["namespace"]
        return w
    bwl = {bdir + "w.yaml": [broker_wl]}
    ncases += [
        # 1. a rule with no `to` admits every destination
        ("agent egress rule with no `to`", {**awl, "x/np.yaml": [raw_np("a", "docs-truth-agent", asel, [{"ports": t443}])]}, 1),
        ("agent egress rule with empty `to`", {**awl, "x/np.yaml": [raw_np("a", "docs-truth-agent", asel, [{"to": []}])]}, 1),
        ("broker egress rule with no `to`", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel, [{"ports": t443}])]}, 1),
        ("agent namespaceSelector {} is in-cluster, not internet",
         {**awl, "x/np.yaml": [raw_np("a", "docs-truth-agent", asel, [{"to": [{"namespaceSelector": {}}]}])]}, 0),
        # 2. CIDRs are parsed, not string-compared
        ("agent 0.0.0.0/1 + 128.0.0.0/1", {**awl, "x/np.yaml": [raw_np("a", "docs-truth-agent", asel,
                                                                        [{"to": [ipb("0.0.0.0/1"), ipb("128.0.0.0/1")], "ports": t443}])]}, 1),
        ("agent 8.0.0.0/7", {**awl, "x/np.yaml": [raw_np("a", "docs-truth-agent", asel, [{"to": [ipb("8.0.0.0/7")], "ports": t443}])]}, 1),
        ("agent IPv6 2000::/3", {**awl, "x/np.yaml": [raw_np("a", "docs-truth-agent", asel, [{"to": [ipb("2000::/3")], "ports": t443}])]}, 1),
        ("agent 0.0.0.0/0 minus everything public is still internet",
         {**awl, "x/np.yaml": [raw_np("a", "docs-truth-agent", asel, [{"to": [ipb("0.0.0.0/0", ["10.0.0.0/8"])], "ports": t443}])]}, 1),
        ("broker split /1 halves without excepts", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
                                                                                    [{"to": [ipb("0.0.0.0/1"), ipb("128.0.0.0/1", full)], "ports": t443}])]}, 1),
        ("broker split /1 halves, both excepted", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
                                                                                   [{"to": [ipb("0.0.0.0/1", ["10.0.0.0/8", "0.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8"]),
                                                                                             ipb("128.0.0.0/1", ["172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"])], "ports": t443}])]}, 0),
        ("broker unparseable cidr", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel, [{"to": [ipb("not-a-cidr")], "ports": t443}])]}, 1),
        # 3. broker identity
        ("broker policy in another namespace", {**bwl, **awl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "docs-truth-agent", {}, [{"to": [ipb("0.0.0.0/0", full)], "ports": t443}])]}, 1),
        ("broker policy with empty podSelector", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", {}, [{"to": [ipb("0.0.0.0/0", full)], "ports": t443}])]}, 1),
        ("broker policy also selects a co-located agent",
         {**bwl, "x/w2.yaml": [wl("docs-truth-agent", "fetch-broker", "openbank-docs-truth-agent")],
          bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", {"matchLabels": {"app.kubernetes.io/part-of": "fetch-broker"}},
                                    [{"to": [ipb("0.0.0.0/0", full)], "ports": t443}])]}, 1),
        ("broker policy by matchExpressions", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker",
                                                                                {"matchExpressions": [{"key": "x", "operator": "Exists"}]},
                                                                                [{"to": [ipb("0.0.0.0/0", full)], "ports": t443}])]}, 1),
        # 4. ports
        ("broker endPort range", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
                                                                  [{"to": [ipb("0.0.0.0/0", full)], "ports": [{"protocol": "TCP", "port": 443, "endPort": 65535}]}])]}, 1),
        ("broker 443 plus 80", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
                                                                [{"to": [ipb("0.0.0.0/0", full)], "ports": t443 + [{"protocol": "TCP", "port": 80}]}])]}, 1),
        ("broker no ports (= all ports)", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel, [{"to": [ipb("0.0.0.0/0", full)]}])]}, 1),
        ("broker UDP 443", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
                                                            [{"to": [ipb("0.0.0.0/0", full)], "ports": [{"protocol": "UDP", "port": 443}]}])]}, 1),
        # 6. IPv6 on the broker is judged by what it reaches
        ("broker ::/0 with no except", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
                                                                        [{"to": [ipb("0.0.0.0/0", full), ipb("::/0")], "ports": t443}])]}, 1),
        ("broker ::/0 leaving IPv4-mapped space", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
                                                                                   [{"to": [ipb("::/0", ["fc00::/7", "fe80::/10", "::1/128"])], "ports": t443}])]}, 1),
        ("broker 2000::/3 still reaches 6to4", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
                                                                                [{"to": [ipb("2000::/3")], "ports": t443}])]}, 1),
        ("broker 2000::/3 excepting 6to4", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
                                                                            [{"to": [ipb("2000::/3", ["2002::/16"])], "ports": t443}])]}, 0),
        ("broker ::/0 reaching NAT64", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
                                                                        [{"to": [ipb("::/0", ["fc00::/7", "fe80::/10", "::1/128", "::/128", "::ffff:0:0/96",
                                                                                              "::ffff:0:0:0/96", "2002::/16", "100::/64"])], "ports": t443}])]}, 1),
        ("broker ::/0 excepting every forbidden prefix", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
                                                                                          [{"to": [ipb("::/0", ["fc00::/7", "fe80::/10", "::1/128", "::/128", "::ffff:0:0/96",
                                                                                                                "::ffff:0:0:0/96", "64:ff9b::/96", "64:ff9b:1::/48",
                                                                                                                "2002::/16", "100::/64"])], "ports": t443}])]}, 0),
        # 7. broker identity is the workload, not a label
        ("look-alike agent wearing the broker label", {**bwl, bdir + "np.yaml": [good_broker],
                                                       "x/w.yaml": [wl(BROKER, "fetch-broker", "openbank-docs-truth-agent")]}, 1),
        ("look-alike non-agent image wearing the broker label", {**bwl, bdir + "np.yaml": [good_broker],
                                                                 "x/w.yaml": [wl(BROKER, "fetch-broker", "busybox")]}, 1),
        ("broker-named workload in broker dir with a foreign image",
         {bdir + "w.yaml": [wl(BROKER, "fetch-broker", "openbank-docs-truth-agent")], bdir + "np.yaml": [good_broker]}, 1),
        ("unrelated workload in broker ns with the broker's full matchLabels",
         {**bwl, bdir + "np.yaml": [good_broker],
          "y/w.yaml": [{"kind": "Deployment", "metadata": {"name": "sidecar-job", "namespace": "fetch-broker"},
                        "spec": {"template": {"metadata": {"labels": {"app.kubernetes.io/name": BROKER}},
                                              "spec": {"containers": [{"image": "docker.io/library/busybox:1"}]}}}}]}, 1),
        ("unrelated workload matching a broad broker selector (no name label)",
         {bdir + "w.yaml": [tagged(broker_wl)], "y/w.yaml": [tagged(wl("cache", "fetch-broker", "redis"))],
          bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", {"matchLabels": {"app.kubernetes.io/part-of": "fetch-broker"}},
                                    [{"to": [ipb("0.0.0.0/0", full)], "ports": t443}])]}, 1),
        ("broker selector missing the name label", {bdir + "w.yaml": [tagged(broker_wl)],
                                                    bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker",
                                                                              {"matchLabels": {"app.kubernetes.io/part-of": "fetch-broker"}},
                                                                              [{"to": [ipb("0.0.0.0/0", full)], "ports": t443}])]}, 1),
        ("tagged broker with the exact name selector is clean", {bdir + "w.yaml": [tagged(broker_wl)], bdir + "np.yaml": [good_broker]}, 0),
        # 8. coverage ratchet: an agent pod no Egress policy selects has unrestricted egress
        ("new agent selected by no Egress policy", awl, 1),
        ("agent covered only by an Ingress-only policy", {**awl, "x/np.yaml": [{"kind": "NetworkPolicy",
            "metadata": {"name": "i", "namespace": "docs-truth-agent"}, "spec": {"podSelector": {}, "policyTypes": ["Ingress"]}}]}, 1),
        # 9. every policy and every rule that selects the broker is judged, not just the named one
        ("extra policy gives the broker 169.254.169.254/32", {**bwl, bdir + "np.yaml": [good_broker],
            "z/np.yaml": [raw_np("x", "fetch-broker", {}, [{"to": [ipb("169.254.169.254/32")]}])]}, 1),
        ("extra 10.0.0.0/8 rule inside the broker policy", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
            [{"to": [ipb("0.0.0.0/0", full)], "ports": t443}, {"to": [ipb("10.0.0.0/8")]}])]}, 1),
        ("broker CGNAT 100.64.0.0/10 on 443", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
            [{"to": [ipb("0.0.0.0/0", full)], "ports": t443}, {"to": [ipb("100.64.0.0/10")], "ports": t443}])]}, 1),
        ("broker reaching 127.0.0.0/8", {**bwl, bdir + "np.yaml": [raw_np(BROKER_EGRESS, "fetch-broker", bsel,
            [{"to": [ipb("0.0.0.0/0", [c for c in full if c != "127.0.0.0/8"])], "ports": t443}])]}, 1),
        ("extra policy with no `to` selecting the broker", {**bwl, bdir + "np.yaml": [good_broker],
            "z/np.yaml": [raw_np("x", "fetch-broker", {}, [{"ports": [{"protocol": "UDP", "port": 53}]}])]}, 1),
        # 10. namespace resolution: kustomize namespace:, else metadata, else a finding
        ("agent with no namespace anywhere", {"x/w.yaml": [nons(agent_wl)],
            "x/np.yaml": [np("a-egress", "docs-truth-agent", "docs-truth-agent", cidr="10.0.0.0/8")]}, 1),
        ("agent namespace from kustomization, internet policy there", {"x/kustomization.yaml": [{"kind": "Kustomization", "namespace": "docs-truth-agent"}],
            "x/w.yaml": [nons(agent_wl)], "y/np.yaml": [np("a", "docs-truth-agent")]}, 1),
        ("agent namespace from parent kustomization, covered", {"x/kustomization.yaml": [{"kind": "Kustomization", "namespace": "docs-truth-agent"}],
            "x/sub/w.yaml": [nons(agent_wl)], "y/np.yaml": [np("a", "docs-truth-agent", "docs-truth-agent", cidr="10.0.0.0/8")]}, 0),
        ("kustomization namespace overrides metadata.namespace", {"x/kustomization.yaml": [{"kind": "Kustomization", "namespace": "other"}],
            "x/w.yaml": [agent_wl], "y/np.yaml": [np("a", "docs-truth-agent", "docs-truth-agent", cidr="10.0.0.0/8")]}, 1),
        # 11. a policy engine this gate cannot read is a finding
        ("CiliumNetworkPolicy under gitops", {**awl, "x/np.yaml": [np("a", "docs-truth-agent", "docs-truth-agent", cidr="10.0.0.0/8")],
            "x/c.yaml": [{"apiVersion": "cilium.io/v2", "kind": "CiliumNetworkPolicy", "metadata": {"name": "c", "namespace": "docs-truth-agent"},
                          "spec": {"endpointSelector": {}, "egress": [{"toEntities": ["world"]}]}}]}, 1),
        ("NetworkPolicy of a foreign apiVersion", {**awl, "x/np.yaml": [{**np("a", "docs-truth-agent", "docs-truth-agent", cidr="10.0.0.0/8"),
                                                                        "apiVersion": "crd.projectcalico.org/v1"}]}, 1),
        # 5. fail closed
        ("unparseable manifest", {"x/broken.yaml": None}, 1),
    ]
    for name, docs, want in ncases:
        _f = evaluate_network(docs, mods); got = len(_f)
        if (got > 0) != (want > 0): print(_f)
        ok = (got > 0) == (want > 0)
        failed += not ok
        print(f"  {'ok ' if ok else 'BAD'} network/{name}: findings={got}")

    covered = {**awl, "x/np.yaml": [np("a", "docs-truth-agent", "docs-truth-agent", cidr="10.0.0.0/8")]}
    bcases = [
        ("baselined uncovered agent passes", awl, {"docs-truth-agent": "#1"}, 0),
        ("stale baseline entry: agent now covered", covered, {"docs-truth-agent": "#1"}, 1),
        ("stale baseline entry: agent gone", {}, {"docs-truth-agent": "#1"}, 1),
        ("baseline does not excuse a different agent", {"x/w.yaml": [wl("finops-agent", "finops-agent", "openbank-docs-truth-agent")]},
         {"docs-truth-agent": "#1"}, 1),
    ]
    for name, docs, base, want in bcases:
        _f = evaluate_network(docs, mods, base)
        ok = (len(_f) > 0) == (want > 0)
        failed += not ok
        print(f"  {'ok ' if ok else 'BAD'} baseline/{name}: findings={len(_f)}")

    print("self-test:", "PASS" if not failed else f"FAIL ({failed})")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    doc = gatelib.load_yaml(AGENTS_YAML) or {}
    findings, count = evaluate_agents(doc)
    findings += evaluate_gitops(_gitops_files())
    modules = agpl_modules(gatelib.load_yaml(RULES_YAML) or {})
    if not modules:
        findings.append("rules.yaml agpl_modules is empty or moved — the agent plane cannot be derived")
    docs = _gitops_docs()
    baseline, berr = load_baseline(BASELINE)
    findings += berr
    findings += evaluate_network(docs, modules, baseline)
    print(f"network: {len(baseline)} baselined agent-plane workload(s) without an Egress NetworkPolicy")
    pods = agent_plane_pods([d for ds in docs.values() if ds for d in ds], modules)
    print(f"network: {len(pods)} agent-plane workloads derived from {len(modules)} agent modules")
    gatelib.subjects(count, "agent charters examined for public-web access")
    for f in findings:
        print(f"::error title=public-web fetch (ADR-0387)::{f}")
    if findings:
        print(f"FAIL: {len(findings)} finding(s)")
        return 1
    print(f"OK: {count} charters, no unbounded public-web access")
    return 0


if __name__ == "__main__":
    sys.exit(main())
