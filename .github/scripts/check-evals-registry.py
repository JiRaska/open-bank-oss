#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: the agent evals registry at openbank-libs/governance/evals/ (ADR-0148).
#
# WHY THIS EXISTS
#   The evals gate (ADR-0148) blocks a model/prompt promotion that regresses a charter's scenario
#   pass rate — the ADR-0020 ratchet applied to agents. The runner that executes scenarios is a
#   later increment; this guard is the structural gate that keeps the eval-as-code registry it will
#   consume well-formed: a suite naming a non-existent charter, a malformed scenario, or an
#   assertion key the runner cannot evaluate is a silent hole in the gate.
#
# WHAT IT CHECKS
#   HARD (exit 1):
#     * a file whose `charter:` is not an id: in agents.yaml, or whose filename != <charter>.yaml,
#     * a missing/invalid `version:` (must be v<N>),
#     * an empty `scenarios:` list,
#     * a scenario missing id/description/input/assert, a non-[a-z0-9-] or duplicate id,
#     * an empty `assert`, or an assertion key the runner does not understand,
#     * a `prompt:` that does not resolve to prompts/<charter>/<prompt>.md (a dangling prompt ref).
#   ADVISORY (::warning, never fails): charters with no eval suite yet (backlog).
#
# Run:  python3 .github/scripts/check-evals-registry.py

import pathlib
import re
import sys

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parents[2]
GOV = ROOT / "openbank-libs" / "governance"
EVALS = GOV / "evals"
AGENTS = GOV / "agents.yaml"
PROMPTS = GOV / "prompts"

VERSION_RE = re.compile(r"^v[0-9]+$")
ID_RE = re.compile(r"^[a-z0-9-]+$")
KNOWN_ASSERTS = {"must_not_be_empty", "must_contain", "must_not_contain"}

# Statuses from prompts/registry.yaml that put a charter OUT OF SCOPE for evals rather than in the
# backlog (issue #2381). Both exclusions are about what the harness can physically do:
#   not-applicable — the charter causes no model call at all (an identity-only principal), so there
#                    is no output for a scenario to assert on.
#   external       — a real model runs, but this repo neither authors the prompt nor makes the call
#                    (HolmesGPT's own image; an operator's coding-agent session). run-evals.py
#                    records a run of OUR prompt against OUR model call; for these there is nothing
#                    to record, and asserting on someone else's output would measure their prompt
#                    while reading as coverage of ours.
# `registered` and `pending` stay in the backlog: those are charters this platform does call.
EVAL_EXEMPT = {"not-applicable", "external"}

REGISTRY = PROMPTS / "registry.yaml"


def charter_ids():
    data = yaml.safe_load(AGENTS.read_text())
    return {a.get("id") for a in (data.get("agents", []) or []) if a.get("id")}


def charter_status(errors):
    """Map charter id -> prompts/registry.yaml status (the #1918 coverage vocabulary)."""
    if not REGISTRY.is_file():
        errors.append(f"{REGISTRY.relative_to(ROOT)} is missing — eval coverage cannot distinguish "
                      f"a charter that has no suite yet from one that can never have one")
        return {}
    try:
        doc = yaml.safe_load(REGISTRY.read_text()) or {}
    except yaml.YAMLError as e:
        errors.append(f"{REGISTRY.relative_to(ROOT)} — not valid YAML: {e}")
        return {}
    return {e.get("id"): e.get("status") for e in (doc.get("charters", []) or []) if e.get("id")}


