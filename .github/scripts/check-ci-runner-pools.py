#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# CI runner pool governance (ADR-0053; rules.yaml: ci_runners; issue #6458).
#
# WHY THIS EXISTS
#   `rules.yaml: ci_runners` declares three ARC scale sets — build, batch, deploy —
#   with a trust split (build/batch carry no cloud credentials; deploy pushes to ECR
#   and syncs ArgoCD) and a capacity split. Until this gate, NOTHING compared that
#   declaration to anything:
#
#     * `openbank-batch` is declared, with an `isolation_rationale` explaining the
#       capacity split it provides, and it is provisioned NOWHERE. The OpenTofu
#       creates exactly two scale sets. A job routed to it would sit `queued`
#       forever — the failure mode with no error message, because "no runner has
#       picked this up yet" and "no runner will ever exist" are the same state to
#       every observer. `rules.yaml: infra_apply.runner_routing` still routes the
#       tofu PLAN lane there on paper.
#
#     * `pr_jobs_allowed_pools: [build, batch]` says a pull_request job must never
#       target the credential-carrying deploy pool. That was the literal content of
#       `ci_runners.blocked_on` ("a CI lint enforcing pr_jobs_allowed_pools ... has
#       not landed"), with `target_enforce_date: 2026-08-31`. This is that lint.
#
#   The declaration reads as governance either way: an unprovisioned pool and a
#   provisioned one are the same three lines of YAML.
#
# WHAT IT CHECKS
#   1. USES-UNPROVISIONED (always fatal, never baselined). No workflow job may target
#      a scale set that no OpenTofu `runnerScaleSetName` provisions. This is the one
#      that turns a silent infinite queue into a red PR, so it has no escape hatch.
#   2. UNKNOWN-LABEL (fatal). A `runs-on:` label matching `openbank-*` must be a
#      declared `ci_runners.pools.<p>.scale_set`. Catches a typo, which otherwise
#      also queues forever.
#   3. PR-POOL (fatal). A workflow triggered by `pull_request`/`pull_request_target`
#      may only target pools listed in `pr_jobs_allowed_pools`.
#   4. DECLARED-UNPROVISIONED (ratcheted). A declared pool with no OpenTofu scale set
#      is reported unless baselined in KNOWN_UNPROVISIONED below. The ratchet fails in
#      BOTH directions: a baseline entry that becomes provisioned, or that names a pool
#      no longer declared, is also an error — so today's debt cannot quietly become
#      permanent, and cannot quietly be forgotten once paid.
#   5. USES-UNAPPLIED (reported, baselined in KNOWN_UNAPPLIED). There are THREE states,
#      not two, and #8388 is why this class exists. "Declared in rules.yaml", "written in
#      OpenTofu" and "running in the cluster" are different facts, and `load_provisioned`
#      can only ever read the middle one — CI has no cluster credentials. So when #8388
#      added helm_release.arc_batch/arc_dr, this gate immediately reported both pools as
#      provisioned, while the merge commit's Platform OpenTofu run did a PLAN only
#      (`tofu apply (manual dispatch)` skipped) and no apply has run since 2026-08-31.
#      The capacity does not exist; the gate built to see that gap could no longer see it.
#      A scale set in KNOWN_UNAPPLIED is therefore subtracted from `provisioned`, so the
#      other four classes reason about capacity that actually exists.
#
# EXIT CODES
#   0 — clean
#   1 — the gate could not answer (no pools parsed, no workflows found). A scan that
#       read nothing must never report green.
#   2 — findings

from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
from pathlib import Path

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gatelib  # noqa: E402  (path must be set first)

# Declared in rules.yaml: ci_runners.pools, provisioned by no OpenTofu scale set.
# Removing an entry requires either provisioning the pool or deleting its declaration.
# Both entries here are also in KNOWN_UNAPPLIED: their OpenTofu exists but has never
# been applied, so they are declared capacity that does not exist (#6458).
KNOWN_UNPROVISIONED: dict[str, str] = {
    "openbank-batch": "#6458 — OpenTofu added by #8388, never applied",
    "openbank-dr": "#6458 — OpenTofu added by #8388, never applied",
}

