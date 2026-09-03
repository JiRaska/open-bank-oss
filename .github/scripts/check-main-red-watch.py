#!/usr/bin/env python3
"""Nothing watches `main`'s own CI conclusion. This is the thing that watches it (issue #4019).

WHY THIS EXISTS
---------------
A red push-triggered workflow on `main` is addressed to nobody. There is no PR to carry the
red check, no reviewer to see it, and no notification: the run simply sits in the Actions tab
with a red dot, and the next PR opened against that commit INHERITS a failure it did not
cause. On 2026-08-07/08 `main` was red four separate times, in three independent ways --

    Services CI   6b3594b2b  build (openbank-engagement-service)   SurfaceRestContractIT
    Services CI   98841b41d  build (openbank-mcp-service)
    CI            9240b133b  Admin UI build / Test (unit + integration)
    CI            b7851d689  Admin UI build / Test (unit + integration)
    Security scan 75e94620c  Trivy fs scan (nanoid 3.3.16, CVE-2026-67213 HIGH)
    Security scan f6efd4d31  Trivy fs scan (same)

-- and every one of them was found because a human happened to sweep the Actions tab. Two PRs
merged onto that red `main` in the meantime.

Before this, `origin/main` contained exactly ONE `workflow_run` listener,
`auto-retry-cancelled.yml`, which re-runs SPOT-KILLED runs and escalates nothing; and no
scheduled job reading a CI conclusion for `main`. The near-miss worth naming is
`deploy-drift-watch.yml`, whose `name:` is literally "Deployed == main watch" -- it compares the
DEPLOYED IMAGE against `main` and never inspects whether `main`'s own CI passed. To that watcher
a red commit that deployed successfully reads as perfectly in sync.

TWO LANES, deliberately different (the external-feed-watch / deploy-drift-watch shape)
--------------------------------------------------------------------------------------
DECLARATION (`--check-declaration`) is a property of the committed workflow files: deterministic,
offline, ~0.1 s, and BLOCKING. Its binding copy is the `main-red-watch-declaration` gate in
.github/gates/gates.yaml, which runs unconditionally on every PR. It exists because the watch's
coverage set is a hand-written `workflows:` list inside a `workflow_run` trigger, and this repo
has already been bitten by a gate whose scope is a hand-kept list of the thing it checks --
`pact-drift-check.yml` was green about work it never did for every module missing from its list.
Without this half, adding a new push-on-main workflow would silently create a lane whose red
nobody is told about, while the repo reports green.

VERDICT (`--inspect-run`) is a property of one completed Actions run. It never gates a merge --
`main` being red is not a defect in the PR at hand -- it ESCALATES: one open `main-red` issue per
watched workflow, refreshed in place, reopened if hand-closed while `main` is still red, and
closed by the watch itself when that workflow next goes green on `main`.

THE RULES the declaration half enforces
---------------------------------------
  R1  Every workflow triggered by `push:` on `main` with NO `paths:` filter MUST appear in
      main-red-watch.yml's `on.workflow_run.workflows` list. These run on EVERY commit to main,
      so their conclusion IS main's health, and an unwatched one is a blind lane.
  R2  A `paths:`-filtered push-on-main workflow must be either watched or listed in NOT_WATCHED
      below with a reason. It does not run on every commit, so its silence is not a signal --
      but that has to be a decision someone wrote down, not an omission.
  R3  main-red-watch.yml must filter on `head_branch == 'main'`. Without it the watch fires on
      every PR run of every watched workflow and opens issues about branches.
  R4  main-red-watch.yml must query the ATTEMPT-SCOPED jobs endpoint. `/actions/runs/<id>/jobs`
      returns the LATEST attempt, so once anybody hand-re-runs a run the watcher silently
      answers about a different attempt than the event fired for -- reporting green on a red
      event, with no error. R4 greps for `/attempts/` and rejects a bare `/jobs` fetch.

WHAT THIS CANNOT DO -- read before treating it as coverage
----------------------------------------------------------
It observes CONCLUSIONS, not correctness. A workflow that passes while checking nothing is
invisible here, and a gate absent from a run is not a gate that failed (`all-green` is not
merge-readiness). It also cannot see a run that was never created: a workflow whose YAML GitHub
could not parse produces a run with zero jobs, which this classifies as unanswerable (exit 1)
rather than green, deliberately.

Why step conclusions and not the log: a job log CONTAINS the step's own `run:` script, so
grepping it matches strings that never executed. `classify_run` is given job dicts and reads
`.steps[].conclusion` -- structured data that the script listing cannot spoof. The self-test
feeds it a job carrying a log body full of failure prose and all-success steps, and requires the
verdict to be green.

EXIT CODES (--inspect-run)
    0  the run is green (or not this watch's business: cancelled / skipped)
    1  the watch could not answer -- no jobs, bad attempt, API failure. A FAILURE OF THIS GATE,
       never a "main is green" verdict.
    2  the run is RED -- escalate.

Run standalone:
    .github/scripts/check-main-red-watch.py --check-declaration
    .github/scripts/check-main-red-watch.py --inspect-run --run-id 31191031034 --attempt 1
Self-test:
    .github/scripts/check-main-red-watch.py --self-test
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
WORKFLOW_DIR = ".github/workflows"
WATCH_WORKFLOW = ".github/workflows/main-red-watch.yml"

# R2 exclusions. Each needs a reason, and the reason has to survive being read out loud: every
# entry here is a lane whose red nobody will be told about.
NOT_WATCHED: dict[str, str] = {
    "Auto deploy": (
        "Owns its own escalation. It reconciles on a 3-hourly cron and a transient cluster or "
        "registry failure is not a defect in main's source tree, so watching it would make this "
        "issue label mostly noise -- which trains people to filter it, the #3891 failure mode."
    ),
    "Admin-UI deploy": (
        "Deploy lane, not a verdict on main's tree. Its drift is already escalated by "
        "deploy-drift-watch.yml against the committed image pin."
    ),
    "Platform OpenTofu": (
        "Infrastructure apply against live AWS. A red here is an operational event with its own "
        "owner and its own state; an issue opened by this watch would duplicate that without "
        "adding the one thing this watch provides (telling someone main's TREE is broken)."
    ),
    "Substrate OpenTofu": "Same as Platform OpenTofu -- infrastructure apply, not a tree verdict.",
    "OpenSSF Scorecard": (
        "Third-party analysis published to the OSSF API. Its red is dominated by upstream rate "
        "limits and API conditions rather than by anything in main's tree, and its verdict is "
        "already visible continuously as the README badge -- the one lane here that is NOT "
        "invisible when it goes red."
    ),
    "Runner image": (
        "Builds the self-hosted runner image on a path filter. A red strands the image at its "
        "previous tag, which is degraded-but-working, and the next PR to touch the path reports "
        "it on a PR check where someone is already looking."
    ),
}

# Conclusions that are not this watch's business. `cancelled` belongs to auto-retry-cancelled.yml
# (a spot-killed runner is not a broken main); `skipped` and `neutral` are not failures. Anything
# NOT in this set and not `success` is treated as red -- `timed_out`, `failure`, `action_required`
# and `startup_failure` all mean the same thing to a person reading main.
NOT_A_VERDICT = {"cancelled", "skipped", "neutral"}


# --------------------------------------------------------------------------------------------
# Declaration half (offline, blocking)
# --------------------------------------------------------------------------------------------


def load_workflows(root: Path) -> list[dict]:
    """(filename, name, push-branches, has-paths-filter) for every parseable workflow."""
    out = []
    wf_dir = root / WORKFLOW_DIR
    for path in sorted(wf_dir.glob("*.yml")) + sorted(wf_dir.glob("*.yaml")):
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:  # a workflow this cannot parse is a finding, not a skip
            out.append({"file": path.name, "name": None, "error": str(exc)})
            continue
        if not isinstance(doc, dict):
            continue
        # PyYAML resolves a bare `on:` key to the boolean True (YAML 1.1 truthiness).
        on = doc.get("on", doc.get(True))
        push = on.get("push") if isinstance(on, dict) else None
        if push is None:
            continue
        push = push if isinstance(push, dict) else {}
        branches = push.get("branches")
        on_main = branches is None or "main" in branches or "**" in branches
        if not on_main:
            continue
        out.append(
            {
                "file": path.name,
                "name": doc.get("name"),
                "paths_filtered": bool(push.get("paths") or push.get("paths-ignore")),
                "error": None,
            }
        )
    return out


def watched_set(root: Path) -> tuple[set[str], str]:
    """The `workflows:` list from main-red-watch.yml's workflow_run trigger, plus its raw text."""
    path = root / WATCH_WORKFLOW
    if not path.exists():
        return set(), ""
    raw = path.read_text(encoding="utf-8")
    doc = yaml.safe_load(raw)
    on = doc.get("on", doc.get(True)) if isinstance(doc, dict) else None
    wr = on.get("workflow_run") if isinstance(on, dict) else None
    names = (wr or {}).get("workflows") or []
    return set(names), raw