def check_file(path, ids, errors):
    rel = path.relative_to(ROOT)
    try:
        doc = yaml.safe_load(path.read_text()) or {}
    except yaml.YAMLError as e:
        errors.append(f"{rel} — not valid YAML: {e}")
        return None

    charter = doc.get("charter")
    if charter not in ids:
        errors.append(f"{rel} — charter '{charter}' is not an id: in agents.yaml")
    if path.stem != str(charter):
        errors.append(f"{rel} — filename must be <charter>.yaml (charter: {charter})")
    if not VERSION_RE.match(str(doc.get("version", ""))):
        errors.append(f"{rel} — version must be v<N> (got {doc.get('version')!r})")

    prompt = doc.get("prompt")
    if prompt is not None:
        pf = PROMPTS / str(charter) / f"{prompt}.md"
        if not pf.is_file():
            errors.append(f"{rel} — prompt '{prompt}' does not resolve to "
                          f"{pf.relative_to(ROOT)} (dangling prompt reference)")

    scenarios = doc.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios:
        errors.append(f"{rel} — scenarios: must be a non-empty list")
        return charter

    seen = set()
    for i, sc in enumerate(scenarios):
        where = f"{rel} scenario[{i}]"
        if not isinstance(sc, dict):
            errors.append(f"{where} — must be a mapping")
            continue
        sid = sc.get("id")
        if not sid or not ID_RE.match(str(sid)):
            errors.append(f"{where} — id must match [a-z0-9-]+ (got {sid!r})")
        elif sid in seen:
            errors.append(f"{where} — duplicate scenario id '{sid}'")
        else:
            seen.add(sid)
        for field in ("description", "input"):
            if not str(sc.get(field, "")).strip():
                errors.append(f"{where} ({sid}) — {field} is required and non-empty")
        assertions = sc.get("assert")
        if not isinstance(assertions, dict) or not assertions:
            errors.append(f"{where} ({sid}) — assert must be a non-empty mapping")
        else:
            unknown = set(assertions) - KNOWN_ASSERTS
            if unknown:
                errors.append(f"{where} ({sid}) — unknown assertion key(s): "
                              f"{', '.join(sorted(unknown))}; runner understands {sorted(KNOWN_ASSERTS)}")
    return charter


def main():
    if not EVALS.is_dir():
        sys.stderr.write(f"::error::evals registry directory missing: {EVALS.relative_to(ROOT)}\n")
        return 1

    ids = charter_ids()
    errors = []
    covered = set()
    files = sorted(EVALS.glob("*.yaml"))

    for path in files:
        before = len(errors)
        charter = check_file(path, ids, errors)
        if charter:
            covered.add(charter)
        if len(errors) == before:
            print(f"  ok  {path.relative_to(ROOT)}")

    # Coverage is scored against the prompt-registry status vocabulary, not against the raw charter
    # list (issue #2381). Before this, a charter that CANNOT have an eval suite counted as backlog:
    # mcp-anonymous and ap2-anonymous are identity-only principals that make no model call at all,
    # so they would have sat in the warning line forever, and a permanently-unclosable item trains
    # readers to skim the whole warning. That is the exact absent-vs-not-applicable conflation
    # prompts/registry.yaml was introduced to end (#1918) — check-prompt-registry.py honours the
    # vocabulary; this half never learned it.
    status = charter_status(errors)
    backlog, excluded = [], []
    for cid in sorted(ids - covered):
        st = status.get(cid)
        if st in EVAL_EXEMPT:
            excluded.append(f"{cid} ({st})")
        else:
            backlog.append(cid)

    # A suite for a charter that never causes a model call is a real contradiction: there is no
    # model output for the scenarios to assert on. Nothing caught it before.
    for cid in sorted(covered):
        if status.get(cid) == "not-applicable":
            errors.append(f"evals/{cid}.yaml exists but prompts/registry.yaml declares "
                          f"'{cid}' not-applicable (it makes no model call) — an eval suite for a "
                          f"charter with no model output cannot assert anything")

    if backlog:
        print(f"::warning title=Evals registry::{len(backlog)} charter(s) have no eval suite yet "
              f"(backlog, ADR-0148): {', '.join(backlog)}")
    if excluded:
        print(f"evals-registry: {len(excluded)} charter(s) out of scope by registry.yaml status "
              f"(not backlog): {', '.join(excluded)}")
    missing = backlog

    if errors:
        for e in errors:
            sys.stderr.write(f"::error title=Evals registry::{e}\n")
        sys.stderr.write(f"::error::check-evals-registry: {len(errors)} violation(s).\n")
        return 1

    print(f"evals-registry: {len(files)} suite(s) across {len(covered)} charter(s), structure OK; "
          f"{len(missing)} charter(s) pending.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