# Scale sets whose OpenTofu resource EXISTS but which no `tofu apply` has ever created.
# This map is the only way CI can tell "written" from "running": the gate has no cluster
# credentials, so `load_provisioned` reads *.tf and would otherwise call a resource that
# has never been applied "provisioned".
#
# EVIDENCE for the two entries below, measured 2026-09-03 against the live sandbox
# cluster (read-only) and the workflow history — three independent reads agreeing:
#   * `kubectl get autoscalingrunnersets -n arc-runners` lists exactly openbank-build
#     and openbank-deploy. No openbank-batch, no openbank-dr.
#   * The helm release secrets in that namespace are sh.helm.release.v1.openbank-build.*
#     and .openbank-deploy.* only — arc_batch/arc_dr have never been installed.
#   * `kubectl get sa -n arc-runners` has no openbank-dr service account, which the same
#     OpenTofu creates, so the apply cannot have run even partially.
#   * Platform OpenTofu run 33728996592 (push of #8388's merge commit b547afe8c) shows
#     `tofu plan (preview)` success and `tofu apply (manual dispatch)` SKIPPED; the last
#     real apply was the 2026-08-31 workflow_dispatch, before #8388 merged.
#
# REMOVING AN ENTRY is a human act after a cluster read — this gate cannot detect the
# apply, so it will not tell you when the debt is paid. Verify with the first command
# above, then delete the entry here and in KNOWN_UNPROVISIONED. The ratchet still fails
# if the OpenTofu resource disappears while the entry remains (STALE-UNAPPLIED), and any
# NEW job routed at an unapplied pool is reported, so the set cannot silently grow.
KNOWN_UNAPPLIED: dict[str, str] = {
    "openbank-batch": "#6458 — helm_release.arc_batch (arc-runners.tf) added by #8388, never applied",
    "openbank-dr": "#6458 — helm_release.arc_dr (arc-runners.tf) added by #8388, never applied",
}

SCALE_SET_RE = re.compile(r'runnerScaleSetName\s*=\s*"([^"]+)"')
PR_EVENTS = {"pull_request", "pull_request_target"}


def load_declared_pools(root: Path) -> tuple[dict[str, str], list[str]]:
    """Return {pool_name: scale_set} and pr_jobs_allowed_pools from rules.yaml."""
    rules = yaml.safe_load((root / "openbank-libs/governance/rules.yaml").read_text())
    ci = (rules or {}).get("ci_runners") or {}
    pools = {}
    for name, body in (ci.get("pools") or {}).items():
        if isinstance(body, dict) and body.get("scale_set"):
            pools[name] = body["scale_set"]
    return pools, list(ci.get("pr_jobs_allowed_pools") or [])


def load_provisioned(root: Path) -> set[str]:
    """Scale sets the OpenTofu actually creates."""
    found = set()
    infra = root / "openbank-infra"
    if infra.is_dir():
        for tf in infra.rglob("*.tf"):
            found.update(SCALE_SET_RE.findall(tf.read_text(errors="replace")))
    return found


def _labels(runs_on) -> list[str]:
    if isinstance(runs_on, str):
        return [runs_on]
    if isinstance(runs_on, list):
        return [x for x in runs_on if isinstance(x, str)]
    if isinstance(runs_on, dict):  # runs-on: { group:, labels: }
        return _labels(runs_on.get("labels"))
    return []


def _events(doc) -> set[str]:
    # PyYAML parses a bare `on:` key as the boolean True (YAML 1.1).
    on = doc.get("on", doc.get(True))
    if isinstance(on, str):
        return {on}
    if isinstance(on, list):
        return set(on)
    if isinstance(on, dict):
        return set(on.keys())
    return set()


def scan_workflows(root: Path):
    """Yield (workflow_path, job_id, label, events) for every runs-on label."""
    wf_dir = root / ".github/workflows"
    for wf in sorted(list(wf_dir.glob("*.yml")) + list(wf_dir.glob("*.yaml"))):
        try:
            doc = yaml.safe_load(wf.read_text())
        except yaml.YAMLError:
            continue
        if not isinstance(doc, dict):
            continue
        events = _events(doc)
        for job_id, job in (doc.get("jobs") or {}).items():
            if not isinstance(job, dict):
                continue
            for label in _labels(job.get("runs-on")):
                if "${{" in label:  # expression — not statically decidable
                    continue
                yield (wf.relative_to(root).as_posix(), job_id, label, events)


