"""Keep ARMED pull requests up to date with `main`, and only those.

WHY THIS EXISTS
---------------
When `main-protection` sets `strict_required_status_checks_policy: true`, a PR must be up to date with
`main` to merge. The script exits before PR enumeration when strict mode is off. `main` moves
every few minutes and CI takes 30-40 minutes, so a PR whose
auto-merge is armed and whose required checks are all green goes `mergeable_state: behind` and
then sits forever: auto-merge never updates a branch. The only remedy used to be a human
noticing and pressing "Update branch". GitHub's merge queue would solve this, but it needs an
organisation-owned repository.

WHAT IT DOES
------------
For every open, non-draft, same-repository PR with auto-merge armed, whose `mergeable_state` is
`behind` and whose REQUIRED contexts (read live from the branch rules, never hard-coded) have all
completed successfully on the current head, it calls
`PUT /repos/{o}/{r}/pulls/{n}/update-branch` with `expected_head_sha`. Everything else is left
alone, with one log line per decision:

  * required checks pending/failed/missing -> skip (updating would discard a verdict in flight,
    or refresh a PR that cannot merge anyway);
  * `dirty` (conflicts)                    -> skip, a human has to resolve those;
  * not armed                              -> skip, the author has not asked for a merge;
  * head committed < --debounce-minutes ago -> skip, so CI is not restarted repeatedly;
  * more than --max-updates in this run    -> skip, capped;
  * branch-update budget exhausted         -> skip while that history remains; a human resolves it.

FULL-FLEET SERIALIZATION
------------------------
A PR whose Services CI fans out to (nearly) the whole fleet takes 1.5-2.5 h with the runner
queue, while `main` moves every ~30 min. Updating it on every pass restarts that build forever
(#8837 went green five times and never landed) and each restart adds ~150 jobs to the shared
queue. So at most ONE full-fleet PR is in flight at a time:

  * full-fleet = the head's check runs carry >= --full-fleet-threshold distinct
    `build (<module>) / ...` matrix entries (the names services-ci.yml's `build` matrix emits,
    so this is the `changes` job's decision as executed, read from the check runs this script
    already fetches — no extra API call, no re-implementation of the selector);
  * in flight = a full-fleet armed PR with any such build check still queued/in_progress on
    its current head -> every other full-fleet candidate is skipped;
  * otherwise only the longest-waiting candidate (auto-merge armed earliest:
    `auto_merge.enabled_at`, else PR created_at; tie-break lowest number) is updated;
  * a head with no build check runs cannot be classified -> treated as small (today's
    behaviour) and logged as such.
Small PRs are unaffected.

THE TOKEN MATTERS
-----------------
The update MUST be made with the GitHub App installation token. A push made with the workflow's
GITHUB_TOKEN does not trigger workflows, so CI would never re-run on the new head and the PR
would wedge on the required checks instead of on `behind`. The merge commit `update-branch`
creates is made server-side (committer `GitHub`) and GitHub signs it, which `required_signatures`
needs: see the PR that introduced this script for the measured evidence.

HARD COST BUDGET
----------------
`--max-updates` limits one workflow run only. `--max-updates-per-pr` counts consecutive GitHub
server-side main-merge commits at the PR head, across workflow runs. An author commit resets this
consecutive count. The bounded first-parent read takes at most cap+1 API calls, regardless of PR
length. An unreadable budget skips only that PR. At the cap, the author must push a substantive
change or rebase the branch, then let normal review and CI run.

MODES
-----
  --self-test           exercise the pure decision function (no network)
  --check-declaration   assert the workflow still wires this script the way the design requires
  (default)             run against the live API; `--dry-run` decides without updating
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("GITHUB_API_URL", "https://api.github.com")
WORKFLOW = pathlib.Path(".github/workflows/auto-update-armed-prs.yml")
OK_CONCLUSIONS = {"success", "neutral", "skipped"}


# Deploy-bot PRs are updated under the same readiness and cost controls as armed PRs.
BOT_DEPLOY_PREFIXES = ("chore/gitops-auto-deploy-", "chore/admin-ui-deploy-")
# REST user.login uses the bot login, not gh GraphQL's app/display alias.
BOT_AUTHOR = "openbank-gitops-bot[bot]"


def is_bot_deploy_pr(p: dict) -> bool:
    return (p.get("user", {}).get("login") == BOT_AUTHOR
            and any((p.get("head", {}).get("ref") or "").startswith(pre)
                    for pre in BOT_DEPLOY_PREFIXES))


def readiness_hold(pr: object) -> str | None:
    """Fail closed when the live PR cannot prove it is open, ready, and unblocked."""
    if not isinstance(pr, dict) or pr.get("state") != "open":
        return "PR is not confirmed open"
    if pr.get("draft") is not False:
        return "PR is draft or draft status is unknown"
    labels = pr.get("labels")
    if not isinstance(labels, list):
        return "PR labels are missing or malformed"
    for label in labels:
        if not isinstance(label, dict):
            return "PR label entry is malformed"
        name = label.get("name")
        if not isinstance(name, str) or not name.strip():
            return "PR label name is missing or malformed"
        if name.strip().casefold() == "blocked":
            return "PR carries the blocked hold label"
    return None


# --------------------------------------------------------------------------- pure decision
def decide(pr: dict, required: list[str], checks: dict[str, str | None], now: dt.datetime,
           head_committed_at: dt.datetime | None, debounce_minutes: int) -> tuple[bool, str]:
    """Return (update?, reason). `checks` maps context -> conclusion (None = not completed)."""
    hold = readiness_hold(pr)
    if hold:
        return False, hold
    armed = bool(pr.get("auto_merge"))
    bot = bool(pr.get("bot_deploy"))
    if not armed and not bot:
        return False, "auto-merge not armed"
    if not pr.get("same_repo"):
        return False, "head is a fork"
    state = pr.get("mergeable_state")
    if state == "dirty":
        return False, "dirty (conflicts) — left for a human"
    if state != "behind":
        return False, f"mergeable_state={state}, not behind"
    if not required:
        return False, "no required contexts resolved — refusing to judge green"
    pending = [c for c in required if c in checks and checks[c] is None]
    if pending:
        return False, f"required context(s) pending: {', '.join(pending)}"
    failed = [c for c in required
              if c in checks and checks[c] not in OK_CONCLUSIONS and checks[c] != "cancelled"]
    if failed:
        return False, "required context(s) not green: " + ", ".join(f"{c}={checks[c]}" for c in failed)
    if head_committed_at is not None:
        age = (now - head_committed_at).total_seconds() / 60
        if age < debounce_minutes:
            return False, f"debounced — head committed {age:.0f} min ago (< {debounce_minutes})"
    actor = "armed" if armed else "bot deploy PR (not armed)"
    unregistered = [c for c in required if c not in checks or checks[c] == "cancelled"]
    if unregistered:
        return True, (f"behind + {actor} + required context(s) unregistered: "
                      f"{', '.join(unregistered)} — update re-registers them")
    return True, f"behind + {actor} + all required contexts green"


BUILD_PREFIX = "build (openbank-"


def classify(checks: dict[str, str | None], threshold: int) -> tuple[str, bool]:
    """Return (kind, building). kind: full-fleet | small | unknown (unknown is treated as small)."""
    modules = {n[len("build ("):n.index(")")] for n in checks if n.startswith(BUILD_PREFIX) and ")" in n}
    building = any(checks[n] is None for n in checks if n.startswith(BUILD_PREFIX))
    if not modules:
        return "unknown", False
    return ("full-fleet" if len(modules) >= threshold else "small"), building


def plan(entries: list[dict], max_updates: int) -> list[tuple[int, bool, str]]:
    """Serialize full-fleet updates. Each entry: number, ok, why, kind, building, waiting_since.
    Returns (number, update?, reason) oldest-armed first, then by PR number."""
    in_flight = sorted(e["number"] for e in entries if e["kind"] == "full-fleet" and e["building"])
    fleet_ok = sorted((e for e in entries if e["ok"] and e["kind"] == "full-fleet"),
                      key=lambda e: (e["waiting_since"], e["number"]))
    chosen = None if in_flight or not fleet_ok else fleet_ok[0]["number"]
    out, updated = [], 0
    # The API lists newest PRs first. Apply the bounded budget in waiting order so
    # recurring new release/deploy PRs cannot indefinitely strand older green work.
    for e in sorted(entries, key=lambda entry: (entry["waiting_since"], entry["number"])):
        ok, why = e["ok"], e["why"]
        if ok and e["kind"] == "full-fleet":
            if in_flight:
                ok, why = False, f"full-fleet serialized — #{in_flight[0]} build in flight"
            elif e["number"] != chosen:
                ok, why = False, f"full-fleet serialized — #{chosen} has waited longer"
            else:
                why += " (full-fleet, longest-waiting)"
        elif ok and e["kind"] == "unknown":
            why += " (classification unreadable — treated as small)"
        if ok and updated >= max_updates:
            ok, why = False, f"cap of {max_updates} update(s) per run reached"
        updated += ok
        out.append((e["number"], ok, why))
    return out


def resolve_state(state: str | None, mergeable: bool | None, behind_by: int | None) -> str | None:
    """Settle a `mergeable_state` GitHub has not computed yet.

    GitHub computes it lazily, so a read can answer `unknown` (or null) for hours on a PR
    nobody opens. Read as "not behind", that strands every armed PR nobody is looking at.
    `mergeable: false` already means conflicts; otherwise the compare API's `behind_by`
    decides behind-ness directly. Anything still undecided stays as it was (a skip).
    """
    if state not in (None, "unknown"):
        return state
    if mergeable is False:
        return "dirty"
    if behind_by:
        return "behind"
    return state


def self_test() -> int:
    now = dt.datetime(2026, 9, 30, 12, 0, tzinfo=dt.timezone.utc)
    old = now - dt.timedelta(hours=2)
    req = ["Validate manifests", "Gitleaks"]
    green = {"Validate manifests": "success", "Gitleaks": "success"}
    base = {"state": "open", "draft": False, "labels": [],
            "auto_merge": {"merge_method": "squash"}, "same_repo": True,
            "mergeable_state": "behind"}
    cases = [
        ("behind+green+armed -> update", base, green, old, True),
        ("behind+pending -> skip", base, {**green, "Gitleaks": None}, old, False),
        ("behind+failed -> skip", base, {**green, "Gitleaks": "failure"}, old, False),
        ("behind+missing context re-registers", base, {"Validate manifests": "success"}, old, True),
        ("dirty -> skip", {**base, "mergeable_state": "dirty"}, green, old, False),
        ("not armed -> skip", {**base, "auto_merge": None}, green, old, False),
        ("draft -> skip", {**base, "draft": True}, green, old, False),
        ("fork -> skip", {**base, "same_repo": False}, green, old, False),
        ("clean (not behind) -> skip", {**base, "mergeable_state": "clean"}, green, old, False),
        ("debounced -> skip", base, green, now - dt.timedelta(minutes=5), False),
    ]
    failures = 0
    for name, pr, checks, committed, want in cases:
        got, why = decide(pr, req, checks, now, committed, 20)
        ok = got == want
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  {name}: update={got} ({why})")
    for name, held in [
        ("blocked label -> skip", {**base, "labels": [{"name": "blocked"}]}),
        ("draft status missing -> skip", {key: value for key, value in base.items() if key != "draft"}),
        ("labels missing -> skip", {key: value for key, value in base.items() if key != "labels"}),
        ("malformed label -> skip", {**base, "labels": [{"name": " "}]}),
        ("closed PR -> skip", {**base, "state": "closed"}),
    ]:
        got, why = decide(held, req, green, now, old, 20)
        ok = not got
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  {name}: update={got} ({why})")
    bot_deploy = {**base, "auto_merge": None, "bot_deploy": True}
    for name, pr, checks, want in [
        ("bot deploy without auto-merge can update", bot_deploy, green, True),
        ("bot deploy pending check waits", bot_deploy, {**green, "Gitleaks": None}, False),
        ("bot deploy failed check waits", bot_deploy, {**green, "Gitleaks": "failure"}, False),
        ("human unarmed PR still waits", {**base, "auto_merge": None}, green, False),
    ]:
        got, why = decide(pr, req, checks, now, old, 20)
        ok = got == want
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {got} ({why})")
    for name, user, ref, want in [
        ("bot deployment REST identity", "openbank-gitops-bot[bot]", "chore/gitops-auto-deploy-42", True),
        ("admin UI REST identity", "openbank-gitops-bot[bot]", "chore/admin-ui-deploy-42", True),
        ("display alias is not REST identity", "app/openbank-gitops-bot", "chore/gitops-auto-deploy-42", False),
        ("human with bot-like branch", "human", "chore/gitops-auto-deploy-42", False),
        ("bot with ordinary branch", BOT_AUTHOR, "feat/payment", False),
    ]:
        got = is_bot_deploy_pr({"user": {"login": user}, "head": {"ref": ref}})
        ok = got == want
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {got}")
    for name, checks, want in [
        ("missing required context re-registers", {"Validate manifests": "success"}, True),
        ("cancelled context re-registers", {**green, "Gitleaks": "cancelled"}, True),
        ("pending sibling prevents context recovery", {"Gitleaks": None}, False),
        ("failed sibling prevents context recovery", {"Gitleaks": "failure"}, False),
    ]:
        got, why = decide(base, req, checks, now, old, 20)
        ok = got == want
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {got} ({why})")
    # Context recovery must obey the full debounce for both actor classes.
    for actor, pr in [("armed", base), ("bot deploy", bot_deploy)]:
        for state, checks in [
            ("missing", {"Validate manifests": "success"}),
            ("cancelled", {**green, "Gitleaks": "cancelled"}),
        ]:
            for age, want in [(0, False), (44, False), (45, True), (120, True)]:
                got, why = decide(pr, req, checks, now,
                                  now - dt.timedelta(minutes=age), 45)
                ok = got == want
                failures += not ok
                print(f"{'PASS' if ok else 'FAIL'}  {actor}+{state}+age={age}: {why}")
        for state, checks, reason in [
            ("pending+missing", {"Validate manifests": None}, "pending"),
            ("failed+missing", {"Validate manifests": "failure"}, "not green"),
        ]:
            got, why = decide(pr, req, checks, now, now, 45)
            ok = not got and reason in why
            failures += not ok
            print(f"{'PASS' if ok else 'FAIL'}  {actor}+fresh+{state}: {why}")
    for name, args, want in [
        ("unknown + behind_by>0 -> behind", ("unknown", None, 3), "behind"),
        ("null + behind_by>0 -> behind", (None, None, 1), "behind"),
        ("unknown + mergeable=false -> dirty", ("unknown", False, 3), "dirty"),
        ("unknown + behind_by=0 -> unknown", ("unknown", None, 0), "unknown"),
        ("unknown + compare unread -> unknown", ("unknown", None, None), "unknown"),
        ("clean is never overridden", ("clean", None, 5), "clean"),
    ]:
        got = resolve_state(*args)
        ok = got == want
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  resolve {name}: {got}")
    # An empty required set must never read as "all green" (vacuous pass).
    got, why = decide(base, [], {}, now, old, 20)
    failures += got
    print(f"{'FAIL' if got else 'PASS'}  empty required set -> skip ({why})")
    # A permission denial must fail the workflow; the old implementation printed a
    # warning and returned zero, so a permanently stranded armed PR looked healthy.
    import contextlib
    import io
    from unittest.mock import patch

    sha = lambda n: f"{n:040x}"
    def commit(n: int, merge: bool, parent: int | None = None, *, committer: str = "GitHub") -> dict:
        return {"sha": sha(n), "commit": {
            "message": "Merge branch 'main' into codex/example" if merge else "fix: author change",
            "committer": {"name": committer, "email": "noreply@github.com"},
            "verification": {"verified": True, "reason": "valid"}},
            "parents": ([{"sha": sha(parent)}, {"sha": sha(1000)}] if merge else
                        ([{"sha": sha(parent)}] if parent is not None else []))}
    author = commit(1, False)
    chain = {sha(1): author}
    for i in range(2, 8):
        chain[sha(i)] = commit(i, True, i - 1)
    calls: list[str] = []
    def commit_api(_method: str, path: str, _body: dict | None = None) -> tuple[int, object]:
        calls.append(path)
        return (200, chain[path.rsplit("/", 1)[1]])
    for name, head, cap, want, want_reads in [
        ("author tip resets prior merge streak", sha(1), 4, 0, 1),
        ("one update then author push", sha(2), 4, 1, 2),
        ("cap minus one", sha(4), 4, 3, 4),
        ("cap", sha(5), 4, 4, 5),
        ("longer than cap stays bounded", sha(7), 4, 4, 5),
    ]:
        calls.clear()
        with patch.dict(globals(), {"api": commit_api}):
            used = branch_update_count("example/repo", 42, "main", cap, head)
        ok = used == want and len(calls) == want_reads and all("/pulls/" not in c for c in calls)
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {used}, {len(calls)} bounded reads")
    # More than GitHub's 250-entry PR-commit cap: the first-parent walk still counts
    # only valid signed updater merges, then stops at the author commit.
    def long_graph(authored: int, updates: int) -> tuple[str, dict]:
        commits: dict[str, dict] = {}
        prev = None
        for i in range(authored + updates):
            current = f"{i:040x}"
            merge = i >= authored
            parents = ([{"sha": prev}, {"sha": sha(1000 + i)}] if merge else
                       ([{"sha": prev}] if prev else [{"sha": sha(999)}]))
            commits[current] = {"sha": current, "commit": {
                "message": "Merge branch 'main' into codex/long" if merge else "feat: work",
                "committer": {"name": "GitHub" if merge else "Author",
                              "email": "noreply@github.com" if merge else "author@example.com"},
                "verification": {"verified": True}}, "parents": parents}
            prev = current
        return prev, commits
    head, long_commits = long_graph(320, 2)
    long_reads: list[str] = []
    def long_api(_method: str, path: str, _body: dict | None = None):
        commit_sha = path.rsplit("/", 1)[1]
        long_reads.append(commit_sha)
        return (200, long_commits[commit_sha]) if commit_sha in long_commits else (404, "Not Found")
    with patch.dict(globals(), {"api": long_api}):
        used = branch_update_count("example/repo", 42, "main", 4, head)
    ok = used == 2 and len(long_reads) == 3
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  322-commit history bounded: {used}, {len(long_reads)} reads")
    chain[sha(8)] = commit(8, False, 7)
    with patch.dict(globals(), {"api": commit_api}):
        used = branch_update_count("example/repo", 42, "main", 4, sha(8))
    ok = used == 0
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  new author push resets a saturated chain")
    chain[sha(9)] = commit(9, True, 8)
    chain[sha(10)] = commit(10, True, 9, committer="User")
    with patch.dict(globals(), {"api": commit_api}):
        used = branch_update_count("example/repo", 42, "main", 4, sha(10))
    ok = used == 0
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  user-spoofed merge message does not consume budget")
    forged = commit(11, True, 9)
    forged["commit"]["verification"] = {"verified": False, "reason": "unsigned"}
    chain[sha(11)] = forged
    with patch.dict(globals(), {"api": commit_api}):
        used = branch_update_count("example/repo", 42, "main", 4, sha(11))
    ok = used == 0
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  unsigned forged GitHub committer does not consume budget")
    for name, response in [("API error", (503, "unavailable")),
                           ("mismatched SHA", (200, author)),
                           ("malformed parent", (200, {**chain[sha(2)], "parents": [{}, {}]})),
                           ("missing verification", (200, {**author, "commit": {
                               "message": "fix: author change", "committer": {"name": "User", "email": "user@example.com"}}})),
                           ("missing committer", (200, {**author, "commit": {
                               "message": "fix: author change", "verification": {"verified": False}}})),
                           ("missing message", (200, {**author, "commit": {
                               "committer": {"name": "User", "email": "user@example.com"},
                               "verification": {"verified": False}}}))]:
        def broken_api(*_args, response=response):
            return response
        with patch.dict(globals(), {"api": broken_api}):
            try:
                branch_update_count("example/repo", 42, "main", 4,
                                    sha(2) if name in ("malformed parent", "mismatched SHA") else sha(1))
            except ValueError:
                print(f"PASS  {name} fails closed")
            else:
                failures += 1
                print(f"FAIL  {name} passed")

    def fixture_get(path: str) -> object:
        if "/pulls?" in path:
            return [{"number": 42, "auto_merge": {"merge_method": "squash"}, "draft": False}]
        if path.endswith("/pulls/42"):
            return {"state": "open", "draft": False, "labels": [],
                    "auto_merge": {"merge_method": "squash"}, "mergeable_state": "behind",
                    "base": {"ref": "main"},
                    "head": {"sha": "a" * 40, "ref": "agent/example",
                             "repo": {"full_name": "example/repo"}}}
        if "/commits/" in path:
            return {"commit": {"committer": {"date": old.isoformat()}}}
        raise AssertionError(f"unexpected API read {path}")

    with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                "get": fixture_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                                "branch_update_count": lambda *_: 0}):
        for status, want in ((403, 1), (409, 0)):
            with patch.dict(globals(), {"api": lambda *_args, code=status: (code, "denied")}):
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    result = run("example/repo", "main", 5, 0, False, 20)
                ok = result == want and (("::error::FAILED" in output.getvalue()) == (status == 403))
                failures += not ok
                print(f"{'PASS' if ok else 'FAIL'}  update-branch HTTP {status} -> exit {result}")

    for name, live_changes in [
        ("blocked added after planning", {"labels": [{"name": "blocked"}]}),
        ("draft converted after planning", {"draft": True}),
        ("labels removed after planning", {"labels": None}),
        ("malformed label added after planning", {"labels": [{"name": " "}]}),
        ("head moved after planning", {"head": {"sha": "b" * 40}}),
        ("armed state removed after planning", {"auto_merge": None}),
        ("PR closed after planning", {"state": "closed"}),
    ]:
        reads = 0
        writes = []
        def changed_after_planning(path: str, changes=live_changes) -> object:
            nonlocal reads
            if path.endswith("/pulls/42"):
                reads += 1
                original = fixture_get(path)
                return {**original, **(changes if reads > 1 else {})}
            return fixture_get(path)
        def record_update(method: str, path: str, _body: dict | None = None, writes=writes):
            if method == "PUT":
                writes.append((method, path))
                return 202, {}
            return 200, fixture_get(path)
        report = {"targets": []}
        with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                    "get": changed_after_planning,
                                    "head_checks": lambda *_: {"Gitleaks": "success"},
                                    "branch_update_count": lambda *_: 0,
                                    "api": record_update,
                                    "observe_update": lambda *_: "c" * 40}), contextlib.redirect_stdout(io.StringIO()):
            result = run("example/repo", "main", 5, 0, False, 20, recovery_report=report)
        ok = (result == 0 and reads >= 2 and not writes
              and report["targets"][0]["updated"] is False
              and report["targets"][0]["updated_head_sha"] is None)
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  live hold {name}: writes={len(writes)}")

    reads = 0
    writes = []
    def unreadable_live_pr(path: str) -> object:
        nonlocal reads
        if path.endswith("/pulls/42"):
            reads += 1
            if reads > 1:
                raise SystemExit("fixture API error")
        return fixture_get(path)
    with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                "get": unreadable_live_pr,
                                "head_checks": lambda *_: {"Gitleaks": "success"},
                                "branch_update_count": lambda *_: 0,
                                "api": lambda method, path, _body=None: writes.append((method, path))
                                or (202, {})}), contextlib.redirect_stdout(io.StringIO()):
        report = {"targets": []}
        result = run("example/repo", "main", 5, 0, False, 20, recovery_report=report)
    ok = (result == 0 and not writes and report["targets"][0]["updated"] is False)
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  live PR API error skips PUT and recovery evidence")

    # A held full-fleet build stays in the candidate set and remains the in-flight
    # serialization barrier, while its own held status prevents an update.
    fleet_pulls = [
        {"number": 42, "auto_merge": {"merge_method": "squash"}, "draft": False},
        {"number": 43, "auto_merge": {"merge_method": "squash"}, "draft": False},
    ]
    fleet_heads = {42: "a" * 40, 43: "b" * 40}
    def fleet_get(path: str) -> object:
        if "/pulls?" in path:
            return fleet_pulls
        for number in (42, 43):
            if path.endswith(f"/pulls/{number}"):
                return {"state": "open", "draft": False,
                        "labels": [{"name": "blocked"}] if number == 42 else [],
                        "auto_merge": {"merge_method": "squash"}, "mergeable_state": "behind",
                        "base": {"ref": "main"},
                        "head": {"sha": fleet_heads[number], "ref": f"agent/{number}",
                                 "repo": {"full_name": "example/repo"}}}
        if "/commits/" in path:
            return {"commit": {"committer": {"date": old.isoformat()}}}
        raise AssertionError(f"unexpected fleet test API read {path}")
    fleet_writes = []
    def record_fleet_write(method: str, path: str, _body: dict | None = None):
        fleet_writes.append((method, path))
        return 202, {}
    fleet_checks = {
        fleet_heads[42]: {"build (openbank-example-service)": None},
        fleet_heads[43]: {"build (openbank-example-service)": "success", "Gitleaks": "success"},
    }
    fleet_report = {"targets": []}
    with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                "get": fleet_get,
                                "head_checks": lambda _repo, head: fleet_checks[head],
                                "branch_update_count": lambda *_: 0,
                                "api": record_fleet_write}), contextlib.redirect_stdout(io.StringIO()):
        result = run("example/repo", "main", 5, 0, False, 1, recovery_report=fleet_report)
    ok = (result == 0 and not fleet_writes
          and [target["pr"] for target in fleet_report["targets"]] == [42, 43]
          and all(not target["updated"] for target in fleet_report["targets"]))
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  blocked in-flight full-fleet PR remains serialization barrier")

    with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                "get": fixture_get, "head_checks": lambda *_: {},
                                "branch_update_count": lambda *_: 0,
                                "api": lambda *_args, **_kwargs: (409, "race")}):
        report = {"targets": []}
        with contextlib.redirect_stdout(io.StringIO()):
            result = run("example/repo", "main", 5, 0, False, 20, recovery_report=report)
        ok = result == 0 and report["targets"] == [{
            "pr": 42, "head_sha": "a" * 40, "budget_validated": True,
            "update_count": 0, "history_error": False,
            "updated": False, "updated_head_sha": None,
        }]
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  missing context recovery still validates target history")
    with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                "get": fixture_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                                "branch_update_count": lambda *_: 0,
                                "api": lambda *_args, **_kwargs: (202, {}),
                                "observe_update": lambda *_: "b" * 40}):
        report = {"targets": []}
        with contextlib.redirect_stdout(io.StringIO()):
            result = run("example/repo", "main", 5, 0, False, 20, recovery_report=report)
        ok = result == 0 and report["targets"] == [{
            "pr": 42, "head_sha": "a" * 40, "budget_validated": True,
            "update_count": 0, "history_error": False,
            "updated": True, "updated_head_sha": "b" * 40,
        }]
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  observed target head produces recovery evidence")
    with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                "get": fixture_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                                "branch_update_count": lambda *_: 0,
                                "api": lambda *_args, **_kwargs: (202, {}),
                                "observe_update": lambda *_: None}):
        report = {"targets": []}
        with contextlib.redirect_stdout(io.StringIO()):
            result = run("example/repo", "main", 5, 0, False, 20, recovery_report=report)
        ok = result == 0 and report["targets"][0]["updated"] is False
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  accepted 202 without changed head cannot recover")
    old_head, new_head = "a" * 40, "b" * 40
    def observed_api(_method: str, path: str, _body: dict | None = None):
        if path.endswith("/pulls/42"):
            return 200, {"head": {"sha": old_head}}
        raise AssertionError(f"unexpected observation read {path}")
    with patch.dict(globals(), {"api": observed_api}):
        unchanged = observe_update("example/repo", 42, old_head, "main", 2, 0)
    ok = unchanged is None
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  unchanged observed head remains unproved")
    def merged_api(_method: str, path: str, _body: dict | None = None):
        if path.endswith("/pulls/42"):
            return 200, {"head": {"sha": new_head}}
        if path.endswith(f"/commits/{new_head}"):
            return 200, {"sha": new_head, "commit": {
                "message": "Merge branch 'main' into agent/example",
                "committer": {"name": "GitHub", "email": "noreply@github.com"},
                "verification": {"verified": True}},
                "parents": [{"sha": old_head}, {"sha": "e" * 40}]}
        raise AssertionError(f"unexpected observation read {path}")
    with patch.dict(globals(), {"api": merged_api}):
        observed = observe_update("example/repo", 42, old_head, "main", 2, 0)
    ok = observed == new_head
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  verified first-parent merge proves update")
    with patch.dict(globals(), {"api": merged_api}):
        wrong_parent = observe_update("example/repo", 42, "c" * 40, "main", 2, 0)
    ok = wrong_parent is None
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  unrelated branch movement cannot recover target")
    for status, expected in ((409, 0), (403, 1)):
        report = {"targets": []}
        with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                    "get": fixture_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                                    "branch_update_count": lambda *_: 0,
                                    "api": lambda *_args, code=status: (code, "denied")}), \
                contextlib.redirect_stdout(io.StringIO()):
            result = run("example/repo", "main", 5, 0, False, 20, recovery_report=report)
        ok = (result == expected and report["targets"][0]["updated"] is False
              and report["targets"][0]["updated_head_sha"] is None)
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  rejected update {status} cannot recover target")
    for label, data, expected in (
        ("Unicode expected-head race", {"message": "expected head sha didn’t match current head ref."}, 0),
        ("ASCII expected-head race", {"message": "expected head sha didn't match current head ref."}, 0),
        ("generic validation", {"message": "Validation Failed"}, 1),
        ("different precondition", {"message": "expected head sha is invalid"}, 1),
        ("wrong schema", {"message": ["expected head sha didn’t match current head ref."]}, 1),
        ("plain text", "expected head sha didn’t match current head ref.", 1),
        ("no message", {"errors": ["head mismatch"]}, 1),
    ):
        calls = []
        def racing_api(method, path, body=None, calls=calls, data=data):
            calls.append(method)
            return (422, data) if method == "PUT" else (200, {"head": {"sha": "b" * 40}})
        report = {"targets": []}
        with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                    "get": fixture_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                                    "branch_update_count": lambda *_: 0, "api": racing_api}), \
                contextlib.redirect_stdout(io.StringIO()):
            result = run("example/repo", "main", 5, 0, False, 20, recovery_report=report)
        ok = (result == expected and calls == ["PUT"]
              and report["targets"][0]["updated"] is False)
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  HTTP 422 {label} preserves recovery and error semantics")
    def unreadable_count(*_args):
        raise ValueError("unreadable test history")
    with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                "get": fixture_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                                "branch_update_count": unreadable_count}):
        report = {"targets": []}
        with contextlib.redirect_stdout(io.StringIO()):
            result = run("example/repo", "main", 5, 0, False, 20, recovery_report=report)
        ok = result == 0 and report["targets"] == [{
            "pr": 42, "head_sha": "a" * 40, "budget_validated": False,
            "update_count": None, "history_error": True,
            "updated": False, "updated_head_sha": None,
        }]
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  unreadable target history remains unverified")
    def capped_count(*_args):
        return 2
    with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                "get": fixture_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                                "branch_update_count": capped_count,
                                "api": lambda *_args, **_kwargs: (_ for _ in ()).throw(
                                    AssertionError("exhausted budget called update-branch"))}):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = run("example/repo", "main", 5, 0, False, 20, max_updates_per_pr=2)
        ok = (result == 0 and "branch-history update budget exhausted (2/2)" in output.getvalue()
              and "push a substantive author change" in output.getvalue())
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  exhausted budget never calls update-branch")
    # One unreadable PR must not poison the run or prevent an unrelated green PR.
    import tempfile
    def two_pr_get(path: str) -> object:
        if "/pulls?" in path:
            return [{"number": n, "auto_merge": {"merge_method": "squash"}, "draft": False}
                    for n in (42, 43)]
        if path.endswith("/pulls/43"):
            return {**fixture_get("/pulls/42"), "head": {"sha": "b" * 40, "ref": "codex/other",
                       "repo": {"full_name": "example/repo"}}}
        if "/commits/" in path:
            return {"commit": {"committer": {"date": old.isoformat()}}}
        return fixture_get(path)
    def one_unreadable(_repo: str, number: int, *_args) -> int:
        if number == 42:
            raise ValueError("#42: commit unavailable")
        return 0
    updated_numbers = []
    def put_update(_method: str, path: str, _body: dict | None = None):
        updated_numbers.append(int(path.split("/pulls/")[1].split("/")[0]))
        return 202, {}
    report = {"targets": []}
    with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                "get": two_pr_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                                "branch_update_count": lambda *_: 0, "api": put_update,
                                "observe_update": lambda *_: "c" * 40}), \
            contextlib.redirect_stdout(io.StringIO()):
        result = run("example/repo", "main", 1, 0, False, 20, recovery_report=report)
    ok = (result == 0 and [t["pr"] for t in report["targets"]] == [42, 43]
          and [t["updated"] for t in report["targets"]] == [True, False])
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  validated but cap-unselected target cannot recover")
    updated_numbers.clear()
    with tempfile.TemporaryDirectory() as tmp:
        summary_path = pathlib.Path(tmp) / "summary.md"
        with patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(summary_path)}), patch.dict(
                globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                            "get": two_pr_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                            "branch_update_count": one_unreadable, "api": put_update}):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = run("example/repo", "main", 5, 0, False, 20)
        summary = summary_path.read_text()
    ok = (result == 0 and updated_numbers == [43] and "#42: unreadable budget" in summary
          and "::warning::#42" in output.getvalue())
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  unreadable budget skips only #42; #43 updates; summary records hold")
    with tempfile.TemporaryDirectory() as tmp:
        summary_path = pathlib.Path(tmp) / "summary.md"
        with patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(summary_path)}), patch.dict(
                globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                            "get": fixture_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                            "branch_update_count": capped_count,
                            "api": lambda *_args, **_kwargs: (_ for _ in ()).throw(
                                AssertionError("exhausted budget called update-branch"))}):
            with contextlib.redirect_stdout(io.StringIO()):
                result = run("example/repo", "main", 5, 0, False, 20, max_updates_per_pr=2)
        summary = summary_path.read_text()
    ok = result == 0 and "#42: branch-history update budget exhausted (2/2)" in summary \
        and "normal review and CI" in summary
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  exhausted budget summary gives unblock instruction")
    with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], False),
                                "get": lambda *_: (_ for _ in ()).throw(
                                    AssertionError("non-strict mode enumerated PRs"))}):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = run("example/repo", "main", 5, 0, False, 20)
        ok = result == 0 and "strict up-to-date checks disabled" in output.getvalue()
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  non-strict mode exits before PR enumeration")
    # Full-fleet serialization (plan()).
    t0 = "2026-09-01T00:00:00Z"
    def E(n, kind, ok=True, building=False, since=t0):
        return {"number": n, "ok": ok, "why": "green", "kind": kind, "building": building,
                "waiting_since": since}
    pcases = [
        ("two full-fleet green -> only oldest-armed updated",
         [E(10, "full-fleet", since="2026-09-02T00:00:00Z"), E(20, "full-fleet", since=t0)], 5,
         {10: False, 20: True}),
        ("equal arm time -> lowest number wins",
         [E(30, "full-fleet"), E(20, "full-fleet")], 5, {30: False, 20: True}),
        ("one full-fleet in flight -> other full-fleet skipped",
         [E(1, "full-fleet", ok=False, building=True), E(2, "full-fleet")], 5, {1: False, 2: False}),
        ("exhausted full-fleet PR does not starve next candidate",
         [E(1, "full-fleet", ok=False), E(2, "full-fleet")], 5, {1: False, 2: True}),
        ("small PRs unaffected by in-flight full-fleet",
         [E(1, "full-fleet", ok=False, building=True), E(3, "small"), E(4, "small")], 5,
         {1: False, 3: True, 4: True}),
        ("unreadable classification -> small",
         [E(1, "full-fleet", ok=False, building=True), E(5, "unknown")], 5, {1: False, 5: True}),
        ("new small PRs cannot consume the cap ahead of older armed work",
         [E(30, "small", since="2026-09-03T00:00:00Z"),
          E(20, "small", since="2026-09-02T00:00:00Z"), E(10, "small")], 2,
         {30: False, 20: True, 10: True}),
        ("equal small-PR arm time uses immutable PR number",
         [E(30, "small"), E(20, "small")], 1, {30: False, 20: True}),
        ("unknown-sized PR respects the same waiting order",
         [E(30, "unknown", since="2026-09-03T00:00:00Z"), E(10, "small")], 1,
         {30: False, 10: True}),
        ("ineligible older PR does not consume the cap",
         [E(30, "small", since="2026-09-03T00:00:00Z"), E(10, "small", ok=False)], 1,
         {30: True, 10: False}),
        ("cap still applies", [E(3, "small"), E(4, "small")], 1, {3: True, 4: False}),
    ]
    for name, entries, cap, want in pcases:
        got = {n: u for n, u, _ in plan(entries, cap)}
        ok = got == want
        failures += not ok
        reasons = "; ".join(f"#{n}={'UPDATE' if u else 'skip'} ({w})" for n, u, w in plan(entries, cap))
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {reasons}")
    green_fleet = {f"build (openbank-s{i}-service) / openbank-s{i}-service (build)": "success" for i in range(25)}
    ccases = [
        ("25 build modules -> full-fleet", green_fleet, ("full-fleet", False)),
        ("one queued -> building", {**green_fleet, "build (openbank-s0-service) / x (contract)": None},
         ("full-fleet", True)),
        ("2 build modules -> small", dict(list(green_fleet.items())[:2]), ("small", False)),
        ("no build checks -> unknown", {"Gitleaks": "success"}, ("unknown", False)),
    ]
    for name, checks, want in ccases:
        got = classify(checks, 20)
        ok = got == want
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  classify {name}: {got}")
    print(f"self-test: {failures} failure(s)")
    return 1 if failures else 0


# --------------------------------------------------------------------------- declaration gate
DECLARATION = [
    ("app token minted", "actions/create-github-app-token@"),
    ("script driven with the app token", "GH_TOKEN: ${{ steps.app_token.outputs.token }}"),
    ("runs this script", "auto-update-armed-prs.py"),
    ("push-to-main trigger", "branches: [main]"),
    ("schedule fallback", "cron:"),
    ("concurrency cancels in progress", "cancel-in-progress: true"),
    ("ambient token read-only", "contents: read"),
    ("persistent per-PR budget wired", "--max-updates-per-pr 4"),
]


def check_declaration() -> int:
    if not WORKFLOW.is_file():
        print(f"::error::{WORKFLOW} is missing")
        print("SUBJECTS=0")
        return 1
    text = WORKFLOW.read_text()
    bad = 0
    for label, needle in DECLARATION:
        present = needle in text
        bad += not present
        print(f"{'ok  ' if present else 'MISS'}  {label}: {needle!r}")
    # GITHUB_TOKEN must never be what drives the update: its pushes trigger no workflows.
    if "GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}" in text or "GH_TOKEN: ${{ github.token }}" in text:
        print("MISS  update driven by GITHUB_TOKEN — CI would never re-run on the new head")
        bad += 1
    src = pathlib.Path(__file__).read_text()
    for label, needle in [("expected_head_sha sent", '"expected_head_sha"'),
                          ("required contexts read live", "/rules/branches/")]:
        present = needle in src
        bad += not present
        print(f"{'ok  ' if present else 'MISS'}  {label}")
    print(f"SUBJECTS={len(DECLARATION) + 3}")
    return 1 if bad else 0


# --------------------------------------------------------------------------- live run
def api(method: str, path: str, body: dict | None = None) -> tuple[int, object]:
    req = urllib.request.Request(API + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("Authorization", f"Bearer {os.environ['GH_TOKEN']}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:300]


def get(path: str) -> object:
    code, data = api("GET", path)
    if code != 200:
        raise SystemExit(f"::error::GET {path} -> {code}: {data}")
    return data


def required_policy(repo: str, branch: str) -> tuple[list[str], bool]:
    rules = get(f"/repos/{repo}/rules/branches/{branch}")
    ctx: list[str] = []
    strict = False
    for r in rules:
        if r.get("type") == "required_status_checks":
            parameters = r["parameters"]
            ctx += [c["context"] for c in parameters["required_status_checks"]]
            strict |= parameters.get("strict_required_status_checks_policy") is True
    return sorted(set(ctx)), strict


def head_checks(repo: str, sha: str) -> dict[str, str | None]:
    out: dict[str, str | None] = {}
    page = 1
    while True:
        data = get(f"/repos/{repo}/commits/{sha}/check-runs?per_page=100&page={page}")
        # Newest first per name: a re-run supersedes the earlier attempt.
        for run in sorted(data["check_runs"], key=lambda r: r.get("started_at") or "", reverse=True):
            out.setdefault(run["name"], run["conclusion"] if run["status"] == "completed" else None)
        if len(data["check_runs"]) < 100:
            break
        page += 1
    for st in get(f"/repos/{repo}/commits/{sha}/status")["statuses"]:
        if st["context"] not in out:
            out[st["context"]] = None if st["state"] == "pending" else st["state"]
    return out


def parse_ts(s: str) -> dt.datetime:
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def head_changed_since(repo: str, number: int, expected_head: str) -> bool:
    """A 422 can be a stale expected SHA; prove movement before treating it as a race."""
    try:
        code, data = api("GET", f"/repos/{repo}/pulls/{number}")
    except (OSError, ValueError):
        return False
    head = data.get("head") if isinstance(data, dict) else None
    sha = head.get("sha") if isinstance(head, dict) else None
    return (code == 200 and isinstance(sha, str)
            and re.fullmatch(r"[0-9a-f]{40}", sha) is not None and sha != expected_head)


def branch_update_count(repo: str, number: int, branch: str, limit: int,
                        expected_head: str) -> int:
    """Count consecutive GitHub base merges on the head's first-parent chain.

    Never scan PR commits: GitHub caps that endpoint at 250 entries. A regular author
    commit ends the streak. Validate each SHA and edge, fail closed on unreadable data,
    and stop after at most limit+1 reads even when the PR has thousands of commits.
    """
    if limit < 0 or not isinstance(expected_head, str) or not re.fullmatch(r"[0-9a-f]{40}", expected_head):
        raise ValueError(f"#{number}: invalid branch-update budget or head")
    sha = expected_head
    seen: set[str] = set()
    marker = f"Merge branch '{branch}' into "
    for used in range(limit + 1):
        if sha in seen:
            raise ValueError(f"#{number}: cyclic first-parent chain")
        seen.add(sha)
        try:
            code, commit = api("GET", f"/repos/{repo}/commits/{sha}")
        except (OSError, ValueError) as error:
            raise ValueError(f"#{number}: commit {sha[:9]} is unreadable: {error}") from error
        if code != 200 or not isinstance(commit, dict) or commit.get("sha") != sha:
            raise ValueError(f"#{number}: commit {sha[:9]} is unreadable")
        details = commit.get("commit")
        parents = commit.get("parents")
        if not isinstance(details, dict) or not isinstance(parents, list):
            raise ValueError(f"#{number}: malformed commit {sha[:9]}")
        message = details.get("message")
        committer = details.get("committer")
        verification = details.get("verification")
        if (not isinstance(message, str) or not isinstance(committer, dict)
                or not isinstance(committer.get("name"), str)
                or not isinstance(committer.get("email"), str)
                or not isinstance(verification, dict)
                or not isinstance(verification.get("verified"), bool)
                or any(not isinstance(p, dict) or not isinstance(p.get("sha"), str)
                       or not re.fullmatch(r"[0-9a-f]{40}", p["sha"]) for p in parents)):
            raise ValueError(f"#{number}: malformed commit metadata {sha[:9]}")
        is_update = (message.partition("\n")[0].startswith(marker)
                     and committer.get("name") == "GitHub"
                     and committer.get("email") == "noreply@github.com"
                     and verification.get("verified") is True
                     and len(parents) == 2)
        if not is_update:
            return used
        if used == limit:
            return used
        parent = parents[0]
        sha = parent.get("sha") if isinstance(parent, dict) else None
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError(f"#{number}: malformed first parent")
    raise AssertionError("bounded branch-update count did not terminate")


def budget_instruction(number: int, used: int, limit: int) -> str:
    return (f"#{number}: branch-history update budget exhausted ({used}/{limit}); "
            "push a substantive author change or rebase the branch to reset the tip streak, "
            "then obtain normal review and CI")


def observe_update(repo: str, number: int, old_sha: str, branch: str,
                   attempts: int = 6, delay_seconds: float = 2) -> str | None:
    """A 202 only queues the update; prove the head became its base-merge child."""
    for attempt in range(attempts):
        if attempt:
            time.sleep(delay_seconds)
        try:
            code, pr = api("GET", f"/repos/{repo}/pulls/{number}")
        except OSError:
            continue
        if code != 200 or not isinstance(pr, dict):
            continue
        head = pr.get("head")
        new_sha = head.get("sha") if isinstance(head, dict) else None
        new_sha = new_sha.lower() if isinstance(new_sha, str) else ""
        if new_sha == old_sha or not re.fullmatch(r"[0-9a-f]{40}", new_sha):
            continue
        try:
            code, commit = api("GET", f"/repos/{repo}/commits/{new_sha}")
        except OSError:
            continue
        if code != 200:
            continue
        details = commit.get("commit", {}) if isinstance(commit, dict) else {}
        committer = details.get("committer") if isinstance(details, dict) else None
        verification = details.get("verification") if isinstance(details, dict) else None
        parents = commit.get("parents") if isinstance(commit, dict) else None
        message = details.get("message") if isinstance(details, dict) else None
        if (isinstance(commit, dict) and commit.get("sha") == new_sha
                and isinstance(message, str)
                and message.partition("\n")[0].startswith(f"Merge branch '{branch}' into ")
                and isinstance(committer, dict) and committer.get("name") == "GitHub"
                and committer.get("email") == "noreply@github.com"
                and isinstance(verification, dict) and verification.get("verified") is True
                and isinstance(parents, list) and len(parents) == 2
                and isinstance(parents[0], dict) and parents[0].get("sha") == old_sha
                and isinstance(parents[1], dict)
                and re.fullmatch(r"[0-9a-f]{40}", parents[1].get("sha", ""))):
            return new_sha
        # Another actor moved this branch; that is not proof our request completed.
        return None
    return None


def write_budget_summary(lines: list[str]) -> None:
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if not target or not lines:
        return
    with open(target, "a", encoding="utf-8") as summary:
        summary.write("\n### Armed PR branch-update budget holds\n\n")
        for line in lines:
            summary.write(f"- {line}\n")


def run(repo: str, branch: str, max_updates: int, debounce: int, dry_run: bool,
        fleet_threshold: int, max_updates_per_pr: int = 4,
        recovery_report: dict | None = None) -> int:
    required, strict = required_policy(repo, branch)
    print(f"required contexts on {branch} (live): {required}; strict={strict}")
    if not required:
        print("::error::no required contexts resolved — refusing to act on an empty set")
        return 1
    if not strict:
        print("strict up-to-date checks disabled; no branch update is needed")
        return 0
    now = dt.datetime.now(dt.timezone.utc)
    pulls = get(f"/repos/{repo}/pulls?state=open&base={branch}&per_page=100")
    candidates = [p for p in pulls
                  if not p.get("draft") and (p.get("auto_merge") or is_bot_deploy_pr(p))]
    print(f"{len(pulls)} open PR(s) against {branch}, "
          f"{len(candidates)} armed-or-bot-deploy and non-draft")
    failed_updates = 0
    entries, shas, budgets, budget_holds = [], {}, {}, []
    for p in candidates:
        n = p["number"]
        budget_error = False
        full = get(f"/repos/{repo}/pulls/{n}")
        sha = shas[n] = full["head"]["sha"]
        pr = {"state": full.get("state"), "draft": full.get("draft"),
              "labels": full.get("labels"), "auto_merge": full.get("auto_merge"),
              "bot_deploy": is_bot_deploy_pr(full),
              "same_repo": (full["head"].get("repo") or {}).get("full_name") == repo,
              "mergeable_state": full.get("mergeable_state")}
        if pr["same_repo"] and pr["mergeable_state"] in (None, "unknown"):
            behind_by = (get(f"/repos/{repo}/compare/{branch}...{sha}") or {}).get("behind_by")
            settled = resolve_state(pr["mergeable_state"], full.get("mergeable"), behind_by)
            print(f"STATE  #{n} {sha[:9]}: mergeable_state={pr['mergeable_state']}, "
                  f"behind_by={behind_by} -> {settled}")
            pr["mergeable_state"] = settled
        kind, building = "unknown", False
        if not pr["same_repo"]:
            ok, why = decide(pr, required, {}, now, None, debounce)
        else:
            # Checks are read for every armed PR, behind or not: an up-to-date full-fleet PR
            # whose build is running is exactly the "in flight" one that blocks the others.
            checks = head_checks(repo, sha)
            kind, building = classify(checks, fleet_threshold)
            if pr["mergeable_state"] != "behind":
                ok, why = decide(pr, required, {}, now, None, debounce)
            else:
                commit = get(f"/repos/{repo}/commits/{sha}")
                committed = parse_ts(commit["commit"]["committer"]["date"])
                ok, why = decide(pr, required, checks, now, committed, debounce)
        if ok:
            try:
                used = branch_update_count(repo, n, branch, max_updates_per_pr, sha)
            except ValueError as error:
                budget_error = True
                ok, why = False, f"unreadable update budget: {error}"
                budget_holds.append(f"#{n}: unreadable budget; inspect its first-parent history before updating")
                print(f"::warning::{error}; skipping this PR only")
            else:
                budgets[n] = used
                if used >= max_updates_per_pr:
                    ok, why = False, budget_instruction(n, used, max_updates_per_pr)
                    budget_holds.append(why)
        since = (full["auto_merge"] or {}).get("enabled_at") or full.get("created_at") or ""
        print(f"CLASS  #{n} {sha[:9]}: {kind}{' (no build-matrix check runs: none selected or not started -> small)' if kind == 'unknown' else ''}"
              f"{', build in flight' if building else ''}, armed since {since or '?'}")
        entries.append({"number": n, "ok": ok, "why": why, "kind": kind, "building": building,
                        "waiting_since": since})
        if recovery_report is not None:
            # This is evidence of reading the target's complete branch history, not evidence
            # that a different PR was updated in the same successful workflow job.
            recovery_report["targets"].append({
                "pr": n, "head_sha": sha, "budget_validated": n in budgets,
                "update_count": budgets.get(n), "history_error": budget_error,
                "updated": False, "updated_head_sha": None,
            })
    updated = 0
    for n, ok, why in plan(entries, max_updates):
        sha = shas[n]
        if not ok:
            print(f"SKIP   #{n} {sha[:9]}: {why}")
            continue
        used = budgets[n]
        if dry_run:
            print(f"DRY    #{n} {sha[:9]}: would update — {why} "
                  f"(budget {used}/{max_updates_per_pr})")
            updated += 1
            continue
        try:
            live = get(f"/repos/{repo}/pulls/{n}")
        except (OSError, SystemExit) as error:
            print(f"SKIP   #{n} {sha[:9]}: live PR readiness could not be verified ({error})")
            continue
        hold = readiness_hold(live)
        live_head = live.get("head") if isinstance(live, dict) else None
        live_sha = live_head.get("sha") if isinstance(live_head, dict) else None
        live_repo = live_head.get("repo") if isinstance(live_head, dict) else None
        if hold:
            print(f"SKIP   #{n} {sha[:9]}: live PR hold — {hold}")
            continue
        if live_sha != sha:
            print(f"SKIP   #{n} {sha[:9]}: live head changed after planning")
            continue
        if not isinstance(live_repo, dict) or live_repo.get("full_name") != repo:
            print(f"SKIP   #{n} {sha[:9]}: live head is not in this repository")
            continue
        live_base = live.get("base")
        if not isinstance(live_base, dict) or live_base.get("ref") != branch:
            print(f"SKIP   #{n} {sha[:9]}: live base changed after planning")
            continue
        if not live.get("auto_merge") and not is_bot_deploy_pr(live):
            print(f"SKIP   #{n} {sha[:9]}: no longer armed and not an eligible bot deploy PR")
            continue
        code, data = api("PUT", f"/repos/{repo}/pulls/{n}/update-branch", {"expected_head_sha": sha})
        if code == 202:
            updated += 1
            if recovery_report is not None:
                observed = observe_update(repo, n, sha, branch)
                if observed:
                    target = next(t for t in recovery_report["targets"] if t["pr"] == n)
                    target["updated"] = True
                    target["updated_head_sha"] = observed
                else:
                    print(f"::warning::#{n}: update accepted but target head transition unverified")
            print(f"UPDATE #{n} {sha[:9]}: {why} "
                  f"(budget {used + 1}/{max_updates_per_pr})")
        elif code == 409 or (
            code == 422 and isinstance(data, dict) and isinstance(data.get("message"), str)
            and data["message"] in (
                "expected head sha didn’t match current head ref.",
                "expected head sha didn't match current head ref.",
            )
        ):
            # Only the known expected-head precondition race is benign; all other 422s fail.
            # Never retry against the new head: the next run must recheck CI and budget.
            print(f"SKIP   #{n} {sha[:9]}: head changed during update ({code})")
        else:
            failed_updates += 1
            print(f"::error::FAILED #{n} {sha[:9]}: update-branch -> {code}: {data}")
    write_budget_summary(budget_holds)
    print(f"done: {updated} update(s), {failed_updates} failed update(s), "
          f"{len(budget_holds)} budget hold(s)")
    return 1 if failed_updates else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-declaration", action="store_true")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--branch", default="main")
    ap.add_argument("--max-updates", type=int, default=5)
    ap.add_argument("--max-updates-per-pr", type=int, default=4,
                    help="cap on consecutive server-side base merges at the PR head")
    ap.add_argument("--debounce-minutes", type=int, default=20)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--recovery-report", type=pathlib.Path,
                    help="run-bound per-PR history evidence for main-red recovery")
    ap.add_argument("--full-fleet-threshold", type=int, default=20,
                    help="distinct build-matrix modules at which a PR counts as full-fleet")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.check_declaration:
        return check_declaration()
    if not a.repo or not os.environ.get("GH_TOKEN"):
        print("::error::--repo (or GITHUB_REPOSITORY) and GH_TOKEN are required")
        return 1
    if a.max_updates < 0 or a.max_updates_per_pr < 0:
        print("::error::update caps must be nonnegative")
        return 1
    report = {
        "version": 1,
        "run_id": int(os.environ.get("GITHUB_RUN_ID", "0")),
        "attempt": int(os.environ.get("GITHUB_RUN_ATTEMPT", "0")),
        "main_sha": os.environ.get("GITHUB_SHA", ""),
        "dry_run": a.dry_run,
        "complete": False,
        "successful": False,
        "targets": [],
    }
    result = run(a.repo, a.branch, a.max_updates, a.debounce_minutes, a.dry_run,
                 a.full_fleet_threshold, a.max_updates_per_pr, recovery_report=report)
    report["complete"] = True
    report["successful"] = result == 0
    if a.recovery_report:
        a.recovery_report.write_text(json.dumps(report, sort_keys=True) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    sys.exit(main())
