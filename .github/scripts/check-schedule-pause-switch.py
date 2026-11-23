#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every `schedule:`-triggered workflow carries an instant pause switch, or says why not.

WHY THIS EXISTS
---------------
ADR-0389: stopping or pausing automation must never need a PR. A PR to pause a cron is a
PR that waits in the same queue the cron may be flooding. So each scheduled workflow gates
its entry jobs on two repository variables that an operator can flip in seconds:

    if: github.event_name != 'schedule' ||
        (vars.<WORKFLOW_ID>_SCHEDULE_PAUSED != 'true' && vars.SCHEDULES_PAUSED != 'true')

`<WORKFLOW_ID>` is the workflow filename, upper-snake (`auto-deploy.yml` ->
`AUTO_DEPLOY`). Only the `schedule` event is paused; `workflow_dispatch` and every other
trigger always run. Unset reads as '' and therefore as "not paused".

WHICH JOBS
----------
An ENTRY job: one with no `needs:`, or one whose `if:` calls a status function
(`always()`, `failure()`, `cancelled()`), since such a job runs even when its needs were
skipped. Every other job is skipped transitively when its entry jobs are.

THE EXCLUSIONS
--------------
A workflow whose schedule IS a control — security scanning, secret scanning, compliance
evidence, backup/DR verification, liveness and red watches, attestation — must not be
pausable without review, so pausing it stays a PR. Those are listed in EXCLUDED with a
reason. A stale exclusion (the file no longer exists or is no longer scheduled) is a
finding too, so the list cannot outlive its subjects.

Advisory: prints findings and exits 1 only with --enforce.
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

import yaml

GLOBAL_VAR = "SCHEDULES_PAUSED"
STATUS_FN = re.compile(r"\b(always|failure|cancelled)\s*\(\s*\)")

# filename -> reason pausing must stay a reviewed PR.
EXCLUDED: dict[str, str] = {
    "finops-lifecycle.yml": "weekly re-check of managed-service end-of-support pins (ADR-0054 cost guard)",
    "auto-deploy.yml": "money-path stranded-service reconcile tick",
    "admin-ui-deploy.yml": "deploy lane; its schedule recovers missed deploys",
    "pact-verification-reconcile.yml": "keeps can-i-deploy unblocked by dispatching provider verification",
    "deploy-window-flush.yml": "only arms deferred deploy PRs; pausing strands the deploy lane",
    "pitest.yml": "pitest-authz produces enforced mutation evidence",
    "perf-baseline.yml": "produces the admin-ui perf evidence dataset",
    "ghcr-publish.yml": "publishes images with provenance attestations",
    "admin-ui-browser-synthetic.yml": "deployed SSO-boundary / security synthetic probe",
    "admin-ui-deployed-auth-gate.yml": "verifies the deployed admin-ui auth gate (security control)",
    "admin-ui-evidence.yml": "publishes compliance evidence datasets",
    "api-fuzz.yml": "API fuzzing earns the pentest attestation",
    "auto-deploy-red-watch.yml": "red watch on the deploy lane (liveness)",
    "branch-protection-parity.yml": "branch-protection policy compliance evidence",
    "credential-inventory.yml": "credential inventory (security)",
    "ct-caa-watch.yml": "certificate-transparency / CAA watch (security)",
    "dast-zap-baseline.yml": "DAST security scan",
    "date-driven-expiry-watch.yml": "credential and pin expiry watch (security liveness)",
    "db-backup-associations.yml": "backup verification",
    "deception-drill.yml": "security deception drill",
    "dependency-submission.yml": "dependency graph feeding vulnerability alerts (security)",
    "deploy-code-lag-watch.yml": "deployed-code lag watch (liveness)",
    "deploy-drift-watch.yml": "deployed == main drift watch (liveness)",
    "dr-restore-verify.yml": "DR restore verification",
    "ecr-live-tags.yml": "keeps deployed images out of registry lifecycle expiry (DR)",
    "external-feed-watch.yml": "external data-feed liveness watch",
    "fleet-attestation.yml": "fleet attestation verification",
    "governance-horizon-watch.yml": "governance/compliance date-horizon watch",
    "image-rescan.yml": "container image vulnerability rescan (security)",
    "main-red-watch.yml": "red watch on main (liveness)",
    "merge-train.yml": "the merge train owns its own hold mechanics; out of scope here",
    "merged-past-red-check-watch.yml": "detects merges past a red required check (control)",
    "ops-heartbeat.yml": "operations heartbeat (liveness)",
    "perf-gate.yml": "carries the k6 security-abuse contract probe",
    "public-readiness.yml": "public-repo leak audit (security)",
    "reproducible-build.yml": "reproducible-build verification (supply chain)",
    "scorecard.yml": "OpenSSF Scorecard security scan",
    "secret-scan.yml": "secret scanning",
    "security-kpis.yml": "security KPI evidence snapshot",
    "security.yml": "security scan",
    "service-build-stall-watch.yml": "build stall watch (liveness)",
    "services-ci.yml": "nightly full-fleet build is the rot detector and also the required PR gate",
    "standing-red-sentinel.yml": "standing-red sentinel (liveness)",
    "verification-metadata-drift.yml": "dependency verification metadata drift (supply chain)",
    "verify-release-evidence.yml": "release evidence verification",
    "vex-fleet-scope.yml": "VEX vulnerability triage (security)",
    "vex-hotfix.yml": "VEX vulnerability triage (security)",
    "vex-triage.yml": "VEX vulnerability triage (security)",
}


