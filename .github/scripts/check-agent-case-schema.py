#!/usr/bin/env python3
"""Agent case-coordination schema gate (ADR-0244 D3/D6/D9).

WHY THIS EXISTS

  #3771 introduced the swarm case-coordination schema into
  openbank-libs/governance/agents.yaml: a `case_classes` block (budgets,
  concurrency ceilings, contested-rate circuit-breaker thresholds) and
  per-charter `case_capabilities` (case.open / case.coordinate / ...). Nothing
  validated the new keys — a typo'd threshold of 1.30, a second charter
  grabbing case.coordinate, or a capability outside the closed vocabulary
  would parse as perfectly good YAML and merge, and the Temporal case
  workflow (Phase 1) would then read a governance lie: budgets are the D6
  stop-condition and single-coordinator is the D3/D9 audit-attribution
  invariant, so a malformed value is not cosmetic, it is an unbounded swarm
  or an unattributable one.

  This gate validates the structural invariants of the case schema:
    1. case_classes.default exists; budget/concurrency/convergence values,
       where present (default or per-class override), are positive numbers,
       and 0 < contested_rate_threshold < 1.
    2. Class names are kebab-case and `default` never appears inside
       `classes` (it would shadow the real default by convention only).
    3. case_capabilities uses the closed ADR-0244 vocabulary only
       (case.open/join/contribute/coordinate/synthesize/preempt), with no
       duplicates inside one charter.
    4. Coordination exclusivity: case.coordinate / case.synthesize /
       case.preempt are held ONLY by the `case-coordinator` charter, and it
       holds all three; a case schema with no coordinator is incomplete.

Self-test:       .github/scripts/check-agent-case-schema.py --self-test
Enforce (CI):    .github/scripts/check-agent-case-schema.py --enforce
Advisory:        .github/scripts/check-agent-case-schema.py   (warnings, exit 0)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
AGENTS_YAML = ROOT / "openbank-libs" / "governance" / "agents.yaml"

COORDINATOR_ID = "case-coordinator"
COORDINATION_CAPS = {"case.coordinate", "case.synthesize", "case.preempt"}
KNOWN_CAPS = COORDINATION_CAPS | {"case.open", "case.join", "case.contribute"}
KEBAB = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")

BUDGET_KEYS = ("tokens_per_case", "max_contributions", "wall_clock_minutes")


def _positive_numbers(block: dict, keys, where: str, findings: list[str]) -> None:
    for key in keys:
        if key not in block:
            continue
        value = block[key]
        if not isinstance(value, (int, float)) or isinstance(value, bool) or value <= 0:
            findings.append(f"{where}: `{key}` must be a positive number, got {value!r}")


def _check_class(name: str, cfg: dict, where: str, findings: list[str]) -> None:
    if not isinstance(cfg, dict):
        findings.append(f"{where}: expected a mapping, got {type(cfg).__name__}")
        return
    budget = cfg.get("budget", {})
    concurrency = cfg.get("concurrency", {})
    convergence = cfg.get("convergence", {})
    _positive_numbers(budget, BUDGET_KEYS, f"{where}.budget", findings)
    _positive_numbers(concurrency, ("max_open_cases",), f"{where}.concurrency", findings)
    threshold = convergence.get("contested_rate_threshold")
    if threshold is not None:
        if (not isinstance(threshold, (int, float)) or isinstance(threshold, bool)
                or not 0 < threshold < 1):
            findings.append(
                f"{where}.convergence: `contested_rate_threshold` must be in (0, 1), "
                f"got {threshold!r} — 0 disables the D9 circuit-breaker, >=1 never fires")


def validate(doc: dict) -> list[str]:
    findings: list[str] = []
    if not isinstance(doc, dict):
        return ["agents.yaml did not parse to a mapping"]

    case_classes = doc.get("case_classes")
    if case_classes is None:
        findings.append("case_classes: block missing — ADR-0244 budgets/ceilings have no home")
    elif isinstance(case_classes, dict):
        default = case_classes.get("default")
        if not isinstance(default, dict):
            findings.append("case_classes.default: missing — per-class overrides merge over it")
        else:
            _check_class("default", default, "case_classes.default", findings)
        classes = case_classes.get("classes", {}) or {}
        if "default" in classes:
            findings.append("case_classes.classes: a class NAMED `default` shadows the real "
                            "default by convention only — rename it")
        for name, cfg in classes.items():
            if not KEBAB.match(str(name)):
                findings.append(f"case_classes.classes.{name}: class names are kebab-case")
            _check_class(str(name), cfg, f"case_classes.classes.{name}", findings)
    else:
        findings.append("case_classes: expected a mapping")

    holders: dict[str, list[str]] = {cap: [] for cap in COORDINATION_CAPS}
    coordinator_caps: set[str] | None = None
    for agent in doc.get("agents", []) or []:
        if not isinstance(agent, dict):
            continue
        agent_id = agent.get("id", "<no-id>")
        caps = agent.get("case_capabilities", []) or []
        seen: set[str] = set()
        for cap in caps:
            if cap not in KNOWN_CAPS:
                findings.append(f"agents[{agent_id}].case_capabilities: `{cap}` is outside the "
                                f"closed ADR-0244 vocabulary {sorted(KNOWN_CAPS)}")
            if cap in seen:
                findings.append(f"agents[{agent_id}].case_capabilities: `{cap}` listed twice")
            seen.add(cap)
            if cap in COORDINATION_CAPS and agent_id != COORDINATOR_ID:
                findings.append(f"agents[{agent_id}]: holds `{cap}` — ADR-0244 D3/D9 coordination "
                                f"exclusivity belongs to `{COORDINATOR_ID}` (who detected is never "
                                f"who coordinated)")
            if cap in COORDINATION_CAPS:
                holders[cap].append(agent_id)
        if agent_id == COORDINATOR_ID:
            coordinator_caps = seen
    if coordinator_caps is None:
        findings.append(f"agents: no `{COORDINATOR_ID}` charter — ADR-0244 D3 coordination has "
                        f"no owner")
    else:
        for cap in sorted(COORDINATION_CAPS):
            if cap not in coordinator_caps:
                findings.append(f"agents[{COORDINATOR_ID}]: missing `{cap}` — the coordinator "
                                f"charter must hold all of {sorted(COORDINATION_CAPS)}")
    return findings


def self_test() -> int:
    """Feed it inputs it MUST flag and inputs it MUST NOT. A gate that has only ever passed is
    unfalsified -- both directions are asserted, so neither a dead check nor a false positive
    can hide."""
    good_class = {
        "budget": {"tokens_per_case": 500000, "max_contributions": 100, "wall_clock_minutes": 60},
        "concurrency": {"max_open_cases": 10},
        "convergence": {"contested_rate_threshold": 0.30},
    }
    good_doc = {
        "case_classes": {"default": good_class, "classes": {"incident-response": good_class}},
        "agents": [
            {"id": "case-coordinator",
             "case_capabilities": ["case.open", "case.coordinate", "case.synthesize", "case.preempt"]},
            {"id": "rca-investigator", "case_capabilities": ["case.join"]},
        ],
    }

    def mutated(**overrides):
        doc = {"case_classes": {"default": good_class, "classes": {}},
               "agents": [dict(a) for a in good_doc["agents"]]}
        doc.update(overrides)
        return doc

    def with_default(default):
        return {"case_classes": {"default": default, "classes": {}},
                "agents": [dict(a) for a in good_doc["agents"]]}

    cases = [
        ("the real shape passes", good_doc, 0),
        ("a class may override only one sub-block",
         mutated(case_classes={"default": good_class,
                               "classes": {"fraud-investigation": {"concurrency": {"max_open_cases": 5}}}}), 0),
        ("missing case_classes block AND missing coordinator are flagged",
         {"agents": []}, 2),
        ("a schema with classes but no coordinator charter is flagged",
         mutated(agents=[{"id": "rca-investigator", "case_capabilities": ["case.join"]}]), 1),
        ("missing default is flagged", mutated(case_classes={"classes": {}}), 1),
        ("threshold 0 disables the circuit-breaker — flagged",
         with_default({"convergence": {"contested_rate_threshold": 0}}), 1),
        ("threshold >= 1 never fires — flagged",
         with_default({"convergence": {"contested_rate_threshold": 1.3}}), 1),
        ("a boolean threshold is not a number — flagged",
         with_default({"convergence": {"contested_rate_threshold": True}}), 1),
        ("a negative token budget is flagged",
         with_default({"budget": {"tokens_per_case": -1}}), 1),
        ("a class named default is flagged",
         mutated(case_classes={"default": good_class, "classes": {"default": good_class}}), 1),
        ("a non-kebab class name is flagged",
         mutated(case_classes={"default": good_class, "classes": {"Fraud_Investigation": good_class}}), 1),
        ("an unknown capability is flagged",
         mutated(agents=[{"id": "case-coordinator",
                          "case_capabilities": ["case.coordinate", "case.synthesize",
                                                "case.preempt", "case.exfiltrate"]}]), 1),
        ("a duplicate capability is flagged",
         mutated(agents=[{"id": "case-coordinator",
                          "case_capabilities": ["case.coordinate", "case.coordinate",
                                                "case.synthesize", "case.preempt"]}]), 1),
        ("a second coordinator is flagged",
         mutated(agents=good_doc["agents"] + [{"id": "rogue",
                                               "case_capabilities": ["case.coordinate"]}]), 1),
        ("a coordinator missing case.preempt is flagged",
         mutated(agents=[{"id": "case-coordinator",
                          "case_capabilities": ["case.coordinate", "case.synthesize"]}]), 1),
    ]
    failed = 0
    for name, doc, expected in cases:
        got = len(validate(doc))
        ok = got == expected
        failed += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name} (expected {expected}, got {got})")
    print(f"self-test: {len(cases) - failed}/{len(cases)} passed")
    return 1 if failed else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    enforce = "--enforce" in sys.argv
    doc = yaml.safe_load(AGENTS_YAML.read_text(encoding="utf-8"))
    findings = validate(doc)
    if not findings:
        print("check-agent-case-schema: OK — case classes and case_capabilities satisfy the "
              "ADR-0244 invariants.")
        return 0
    for finding in findings:
        print(f"{'::error::' if enforce else '::warning::'}{finding}")
    print(f"\n{len(findings)} case-schema violation(s). Budgets are the D6 stop-condition and "
          f"single-coordinator is the D3/D9 audit invariant — malformed is not cosmetic.")
    return 1 if enforce else 0


if __name__ == "__main__":
    sys.exit(main())
