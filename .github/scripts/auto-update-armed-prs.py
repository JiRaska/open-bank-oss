#!/usr/bin/env python3
"""Keep ARMED pull requests up to date with `main`, and only those.

WHY THIS EXISTS
---------------
`main-protection` sets `strict_required_status_checks_policy: true` (measured 2026-09-30 via
`gh api repos/JiRaska/open-bank-oss/rules/branches/main`), so a PR must be up to date with
`main` to merge. `main` moves every few minutes and CI takes 30-40 minutes, so a PR whose
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
  * more than --max-updates in this run    -> skip, capped.

THE TOKEN MATTERS
-----------------
The update MUST be made with the GitHub App installation token. A push made with the workflow's
GITHUB_TOKEN does not trigger workflows, so CI would never re-run on the new head and the PR
would wedge on the required checks instead of on `behind`. The merge commit `update-branch`
creates is made server-side (committer `GitHub`) and GitHub signs it, which `required_signatures`
needs: see the PR that introduced this script for the measured evidence.

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


# --------------------------------------------------------------------------- pure decision
def decide(pr: dict, required: list[str], checks: dict[str, str | None], now: dt.datetime,
           head_committed_at: dt.datetime | None, debounce_minutes: int) -> tuple[bool, str]:
    """Return (update?, reason). `checks` maps context -> conclusion (None = not completed)."""
    if pr.get("draft"):
        return False, "draft"
    if not pr.get("auto_merge"):
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
    missing = [c for c in required if c not in checks]
    if missing:
        return False, f"required context(s) not reported: {', '.join(missing)}"
    pending = [c for c in required if checks[c] is None]
    if pending:
        return False, f"required context(s) pending: {', '.join(pending)}"
    failed = [c for c in required if checks[c] not in OK_CONCLUSIONS]
    if failed:
        return False, "required context(s) not green: " + ", ".join(f"{c}={checks[c]}" for c in failed)
    if head_committed_at is not None:
        age = (now - head_committed_at).total_seconds() / 60
        if age < debounce_minutes:
            return False, f"debounced — head committed {age:.0f} min ago (< {debounce_minutes})"
    return True, "behind + armed + all required contexts green"


def self_test() -> int:
    now = dt.datetime(2026, 9, 30, 12, 0, tzinfo=dt.timezone.utc)
    old = now - dt.timedelta(hours=2)
    req = ["Validate manifests", "Gitleaks"]
    green = {"Validate manifests": "success", "Gitleaks": "success"}
    base = {"draft": False, "auto_merge": {"merge_method": "squash"}, "same_repo": True,
            "mergeable_state": "behind"}
    cases = [
        ("behind+green+armed -> update", base, green, old, True),
        ("behind+pending -> skip", base, {**green, "Gitleaks": None}, old, False),
        ("behind+failed -> skip", base, {**green, "Gitleaks": "failure"}, old, False),
        ("behind+missing context -> skip", base, {"Validate manifests": "success"}, old, False),
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
    # An empty required set must never read as "all green" (vacuous pass).
    got, why = decide(base, [], {}, now, old, 20)
    failures += got
    print(f"{'FAIL' if got else 'PASS'}  empty required set -> skip ({why})")
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


def required_contexts(repo: str, branch: str) -> list[str]:
    rules = get(f"/repos/{repo}/rules/branches/{branch}")
    ctx: list[str] = []
    for r in rules:
        if r.get("type") == "required_status_checks":
            ctx += [c["context"] for c in r["parameters"]["required_status_checks"]]
    return sorted(set(ctx))


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


def run(repo: str, branch: str, max_updates: int, debounce: int, dry_run: bool) -> int:
    required = required_contexts(repo, branch)
    print(f"required contexts on {branch} (live): {required}")
    if not required:
        print("::error::no required contexts resolved — refusing to act on an empty set")
        return 1
    now = dt.datetime.now(dt.timezone.utc)
    pulls = get(f"/repos/{repo}/pulls?state=open&base={branch}&per_page=100")
    armed = [p for p in pulls if p.get("auto_merge") and not p.get("draft")]
    print(f"{len(pulls)} open PR(s) against {branch}, {len(armed)} armed and non-draft")
    updated = 0
    for p in armed:
        n = p["number"]
        full = get(f"/repos/{repo}/pulls/{n}")
        sha = full["head"]["sha"]
        pr = {"draft": full["draft"], "auto_merge": full["auto_merge"],
              "same_repo": (full["head"].get("repo") or {}).get("full_name") == repo,
              "mergeable_state": full.get("mergeable_state")}
        if pr["mergeable_state"] != "behind" or not pr["same_repo"]:
            ok, why = decide(pr, required, {}, now, None, debounce)
        else:
            commit = get(f"/repos/{repo}/commits/{sha}")
            committed = parse_ts(commit["commit"]["committer"]["date"])
            ok, why = decide(pr, required, head_checks(repo, sha), now, committed, debounce)
        if ok and updated >= max_updates:
            ok, why = False, f"cap of {max_updates} update(s) per run reached"
        if not ok:
            print(f"SKIP   #{n} {sha[:9]}: {why}")
            continue
        if dry_run:
            print(f"DRY    #{n} {sha[:9]}: would update — {why}")
            updated += 1
            continue
        code, data = api("PUT", f"/repos/{repo}/pulls/{n}/update-branch", {"expected_head_sha": sha})
        if code == 202:
            updated += 1
            print(f"UPDATE #{n} {sha[:9]}: {why}")
        else:
            print(f"::warning::FAILED #{n} {sha[:9]}: update-branch -> {code}: {data}")
    print(f"done: {updated} update(s)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-declaration", action="store_true")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--branch", default="main")
    ap.add_argument("--max-updates", type=int, default=5)
    ap.add_argument("--debounce-minutes", type=int, default=20)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.check_declaration:
        return check_declaration()
    if not a.repo or not os.environ.get("GH_TOKEN"):
        print("::error::--repo (or GITHUB_REPOSITORY) and GH_TOKEN are required")
        return 1
    return run(a.repo, a.branch, a.max_updates, a.debounce_minutes, a.dry_run)


if __name__ == "__main__":
    sys.exit(main())