def check_declaration(root: Path, workflows=None, watched=None, raw=None) -> list[str]:
    """R1-R4. Returns a list of finding strings; empty means clean."""
    workflows = load_workflows(root) if workflows is None else workflows
    if watched is None or raw is None:
        watched, raw = watched_set(root)
    findings: list[str] = []

    if not watched:
        findings.append(
            f"{WATCH_WORKFLOW} declares no `on.workflow_run.workflows` -- the watch covers nothing."
        )

    for wf in workflows:
        if wf.get("error"):
            findings.append(f"{wf['file']}: unparseable ({wf['error'].splitlines()[0]})")
            continue
        name = wf["name"]
        if not name:
            findings.append(f"{wf['file']}: push-on-main workflow with no `name:` -- unwatchable.")
            continue
        if name in watched:
            if name in NOT_WATCHED:
                findings.append(
                    f"{name!r} is BOTH in the watch list and in NOT_WATCHED -- pick one."
                )
            continue
        if name in NOT_WATCHED:
            if not wf["paths_filtered"]:
                findings.append(
                    f"R1 {name!r} ({wf['file']}) runs on EVERY push to main, so its red IS main "
                    f"red -- it cannot be excluded via NOT_WATCHED. Add it to {WATCH_WORKFLOW}."
                )
            continue
        rule = "R1" if not wf["paths_filtered"] else "R2"
        findings.append(
            f"{rule} {name!r} ({wf['file']}) pushes to main but is neither in "
            f"{WATCH_WORKFLOW}'s `workflows:` list nor declared in NOT_WATCHED with a reason."
        )

    # A NOT_WATCHED entry for a workflow that no longer pushes to main is stale scope: it reads
    # as a considered exclusion and covers nothing. Fails in that direction too, deliberately.
    live_names = {w["name"] for w in workflows if w.get("name")}
    for name in NOT_WATCHED:
        if name not in live_names:
            findings.append(
                f"NOT_WATCHED declares {name!r}, which is not a push-on-main workflow -- "
                f"stale exclusion, delete it."
            )

    if raw:
        findings.extend(check_head_branch_filter(raw))
    findings.extend(check_attempt_scoped_fetch())
    return findings


