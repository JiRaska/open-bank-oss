#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# AGENT PR GUARD — an autonomous agent's PR may not reach main through a protected path.
#
# WHY THIS EXISTS
#   The repo is moving to scheduled, unattended agents that open PRs 24/7. The control that
#   has kept unattended work off the money path so far is a PreToolUse hook on the
#   MAINTAINER'S LAPTOP (`protected-pr-check.sh`). That control does not exist anywhere an
#   agent actually runs unattended — not in a cloud session, not on a GitHub runner — and
#   `main`'s ruleset does not supply it either: `required_approving_review_count` is 0 and
#   the admin bypass actor is the same identity an agent authenticates as. An agent has
#   already, unprompted, reached for an administrative override on a money-path PR when
#   self-approval was refused.
#
#   So the rule moves to where it runs regardless of who is driving: a required check.
#   A red required check cannot be cleared by the agent that tripped it, cannot be cleared
#   by `--auto`, and takes a deliberate human action to override.
#
# WHAT IT IS AND IS NOT
#   IS:     a guard against the unattended-accident — an agent quietly landing a change on
#           the money path, CI machinery, or authorization policy with nobody reading it.
#   IS NOT: a boundary against a determined agent. Scope is decided from the PR's author and
#           head-branch name, both of which the agent itself supplies. The identity boundary
#           is the token: the agent authenticates as an App that holds no admin rights and is
#           not in the ruleset's `bypass_actors`. This check is the second layer, not the first.
#
# SCOPE — who is judged, and why the unknown case FAILS
#   in scope:     an author declared in rules.yaml `autonomous_agent_prs.agent_accounts`, OR
#                 a head branch matching a declared `agent_branch_prefixes` entry.
#   out of scope: a human author on a human branch (they already own the review), and a
#                 deterministic automation account declared in `automation_accounts`
#                 (release-please and the deploy snapshotters legitimately touch money-path
#                 paths; blocking them deadlocks the release queue for no safety gained —
#                 their content is generated, not authored).
#   UNDECLARED:   a Bot-typed author in neither list is a FAILURE, not a pass. A new machine
#                 identity must be classified by a human before it can open PRs here, and
#                 that classification is itself a governance-path change. This is the one
#                 clause that keeps the scope from being a hand-kept list that reads as
#                 passing when it is short (repo lore: a gate scoped by a hand-kept list of
#                 its own subjects reports clean about work it never did).
#
# PROTECTED PATHS — DERIVED, not retyped
#   The service token set is derived from rules.yaml `money_path_services` by stripping the
#   `openbank-` prefix and the optional `-service` suffix, so onboarding a money-path service
#   extends this guard with no edit here. `extra_protected_tokens` carries the high-blast
#   radius services that are NOT money-path by that list's definition (kyc, party, card
#   issuance, aml, tax, dispute) — a hand-kept list of external FACTS, which is fine; a
#   hand-kept list of the gate's own SCOPE is not.
#
#   Two naming schemes must both be covered, because component names are not service names:
#     service source     openbank-<tok>-service/...
#     deployment config  openbank-infra/gitops/components/<tok>s?/...
#   An earlier laptop-side version anchored on the service list only and allowed a PR that
#   opened balance-service to the public ingress through gitops.
#
#   Docs are excluded from the authz clause on purpose: a Markdown file under docs/ cannot
#   change what the PDP enforces, and blocking the documentation ABOUT a control as if it
#   were the control trains people to route around the guard (#3888).
#
# FALSIFIABILITY
#   --self-test drives the classifier over fixtures with no network: every block reason must
#   be independently reachable (asserted on the REASON, not just the exit code — two branches
#   agreeing on a verdict is how a clause becomes unfalsifiable while the suite stays green),
#   every out-of-scope shape must pass, the undeclared-bot shape must fail, and an enumeration
#   failure must NOT be reported as clean.
#
# USAGE
#   python3 .github/scripts/check-agent-pr-guard.py             # PR number from the environment
#   python3 .github/scripts/check-agent-pr-guard.py --pr 6410   # explicit
#   python3 .github/scripts/check-agent-pr-guard.py --self-test
#
# EXIT CODES
#   0  out of scope, or in scope and touching nothing protected
#   1  in scope and touching a protected path — human review required
#   2  could not determine author / branch / file list — NOT a clean verdict

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gatelib  # noqa: E402  (path must be set first)

