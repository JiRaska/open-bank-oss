#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# pyright: reportAny=false, reportExplicitAny=false, reportArgumentType=false, reportUnknownParameterType=false, reportMissingTypeArgument=false, reportUnusedCallResult=false, reportOptionalSubscript=false, reportUnnecessaryIsInstance=false, reportUnreachable=false, reportUnknownVariableType=false, reportUnknownMemberType=false, reportUnknownArgumentType=false, reportOperatorIssue=false, reportImplicitStringConcatenation=false, reportOptionalMemberAccess=false
#
# Generate openbank-admin-ui/ai-governance-snapshot.json from curated rollout narrative
# + machine-checkable repo facts (ADR-0029 D3 / ADR-0031 / ADR-0148).
#
# WHY THIS IS GENERATED, NOT HAND-WRITTEN
#   The IAOps governance route used to carry a hand-maintained status block whose phase number,
#   D1–D9 statuses and assurance facts could drift from the repo. The human narrative now lives in
#   openbank-libs/governance/ai-rollout.yaml, and the machine-checkable parts are derived here from
#   repo sources: agents.yaml, prompts/registry.yaml, evals/baselines.json, and the services that
#   actually package registry prompts into their runtime. route.ts reads the generated JSON only.
#
# Regenerate: python3 .github/scripts/gen-ai-governance-snapshot.py
# Verify:     python3 .github/scripts/gen-ai-governance-snapshot.py --check

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import pathlib
import re
import sys
from typing import Any, cast

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)


ROOT = pathlib.Path(__file__).resolve().parents[2]
CURATED = ROOT / "openbank-libs" / "governance" / "ai-rollout.yaml"
AGENTS = ROOT / "openbank-libs" / "governance" / "agents.yaml"
PROMPT_REGISTRY = ROOT / "openbank-libs" / "governance" / "prompts" / "registry.yaml"
EVALS_BASELINES = ROOT / "openbank-libs" / "governance" / "evals" / "baselines.json"
OUT = ROOT / "openbank-admin-ui" / "ai-governance-snapshot.json"

ALLOWED_D_STATUSES = {"built", "partial", "planned"}
EXPECTED_DECISION_IDS = [f"D{i}" for i in range(1, 10)]


def fail(message: str) -> "None":
    sys.stderr.write(f"gen-ai-governance-snapshot: {message}\n")
    raise SystemExit(1)


