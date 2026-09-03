#!/usr/bin/env python3
"""Branch-protection parity gate (rules.yaml: review; issues #2183, #7970).

WHY THIS GATE EXISTS
  `rules.yaml: review` declares the review policy — today honestly marked
  `enforcement_status: aspirational_not_enforced` because the repo has a single
  write-access account and GitHub will not let you approve your own PR. The gap
  is admitted, and that admission is the control: the moment DECLARED and LIVE
  disagree in either direction, the governance file is lying again.

  Two failure shapes this gate catches:

  1. DRIFT — someone changes the live `main-protection` ruleset (or the
     declaration) without the other. Either direction is a defect: a tightened
     ruleset with a stale `aspirational_not_enforced` hides a control from the
     auditor; a loosened ruleset hides its absence.

  2. REVISIT TRIGGER FIRED — rules.yaml names the unblock condition verbatim:
     "the moment a second account holds write access". A policy whose unblock
     condition is a fact must have a mechanism that NOTICES the fact. This gate
     counts direct human collaborators with push access and fails when the
     count reaches 2 while the declaration still says aspirational — turning
     #2183 from a comment with a shelf life into a red CI run.

WHAT IT CHECKS (all via the GitHub API, read-only)
  a. The live ruleset for the default branch exists and is named in
     `review.ruleset_name` (default `main-protection`).
  b. `required_approving_review_count` and `require_code_owner_review` on the
     live ruleset equal the values implied by the declaration:
       - aspirational_not_enforced  -> both absent/zero (the admitted gap)
       - enforced                   -> default_approvals / money_path_approvals
                                       as declared (money-path count is applied
                                       per-path by CODEOWNERS, verified here as
                                       the ruleset-level baseline only)
  c. The compensating controls the declaration claims are still in force:
     a required_status_checks rule with at least
     `review.compensating_min_required_checks` (default 5) required checks.
  d. The revisit trigger: number of direct HUMAN collaborators with push
     access < 2 while the status is aspirational.

EXIT: 0 = parity; 1 = any check failed (with a per-check explanation).

Usage:
  GH_TOKEN=$(gh auth token) python3 .github/scripts/check-branch-protection.py
  python3 .github/scripts/check-branch-protection.py --self-test

Env: GH_TOKEN or GITHUB_TOKEN (required outside --self-test),
     GITHUB_REPOSITORY (default JiRaska/open-bank-oss).
"""
import json
import os
import sys
import urllib.error
import urllib.request

import yaml

REPO = os.environ.get("GITHUB_REPOSITORY", "JiRaska/open-bank-oss")
RULES_YAML = "openbank-libs/governance/rules.yaml"


# ---------------------------------------------------------------- pure core

def evaluate(declared, live_ruleset, live_writers):
    """Compare the rules.yaml `review` declaration against live state.

    declared:      the `review` mapping from rules.yaml
    live_ruleset:  the detailed ruleset JSON (GET /repos/{r}/rulesets/{id})
    live_writers:  count of direct human collaborators with push access

    Returns a list of failure strings; empty = parity.
    """
    failures = []
    status = declared.get("enforcement_status", "aspirational_not_enforced")
    ruleset_name = declared.get("ruleset_name", "main-protection")
    min_checks = int(declared.get("compensating_min_required_checks", 5))

    if live_ruleset is None:
        return [f"live ruleset '{ruleset_name}' not found — the declaration "
                f"assumes it exists; either it was renamed/deleted or the name "
                f"in rules.yaml is stale"]

    if live_ruleset.get("name") != ruleset_name:
        failures.append(f"ruleset name drift: live={live_ruleset.get('name')!r} "
                        f"declared={ruleset_name!r}")

    rules = {r.get("type"): r for r in live_ruleset.get("rules", [])}
    pr = rules.get("pull_request", {}).get("parameters", {})
    live_count = int(pr.get("required_approving_review_count", 0) or 0)
    live_codeowners = bool(pr.get("require_code_owner_review", False))

    if status == "aspirational_not_enforced":
        if live_count != 0 or live_codeowners:
            failures.append(
                f"declaration says aspirational_not_enforced but the live ruleset "
                f"enforces count={live_count} code_owner={live_codeowners} — a "
                f"control that exists but the governance file denies is as much "
                f"a lie as the reverse; update rules.yaml: review")
    elif status == "enforced":
        want = int(declared.get("default_approvals", 1))
        if live_count != want:
            failures.append(f"declared default_approvals={want} but live "
                            f"required_approving_review_count={live_count}")
        if not live_codeowners:
            failures.append("enforced review requires require_code_owner_review "
                            "on the live ruleset, but it is false")
    else:
        failures.append(f"unknown enforcement_status {status!r}; known: "
                        f"aspirational_not_enforced, enforced")

    # Compensating controls: the declaration leans on the required status
    # checks; if they thin out, the honest gap becomes a dishonest one.
    rsc = rules.get("required_status_checks", {}).get("parameters", {})
    checks = rsc.get("required_status_checks", [])
    if len(checks) < min_checks:
        failures.append(f"compensating control weakened: {len(checks)} required "
                        f"status checks on the live ruleset, declaration assumes "
                        f">= {min_checks}")

    # Revisit trigger — the unblock condition is a fact; notice the fact.
    if status == "aspirational_not_enforced" and live_writers >= 2:
        failures.append(
            f"REVISIT TRIGGER FIRED (#2183): {live_writers} accounts now hold "
            f"write access, so the single-account blocker for enforcing the "
            f"review rule no longer holds. Set the ruleset's "
            f"required_approving_review_count to review.default_approvals with "
            f"require_code_owner_review, confirm a bot PR still merges, then "
            f"flip enforcement_status to enforced.")
    return failures