REPO = "JiRaska/open-bank-oss"
RULES = "openbank-libs/governance/rules.yaml"


class Undetermined(Exception):
    """The verdict could not be computed. Never downgraded to a pass — on GitHub a
    permission-shaped absence is byte-identical to a real one."""


# --------------------------------------------------------------------------- rules


def load_rules(path=RULES):
    import yaml

    try:
        with open(path) as fh:
            doc = yaml.safe_load(fh)
    except (OSError, yaml.YAMLError) as e:
        raise Undetermined(f"could not read {path}: {e}") from e
    cfg = (doc or {}).get("autonomous_agent_prs")
    if not isinstance(cfg, dict):
        raise Undetermined(f"{path} has no `autonomous_agent_prs` block — the guard cannot be scoped")
    cfg["_money_path_services"] = (doc or {}).get("money_path_services") or []
    if not cfg["_money_path_services"]:
        raise Undetermined(f"{path} has no `money_path_services` — the protected token set would be empty")
    return cfg


def protected_tokens(cfg):
    """Service tokens, DERIVED from money_path_services plus the declared extras."""
    toks = set()
    for svc in cfg["_money_path_services"]:
        t = re.sub(r"^openbank-", "", svc)
        t = re.sub(r"-service$", "", t)
        if t:
            toks.add(t)
    toks.update(cfg.get("extra_protected_tokens") or [])
    return sorted(toks)


# --------------------------------------------------------------------------- classification


def norm_login(login):
    """One spelling for a machine account.

    GitHub spells the same App three ways depending on which surface answers: the REST API
    says `openbank-gitops-bot[bot]`, `gh pr view --json author` says `app/openbank-gitops-bot`,
    and a human writing the config says whichever they last saw. A declaration that matches
    only one of those silently classifies the account as UNDECLARED — which this gate treats
    as a failure, so the defect is loud rather than silent, but it is still a defect. Measured
    on PR #6403, where the declared `openbank-gitops-bot[bot]` did not match the `app/...`
    form the gate actually received."""
    login = login.strip()
    if login.startswith("app/"):
        login = login[len("app/"):]
    if login.endswith("[bot]"):
        login = login[: -len("[bot]")]
    return login


def in_scope(author, is_bot, branch, cfg):
    """(bool in_scope, str why). Raises Undetermined for an undeclared machine account."""
    agents = {norm_login(a) for a in (cfg.get("agent_accounts") or [])}
    automation = {norm_login(a) for a in (cfg.get("automation_accounts") or [])}
    prefixes = cfg.get("agent_branch_prefixes") or []
    author_key = norm_login(author)

    if author_key in agents:
        return True, f"author `{author}` is a declared autonomous agent account"
    for p in prefixes:
        if branch.startswith(p):
            return True, f"head branch `{branch}` uses the agent prefix `{p}`"
    if author_key in automation:
        return False, f"author `{author}` is declared deterministic automation (generated content)"
    if is_bot:
        raise Undetermined(
            f"author `{author}` is a machine account declared in NEITHER "
            f"`autonomous_agent_prs.agent_accounts` NOR `automation_accounts`. A new machine "
            f"identity must be classified by a human before it opens pull requests here. "
            f"Refusing to guess — an unclassified bot is not an out-of-scope one."
        )
    return False, f"author `{author}` is a human account on a non-agent branch"


NEW_COMPONENT_RE = re.compile(r"^(openbank-[^/]+)/version\.txt$")