# --------------------------------------------------------------------------------------------
# Verdict half (online)
# --------------------------------------------------------------------------------------------


def gh_api(path: str) -> object:
    proc = subprocess.run(
        ["gh", "api", "--paginate", path], capture_output=True, text=True, check=False
    )
    if proc.returncode != 0:
        raise RuntimeError(f"gh api {path} failed: {proc.stderr.strip()[:400]}")
    # --paginate concatenates JSON documents when the response is an object; take them all.
    text = proc.stdout.strip()
    docs = []
    dec = json.JSONDecoder()
    idx = 0
    while idx < len(text):
        obj, end = dec.raw_decode(text, idx)
        docs.append(obj)
        idx = end
        while idx < len(text) and text[idx] in " \n\r\t":
            idx += 1
    return docs


def fetch_jobs_scoped(repo: str, run_id: int, attempt: int) -> list[dict]:
    """R4 lives here: the URL is attempt-scoped, always. Never `/runs/<id>/jobs`."""
    docs = gh_api(f"repos/{repo}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100")
    jobs: list[dict] = []
    for doc in docs:
        jobs.extend(doc.get("jobs", []) if isinstance(doc, dict) else [])
    return jobs


def check_head_branch_filter(raw: str) -> list[str]:
    """R3, against the PARSED `if:` expressions rather than the file's text.

    `head_branch` genuinely belongs in the yml -- unlike R4's endpoint, which lives in Python --
    so the fix here is not to look somewhere else, it is to stop looking at the whole file. A
    `"head_branch" not in raw` test is satisfied by any comment that happens to name the field,
    and this file's header discusses it at length; R4 shipped exactly that defect. Reading
    `jobs.*.if` asks the construct instead, and the YAML parser has already dropped comments, so
    prose cannot answer for behaviour in either direction.
    """
    try:
        doc = yaml.safe_load(raw) or {}
    except yaml.YAMLError as exc:
        return [f"R3 {WATCH_WORKFLOW} does not parse: {exc}"]
    jobs = doc.get("jobs") if isinstance(doc, dict) else None
    if not isinstance(jobs, dict) or not jobs:
        return [f"R3 {WATCH_WORKFLOW} declares no jobs -- nothing filters anything."]

    conditions = [str(j.get("if", "")) for j in jobs.values() if isinstance(j, dict)]
    if any("head_branch" in c for c in conditions):
        return []
    return [
        f"R3 no job in {WATCH_WORKFLOW} filters on `head_branch` in its `if:` -- the watch would "
        f"fire on every PR run of every watched workflow. Job conditions seen: "
        f"{[c for c in conditions if c] or 'none'}."
    ]


