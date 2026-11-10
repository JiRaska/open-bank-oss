#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Merge-train THROUGHPUT stall watch: "the train runs, yet nothing merges".

WHY. Three times on 2026-11-09 the merge train ran on schedule and merged nothing for hours, and
each time a human noticed: (1) a scheduled Gitleaks full scan turned main red, so the train only
served "fix" PRs one at a time; (2) a mermaid bump left a self-test UNFALSIFIED on main, so main
was red; (3) a `rules.yaml: security_sensitive_paths` change made the checklist admission refuse
every strict PR behind it with one identical reason. The existing controls cannot see this
shape: the server-side deadman only proves that train RUNS happen, and merge-train's own
STUCK issue covers only PRs it told of a conflict or refusal more than 4 h ago.

WHAT. STALL when
  * armed PRs exist (open, non-draft, auto-merge on, not a `train/` carrier) AND the newest
    merge into main is >= STALL_MINUTES old, or
  * main's required checks have been red for >= STALL_MINUTES.
The census reads the newest successful "Merge train" settle log, takes each armed PR's LAST
decision line (`# 15381 skip      <reason>`, `strict`, `fix`, `fast`, `CANDIDATE`), normalises
the reason (PR numbers, SHAs, numbers stripped) and groups. A reason shared by >= SYSTEMIC_MIN
armed PRs, or by every non-candidate armed PR (at least two), is SYSTEMIC and leads the issue.

OUTPUT. Exactly one open issue labelled STALL_LABEL: created on a stall, its body rewritten
(only when it changed) while the stall lasts, a comment only when the systemic cause changes,
closed with a comment once the stall is over.

EXIT. 0 = looked (stall or not); 1 = COULD NOT LOOK (API, log or ruleset unreadable). A watch that
cannot read its inputs must never report "no stall" — that is the failure this exists to end.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

STALL_MINUTES = 60
SYSTEMIC_MIN = 3
STALL_LABEL = "merge-train-stall"
STALL_TITLE_PREFIX = "merge-train: no merge for"
TRAIN_WORKFLOW = "merge-train.yml"
MIN_INTERVAL_S = 600                 # the train ticks ~1/min; the watch looks at most every 10 min
GREEN = {"success", "neutral", "skipped"}
MAIN_WALK = 15                       # first-parent main commits examined for a red streak
STATUS_CONTEXT = "merge-train"       # the train's own status; never a main verdict
SYSTEMIC_MARK = re.compile(r"<!-- stall-systemic: ([0-9a-f]{12}) -->")
DECISION_RE = re.compile(r"#\s*(\d+) (CANDIDATE|skip|strict|fix|fast)\s+(.*)$")  # `notice` is not a decision
ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


class CouldNotLook(Exception):
    """An input was unreadable. Never rendered as 'no stall'."""


def ts(s: str) -> float:
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp()


# ---------------------------------------------------------------------------- pure decisions
def normalise(reason: str) -> str:
    r = re.sub(r"#\d+", "#N", reason)
    r = re.sub(r"\b[0-9a-f]{7,40}\b", "<sha>", r)
    r = re.sub(r"\b\d+\b", "N", r)
    return re.sub(r"\s+", " ", r).strip()


def parse_settle_log(text: str) -> dict[int, tuple[str, str]]:
    """PR -> (kind, reason) of its LAST decision line. Only post-timestamp output is matched, so the
    step's own `run:` script echoed into the log header cannot produce a decision."""
    out: dict[int, tuple[str, str]] = {}
    for line in text.splitlines():
        line = ANSI_RE.sub("", line)
        m = re.match(r"^\d{4}-\d\d-\d\dT[\d:.]+Z (.*)$", line)
        body = m.group(1) if m else line
        d = DECISION_RE.match(body)
        if d:
            out[int(d.group(1))] = (d.group(2), d.group(3).strip())
    return out


def is_candidate(kind: str, reason: str) -> bool:
    return kind == "CANDIDATE" or (kind == "strict" and reason.startswith("green:"))


