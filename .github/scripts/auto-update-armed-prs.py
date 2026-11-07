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
For every open, non-draft, same-repository PR with auto-merge armed — plus deploy-bot PRs
(`chore/gitops-auto-deploy-*` / `chore/admin-ui-deploy-*` opened by the gitops bot), which ship
user-visible features and are updated the same way even when nobody armed auto-merge on them —
whose `mergeable_state` is `behind`, it calls
`PUT /repos/{o}/{r}/pulls/{n}/update-branch` with `expected_head_sha`. Everything else is left
alone, with one log line per decision:

  * required checks pending/failed -> skip (updating would discard a verdict in flight,
    or refresh a PR that cannot merge anyway);
  * required check missing or cancelled -> UPDATE: under the strict up-to-date policy the PR is
    stranded either way, and a branch update is exactly what re-registers the context. #12928
    rotted behind main for hours because security checks cancelled at creation read as
    "missing" and the old code kept skipping — the chicken-and-egg case this rule exists for;
  * `dirty` (conflicts)                    -> skip, a human has to resolve those;
  * not armed                              -> skip, the author has not asked for a merge;
  * head committed < --debounce-minutes ago -> skip, so CI is not restarted repeatedly;
  * more than --max-updates in this run    -> skip, capped;
  * branch-update budget exhausted         -> skip until the author pushes; listed in the summary.

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
The global lane additionally admits at most one eligible PR of any size per invocation.
Fresh current-main/head CI reservations carry that admission across invocations: required
pending or a mapped primary CI attempt reserves it; an independent terminal required failure
releases it. Ready, clean, armed heads reserve only for the existing debounce interval.
Explicit blocked/draft PRs and unarmed human work are outside this updater's admission lane;
this is not a host-wide runner scheduler and cannot control external merges or manual builds.
Unarmed generated deployment PRs retain existing eligibility and reserve pending CI, but do
not get a ready-to-merge lease. The existing full-fleet constraint remains an additional filter.

THE TOKEN MATTERS
-----------------
The update MUST be made with the GitHub App installation token. A push made with the workflow's
GITHUB_TOKEN does not trigger workflows, so CI would never re-run on the new head and the PR
would wedge on the required checks instead of on `behind`. The merge commit `update-branch`
creates is made server-side (committer `GitHub`) and GitHub signs it, which `required_signatures`
needs: see the PR that introduced this script for the measured evidence.

HARD COST BUDGET
----------------
`--max-updates` limits one workflow run only. `--max-updates-per-pr` caps CONSECUTIVE
server-side base merges at the tip of the head (walked along first parents, at most cap + 1
commit reads), across workflow runs. Any non-updater commit — the author pushing, or merging or
rebasing main themselves — resets it. At the cap the PR is skipped with the exact unblock action,
and listed in the job summary. An unreadable budget skips THAT PR (fail closed) without failing
the run; only a refused update-branch call fails the workflow.

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
import urllib.parse
import urllib.request

API = os.environ.get("GITHUB_API_URL", "https://api.github.com")
WORKFLOW = pathlib.Path(".github/workflows/auto-update-armed-prs.yml")
OK_CONCLUSIONS = {"success", "neutral", "skipped"}


# Deploy-bot PRs ship user-facing features but nobody arms auto-merge on them; they are
# updated exactly like armed PRs. Author AND prefix are both required so a human branch can
# never qualify — feature PRs are never touched.
BOT_DEPLOY_PREFIXES = ("chore/gitops-auto-deploy-", "chore/admin-ui-deploy-")
BOT_AUTHOR = "app/openbank-gitops-bot"


def is_bot_deploy_pr(p: dict) -> bool:
    return (p.get("user", {}).get("login") == BOT_AUTHOR
            and any((p.get("head", {}).get("ref") or "").startswith(pre) for pre in BOT_DEPLOY_PREFIXES))


