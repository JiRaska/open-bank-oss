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


class Unreachable(RuntimeError):
    """The rulesets API could not be READ. Not a finding — a third state.

    "the ruleset requires X and no job emits X" and "I could not reach the rulesets API"
    are different facts and must not render as the same verdict. On 2026-08-21 ~18:25 UTC a
    shared installation rate limit hit two gates in one run: `check-stale-comment-references.py`
    printed `::notice:: ... UNRESOLVED ... Not a pass and not a failure` and stayed green, while
    this gate exited 1 and turned PR #5896 red on a diff that touches no workflow and no
    ruleset. This class carries the transient half so `main()` can degrade the same way.

    RATE LIMIT vs GENUINE PERMISSION DENIAL — distinguishable, because `gh` prints the reason.
    A quota exhaustion says `API rate limit exceeded` (HTTP 403 with `x-ratelimit-remaining: 0`)
    and a permission denial says `Resource not accessible by integration` / `Must have admin
    rights` (HTTP 403 with quota left). Only the transient family degrades to UNRESOLVED; a
    permission denial stays a hard error, because it is a persistent misconfiguration of the
    gate itself that no amount of waiting fixes, and silently downgrading it would leave the
    gate permanently unresolved with nothing to notice.
    """


TRANSIENT_MARKERS = (
    "api rate limit exceeded",
    "secondary rate limit",
    "you have exceeded a secondary rate limit",
    "was submitted too quickly",
    "connection refused",
    "could not resolve host",
    "network is unreachable",
    "timeout",
    "timed out",
    "eof",
    "bad gateway",
    "service unavailable",
    "server error",
    "(http 429)",
    "(http 500)",
    "(http 502)",
    "(http 503)",
    "(http 504)",
)


def is_transient(message: str) -> bool:
    """True when a `gh api` failure is about REACHABILITY, not about the ruleset's content."""
    return any(m in message.lower() for m in TRANSIENT_MARKERS)


def gh_api(path: str) -> list | dict:
    """`gh api <path>`, returning the parsed JSON.

    Raises on any failure, in one of two flavours the caller renders differently:
    `Unreachable` (a subclass) when nothing could be READ — missing `gh`, network error,
    timeout, rate limit, 5xx — and plain `RuntimeError` for everything else, such as a
    permission denial or a non-JSON response. A caller that only handled a non-zero exit
    code would let a missing `gh` executable raise a bare `FileNotFoundError` straight out
    of `main()`, which still exits non-zero but as an unhandled traceback rather than a
    message an operator can act on.
    """
    try:
        p = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=30)
    except subprocess.TimeoutExpired as exc:
        raise Unreachable(f"`gh api {path}` timed out: {exc}") from exc
    except (OSError, subprocess.SubprocessError) as exc:
        # A missing `gh` binary is not a defect in the ruleset either — nothing was read.
        raise Unreachable(f"could not run `gh api {path}`: {exc}") from exc
    if p.returncode != 0:
        err = p.stderr.strip()
        msg = f"gh api {path} failed (rc={p.returncode}): {err}"
        raise (Unreachable if is_transient(err) else RuntimeError)(msg)
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


def job_names_for_event(root: pathlib.Path, event: str = "pull_request") -> set[str]:
    """Every job `name:` (or job id, if `name:` is absent) from a tracked workflow under
    WORKFLOWS_DIR that triggers on `event`."""
    names: set[str] = set()
    for path in sorted((root / WORKFLOWS_DIR).glob("*.yml")):
        try:
            doc = yaml.safe_load(gatelib.read_text(path)) or {}
        except yaml.YAMLError:
            continue
        on = doc.get(True, doc.get("on"))
        triggers = on if isinstance(on, (dict, list)) else {}
        has_event = (
            event in triggers
            if isinstance(triggers, (dict, list))
            else False
        )
        if not has_event:
            continue
        for job_id, job in (doc.get("jobs") or {}).items():
            if not isinstance(job, dict):
                continue
            names.add(str(job.get("name", job_id)))
    return names


def pr_triggered_job_names(root: pathlib.Path) -> set[str]:
    """Backwards-compatible alias for the pull_request scan."""
    return job_names_for_event(root, "pull_request")


def findings(root: pathlib.Path, repo: str) -> tuple[list[str], list[str], int]:
    """Return (missing, unreachable_note, contexts_checked)."""
    contexts = required_contexts(repo)
    # Via the alias, NOT job_names_for_event directly: self_test() stubs this name, and calling
    # the underlying function instead would leave all 20 stubbed cases asserting nothing while
    # still passing. Found exactly that way when merge_group support was added (ADR-0272).
    jobs = pr_triggered_job_names(root)
    missing = [c for c in contexts if c not in jobs]
    return missing, contexts, len(contexts)


