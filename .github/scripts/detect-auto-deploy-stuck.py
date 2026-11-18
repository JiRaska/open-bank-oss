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
  R4  the query is bounded (one unfiltered page), then R1 is applied locally. The filtered
      GitHub index can be stale (#12189); missing or old lane verdicts fail this watch closed.

A SECOND SUBJECT, per service: dropped and not re-pinned (`--dropped-pins`)
----------------------------------------------------------------------------
`deploy-window.py prune` reverts a stale image pin on a deploy PR and records it in one
`<!-- deploy-prune -->` comment on that PR. The prune is safe, but 2026-11-17/18 #16701 lost 10 of
12 pins, domestic-payment's outbox pin among them, and that service stayed undeployed for >12 h
with no signal. A dropped pin is expected to be replaced by the next build of that service on
main, within hours. `dropped_not_repinned` flags a recorded drop older than --hours (default 6)
whose service has no pin other than the one the PR kept, neither on main nor in an open deploy
PR. Reported like the lane check: JSON verdict, exit 0 clean / 2 found / 1 could not read.

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
import importlib.util
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from watch_outcome import emit  # noqa: E402  — CLEAN/FOUND/UNREADABLE, gate watch-outcome-declared

WORKFLOW = ".github/workflows/auto-deploy-red-watch.yml"
WORKFLOW_ID = "auto-deploy.yml"
LANE = "schedule"
THRESHOLD = 3
# R4: bounded UNFILTERED pages. GitHub's filtered workflow-runs index has returned Sept/Oct 2
# results while the unfiltered page contained current runs (#9419), so the lane is selected
# locally. One page used to be enough; measured 2026-11-14 a 100-run page held 72
# `workflow_run`, 22 `push`, 3 dispatch and only 3 scheduled runs, and the old 50-run page held
# ONE — so the watch failed "could not read the scheduled lane" on 20 of 20 ticks while the
# lane it exists to watch was genuinely red. Read up to MAX_PAGES and stop as soon as
# THRESHOLD verdicts are in hand: one call on a normal tick, MAX_PAGES at worst.
PER_PAGE = 100
MAX_PAGES = 4
MAX_PAGE_AGE = timedelta(hours=6)
# GitHub drops and delays scheduled ticks under load: the `23 */3` cron measured 7.6h and
# 9.2h gaps on 2026-11-14. A three-hour bound therefore made the watch unreadable most of
# the day. The bound now only rejects a lane that has produced no verdict for half a day,
# which is itself a fact worth a red run.
MAX_LANE_AGE = timedelta(hours=12)


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


def require_current_population(runs: list[dict], now: datetime) -> None:
    """Do not turn a stale/truncated API page into a false red or false green verdict."""
    if not runs:
        raise RuntimeError("workflow-runs page is empty")

    def age(run: dict) -> timedelta:
        stamp = run.get("created_at")
        if not isinstance(stamp, str):
            raise TypeError("workflow run has no created_at timestamp")
        when = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
        if when.tzinfo is None:
            raise RuntimeError("workflow run has no timezone in created_at")
        return now - when

    if min(age(r) for r in runs) > MAX_PAGE_AGE:
        raise RuntimeError("unfiltered workflow-runs page is stale")
    verdicts = lane_verdicts(runs)
    if len(verdicts) < THRESHOLD:
        raise RuntimeError(f"unfiltered page has only {len(verdicts)} scheduled verdicts; need {THRESHOLD}")
    if age(verdicts[0]) > MAX_LANE_AGE:
        raise RuntimeError("newest completed scheduled verdict is stale")


# --------------------------------------------------------------------------- dropped, not re-pinned

DROP_HOURS = 6
# A drop this old is history: whatever is still unpinned has outlived this check's question.
DROP_MAX_AGE_HOURS = 24 * 7
GITOPS_DIR = "openbank-infra/gitops"
DEPLOY_PR_PREFIX = "chore/gitops-auto-deploy-"
DROPPED_MARKER = "<!-- deploy-prune-dropped -->"


def _deploy_window():
    """deploy-window.py is the single owner of the prune comment format; reuse, never re-parse."""
    spec = importlib.util.spec_from_file_location(
        "deploy_window", Path(__file__).resolve().parent / "deploy-window.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


Pins = dict[str, dict[str, set[str]]]  # service -> gitops file -> tags pinned there


def dropped_not_repinned(drops: list[dict], main_pins: Pins, open_pins: Pins, now: int,
                         hours: float = DROP_HOURS) -> list[dict]:
    """Drops older than `hours` (and younger than DROP_MAX_AGE_HOURS) whose service is still
    pinned where it was and nowhere newer.

    * A service main no longer pins at all was removed: nothing to re-pin, never flagged.
    * Re-pinned means the file the drop was recorded for (any file of the service when the record
      names none) now holds a tag that is neither the one the PR kept nor the one it dropped,
      on main or in an open deploy PR. A stale overlay in another file is not a re-pin, and an
      open PR that carries the dropped tag itself is not a re-pin.
    """
    out = []
    for d in drops:
        age_h = (now - int(d["at"])) / 3600
        if age_h < hours or age_h > DROP_MAX_AGE_HOURS:
            continue
        if d["service"] not in main_pins:
            continue
        ignored = {d["kept"], d["dropped"]}
        repinned = False
        for pins in (main_pins, open_pins):
            by_file = pins.get(d["service"], {})
            files = [d["file"]] if d.get("file") else list(by_file)
            repinned = repinned or any(tag not in ignored for f in files for tag in by_file.get(f, ()))
        if not repinned:
            out.append({**d, "age_hours": round(age_h, 1)})
    return sorted(out, key=lambda d: (d["service"], d["at"]))


def evaluate_dropped(drops: list[dict], main_pins: Pins, open_pins: Pins, now: int,
                     hours: float = DROP_HOURS) -> dict:
    found = dropped_not_repinned(drops, main_pins, open_pins, now, hours)
    return {"subject": "dropped-not-repinned", "hours": hours, "drops_seen": len(drops),
            "stuck": bool(found), "services": found}


def drops_from_comments(number: int, comments: list[dict]) -> list[dict]:
    """Records of the prune comments THE BOT wrote on one PR; anyone else's are forged by default."""
    dw = _deploy_window()
    out: list[dict] = []
    for c in comments:
        if dw.PRUNE_MARKER in (c.get("body") or "") and c.get("login") in dw.BOT_LOGINS:
            out += [dict(r, pr=number) for r in dw.parse_drop_records(c["body"])]
    return out


def _gh_lines(*args: str) -> list[str]:
    out = subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout
    return [ln for ln in out.splitlines() if ln.strip()]


def search_query(repo: str, now: datetime) -> str:
    """PRs whose comments mention the prune heading, updated inside the reporting window. Filtered
    at the source: a `pr list --limit 100` covers hours of this repo's PR traffic, not days."""
    since = (now - timedelta(hours=DROP_MAX_AGE_HOURS)).strftime("%Y-%m-%d")
    return f'repo:{repo} is:pr "Stale image pins dropped" in:comments updated:>={since}'


def fetch_drops(repo: str, now: datetime | None = None) -> list[dict]:
    """Every bot-authored record on a gitops deploy PR matching the search, any state."""
    now = now or datetime.now(timezone.utc)
    items = [json.loads(ln) for ln in _gh_lines(
        "api", "-X", "GET", "search/issues", "-f", f"q={search_query(repo, now)}", "-f", "per_page=100",
        "--paginate", "--jq", ".items[] | {number, title} | tojson")]
    drops: list[dict] = []
    for it in items:
        if not str(it.get("title", "")).startswith("chore(gitops): auto-deploy"):
            continue
        comments = [json.loads(ln) for ln in _gh_lines(
            "api", f"repos/{repo}/issues/{it['number']}/comments", "--paginate",
            "--jq", ".[] | {body, login: .user.login} | tojson")]
        drops += drops_from_comments(it["number"], comments)
    return drops


def fetch_pins(repo: str, root: Path) -> tuple[Pins, Pins]:
    """(main, open deploy PRs): tags pinned per service and per gitops file."""
    dw = _deploy_window()
    main_pins: Pins = {}
    open_pins: Pins = {}

    def add(into: Pins, rel: str, text: str) -> None:
        for m in dw.PIN_RE.finditer(text):
            into.setdefault(m["image"].rsplit("/", 1)[-1], {}).setdefault(rel, set()).add(m["tag"])

    for f in (root / GITOPS_DIR).rglob("*.y*ml"):
        add(main_pins, str(f.relative_to(root)), f.read_text(errors="replace"))
    if not main_pins:
        raise RuntimeError(f"no image pins found under {GITOPS_DIR}: wrong --root?")
    for ln in _gh_lines("pr", "list", "--repo", repo, "--state", "open", "--limit", "100",
                        "--json", "number,headRefName", "--jq",
                        '.[] | select(.headRefName | startswith("' + DEPLOY_PR_PREFIX + '")) | .number'):
        diff = subprocess.run(["gh", "pr", "diff", ln, "--repo", repo],
                              check=True, capture_output=True, text=True).stdout
        cur = None
        for line in diff.splitlines():
            if line.startswith("+++ "):
                cur = line[4:].removeprefix("b/")
            elif cur and line.startswith("+") and not line.startswith("+++"):
                add(open_pins, cur, line[1:])
    return main_pins, open_pins


# --------------------------------------------------------------------------- online lane


def fetch_runs(repo: str, per_page: int = PER_PAGE, max_pages: int = MAX_PAGES) -> list[dict]:
    """Bounded unfiltered pages (R4); stop once THRESHOLD lane verdicts are in hand."""
    runs: list[dict] = []
    seen: set = set()
    for page in range(1, max_pages + 1):
        path = f"repos/{repo}/actions/workflows/{WORKFLOW_ID}/runs?per_page={per_page}&page={page}"
        out = subprocess.run(
            ["gh", "api", path, "--jq", ".workflow_runs"],
            check=True, capture_output=True, text=True,
        ).stdout
        batch = json.loads(out)
        if not isinstance(batch, list):
            raise RuntimeError(f"unexpected shape from {path}: {type(batch).__name__}")
        # A run created between page reads shifts the next page by one: drop the repeat.
        runs.extend(r for r in batch if r.get("id") not in seen)
        seen.update(r.get("id") for r in batch)
        if len(lane_verdicts(runs)) >= THRESHOLD or len(batch) < per_page:
            break
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

    # #12189: GitHub's event/status-filtered index returned old runs while the unfiltered
    # workflow page held current scheduled successes. Mock the HTTP boundary, not just R1.
    from unittest.mock import patch

    now = datetime(2026, 10, 6, 18, tzinfo=timezone.utc)
    current = [_run(8, "schedule", "success"), _run(7, "push", "success"),
               _run(6, "schedule", "success"), _run(5, "schedule", "success")]
    for i, run in enumerate(current):
        run["created_at"] = f"2026-10-06T{17-i:02d}:00:00Z"
    stale = [_run(3, "schedule", "failure"), _run(2, "schedule", "failure"),
             _run(1, "schedule", "failure")]
    for run in stale:
        run["created_at"] = "2026-09-21T18:00:00Z"

    def fake_gh(argv: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
        page = stale if "event=" in argv[2] or "status=" in argv[2] else current
        return subprocess.CompletedProcess(argv, 0, json.dumps(page), "")

    with patch.object(subprocess, "run", side_effect=fake_gh) as gh:
        fetched = fetch_runs("example/repo")
    check("API regression: one bounded unfiltered request, no stale event/status index",
          gh.call_count == 1 and "?per_page=100&page=1" in gh.call_args.args[0][2]
          and "event=" not in gh.call_args.args[0][2]
          and "status=" not in gh.call_args.args[0][2])
    require_current_population(fetched, now)
    check("API regression: current scheduled success closes, stale filtered red does not reopen",
          evaluate(fetched)["close_on"] is not None and not evaluate(fetched)["stuck"]
          and evaluate(stale)["stuck"])

    def rejected(page: list[dict], at: datetime = now) -> bool:
        try:
            require_current_population(page, at)
        except RuntimeError:
            return True
        return False

    check("freshness: stale filtered page cannot escalate or close", rejected(stale))
    check("coverage: fewer than three scheduled verdicts cannot return not-stuck",
          rejected(current[:2]))
    old_lane = [dict(r, created_at="2026-10-05T00:00:00Z")
                if r["event"] == "schedule" else r for r in current]
    check("freshness: fresh push cannot mask stale scheduled lane", rejected(old_lane))
    delayed_tick = [dict(r, created_at="2026-10-06T15:30:00Z")
                    if r["event"] == "schedule" else r for r in current]
    check("freshness: a delayed scheduled tick within three hours remains usable",
          not rejected(delayed_tick))
    missed_two = [dict(r, created_at="2026-10-06T09:36:00Z")
                  if r["event"] == "schedule" else r for r in current]
    check("freshness: GitHub dropping two ticks (measured 7.6h/9.2h gaps) stays readable",
          not rejected(missed_two))
    half_day = [dict(r, created_at="2026-10-06T03:00:00Z")
                if r["event"] == "schedule" else r for r in current]
    check("freshness: a lane silent for over twelve hours is unreadable, not green",
          rejected(half_day))

    # 2026-11-14 live shape: the scheduled lane sits past page 1 behind workflow_run noise.
    noise = [dict(_run(1000 + i, "workflow_run", "skipped"), created_at="2026-10-06T16:00:00Z")
             for i in range(100)]
    deep = [dict(_run(7, "schedule", "failure"), created_at="2026-10-06T15:00:00Z"),
            dict(_run(6, "schedule", "failure"), created_at="2026-10-06T08:00:00Z"),
            dict(_run(5, "schedule", "failure"), created_at="2026-10-06T01:00:00Z")]
    pages = {1: noise, 2: [noise[-1], *deep]}  # page 2 repeats one run: a run landed mid-read

    def paged_gh(argv: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
        n = int(argv[2].rsplit("page=", 1)[1])
        return subprocess.CompletedProcess(argv, 0, json.dumps(pages.get(n, [])), "")

    with patch.object(subprocess, "run", side_effect=paged_gh) as gh:
        fetched = fetch_runs("example/repo")
    check("2026-11-14: lane behind 100 workflow_run runs is read from page 2 and escalates",
          gh.call_count == 2 and not rejected(fetched) and evaluate(fetched)["stuck"]
          and len(fetched) == 103)
    check("2026-11-14 negative: the same history cut to page 1 (the old read) is unreadable",
          rejected(noise))
    at_1650 = datetime(2026, 10, 6, 16, 50, tzinfo=timezone.utc)
    omitted_green = [_run(8, "push", "success"), _run(7, "schedule", "failure"),
                     _run(6, "schedule", "failure"), _run(5, "schedule", "failure")]
    for run, stamp in zip(omitted_green, ["16:40", "12:44", "09:36", "06:50"], strict=True):
        run["created_at"] = f"2026-10-06T{stamp}:00Z"
    # #9419's omission came from the FILTERED index; the unfiltered pages read now carry every
    # run, so a 4h-old newest tick is the newest truth, not a hole. The guard that remains is
    # the twelve-hour bound, held below — the price of tolerating GitHub's dropped ticks.
    check("Oct 6 16:50: a 4h-old scheduled red is readable on an unfiltered page (#9419 trade)",
          evaluate(omitted_green)["stuck"] and not rejected(omitted_green, at_1650))
    check("Oct 6: the same red read 13h later is unreadable, never stuck-by-default",
          rejected(omitted_green, at_1650 + timedelta(hours=13)))
    arrived = _run(9, "schedule", "success")
    arrived["id"] = 37488239667
    arrived["created_at"] = "2026-10-06T15:31:31Z"
    complete_page = [arrived, *omitted_green]
    check("Oct 6 16:50: delayed green tick becomes the current closing verdict",
          not rejected(complete_page, at_1650)
          and not evaluate(complete_page)["stuck"]
          and evaluate(complete_page)["close_on"]["id"] == arrived["id"])

    # ---- dropped, not re-pinned (2026-11-17/18, #16701) ----
    dw = _deploy_window()
    t0 = 1_800_000_000
    reg = "registry.example/openbank"
    stale = [("f.yaml", f"{reg}-domestic-payment", "sandbox-aaaaaaa1", None, "sandbox-bbbbbbb2", None),
             ("g.yaml", f"{reg}-ledger", "sandbox-ccccccc3", None, "sandbox-ddddddd4", None)]
    comment = dw.render_comment(dw.drop_records(stale, "abcd1234", t0), ["openbank-kyc:sandbox-1111111"])
    drops = dw.parse_drop_records(comment)
    check("dropped-pins: the prune comment round-trips through the shared parser",
          [d["service"] for d in drops] == ["openbank-domestic-payment", "openbank-ledger"])
    main_pins = {"openbank-domestic-payment": {"f.yaml": {"sandbox-aaaaaaa1"}},
                 "openbank-ledger": {"g.yaml": {"sandbox-eeeeeee5"}}}   # ledger re-pinned on main
    later = t0 + 7 * 3600
    v = evaluate_dropped(drops, main_pins, {}, later)
    check("dropped-pins: a dropped service with no newer pin after 6h is FOUND",
          v["stuck"] and [d["service"] for d in v["services"]] == ["openbank-domestic-payment"])
    check("dropped-pins: a service re-pinned on main is NOT flagged",
          all(d["service"] != "openbank-ledger" for d in v["services"]))
    open_pr = {"openbank-domestic-payment": {"f.yaml": {"sandbox-fffffff6"}}}
    check("dropped-pins: a newer pin in an open deploy PR is NOT a finding",
          not evaluate_dropped(drops[:1], main_pins, open_pr, later)["stuck"])
    check("dropped-pins: an open PR carrying the DROPPED tag is not a re-pin (red-first: review MEDIUM 4)",
          evaluate_dropped(drops[:1], main_pins, {"openbank-domestic-payment": {"f.yaml": {"sandbox-bbbbbbb2"}}},
                           later)["stuck"])
    overlay = {"openbank-domestic-payment": {"f.yaml": {"sandbox-aaaaaaa1"}, "dr/f.yaml": {"sandbox-0000000"}}}
    check("dropped-pins: a different tag in ANOTHER file is not a re-pin of the dropped file",
          evaluate_dropped(drops[:1], overlay, {}, later)["stuck"])
    check("dropped-pins: a service main no longer pins (removed) is not flagged (review MEDIUM 5)",
          not evaluate_dropped(drops[:1], {"openbank-ledger": {"g.yaml": {"sandbox-eeeeeee5"}}}, {}, later)["stuck"])
    check("dropped-pins: younger than the threshold is not flagged yet",
          not evaluate_dropped(drops, main_pins, {}, t0 + 5 * 3600)["stuck"])
    check("dropped-pins: the threshold is configurable",
          evaluate_dropped(drops, main_pins, {}, t0 + 2 * 3600, hours=1)["stuck"])
    check("dropped-pins: a week-old drop is history, not a finding",
          not evaluate_dropped(drops, main_pins, {}, t0 + (DROP_MAX_AGE_HOURS + 1) * 3600)["stuck"])
    check("dropped-pins: nothing dropped -> clean", not evaluate_dropped([], main_pins, {}, later)["stuck"])

    # Trust: the comment is writable by anyone on a public PR (review HIGH 1/2).
    bot = "openbank-gitops-bot[bot]"
    junk = (dw.PRUNE_MARKER + '<!-- deploy-prune-data [{"service": "openbank-x", "kept": ["a"], '
                         '"dropped": "b", "at": "soon"}, {"service": "@all`", "kept": "a", "dropped": "b", "at": 1}] -->')
    check("trust: a non-bot prune comment yields no drops",
          drops_from_comments(9, [{"body": comment, "login": "mallory"}]) == [])
    check("trust: a bot comment yields its drops, tagged with the PR",
          [d["pr"] for d in drops_from_comments(9, [{"body": comment, "login": bot}])] == [9, 9])
    check("trust: malformed bot-authored records neither raise nor flow through",
          drops_from_comments(9, [{"body": junk, "login": bot}]) == [])
    check("trust: a forged non-int `at` cannot reach int() and crash the watch",
          not evaluate_dropped(dw.parse_drop_records(junk), main_pins, {}, later)["stuck"])

    # Source-side filtering and pagination (review HIGH 3): mock the HTTP boundary.
    from unittest.mock import patch
    now_dt = datetime(2026, 11, 18, 12, tzinfo=timezone.utc)
    calls: list[list[str]] = []

    def fake_lines(*args: str) -> list[str]:
        calls.append(list(args))
        if list(args[1:4]) == ["-X", "GET", "search/issues"]:
            return [json.dumps({"number": 9, "title": "chore(gitops): auto-deploy services @ abc"}),
                    json.dumps({"number": 10, "title": "feat: unrelated"})]
        return [json.dumps({"body": comment, "login": bot}), json.dumps({"body": comment, "login": "mallory"})]

    with patch.object(sys.modules[__name__], "_gh_lines", side_effect=fake_lines):
        got = fetch_drops("o/r", now_dt)
    q = next(c for c in calls if "search/issues" in c)
    check("fetch: the query filters at the source, covers the 7-day window and paginates",
          any("in:comments" in x and "updated:>=2026-11-11" in x for x in q) and "--paginate" in q)
    check("fetch: only the matching deploy PR's comments are read, and only the bot's are trusted",
          len(got) == 2 and all(d["pr"] == 9 for d in got)
          and not any("issues/10/" in " ".join(c) for c in calls))

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
    ap.add_argument("--dropped-pins", action="store_true",
                    help="report services whose pin a prune dropped and nothing re-pinned")
    ap.add_argument("--hours", type=float, default=DROP_HOURS)
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
            require_current_population(runs, datetime.now(timezone.utc))
        except (subprocess.CalledProcessError, TypeError, ValueError, RuntimeError) as e:
            err = getattr(e, "stderr", "") or str(e)
            print(f"::error::the watch could not read the {LANE} lane of {WORKFLOW_ID}: "
                  f"{err.strip()[:400]}", file=sys.stderr)
            emit("UNREADABLE", f"{LANE} lane of {WORKFLOW_ID}: {err.strip()[:200]}")
            return 1
        v = evaluate(runs)
        text = json.dumps(v, indent=2)
        print(text)
        if a.json:
            Path(a.json).write_text(text)
        emit("FOUND" if v["stuck"] else "CLEAN",
             f"{WORKFLOW_ID} {LANE} lane: {v['population']} verdicts, stuck={v['stuck']}")
        return 2 if v["stuck"] else 0

    if a.dropped_pins:
        if not a.repo:
            print("::error::--repo (or GITHUB_REPOSITORY) is required", file=sys.stderr)
            return 1
        try:
            main_pins, open_pins = fetch_pins(a.repo, Path(a.root))
            v = evaluate_dropped(fetch_drops(a.repo), main_pins, open_pins,
                                 int(datetime.now(timezone.utc).timestamp()), a.hours)
        except (subprocess.CalledProcessError, TypeError, ValueError, RuntimeError, OSError) as e:
            err = getattr(e, "stderr", "") or str(e)
            print(f"::error::could not read dropped pins: {err.strip()[:400]}", file=sys.stderr)
            emit("UNREADABLE", f"dropped pins: {err.strip()[:200]}")
            return 1
        text = json.dumps(v, indent=2)
        print(text)
        if a.json:
            Path(a.json).write_text(text)
        emit("FOUND" if v["stuck"] else "CLEAN",
             f"dropped-not-repinned: {len(v['services'])} service(s) of {v['drops_seen']} drop(s)")
        return 2 if v["stuck"] else 0

    ap.error("pick one of --self-test / --check-declaration / --evaluate / --dropped-pins")
    return 2


if __name__ == "__main__":
    sys.exit(main())