def evaluate(
    root: Path,
    baseline: dict[str, str] | None = None,
    unapplied: dict[str, str] | None = None,
) -> tuple[list[str], list[str], int, int]:
    """Return (findings, gaps, n_pools, n_labels_seen).

    `gaps` are known, baselined capacity gaps: reported loudly on every run so the
    declaration never reads as fine, but not fatal, because the fix is a `tofu apply`
    that only a human with cloud credentials can perform.
    """
    baseline = KNOWN_UNPROVISIONED if baseline is None else baseline
    unapplied = KNOWN_UNAPPLIED if unapplied is None else unapplied
    pools, pr_allowed = load_declared_pools(root)
    tf_declared = load_provisioned(root)
    # An OpenTofu resource nobody has applied is not capacity. Intersecting with what the
    # *.tf actually declares keeps a stale entry from inventing a pool that is not there.
    unapplied_sets = {s for s in unapplied if s in tf_declared}
    provisioned = tf_declared - unapplied_sets
    declared_sets = set(pools.values())
    pr_allowed_sets = {pools[p] for p in pr_allowed if p in pools}

    findings: list[str] = []
    gaps: list[str] = []
    n_labels = 0

    for wf, job, label, events in scan_workflows(root):
        if not label.startswith("openbank-"):
            continue
        n_labels += 1
        if label not in declared_sets:
            findings.append(
                f"UNKNOWN-LABEL {wf}: job '{job}' targets '{label}', which is not any "
                f"ci_runners.pools.*.scale_set. Nothing will ever pick that job up."
            )
            continue
        if label in unapplied_sets:
            gaps.append(
                f"USES-UNAPPLIED {wf}: job '{job}' targets '{label}', whose OpenTofu "
                f"exists but has never been applied — there is no such scale set in the "
                f"cluster, so this job queues forever. {unapplied[label]}"
            )
        elif label not in provisioned:
            findings.append(
                f"USES-UNPROVISIONED {wf}: job '{job}' targets '{label}', which no "
                f"OpenTofu runnerScaleSetName provisions. That job queues forever."
            )
        if events & PR_EVENTS and label not in pr_allowed_sets:
            findings.append(
                f"PR-POOL {wf}: job '{job}' runs on '{label}' from a "
                f"{sorted(events & PR_EVENTS)} trigger; rules.yaml "
                f"pr_jobs_allowed_pools permits only {sorted(pr_allowed_sets)}."
            )

    # Ratchet, both directions.
    for pool, scale_set in sorted(pools.items()):
        if scale_set in provisioned:
            if scale_set in baseline:
                findings.append(
                    f"STALE-BASELINE '{scale_set}' is now provisioned — remove it from "
                    f"KNOWN_UNPROVISIONED in {Path(__file__).name}."
                )
            continue
        if scale_set not in baseline:
            findings.append(
                f"DECLARED-UNPROVISIONED ci_runners.pools.{pool}.scale_set='{scale_set}' "
                f"is declared but no OpenTofu runnerScaleSetName creates it. Provision it, "
                f"delete the declaration, or baseline it with an issue reference."
            )
    for scale_set in sorted(baseline):
        if scale_set not in declared_sets:
            findings.append(
                f"STALE-BASELINE '{scale_set}' is baselined but is no longer declared in "
                f"ci_runners.pools — remove the baseline entry."
            )
    for scale_set in sorted(unapplied):
        if scale_set not in tf_declared:
            findings.append(
                f"STALE-UNAPPLIED '{scale_set}' is listed in KNOWN_UNAPPLIED but no "
                f"OpenTofu runnerScaleSetName declares it. Either the resource was "
                f"deleted (drop the entry) or it was renamed (update it)."
            )
        elif scale_set in declared_sets:
            gaps.append(
                f"DECLARED-UNAPPLIED '{scale_set}' is declared in ci_runners.pools and "
                f"written in OpenTofu, but no apply has created it. {unapplied[scale_set]}"
            )

    return findings, gaps, len(pools), n_labels


# --------------------------------------------------------------------------------------
# Self-test: prove each finding class is reachable, and that a clean fixture is clean.
# --------------------------------------------------------------------------------------

_BASE_POOLS = {"build": "openbank-build", "deploy": "openbank-deploy"}


def _rules(extra_pools: dict[str, str] | None = None) -> str:
    pools = dict(_BASE_POOLS)
    pools.update(extra_pools or {})
    body = "".join(f"    {n}:\n      scale_set: {s}\n" for n, s in pools.items())
    return "ci_runners:\n  pools:\n" + body + "  pr_jobs_allowed_pools: [build]\n"


_TF = 'resource "helm_release" "b" {\n  values = [yamlencode({\n    runnerScaleSetName = "openbank-build"\n  })]\n}\nresource "helm_release" "d" {\n  values = [yamlencode({\n    runnerScaleSetName = "openbank-deploy"\n  })]\n}\n'


def _fixture(tmp: Path, rules: str, tf: str, workflow: str) -> Path:
    root = tmp
    (root / "openbank-libs/governance").mkdir(parents=True, exist_ok=True)
    (root / "openbank-libs/governance/rules.yaml").write_text(rules)
    (root / "openbank-infra").mkdir(parents=True, exist_ok=True)
    (root / "openbank-infra/arc.tf").write_text(tf)
    (root / ".github/workflows").mkdir(parents=True, exist_ok=True)
    (root / ".github/workflows/w.yml").write_text(workflow)
    return root


