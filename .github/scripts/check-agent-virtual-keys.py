#!/usr/bin/env python3
"""Every agent must authenticate to the LiteLLM gateway with its OWN virtual key.

WHY THIS IS A GATE AND NOT A COMMENT. The master key and a virtual key are the same shape
(`sk-...`) and both authenticate successfully, so an agent wired to the master key works
perfectly -- it just has no budget, no attribution, and cannot be revoked without revoking the
whole fleet. There is no failing request, no red dashboard, and no log line to notice: the only
symptom is that a control the platform claims to enforce quietly enforces nothing. That is
precisely the class of regression a text guard can catch and a human review cannot.

THE RULE. In `openbank-infra/gitops/components/`, an ExternalSecret entry whose `remoteRef.key`
is `litellm` may reference `property: LITELLM_MASTER_KEY` ONLY inside `components/ai-platform/`
-- the gateway's own namespace, where that key is the proxy's admin credential and is supposed
to live. Anywhere else it means an agent is on the shared key.

Run standalone:  .github/scripts/check-agent-virtual-keys.py [--enforce]
Self-test:       .github/scripts/check-agent-virtual-keys.py --self-test
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

import gatelib

REPO = Path(__file__).resolve().parents[2]
COMPONENTS = REPO / "openbank-infra" / "gitops" / "components"
MASTER = "LITELLM_MASTER_KEY"
# The gateway's own namespace: this is where the master key legitimately lives.
ALLOWED_PREFIX = "ai-platform"


def offenders_in(doc: dict, rel: str) -> list[str]:
    """Return one message per entry that sources the master key from outside ai-platform."""
    if not isinstance(doc, dict) or doc.get("kind") != "ExternalSecret":
        return []
    if rel.split("/")[0] == ALLOWED_PREFIX:
        return []
    out = []
    for entry in (doc.get("spec") or {}).get("data") or []:
        if not isinstance(entry, dict):
            continue
        ref = entry.get("remoteRef") or {}
        if ref.get("key") == "litellm" and ref.get("property") == MASTER:
            out.append(
                f"{rel}: secretKey {entry.get('secretKey')} sources the SHARED master key "
                f"(remoteRef.property: {MASTER}). Use this agent's own virtual key "
                f"(property: KEY_<AGENT_ID>), seeded by "
                f"openbank-infra/scripts/seed-litellm-virtual-keys.sh."
            )
    return out


def audit() -> list[str]:
    findings: list[str] = []
    for path in gatelib.rglob(COMPONENTS, "*.yaml"):
        rel_to_components = path.relative_to(COMPONENTS).as_posix()
        try:
            docs = list(yaml.safe_load_all(path.read_text()))
        except yaml.YAMLError:
            # Not this gate's job: `Validate manifests` already fails on unparseable YAML, and
            # swallowing it here would be a second opinion nobody reads.
            continue
        for doc in docs:
            findings += offenders_in(doc, rel_to_components)
    return findings


def self_test() -> int:
    """Feed it inputs it MUST flag and inputs it MUST NOT. A gate that has only ever passed is
    unfalsified -- both directions are asserted, so neither a dead check nor a false positive
    can hide."""
    agent = {
        "kind": "ExternalSecret",
        "spec": {
            "data": [
                {"secretKey": "X_MODEL_API_KEY",
                 "remoteRef": {"key": "litellm", "property": MASTER}}
            ]
        },
    }
    cases = [
        ("agent on the master key is flagged",
         agent, "devops-agent/model-externalsecret.yaml", 1),
        ("the gateway's own namespace is exempt",
         agent, "ai-platform/model-externalsecret.yaml", 0),
        ("an agent on its own virtual key passes",
         {"kind": "ExternalSecret", "spec": {"data": [
             {"secretKey": "X", "remoteRef": {"key": "litellm",
                                              "property": "KEY_DEVOPS_AGENT"}}]}},
         "devops-agent/model-externalsecret.yaml", 0),
        ("a different KV path with the same property name is not our business",
         {"kind": "ExternalSecret", "spec": {"data": [
             {"secretKey": "X", "remoteRef": {"key": "somewhere-else",
                                              "property": MASTER}}]}},
         "devops-agent/model-externalsecret.yaml", 0),
        ("a non-ExternalSecret document mentioning the key is not flagged",
         {"kind": "Deployment", "spec": {"data": [
             {"secretKey": "X", "remoteRef": {"key": "litellm", "property": MASTER}}]}},
         "devops-agent/deploy.yaml", 0),
        ("an ExternalSecret with no data entries is fine",
         {"kind": "ExternalSecret", "spec": {}}, "devops-agent/es.yaml", 0),
        ("a null document does not crash the walk",
         None, "devops-agent/es.yaml", 0),
    ]
    failed = 0
    for name, doc, rel, expected in cases:
        got = len(offenders_in(doc, rel))
        ok = got == expected
        failed += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name} (expected {expected}, got {got})")
    print(f"self-test: {len(cases) - failed}/{len(cases)} passed")
    return 1 if failed else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    enforce = "--enforce" in sys.argv
    # Measured 2026-09-03: with COMPONENTS renamed away this gate printed
    # "OK — no agent sources the shared LiteLLM master key" and exited 0 — green about a tree
    # it never read. Emit the count so run-gates' min_subjects floor can see a scope collapse.
    gatelib.subjects(sum(1 for _ in gatelib.rglob(COMPONENTS, "*.yaml")),
                     "gitops component manifests scanned")
    findings = audit()
    if not findings:
        print("check-agent-virtual-keys: OK — no agent sources the shared LiteLLM master key.")
        return 0
    for f in findings:
        print(f"{'::error::' if enforce else '::warning::'}{f}")
    print(f"\n{len(findings)} agent secret(s) on the shared master key. "
          f"Each is a caller with no budget, no spend attribution, and no independent revocation.")
    return 1 if enforce else 0


if __name__ == "__main__":
    sys.exit(main())