def read_text(path: pathlib.Path) -> str:
    if not path.exists():
        fail(f"required source file missing: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def load_yaml(path: pathlib.Path):
    try:
        return yaml.safe_load(read_text(path))
    except yaml.YAMLError as exc:
        fail(f"failed to parse YAML {path.relative_to(ROOT)}: {exc}")


def load_json(path: pathlib.Path):
    try:
        return json.loads(read_text(path))
    except json.JSONDecodeError as exc:
        fail(f"failed to parse JSON {path.relative_to(ROOT)}: {exc}")


def sha256_short(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def validate_curated(curated: dict) -> None:
    if not isinstance(curated, dict):
        fail("ai-rollout.yaml must be a mapping")
    for key in ["adrRef", "adrStatus", "phase", "decisions", "compliance", "auditTrail"]:
        if key not in curated:
            fail(f"ai-rollout.yaml missing required key: {key}")

    phase = curated["phase"]
    if not isinstance(phase, dict):
        fail("phase must be a mapping")
    for key in ["current", "total", "label", "agentsActing"]:
        if key not in phase:
            fail(f"phase missing required key: {key}")

    decisions = curated["decisions"]
    if not isinstance(decisions, list):
        fail("decisions must be a list")
    ids = [item.get("id") for item in decisions if isinstance(item, dict)]
    if ids != EXPECTED_DECISION_IDS:
        fail(f"decisions must appear exactly as {EXPECTED_DECISION_IDS}; got {ids}")
    for item in decisions:
        if not isinstance(item, dict):
            fail("each decision must be a mapping")
        for key in ["id", "title", "status", "detail"]:
            if key not in item:
                fail(f"decision {item!r} missing required key: {key}")
        if item["status"] not in ALLOWED_D_STATUSES:
            fail(f"decision {item['id']} has invalid status {item['status']!r}")

    for row_name in ["compliance"]:
        rows = curated[row_name]
        if not isinstance(rows, list) or not rows:
            fail(f"{row_name} must be a non-empty list")
    audit = curated["auditTrail"]
    if not isinstance(audit, dict):
        fail("auditTrail must be a mapping")
    for key in ["capture", "pipeline", "live", "planned"]:
        if not isinstance(audit.get(key), list) or not audit[key]:
            fail(f"auditTrail.{key} must be a non-empty list")


def collect_agent_facts(agents_doc: dict) -> tuple[dict, list[str], str, str]:
    agents = agents_doc.get("agents") or []
    if not isinstance(agents, list) or not agents:
        fail("agents.yaml has no agents[] entries")

    agent_ids: list[str] = []
    by_plane: dict[str, int] = {}
    for item in agents:
        if not isinstance(item, dict):
            fail("agents.yaml agents[] entries must be mappings")
        agent_id = str(item.get("id", "")).strip()
        plane = str(item.get("plane", "")).strip() or "unknown"
        if not agent_id:
            fail("agents.yaml contains an agent with no id")
        agent_ids.append(agent_id)
        by_plane[plane] = by_plane.get(plane, 0) + 1

    defaults = agents_doc.get("defaults") or {}
    enforced = str(defaults.get("enforced", "advisory"))
    policy_default = str(defaults.get("policy_decision", "deny"))
    facts = {
        "source": "openbank-libs/governance/agents.yaml",
        "sha256": sha256_short(AGENTS),
        "count": len(agent_ids),
        "ids": agent_ids,
        "byPlane": by_plane,
        "defaults": {
            "enforced": enforced,
            "policyDecision": policy_default,
        },
    }
    return facts, agent_ids, enforced, policy_default


def collect_prompt_registry_facts(registry_doc: dict, agent_ids: list[str]) -> dict:
    entries = registry_doc.get("charters") or []
    if not isinstance(entries, list) or not entries:
        fail("prompts/registry.yaml has no charters[] entries")

    allowed_statuses = {"registered", "pending", "external", "not-applicable"}
    by_status: dict[str, list[str]] = {key: [] for key in sorted(allowed_statuses)}
    seen_ids: list[str] = []
    for entry in entries:
        if not isinstance(entry, dict):
            fail("prompts/registry.yaml charters[] entries must be mappings")
        charter_id = str(entry.get("id", "")).strip()
        status = str(entry.get("status", "")).strip()
        if not charter_id:
            fail("prompts/registry.yaml contains a charter with no id")
        if status not in allowed_statuses:
            fail(f"prompts/registry.yaml charter {charter_id} has invalid status {status!r}")
        seen_ids.append(charter_id)
        by_status[status].append(charter_id)

    if sorted(seen_ids) != sorted(agent_ids):
        missing = sorted(set(agent_ids) - set(seen_ids))
        extra = sorted(set(seen_ids) - set(agent_ids))
        fail(
            "prompts/registry.yaml coverage mismatch vs agents.yaml: "
            f"missing={missing or '[]'} extra={extra or '[]'}"
        )

    return {
        "source": "openbank-libs/governance/prompts/registry.yaml",
        "sha256": sha256_short(PROMPT_REGISTRY),
        "counts": {status: len(ids) for status, ids in by_status.items()},
        "idsByStatus": {status: ids for status, ids in by_status.items()},
    }


def collect_evals_facts() -> dict:
    baselines = load_json(EVALS_BASELINES)
    if "default_min_pass_rate" not in baselines:
        fail("evals/baselines.json missing default_min_pass_rate")
    overrides = baselines.get("overrides", {})
    if not isinstance(overrides, dict):
        fail("evals/baselines.json overrides must be an object")
    return {
        "source": "openbank-libs/governance/evals/baselines.json",
        "sha256": sha256_short(EVALS_BASELINES),
        "baselinePresent": True,
        "defaultMinPassRate": baselines["default_min_pass_rate"],
        "overrideCount": len(overrides),
        "overrideCharters": sorted(overrides.keys()),
    }


def collect_registry_loader_facts(registered_charters: list[str]) -> dict:
    build_pattern = re.compile(r'openbank-libs/governance/prompts/([a-z0-9-]+)"\)')
    source_pattern = re.compile(r'/governance-prompts/([a-z0-9-]+)/')
    # A loader that composes the path from the charter id, e.g.
    #   val path = "/governance-prompts/$agentId/$name.md"
    # ADR-0148 asks for exactly this — one registry-driven loader rather than a branch per
    # charter — so `source_pattern` above, which only ever matches a LITERAL slug, cannot
    # see it and reports every packaged charter as unloaded. That is not a missing loader,
    # it is a loader this check cannot statically resolve, and failing on it would push
    # agent-service back to hardcoding a charter list. Measured on openbank-agent-service
    # after #3312: packaged=[compliance-officer, ui-assistant], literal hits=[], so both
    # were reported missing while `RegisteredPromptTemplates` loads them at runtime.
    dynamic_pattern = re.compile(r'/governance-prompts/\$')

    services: list[dict] = []
    loaded_charters: set[str] = set()
    for build_file in sorted(ROOT.glob("openbank-*/build.gradle.kts")):
        build_text = read_text(build_file)
        packaged = sorted(set(build_pattern.findall(build_text)))
        if not packaged:
            continue

        module = build_file.parent.name
        src_root = build_file.parent / "src" / "main" / "kotlin"
        source_hits: set[str] = set()
        dynamic_loader = False
        for kotlin_file in src_root.rglob("*.kt"):
            text = kotlin_file.read_text(encoding="utf-8")
            source_hits.update(source_pattern.findall(text))
            if dynamic_pattern.search(text):
                dynamic_loader = True

        # The rule being enforced is "nothing is packaged that no code loads". A dynamic
        # loader still has to EXIST — a module packaging prompts with no reference to
        # /governance-prompts/ at all is still dead packaging and still fails below.
        missing_code = [] if dynamic_loader else sorted(set(packaged) - source_hits)
        if missing_code:
            fail(
                f"{module} packages registry prompts for {missing_code} but no Kotlin source loads "
                "a matching /governance-prompts/<charter>/ resource"
            )

        unknown_packaged = sorted(set(packaged) - set(registered_charters))
        if unknown_packaged:
            fail(f"{module} packages prompt registry charters not marked registered: {unknown_packaged}")

        loaded_charters.update(packaged)
        services.append({
            "module": module,
            "charters": packaged,
            "buildFile": str(build_file.relative_to(ROOT)),
        })

    registered_set = set(registered_charters)
    return {
        "services": services,
        "serviceCount": len(services),
        "registeredChartersLoadedFromRegistry": sorted(loaded_charters),
        "registeredChartersStillInline": sorted(registered_set - loaded_charters),
    }


def build_snapshot() -> dict:
    curated = cast(dict[str, Any] | None, load_yaml(CURATED))
    if curated is None:
        fail("ai-rollout.yaml is empty")
    validate_curated(curated)

    agents_doc = cast(dict[str, Any] | None, load_yaml(AGENTS))
    registry_doc = cast(dict[str, Any] | None, load_yaml(PROMPT_REGISTRY))
    if agents_doc is None:
        fail("agents.yaml is empty")
    if registry_doc is None:
        fail("prompts/registry.yaml is empty")

    agent_facts, agent_ids, enforced, policy_default = collect_agent_facts(agents_doc)
    prompt_facts = collect_prompt_registry_facts(registry_doc, agent_ids)
    evals_facts = collect_evals_facts()
    loader_facts = collect_registry_loader_facts(prompt_facts["idsByStatus"]["registered"])

    decisions = curated["decisions"]
    decision_summary = {
        "built": sum(1 for item in decisions if item["status"] == "built"),
        "partial": sum(1 for item in decisions if item["status"] == "partial"),
        "planned": sum(1 for item in decisions if item["status"] == "planned"),
        "total": len(decisions),
    }

    return {
        "adrRef": curated["adrRef"],
        "adrStatus": curated["adrStatus"],
        "phase": curated["phase"]["current"],
        "totalPhases": curated["phase"]["total"],
        "phaseLabel": curated["phase"]["label"],
        "agentsActing": curated["phase"]["agentsActing"],
        "decisions": decisions,
        "decisionSummary": decision_summary,
        "compliance": curated["compliance"],
        "auditTrail": curated["auditTrail"],
        "facts": {
            "agentCharters": agent_facts,
            "promptRegistryCoverage": prompt_facts,
            "evals": evals_facts,
            "registryPromptLoaders": loader_facts,
            "governanceDefaults": {
                "enforcement": enforced,
                "policyDefault": policy_default,
            },
            "provenance": {
                "curated": {
                    "source": "openbank-libs/governance/ai-rollout.yaml",
                    "sha256": sha256_short(CURATED),
                },
                "generatedBy": ".github/scripts/gen-ai-governance-snapshot.py",
            },
        },
    }


def render(snapshot: dict) -> str:
    return json.dumps(snapshot, indent=2, ensure_ascii=False) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate the admin-ui AI governance snapshot")
    parser.add_argument("--check", action="store_true", help="verify the committed snapshot is up to date")
    args = parser.parse_args()

    rendered = render(build_snapshot())

    if args.check:
        current = read_text(OUT)
        if current != rendered:
            sys.stderr.write(
                "::error title=AI governance snapshot::openbank-admin-ui/ai-governance-snapshot.json is stale — "
                "run 'python3 .github/scripts/gen-ai-governance-snapshot.py' and commit the result.\n"
            )
            diff = difflib.unified_diff(
                current.splitlines(),
                rendered.splitlines(),
                fromfile="committed",
                tofile="regenerated",
                lineterm="",
            )
            sys.stderr.write("\n".join(diff) + "\n")
            raise SystemExit(1)
        print("ai-governance-snapshot: openbank-admin-ui/ai-governance-snapshot.json is in sync.")
        return

    OUT.write_text(rendered, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