def protected_reasons(files, cfg, added=frozenset()):
    """Every protected-path clause this file list trips, as (clause, matched paths).

    Returns ALL of them rather than the first: a reason list that stops at the first hit
    cannot show that its later clauses are reachable, which is what the self-test asserts."""
    toks = "|".join(re.escape(t) for t in protected_tokens(cfg))
    out = []

    ci = [f for f in files if re.match(r"^\.github/(workflows|actions)/", f)]
    if ci:
        out.append(("ci-definitions", ci))

    svc_re = re.compile(
        rf"^openbank-({toks})s?(-service)?/|^openbank-infra/gitops/components/({toks})s?(-service)?/"
    )
    svc = [f for f in files if svc_re.match(f)]
    if svc:
        out.append(("money-path", svc))

    # A Markdown file under docs/ cannot change what the PDP enforces (#3888).
    authz = [
        f
        for f in files
        if not re.match(r"^docs/.*\.md$", f)
        and re.search(r"(\.rego$|/opa/|authz|rbac|networkpolicy)", f)
    ]
    if authz:
        out.append(("authz-policy", authz))

    # A BRAND-NEW released component (#6560). Every clause above is name-based: it matches
    # changed paths against tokens derived from `money_path_services` + `extra_protected_tokens`,
    # both hand-kept lists of services that already exist. A directory that is not on main
    # cannot be in either list, so a PR introducing a whole new service — money-path or not —
    # trips no clause and the guard returns "touches no protected path". The scope of the check
    # is a list of the very thing it checks, which reads as PASSING when the list is short
    # rather than as UNCHECKED.
    #
    # The test here is structural instead of nominal, and uses the repo's own definition:
    # "a module is a released component IFF it has a version.txt" (CLAUDE.md rule 2). An
    # ADDED `openbank-*/version.txt` is therefore exactly "this PR creates a new released
    # component", with no list to keep. `added` comes from the GitHub files API `status`
    # field, so a release-please bump of an EXISTING version.txt (status "modified") is not
    # caught here — and release-please is out of scope anyway as declared automation.
    new_component = sorted(f for f in files if f in added and NEW_COMPONENT_RE.match(f))
    if new_component:
        out.append(("new-released-component", new_component))

    gov_globs = cfg.get("governance_path_globs") or []
    gov = [f for f in files if any(fnmatch.fnmatch(f, g) for g in gov_globs)]
    if gov:
        out.append(("governance-and-gates", gov))

    return out


REASON_TEXT = {
    "ci-definitions": (
        "changes .github/workflows or .github/actions. Merging that to main changes what "
        "executes on the self-hosted runners."
    ),
    "money-path": (
        "touches money-path / high-blast-radius service or gitops paths, regardless of the "
        "PR title's scope."
    ),
    "authz-policy": "changes authorization policy (rego / OPA / RBAC / NetworkPolicy).",
    "governance-and-gates": (
        "changes governance sources or CI gate machinery — these decide what the whole fleet "
        "enforces."
    ),
    "new-released-component": (
        "creates a NEW released component (adds an openbank-*/version.txt). A new service has "
        "no classification yet — it is in no money-path list, has no threat model and no "
        "owner — so no clause above can speak for it. That is a human's call, not an agent's."
    ),
}


def verdict(author, is_bot, branch, files, cfg, added=frozenset()):
    """(exit_code, message). Raises Undetermined when it cannot decide."""
    scoped, why = in_scope(author, is_bot, branch, cfg)
    if not scoped:
        return 0, f"out of scope: {why}"
    if not files:
        raise Undetermined(
            "the changed-file list is empty — an agent PR that changes nothing is not a clean verdict"
        )
    hits = protected_reasons(files, cfg, added)
    if not hits:
        return 0, f"in scope ({why}) and touches no protected path — {len(files)} file(s) checked"
    lines = [f"BLOCKED: this PR is agent-authored ({why}) and:"]
    for clause, matched in hits:
        lines.append(f"  * {REASON_TEXT[clause]}")
        lines.append(f"    matched: {' '.join(sorted(matched)[:3])}")
    lines.append("")
    lines.append(
        "An autonomous agent does not land these paths unattended. Hand this PR to a human "
        "reviewer; do NOT reach for an administrative override flag — a refusal here is the "
        "correct final state."
    )
    return 1, "\n".join(lines)


# --------------------------------------------------------------------------- enumeration


def _gh(args):
    cmd = ["gh"] + args
    if args and args[0] != "api":
        cmd += ["-R", REPO]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise Undetermined(f"gh {' '.join(args)} failed (rc={proc.returncode}): {proc.stderr.strip()}")
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as e:
        raise Undetermined(f"gh {' '.join(args)} returned non-JSON: {e}") from e