def check_attempt_scoped_fetch() -> list[str]:
    """R4, by EXERCISE rather than by grep: call the real fetch and read the URL it builds.

    The previous form searched main-red-watch.yml for the literal `/attempts/`. That string lives
    in the yml only as PROSE -- the request is built here, in Python -- so the rule was satisfied
    by the comment describing the endpoint and would have passed with the call itself unscoped.
    Measured before this change: breaking `fetch_jobs_scoped` to `/runs/<id>/jobs` while leaving
    the comment alone left both `--check-declaration` and `--self-test` at exit 0, while deleting
    only the comment reddened them. Exactly backwards.

    Grepping this file instead would be the same defect one file over. So: stub `gh_api`, invoke
    the real function, and assert on the URL it actually asks for. Offline -- nothing is sent.
    """
    seen: list[str] = []

    def _record(path: str) -> object:
        seen.append(path)
        return []

    global gh_api
    original = gh_api
    try:
        gh_api = _record  # type: ignore[assignment]
        fetch_jobs_scoped("o/r", 1, 1)
    except Exception as exc:  # noqa: BLE001 - a fetch that cannot run is itself the finding
        return [f"R4 fetch_jobs_scoped raised {type(exc).__name__}: {exc}"]
    finally:
        gh_api = original  # type: ignore[assignment]

    if not seen:
        return ["R4 fetch_jobs_scoped issued no request -- it cannot be attempt-scoped."]
    bad = [u for u in seen if "/attempts/" not in u or re.search(r"/runs/\d+/jobs", u)]
    if bad:
        return [
            f"R4 fetch_jobs_scoped does not query the attempt-scoped jobs endpoint (asked for "
            f"{bad[0]!r}). `/actions/runs/<id>/jobs` returns the LATEST attempt, so a hand-re-run "
            f"makes the watch answer about a different attempt than the event fired for."
        ]
    return []


def classify_run(run: dict, jobs: list[dict]) -> dict:
    """Pure. `run` is the workflow_run payload; `jobs` come from the attempt-scoped endpoint.

    Reads `.steps[].conclusion` -- structured data. It is given no log and cannot read one, which
    is what makes the "grep matched the step's own `run:` script" failure unreachable here.
    """
    conclusion = (run.get("conclusion") or "").lower()
    verdict = {
        "run_id": run.get("id"),
        "attempt": run.get("run_attempt"),
        "workflow": run.get("name"),
        "head_sha": run.get("head_sha"),
        "head_branch": run.get("head_branch"),
        "html_url": run.get("html_url"),
        "conclusion": conclusion,
        "failing": [],
        "status": None,
    }

    if conclusion in NOT_A_VERDICT:
        verdict["status"] = "not-a-verdict"
        return verdict

    if not jobs:
        # Zero jobs on a completed run is not green. It is what an unparseable workflow, a
        # concurrency-group that stopped the job being created, or a permissions failure all
        # look like -- each of which this must NOT report as healthy.
        verdict["status"] = "unanswerable"
        verdict["reason"] = (
            "the attempt-scoped jobs endpoint returned no jobs for this run; a completed run "
            "with zero jobs is unanswerable, not green"
        )
        return verdict

    for job in sorted(jobs, key=lambda j: (j.get("started_at") or "", j.get("id") or 0)):
        if (job.get("conclusion") or "").lower() not in ("failure", "timed_out"):
            continue
        steps = [
            s.get("name")
            for s in (job.get("steps") or [])
            if (s.get("conclusion") or "").lower() in ("failure", "timed_out")
        ]
        verdict["failing"].append(
            {
                "job": job.get("name"),
                "job_id": job.get("id"),
                "job_url": job.get("html_url"),
                "conclusion": (job.get("conclusion") or "").lower(),
                "steps": steps,
            }
        )

    if verdict["failing"]:
        verdict["status"] = "red"
    elif conclusion == "success":
        verdict["status"] = "green"
    else:
        # The run says failure but no job does. Real (a required job was skipped, a matrix leg
        # never started) and NOT green -- say so rather than picking the convenient answer.
        verdict["status"] = "unanswerable"
        verdict["reason"] = (
            f"run conclusion is {conclusion!r} but no job in attempt "
            f"{run.get('run_attempt')} reports a failure"
        )
    return verdict