def self_test() -> int:
    empty: dict[str, str] = {}
    cases = [
        ("control: a push job on a provisioned, declared pool is clean",
         _rules(), "push", "openbank-deploy", empty, None, empty),
        ("USES-UNPROVISIONED: a job on a declared-but-unprovisioned pool",
         _rules({"batch": "openbank-batch"}), "push", "openbank-batch", empty, "USES-UNPROVISIONED", empty),
        ("UNKNOWN-LABEL: a typo'd openbank-* label",
         _rules(), "push", "openbank-buidl", empty, "UNKNOWN-LABEL", empty),
        ("PR-POOL: a pull_request job on the credential-carrying deploy pool",
         _rules(), "pull_request", "openbank-deploy", empty, "PR-POOL", empty),
        ("DECLARED-UNPROVISIONED: a declared pool no OpenTofu creates",
         _rules({"ghost": "openbank-ghost"}), "push", "openbank-build", empty, "DECLARED-UNPROVISIONED", empty),
        ("STALE-BASELINE (provisioned): a baselined pool that now exists",
         _rules(), "push", "openbank-build", {"openbank-build": "x"}, "STALE-BASELINE", empty),
        ("STALE-BASELINE (undeclared): a baselined pool no longer declared",
         _rules(), "push", "openbank-build", {"openbank-gone": "x"}, "STALE-BASELINE", empty),
        # The #8388 regression itself: the OpenTofu declares openbank-build, so the old
        # gate called it provisioned. Marked unapplied, a job on it is a reported gap.
        ("USES-UNAPPLIED: a job on a pool whose OpenTofu exists but was never applied",
         _rules(), "push", "openbank-build", {"openbank-build": "x"}, "USES-UNAPPLIED",
         {"openbank-build": "#6458"}),
        # Both halves of the ratchet on the new map.
        ("DECLARED-UNAPPLIED: a declared pool written in OpenTofu but never applied",
         _rules(), "push", "openbank-deploy", {"openbank-build": "x"}, "DECLARED-UNAPPLIED",
         {"openbank-build": "#6458"}),
        ("STALE-UNAPPLIED: an unapplied entry whose OpenTofu resource is gone",
         _rules(), "push", "openbank-deploy", empty, "STALE-UNAPPLIED", {"openbank-vanished": "#6458"}),
    ]
    rc = 0
    for i, (desc, rules, event, label, baseline, expect, unapplied) in enumerate(cases, 1):
        wf = (f"on:\n  {event}:\n    branches: [main]\n"
              f"jobs:\n  a:\n    runs-on: {label}\n    steps: [{{run: 'true'}}]\n")
        with tempfile.TemporaryDirectory() as td:
            root = _fixture(Path(td), rules, _TF, wf)
            findings, gaps, n_pools, _ = evaluate(root, baseline, unapplied)
            findings = findings + gaps
            if n_pools == 0:
                print(f"SELF-TEST FAIL case {i} ({desc}): fixture parsed no pools")
                rc = 1
                continue
            if expect is None:
                if findings:
                    print(f"SELF-TEST FAIL case {i} ({desc}): expected clean, got {findings}")
                    rc = 1
                else:
                    print(f"self-test case {i} OK — {desc}")
            else:
                hit = [f for f in findings if f.startswith(expect)]
                if not hit:
                    print(f"SELF-TEST FAIL case {i} ({desc}): expected a {expect} finding, got {findings}")
                    rc = 1
                else:
                    print(f"self-test case {i} OK — {desc}")
    if rc == 0:
        print("check-ci-runner-pools self-test: every finding class is reachable, control is clean")
    return rc


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=os.environ.get("GITHUB_WORKSPACE", "."))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = Path(args.root).resolve()
    findings, gaps, n_pools, n_labels = evaluate(root)

    if n_pools == 0:
        print("ERROR: parsed zero ci_runners pools from rules.yaml — the gate read nothing.", file=sys.stderr)
        return 1
    if not (root / ".github/workflows").is_dir():
        print("ERROR: no .github/workflows directory — the gate read nothing.", file=sys.stderr)
        return 1

    # The corpus is the DECLARATION plus the labels in use: if rules.yaml stops parsing, or
    # every workflow loses its self-hosted `runs-on`, every clause matches nothing and the
    # gate passes everything while still exiting 0.
    gatelib.subjects(n_pools + n_labels, "declared pools + self-hosted runs-on labels")
    print(f"ci-runner-pools: {n_pools} declared pools, {n_labels} self-hosted runs-on labels examined")
    if KNOWN_UNPROVISIONED:
        for k, v in sorted(KNOWN_UNPROVISIONED.items()):
            print(f"  baselined unprovisioned pool: {k} ({v})")
    # Known capacity gaps are printed as warnings on EVERY run: the whole point of #6458
    # is that a declared-but-absent pool must never read as fine. They are not fatal —
    # the fix is a `tofu apply` a human must run — but they are never silent either.
    for g in gaps:
        print(f"::warning::{g}")
    if findings:
        for f in findings:
            print(f"::error::{f}")
        return 2
    if gaps:
        print(f"ci-runner-pools: {len(gaps)} known capacity gap(s) reported above (#6458); no new findings")
        return 0
    print("ci-runner-pools: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
