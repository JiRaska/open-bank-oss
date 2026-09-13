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
EVALS_DIR = EVALS_BASELINES.parent
EVAL_RECORDINGS = EVALS_DIR / "recordings"
OUT = ROOT / "openbank-admin-ui" / "ai-governance-snapshot.json"

ALLOWED_D_STATUSES = {"built", "partial", "planned"}
ALLOWED_PHASE_STATUSES = {"complete", "active", "blocked", "planned"}
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
    for key in ["adrRef", "adrStatus", "phase", "controlMaturity", "decisions", "compliance", "auditTrail"]:
        if key not in curated:
            fail(f"ai-rollout.yaml missing required key: {key}")

    phase = curated["phase"]
    if not isinstance(phase, dict):
        fail("phase must be a mapping")
    for key in ["current", "total", "label", "agentsActing", "roadmap"]:
        if key not in phase:
            fail(f"phase missing required key: {key}")
    current = phase["current"]
    total = phase["total"]
    if type(current) is not int or type(total) is not int or not 1 <= current <= total:
        fail("phase.current must be an integer between 1 and phase.total")
    roadmap = phase["roadmap"]
    if not isinstance(roadmap, list) or len(roadmap) != total:
        fail("phase.roadmap must contain exactly phase.total entries")
    numbers = [item.get("number") for item in roadmap if isinstance(item, dict)]
    if numbers != list(range(1, total + 1)):
        fail("phase.roadmap numbers must be consecutive and start at 1")
    for item in roadmap:
        if not isinstance(item, dict):
            fail("each phase.roadmap entry must be a mapping")
        if type(item.get("number")) is not int:
            fail("phase.roadmap entry missing or invalid number")
        for key in ["status", "title", "outcome"]:
            if not isinstance(item.get(key), str) or not item[key].strip():
                fail(f"phase.roadmap entry missing or invalid {key}")
        if item["status"] not in ALLOWED_PHASE_STATUSES:
            fail(f"phase.roadmap entry {item['number']} has invalid status {item['status']!r}")
    active = [item["number"] for item in roadmap if item["status"] == "active"]
    if active != [current]:
        fail("phase.roadmap must have exactly the current phase marked active")
    if any(item["status"] != "complete" for item in roadmap if item["number"] < current):
        fail("all phases before phase.current must be complete")
    if any(item["status"] == "complete" for item in roadmap if item["number"] > current):
        fail("no phase after phase.current may be marked complete")

    control_maturity = curated["controlMaturity"]
    if not isinstance(control_maturity, dict):
        fail("controlMaturity must be a mapping")
    for key in ["current", "total", "label", "achieved", "remaining"]:
        if key not in control_maturity:
            fail(f"controlMaturity missing required key: {key}")
    maturity_current = control_maturity["current"]
    maturity_total = control_maturity["total"]
    if type(maturity_current) is not int or type(maturity_total) is not int or not 1 <= maturity_current <= maturity_total:
        fail("controlMaturity.current must be an integer between 1 and controlMaturity.total")
    if not isinstance(control_maturity["label"], str) or not control_maturity["label"].strip():
        fail("controlMaturity.label must be a non-empty string")
    achieved = control_maturity["achieved"]
    if not isinstance(achieved, list) or len(achieved) != maturity_current or not all(isinstance(item, str) and re.fullmatch(r"D[1-9]", item) for item in achieved):
        fail("controlMaturity.achieved must contain one D1–D9 decision id per completed control")
    if len(set(achieved)) != len(achieved):
        fail("controlMaturity.achieved must not contain duplicate decision ids")
    if not isinstance(control_maturity["remaining"], str) or not control_maturity["remaining"].strip():
        fail("controlMaturity.remaining must be a non-empty string")

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

    decision_statuses = {item["id"]: item["status"] for item in decisions}
    for decision_id in achieved:
        if decision_statuses.get(decision_id) != "built":
            fail(f"controlMaturity.achieved {decision_id} must reference a built decision")

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
    prompts_by_charter: dict[str, list[str]] = {}
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
        prompts = entry.get("prompts", [])
        if not isinstance(prompts, list) or any(not isinstance(item, str) for item in prompts):
            fail(f"prompts/registry.yaml charter {charter_id} has invalid prompts")
        prompts_by_charter[charter_id] = prompts

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
        "promptsByCharter": prompts_by_charter,
    }