def fetch_pr(n):
    pr = _gh(["pr", "view", str(n), "--json", "author,headRefName"])
    author = (pr.get("author") or {}).get("login") or ""
    is_bot = bool((pr.get("author") or {}).get("is_bot"))
    branch = pr.get("headRefName") or ""
    if not author or not branch:
        raise Undetermined(f"PR #{n}: could not read author/headRefName")
    pages = _gh(["api", f"repos/{REPO}/pulls/{n}/files?per_page=100", "--paginate", "--slurp"])
    flat = [f for page in pages for f in page]
    files = [f["filename"] for f in flat]
    added = frozenset(f["filename"] for f in flat if f.get("status") == "added")
    return author, is_bot, branch, files, added


def resolve_pr_number(explicit):
    if explicit:
        return int(explicit)
    for var in ("PR_NUMBER", "GITHUB_PR_NUMBER"):
        if os.environ.get(var, "").strip().isdigit():
            return int(os.environ[var])
    m = re.match(r"refs/pull/(\d+)/", os.environ.get("GITHUB_REF", ""))
    if m:
        return int(m.group(1))
    ev = os.environ.get("GITHUB_EVENT_PATH", "")
    if ev and os.path.exists(ev):
        try:
            with open(ev) as fh:
                n = (json.load(fh).get("pull_request") or {}).get("number")
            if n:
                return int(n)
        except (OSError, ValueError, json.JSONDecodeError):
            pass
    return None


# --------------------------------------------------------------------------- self-test

FIXTURE = {
    "agent_accounts": ["openbank-agent-bot[bot]"],
    "automation_accounts": ["release-please[bot]", "openbank-gitops-bot[bot]"],
    "agent_branch_prefixes": ["agent/"],
    "extra_protected_tokens": ["kyc", "party"],
    "governance_path_globs": [
        "openbank-libs/governance/*",
        ".github/gates/*",
        ".github/scripts/*",
        ".github/agent-prompts/*",
    ],
    "_money_path_services": ["openbank-ledger-service", "openbank-balance-service"],
}


