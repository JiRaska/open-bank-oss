#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Agent model-id parity gate (ADR-0031 D5, issue #3667).

WHY THIS EXISTS

  `openbank-libs/governance/agents.yaml` declares a `model:` for each charter — the value
  #3813 threads onto every AI-attributed audit event. But `CharterRegistry` is
  `@ConfigMapping(prefix = "agent.charter")`: it reads `openbank-agent-service/src/main/resources/
  application.yaml`, never `agents.yaml`. So `agents.yaml` is data for generators (the EU AI Act
  inventory, the OPA bundle hash) and application.yaml is the copy the runtime actually consults —
  two hand-maintained lists of the same fact, exactly the shape this repo's own rule warns against
  ("never let a document keep its own copy of a list that lives elsewhere").

  Measured at #3813: 15 charters in agents.yaml declare a real `model:`; application.yaml's
  `agent.charter.charters` had 2 (`ui-assistant`, `compliance-officer`). `CharterRegistry.modelId`
  returns `UNKNOWN_MODEL` for anything not in that list, so 13 agents — including `mcp-anonymous`,
  the fixed principal every MCP tool call runs as today — would have every AI-attributed event it
  produces stamped "unknown" while agents.yaml claims a real model.

WHY IT IS NOT LIVE TODAY, AND WHY THAT IS ABOUT TO CHANGE

  `mcp-anonymous` and `ap2-anonymous` declare `model: unknown` in agents.yaml itself (ADR-0181
  phase 1: a single fixed principal stands in for every caller), so the two sides already agree —
  UNKNOWN_MODEL both ways is correct, not a gap. Every other id with a real model is a service this
  agent-service does not yet serve chat/MCP traffic for. So there is no live mismatch today; there
  is a coverage gap that ADR-0126 phase 2 (per-agent OAuth identity replacing the fixed principal)
  turns into a live one the day a real caller authenticates as one of them.

WHAT THIS GATE CHECKS, AND HOW IT RATCHETS

  For every agents.yaml id with a `model:` that is not the literal "unknown": if application.yaml
  also declares that id, the two `model:` values must be byte-identical (a genuine drift — NOT
  baselined, always a NEW finding). If application.yaml does not declare the id at all, it is
  BASELINED coverage debt — a ratchet in the same shape as check-outbox-has-writer.py: a NEW
  uncovered id fails, and a BASELINE entry that has since gained application.yaml coverage also
  fails ("this id is covered now — remove it"), so the list cannot silently rot in either direction.

EXIT CODES
  0  no new mismatches, no new gaps beyond baseline, no stale baseline entries
  1  a drift, an unbaselined gap, or a stale baseline entry
  2  the check could not run, or the self-test failed

