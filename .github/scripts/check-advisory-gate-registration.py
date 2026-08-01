#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: every ADVISORY gate in CI must be registered in rules.yaml with a deadline (issue #2392).
#
# WHY THIS EXISTS
#   ADR-0144 says an advisory rule must carry a `target_enforce_date`, and
#   check-gate-graduation.sh enforces that — but it walks rules.yaml and NOTHING ELSE. So the
#   invariant only ever applied to gates that had already opted in. A CI gate that never got a
#   rules.yaml entry was invisible to it: no deadline, no graduation pressure, no failure when it
#   sat advisory forever.
#
#   That is not hypothetical. check-prompt-registry.py ran `continue-on-error: true` with no entry
#   and was RED on main for a month (#2363) — the unregistered file being the prompt hardened
#   against injection. check-evals-registry.py the same (#2375). check-authz-pdp-parity.py cited
#   `rules.yaml: authz_pdp_parity`, a key that never existed, so it read as governed while being
#   exactly as unwatched (#2393). Each flip happened because a person ran the script by hand.
#
#   This script closes the loop from the CI side: rules.yaml can no longer be the only place that
#   knows a gate exists.
#
# WHAT IT CHECKS
#   For every advisory governance gate — whether declared in .github/gates/gates.yaml or still
#   living as a workflow step — the checker it runs (`check-*.py|sh`, `run-evals.py`) must be named
#   as some rules.yaml rule's `ci_producer:`, and that rule must carry both
#   `enforced: advisory|planned` and a `target_enforce_date`.
#
#   TWO SOURCES, deliberately. Most gates now live in .github/gates/gates.yaml, where `mode:` says
#   advisory-or-enforced OUTRIGHT — no name heuristic, no `continue-on-error` inference. Workflow
#   steps are still scanned because gates exist outside the manifest (opa-policy.yml, fleet-lint.yml,
#   the pact workflows). Dropping the workflow scan when the manifest landed would have quietly
#   narrowed this gate; keeping only the workflow scan would have quietly emptied it, since the 79
#   steps it used to read moved into the manifest in the same PR. `--expect-manifest` (used in CI)
#   fails if the manifest yields nothing, so "the manifest moved again" cannot read as "no advisory
#   gates exist".
#
#   Both halves of "advisory" are covered on purpose. `continue-on-error` is the obvious one, but
#   most advisory gates here are advisory INSIDE the script — it prints ::warning and exits 0 unless
#   passed --enforce — so a scan for continue-on-error alone would have found 1 of the 12 that exist
#   today and reported the other 11 as enforced.
#
#   Deliberately NOT flagged: non-governance steps with continue-on-error (ECR login, cache warm,
#   Codecov upload, Trivy install). Those are best-effort infrastructure, not rules with a
#   graduation story, and demanding a rules.yaml entry for "Install Trivy" would make this gate
#   noise that gets silenced.
#
# Run:  python3 .github/scripts/check-advisory-gate-registration.py [--root .]

import argparse
import pathlib
import re
import sys

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

WORKFLOWS = ".github/workflows/*.yml"
MANIFEST = ".github/gates/gates.yaml"
RULES = "openbank-libs/governance/rules.yaml"

CHECKER_RE = re.compile(r"((?:\.github|openbank-infra)/scripts/(?:check|run)-[a-z0-9-]+\.(?:py|sh))")
GRADUATING = {"advisory", "planned"}


def rules_by_producer(root: pathlib.Path, errors):
    """Map every ci_producer script path -> (rule name, enforced, has_date).

    Walks the PARSED document, not the raw lines. A line-wise scan (the first draft here) attributes
    `enforced:`/`ci_producer:` to whichever bare `key:` line it saw last, which is wrong the moment a
    rule contains a nested mapping — `finops_tiers` holds a `tiers:` sub-tree and declares its own
    `enforced`/`target_enforce_date` AFTER it, so the fields landed under `T3`. The gate reported a
    rule as registered while reading another rule's metadata.
    """
    f = root / RULES
    if not f.is_file():
        errors.append(f"{RULES} not found — cannot check gate registration")
        return {}
    try:
        doc = yaml.safe_load(f.read_text()) or {}
    except yaml.YAMLError as e:
        errors.append(f"{RULES} — not valid YAML: {e}")
        return {}

    out = {}

    def walk(node, name):
        if not isinstance(node, dict):
            return
        producer = node.get("ci_producer")
        if isinstance(producer, str):
            for script in CHECKER_RE.findall(producer):
                out[script] = (name, node.get("enforced"), "target_enforce_date" in node)
        for key, value in node.items():
            if isinstance(value, dict):
                walk(value, key)

    walk(doc, "<root>")
    return out


