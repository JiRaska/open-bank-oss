#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Finish a selected deploy PR without looping on GitHub's CLEAN auto-merge error.

The workflow serializes selection and passes the main/head snapshots. A normal merge is
allowed only for a CLEAN PR with verified live policy, checks and signatures. GitHub's
strict branch rule and the merge API's ``sha`` guard close the final mutation race.
"""
from __future__ import annotations

import argparse
import datetime
import importlib.util
import json
import os
import re
import subprocess
import sys
import time

_spec = importlib.util.spec_from_file_location("deploy_window", os.path.join(os.path.dirname(__file__), "deploy-window.py"))
_window = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_window)
DEPLOY_PREFIXES = _window.DEPLOY_PREFIXES
decide = _window.decide
last_deploy_from_commits = _window.last_deploy_from_commits
manual_deploy_head = _window.manual_deploy_head


def gh(*args: str) -> dict | list:
    result = subprocess.run(["gh", *args], capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def run(*args: str, input_text: str | None = None) -> str:
    return subprocess.run(args, input=input_text, capture_output=True, text=True,
                          check=True).stdout.strip()


def required_policy(ruleset: dict) -> tuple[list[str], int]:
    if ruleset.get("name") != "main-protection" or ruleset.get("enforcement") != "active":
        raise ValueError("main-protection ruleset is absent or inactive")
    if ruleset.get("bypass_actors"):
        raise ValueError("main-protection has bypass actors; normal merge safety is ambiguous")
    refs = ruleset.get("conditions", {}).get("ref_name", {})
    if "refs/heads/main" not in refs.get("include", []) or refs.get("exclude"):
        raise ValueError("main-protection does not apply unambiguously to main")
    rules = {r.get("type"): r.get("parameters", {}) for r in ruleset.get("rules", [])}
    statuses = rules.get("required_status_checks", {})
    if not statuses.get("strict_required_status_checks_policy"):
        raise ValueError("strict main status policy is not active")
    names = [r.get("context") for r in statuses.get("required_status_checks", [])]
    if not names or any(not n for n in names) or len(names) != len(set(names)):
        raise ValueError("required main status contexts are missing or ambiguous")
    reviews = rules.get("pull_request")
    if reviews is None:
        count = 0
    elif isinstance(reviews, dict):
        count = reviews.get("required_approving_review_count")
        if not isinstance(count, int) or isinstance(count, bool) or count < 0:
            raise ValueError("main-protection has an invalid approving review count")
    else:
        raise ValueError("main-protection has an invalid pull request review rule")
    return names, count


def action(pr: dict, head: str, base: str, required: list[str], reviews: int) -> str:
    if pr.get("state") != "OPEN" or pr.get("isDraft"):
        raise ValueError("selected deploy PR is no longer open and ready")
    if any(label.get("name") == "blocked" for label in pr.get("labels") or []):
        raise ValueError("selected deploy PR carries a blocked deployment hold")
    if pr.get("headRefOid") != head or pr.get("baseRefOid") != base:
        raise ValueError("selected deploy PR head or base changed; retry selection")
    head_name = str(pr.get("headRefName", ""))
    if head_name.startswith(DEPLOY_PREFIXES[1]):
        raise ValueError("Admin UI deploy held until #12211 image provenance is verified")
    if not head_name.startswith(DEPLOY_PREFIXES[0]):
        raise ValueError("selected PR no longer has a service deploy branch")
    state = pr.get("mergeStateStatus")
    if state in ("BLOCKED", "PENDING", "UNSTABLE"):
        return "ARM"
    if state != "CLEAN":
        raise ValueError(f"deploy PR merge state {state!r} needs a safe retry")
    if reviews > 0 and pr.get("reviewDecision") != "APPROVED":
        raise ValueError(f"{reviews} approval(s) required; reviewDecision is "
                         f"{pr.get('reviewDecision')!r}")
    checks = pr.get("statusCheckRollup") or []
    for name in required:
        matching = [c for c in checks if c.get("name") == name]
        if not matching or not all(c.get("conclusion") == "SUCCESS" or
                                    c.get("state") == "SUCCESS" for c in matching):
            raise ValueError(f"required exact-head status {name!r} is not successful")
    return "MERGE"


def verify_source(repo: str, number: int, head_name: str, selected_main: str) -> None:
    if head_name.startswith(DEPLOY_PREFIXES[1]):
        raise ValueError("Admin UI image source provenance is not verified; see #12211")
    run("git", "fetch", "--depth=100", "origin", "main")
    if run("git", "rev-parse", "origin/main") != selected_main:
        raise ValueError("main changed before source verification; retry selection")
    run("git", "reset", "--hard", "origin/main")
    if head_name.startswith(DEPLOY_PREFIXES[0]):
        diff = run("gh", "pr", "diff", str(number), "--repo", repo)
        checked = subprocess.run([sys.executable, ".github/scripts/deploy-window.py",
                                  "verify", "--root", "."], input=diff,
                                 capture_output=True, text=True, check=False)
        if checked.returncode:
            raise ValueError(f"GitOps image source is stale: {checked.stderr.strip()}")


def live_main(repo: str) -> str:
    return gh("api", f"repos/{repo}/git/ref/heads/main")["object"]["sha"]


def verify_signatures(repo: str, commits: list[dict]) -> None:
    if not commits:
        raise ValueError("selected deploy PR has no commits")
    for commit in commits:
        sha = commit.get("oid")
        if not sha or not gh("api", f"repos/{repo}/commits/{sha}")["commit"]["verification"].get("verified"):
            raise ValueError(f"deploy PR commit {sha!r} lacks a verified signature")


def self_test() -> None:
    sha = "a" * 40
    base = "b" * 40
    policy = {"name": "main-protection", "enforcement": "active",
              "conditions": {"ref_name": {"include": ["refs/heads/main"], "exclude": []}},
              "rules": [{"type": "required_status_checks", "parameters": {
                  "strict_required_status_checks_policy": True,
                  "required_status_checks": [{"context": "all-green"}]}},
                  {"type": "pull_request", "parameters": {"required_approving_review_count": 1}}]}
    checks, reviews = required_policy(policy)
    pr = {"state": "OPEN", "isDraft": False, "headRefName": DEPLOY_PREFIXES[0] + base,
          "headRefOid": sha, "baseRefOid": base, "mergeStateStatus": "CLEAN",
          "reviewDecision": "APPROVED", "statusCheckRollup": [{"name": "all-green", "conclusion": "SUCCESS"}]}
    assert (checks, reviews) == (["all-green"], 1)
    assert action(pr, sha, base, checks, reviews) == "MERGE"  # CLEAN never arms
    assert action({**pr, "mergeStateStatus": "BLOCKED"}, sha, base, checks, reviews) == "ARM"
    assert action({**pr, "mergeStateStatus": "PENDING"}, sha, base, checks, reviews) == "ARM"
    admin_pr = {**pr, "headRefName": DEPLOY_PREFIXES[1] + base}
    for state in ("CLEAN", "BLOCKED", "PENDING"):
        try:
            action({**admin_pr, "mergeStateStatus": state}, sha, base, checks, reviews)
        except ValueError as exc:
            assert "#12211" in str(exc)
        else:
            raise AssertionError(f"Admin UI deploy {state} bypassed provenance hold")
    try:
        verify_source("example/open-bank", 1, admin_pr["headRefName"], base)
    except ValueError as exc:
        assert "#12211" in str(exc)
    else:
        raise AssertionError("Admin UI deploy with unverified image provenance accepted")
    assert action({**pr, "mergeStateStatus": "CLEAN", "reviewDecision": "APPROVED"},
                  sha, base, checks, 1) == "MERGE"
    for bad, h, b, r in [
        ({**pr, "headRefOid": "c" * 40}, sha, base, 0),
        ({**pr, "baseRefOid": "c" * 40}, sha, base, 0),
        ({**pr, "statusCheckRollup": []}, sha, base, 0),
        ({**pr, "labels": [{"name": "blocked"}]}, sha, base, 0),
        ({**pr, "reviewDecision": ""}, sha, base, 1),
        ({**pr, "mergeStateStatus": "BEHIND"}, sha, base, 0),
    ]:
        try:
            action(bad, h, b, checks, r)
        except ValueError:
            pass
        else:
            raise AssertionError(f"unsafe deploy PR accepted: {bad}")
    try:
        required_policy({**policy, "rules": [{"type": "required_status_checks", "parameters": {
            "strict_required_status_checks_policy": False,
            "required_status_checks": [{"context": "all-green"}]}}]})
    except ValueError:
        pass
    else:
        raise AssertionError("non-strict main accepted")
    no_review_policy = {**policy, "rules": policy["rules"][:1]}
    assert required_policy(no_review_policy) == (["all-green"], 0)
    assert action({**pr, "reviewDecision": ""}, sha, base, checks, 0) == "MERGE"
    assert required_policy({**policy, "rules": [policy["rules"][0],
                           {"type": "pull_request", "parameters": {"required_approving_review_count": 0}}]}) == (["all-green"], 0)
    for rules in ([policy["rules"][0],
                   {"type": "pull_request", "parameters": {"required_approving_review_count": -1}}],
                  [policy["rules"][0],
                   {"type": "pull_request", "parameters": {"required_approving_review_count": True}}]):
        try:
            required_policy({**policy, "rules": rules})
        except ValueError:
            pass
        else:
            raise AssertionError("main with invalid required reviews accepted")
    original_gh = globals()["gh"]
    try:
        globals()["gh"] = lambda *_: {"commit": {"verification": {"verified": False}}}
        try:
            verify_signatures("example/open-bank", [{"oid": sha}])
        except ValueError:
            pass
        else:
            raise AssertionError("unverified deploy commit accepted")
        globals()["gh"] = lambda *_: {"commit": {"verification": {"verified": True}}}
        verify_signatures("example/open-bank", [{"oid": sha}])
    finally:
        globals()["gh"] = original_gh
    print("deploy-window-mutate: CLEAN/BLOCKED/PENDING, Admin UI hold, review, checks and SHA guards OK")


def main(args: argparse.Namespace) -> None:
    repo = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[0-9a-f]{40}", args.selected_main) or not re.fullmatch(r"[0-9a-f]{40}", args.selected_head):
        raise ValueError("selection did not capture exact main/head SHA")
    if live_main(repo) != args.selected_main:
        raise ValueError("main changed after selection; retry next flush")
    pr = gh("pr", "view", str(args.number), "--repo", repo, "--json",
            "state,isDraft,labels,headRefName,headRefOid,baseRefOid,mergeStateStatus,reviewDecision,statusCheckRollup,commits,id")
    listing = gh("api", f"repos/{repo}/rulesets")
    matches = [r for r in listing if r.get("name") == "main-protection" and r.get("enforcement") == "active"]
    if len(matches) != 1:
        raise ValueError("cannot identify one active main-protection ruleset")
    policy = gh("api", f"repos/{repo}/rulesets/{matches[0]['id']}")
    required, reviews = required_policy(policy)
    choice = action(pr, args.selected_head, args.selected_main, required, reviews)
    open_prs = gh("pr", "list", "--repo", repo, "--state", "open", "--limit", "200",
                  "--json", "number,headRefName,autoMergeRequest")
    armed = [p["number"] for p in open_prs if p["number"] != args.number and
             p["headRefName"].startswith(DEPLOY_PREFIXES) and p.get("autoMergeRequest")]
    if armed:
        raise ValueError(f"another deploy PR is already armed: {armed}")
    commits = gh("api", f"repos/{repo}/commits?sha=main&per_page=50")
    observed = [{"message": c["commit"]["message"],
                 "epoch": int(datetime.datetime.fromisoformat(
                     c["commit"]["committer"]["date"].replace("Z", "+00:00")).timestamp())}
                for c in commits]
    now = int(time.time())
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as event_file:
        event = json.load(event_file)
    expedited = manual_deploy_head(event) == pr["headRefName"]
    if decide("flush", now, last_deploy_from_commits(observed), False,
              int(os.getenv("DEPLOY_WINDOW_SECONDS", "1800")))[0] != "ARM" and not expedited:
        raise ValueError("deploy window closed since selection; retry next flush")
    verify_source(repo, args.number, pr["headRefName"], args.selected_main)
    # A refreshed PR may change head during source verification. A main update here must
    # retry; for the final API race, strict status checks reject a behind branch.
    if live_main(repo) != args.selected_main:
        raise ValueError("main changed before deploy mutation; retry next flush")
    current = gh("pr", "view", str(args.number), "--repo", repo, "--json",
                 "state,isDraft,labels,headRefName,headRefOid,baseRefOid,mergeStateStatus,reviewDecision,statusCheckRollup,commits,id")
    if required_policy(gh("api", f"repos/{repo}/rulesets/{matches[0]['id']}")) != (required, reviews):
        raise ValueError("main ruleset changed during deploy verification; retry selection")
    if action(current, args.selected_head, args.selected_main, required, reviews) != choice:
        raise ValueError("deploy PR state changed during verification; retry next flush")
    if choice == "ARM":
        # GraphQL has no expected-head parameter. Narrow its race with a final live read;
        # GitHub's required review and strict checks still gate the eventual auto-merge.
        if live_main(repo) != args.selected_main:
            raise ValueError("main changed before arming; retry next flush")
        latest = gh("pr", "view", str(args.number), "--repo", repo, "--json",
                    "state,isDraft,labels,headRefName,headRefOid,baseRefOid,mergeStateStatus,reviewDecision,statusCheckRollup,id")
        if latest.get("id") != current["id"] or action(latest, args.selected_head,
                args.selected_main, required, reviews) != "ARM":
            raise ValueError("deploy PR changed before arming; retry next flush")
        gh("api", "graphql", "-f", "query=mutation($id: ID!) { enablePullRequestAutoMerge(input: {pullRequestId: $id, mergeMethod: SQUASH}) {pullRequest {number}}}",
           "-f", f"id={current['id']}")
        print(f"auto-merge armed on deploy PR #{args.number}")
        return
    verify_signatures(repo, current.get("commits") or [])
    if live_main(repo) != args.selected_main:
        raise ValueError("main changed before normal merge; retry next flush")
    response = gh("api", "-X", "PUT", f"repos/{repo}/pulls/{args.number}/merge",
                  "-f", "merge_method=squash", "-f", f"sha={args.selected_head}")
    if not response.get("merged"):
        raise ValueError(f"GitHub did not merge deploy PR #{args.number}: {response.get('message')}")
    print(f"CLEAN deploy PR #{args.number} merged normally as {response['sha']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--number", type=int)
    parser.add_argument("--selected-main")
    parser.add_argument("--selected-head")
    try:
        args = parser.parse_args()
        if args.self_test:
            self_test()
        elif args.number and args.selected_main and args.selected_head:
            main(args)
        else:
            parser.error("--number, --selected-main and --selected-head are required")
    except (ValueError, KeyError, subprocess.CalledProcessError) as exc:
        print(f"::error::deploy window mutation refused: {exc}", file=sys.stderr)
        sys.exit(1)