def collect_evals_facts(registered_charters: list[str]) -> dict:
    baselines = load_json(EVALS_BASELINES)
    if "default_min_pass_rate" not in baselines:
        fail("evals/baselines.json missing default_min_pass_rate")
    overrides = baselines.get("overrides", {})
    if not isinstance(overrides, dict):
        fail("evals/baselines.json overrides must be an object")
    suite_charters = sorted(path.stem for path in EVALS_DIR.glob("*.yaml"))
    recorded_charters = sorted(path.stem for path in EVAL_RECORDINGS.glob("*.json"))
    return {
        "source": "openbank-libs/governance/evals/baselines.json",
        "sha256": sha256_short(EVALS_BASELINES),
        "baselinePresent": True,
        "defaultMinPassRate": baselines["default_min_pass_rate"],
        "overrideCount": len(overrides),
        "overrideCharters": sorted(overrides.keys()),
        "suiteCharters": suite_charters,
        "recordedCharters": recorded_charters,
        "missingSuiteCharters": sorted(set(registered_charters) - set(suite_charters)),
        "missingRecordingCharters": sorted(set(suite_charters) - set(recorded_charters)),
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

    agents_doc = cast(dict[str, Any] | None, load_yaml(AGENTS))
    registry_doc = cast(dict[str, Any] | None, load_yaml(PROMPT_REGISTRY))
    if agents_doc is None:
        fail("agents.yaml is empty")
    if registry_doc is None:
        fail("prompts/registry.yaml is empty")

    agent_facts, agent_ids, enforced, policy_default = collect_agent_facts(agents_doc)
    validate_curated(curated)
    prompt_facts = collect_prompt_registry_facts(registry_doc, agent_ids)
    registered_charters = prompt_facts["idsByStatus"]["registered"]
    evals_facts = collect_evals_facts(registered_charters)
    loader_facts = collect_registry_loader_facts(registered_charters)

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
        "phaseRoadmap": curated["phase"]["roadmap"],
        "controlMaturity": curated["controlMaturity"],
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



def self_test() -> int:
    """Falsify the curated-input validator.

    This snapshot is what openbank-admin-ui renders as the platform's AI-governance posture —
    the phase, the D1-D9 decision statuses, the compliance rows. It is READ by people deciding
    whether a control exists, so a validator that accepts a malformed or partial curated file
    publishes a governance claim nobody checked.

    Every rule fails in the same direction: accept less structure, and the snapshot renders
    with a decision silently missing or a status nobody defined. The page still looks
    complete, because a missing row is not a visible row.
    """
    import contextlib
    import copy
    import io

    fails: list[str] = []

    good = {
        "adrRef": "ADR-0031",
        "adrStatus": "accepted",
        "phase": {
            "current": 2, "total": 5, "label": "phase two", "agentsActing": 3,
            "roadmap": [
                {"number": 1, "status": "complete", "title": "one", "outcome": "done"},
                {"number": 2, "status": "active", "title": "two", "outcome": "now"},
                {"number": 3, "status": "blocked", "title": "three", "outcome": "needs proof"},
                {"number": 4, "status": "blocked", "title": "four", "outcome": "needs proof"},
                {"number": 5, "status": "planned", "title": "five", "outcome": "later"},
            ],
        },
        "controlMaturity": {
            "current": 4, "total": 5, "label": "four controls built",
            "achieved": ["D1", "D2", "D3", "D4"], "remaining": "D5 evidence",
        },
        "decisions": [
            {"id": f"D{i}", "title": f"t{i}", "status": "built", "detail": f"d{i}"}
            for i in range(1, 10)
        ],
        "compliance": [{"control": "c", "status": "ok"}],
        "auditTrail": {"capture": ["a"], "pipeline": ["b"], "live": ["c"], "planned": ["d"]},
    }

    def rejects(label: str, mutate) -> None:
        """A mutated curated doc must be REJECTED (validate_curated raises SystemExit)."""
        doc = copy.deepcopy(good)
        mutate(doc)
        sink = io.StringIO()
        try:
            with contextlib.redirect_stderr(sink):
                validate_curated(doc)
        except SystemExit:
            return
        fails.append(f"{label}: accepted a document it must reject")

    # The valid document must pass — without this a validator that rejects everything looks
    # identical to a working one.
    sink = io.StringIO()
    try:
        with contextlib.redirect_stderr(sink):
            validate_curated(copy.deepcopy(good))
    except SystemExit:
        fails.append(f"a well-formed curated document was rejected: {sink.getvalue().strip()}")

    # Top-level keys: each one absent means a section of the published page has no source.
    for key in ("adrRef", "adrStatus", "phase", "controlMaturity", "decisions", "compliance", "auditTrail"):
        rejects(f"a missing top-level {key!r}", lambda d, k=key: d.pop(k))

    # The phase block drives the headline number on the page.
    for key in ("current", "total", "label", "agentsActing", "roadmap"):
        rejects(f"a missing phase.{key}", lambda d, k=key: d["phase"].pop(k))
    rejects("a non-mapping phase", lambda d: d.update(phase=["not", "a", "map"]))
    rejects("a phase above total", lambda d: d["phase"].update(current=6))
    rejects("a dropped roadmap entry", lambda d: d["phase"]["roadmap"].pop())
    rejects("an inactive current phase", lambda d: d["phase"]["roadmap"][1].update(status="blocked"))
    rejects("an incomplete prior phase", lambda d: d["phase"]["roadmap"][0].update(status="blocked"))
    rejects("a complete phase after current", lambda d: d["phase"]["roadmap"][2].update(status="complete"))

    # This is a separate ruler from release autonomy. It must not become a free-form, unvalidated
    # score that renders a stronger governance claim than its input supports.
    for key in ("current", "total", "label", "achieved", "remaining"):
        rejects(f"a missing controlMaturity.{key}", lambda d, k=key: d["controlMaturity"].pop(k))
    rejects("a non-mapping controlMaturity", lambda d: d.update(controlMaturity=["not", "a", "map"]))
    rejects("a control maturity above total", lambda d: d["controlMaturity"].update(current=6))
    rejects("a control maturity with too few achieved controls", lambda d: d["controlMaturity"].update(achieved=["D1"]))
    rejects("a control maturity with duplicate achieved controls", lambda d: d["controlMaturity"].update(achieved=["D1", "D1", "D3", "D4"]))
    rejects("a control maturity citing a partial decision", lambda d: d["decisions"][2].update(status="partial"))
    rejects("a control maturity with a blank remaining statement", lambda d: d["controlMaturity"].update(remaining=""))

    # D1-D9 must appear EXACTLY, in order. A dropped decision is the failure that matters most
    # here: the page renders eight rows and reads as complete, because a row that is not there
    # cannot look wrong.
    rejects("a dropped decision", lambda d: d["decisions"].pop())
    rejects("decisions out of order", lambda d: d["decisions"].reverse())
    rejects("a renamed decision id",
            lambda d: d["decisions"][0].update(id="D0"))
    rejects("a duplicated decision id",
            lambda d: d["decisions"].__setitem__(1, dict(d["decisions"][0])))
    rejects("a non-list decisions", lambda d: d.update(decisions={"id": "D1"}))

    # Per-decision fields, and the closed status vocabulary — an unknown status renders as
    # something the reader will interpret, having never been defined.
    for key in ("id", "title", "status", "detail"):
        rejects(f"a decision missing {key!r}", lambda d, k=key: d["decisions"][3].pop(k))
    rejects("an invalid decision status",
            lambda d: d["decisions"][2].update(status="mostly-done"))
    for ok_status in sorted(ALLOWED_D_STATUSES):
        doc = copy.deepcopy(good)
        doc["decisions"][0]["status"] = ok_status
        if ok_status != "built":
            # This loop exercises the documented decision-status vocabulary, not a contradictory
            # maturity claim. A non-built D1 cannot remain in the achieved-controls list.
            doc["controlMaturity"].update(current=3, achieved=["D2", "D3", "D4"])
        sink = io.StringIO()
        try:
            with contextlib.redirect_stderr(sink):
                validate_curated(doc)
        except SystemExit:
            fails.append(f"the documented status {ok_status!r} was rejected")

    # An EMPTY compliance list is the pure form of the failure: the section renders with no
    # rows and reads as "nothing to report" rather than "nobody wrote this".
    rejects("an empty compliance list", lambda d: d.update(compliance=[]))
    rejects("a non-list compliance", lambda d: d.update(compliance="none"))

    # The audit trail is the evidence half of the page. An empty list there renders as a
    # section with no rows, which reads as "no audit gaps" rather than "nobody supplied the
    # evidence" — the same shape as the compliance case, one level more consequential.
    for key in ("capture", "pipeline", "live", "planned"):
        rejects(f"an empty auditTrail.{key}", lambda d, k=key: d["auditTrail"].update({k: []}))
        rejects(f"a missing auditTrail.{key}", lambda d, k=key: d["auditTrail"].pop(k))
    rejects("a non-mapping auditTrail", lambda d: d.update(auditTrail=["x"]))

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: ai-governance snapshot validator is falsifiable (33 cases)")
    return 0

def main() -> None:
    if "--self-test" in sys.argv:
        return self_test()

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
    # `sys.exit(main() or 0)`, not a bare `main()`. main() is declared `-> None` and its other
    # exits happen via fail() raising SystemExit, so a bare call discards any RETURNED code —
    # which meant the self-test could print four failures and the process still exited 0.
    # A harness whose verdict the process throws away is not a harness.
    sys.exit(main() or 0)