def census(armed: list[int], decisions: dict[int, tuple[str, str]]):
    """-> (groups {normalised reason: [PRs]}, systemic [reasons], candidates [PRs])."""
    groups: dict[str, list[int]] = {}
    cands: list[int] = []
    for n in sorted(armed):
        kind, reason = decisions.get(n, ("absent", "not in the settle log"))
        if is_candidate(kind, reason):
            cands.append(n)
            continue
        groups.setdefault(f"{kind}: {normalise(reason)}", []).append(n)
    non_cand = sum(len(v) for v in groups.values())
    systemic = sorted(
        (k for k, v in groups.items()
         if len(v) >= SYSTEMIC_MIN or (len(v) == non_cand and non_cand >= 2)),
        key=lambda k: (-len(groups[k]), k))
    return groups, systemic, cands


def is_stall(armed: list[int], last_merge: float, red_since: float | None, now: float) -> tuple[bool, list[str]]:
    why = []
    if armed and now - last_merge >= STALL_MINUTES * 60:
        why.append(f"{len(armed)} armed PR(s) and no merge for {int((now - last_merge) // 900) * 15}+ min")
    if red_since is not None and now - red_since >= STALL_MINUTES * 60:
        why.append(f"main red for {int((now - red_since) // 900) * 15}+ min")
    return bool(why), why


def render(v: dict) -> str:
    L = [f"<!-- stall-systemic: {v['systemic_key']} -->",
         f"**Merge train stalled.** {'; '.join(v['why'])} (threshold {STALL_MINUTES} min).", "",
         f"- Minutes since last merge: **{v['minutes'] // 15 * 15}+** (#{v['last_pr']})",
         f"- main: **{v['main']}**",
         f"- Armed: {len(v['armed'])}, train candidates: {len(v['candidates'])} "
         f"({', '.join(f'#{n}' for n in v['candidates']) or 'none'})",
         f"- Census from settle run: {v['settle_url']}", ""]
    if v["systemic"]:
        L.append("## SYSTEMIC cause(s)")
        for k in v["systemic"]:
            L.append(f"- **{k}** — {len(v['groups'][k])} PRs: {', '.join(f'#{n}' for n in v['groups'][k])}")
        L.append("")
    L.append("## Full census")
    for k, prs in sorted(v["groups"].items(), key=lambda kv: (-len(kv[1]), kv[0])):
        L.append(f"- {len(prs)} × `{k}`: {', '.join(f'#{n}' for n in prs)}")
    L += ["", f"_Kept by `merge-train-stall-watch.yml` ({v['watch_url']}); one issue, body rewritten, "
               "closed automatically when merges resume._"]
    return "\n".join(L)


def title_for(v: dict) -> str:
    """Fixed for the life of one stall (keyed on the last merge), so the title is not PATCHed every run."""
    return f"{STALL_TITLE_PREFIX} {STALL_MINUTES}+ min (last merge #{v['last_pr']})"


def reconcile_issue(api, stalled: bool, v: dict) -> str:
    """Idempotent: never a second issue, body PATCHed only when changed, a comment only when the
    systemic cause changes, closed on recovery. Returns the action taken."""
    open_issues = api.open_issues(STALL_LABEL)
    issue = min(open_issues, key=lambda i: i["number"]) if open_issues else None
    if not stalled:
        if issue:
            api.comment(issue["number"], f"Merges resumed: #{v['last_pr']} merged {v['minutes']} min ago, "
                                         f"main {v['main']}. Closing. ({v['watch_url']})")
            api.close(issue["number"])
            return "closed"
        return "none"
    body = render(v)
    if issue is None:
        api.create(title_for(v), body, STALL_LABEL)
        return "created"
    old = SYSTEMIC_MARK.search(issue.get("body") or "")
    changed = (old.group(1) if old else None) != v["systemic_key"]
    if changed:   # before the PATCH that moves the marker, so a failed comment is retried next run
        api.comment(issue["number"], "Systemic cause changed: "
                    + ("; ".join(v["systemic"]) or "no systemic cause any more") + ".")
    if issue.get("body") != body or issue.get("title") != title_for(v):
        api.update(issue["number"], title_for(v), body)
        return "updated+comment" if changed else "updated"
    return "updated+comment" if changed else "unchanged"