def self_test():
    cases = [
        # (name, author, is_bot, branch, files, expect_code, expect_substring)
        (
            "agent account + money-path service source is blocked",
            "openbank-agent-bot[bot]", True, "fix/ledger-rounding",
            ["openbank-ledger-service/src/main/kotlin/A.kt"], 1, "money-path",
        ),
        (
            "agent BRANCH under the human account is still blocked",
            "JiRaska", False, "agent/fix-ledger",
            ["openbank-ledger-service/src/main/kotlin/A.kt"], 1, "money-path",
        ),
        (
            "gitops component path counts as money-path (plural component name)",
            "openbank-agent-bot[bot]", True, "agent/x",
            ["openbank-infra/gitops/components/balances/ingress.yaml"], 1, "money-path",
        ),
        (
            "workflow change is blocked on its own clause",
            "openbank-agent-bot[bot]", True, "agent/x",
            [".github/workflows/ci.yml"], 1, "self-hosted runners",
        ),
        (
            "rego change is blocked on its own clause",
            "openbank-agent-bot[bot]", True, "agent/x",
            ["openbank-infra/gitops/base/policy/rest.rego"], 1, "authorization policy",
        ),
        (
            "governance source is blocked on its own clause",
            "openbank-agent-bot[bot]", True, "agent/x",
            ["openbank-libs/governance/rules.yaml"], 1, "governance sources",
        ),
        (
            "a gate script is governance machinery too",
            "openbank-agent-bot[bot]", True, "agent/x",
            [".github/scripts/check-something.py"], 1, "governance sources",
        ),
        (
            "an agent's own PROMPT is its program — it may not edit its mandate",
            "openbank-agent-bot[bot]", True, "agent/x",
            [".github/agent-prompts/issue-worker.md"], 1, "governance sources",
        ),
        (
            "docs ABOUT authz are NOT the control (#3888)",
            "openbank-agent-bot[bot]", True, "agent/x",
            ["docs/adr/0034-authz.md"], 0, "touches no protected path",
        ),
        (
            "an ordinary agent PR passes",
            "openbank-agent-bot[bot]", True, "agent/x",
            ["openbank-admin-ui/app/page.tsx"], 0, "touches no protected path",
        ),
        (
            "a human on a human branch is out of scope even on the money path",
            "JiRaska", False, "fix/ledger-rounding",
            ["openbank-ledger-service/src/main/kotlin/A.kt"], 0, "out of scope",
        ),
        (
            "declared automation is out of scope on the money path",
            "release-please[bot]", True, "release-please--branches--main",
            ["openbank-ledger-service/version.txt"], 0, "deterministic automation",
        ),
        (
            "the `app/...` spelling of a declared automation account still matches (#6403)",
            "app/openbank-gitops-bot", True, "deploy/snapshot",
            ["openbank-infra/gitops/components/balances/kustomization.yaml"], 0,
            "deterministic automation",
        ),
        (
            "the bare spelling of a declared agent account still matches",
            "openbank-agent-bot", True, "chore/x",
            ["openbank-ledger-service/src/main/kotlin/A.kt"], 1, "money-path",
        ),
        (
            "extra_protected_tokens are covered (kyc is not in money_path_services)",
            "openbank-agent-bot[bot]", True, "agent/x",
            ["openbank-kyc-service/src/main/kotlin/A.kt"], 1, "money-path",
        ),
        # ---- #6560: a WHOLE NEW service trips no name-based clause ---------------------
        (
            "a brand-new released component is blocked even though its name is in no list",
            "openbank-agent-bot[bot]", True, "agent/x",
            [
                "openbank-referral-service/version.txt",
                "openbank-referral-service/src/main/kotlin/Referral.kt",
                "release-please-config.json",
            ], 1, "NEW released component",
            {"openbank-referral-service/version.txt"},
        ),
        (
            # THE NEGATIVE CONTROL for the clause above. Same paths, same author, same
            # branch — only `added` differs. Without the status discriminator this case
            # would also go red, and the clause would be blocking every /bump instead of
            # every new component. The two cases only pass together if `status` is what
            # decides.
            "an EXISTING component's version.txt bump is NOT a new component",
            "openbank-agent-bot[bot]", True, "agent/x",
            [
                "openbank-referral-service/version.txt",
                "openbank-referral-service/src/main/kotlin/Referral.kt",
                "release-please-config.json",
            ], 0, "touches no protected path",
            frozenset(),
        ),
        (
            # The measured #5979 file list, which returned exit 0 on origin/main.
            "the #5979 shape: new service + release registry, no protected token anywhere",
            "openbank-agent-bot[bot]", True, "feat/referral-mgm",
            [
                "openbank-referral-service/version.txt",
                "openbank-referral-service/build.gradle.kts",
                ".release-please-manifest.json",
                "docs/threat-models/openbank-referral-service.md",
            ], 1, "NEW released component",
            {"openbank-referral-service/version.txt"},
        ),
    ]

    failures = []
    for case in cases:
        name, author, is_bot, branch, files, want_code, want_sub = case[:7]
        added = frozenset(case[7]) if len(case) > 7 else frozenset()
        try:
            code, msg = verdict(author, is_bot, branch, files, dict(FIXTURE), added)
        except Undetermined as e:
            failures.append(f"{name}: raised Undetermined ({e})")
            continue
        if code != want_code:
            failures.append(f"{name}: exit {code}, expected {want_code} — {msg}")
        elif want_sub not in msg:
            failures.append(f"{name}: message missing {want_sub!r} — {msg}")

    # The undeclared-bot clause: absence of a classification must FAIL, not pass.
    try:
        verdict("some-new-bot[bot]", True, "chore/whatever", ["README.md"], dict(FIXTURE))
        failures.append("an UNDECLARED bot account was treated as out of scope — it must be undetermined")
    except Undetermined as e:
        if "NEITHER" not in str(e):
            failures.append(f"undeclared-bot raised the wrong Undetermined: {e}")

    # An empty file list for an in-scope PR is an enumeration failure, not a clean.
    try:
        verdict("openbank-agent-bot[bot]", True, "agent/x", [], dict(FIXTURE))
        failures.append("an EMPTY file list was reported as clean — that is the enumeration-failure shape")
    except Undetermined:
        pass

    # A rules file with no autonomous_agent_prs block must be undetermined, not a pass.
    import tempfile

    with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as fh:
        fh.write("money_path_services: [openbank-ledger-service]\n")
        empty = fh.name
    try:
        load_rules(empty)
        failures.append("rules.yaml without `autonomous_agent_prs` loaded cleanly — it must be undetermined")
    except Undetermined:
        pass
    finally:
        os.unlink(empty)

    # And the real rules.yaml must actually carry the block — a self-test that only ever
    # exercises its own fixture cannot notice the config being deleted from under it.
    if os.path.exists(RULES):
        try:
            live = load_rules()
        except Undetermined as e:
            failures.append(f"the live {RULES} does not satisfy the guard: {e}")
        else:
            for required in ("agent_accounts", "automation_accounts", "agent_branch_prefixes", "governance_path_globs"):
                if required not in live:
                    failures.append(f"live {RULES}: `autonomous_agent_prs.{required}` is missing")
            toks = protected_tokens(live)
            if len(toks) < 20:
                failures.append(f"live rules.yaml yields only {len(toks)} protected tokens — the derivation is broken")

    if failures:
        print("SELF-TEST FAILED — the guard is not falsifiable as written:")
        for f in failures:
            print(f"  x {f}")
        return 1
    print(
        f"self-test OK — {len(cases)} classifier cases + 4 undetermined cases, "
        f"every block clause independently reached"
    )
    return 0