def workflow_id(filename: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", filename.rsplit(".", 1)[0]).upper()


def var_name(filename: str) -> str:
    return f"{workflow_id(filename)}_SCHEDULE_PAUSED"


def triggers(doc: dict) -> dict:
    on = doc.get(True, doc.get("on"))
    if isinstance(on, str):
        return {on: None}
    if isinstance(on, list):
        return {k: None for k in on}
    return on or {}


def entry_jobs(doc: dict) -> list[tuple[str, str]]:
    out = []
    for name, job in (doc.get("jobs") or {}).items():
        job = job or {}
        cond = str(job.get("if", "") or "")
        if not job.get("needs") or STATUS_FN.search(cond):
            out.append((name, cond))
    return out


def switch_expr(filename: str) -> str:
    return (f"github.event_name != 'schedule' || (vars.{var_name(filename)} != 'true' && "
            f"vars.{GLOBAL_VAR} != 'true')")


def _wraps(expr: str) -> bool:
    """True when expr is `( ... )` with the first paren closing at the very end."""
    if not (expr.startswith("(") and expr.endswith(")")):
        return False
    depth, quote = 0, False
    for i, ch in enumerate(expr):
        if ch == "'":
            quote = not quote
        elif not quote and ch == "(":
            depth += 1
        elif not quote and ch == ")":
            depth -= 1
            if depth == 0 and i != len(expr) - 1:
                return False
    return depth == 0 and not quote


def has_switch(cond: str, filename: str) -> bool:
    """Exact structural form only: `<switch>` alone, or `(<switch>) && (<original>)`.

    A substring match would accept `<switch> || always()`, where operator precedence lets
    the job run on a paused schedule anyway."""
    c = re.sub(r"\s+", " ", cond).strip()
    m = re.fullmatch(r"\$\{\{(.*)\}\}", c)
    if m:
        c = m.group(1).strip()
    sw = switch_expr(filename)
    if c == sw:
        return True
    head = f"({sw}) && "
    return c.startswith(head) and _wraps(c[len(head):])


def check(wf_dir: Path, excluded: dict[str, str]) -> tuple[int, list[str]]:
    findings: list[str] = []
    subjects = 0
    scheduled = set()
    for f in sorted(list(wf_dir.glob("*.yml")) + list(wf_dir.glob("*.yaml"))):
        try:
            doc = yaml.safe_load(f.read_text()) or {}
        except yaml.YAMLError as e:
            findings.append(f"{f.name}: does not parse ({e.__class__.__name__}) — cannot check")
            continue
        if "schedule" not in triggers(doc):
            continue
        scheduled.add(f.name)
        subjects += 1
        if f.name in excluded:
            continue
        jobs = entry_jobs(doc)
        if not jobs:
            findings.append(f"{f.name}: scheduled but no entry job found")
        for name, cond in jobs:
            if not has_switch(cond, f.name):
                findings.append(
                    f"{f.name}: job `{name}` lacks the pause switch — add "
                    f"`github.event_name != 'schedule' || (vars.{var_name(f.name)} != 'true' && "
                    f"vars.{GLOBAL_VAR} != 'true')` to its if:, or list the workflow in EXCLUDED "
                    f"with a reason"
                )
    for name in sorted(set(excluded) - scheduled):
        findings.append(f"EXCLUDED entry {name}: no such scheduled workflow — remove it (stale exclusion)")
    return subjects, findings


def self_test() -> int:
    good = (
        "on:\n  schedule:\n    - cron: '0 1 * * *'\n  workflow_dispatch:\n"
        "jobs:\n  a:\n    if: github.event_name != 'schedule' || (vars.GOOD_SCHEDULE_PAUSED != 'true' && "
        "vars.SCHEDULES_PAUSED != 'true')\n    runs-on: x\n    steps: [{run: 'true'}]\n"
        "  b:\n    needs: a\n    runs-on: x\n    steps: [{run: 'true'}]\n"
    )
    cases = [
        ("switched workflow passes", {"good.yml": good}, {}, None),
        ("new scheduled workflow without switch fails",
         {"good.yml": good, "new-cron.yml": "on:\n  schedule:\n    - cron: '0 1 * * *'\njobs:\n  a:\n    runs-on: x\n    steps: [{run: 'true'}]\n"},
         {}, "new-cron.yml: job `a` lacks the pause switch"),
        ("always() dependent without switch fails",
         {"good.yml": good.replace("    needs: a\n", "    needs: a\n    if: always()\n")},
         {}, "good.yml: job `b` lacks the pause switch"),
        ("loose switch OR-ed with always() fails",
         {"good.yml": good.replace("(vars.GOOD_SCHEDULE_PAUSED != 'true' && vars.SCHEDULES_PAUSED != 'true')",
                                   "vars.GOOD_SCHEDULE_PAUSED != 'true' && vars.SCHEDULES_PAUSED != 'true' || always()")},
         {}, "good.yml: job `a` lacks the pause switch"),
        ("switch && unwrapped original fails",
         {"good.yml": good.replace("    if: github", "    if: (github").replace("'true')\n    runs-on", "'true')) && (always()) || x\n    runs-on", 1)},
         {}, "good.yml: job `a` lacks the pause switch"),
        ("combined structural form passes",
         {"good.yml": good.replace("    needs: a\n", "    needs: a\n    if: (github.event_name != 'schedule' || (vars.GOOD_SCHEDULE_PAUSED != 'true' && vars.SCHEDULES_PAUSED != 'true')) && (always() && x == ')')\n")},
         {}, None),
        ("switch for the wrong workflow id fails",
         {"other.yml": good}, {}, "other.yml: job `a` lacks the pause switch"),
        ("excluded workflow passes",
         {"scan.yml": "on:\n  schedule:\n    - cron: '0 1 * * *'\njobs:\n  a:\n    runs-on: x\n    steps: [{run: 'true'}]\n"},
         {"scan.yml": "security scan"}, None),
        ("stale exclusion fails", {"good.yml": good}, {"gone.yml": "x"}, "stale exclusion"),
        ("unscheduled workflow is not a subject",
         {"push.yml": "on: push\njobs:\n  a:\n    runs-on: x\n    steps: [{run: 'true'}]\n"}, {}, None),
    ]
    bad = 0
    for label, files, excl, expect in cases:
        with tempfile.TemporaryDirectory() as d:
            for n, t in files.items():
                (Path(d) / n).write_text(t)
            _, findings = check(Path(d), excl)
        joined = "\n".join(findings)
        ok = (not findings) if expect is None else (expect in joined)
        print(f"{'ok  ' if ok else 'FAIL'} {label}" + ("" if ok else f"\n     got: {findings}"))
        bad += not ok
    print(f"self-test: {len(cases) - bad}/{len(cases)} cases behave")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--workflows", default=".github/workflows")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--list-vars", action="store_true", help="print every switch variable name")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    wf = Path(a.workflows)
    if a.list_vars:
        names = {GLOBAL_VAR}
        for f in list(wf.glob("*.yml")) + list(wf.glob("*.yaml")):
            if f.name not in EXCLUDED and "schedule" in triggers(yaml.safe_load(f.read_text()) or {}):
                names.add(var_name(f.name))
        print("\n".join(sorted(names)))
        return 0
    subjects, findings = check(wf, EXCLUDED)
    print(f"SUBJECTS={subjects}")
    for x in findings:
        print(("::error::" if a.enforce else "::warning::") + x)
    print(f"{len(findings)} finding(s) over {subjects} scheduled workflow(s), {len(EXCLUDED)} excluded")
    return 1 if findings and a.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