def render(verdict: dict) -> str:
    lines = []
    first = verdict["failing"][0] if verdict["failing"] else None
    head = (verdict.get("head_sha") or "")[:9]
    if verdict["status"] == "red":
        lines.append(
            f"main is RED: {verdict['workflow']} @ {head} "
            f"(attempt {verdict['attempt']}) -- {len(verdict['failing'])} failing job(s)."
        )
        gate = first["job"] if not first["steps"] else f"{first['job']} / {first['steps'][0]}"
        lines.append(f"  first failing gate: {gate}")
        for f in verdict["failing"]:
            steps = ", ".join(f["steps"]) or "(no step-level failure reported)"
            lines.append(f"  - {f['job']} [{f['conclusion']}]: {steps}")
    elif verdict["status"] == "green":
        lines.append(f"main is green: {verdict['workflow']} @ {head} (attempt {verdict['attempt']}).")
    elif verdict["status"] == "not-a-verdict":
        lines.append(
            f"{verdict['workflow']} @ {head} concluded {verdict['conclusion']!r} -- not a verdict "
            f"on main (auto-retry-cancelled.yml owns cancelled runs)."
        )
    else:
        lines.append(
            f"COULD NOT ANSWER for {verdict['workflow']} @ {head}: {verdict.get('reason', '')}"
        )
    lines.append(f"  {verdict.get('html_url') or ''}")
    return "\n".join(lines)


STATUS_EXIT = {"green": 0, "not-a-verdict": 0, "unanswerable": 1, "red": 2}


# --------------------------------------------------------------------------------------------
# Self-test
# --------------------------------------------------------------------------------------------


def _job(name, conclusion, steps, **extra):
    return {
        "name": name,
        "id": extra.get("id", 1),
        "conclusion": conclusion,
        "html_url": "u",
        "started_at": extra.get("started_at", "2026-08-08T00:00:00Z"),
        "steps": [{"name": n, "conclusion": c} for n, c in steps],
        **{k: v for k, v in extra.items() if k not in ("id", "started_at")},
    }


def _run(conclusion="failure", attempt=1):
    return {
        "id": 31191031034,
        "run_attempt": attempt,
        "name": "Services CI",
        "head_sha": "6b3594b2bdeadbeef",
        "head_branch": "main",
        "html_url": "https://example/run",
        "conclusion": conclusion,
    }


