#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A required status-check context must be a job that some tracked workflow still emits.

WHY THIS EXISTS (ADR-0254)
--------------------------
Closing #4339 required deleting four workflows (`adr-registry.yml`, `eu-ai-act-registry.yml`,
`agent-charter-registry.yml`, `ai-governance-snapshot.yml`) that duplicated declared gates.
Before deleting them I had to read `main-protection`'s required contexts by hand
(`gh api repos/.../rulesets/<id>`) to confirm none of their job names was one — nothing in CI
checks that. The failure mode is not hypothetical: a required context with no workflow left to
satisfy it strands every PR forever (mergeStateStatus never leaves BLOCKED, and there is no
error anywhere pointing at why — CLAUDE.md documents the adjacent shape, "a PR that is
conflicted AT CREATION never gets a merge ref", as a class of GitHub-side gap this repo has
been bitten by before). A required context that nothing emits is the same gap from upstream.

WHAT THIS CHECKS
----------------
Every required-status-check context in the `main-protection` ruleset must equal the `name:` of
a job in some `.github/workflows/*.yml` that is (a) tracked in git and (b) triggered on
`pull_request` (a push-only or schedule-only job can never satisfy a PR-blocking context, which
is a stronger and separate defect the same comparison catches for free). Matches on job NAME,
not job id — a required context is the check-run name GitHub reports, which for an ordinary job
equals its `name:` field (falling back to the job id when `name:` is absent, exactly as GitHub
does).

WHAT THIS DELIBERATELY DOES NOT CHECK
--------------------------------------
Whether the job is REACHABLE — that is `check-gate-invocation-reachability.py`'s job, for
`ci.yml` specifically, and a job that exists but sits behind a conditional is a different
(smaller) defect than a job that does not exist anywhere. It also does not verify a workflow
step actually calls a script in `.github/scripts` — that is `check-gate-script-registration.py`.
Three checks, three questions: does the CONTEXT match a JOB (this one), is the JOB reachable
(invocation-reachability), is the SCRIPT invoked (script-registration).

NO SPECIAL PERMISSION IS NEEDED — AND `administration` IS NOT A VALID ONE TO ASK FOR
--------------------------------------------------------------------------------------
The first version of this gate declared `permissions: administration: read` on its job,
reasoning from GitHub's general REST docs that reading a ruleset needs the repository's
"Administration" permission. That broke `ci.yml` outright: `administration` is not one of the
keys the workflow `permissions:` block accepts for the ephemeral `GITHUB_TOKEN` at all (the
valid set is `actions`, `checks`, `contents`, `deployments`, `discussions`, `id-token`,
`issues`, `packages`, `pages`, `pull-requests`, `repository-projects`, `security-events`,
`statuses`, `attestations`, and a few narrower ones — never `administration`). GitHub cannot
parse a workflow declaring an unknown permission key, so the run itself failed with "workflow
file issue" and ZERO jobs — on every push, not just this gate's shard (#4339/ADR-0254's own
follow-up caught this the hard way: it briefly reddened `ci.yml` on an open PR).

The fix is that no permission was ever needed. `GET /repos/{owner}/{repo}/rulesets` and
`GET /repos/{owner}/{repo}/rulesets/{id}` are **world-readable on a public repository** —
verified with a bare unauthenticated `curl`, 200 with no `Authorization` header at all. `gh
api` inside the gate uses whatever ambient `GH_TOKEN` the shard already exports for other
gates; it would work identically with none. Before declaring ANY permission a gate "needs",
check whether the endpoint is public first — a private-repo assumption ported onto a public
one is an easy, and in this case actively CI-breaking, mistake.

WHY THE RULESET IS READ LIVE, NEVER FROM A CHECKED-IN COPY
------------------------------------------------------------
A hand-kept list of required contexts, diffed against the live ruleset only when someone
remembers to, is exactly the "gate whose scope is a hand-kept list reads as passing when the
list is short" trap this repo's CI section names as its most-repeated defect class. The
ruleset is fetched from the API every run.

Usage:  check-ruleset-context-parity.py [--enforce] [--repo OWNER/REPO]
        check-ruleset-context-parity.py --self-test