def advisory_steps(root: pathlib.Path, errors):
    """Yield (workflow, job, step-name, script) for advisory governance-checker steps."""
    for wf in sorted(root.glob(WORKFLOWS)):
        try:
            doc = yaml.safe_load(wf.read_text()) or {}
        except yaml.YAMLError as e:
            errors.append(f"{wf.name} — not valid YAML: {e}")
            continue
        for job_name, job in (doc.get("jobs") or {}).items():
            for step in (job.get("steps") or []):
                run = str(step.get("run") or "")
                name = str(step.get("name") or "")
                scripts = CHECKER_RE.findall(run)
                if not scripts:
                    continue
                # "enforced" in the name WINS over "advisory". Without that precedence this gate
                # flags itself — its own step is named "advisory-gate registration (…, enforced)",
                # and a step that says `(ADR-0148, enforced)` while explaining what advisory means
                # is a normal shape here. Same family as the comment-stripping in
                # check-roles-allowed-realm.py: a name-matching heuristic that cannot tell a gate
                # from a gate ABOUT gates manufactures its own findings.
                label = name.lower()
                is_advisory = step.get("continue-on-error") is True or (
                    "advisory" in label and "enforced" not in label
                )
                if not is_advisory:
                    continue
                for script in scripts:
                    yield wf.name, job_name, name, script


def advisory_manifest_gates(root: pathlib.Path, errors):
    """Yield (source, group, gate-name, script) for advisory gates in .github/gates/gates.yaml.

    No heuristic here: the manifest states `mode: advisory` outright, which is the whole reason
    the gates were moved into it. The step-name matching below exists only for the gates that
    still live in workflows.
    """
    f = root / MANIFEST
    if not f.is_file():
        return
    try:
        doc = yaml.safe_load(f.read_text()) or {}
    except yaml.YAMLError as e:
        errors.append(f"{MANIFEST} — not valid YAML: {e}")
        return
    for gate in doc.get("gates") or []:
        if gate.get("mode") != "advisory":
            continue
        for script in CHECKER_RE.findall(str(gate.get("run") or "")):
            yield MANIFEST, gate.get("group", "?"), gate.get("id", "?"), script


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument(
        "--expect-manifest",
        action="store_true",
        help="fail if .github/gates/gates.yaml contributes no advisory gate (see header)",
    )
    args = ap.parse_args()
    root = pathlib.Path(args.root).resolve()

    errors = []
    producers = rules_by_producer(root, errors)
    checked = 0

    from_manifest = list(advisory_manifest_gates(root, errors))
    if args.expect_manifest and not from_manifest:
        errors.append(
            f"{MANIFEST} contributed 0 advisory gates. Either every gate really is enforced now "
            f"(then drop --expect-manifest in ci.yml, in the same commit), or this script has "
            f"stopped reading the manifest and is about to report a green over nothing.",
        )

    for wf, job, name, script in list(from_manifest) + list(advisory_steps(root, errors)):
        checked += 1
        where = f"{wf} :: {job} :: {name or script}"
        if script not in producers:
            errors.append(
                f"{where} — advisory gate running {script} has no rules.yaml rule naming it as "
                f"`ci_producer`. An advisory gate with no rule has no target_enforce_date, so "
                f"check-gate-graduation.sh (ADR-0144) cannot see it and it stays advisory forever. "
                f"Add a rule with `enforced: advisory` + `target_enforce_date` + `ci_producer`, or "
                f"make the step enforced.",
            )
            continue
        rule, enforced, has_date = producers[script]
        if enforced not in GRADUATING:
            errors.append(
                f"{where} — rules.yaml `{rule}` declares `enforced: {enforced}` but the CI step is "
                f"advisory. The rule and the gate disagree about whether this blocks; fix whichever "
                f"is wrong.",
            )
        elif not has_date:
            errors.append(
                f"{where} — rules.yaml `{rule}` is `enforced: {enforced}` with no "
                f"`target_enforce_date` (ADR-0144 requires one).",
            )

    if errors:
        for e in errors:
            sys.stderr.write(f"::error title=Advisory gate registration::{e}\n")
        sys.stderr.write(
            f"::error::check-advisory-gate-registration: {len(errors)} unregistered advisory gate(s).\n",
        )
        return 1

    print(
        f"advisory-gate registration: {checked} advisory governance gate(s) checked "
        f"({len(from_manifest)} from {MANIFEST}, {checked - len(from_manifest)} from workflow "
        f"steps) against {len(producers)} rules.yaml ci_producer entr(ies); all registered with "
        f"a deadline.",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