def merge_group_gaps(root: pathlib.Path, contexts: list[str]) -> list[str]:
    """Required contexts that no `merge_group`-triggered workflow can report.

    ADR-0272. A merge queue only merges an entry once every required context reports on the
    `merge_group` event. A context that never reports there does not fail the queue -- it
    STALLS it, forever, with zero failures, which is the same shape this gate already guards
    at the `pull_request` event and the reason it exists at all.

    ALWAYS ADVISORY, on purpose. The queue is not enabled, so a missing `merge_group` trigger
    is a readiness gap and not yet a defect; making it red today would fail every PR for a
    feature nobody has turned on. It becomes enforced at ADR-0272 rollout step 4, in the same
    change that enables the queue -- and the point of reporting it now is that the precondition
    is machine-checked rather than a paragraph in an ADR that nobody re-reads.
    """
    jobs = job_names_for_event(root, "merge_group")
    return [c for c in contexts if c not in jobs]


def self_test() -> int:
    import tempfile

    fails = []
    # Counted, not hard-coded. The message used to claim a literal "20 cases", which was a
    # hand-count that went stale the moment cases were added (ADR-0272 added four) -- the same
    # trap a hard-coded corpus size is: it keeps reporting a full corpus after half of it is
    # gone. Every parameterised case and every scenario block bumps this.
    ran: list[str] = []

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
        ran.append("pr_triggered_job_names")
        got = pr_triggered_job_names(root)
        want = {"X job", "z"}
        if got != want:
            fails.append(f"pr_triggered_job_names: want {want}, got {got}")

    # --- merge_group_gaps (ADR-0272) ---------------------------------------------------
    # Both directions. A readiness check that only ever sees the not-ready case cannot tell a
    # workflow that gained merge_group from one that did not, which is the whole question it
    # will be asked at rollout step 4.
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        write_workflow(root, "ready.yml",
                       "on:\n  pull_request:\n  merge_group:\njobs:\n  a:\n    name: Ready ctx\n")
        write_workflow(root, "notready.yml",
                       "on:\n  pull_request:\njobs:\n  b:\n    name: Stalls ctx\n")
        write_workflow(root, "listform.yml",
                       "on: [pull_request, merge_group]\njobs:\n  c:\n    name: List form ctx\n")
        ran += ["merge_group: ready", "merge_group: stalls", "merge_group: list form",
                "merge_group: merge_group-only counts as ready"]
        got = merge_group_gaps(root, ["Ready ctx", "Stalls ctx", "List form ctx"])
        if got != ["Stalls ctx"]:
            fails.append(f"merge_group_gaps: want ['Stalls ctx'], got {got}")
        if merge_group_gaps(root, []) != []:
            fails.append("merge_group_gaps: no contexts must yield no gaps")
        # A workflow with merge_group but NOT pull_request is still merge_group-ready: the two
        # questions are independent, and conflating them would hide a real readiness gap.
        write_workflow(root, "mgonly.yml",
                       "on:\n  merge_group:\njobs:\n  e:\n    name: MG only\n")
        if merge_group_gaps(root, ["MG only"]) != []:
            fails.append("merge_group_gaps: a merge_group-only workflow must count as ready")

    # --- findings, with gh_api / required_contexts stubbed ------------------------------
    def case(label, contexts, jobs, want_missing):
        ran.append(label)
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

    # --- the THIRD STATE: an unreachable API is not a failure, a mismatch still is --------
    # Both directions, because either one alone is vacuous: a gate that never fails proves
    # nothing about rate limits, and a gate that always fails hides them.
    import contextlib
    import io

    def run_main(argv_extra, raise_exc=None, contexts=None, jobs=frozenset()):
        ran.append("run_main")
        """Drive the real main() end to end, stubbing only the network boundary."""
        orig_rc = globals()["required_contexts"]
        orig_jn = globals()["pr_triggered_job_names"]
        orig_argv = sys.argv

        def stub(repo):
            if raise_exc is not None:
                raise raise_exc
            return list(contexts or [])

        globals()["required_contexts"] = stub
        globals()["pr_triggered_job_names"] = lambda root: set(jobs)
        sys.argv = ["check-ruleset-context-parity.py", *argv_extra]
        out, err = io.StringIO(), io.StringIO()
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                rc = main()
        finally:
            globals()["required_contexts"], globals()["pr_triggered_job_names"] = orig_rc, orig_jn
            sys.argv = orig_argv
        return rc, out.getvalue() + err.getvalue()

    rate_limited = Unreachable(
        "gh api repos/o/r/rulesets failed (rc=1): gh: API rate limit exceeded for "
        "installation ... (HTTP 403)"
    )
    rc, out = run_main(["--enforce"], raise_exc=rate_limited)
    if rc != 0 or "::notice::" not in out or "UNRESOLVED" not in out:
        fails.append(f"API rate-limited must be UNRESOLVED, not a failure: rc={rc}, out={out!r}")
    if "::error::" in out:
        fails.append(f"API rate-limited must not emit ::error::: {out!r}")

    rc, out = run_main(
        ["--enforce"],
        contexts=["Validate manifests", "Gitleaks", "OPA policy gate", "adr-registry"],
        jobs={"Validate manifests", "Gitleaks", "OPA policy gate"},
    )
    if rc != 1 or "adr-registry" not in out:
        fails.append(f"a genuine mismatch must STILL fail: rc={rc}, out={out!r}")

    # A NON-transient 403 (a permission denial) is a different fact from a rate limit and
    # stays red — see Unreachable's docstring.
    denied = RuntimeError(
        "gh api repos/o/r/rulesets failed (rc=1): gh: Resource not accessible by "
        "integration (HTTP 403)"
    )
    rc, out = run_main(["--enforce"], raise_exc=denied)
    if rc != 1 or "::error::" not in out:
        fails.append(f"a permission denial must stay a hard failure: rc={rc}, out={out!r}")

    for msg, want in [
        ("gh: API rate limit exceeded for installation ... (HTTP 403)", True),
        ("You have exceeded a secondary rate limit", True),
        ("dial tcp: connection refused", True),
        ("Resource not accessible by integration (HTTP 403)", False),
        ("Must have admin rights to Repository. (HTTP 403)", False),
        ("Not Found (HTTP 404)", False),
    ]:
        if is_transient(msg) is not want:
            fails.append(f"is_transient({msg!r}): want {want}, got {not want}")

    # The corpus floor lives in main(), so an UNRESOLVED run is not failed by it while a
    # ruleset that really lost its protection still is.
    # The UNRESOLVED run must ALSO tell run-gates.py not to apply `min_subjects:` to it —
    # otherwise the manifest floor (3) fails a run that examined nothing by definition, and
    # the third state is undone one layer up. A resolved run still prints a real count.
    _, out = run_main(["--enforce"], raise_exc=rate_limited)
    if "SUBJECTS=UNRESOLVED" not in out:
        fails.append(f"UNRESOLVED run must print SUBJECTS=UNRESOLVED: {out!r}")
    _, out = run_main(["--enforce"], contexts=["Gitleaks"], jobs={"Gitleaks"})
    if "SUBJECTS=1" not in out:
        fails.append(f"a resolved run must print its real subject count: {out!r}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(f"self-test ok: ruleset-context-parity is falsifiable ({len(ran)} cases)")
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
    except Unreachable as exc:
        # THIRD STATE. Not a pass (nothing was compared) and not a failure (the PR's diff
        # cannot cause this). Same shape as check-stale-comment-references.py's repo rule.
        gatelib.subjects_unresolved(f"rulesets API unreachable: {exc}")
        print(
            f"::notice::ruleset-context-parity: UNRESOLVED for {args.repo} — {exc}. "
            f"The rulesets API could not be read, so no context was compared against any "
            f"job. Not a pass and not a failure."
        )
        return 0
    except RuntimeError as exc:
        # A NON-transient failure — a permission denial, an unparseable response — is a real
        # defect in the gate's own wiring and stays red. See Unreachable's docstring for why
        # the two 403s are not the same fact.
        sys.stderr.write(f"::error::ruleset-context-parity: {exc}\n")
        return 1

    # The corpus floor stays in gates.yaml (`min_subjects: 3`) — run-gates.py applies it to a
    # run that printed a count, and skips it for a run that printed UNRESOLVED above.
    gatelib.subjects(n, "required status-check contexts")
    for c in missing:
        print(
            f"::{'error' if args.enforce else 'warning'}::ruleset-context-parity: required "
            f"context '{c}' matches no job name in any pull_request-triggered workflow under "
            f"{WORKFLOWS_DIR}. Every PR is now permanently BLOCKED on a check that can never "
            f"report — fix the ruleset (the workflow was renamed/deleted) or add the job back."
        )
    gaps = merge_group_gaps(root, contexts)
    for c in gaps:
        print(
            f"::warning::ruleset-context-parity: required context '{c}' has no "
            f"merge_group-triggered job. A merge queue would STALL on it (never report, never "
            f"fail). Advisory until ADR-0272 step 4 enables the queue."
        )
    print(
        f"ruleset-context-parity: {n} required context(s) checked, {len(missing)} orphaned, "
        f"{len(gaps)} not merge_group-ready (advisory, ADR-0272)."
    )
    return 1 if (missing and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