"""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

WORKFLOWS_DIR = ".github/workflows"


def gh_api(path: str) -> list | dict:
    """`gh api <path>`, returning the parsed JSON.

    Raises `RuntimeError` on any failure — a missing `gh` binary, a network error, an
    unauthenticated token, or a non-JSON response — so the caller's one `except RuntimeError`
    catches every way this can go wrong. A caller that only handled a non-zero exit code
    would let a missing `gh` executable raise a bare `FileNotFoundError` straight out of
    `main()`, which still exits non-zero but as an unhandled traceback rather than the
    `::error::` message an operator can act on.
    """
    try:
        p = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as exc:
        raise RuntimeError(f"could not run `gh api {path}`: {exc}") from exc
    if p.returncode != 0:
        raise RuntimeError(f"gh api {path} failed (rc={p.returncode}): {p.stderr.strip()}")
    try:
        return json.loads(p.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"gh api {path} returned non-JSON: {exc}") from exc


def required_contexts(repo: str) -> list[str]:
    """Every required-status-check context across every ruleset that enforces one.

    A repo can have more than one active ruleset (this one has exactly one,
    `main-protection`, but nothing here assumes that stays true). `enforcement` must be
    `active` — an `evaluate`-mode (dry-run) ruleset's contexts are not actually required and
    must not be treated as if a missing job strands a PR.
    """
    rulesets = gh_api(f"repos/{repo}/rulesets")
    out: list[str] = []
    for summary in rulesets:
        if summary.get("enforcement") != "active":
            continue
        detail = gh_api(f"repos/{repo}/rulesets/{summary['id']}")
        for rule in detail.get("rules", []):
            if rule.get("type") != "required_status_checks":
                continue
            for check in rule.get("parameters", {}).get("required_status_checks", []):
                ctx = check.get("context")
                if ctx:
                    out.append(ctx)
    return out


def pr_triggered_job_names(root: pathlib.Path) -> set[str]:
    """Every job `name:` (or job id, if `name:` is absent) from a `pull_request`-triggered,
    tracked workflow under WORKFLOWS_DIR."""
    names: set[str] = set()
    for path in sorted((root / WORKFLOWS_DIR).glob("*.yml")):
        try:
            doc = yaml.safe_load(gatelib.read_text(path)) or {}
        except yaml.YAMLError:
            continue
        on = doc.get(True, doc.get("on"))
        triggers = on if isinstance(on, (dict, list)) else {}
        has_pr = (
            "pull_request" in triggers
            if isinstance(triggers, (dict, list))
            else False
        )
        if not has_pr:
            continue
        for job_id, job in (doc.get("jobs") or {}).items():
            if not isinstance(job, dict):
                continue
            names.add(str(job.get("name", job_id)))
    return names


def findings(root: pathlib.Path, repo: str) -> tuple[list[str], list[str], int]:
    """Return (missing, unreachable_note, contexts_checked)."""
    contexts = required_contexts(repo)
    jobs = pr_triggered_job_names(root)
    missing = [c for c in contexts if c not in jobs]
    return missing, contexts, len(contexts)


def self_test() -> int:
    import tempfile

    fails = []

    def write_workflow(root: pathlib.Path, name: str, text: str) -> None:
        d = root / WORKFLOWS_DIR
        d.mkdir(parents=True, exist_ok=True)
        (d / name).write_text(text)

    # --- pr_triggered_job_names --------------------------------------------------------
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        write_workflow(root, "a.yml", "on: [pull_request]\njobs:\n  x:\n    name: X job\n")
        write_workflow(root, "b.yml", "on: push\njobs:\n  y:\n    name: Y job\n")
        write_workflow(root, "c.yml", "on:\n  pull_request:\n  push:\njobs:\n  z: {}\n")
        got = pr_triggered_job_names(root)
        want = {"X job", "z"}
        if got != want:
            fails.append(f"pr_triggered_job_names: want {want}, got {got}")

    # --- findings, with gh_api / required_contexts stubbed ------------------------------
    def case(label, contexts, jobs, want_missing):
        orig_rc, orig_jn = globals()["required_contexts"], globals()["pr_triggered_job_names"]
        globals()["required_contexts"] = lambda repo: contexts
        globals()["pr_triggered_job_names"] = lambda root: jobs
        try:
            missing, _, n = findings(pathlib.Path("."), "owner/repo")
        finally:
            globals()["required_contexts"], globals()["pr_triggered_job_names"] = orig_rc, orig_jn
        if sorted(missing) != sorted(want_missing) or n != len(contexts):
            fails.append(f"{label}: want missing={want_missing}, got {missing} (n={n})")

    case("every context has a job — clean", ["Validate manifests", "Gitleaks"],
         {"Validate manifests", "Gitleaks", "OPA policy gate"}, [])
    case("a required context with no job anywhere — the #4339 shape",
         ["Validate manifests", "adr-registry"], {"Validate manifests"}, ["adr-registry"])
    case("no required contexts at all is clean (nothing to violate)", [], set(), [])
    case("a mix of matched and orphaned contexts reports only the orphan",
         ["Gitleaks", "adr-registry", "OPA policy gate"],
         {"Gitleaks", "OPA policy gate"}, ["adr-registry"])
    case("matching is exact — no case-folding or fuzzy match",
         ["gitleaks"], {"Gitleaks"}, ["gitleaks"])

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: ruleset-context-parity is falsifiable (7 cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--repo", default="JiRaska/open-bank-oss")
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root)
    try:
        missing, contexts, n = findings(root, args.repo)
    except RuntimeError as exc:
        # A gate that cannot reach the API must not report a silent pass — a missing `gh`
        # binary, a rate limit, or the API being unreachable is a tool failure, not "no
        # violations found".
        sys.stderr.write(f"::error::ruleset-context-parity: {exc}\n")
        return 1

    gatelib.subjects(n, "required status-check contexts")
    for c in missing:
        print(
            f"::{'error' if args.enforce else 'warning'}::ruleset-context-parity: required "
            f"context '{c}' matches no job name in any pull_request-triggered workflow under "
            f"{WORKFLOWS_DIR}. Every PR is now permanently BLOCKED on a check that can never "
            f"report — fix the ruleset (the workflow was renamed/deleted) or add the job back."
        )
    print(f"ruleset-context-parity: {n} required context(s) checked, {len(missing)} orphaned.")
    return 1 if (missing and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
