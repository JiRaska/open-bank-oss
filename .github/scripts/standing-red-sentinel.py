#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Standing-red sentinel: any SCHEDULED workflow that stays red gets ONE issue, and loses it when green.

WHY THIS EXISTS
---------------
Measured 2026-11-09..14 (scheduled runs only): `auto-deploy red watch` red 20/20,
`Service build stall watch` red 11/20, `Pact verification reconcile` red on most ticks,
`record-agent-merge` silently failing 77 PRs for a week (#16187). None of it was hidden. Every
red was in the Actions tab, and nobody acted on it. A scheduled workflow has no PR, no reviewer
and no push author, so its red is addressed to nobody. main-red-watch (#4019) covers the PUSH
lane of main. auto-deploy-red-watch covers one workflow. This script covers the scheduled lane
of every workflow, including the watches themselves, which is how a blind watch gets noticed.

RULES
-----
  S1  population per workflow: unfiltered runs page (the filtered index is stale, #9419),
      then `event == schedule`, completed, not cancelled/skipped, newest first.
  S2  standing red := the newest STREAK verdicts all failed (`failure`/`timed_out`),
      or the newest >= 2 failed and the oldest of that streak is >= 24h old (daily jobs).
  S3  ONE issue per workflow, marker `<!-- standing-red:<file> -->`, label `standing-red`.
      Refreshed in place, never stacked. Reopened if hand-closed while still red.
  S4  escalation: an issue open >= 72h while still red gets the `standing-red-escalated`
      label and one escalation comment.
  S5  closed only by a green scheduled verdict newer than the streak. A workflow with no
      readable verdict this tick (no scheduled run on the page) is never closed or opened;
      it counts as SKIPPED.
  S6  workflows with a DEDICATED escalation are excluded, so one red never files two issues.

API cost: one `GET /actions/workflows/<file>/runs?per_page=50` per scheduled workflow
(~60), plus one paginated issue listing. Runs every 3h: ~500 GITHUB_TOKEN calls/day.

  --self-test   offline fixtures; known-positive, known-negatives, issue state machine.
  --evaluate    online: verdict JSON (consumed by the workflow's github-script step).
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from watch_outcome import emit  # noqa: E402

STREAK = 3
DAILY_SPAN = timedelta(hours=24)
ESCALATE_AFTER = timedelta(hours=72)
PER_PAGE = 50
RED = {"failure", "timed_out", "startup_failure"}
IGNORED = {"cancelled", "skipped", "neutral", "stale", None}
# S6: workflow file -> the watch that already escalates its red.
DEDICATED = {
    "auto-deploy.yml": "auto-deploy-red-watch.yml (#9130)",
}


def scheduled_workflows(root: Path) -> list[str]:
    import yaml
    out = []
    for f in sorted((root / ".github/workflows").glob("*.y*ml")):
        try:
            doc = yaml.safe_load(f.read_text()) or {}
        except yaml.YAMLError:
            continue
        on = doc.get("on", doc.get(True))  # PyYAML reads a bare `on:` key as True
        if isinstance(on, dict) and "schedule" in on and f.name not in DEDICATED:
            out.append(f.name)
    return out


def _ts(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def verdict(runs: list[dict], now: datetime) -> dict:
    """S1 + S2 (+ the S5 close licence) for one workflow's runs page."""
    lane = sorted(
        (r for r in runs if r.get("event") == "schedule" and r.get("status") == "completed"
         and r.get("conclusion") not in IGNORED),
        key=lambda r: r["created_at"], reverse=True)
    if not lane:
        return {"state": "unknown", "streak": 0, "runs": []}
    streak = []
    for r in lane:
        if r.get("conclusion") in RED:
            streak.append(r)
        else:
            break
    if not streak:
        return {"state": "green", "streak": 0, "close_on": _slim(lane[0]), "runs": []}
    span = now - _ts(streak[-1]["created_at"])
    red = len(streak) >= STREAK or (len(streak) >= 2 and span >= DAILY_SPAN)
    return {"state": "red" if red else "flaky", "streak": len(streak),
            "since": streak[-1]["created_at"], "runs": [_slim(r) for r in streak[:5]]}


def _slim(r: dict) -> dict:
    return {k: r.get(k) for k in ("id", "created_at", "conclusion", "html_url")}


def plan(verdicts: dict[str, dict], issues: list[dict], now: datetime) -> list[dict]:
    """S3-S5: what to do with the issue tracker, as data (the workflow executes it)."""
    by_file: dict[str, dict] = {}
    for i in sorted(issues, key=lambda i: i["state"] != "open"):  # open wins over closed
        body = i.get("body") or ""
        if "<!-- standing-red:" in body:
            f = body.split("<!-- standing-red:", 1)[1].split(" -->", 1)[0]
            by_file.setdefault(f, i)
    actions = []
    for f, v in sorted(verdicts.items()):
        issue = by_file.get(f)
        if v["state"] == "red":
            if issue is None:
                actions.append({"op": "open", "file": f, "verdict": v})
            elif issue["state"] == "closed":
                actions.append({"op": "reopen", "file": f, "number": issue["number"], "verdict": v})
            else:
                escalate = (now - _ts(issue["created_at"]) >= ESCALATE_AFTER
                            and "standing-red-escalated" not in
                            [lab["name"] if isinstance(lab, dict) else lab
                             for lab in issue.get("labels", [])])
                actions.append({"op": "refresh", "file": f, "number": issue["number"],
                                "verdict": v, "escalate": escalate})
        elif v["state"] == "green" and issue and issue["state"] == "open":
            actions.append({"op": "close", "file": f, "number": issue["number"], "verdict": v})
    return actions


def gh_json(path: str) -> object:
    out = subprocess.run(["gh", "api", path], check=True, capture_output=True, text=True).stdout
    return json.loads(out)


def evaluate(repo: str, root: Path, now: datetime) -> dict:
    verdicts, unreadable = {}, []
    for f in scheduled_workflows(root):
        try:
            doc = gh_json(f"repos/{repo}/actions/workflows/{f}/runs?per_page={PER_PAGE}")
            verdicts[f] = verdict(doc["workflow_runs"], now)
        except subprocess.CalledProcessError as e:
            if "HTTP 404" in (e.stderr or ""):  # file not on the default branch yet: no lane
                verdicts[f] = {"state": "unknown", "streak": 0, "runs": []}
                continue
            unreadable.append(f"{f}: {(e.stderr or str(e)).strip()[:120]}")
        except (KeyError, ValueError, TypeError) as e:
            unreadable.append(f"{f}: {(getattr(e, 'stderr', '') or str(e)).strip()[:120]}")
    issues = []
    for page in range(1, 11):
        batch = gh_json(f"repos/{repo}/issues?labels=standing-red&state=all&per_page=100&page={page}")
        issues.extend(i for i in batch if "pull_request" not in i)
        if len(batch) < 100:
            break
    return {"verdicts": verdicts, "unreadable": unreadable,
            "actions": plan(verdicts, issues, now)}


# --------------------------------------------------------------------------- self-test

def self_test() -> int:
    now = datetime(2026, 11, 14, 21, 0, tzinfo=timezone.utc)
    bad: list[str] = []

    def check(label: str, cond: bool) -> None:
        print(f"  {'ok ' if cond else 'BAD'} {label}")
        if not cond:
            bad.append(label)

    def run(h: float, concl: str, event: str = "schedule", i: int = 0) -> dict:
        return {"id": i or int(h * 10), "event": event, "status": "completed", "conclusion": concl,
                "created_at": (now - timedelta(hours=h)).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "html_url": "u"}

    # Known-positive: the 2026-11-14 auto-deploy-red-watch shape, half-hourly, all red,
    # interleaved with a green manual dispatch that must not break the streak.
    live = [run(0.5, "failure"), run(0.9, "success", "workflow_dispatch"),
            run(1, "failure"), run(1.5, "failure"), run(2, "failure")]
    check("known-positive: 4 red scheduled ticks, green dispatch between, is standing red",
          verdict(live, now)["state"] == "red")
    check("negative: newest scheduled tick green -> green (close licence)",
          verdict([run(0.5, "success"), *live[2:]], now)["state"] == "green")
    check("negative: one red tick is flaky, not standing",
          verdict([run(0.5, "failure"), run(1, "success")], now)["state"] == "flaky")
    check("daily job: two reds spanning >=24h is standing red",
          verdict([run(1, "failure"), run(25, "failure"), run(49, "success")], now)["state"] == "red")
    check("daily job: two reds within 24h is not yet",
          verdict([run(1, "failure"), run(2, "failure"), run(30, "success")], now)["state"] == "flaky")
    check("cancelled ticks are not verdicts (concurrency supersede)",
          verdict([run(0.5, "cancelled"), *live], now)["state"] == "red")
    check("no scheduled verdict on the page -> unknown, never green",
          verdict([run(1, "success", "push")], now)["state"] == "unknown")

    red = verdict(live, now)
    green = verdict([run(0.5, "success")], now)
    unknown = {"state": "unknown"}

    def issue(n: int, f: str, state: str, age_h: float, labels=()) -> dict:
        return {"number": n, "state": state, "body": f"<!-- standing-red:{f} -->\nx",
                "labels": [{"name": x} for x in labels],
                "created_at": (now - timedelta(hours=age_h)).strftime("%Y-%m-%dT%H:%M:%SZ")}

    ops = lambda v, iss: [(a["op"], a.get("escalate")) for a in plan(v, iss, now)]  # noqa: E731
    check("red, no issue -> open", ops({"a.yml": red}, []) == [("open", None)])
    check("red, open issue -> refresh in place (dedupe)",
          ops({"a.yml": red}, [issue(1, "a.yml", "open", 5)]) == [("refresh", False)])
    check("red, hand-closed issue -> reopen",
          ops({"a.yml": red}, [issue(1, "a.yml", "closed", 5)]) == [("reopen", None)])
    check("red, open + an older closed duplicate -> refresh the open one only",
          ops({"a.yml": red}, [issue(9, "a.yml", "closed", 200), issue(1, "a.yml", "open", 5)])
          == [("refresh", False)])
    check("red >=72h -> escalate once", ops({"a.yml": red}, [issue(1, "a.yml", "open", 73)])
          == [("refresh", True)])
    check("already escalated -> no second escalation",
          ops({"a.yml": red}, [issue(1, "a.yml", "open", 99, ["standing-red-escalated"])])
          == [("refresh", False)])
    check("green -> close open issue", ops({"a.yml": green}, [issue(1, "a.yml", "open", 5)])
          == [("close", None)])
    check("unknown (unreadable) -> never closes", ops({"a.yml": unknown},
          [issue(1, "a.yml", "open", 5)]) == [])
    check("another workflow's marker is not this one's issue",
          ops({"a.yml": red}, [issue(1, "ab.yml", "open", 5)]) == [("open", None)])

    import tempfile
    with tempfile.TemporaryDirectory() as td:
        wf = Path(td) / ".github/workflows"
        wf.mkdir(parents=True)
        (wf / "s.yml").write_text("on:\n  schedule:\n    - cron: '1 * * * *'\n")
        (wf / "p.yml").write_text("on:\n  push:\n")
        (wf / "auto-deploy.yml").write_text("on:\n  schedule:\n    - cron: '1 * * * *'\n")
        check("discovery: scheduled yes, push-only no, dedicated excluded",
              scheduled_workflows(Path(td)) == ["s.yml"])
    real = scheduled_workflows(Path(os.environ.get("GATE_ROOT", ".")))
    check("discovery: the real tree has scheduled workflows, including this one",
          len(real) >= 10 and "standing-red-sentinel.yml" in real)
    print(f"SUBJECTS={len(real)}")
    print("self-test: " + ("all cases behaved as required" if not bad else f"{len(bad)} FAILED"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--evaluate", action="store_true")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--json", metavar="PATH")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not (a.evaluate and a.repo):
        ap.error("--self-test, or --evaluate with --repo")
    try:
        v = evaluate(a.repo, Path("."), datetime.now(timezone.utc))
    except (subprocess.CalledProcessError, ValueError) as e:
        return emit("UNREADABLE", f"issue listing failed: {(getattr(e, 'stderr', '') or str(e))[:200]}")
    text = json.dumps(v, indent=2)
    if a.json:
        Path(a.json).write_text(text)
    red = sorted(f for f, x in v["verdicts"].items() if x["state"] == "red")
    print(f"scheduled workflows read={len(v['verdicts'])} red={len(red)} "
          f"unreadable={len(v['unreadable'])}: {', '.join(red)}")
    for u in v["unreadable"]:
        print(f"::warning::unreadable {u}")
    # More than a third unreadable is a blind sentinel, not a partial answer.
    if len(v["unreadable"]) * 3 > len(v["verdicts"]) + len(v["unreadable"]):
        return emit("UNREADABLE", f"{len(v['unreadable'])} workflows unreadable")
    return emit("FOUND" if red else "CLEAN", f"standing red: {len(red)}")


if __name__ == "__main__":
    sys.exit(main())