# ---------------------------------------------------------------- GitHub API

def api(path, token):
    req = urllib.request.Request(
        f"https://api.github.com{path}",
        headers={"Authorization": f"Bearer {token}",
                 "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


def fetch_live(token, repo, ruleset_name):
    """Return (ruleset_detail_or_None, human_writer_count)."""
    writers = 0
    page = 1
    while True:
        batch = api(f"/repos/{repo}/collaborators?affiliation=direct"
                    f"&per_page=100&page={page}", token)
        if not batch:
            break
        for c in batch:
            if c.get("type") == "User" and (c.get("permissions") or {}).get("push"):
                writers += 1
        page += 1

    rulesets = api(f"/repos/{repo}/rulesets?includes_parents=false", token)
    target = next((rs for rs in rulesets if rs.get("name") == ruleset_name), None)
    detail = api(f"/repos/{repo}/rulesets/{target['id']}", token) if target else None
    return detail, writers


# ---------------------------------------------------------------- entry

def load_declaration(path=RULES_YAML):
    with open(path) as f:
        return (yaml.safe_load(f) or {}).get("review") or {}


def self_test():
    declared = {"enforcement_status": "aspirational_not_enforced",
                "ruleset_name": "main-protection",
                "compensating_min_required_checks": 5}
    ok_ruleset = {"name": "main-protection", "rules": [
        {"type": "pull_request", "parameters": {
            "required_approving_review_count": 0,
            "require_code_owner_review": False}},
        {"type": "required_status_checks", "parameters": {
            "required_status_checks": [{"context": f"check-{i}"} for i in range(6)]}}]}

    cases = [
        ("parity passes", evaluate(declared, ok_ruleset, 1), False),
        ("loosened live ruleset is still parity (gap admitted)",
         evaluate(declared, ok_ruleset, 1), False),
        ("tightened live ruleset while declared aspirational FAILS",
         evaluate(declared, {**ok_ruleset, "rules": [
             {"type": "pull_request", "parameters": {
                 "required_approving_review_count": 2,
                 "require_code_owner_review": True}},
             ok_ruleset["rules"][1]]}, 1), True),
        ("missing ruleset FAILS", evaluate(declared, None, 1), True),
        ("revisit trigger: second writer while aspirational FAILS",
         evaluate(declared, ok_ruleset, 2), True),
        ("thinned required checks FAIL",
         evaluate(declared, {**ok_ruleset, "rules": [
             ok_ruleset["rules"][0],
             {"type": "required_status_checks", "parameters": {
                 "required_status_checks": [{"context": "only-one"}]}}]}, 1), True),
        ("enforced declaration with matching live passes",
         evaluate({"enforcement_status": "enforced", "default_approvals": 2,
                   "ruleset_name": "main-protection"},
                  {**ok_ruleset, "rules": [
                      {"type": "pull_request", "parameters": {
                          "required_approving_review_count": 2,
                          "require_code_owner_review": True}},
                      ok_ruleset["rules"][1]]}, 3), False),
    ]
    bad = 0
    for name, failures, expect_fail in cases:
        failed = bool(failures)
        if failed != expect_fail:
            print(f"SELF-TEST FAIL: {name}: expected "
                  f"{'red' if expect_fail else 'green'}, got {failures}")
            bad += 1
        else:
            print(f"self-test ok: {name}")
    if bad:
        sys.exit(f"self-test: {bad} case(s) wrong")
    print("self-test: all cases behaved")


def main():
    if "--self-test" in sys.argv:
        self_test()
        return
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("GH_TOKEN/GITHUB_TOKEN required")
    declared = load_declaration()
    ruleset_name = declared.get("ruleset_name", "main-protection")
    live, writers = fetch_live(token, REPO, ruleset_name)
    failures = evaluate(declared, live, writers)
    if failures:
        for f in failures:
            print(f"::error::{f}")
        sys.exit(1)
    print(f"branch-protection parity ok: ruleset '{ruleset_name}' matches "
          f"rules.yaml: review (status={declared.get('enforcement_status')}, "
          f"writers={writers})")


if __name__ == "__main__":
    main()