# --------------------------------------------------------------------------- pure decision
def decide(pr: dict, required: list[str], checks: dict[str, str | None], now: dt.datetime,
           head_committed_at: dt.datetime | None, debounce_minutes: int) -> tuple[bool, str]:
    """Return (update?, reason). `checks` maps context -> conclusion (None = not completed)."""
    if pr.get("draft"):
        return False, "draft"
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
    actor = "armed" if armed else "bot deploy PR (not armed)"
    # A context is "unregistered" when it is absent from `checks` (workflow path-filtered out
    # or never registered — e.g. security checks cancelled when the PR was created, so the
    # context never reports on the current head) or present with a `cancelled` conclusion.
    # Under the strict up-to-date policy such a PR is stranded either way, and a branch update
    # is exactly what re-registers the contexts. A verdict in flight (pending) or a real
    # failure always wins, though: updating would discard the one or churn a PR that cannot
    # merge anyway.
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
    Returns (number, update?, reason) in input order."""
    in_flight = sorted(e["number"] for e in entries if e["kind"] == "full-fleet" and e["building"])
    fleet_ok = sorted((e for e in entries if e["ok"] and e["kind"] == "full-fleet"),
                      key=lambda e: (e["waiting_since"], e["number"]))
    chosen = None if in_flight or not fleet_ok else fleet_ok[0]["number"]
    out, updated = [], 0
    for e in entries:
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
    base = {"draft": False, "auto_merge": {"merge_method": "squash"}, "same_repo": True,
            "mergeable_state": "behind"}
    bot_deploy = {**base, "auto_merge": None, "bot_deploy": True}
    cases = [
        ("behind+green+armed -> update", base, green, old, True),
        ("behind+pending -> skip", base, {**green, "Gitleaks": None}, old, False),
        ("behind+failed -> skip", base, {**green, "Gitleaks": "failure"}, old, False),
        ("behind+missing context -> update (re-registers)",
         base, {"Validate manifests": "success"}, old, True),
        ("behind+missing+pending -> skip (verdict in flight wins)",
         base, {"Validate manifests": "success", "Gitleaks": None}, old, False),
        ("behind+failed+missing -> skip (failure wins)",
         base, {"Validate manifests": "failure"}, old, False),
        ("behind+cancelled -> update (re-registers)",
         base, {**green, "Gitleaks": "cancelled"}, old, True),
        ("behind+green+bot deploy PR (not armed) -> update", bot_deploy, green, old, True),
        ("behind+unregistered+bot deploy PR (not armed) -> update",
         bot_deploy, {"Validate manifests": "success"}, old, True),
        ("not armed human PR -> skip", {**base, "auto_merge": None}, green, old, False),
        ("dirty -> skip", {**base, "mergeable_state": "dirty"}, green, old, False),
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
    # Recovery of unregistered contexts must obey the same debounce as a green head.
    # Cover both supported actors and real missing/cancelled states; a pending or failed
    # sibling still wins so recovery never discards a verdict in flight.
    for actor, pr in [("armed", base), ("bot deploy", bot_deploy)]:
        for state, checks in [
            ("missing", {"Validate manifests": "success"}),
            ("cancelled", {**green, "Gitleaks": "cancelled"}),
        ]:
            for age, want in [(0, False), (44, False), (45, True), (120, True)]:
                got, why = decide(pr, req, checks, now, now - dt.timedelta(minutes=age), 45)
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

    def graph(authored: int, updates: int, renamed_at: int | None = None) -> tuple[str, dict]:
        """A first-parent chain: `authored` author commits, then `updates` updater merges."""
        commits: dict[str, dict] = {}
        prev = None
        for i in range(authored + updates):
            sha = f"{i:040x}"
            parents = [{"sha": prev}] if prev else [{"sha": "f" * 40}]
            if i >= authored:
                into = "codex/renamed" if i == renamed_at else "agent/example"
                commits[sha] = {"commit": {"message": f"Merge branch 'main' into {into}\n\nbody",
                                           "committer": {"name": "GitHub"}},
                                "parents": parents + [{"sha": "e" * 40}]}
            else:
                commits[sha] = {"commit": {"message": f"feat: work {i}",
                                           "committer": {"name": "Author"}}, "parents": parents}
            prev = sha
        return prev, commits

    def graph_api(commits: dict, update_code: int = 202, reads: list | None = None,
                  update_data: object = None):
        def fake(method: str, path: str, body: dict | None = None) -> tuple[int, object]:
            if method == "GET" and "/commits/" in path:
                sha = path.rsplit("/", 1)[1]
                if reads is not None:
                    reads.append(sha)
                return (200, commits[sha]) if sha in commits else (404, "Not Found")
            if method == "PUT" and path.endswith("/update-branch"):
                return update_code, update_data if update_data is not None else ("denied" if update_code != 202 else {})
            raise AssertionError(f"unexpected API call {method} {path}")
        return fake

    # 320 author commits + 2 updater merges: >250, the size GitHub's PR-commits list truncates.
    head, commits = graph(320, 2, renamed_at=320)
    reads: list = []
    with patch.dict(globals(), {"api": graph_api(commits, reads=reads)}):
        used = branch_update_count("example/repo", head, "main", 4)
    ok = used == 2 and len(reads) == 3
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  322-commit PR readable: budget {used}, {len(reads)} reads")
    # An author push on top of earlier updates resets the budget.
    _, base_commits = graph(5, 3)
    tip = "a" * 40
    base_commits[tip] = {"commit": {"message": "fix: author push", "committer": {"name": "Author"}},
                         "parents": [{"sha": f"{7:040x}"}]}
    with patch.dict(globals(), {"api": graph_api(base_commits)}):
        used = branch_update_count("example/repo", tip, "main", 4)
    failures += used != 0
    print(f"{'PASS' if used == 0 else 'FAIL'}  author push resets the budget: {used}")
    with patch.dict(globals(), {"api": graph_api({})}):
        try:
            branch_update_count("example/repo", "d" * 40, "main", 4)
        except BudgetUnreadable:
            print("PASS  unreadable head fails closed (BudgetUnreadable)")
        else:
            failures += 1
            print("FAIL  unreadable head passed")

    def fixture_get(path: str) -> object:
        if "/git/ref/" in path:
            return {"object": {"sha": "main"}}
        if "/compare/" in path:
            return {"behind_by": 1}
        if "/pulls?" in path:
            return [{"number": 42, "auto_merge": {"merge_method": "squash"}, "draft": False}]
        if path.endswith("/pulls/42"):
            return {"number": 42, "state": "open", "labels": [], "draft": False, "auto_merge": {"merge_method": "squash"}, "mergeable_state": "behind",
                    "head": {"sha": fixture_head, "ref": "agent/example",
                             "repo": {"full_name": "example/repo"}}}
        if "/commits/" in path:
            return {"commit": {"committer": {"date": old.isoformat()}}}
        raise AssertionError(f"unexpected API read {path}")

    def run_fixture(commits: dict, update_code: int, update_data: object = None, **kw) -> tuple[int, str]:
        with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                    "get": fixture_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                                    "api": graph_api(commits, update_code, update_data=update_data)}):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = run("example/repo", "main", 5, 0, False, 20, **kw)
        return result, output.getvalue()

    fixture_head, fresh = graph(320, 0)
    for status, want in ((403, 1), (409, 0), (202, 0)):
        result, out = run_fixture(fresh, status)
        ok = result == want and (("::error::FAILED" in out) == (status == 403))
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  320-commit PR, update-branch HTTP {status} -> exit {result}")
    report = {"targets": []}
    with patch.dict(globals(), {"observe_update": lambda *_: "c" * 40}):
        result, out = run_fixture(fresh, 202, recovery_report=report)
    target_ok = (result == 0 and len(report["targets"]) == 1
                 and report["targets"][0]["budget_validated"] is True
                 and report["targets"][0]["updated"] is True
                 and report["targets"][0]["updated_head_sha"] == "c" * 40)
    failures += not target_ok
    print(f"{'PASS' if target_ok else 'FAIL'}  target evidence records only an observed update")
    # GitHub reports the expected-head precondition race as 422, not only 409.
    # Exercise the real response handler: no retry, changed SHA or generic 422 success.
    for label, data, want in [
        ("Unicode expected-head race", {"message": "expected head sha didn’t match current head ref."}, 0),
        ("ASCII expected-head race", {"message": "expected head sha didn't match current head ref."}, 0),
        ("generic validation", {"message": "Validation Failed"}, 1),
        ("different precondition", {"message": "expected head sha is invalid"}, 1),
        ("wrong schema", {"message": ["expected head sha didn’t match current head ref."]}, 1),
        ("plain text", "expected head sha didn’t match current head ref.", 1),
        ("no message", {"errors": ["head mismatch"]}, 1),
    ]:
        result, out = run_fixture(fresh, 422, update_data=data)
        ok = result == want and (("::error::FAILED" in out) == bool(want))
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  HTTP 422 {label} -> exit {result}")
    fixture_head, spent = graph(3, 5)
    result, out = run_fixture(spent, 500)  # 500 would fail the run if update-branch were called
    ok = (result == 0 and "update budget exhausted (4/4" in out and "push to the branch" in out
          and "::error::" not in out)
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  exhausted budget skips with unblock action, exit {result}")
    fixture_head = "d" * 40
    result, out = run_fixture({}, 500)
    ok = result == 0 and "update budget unreadable" in out and "::error::" not in out
    failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  unreadable budget skips that PR, exit {result}")
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
    workflow = WORKFLOW.read_text()
    ccases = [
        ("actual workflow cancels only for a newer push", workflow, True),
        ("constant true preempts fallbacks", workflow.replace(CANCEL_ONLY_PUSH, "true"), False),
        ("constant false loses newer-push cancellation", workflow.replace(CANCEL_ONLY_PUSH, "false"), False),
        ("wrong event cancels scheduled work", workflow.replace("github.event_name == 'push'", "github.event_name == 'schedule'"), False),
        ("wrong global group loses serialization", workflow.replace("group: auto-update-armed-prs", "group: another-group"), False),
        ("comment cannot satisfy the declaration", workflow.replace(CANCEL_ONLY_PUSH, "true")
         + f"\n# cancel-in-progress: {CANCEL_ONLY_PUSH}\n", False),
        ("duplicate cancellation key is rejected", workflow.replace(
            f"cancel-in-progress: {CANCEL_ONLY_PUSH}", f"cancel-in-progress: {CANCEL_ONLY_PUSH}\n  cancel-in-progress: true"), False),
    ]
    for name, text, want in ccases:
        got = push_only_cancellation_declared(text)
        ok = got == want
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {got}")
    print(f"self-test: {failures} failure(s)")
    return 1 if failures else 0


# --------------------------------------------------------------------------- declaration gate
DECLARATION = [
    ("app token minted", "actions/create-github-app-token@"),
    ("script driven with the app token", "GH_TOKEN: ${{ steps.app_token.outputs.token }}"),
    ("runs this script", "auto-update-armed-prs.py"),
    ("push-to-main trigger", "branches: [main]"),
    ("schedule fallback", "cron:"),
    ("ambient token read-only", "contents: read"),
    ("persistent per-PR budget wired", "--max-updates-per-pr 4"),
]


CANCEL_ONLY_PUSH = "${{ github.event_name == 'push' }}"


def push_only_cancellation_declared(text: str) -> bool:
    """Require the exact global concurrency contract, not a matching comment."""
    lines = text.splitlines()
    starts = [index for index, line in enumerate(lines) if line == "concurrency:"]
    if len(starts) != 1:
        return False
    entries = []
    for line in lines[starts[0] + 1:]:
        if line and not line[0].isspace() and not line.startswith("#"):
            break
        if line.strip() and not line.lstrip().startswith("#"):
            entries.append(line.strip())
    return entries == ["group: auto-update-armed-prs", f"cancel-in-progress: {CANCEL_ONLY_PUSH}"]


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
    cancellation = push_only_cancellation_declared(text)
    bad += not cancellation
    print(f"{'ok  ' if cancellation else 'MISS'}  only a new push cancels admitted work; one global group")
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
    print(f"SUBJECTS={len(DECLARATION) + 4}")
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
    latest = {}
    page = 1
    while True:
        checks = get(f"/repos/{repo}/commits/{sha}/check-runs?per_page=100&page={page}")["check_runs"]
        for check in checks:
            if check["name"] not in latest or check["id"] > latest[check["name"]]["id"]:
                latest[check["name"]] = check
        if len(checks) < 100:
            break
        page += 1
    out = {name: check["conclusion"] if check["status"] == "completed" else None
           for name, check in latest.items()}
    for status in sorted(get(f"/repos/{repo}/commits/{sha}/status")["statuses"],
                         key=lambda st: st["id"], reverse=True):
        out.setdefault(status["context"], None if status["state"] == "pending" else status["state"])
    return out


# Stateless admission: fresh immutable-head reservations across invocations.
PRIMARY_CI_PATHS = {".github/workflows/services-ci.yml", ".github/workflows/ci.yml"}


def global_plan(entries: list[dict], max_updates: int) -> list[tuple[int, bool, str]]:
    """One admission across cron runs, using freshly read immutable-head reservations."""
    blockers = [e for e in entries if e.get("reservation") in ("reserved", "unknown")]
    eligible = sorted((e for e in entries if e["ok"] and not e.get("held")),
                      key=lambda e: (e["waiting_since"], e["number"]))
    chosen = eligible[0]["number"] if eligible and max_updates > 0 and not blockers else None
    result = []
    for e in entries:
        ok = e["ok"] and not e.get("held") and e["number"] == chosen
        why = e["why"]
        if e.get("held"):
            why = "explicit hold — excluded"
        elif e["ok"] and blockers:
            why = f"global refresh slot unavailable: #{blockers[0]['number']} {blockers[0]['reservation']}"
        elif e["ok"] and not ok:
            why = "global refresh admission: another eligible PR has waited longer or cap is zero"
        result.append((e["number"], ok, why))
    return result


def check_has_active_retry(repo: str, number: int, sha: str, check: dict,
                                  latest_runs: dict, reader) -> bool:
    """Recognize a proven newer attempt of this required check's actual producer.

    Check IDs do not encode workflow attempts. The Actions job endpoint provides
    run_attempt; the run endpoint binds that attempt to a workflow ID and PR.
    A queued independent workflow must never erase a required failure.
    """
    url = urllib.parse.urlsplit(check.get("details_url", ""))
    prefix = f"/{repo}/actions/runs/"
    if url.scheme != "https" or url.netloc != "github.com" or not url.path.startswith(prefix):
        return False
    parts = url.path[len(prefix):].split("/")
    if len(parts) != 3 or parts[1] != "job" or not parts[0].isdigit() or not parts[2].isdigit():
        return False
    run_id, job_id = int(parts[0]), int(parts[2])
    job = reader(f"/repos/{repo}/actions/jobs/{job_id}")
    if (job["id"] != job_id or job["run_id"] != run_id or job["head_sha"] != sha
            or job["check_run_url"] != f"{API}/repos/{repo}/check-runs/{check['id']}"):
        return False
    old = reader(f"/repos/{repo}/actions/runs/{run_id}")
    if (old["id"] != run_id or old["head_sha"] != sha or old["event"] != "pull_request"
            or old["repository"]["full_name"] != repo
            or not any(p["number"] == number for p in old["pull_requests"])):
        return False
    current = latest_runs.get(old["path"])
    return bool(current and current["workflow_id"] == old["workflow_id"]
                and current["status"] != "completed"
                and (current["id"], current["run_attempt"]) > (run_id, job["run_attempt"]))


def read_reservation(repo: str, number: int, main_sha: str, required: list[str],
                     now: dt.datetime, debounce_minutes: int, reader=get) -> dict:
    """Fresh native-required bindings plus primary CI discovery; never infer prose holds."""
    # A check's job/run may be shared by many required contexts; cache only within
    # this immutable-head observation, never across invocations or pre-PUT snapshots.
    raw_reader, cache = reader, {}
    def reader(path):
        if path not in cache:
            cache[path] = raw_reader(path)
        return cache[path]
    result = {"number": number, "main_snapshot": main_sha, "reservation": "none", "held": False}
    try:
        pr = reader(f"/repos/{repo}/pulls/{number}")
        result["head"] = sha = pr["head"]["sha"]
        result["held"] = pr["draft"] or any(x["name"] == "blocked" for x in pr["labels"])
        if (pr["state"] != "open" or result["held"] or not (pr["auto_merge"] or is_bot_deploy_pr(pr))
                or (pr["head"].get("repo") or {}).get("full_name") != repo):
            return result
        compare = reader(f"/repos/{repo}/compare/{main_sha}...{sha}")
        if type(compare["behind_by"]) is not int or compare["behind_by"] < 0:
            raise ValueError("invalid behind count")
        if compare["behind_by"] != 0:
            return result
        latest = {}
        page = 1
        while True:
            checks = reader(f"/repos/{repo}/commits/{sha}/check-runs?per_page=100&page={page}")["check_runs"]
            for check in checks:
                if check["name"] not in latest or check["id"] > latest[check["name"]]["id"]:
                    latest[check["name"]] = check
            if len(checks) < 100:
                break
            page += 1
        # Preserve legacy commit-status contexts, but never replace a check-run verdict.
        for status in sorted(reader(f"/repos/{repo}/commits/{sha}/status")["statuses"],
                             key=lambda s: s["id"], reverse=True):
            if status["context"] not in latest:
                latest[status["context"]] = {
                    "id": status["id"], "name": status["context"],
                    "status": "in_progress" if status["state"] == "pending" else "completed",
                    "conclusion": None if status["state"] == "pending" else status["state"],
                    "completed_at": status["updated_at"],
                }
        latest_runs = {}
        page = 1
        while True:
            runs = reader(f"/repos/{repo}/actions/runs?head_sha={sha}&event=pull_request&per_page=100&page={page}")["workflow_runs"]
            for run in runs:
                if (run["head_sha"] == sha and run["event"] == "pull_request"
                        and run["repository"]["full_name"] == repo
                        and any(p["number"] == number for p in run["pull_requests"])):
                    if not isinstance(run.get("status"), str) or not run["status"]:
                        raise ValueError("missing workflow run status")
                    key = run["path"]
                    if key not in latest_runs or (run["id"], run["run_attempt"]) > (latest_runs[key]["id"], latest_runs[key]["run_attempt"]):
                        latest_runs[key] = run
            if len(runs) < 100:
                break
            page += 1
        verdicts = [latest[c] for c in required if c in latest]
        # Derive producers from each native required check's exact job/run binding.
        # This includes Secret scan and any future required producer without treating
        # unrelated advisory runs as admission work. Success from an older attempt
        # must not let the ready lease expire while its real retry is still active.
        retry = {c["name"]: check_has_active_retry(repo, number, sha, c, latest_runs, reader)
                 for c in verdicts if c["status"] == "completed"}
        for check in verdicts:
            if (check["status"] == "completed" and check["conclusion"] not in OK_CONCLUSIONS
                    and not retry[check["name"]]):
                return result  # An independent/current required failure still releases.
        if any(c["status"] != "completed" for c in verdicts) or any(retry.values()):
            result["reservation"] = "reserved"
            return result
        # A same-head rerun can be queued before its new checks replace old success.
        # Always read mapped primary runs before applying the bounded ready lease.
        primary_runs = {p: r for p, r in latest_runs.items() if p in PRIMARY_CI_PATHS}
        if any(r["status"] != "completed" for r in primary_runs.values()):
            result["reservation"] = "reserved"
            return result
        if required and len(verdicts) == len(required):
            if not pr["auto_merge"] or pr.get("mergeable_state") != "clean":
                return result
            # Existing debounce bounds a ready lease; no permanent all-green lock.
            completed = max(parse_ts(c["completed_at"]) for c in verdicts)
            if (now - completed).total_seconds() < debounce_minutes * 60:
                result["reservation"] = "reserved"
            return result
        if primary_runs and all(r["conclusion"] not in OK_CONCLUSIONS for r in primary_runs.values()):
            result["reservation"] = "none"
        else:
            result["reservation"] = "unknown"  # No CI proof: fail closed, explicit starvation risk.
    except (KeyError, TypeError, ValueError, RuntimeError, OSError, SystemExit):
        result["reservation"] = "unknown"
    return result


def snapshot_unchanged(repo: str, branch: str, main_sha: str, number: int,
                       head_sha: str, reader=get) -> bool:
    """Prototype ref/authority drift guard; decide/checks/budget still required before PUT."""
    try:
        if reader(f"/repos/{repo}/git/ref/heads/{branch}")["object"]["sha"] != main_sha:
            return False
        pr = reader(f"/repos/{repo}/pulls/{number}")
        return (pr["state"] == "open" and not pr["draft"]
                and not any(label["name"] == "blocked" for label in pr["labels"])
                and pr["head"]["sha"] == head_sha
                and (pr["head"].get("repo") or {}).get("full_name") == repo
                and pr["mergeable_state"] == "behind"
                and bool(pr["auto_merge"] or is_bot_deploy_pr(pr)))
    except (KeyError, TypeError, ValueError, RuntimeError, OSError, SystemExit):
        return False


def parse_ts(s: str) -> dt.datetime:
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


class BudgetUnreadable(Exception):
    """The update budget could not be established; that one PR is skipped, fail closed."""


def is_updater_merge(commit: dict, branch: str) -> bool:
    """A server-side `update-branch` merge: GitHub-committed, two parents, base merged in."""
    body = commit.get("commit", {})
    return (body.get("message", "").partition("\n")[0].startswith(f"Merge branch '{branch}' into ")
            and body.get("committer", {}).get("name") == "GitHub"
            and len(commit.get("parents", [])) == 2)


def branch_update_count(repo: str, head_sha: str, branch: str, cap: int) -> int:
    """Count the CONSECUTIVE base merges at the tip of the head, along first parents.

    The budget is "CI restarts we caused since the author last pushed", read from the commit
    graph itself (so a new workflow run cannot reset it) and bounded by construction: at most
    cap + 1 commit reads, whatever the PR's size. The previous design listed
    `pulls/{n}/commits`, which GitHub truncates (100 per page, 250 in total), so every long-lived
    PR became permanently unreadable and failed the whole run. A commit that is not an updater
    merge (the author pushed, or merged/rebased main themselves) ends the walk and so resets the
    budget: that push is a deliberate human act, not an automated restart loop. Human presses of
    "Update branch" produce the same server-side merge and count too.
    """
    used, sha = 0, head_sha
    while used < cap:
        code, commit = api("GET", f"/repos/{repo}/commits/{sha}")
        if code != 200 or not isinstance(commit, dict) or not commit.get("parents"):
            raise BudgetUnreadable(f"commit {sha[:9]} unreadable ({code})")
        if not is_updater_merge(commit, branch):
            break
        used += 1
        sha = commit["parents"][0]["sha"]
    return used


def write_summary(lines: list[str]) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path or not lines:
        return
    with open(path, "a", encoding="utf-8") as fh:
        fh.write("### Armed PRs the updater will not touch\n\n" + "\n".join(lines) + "\n")


def open_pulls(repo: str, branch: str) -> list[dict]:
    pulls, page = [], 1
    while True:
        batch = get(f"/repos/{repo}/pulls?state=open&base={branch}&per_page=100&page={page}")
        pulls.extend(batch)
        if len(batch) < 100:
            return pulls
        page += 1


def explicit_hold(pr: dict) -> bool:
    return pr["draft"] or any(label["name"] == "blocked" for label in pr["labels"])


def evaluate_current(repo: str, branch: str, full: dict, required: list[str],
                     now: dt.datetime, debounce: int, fleet_threshold: int,
                     cap: int) -> dict:
    number, sha = full["number"], full["head"]["sha"]
    held = explicit_hold(full)
    pr = {"draft": full["draft"], "auto_merge": full["auto_merge"],
          "bot_deploy": is_bot_deploy_pr(full),
          "same_repo": (full["head"].get("repo") or {}).get("full_name") == repo,
          "mergeable_state": full.get("mergeable_state")}
    if full["state"] != "open" or held:
        return {"number": number, "head": sha, "held": held, "ok": False, "why": "closed or explicit hold",
                    "kind": "unknown", "building": False, "waiting_since": "", "budget": None}
    if pr["same_repo"] and pr["mergeable_state"] in (None, "unknown"):
        behind = get(f"/repos/{repo}/compare/{branch}...{sha}")["behind_by"]
        pr["mergeable_state"] = resolve_state(pr["mergeable_state"], full.get("mergeable"), behind)
    checks = head_checks(repo, sha) if pr["same_repo"] else {}
    kind, building = classify(checks, fleet_threshold)
    committed = None
    if pr["same_repo"] and pr["mergeable_state"] == "behind":
        committed = parse_ts(get(f"/repos/{repo}/commits/{sha}")["commit"]["committer"]["date"])
    ok, why = decide(pr, required, checks, now, committed, debounce)
    used = None
    history_error = False
    if ok:
        try:
            used = branch_update_count(repo, sha, branch, cap)
        except BudgetUnreadable as error:
            history_error = True
            ok, why = False, f"update budget unreadable ({error})"
        else:
            if used >= cap:
                ok, why = False, (f"update budget exhausted ({used}/{cap} consecutive base merges); "
                                  "to unblock, push to the branch only after checking why CI keeps losing the race")
    return {"number": number, "head": sha, "held": held, "ok": ok, "why": why, "kind": kind, "building": building,
                "waiting_since": (full["auto_merge"] or {}).get("enabled_at") or full.get("created_at") or "",
                "budget": used, "history_error": history_error}


def observe_update(repo: str, number: int, old_sha: str, branch: str,
                   attempts: int = 6, delay_seconds: float = 2) -> str | None:
    """Prove GitHub's queued update became a verified merge whose first parent is old_sha."""
    for attempt in range(attempts):
        if attempt:
            time.sleep(delay_seconds)
        try:
            code, pr = api("GET", f"/repos/{repo}/pulls/{number}")
        except (OSError, ValueError):
            continue
        head = pr.get("head") if code == 200 and isinstance(pr, dict) else None
        new_sha = head.get("sha") if isinstance(head, dict) else None
        if not isinstance(new_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", new_sha) or new_sha == old_sha:
            continue
        try:
            code, commit = api("GET", f"/repos/{repo}/commits/{new_sha}")
        except (OSError, ValueError):
            continue
        details = commit.get("commit") if code == 200 and isinstance(commit, dict) else None
        parents = commit.get("parents") if isinstance(commit, dict) else None
        committer = details.get("committer") if isinstance(details, dict) else None
        verification = details.get("verification") if isinstance(details, dict) else None
        message = details.get("message") if isinstance(details, dict) else None
        if (commit.get("sha") == new_sha and isinstance(message, str)
                and message.partition("\n")[0].startswith(f"Merge branch '{branch}' into ")
                and isinstance(committer, dict) and committer.get("name") == "GitHub"
                and committer.get("email") == "noreply@github.com"
                and isinstance(verification, dict) and verification.get("verified") is True
                and isinstance(parents, list) and len(parents) == 2
                and isinstance(parents[0], dict) and parents[0].get("sha") == old_sha
                and isinstance(parents[1], dict)
                and re.fullmatch(r"[0-9a-f]{40}", parents[1].get("sha", ""))):
            return new_sha
        return None
    return None


def admission_entries(repo: str, branch: str, main_sha: str, required: list[str],
                      now: dt.datetime, debounce: int, fleet_threshold: int, cap: int) -> list[dict]:
    entries = []
    for summary in open_pulls(repo, branch):
        if not (summary.get("auto_merge") or is_bot_deploy_pr(summary)):
            continue
        full = get(f"/repos/{repo}/pulls/{summary['number']}")
        entry = evaluate_current(repo, branch, full, required, now, debounce, fleet_threshold, cap)
        reservation = read_reservation(repo, full["number"], main_sha, required, now, debounce, reader=get)
        if reservation.get("head") != entry["head"]:
            reservation["reservation"] = "unknown"  # Head raced while assembling this snapshot.
        entry["reservation"] = reservation["reservation"]
        entries.append(entry)
    # Retain the original full-fleet serialization constraint before applying the global lane.
    native = {n: (ok, why) for n, ok, why in plan(entries, len(entries))}
    for entry in entries:
        entry["ok"], entry["why"] = native[entry["number"]]
    return entries


def run(repo: str, branch: str, max_updates: int, debounce: int, dry_run: bool,
        fleet_threshold: int, max_updates_per_pr: int = 4,
        recovery_report: dict | None = None) -> int:
    try:
        required, strict = required_policy(repo, branch)
    except (KeyError, TypeError, ValueError, RuntimeError, OSError, SystemExit) as error:
        print(f"::error::required policy read failed; no update: {error}")
        return 1
    if not required:
        print("::error::no required contexts resolved — refusing to act on an empty set")
        return 1
    if not strict:
        print("strict up-to-date checks disabled; no branch update is needed")
        return 0
    try:
        main_sha = get(f"/repos/{repo}/git/ref/heads/{branch}")["object"]["sha"]
        entries = admission_entries(repo, branch, main_sha, required, dt.datetime.now(dt.timezone.utc),
                                    debounce, fleet_threshold, max_updates_per_pr)
        if recovery_report is not None:
            recovery_report["targets"] = [{
                "pr": entry["number"], "head_sha": entry["head"],
                "budget_validated": isinstance(entry.get("budget"), int),
                "update_count": entry.get("budget"), "history_error": entry.get("history_error", False),
                "updated": False, "updated_head_sha": None,
            } for entry in entries]
        for entry in entries:
            print(f"CLASS #{entry['number']}: {entry['why']}; reservation={entry['reservation']}")
        write_summary([f"- #{e['number']}: {e['why']}" for e in entries if "budget" in e["why"]])
        selected = [n for n, ok, _ in global_plan(entries, max_updates) if ok]
        if not selected:
            print("SKIP global admission: no eligible PR or current-head reservation")
            return 0
        number = selected[0]
        original = next(e for e in entries if e["number"] == number)
        if dry_run:
            print(f"DRY #{number}: one global admission, budget {original['budget']}/{max_updates_per_pr}")
            return 0
        # Refresh every reservation and eligibility decision: a queued retry may have registered
        # after the initial snapshot. A native expected_head_sha still guards the final write.
        current_required, current_strict = required_policy(repo, branch)
        if current_required != required or not current_strict:
            return 0
        fresh = admission_entries(repo, branch, main_sha, required, dt.datetime.now(dt.timezone.utc),
                                  debounce, fleet_threshold, max_updates_per_pr)
        if [n for n, ok, _ in global_plan(fresh, max_updates) if ok] != [number]:
            return 0
        entry = next(e for e in fresh if e["number"] == number)
        if entry["head"] != original["head"]:
            return 0
        # Final exact live authority/check/budget/debounce guard, then immutable-main recheck.
        full = get(f"/repos/{repo}/pulls/{number}")
        checked = evaluate_current(repo, branch, full, required, dt.datetime.now(dt.timezone.utc),
                                   debounce, fleet_threshold, max_updates_per_pr)
        if not checked["ok"] or checked["head"] != original["head"]:
            return 0
        if not snapshot_unchanged(repo, branch, main_sha, number, checked["head"], reader=get):
            return 0
        code, data = api("PUT", f"/repos/{repo}/pulls/{number}/update-branch",
                         {"expected_head_sha": checked["head"]})
        if code == 202:
            print(f"UPDATE #{number}: one global admission (budget {checked['budget'] + 1}/{max_updates_per_pr})")
            if recovery_report is not None:
                observed = observe_update(repo, number, checked["head"], branch)
                if observed:
                    target = next((item for item in recovery_report["targets"] if item["pr"] == number), None)
                    if target is not None:
                        target["updated"] = True
                        target["updated_head_sha"] = observed
                else:
                    print(f"::warning::#{number}: update accepted, but exact head transition was not observed")
            return 0
        if code == 409 or (code == 422 and isinstance(data, dict) and data.get("message") in (
                "expected head sha didn’t match current head ref.",
                "expected head sha didn't match current head ref.")):
            print(f"SKIP #{number}: head changed during update ({code})")
            return 0
        print(f"::error::FAILED #{number}: update-branch -> {code}: {data}")
        return 1
    except (KeyError, TypeError, ValueError, RuntimeError, OSError, SystemExit) as error:
        print(f"::error::global admission read failed; no retry: {error}")
        return 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-declaration", action="store_true")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--branch", default="main")
    ap.add_argument("--max-updates", type=int, default=5)
    ap.add_argument("--max-updates-per-pr", type=int, default=4,
                    help="hard lifetime cap from server-side merge commits on the PR")
    ap.add_argument("--debounce-minutes", type=int, default=20)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--recovery-report", type=pathlib.Path,
                    help="write run-bound per-PR update evidence for main-red recovery")
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
        "version": 1, "run_id": int(os.environ.get("GITHUB_RUN_ID", "0")),
        "attempt": int(os.environ.get("GITHUB_RUN_ATTEMPT", "0")),
        "main_sha": os.environ.get("GITHUB_SHA", ""), "dry_run": a.dry_run,
        "complete": False, "successful": False, "targets": [],
    }
    result = run(a.repo, a.branch, a.max_updates, a.debounce_minutes, a.dry_run,
                 a.full_fleet_threshold, a.max_updates_per_pr,
                 recovery_report=report if a.recovery_report else None)
    report["complete"] = True
    report["successful"] = result == 0
    if a.recovery_report:
        a.recovery_report.write_text(json.dumps(report, sort_keys=True) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    sys.exit(main())
