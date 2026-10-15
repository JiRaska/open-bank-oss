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
import sys
import urllib.error
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

    def graph_api(commits: dict, update_code: int = 202, reads: list | None = None):
        def fake(method: str, path: str, body: dict | None = None) -> tuple[int, object]:
            if method == "GET" and "/commits/" in path:
                sha = path.rsplit("/", 1)[1]
                if reads is not None:
                    reads.append(sha)
                return (200, commits[sha]) if sha in commits else (404, "Not Found")
            if method == "PUT" and path.endswith("/update-branch"):
                return update_code, "denied" if update_code != 202 else {}
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
        if "/pulls?" in path:
            return [{"number": 42, "auto_merge": {"merge_method": "squash"}, "draft": False}]
        if path.endswith("/pulls/42"):
            return {"draft": False, "auto_merge": {"merge_method": "squash"}, "mergeable_state": "behind",
                    "head": {"sha": fixture_head, "ref": "agent/example",
                             "repo": {"full_name": "example/repo"}}}
        if "/commits/" in path:
            return {"commit": {"committer": {"date": old.isoformat()}}}
        raise AssertionError(f"unexpected API read {path}")

    def run_fixture(commits: dict, update_code: int, **kw) -> tuple[int, str]:
        with patch.dict(globals(), {"required_policy": lambda *_: (["Gitleaks"], True),
                                    "get": fixture_get, "head_checks": lambda *_: {"Gitleaks": "success"},
                                    "api": graph_api(commits, update_code)}):
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


def run(repo: str, branch: str, max_updates: int, debounce: int, dry_run: bool,
        fleet_threshold: int, max_updates_per_pr: int = 4) -> int:
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
    stuck: list[str] = []
    entries, shas, budgets = [], {}, {}
    for p in candidates:
        n = p["number"]
        full = get(f"/repos/{repo}/pulls/{n}")
        sha = shas[n] = full["head"]["sha"]
        pr = {"draft": full["draft"], "auto_merge": full["auto_merge"],
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
            # Checks are read for every candidate, behind or not: an up-to-date full-fleet PR
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
                used = branch_update_count(repo, sha, branch, max_updates_per_pr)
            except BudgetUnreadable as error:
                # Fail closed for THIS PR only; the others are still served and the run stays green.
                ok, why = False, f"update budget unreadable ({error}); skipped, retried next run"
                print(f"::warning::#{n}: {why}")
                stuck.append(f"- #{n}: {why}")
            else:
                budgets[n] = used
                if used >= max_updates_per_pr:
                    ok, why = False, (
                        f"update budget exhausted ({used}/{max_updates_per_pr} consecutive base "
                        "merges since the last author push); to unblock, push to the branch "
                        "(merge or rebase main locally) or press 'Update branch' once by hand "
                        "after checking why CI keeps losing the race")
                    print(f"::warning::#{n}: {why}")
                    stuck.append(f"- #{n}: {why}")
        since = (full["auto_merge"] or {}).get("enabled_at") or full.get("created_at") or ""
        print(f"CLASS  #{n} {sha[:9]}: {kind}{' (no build-matrix check runs: none selected or not started -> small)' if kind == 'unknown' else ''}"
              f"{', build in flight' if building else ''}, armed since {since or '?'}")
        entries.append({"number": n, "ok": ok, "why": why, "kind": kind, "building": building,
                        "waiting_since": since})
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
        code, data = api("PUT", f"/repos/{repo}/pulls/{n}/update-branch", {"expected_head_sha": sha})
        if code == 202:
            updated += 1
            print(f"UPDATE #{n} {sha[:9]}: {why} "
                  f"(budget {used + 1}/{max_updates_per_pr})")
        elif code == 409:
            # A competing update changed the expected head. The next run re-reads it.
            print(f"SKIP   #{n} {sha[:9]}: head changed during update ({code})")
        else:
            failed_updates += 1
            print(f"::error::FAILED #{n} {sha[:9]}: update-branch -> {code}: {data}")
    write_summary(stuck)
    print(f"done: {updated} update(s), {failed_updates} failed update(s), "
          f"{len(stuck)} PR(s) held by the budget")
    return 1 if failed_updates else 0


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
    return run(a.repo, a.branch, a.max_updates, a.debounce_minutes, a.dry_run,
               a.full_fleet_threshold, a.max_updates_per_pr)


if __name__ == "__main__":
    sys.exit(main())
