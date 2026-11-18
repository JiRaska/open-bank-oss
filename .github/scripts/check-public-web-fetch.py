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
#     workload, and that workload is the fetch broker.
#
# Run:
#   python3 .github/scripts/check-public-web-fetch.py [--self-test]

import argparse
import pathlib
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
HOST_RE = re.compile(r"^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$")
IPV4_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")


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
    if not HOST_RE.match(host):
        return "not a DNS hostname"
    return None


def evaluate_agents(doc: dict) -> tuple[list[str], int]:
    findings: list[str] = []
    agents = [a for a in (doc.get("agents") or []) if isinstance(a, dict)]
    for agent in agents:
        aid = agent.get("id", "<no id>")
        allows = _allows(agent)
        grants = any(RESOURCE in (a.get("resources") or []) for a in allows)
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


def evaluate_gitops(files: dict[str, str]) -> list[str]:
    """files: relative path -> text. A reference is the secret name inside a workload manifest."""
    holders = sorted(p for p, text in files.items() if SECRET_NAME in text and "kind: ExternalSecret" not in text)
    findings = [f"{p}: references {SECRET_NAME} outside the fetch broker" for p in holders if BROKER not in p]
    if len([p for p in holders if BROKER in p]) > 1:
        findings.append(f"{SECRET_NAME} is mounted by more than one fetch-broker manifest: {holders}")
    return findings


def _gitops_files() -> dict[str, str]:
    if not GITOPS.is_dir():
        return {}
    out = {}
    for p in gatelib.rglob(GITOPS, "*.yaml"):
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
    ]
    failed = 0
    for name, agents, want in cases:
        got = len(evaluate_agents({"agents": agents})[0])
        ok = (got > 0) == (want > 0)
        failed += not ok
        print(f"  {'ok ' if ok else 'BAD'} agents/{name}: findings={got}")

    gcases = [
        ("broker only", {f"gitops/components/{BROKER}/deployment.yaml": SECRET_NAME}, 0),
        ("externalsecret ignored", {"gitops/x/es.yaml": f"kind: ExternalSecret\n{SECRET_NAME}"}, 0),
        ("second mount", {f"gitops/components/{BROKER}/d.yaml": SECRET_NAME, "gitops/components/agent/d.yaml": SECRET_NAME}, 1),
    ]
    for name, files, want in gcases:
        got = len(evaluate_gitops(files))
        ok = (got > 0) == (want > 0)
        failed += not ok
        print(f"  {'ok ' if ok else 'BAD'} gitops/{name}: findings={got}")

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
