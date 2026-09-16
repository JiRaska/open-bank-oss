#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Is the SCHEDULED auto-deploy lane stuck red? The logic behind auto-deploy-red-watch.yml.

WHY THIS EXISTS
---------------
auto-deploy-red-watch.yml (#9130) escalates a stuck auto-deploy pipeline onto one
`fleet-health` issue (marker `<!-- auto-deploy-red -->`) after three consecutive failures.
Measured 2026-09-13: the scheduled lane had been red for 27 hours, ten scheduled runs in a
row, no issue existed, and the watch had run GREEN the whole time. Its population was wrong:
it read the three newest completed `auto-deploy.yml` runs with no filter on the triggering
EVENT, and auto-deploy also runs on `push` (a merge to main deploys the services that merge
touched). Those push runs land between scheduled ticks and are green, legitimately, because
their can-i-deploy selector covers only the services the push changed. The real sequence was

    2271 schedule failure | 2270 schedule failure | 2269 push success   -> stuck = false

A green run about a DIFFERENT subject broke the streak every time. Two things made it worse:
the not-stuck branch actively CLOSED an open issue on any green run, so even a lucky escalation
would have been tidied away by the next push; and the issue text the watch writes describes
exactly this trap ("partial deploys still land, so the fleet looks healthy while blocked
services stay pinned") — the watch was that trap's own victim. Side effect, also real: the
watch used `github.paginate(..., per_page: 10)` over the whole run history, ~228 API calls per
tick to look at three runs, and by 04:25Z that same day it was failing on
`API rate limit exceeded for installation`.

THE RULE this script embodies (rules.yaml: `ci_watches.lane_scoped_population`)
--------------------------------------------------------------------------------
A watch that asserts a verdict about ONE lane of a workflow must draw its population from
that lane only. `auto-deploy.yml` has three lanes — `schedule` (reconcile: every service,
the only lane whose red means "the fleet cannot deploy"), `push` (the changed services) and
`workflow_dispatch` (an operator's explicit refresh). Only the scheduled lane is a verdict on
the fleet, so:

  R1  the population is `event == schedule`, `status == completed`, cancelled runs dropped
      (a concurrency-group supersede is not a verdict, #2185);
  R2  stuck := the THRESHOLD newest scheduled verdicts are all `failure`;
  R3  the issue is closed only by a green SCHEDULED run newer than the streak — never by a
      green push, which is a fact about other services;
  R4  the query is bounded (one page, `per_page` a little above the threshold) — a watch that
      reads the whole history to look at three runs is the rate-limit outage it reports on.

Three lanes
-----------
  --self-test          offline fixtures, including the KNOWN-POSITIVE that was measured live
                       (ten scheduled failures interleaved with green pushes MUST escalate)
                       and the known-negatives. Gate `auto-deploy-red-watch-declaration`.
  --check-declaration  offline: auto-deploy-red-watch.yml invokes THIS script and carries no
                       inline `listWorkflowRuns` — the decision logic must not be re-inlined
                       into a `github-script` block, where no test can reach it.
  --evaluate           online: one `gh api` call, JSON verdict on stdout/--json. Exit 0 = not
                       stuck, 2 = stuck, 1 = the watch itself could not answer (which is a
                       failure of the watch, never a "not stuck").

WHAT THIS CANNOT DO
-------------------
"Three red scheduled runs" is still a proxy for the subject that matters — a SERVICE that is
durably blocked (REGRESSION / UNVERIFIABLE, per classify-can-i-deploy-block.sh). A tick that
happens to pass because the blocked service was not selected that time, or a partial fleet
where only money-path services are pinned, is invisible here. The subject-based watch (a
per-service verdict artifact published by auto-deploy, escalation on "service X durable-blocked
longer than N", closure only on "service X deployed at current main") is tracked as the
follow-up issue named in the PR that introduced this script.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

WORKFLOW = ".github/workflows/auto-deploy-red-watch.yml"
WORKFLOW_ID = "auto-deploy.yml"
LANE = "schedule"
THRESHOLD = 3
# R4: one page, a little above the threshold so a couple of cancelled runs still leave enough
# verdicts. NOT a paginate; NOT the history.
PER_PAGE = 8


# --------------------------------------------------------------------------- pure logic


def lane_verdicts(runs: list[dict], lane: str = LANE) -> list[dict]:
    """R1: the population is the lane's completed, non-cancelled runs, newest first."""
    kept = [
        r for r in runs
        if r.get("event") == lane
        and r.get("status") == "completed"
        and r.get("conclusion") != "cancelled"
    ]
    return sorted(kept, key=lambda r: r.get("created_at", ""), reverse=True)


def evaluate(runs: list[dict], threshold: int = THRESHOLD, lane: str = LANE) -> dict:
    """R2 + R3 over an arbitrary (possibly multi-lane) list of runs.

    Returns a JSON-able verdict. `close_on` is the green SCHEDULED run that licenses closing an
    open issue, or None; a green run from any other lane never appears there (R3).
    """
    verdicts = lane_verdicts(runs, lane)
    recent = verdicts[:threshold]
    stuck = len(recent) >= threshold and all(r.get("conclusion") == "failure" for r in recent)
    # R3: only a green run of THIS lane, and only the newest verdict — a green tick older than
    # the current streak is history, not a licence to close.
    newest = verdicts[0] if verdicts else None
    close_on = newest if (newest and newest.get("conclusion") == "success") else None
    return {
        "lane": lane,
        "threshold": threshold,
        "population": len(verdicts),
        "stuck": stuck,
        "recent": [_slim(r) for r in recent],
        "close_on": _slim(close_on) if close_on else None,
    }


def _slim(r: dict) -> dict:
    return {
        "id": r.get("id"),
        "run_number": r.get("run_number"),
        "event": r.get("event"),
        "conclusion": r.get("conclusion"),
        "created_at": r.get("created_at"),
        "head_sha": r.get("head_sha"),
        "html_url": r.get("html_url"),
    }


# --------------------------------------------------------------------------- online lane


def fetch_runs(repo: str, per_page: int = PER_PAGE) -> list[dict]:
    """One bounded call (R4). `event=` is a server-side filter, so the page IS the lane."""
    path = (
        f"repos/{repo}/actions/workflows/{WORKFLOW_ID}/runs"
        f"?event={LANE}&status=completed&per_page={per_page}"
    )
    out = subprocess.run(
        ["gh", "api", path, "--jq", ".workflow_runs"],
        check=True, capture_output=True, text=True,
    ).stdout
    runs = json.loads(out)
    if not isinstance(runs, list):
        raise RuntimeError(f"unexpected shape from {path}: {type(runs).__name__}")
    return runs


# --------------------------------------------------------------------------- declaration


def check_declaration(root: Path) -> list[str]:
    """The workflow must call THIS script and must not carry the decision inline."""
    wf = root / WORKFLOW
    problems: list[str] = []
    if not wf.exists():
        return [f"{WORKFLOW}: missing"]
    text = wf.read_text()
    code = "\n".join(ln for ln in text.splitlines() if not ln.lstrip().startswith("#"))
    me = Path(__file__).name
    if not re.search(rf"python3\s+\.github/scripts/{re.escape(me)}\s+--evaluate", code):
        problems.append(f"{WORKFLOW}: does not invoke `python3 .github/scripts/{me} --evaluate`")
    if not re.search(rf"python3\s+\.github/scripts/{re.escape(me)}\s+--self-test", code):
        problems.append(f"{WORKFLOW}: does not run `{me} --self-test` before trusting a verdict")
    if "listWorkflowRuns" in code:
        problems.append(
            f"{WORKFLOW}: carries an inline `listWorkflowRuns` — the population decision "
            f"must live in {me}, where --self-test can reach it"
        )
    return problems


# --------------------------------------------------------------------------- self-test


def _run(n: int, event: str, conclusion: str, status: str = "completed") -> dict:
    return {
        "id": 1000 + n, "run_number": n, "event": event, "status": status,
        "conclusion": conclusion, "created_at": f"2026-09-13T{n:02d}:00:00Z",
        "head_sha": f"{n:09x}", "html_url": f"https://example.invalid/runs/{n}",
    }


def self_test() -> int:
    failures: list[str] = []

    def check(label: str, cond: bool) -> None:
        print(("  ok   " if cond else "  FAIL ") + label)
        if not cond:
            failures.append(label)

    # KNOWN-POSITIVE, measured live 2026-09-13: ten scheduled failures, green pushes between
    # them. Under the old population (newest three, any event) this was `stuck = false`.
    seq = []
    n = 23
    for i in range(10):
        seq.append(_run(n, "schedule", "failure")); n -= 1
        if i % 2 == 0:
            seq.append(_run(n, "push", "success")); n -= 1
    v = evaluate(seq)
    check("known-positive: 10 scheduled failures interleaved with green pushes -> stuck", v["stuck"])
    check("known-positive: population counts only the scheduled lane", v["population"] == 10)
    check("known-positive: a green push never licenses closing", v["close_on"] is None)
    check("known-positive: every run in the streak is a scheduled failure",
          all(r["event"] == "schedule" and r["conclusion"] == "failure" for r in v["recent"]))

    # The exact live triple.
    live = [_run(2271, "schedule", "failure"), _run(2270, "schedule", "failure"),
            _run(2269, "push", "success"), _run(2268, "push", "failure"),
            _run(2267, "schedule", "failure")]
    check("live 2271/2270/2269: stuck (the push success is not in the population)",
          evaluate(live)["stuck"])

    # The OLD population, reproduced, must read the same input as not stuck — this is the
    # assertion that the fix is a fix and not a coincidence of the fixture.
    old_recent = [r for r in sorted(live, key=lambda r: r["created_at"], reverse=True)
                  if r["conclusion"] != "cancelled"][:THRESHOLD]
    check("old population (no lane filter) reads the same input as NOT stuck",
          not all(r["conclusion"] == "failure" for r in old_recent))

    # Known-negatives.
    check("two scheduled failures only -> not stuck (below threshold)",
          not evaluate([_run(3, "schedule", "failure"), _run(2, "schedule", "failure")])["stuck"])
    green_newest = [_run(5, "schedule", "success"), _run(4, "schedule", "failure"),
                    _run(3, "schedule", "failure"), _run(2, "schedule", "failure")]
    v = evaluate(green_newest)
    check("newest scheduled run green -> not stuck", not v["stuck"])
    check("newest scheduled run green -> licenses closing", v["close_on"] is not None
          and v["close_on"]["run_number"] == 5)
    v = evaluate([_run(5, "schedule", "failure"), _run(4, "schedule", "success"),
                  _run(3, "schedule", "failure"), _run(2, "schedule", "failure")])
    check("a green tick INSIDE the window breaks the streak", not v["stuck"])
    check("...but an older green does not license closing (newest is red)", v["close_on"] is None)
    v = evaluate([_run(6, "schedule", "cancelled"), _run(5, "schedule", "failure"),
                  _run(4, "schedule", "cancelled"), _run(3, "schedule", "failure"),
                  _run(2, "schedule", "failure")])
    check("cancelled scheduled runs are skipped, not counted as red or green", v["stuck"]
          and v["population"] == 3)
    v = evaluate([_run(6, "schedule", "failure", status="in_progress"),
                  _run(5, "schedule", "failure"), _run(4, "schedule", "failure"),
                  _run(3, "schedule", "failure")])
    check("an in-progress run is not a verdict yet", v["stuck"] and v["population"] == 3)
    check("empty population -> not stuck, nothing to close",
          evaluate([]) == {"lane": LANE, "threshold": THRESHOLD, "population": 0,
                           "stuck": False, "recent": [], "close_on": None})
    v = evaluate([_run(3, "push", "failure"), _run(2, "push", "failure"),
                  _run(1, "push", "failure")])
    check("three red PUSH runs are not a scheduled-lane verdict", not v["stuck"]
          and v["population"] == 0)

    # Declaration lane, held to a known-positive and two sabotages on a synthetic tree.
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        wf = root / WORKFLOW
        wf.parent.mkdir(parents=True)
        me = Path(__file__).name
        good = (f"run: python3 .github/scripts/{me} --self-test\n"
                f"run: python3 .github/scripts/{me} --evaluate --repo x\n")
        wf.write_text(good)
        check("declaration: the committed shape passes", check_declaration(root) == [])
        wf.write_text(good + "script: |\n  github.rest.actions.listWorkflowRuns({})\n")
        check("declaration: re-inlined listWorkflowRuns is rejected",
              any("listWorkflowRuns" in p for p in check_declaration(root)))
        wf.write_text(good + "# github.rest.actions.listWorkflowRuns in a comment is fine\n")
        check("declaration: a commented mention is not an inline decision",
              check_declaration(root) == [])
        wf.write_text(f"run: python3 .github/scripts/{me} --self-test\n")
        check("declaration: a workflow that never evaluates is rejected",
              any("--evaluate" in p for p in check_declaration(root)))

    # The real tree, so the gate is red the moment the workflow drifts.
    real = check_declaration(Path(os.environ.get("GATE_ROOT", ".")))
    check("declaration: the real auto-deploy-red-watch.yml conforms", real == [])
    for p in real:
        print("         " + p)

    if failures:
        print(f"self-test: {len(failures)} case(s) FAILED")
        return 1
    print("self-test: all cases behaved as required")
    return 0


# --------------------------------------------------------------------------- main


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-declaration", action="store_true")
    ap.add_argument("--evaluate", action="store_true")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--json", metavar="PATH", help="also write the verdict here")
    ap.add_argument("--root", default=".")
    a = ap.parse_args()

    if a.self_test:
        return self_test()

    if a.check_declaration:
        problems = check_declaration(Path(a.root))
        print("SUBJECTS=1  # auto-deploy-red-watch.yml")
        for p in problems:
            print("::error::" + p)
        return 1 if problems else 0

    if a.evaluate:
        if not a.repo:
            print("::error::--repo (or GITHUB_REPOSITORY) is required", file=sys.stderr)
            return 1
        try:
            runs = fetch_runs(a.repo)
        except (subprocess.CalledProcessError, ValueError, RuntimeError) as e:
            err = getattr(e, "stderr", "") or str(e)
            print(f"::error::the watch could not read the {LANE} lane of {WORKFLOW_ID}: "
                  f"{err.strip()[:400]}", file=sys.stderr)
            return 1
        v = evaluate(runs)
        text = json.dumps(v, indent=2)
        print(text)
        if a.json:
            Path(a.json).write_text(text)
        return 2 if v["stuck"] else 0

    ap.error("pick one of --self-test / --check-declaration / --evaluate")
    return 2


if __name__ == "__main__":
    sys.exit(main())