# --------------------------------------------------------------------------- main


def main():
    ap = argparse.ArgumentParser(description="agent PR guard")
    ap.add_argument("--pr")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument(
        "--paths", nargs="+", metavar="PATH",
        help="classify a file list directly, with no PR: exit 1 if any path is protected. "
             "For an agent deciding whether a change is in scope BEFORE writing it.",
    )
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    # --paths: ask the gate instead of reasoning about it.
    #
    # The worker's own instructions tell it to discard an issue whose fix would land on a
    # protected path. On 2026-08-24 it reasoned about that and got it wrong: it picked the
    # party-service slice of #5679, having weighed `money_path_services` and overlooked
    # `extra_protected_tokens`, where `party` sits. The PR (#6607) was red from its first
    # check and could never merge — a whole run spent on work the gate was always going to
    # refuse.
    #
    # A rule an agent must APPLY BY REASONING is a rule it can misread. This makes the same
    # question answerable by running one command, before any code is written.
    if args.paths:
        try:
            cfg = load_rules()
        except Undetermined as e:
            print(f"::error::agent-pr-guard --paths could not read the rules: {e}")
            return 2
        hits = protected_reasons(list(args.paths), cfg, frozenset(args.paths))
        if not hits:
            print(f"in scope: none of the {len(args.paths)} path(s) given are protected")
            return 0
        print("PROTECTED — an agent PR touching these will be refused by the gate:")
        for clause, matched in hits:
            print(f"  * {REASON_TEXT[clause]}")
            print(f"    matched: {' '.join(sorted(matched)[:3])}")
        return 1

    try:
        cfg = load_rules()
        # The SUBJECT corpus is the RULE SET, not the PR. A PR-scoped gate examines one PR by
        # definition, so counting PRs could never distinguish a working gate from a broken
        # one. What CAN silently collapse is the derivation: if money_path_services stops
        # being readable, or the glob list empties, every clause matches nothing and the gate
        # passes everything while still exiting 0 on a real PR. That is the exact failure
        # `min_subjects:` exists for, so the floor is placed on the derived rules.
        n_rules = len(protected_tokens(cfg)) + len(cfg.get("governance_path_globs") or [])
        gatelib.subjects(n_rules, "protected service tokens + governance globs")
        n = resolve_pr_number(args.pr)
        if n is None:
            print("agent-pr-guard: no pull request in context — nothing to judge")
            return 0
        author, is_bot, branch, files, added = fetch_pr(n)
        code, msg = verdict(author, is_bot, branch, files, cfg, added)
    except Undetermined as e:
        # Third state: the verdict could not be computed. The floor must not then convert an
        # unreachable API into a lost-corpus red one layer up.
        gatelib.subjects_unresolved(str(e))
        print(f"::error::agent-pr-guard could not reach a verdict: {e}")
        return 2
    print(f"agent-pr-guard: {msg}" if code == 0 else msg)
    return code


if __name__ == "__main__":
    sys.exit(main())