Run:  python3 .github/scripts/check-agent-model-parity.py [--self-test] [--list]
"""

import argparse
import pathlib
import sys

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
AGENTS_YAML = ROOT / "openbank-libs" / "governance" / "agents.yaml"
APPLICATION_YAML = ROOT / "openbank-agent-service" / "src" / "main" / "resources" / "application.yaml"

UNKNOWN = "unknown"

# Ids that declare a REAL model in agents.yaml but are not yet served by this service's charter
# runtime (ADR-0126 phase 2 is what onboards them). Dated so staleness has a reference point.
# Each entry must carry the value FROM agents.yaml, so a value change there is caught as a
# baseline-drift finding rather than silently inherited.
BASELINE_UNCOVERED = {
    # 2026-08-09, ADR-0126 phase 2 not yet landed — these are separate microservices
    # (openbank-authz-policy-auditor, openbank-control-liveness-sentinel, openbank-devops-agent,
    # openbank-docs-truth-agent, openbank-finops-agent, openbank-governance-auditor,
    # openbank-release-steward) plus three chat-plane ids not yet routed through this service
    # (rca-investigator, customer-copilot, ledger-domain-engineer). None calls
    # CharterRegistry.modelId today because none authenticates to this service's MCP endpoint as
    # itself; the fixed mcp-anonymous principal stands in for all of them (ADR-0181 phase 1).
    "ledger-domain-engineer": "llama-3.3-70b-versatile",
    "rca-investigator": "deepseek-ai/DeepSeek-V3.2",
    "customer-copilot": "deepseek-ai/DeepSeek-V3.2",
    "finops-agent": "deepseek-ai/DeepSeek-V3.2",
    "devops-agent": "deepseek-ai/DeepSeek-V3.2",
    "control-liveness-sentinel": "deepseek-ai/DeepSeek-V3.2",
    "governance-auditor": "deepseek-ai/DeepSeek-V3.2",
    "release-steward": "deepseek-ai/DeepSeek-V3.2",
    "docs-truth-agent": "deepseek-ai/DeepSeek-V3.2",
    "authz-policy-auditor": "deepseek-ai/DeepSeek-V3.2",
    "flaky-test-hunter": "deepseek-ai/DeepSeek-V3.2",
    # 2026-09-05, ADR-0284 D9. Both charters are `enabled: false` and have no runtime at all —
    # they land ahead of the loop so the powers are bounded before the code exists. Neither can
    # be served by CharterRegistry until that loop is written, so this is coverage debt by
    # construction rather than drift; the entry fails the moment agent-service DOES declare them,
    # which is the point at which the two copies of the model id could start disagreeing.
    "kyb-analyst": "deepseek-ai/DeepSeek-V3.2",
    "business-copilot": "deepseek-ai/DeepSeek-V3.2",
}


def load_agents_yaml_models(path: pathlib.Path) -> dict:
    doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    out = {}
    for a in doc.get("agents") or []:
        model = a.get("model")
        if isinstance(model, str) and model != UNKNOWN:
            out[a["id"]] = model
    return out


def load_application_yaml_models(path: pathlib.Path) -> dict:
    doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    charters = (((doc.get("agent") or {}).get("charter") or {}).get("charters")) or []
    out = {}
    for c in charters:
        if isinstance(c, dict) and isinstance(c.get("agent-id"), str) and isinstance(c.get("model"), str):
            out[c["agent-id"]] = c["model"]
    return out


def check(declared: dict, covered: dict, baseline: dict) -> tuple[list[str], list[str]]:
    """Returns (findings, list_lines). Findings are the failure strings; empty means clean."""
    findings = []
    lines = []
    for agent_id, model in sorted(declared.items()):
        runtime_model = covered.get(agent_id)
        if runtime_model is not None:
            if runtime_model != model:
                findings.append(
                    f"{agent_id}: agents.yaml declares model `{model}`, application.yaml's charter "
                    f"declares `{runtime_model}` — CharterRegistry will report the runtime value. "
                    f"Update whichever is stale."
                )
            elif agent_id in baseline:
                findings.append(
                    f"{agent_id}: baselined as uncovered, but application.yaml now declares it "
                    f"(`{runtime_model}`) — remove it from BASELINE_UNCOVERED so the list keeps "
                    f"meaning something."
                )
            lines.append(f"covered   {agent_id} -> {model}")
        elif agent_id in baseline:
            if baseline[agent_id] != model:
                findings.append(
                    f"{agent_id}: baselined at model `{baseline[agent_id]}`, agents.yaml now says "
                    f"`{model}` — update BASELINE_UNCOVERED to match, or this hides a real change "
                    f"in what CharterRegistry.modelId will (eventually) return."
                )
            lines.append(f"baselined {agent_id} -> {model} (no application.yaml coverage yet)")
        else:
            findings.append(
                f"{agent_id}: declares model `{model}` in agents.yaml but has no application.yaml "
                f"charter entry, and is not in BASELINE_UNCOVERED — CharterRegistry.modelId will "
                f"return UNKNOWN_MODEL for it. Add the application.yaml entry, or baseline it with "
                f"a reason if it is not yet served by this agent-service's charter runtime."
            )
    stale = sorted(set(baseline) - set(declared))
    for agent_id in stale:
        findings.append(
            f"{agent_id}: in BASELINE_UNCOVERED but no longer declares a model in agents.yaml — "
            f"remove it."
        )
    return findings, lines


def self_test() -> int:
    declared_a = {"covered-agent": "model-x", "gap-agent": "model-y", "drift-agent": "model-z"}
    covered_a = {"covered-agent": "model-x", "drift-agent": "model-DIFFERENT"}
    baseline_a = {"gap-agent": "model-y"}

    findings, _ = check(declared_a, covered_a, baseline_a)
    reasons = "; ".join(findings)
    checks = [
        ("covered id with matching model produces no finding", "covered-agent" not in reasons),
        ("baselined gap with correct value produces no finding", "gap-agent" not in reasons),
        ("a real drift between the two sides IS flagged", "drift-agent" in reasons and "DIFFERENT" in reasons),
    ]

    # A baselined id that gains application.yaml coverage must be flagged to remove it.
    findings2, _ = check(
        {"newly-covered": "m"}, {"newly-covered": "m"}, {"newly-covered": "m"},
    )
    checks.append(("a baseline entry that is now covered is flagged as stale", bool(findings2)))

    # An unbaselined gap must be flagged.
    findings3, _ = check({"orphan": "m"}, {}, {})
    checks.append(("an unbaselined gap IS flagged", bool(findings3)))

    # A stale baseline entry (id no longer in agents.yaml) must be flagged.
    findings4, _ = check({}, {}, {"ghost": "m"})
    checks.append(("a stale baseline entry (id gone from agents.yaml) is flagged", bool(findings4)))

    failures = 0
    for desc, ok in checks:
        print(f"  {'ok' if ok else 'FAIL'}   {desc}")
        failures += 0 if ok else 1
    print(f"\nself-test: {len(checks) - failures} passed, {failures} failed")
    return 0 if failures == 0 else 2


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if not AGENTS_YAML.is_file() or not APPLICATION_YAML.is_file():
        print("::error::check-agent-model-parity: expected files not found", file=sys.stderr)
        return 2

    declared = load_agents_yaml_models(AGENTS_YAML)
    covered = load_application_yaml_models(APPLICATION_YAML)
    findings, lines = check(declared, covered, BASELINE_UNCOVERED)

    # Unconditional, including on the failure path (gatelib.subjects' own contract) — a gate that
    # found its 13 charters and then flagged a drift among them must not also read as having lost
    # its corpus.
    gatelib.subjects(len(declared), "charters declaring a model in agents.yaml")

    if args.list:
        for line in lines:
            print(line)
        return 0

    for f in findings:
        print(f"::error::agent-model-parity: {f}")

    n_covered = sum(1 for a in declared if a in covered)
    n_baseline = len(BASELINE_UNCOVERED)
    print(
        f"agent-model-parity: {len(declared)} charter(s) declare a model; {n_covered} covered by "
        f"application.yaml, {n_baseline} baselined as not-yet-served, {len(findings)} finding(s)."
    )
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