def self_test() -> int:
    global gh_api, fetch_jobs_scoped  # R4 is falsified by swapping these
    failures = []

    def check(label, cond):
        if not cond:
            failures.append(label)
        print(f"  [{'ok ' if cond else 'FAIL'}] {label}")

    print("verdict classifier")
    red = classify_run(
        _run(),
        [
            _job("all-green", "failure", [("aggregate", "failure")], id=2, started_at="2026-08-08T01:00:00Z"),
            _job(
                "build (openbank-engagement-service)",
                "failure",
                [("Set up job", "success"), ("Test (unit + integration)", "failure")],
                id=1,
                started_at="2026-08-08T00:00:00Z",
            ),
        ],
    )
    check("a red run classifies red", red["status"] == "red")
    check("exit code for red is 2", STATUS_EXIT[red["status"]] == 2)
    check(
        "the FIRST failing gate is the earliest-started job, not the aggregate that ran last",
        red["failing"][0]["job"] == "build (openbank-engagement-service)",
    )
    check(
        "the failing STEP is named, from step conclusions",
        red["failing"][0]["steps"] == ["Test (unit + integration)"],
    )
    check("the rendered report names the gate", "Test (unit + integration)" in render(red))

    green = classify_run(_run("success"), [_job("build", "success", [("Test", "success")])])
    check("a green run classifies green", green["status"] == "green")
    check("exit code for green is 0", STATUS_EXIT[green["status"]] == 0)
    check("a green run escalates nothing", green["failing"] == [])

    # THE log-grep trap, made unreachable by construction. This job carries a `_log` body full of
    # the exact strings a naive grep would match -- including a step's own `run:` script echoing
    # an error it never emitted -- while every step conclusion is success.
    log_trap = _job(
        "Trivy",
        "success",
        [("Trivy fs scan", "success")],
        _log=(
            '##[group]Run echo "::error::Trivy fs scan found HIGH severity"\n'
            "Total: 1 (HIGH: 1, CRITICAL: 0)\nExited with code exit status 1\n"
        ),
    )
    trap = classify_run(_run("success"), [log_trap])
    check("a job whose LOG is full of failure prose but whose steps are green is NOT red",
          trap["status"] == "green")

    # THE attempt-scoping trap. Attempt 1 (what the event fired for) is red; the LATEST attempt,
    # which /runs/<id>/jobs would return, is green. Classifying the attempt the event named must
    # answer red. This is the case that makes the whole watcher honest.
    attempts = {
        1: [_job("build", "failure", [("Test", "failure")])],
        2: [_job("build", "success", [("Test", "success")])],
    }
    seen_urls = []

    # Drive the REAL fetch_jobs_scoped with `gh_api` stubbed, so the URL under test is the one the
    # function builds -- not one the test wrote itself. The previous form hand-wrote the
    # attempt-scoped URL and then asserted about its own string: symmetric, and therefore green
    # against a fetch that had been changed to the unscoped endpoint.
    def _stub(path):
        seen_urls.append(path)
        attempt = int(re.search(r"/attempts/(\d+)/", path).group(1)) if "/attempts/" in path else 1
        return [{"jobs": attempts[attempt]}]

    _original_gh_api = gh_api
    try:
        gh_api = _stub  # type: ignore[assignment]
        jobs1 = fetch_jobs_scoped("o/r", 1, 1)
        jobs2 = fetch_jobs_scoped("o/r", 1, 2)
    finally:
        gh_api = _original_gh_api  # type: ignore[assignment]

    v1 = classify_run(_run(attempt=1), jobs1)
    v2 = classify_run(_run("success", attempt=2), jobs2)
    check("attempt 1 (the attempt the event fired for) reports RED", v1["status"] == "red")
    check("attempt 2 (the latest) reports green -- the two genuinely differ", v2["status"] == "green")
    check("the fetch URL is attempt-scoped", all("/attempts/" in u for u in seen_urls))
    check(
        "the fetch URL is never the unscoped /runs/<id>/jobs",
        not any(re.search(r"/runs/\d+/jobs", u) for u in seen_urls),
    )

    cancelled = classify_run(_run("cancelled"), [])
    check("a cancelled run is not this watch's business", cancelled["status"] == "not-a-verdict")
    check("...and exits 0", STATUS_EXIT[cancelled["status"]] == 0)

    empty = classify_run(_run("failure"), [])
    check("a completed run with ZERO jobs is unanswerable, never green",
          empty["status"] == "unanswerable")
    check("...and exits 1, distinct from both green (0) and red (2)",
          STATUS_EXIT[empty["status"]] == 1)

    liar = classify_run(_run("failure"), [_job("build", "success", [("Test", "success")])])
    check("run says failure but no job does -> unanswerable, not green",
          liar["status"] == "unanswerable")

    check("a timed_out job counts as red",
          classify_run(_run("failure"), [_job("b", "timed_out", [("T", "timed_out")])])["status"] == "red")

    print("declaration checker")
    wl = {"CI", "Services CI"}
    # Every NOT_WATCHED name must be present, or the stale-exclusion rule below fires and this
    # baseline stops being a baseline. That coupling is the point: the fixture cannot drift away
    # from the real exclusion map without the self-test saying so.
    wf_ok = [
        {"file": "ci.yml", "name": "CI", "paths_filtered": False, "error": None},
        {"file": "services-ci.yml", "name": "Services CI", "paths_filtered": False, "error": None},
    ] + [
        {"file": f"{n.lower().replace(' ', '-')}.yml", "name": n, "paths_filtered": True,
         "error": None}
        for n in NOT_WATCHED
    ]
    # A real (minimal) workflow document, not a string that merely contains the substrings the
    # old rules grepped for. R3 parses this now, so a fixture that is not a workflow would only
    # ever prove that a substring was absent from an arbitrary string.
    raw_ok = (
        "on:\n  workflow_run:\n    workflows: [CI]\n"
        "jobs:\n  watch:\n    if: github.event.workflow_run.head_branch == 'main'\n"
        "    runs-on: ubuntu-latest\n"
    )
    check("a fully declared set is clean", check_declaration(REPO, wf_ok, wl, raw_ok) == [])

    wf_new = wf_ok + [
        {"file": "security.yml", "name": "Security scan", "paths_filtered": False, "error": None}
    ]
    f = check_declaration(REPO, wf_new, wl, raw_ok)
    check("R1: a NEW unconditional push-on-main workflow that nothing watches is FLAGGED",
          any("R1" in x and "Security scan" in x for x in f))

    wf_pf = wf_ok + [
        {"file": "labels.yml", "name": "Label sync", "paths_filtered": True, "error": None}
    ]
    f = check_declaration(REPO, wf_pf, wl, raw_ok)
    check("R2: an undeclared paths-filtered push-on-main workflow is FLAGGED",
          any("R2" in x and "Label sync" in x for x in f))

    # R3 reads jobs.*.if now, so falsify it with a real workflow document rather than a bare
    # string. The old case passed "/attempts/1/jobs" as `raw` -- a value that is not a workflow
    # at all, so it proved only that the substring was missing from an arbitrary string.
    _yml_no_filter = (
        "on:\n  workflow_run:\n    workflows: [CI]\n"
        "jobs:\n  watch:\n    if: github.event.workflow_run.event == 'push'\n"
        "    runs-on: ubuntu-latest\n"
    )
    f = check_declaration(REPO, wf_ok, wl, _yml_no_filter)
    check("R3: a job not filtering on head_branch is FLAGGED", any("R3" in x for x in f))

    _yml_with_filter = _yml_no_filter.replace(
        "if: github.event.workflow_run.event == 'push'",
        "if: github.event.workflow_run.head_branch == 'main'",
    )
    check("R3: a job that does filter on head_branch is clean",
          not any("R3" in x for x in check_declaration(REPO, wf_ok, wl, _yml_with_filter)))

    # Over-block control: prose naming the field must NOT satisfy R3. This is the exact defect
    # R4 shipped, so the guard against it is a test, not a comment.
    _yml_comment_only = _yml_no_filter.replace(
        "jobs:", "# the watch filters on head_branch so PR runs are ignored\njobs:"
    )
    check("R3: a COMMENT naming head_branch does not satisfy the rule",
          any("R3" in x for x in check_declaration(REPO, wf_ok, wl, _yml_comment_only)))
    # R4 is behavioural now, so falsify it by breaking the FETCH, not by handing it a doctored
    # yml. The old case passed a fake `raw` without `/attempts/` -- which no longer proves
    # anything, because the yml never carried the request in the first place.
    _real_fetch = fetch_jobs_scoped

    def _unscoped(repo, run_id, attempt):
        gh_api(f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100")
        return []

    try:
        fetch_jobs_scoped = _unscoped  # type: ignore[assignment]
        f = check_declaration(REPO, wf_ok, wl, raw_ok)
        check("R4: a fetch using the UNSCOPED jobs endpoint is FLAGGED",
              any("R4" in x and "attempt-scoped" in x for x in f))
    finally:
        fetch_jobs_scoped = _real_fetch  # type: ignore[assignment]

    # ...and the real one is NOT flagged, so the rule is not simply always-on.
    check("R4: the real attempt-scoped fetch is clean",
          not any("R4" in x for x in check_declaration(REPO, wf_ok, wl, raw_ok)))

    # Over-block control: R4 must not depend on the yml's prose. Strip every mention of the
    # endpoint from the workflow text and it stays clean, because the code is still correct.
    check("R4: deleting the yml comment does NOT flag a correct fetch",
          not any("R4" in x for x in check_declaration(REPO, wf_ok, wl, "head_branch\n")))
    check("R4 accepts the attempt-scoped form",
          not any("R4" in x for x in check_declaration(REPO, wf_ok, wl, raw_ok)))

    f = check_declaration(REPO, [w for w in wf_ok if w["name"] != "Auto deploy"], wl, raw_ok)
    check("a NOT_WATCHED entry for a workflow that no longer pushes to main is FLAGGED as stale",
          any("stale exclusion" in x for x in f))

    f = check_declaration(REPO, wf_ok, wl | {"Auto deploy"}, raw_ok)
    check("a workflow in BOTH the watch list and NOT_WATCHED is FLAGGED",
          any("BOTH" in x for x in f))

    check("an empty watch list is FLAGGED",
          any("covers nothing" in x for x in check_declaration(REPO, wf_ok, set(), raw_ok)))

    wf_bad = wf_ok + [{"file": "broken.yml", "name": None, "error": "mapping values not allowed"}]
    check("an unparseable workflow is a finding, not a skip",
          any("unparseable" in x for x in check_declaration(REPO, wf_bad, wl, raw_ok)))

    print()
    if failures:
        print(f"SELF-TEST FAILED: {len(failures)} case(s)")
        return 1
    print("self-test: all cases behaved as required")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-declaration", action="store_true")
    ap.add_argument("--inspect-run", action="store_true")
    ap.add_argument("--root", default=str(REPO))
    ap.add_argument("--repo", default=os.environ.get("GH_REPO", "JiRaska/open-bank-oss"))
    ap.add_argument("--run-id", type=int)
    ap.add_argument("--attempt", type=int)
    ap.add_argument("--json", help="write the verdict as JSON here")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if args.check_declaration:
        findings = check_declaration(Path(args.root))
        for f in findings:
            print(f"::error::main-red-watch declaration: {f}")
        if findings:
            print(f"\n{len(findings)} finding(s). See {Path(__file__).name} R1-R4.")
            return 1
        print("main-red-watch: every push-on-main workflow is watched or declared.")
        return 0

    if args.inspect_run:
        if not args.run_id:
            ap.error("--inspect-run needs --run-id")
        try:
            # The unscoped run object is fetched ONLY to derive the attempt, and the caller
            # usually knows it: main-red-watch.yml always passes --attempt from
            # `github.event.workflow_run.run_attempt`. When it does, this call's result was
            # overwritten on the next line and never read — one dead API request on the
            # highest-frequency consumer in the estate. Measured 2026-09-03 with a `gh` shim:
            # 3 requests per --inspect-run, ~129 firings/hour, so ~387 of the 1000/hour
            # INSTALLATION quota. That quota was exhausted at 08:01 the same morning and took
            # Trivy's SARIF upload and dependency-review down fleet-wide, including on main.
            attempt = args.attempt
            if not attempt:
                attempt = gh_api(f"repos/{args.repo}/actions/runs/{args.run_id}")[0].get(
                    "run_attempt") or 1
            # Read the run AT THAT ATTEMPT: the top-level object describes the LATEST attempt, so
            # its `conclusion` can disagree with the attempt the event fired for. This one is
            # load-bearing and stays.
            meta = gh_api(f"repos/{args.repo}/actions/runs/{args.run_id}/attempts/{attempt}")[0]
            jobs = fetch_jobs_scoped(args.repo, args.run_id, attempt)
        except Exception as exc:  # noqa: BLE001 -- any failure here is "could not answer"
            print(f"::error::main-red-watch could not answer: {exc}")
            return 1
        verdict = classify_run(meta, jobs)
        print(render(verdict))
        if args.json:
            Path(args.json).write_text(json.dumps(verdict, indent=2), encoding="utf-8")
        return STATUS_EXIT[verdict["status"]]

    ap.error("pick one of --self-test / --check-declaration / --inspect-run")
    return 2


if __name__ == "__main__":
    sys.exit(main())