# ---------------------------------------------------------------------------- live API
class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


class Api:
    def __init__(self, repo: str, token: str):
        self.repo, self.token = repo, token

    def _req(self, method: str, path: str, data=None, raw=False):
        url = path if path.startswith("http") else f"https://api.github.com{path}"
        req = urllib.request.Request(url, method=method, data=json.dumps(data).encode() if data else None,
                                     headers={"Authorization": f"Bearer {self.token}",
                                              "Accept": "application/vnd.github+json",
                                              "X-GitHub-Api-Version": "2022-11-28"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                b = r.read()
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise CouldNotLook(f"{method} {path}: {e}") from e
        return b.decode("utf-8", "replace") if raw else (json.loads(b) if b else None)

    def get(self, path, raw=False):
        return self._req("GET", f"/repos/{self.repo}{path}", raw=raw)

    # reads
    def last_merge(self) -> tuple[float, int]:
        prs = self.get("/pulls?state=closed&base=main&sort=updated&direction=desc&per_page=100")
        # Bot PRs (gitops deploys) merge outside the train and would reset the clock on a real stall.
        merged = [(ts(p["merged_at"]), p["number"]) for p in prs
                  if p.get("merged_at") and (p.get("user") or {}).get("type") != "Bot"]
        if not merged:
            raise CouldNotLook("no merged PR among the 100 most recently updated closed PRs")
        return max(merged)

    def armed(self) -> list[int]:
        out, page = [], 1
        while page <= 10:
            prs = self.get(f"/pulls?state=open&base=main&per_page=100&page={page}")
            out += [p["number"] for p in prs if p.get("auto_merge") and not p.get("draft")
                    and not p["head"]["ref"].startswith("train/")]
            if len(prs) < 100:
                return out
            page += 1
        raise CouldNotLook("more than 1000 open PRs; refusing a truncated census")

    def settle_log(self, run_id: str | None) -> tuple[str, str]:
        if not run_id:
            runs = self.get(f"/actions/workflows/{TRAIN_WORKFLOW}/runs?status=success&per_page=1")["workflow_runs"]
            if not runs:
                raise CouldNotLook("no successful Merge train run")
            run_id = str(runs[0]["id"])
        run = self.get(f"/actions/runs/{run_id}")
        jobs = self.get(f"/actions/runs/{run_id}/attempts/{run['run_attempt']}/jobs")["jobs"]
        settle = [j for j in jobs if j["name"].startswith("settle") and j.get("conclusion") == "success"]
        if not settle:
            raise CouldNotLook(f"run {run_id} has no successful settle job")
        return self.job_log(settle[0]["id"]), run["html_url"]

    def job_log(self, job_id) -> str:
        """The logs endpoint 302s to a pre-signed blob URL; urllib would forward the Authorization header
        there and get 401. Read the Location ourselves and fetch it WITHOUT credentials."""
        req = urllib.request.Request(f"https://api.github.com/repos/{self.repo}/actions/jobs/{job_id}/logs",
                                     headers={"Authorization": f"Bearer {self.token}",
                                              "Accept": "application/vnd.github+json"})
        try:
            urllib.request.build_opener(_NoRedirect).open(req, timeout=60)
            raise CouldNotLook(f"job {job_id} logs: expected a redirect")
        except urllib.error.HTTPError as e:
            loc = e.headers.get("Location") if e.code in (301, 302, 303, 307, 308) else None
            if not loc:
                raise CouldNotLook(f"job {job_id} logs: HTTP {e.code}") from e
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise CouldNotLook(f"job {job_id} logs: {e}") from e
        try:
            with urllib.request.urlopen(loc, timeout=60) as r:
                return r.read().decode("utf-8", "replace")
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise CouldNotLook(f"job {job_id} log blob: {e}") from e

    def required(self) -> list[str]:
        rules = self.get("/rules/branches/main")
        ctx = [c["context"] for r in rules if r.get("type") == "required_status_checks"
               for c in r["parameters"]["required_status_checks"]]
        ctx = [c for c in ctx if c != STATUS_CONTEXT]
        if not ctx:
            raise CouldNotLook("no required status checks readable on main")
        return ctx

    def main_commits(self) -> list[tuple[str, float]]:
        cs = self.get(f"/commits?sha=main&per_page={MAIN_WALK}")
        return [(c["sha"], ts(c["commit"]["committer"]["date"])) for c in cs]

    def verdicts(self, sha: str) -> dict[str, str]:
        """context -> 'success'|'failure' for every COMPLETED check-run and final commit status on sha.
        Two-plus calls per commit, not one per context (the train ticks ~1/min; GITHUB_TOKEN has 1000/h)."""
        out: dict[str, str] = {}
        for page in (1, 2, 3):
            r = self.get(f"/commits/{sha}/check-runs?filter=latest&per_page=100&page={page}")
            for c in r["check_runs"]:
                if c.get("status") == "completed":
                    bad = c.get("conclusion") not in GREEN
                    out[c["name"]] = "failure" if bad or out.get(c["name"]) == "failure" else "success"
            if len(r["check_runs"]) < 100:
                break
        for st in self.get(f"/commits/{sha}/status")["statuses"]:
            if st["state"] in ("success", "failure", "error"):
                out.setdefault(st["context"], "success" if st["state"] == "success" else "failure")
        return out

    def last_watch_run(self) -> float | None:
        """Start time of the newest successful run of this watch (never the current, still-running one)."""
        runs = self.get("/actions/workflows/merge-train-stall-watch.yml/runs?status=success&per_page=1")["workflow_runs"]
        return ts(runs[0]["run_started_at"]) if runs else None

    # issue writes
    def open_issues(self, label):
        return self.get(f"/issues?state=open&labels={label}&per_page=10")

    def create(self, title, body, label):
        self._req("POST", f"/repos/{self.repo}/issues", {"title": title, "body": body, "labels": [label]})

    def update(self, n, title, body):
        self._req("PATCH", f"/repos/{self.repo}/issues/{n}", {"title": title, "body": body})

    def comment(self, n, body):
        self._req("POST", f"/repos/{self.repo}/issues/{n}/comments", {"body": body})

    def close(self, n):
        self._req("PATCH", f"/repos/{self.repo}/issues/{n}", {"state": "closed", "state_reason": "completed"})


def main_red_since(api) -> tuple[float | None, str]:
    """-> (time main turned red or None, description). Per context, the newest commit with a
    completed verdict decides; a red streak runs back to the commit after the last green."""
    required, commits = api.required(), api.main_commits()
    table, pending = [], set(required)
    for sha, t in commits:            # stop once every context has a verdict that is green
        vs = api.verdicts(sha)
        table.append((t, vs))
        pending -= {c for c in required if vs.get(c) == "success"}
        if not pending:
            break
    if not any(c in vs for _, vs in table for c in required):
        raise CouldNotLook(f"no required context ({', '.join(required)}) has any verdict on the last "
                           f"{len(table)} main commits — cannot tell red from green")
    reds, since = [], None
    for ctx in required:
        first_red_t, verdict_seen = None, None
        for t, vs in table:
            v = vs.get(ctx)
            if v is None:
                continue
            if verdict_seen is None:
                verdict_seen = v
                if v != "failure":
                    break
            if v == "failure":
                first_red_t = t
            else:
                break
        if verdict_seen == "failure":
            reds.append(ctx)
            since = first_red_t if since is None else min(since, first_red_t)
    return since, ("red: " + ", ".join(reds)) if reds else "green"


def evaluate(api, now: float, run_id: str | None, watch_url: str) -> tuple[bool, dict]:
    last_t, last_pr = api.last_merge()
    armed = api.armed()
    red_since, main_desc = main_red_since(api)
    stalled, why = is_stall(armed, last_t, red_since, now)
    v = {"minutes": int((now - last_t) // 60), "last_pr": last_pr, "main": main_desc, "armed": armed,
         "why": why, "watch_url": watch_url, "groups": {}, "systemic": [], "candidates": [],
         "settle_url": "n/a", "systemic_key": hashlib.sha256(b"").hexdigest()[:12]}
    if stalled:
        text, url = api.settle_log(run_id)
        if not parse_settle_log(text):
            raise CouldNotLook(f"settle log {url} holds no decision line — census unreadable")
        v["groups"], v["systemic"], v["candidates"] = census(armed, parse_settle_log(text))
        v["settle_url"] = url
        v["systemic_key"] = hashlib.sha256("\n".join(v["systemic"]).encode()).hexdigest()[:12]
    return stalled, v


# ---------------------------------------------------------------------------- self-test
REAL_REASONS = [
    "waiting for main (Gitleaks red on main 3f2a9c1d0b7e)",
    "waiting for main (Validate manifests red on main 9ab01c2d3e4f)",
    "security-checklist-money-path fails on the live body — tick the checklist, then re-run "
    "Validate manifests; the train re-reads the body every tick",
]


class FakeApi:
    def __init__(self, issues=None, fail=None, merged=(0.0, 1), armed=(), red=None, log=""):
        self.issues = list(issues or [])
        self.calls: list[tuple] = []
        self.last_watch = None
        self.fail, self._merged, self._armed, self._red, self._log = fail, merged, list(armed), red, log

    def _chk(self, what):
        if self.fail == what:
            raise CouldNotLook(f"fake {what} unreadable")

    def last_merge(self):
        self._chk("pulls")
        return self._merged

    def armed(self):
        self._chk("armed")
        return self._armed

    def settle_log(self, run_id):
        self._chk("log")
        return self._log, "https://example.invalid/run/1"

    def required(self):
        self._chk("required")
        return ["Validate manifests"]

    def main_commits(self):
        return [("c2", 5000.0), ("c1", 4000.0), ("c0", 3000.0)]

    def verdicts(self, sha):
        v = (self._red or {"c2": "success"}).get(sha)
        return {} if v is None else {"Validate manifests": v}

    def last_watch_run(self):
        return self.last_watch

    def open_issues(self, label):
        return [i for i in self.issues if i["state"] == "open"]

    def create(self, title, body, label):
        self.calls.append(("create",))
        self.issues.append({"number": 900 + len(self.issues), "title": title, "body": body, "state": "open"})

    def update(self, n, title, body):
        self.calls.append(("update", n))
        for i in self.issues:
            if i["number"] == n:
                i.update(title=title, body=body)

    def comment(self, n, body):
        self.calls.append(("comment", n, body))

    def close(self, n):
        self.calls.append(("close", n))
        for i in self.issues:
            if i["number"] == n:
                i["state"] = "closed"


def self_test() -> int:
    fails = 0

    def check(name, ok):
        nonlocal fails
        print(f"{'ok  ' if ok else 'FAIL'} {name}")
        fails += 0 if ok else 1

    T = STALL_MINUTES * 60
    # threshold boundary
    check("stall at exactly STALL_MINUTES with armed PRs", is_stall([1], 0, None, T)[0])
    check("no stall one second before the threshold", not is_stall([1], 0, None, T - 1)[0])
    check("no stall with no armed PRs and main green", not is_stall([], 0, None, 10 * T)[0])
    check("main red for STALL_MINUTES is a stall even with a recent merge", is_stall([], T, 0, T)[0])
    check("main red under the threshold is not", not is_stall([], T, 1, T)[0])

    # census: the three real 2026-11-09 reason strings, with differing PR numbers/SHAs
    log = "\n".join([
        "2026-11-09T19:11:58.1Z # 15001 skip      " + REAL_REASONS[0],
        "2026-11-09T19:11:58.1Z # 15002 skip      " + REAL_REASONS[0].replace("3f2a9c1d0b7e", "77aa88bb99cc"),
        "2026-11-09T19:11:58.1Z # 15003 skip      " + REAL_REASONS[0].replace("3f2a9c1d0b7e", "0123456789ab"),
        "2026-11-09T19:11:58.1Z # 15010 skip      " + REAL_REASONS[1],
        "2026-11-09T19:12:00.1Z # 14881 skip      " + REAL_REASONS[2],
        "2026-11-09T19:12:00.1Z # 14940 skip      " + REAL_REASONS[2],
        "2026-11-09T19:12:00.1Z # 15298 skip      " + REAL_REASONS[2],
        "2026-11-09T19:12:01.1Z # 15357 strict    held: main reserved for strict-lane #15330",
        "2026-11-09T19:12:05.1Z # 15345 CANDIDATE armed + all required green",
        "2026-11-09T19:12:19.3Z # 15381 skip      touches .gitleaks.toml — manual lane, never a train",
        "2026-11-09T19:12:19.3Z # 15381 strict    green: armed + all required green",
        "echo \"# 99999 skip      script text in the log header\"",
    ])
    dec = parse_settle_log(log)
    check("last decision line wins (15381 strict green)", dec.get(15381, ("",))[0] == "strict")
    check("an echoed script line is not a decision", 99999 not in dec)
    armed = [15001, 15002, 15003, 15010, 14881, 14940, 15298, 15357, 15345, 15381, 15500]
    groups, systemic, cands = census(armed, dec)
    gl = "skip: " + normalise(REAL_REASONS[0])
    ck = "skip: " + normalise(REAL_REASONS[2])
    check("Gitleaks main-red reason groups 3 PRs across differing SHAs", groups.get(gl) == [15001, 15002, 15003])
    check("checklist reason is SYSTEMIC with 3 PRs", ck in systemic and groups[ck] == [14881, 14940, 15298])
    check("main-red reason is SYSTEMIC", gl in systemic)
    check("Validate-manifests main red (1 PR) is not systemic", ("skip: " + normalise(REAL_REASONS[1])) not in systemic)
    check("CANDIDATE and strict-green are candidates", cands == [15345, 15381])
    check("armed PR absent from the log is reported", groups.get("absent: not in the settle log") == [15500])
    g2, s2, _ = census([15357, 15358], {15357: ("strict", "held: main reserved for strict-lane #15330"),
                                        15358: ("strict", "held: main reserved for strict-lane #15331")})
    check("reason shared by every non-candidate armed PR is SYSTEMIC", s2 == ["strict: held: main reserved for strict-lane #N"])
    _, s3, _ = census([1, 2], {1: ("skip", "a"), 2: ("skip", "b")})
    check("distinct reasons are not systemic", s3 == [])

    # could not look: non-zero, never "no stall"
    for what in ("pulls", "armed", "required", "log"):
        api = FakeApi(fail=what, merged=(0.0, 7), armed=[1], log=log)
        try:
            evaluate(api, 10 * T, None, "w")
            check(f"unreadable {what} raises CouldNotLook", False)
        except CouldNotLook:
            check(f"unreadable {what} raises CouldNotLook", True)
    try:
        evaluate(FakeApi(merged=(0.0, 7), armed=[1], log="2026-11-09T19:00:00Z no decisions here"), 10 * T, None, "w")
        check("settle log without a decision line raises CouldNotLook", False)
    except CouldNotLook:
        check("settle log without a decision line raises CouldNotLook", True)
    check("trailing notice does not override the decision",
          parse_settle_log("2026-01-01T00:00:00Z # 5 skip      " + REAL_REASONS[2] + "\n"
                           "2026-01-01T00:00:01Z #     5 notice    told the PR")[5][0] == "skip")
    try:
        main_red_since(FakeApi(red={"c9": "failure"}))
        check("no context verdict on any main commit raises CouldNotLook", False)
    except CouldNotLook:
        check("no context verdict on any main commit raises CouldNotLook", True)
    blind = FakeApi(fail="pulls")
    run_cli(blind, 0.0, write=True)
    run_cli(blind, 1.0, write=True)
    check("could-not-look with --write reports a blind watch in ONE issue, once",
          len(blind.issues) == 1 and [c[0] for c in blind.calls] == ["create"])
    thr = FakeApi(fail="pulls")
    thr.last_watch = 2.0 * MIN_INTERVAL_S
    check("second run in the same bucket does not look", run_cli(thr, 3.0 * MIN_INTERVAL_S - 1, throttle=True) == 0)
    check("first run of the next bucket looks", run_cli(thr, 3.0 * MIN_INTERVAL_S, throttle=True) == 1)
    thr.last_watch = 3.0 * MIN_INTERVAL_S - 100   # a throttled run late in the previous bucket
    check("a recent THROTTLED run does not suppress the next bucket's look",
          run_cli(thr, 3.0 * MIN_INTERVAL_S + 10, throttle=True) == 1)
    check("cli maps CouldNotLook to exit 1", run_cli(FakeApi(fail="pulls"), 0.0) == 1)
    check("cli maps a clean look to exit 0", run_cli(FakeApi(merged=(0.0, 7)), 1.0) == 0)

    # main red streak: c2 and c1 red, c0 green -> red since c1 (4000)
    since, desc = main_red_since(FakeApi(red={"c2": "failure", "c1": "failure", "c0": "success"}))
    check("main red since the commit after the last green", since == 4000.0 and desc.startswith("red"))
    since, _ = main_red_since(FakeApi(red={"c2": None, "c1": "success", "c0": "failure"}))
    check("newest completed verdict green -> main green", since is None)

    # idempotent issue lifecycle
    api = FakeApi(merged=(0.0, 7), armed=armed, log=log)
    st, v = evaluate(api, 2 * T, None, "w")
    check("evaluate reports the stall", st and v["systemic"])
    check("first stall creates one issue", reconcile_issue(api, st, v) == "created" and len(api.issues) == 1)
    st, v = evaluate(api, 2 * T, None, "w")
    api.calls.clear()
    reconcile_issue(api, st, v)
    check("same state again: no write, no comment", api.calls == [])
    st, v = evaluate(api, 2 * T + 300, None, "w")
    api.calls.clear()
    reconcile_issue(api, st, v)
    check("5 min later in the same 15-min bucket: no write at all", api.calls == [])
    st, v = evaluate(api, 2 * T + 900, None, "w")
    api.calls.clear()
    reconcile_issue(api, st, v)
    check("next 15-min bucket: body updated, title unchanged, no comment",
          [c[0] for c in api.calls] == ["update"] and len(api.issues) == 1
          and api.issues[0]["title"] == title_for(v))
    api._log = log.replace(REAL_REASONS[2], "some other refusal")
    st, v = evaluate(api, 2 * T + 300, None, "w")
    api.calls.clear()
    reconcile_issue(api, st, v)
    check("systemic cause changed: exactly one comment, before the body update",
          [c[0] for c in api.calls] == ["comment", "update"] and len(api.issues) == 1)
    api._merged = (2 * T + 200, 8)
    st, v = evaluate(api, 2 * T + 300, None, "w")
    api.calls.clear()
    check("merge after the stall closes the issue", reconcile_issue(api, st, v) == "closed"
          and api.issues[0]["state"] == "closed" and ("close", api.issues[0]["number"]) in api.calls)
    api.calls.clear()
    check("closed and healthy: no further writes", reconcile_issue(api, st, v) == "none" and api.calls == [])
    print(f"self-test: {'PASS' if fails == 0 else f'FAIL ({fails})'}")
    return 1 if fails else 0


def report_blind(api, err: str, watch_url: str) -> None:
    """A red workflow_run is addressed to nobody, so the blind state goes into the same one issue. Best
    effort: if writing fails too there is nothing left to tell, and the exit is already 1."""
    try:
        issues = api.open_issues(STALL_LABEL)
        body = (f"<!-- stall-systemic: blind -->\n**The merge-train stall watch cannot see.** {err}\n\n"
                f"Until this clears, a stall would go unreported. Run: {watch_url}")
        if not issues:
            api.create(f"{STALL_TITLE_PREFIX} ? min (stall watch blind)", body, STALL_LABEL)
        elif "stall-systemic: blind" not in (issues[0].get("body") or ""):
            api.update(min(i["number"] for i in issues), issues[0]["title"], body)
    except (CouldNotLook, KeyError, TypeError, ValueError) as e2:
        print(f"::error::could not report the blind watch either: {e2}")


def run_cli(api, now: float, run_id: str | None = None, watch_url: str = "", write: bool = False,
            throttle: bool = False) -> int:
    try:
        if throttle:
            # The FIRST run in each MIN_INTERVAL_S wall-clock bucket looks. Bucketing (not "N s since the
            # last run") matters: a throttled run is itself a successful run, and measuring from it would
            # throttle forever while the train ticks every minute. A cancelled looker is not `success`, so
            # the next run in its bucket looks instead.
            last = api.last_watch_run()
            if last is not None and int(now // MIN_INTERVAL_S) == int(last // MIN_INTERVAL_S):
                print(f"throttled: a run already looked in this {MIN_INTERVAL_S} s bucket")
                return 0
        stalled, v = evaluate(api, now, run_id, watch_url)
    except (CouldNotLook, KeyError, TypeError, ValueError) as e:
        print(f"::error::merge-train stall watch COULD NOT LOOK: {e}. This is a failure of the watch, not 'no stall'.")
        if write:
            report_blind(api, str(e), watch_url)
        return 1
    if stalled:
        print(f"::warning::merge train stalled: {'; '.join(v['why'])}. Systemic: {'; '.join(v['systemic']) or 'none'}")
    else:
        print(f"no stall: last merge #{v['last_pr']} {v['minutes']} min ago, {len(v['armed'])} armed, main {v['main']}")
    print(render(v) if stalled else "")
    if write:
        try:
            print(f"issue: {reconcile_issue(api, stalled, v)}")
        except CouldNotLook as e:
            print(f"::error::could not update the stall issue: {e}")
            return 1
    return 0


def check_declaration(root: str = ".") -> int:
    """The watch is only as good as its wiring: it must fire on the train's own runs (no cron —
    GitHub delivered ~3% of crons here), write the issue, and carry the label labels.yml declares."""
    def read(p):
        with open(os.path.join(root, p), encoding="utf-8") as f:
            return f.read()
    errs = []
    train = re.search(r"^name:\s*(.+)$", read(".github/workflows/merge-train.yml"), re.M)
    wf = read(".github/workflows/merge-train-stall-watch.yml")
    if not train or not re.search(r"workflows:\s*\[\s*['\"]?" + re.escape(train.group(1).strip()) + r"['\"]?\s*\]", wf):
        errs.append("merge-train-stall-watch.yml does not listen on the merge train's workflow name")
    if "workflow_run:" not in wf:
        errs.append("merge-train-stall-watch.yml has no workflow_run trigger")
    if "merge-train-stall-watch.py --write" not in wf:
        errs.append("merge-train-stall-watch.yml never runs the watch with --write")
    if not re.search(r"^\s*- name: " + re.escape(STALL_LABEL) + r"\s*$", read(".github/labels.yml"), re.M):
        errs.append(f"label {STALL_LABEL} is not declared in .github/labels.yml")
    print("SUBJECTS=1")
    for e in errs:
        print(f"::error::{e}")
    print("declaration: " + ("FAIL" if errs else "OK"))
    return 1 if errs else 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    if "--check-declaration" in argv:
        return check_declaration()
    repo, token = os.environ.get("GITHUB_REPOSITORY", ""), os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN", "")
    if not repo or not token:
        print("::error::GITHUB_REPOSITORY and GH_TOKEN are required — refusing to report 'no stall' blind")
        return 1
    run_id = os.environ.get("TRAIN_RUN_ID") or None
    watch = f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{repo}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}"
    throttle = os.environ.get("GITHUB_EVENT_NAME") == "workflow_run"
    return run_cli(Api(repo, token), datetime.now(timezone.utc).timestamp(), run_id, watch, "--write" in argv, throttle)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
