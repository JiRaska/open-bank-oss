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
#   by `--auto`. Protected changes require an explicit owner decision on the current head, or on
#   an earlier head followed ONLY by base-branch merges that leave the PR's net patch
#   byte-identical (otherwise strict up-to-date checks void every approval forever).
#   Trust-chain changes remain blocked and require separate maintainer adoption.
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
#   (the gate is read-only: it only GETs from the API and never posts)
#
# EXIT CODES
#   0  out of scope, or in scope and touching nothing protected
#   1  in scope and touching a protected path — human review required
#   2  could not determine author / branch / file list — NOT a clean verdict

import argparse
from pathlib import Path
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
        "An autonomous agent does not land these paths unattended. Obtain explicit owner approval on the current head; "
        "review-policy changes require separate maintainer adoption; do NOT reach for an administrative override flag — a refusal here is the "
        "correct final state."
    )
    return 1, "\n".join(lines)


# The review route cannot authorize modifications to its own trust chain.
REVIEW_POLICY_PATHS = {
    ".github/scripts/check-agent-pr-guard.py",
    ".github/scripts/test-agent-pr-review.py",
    ".github/scripts/gatelib.py",
    ".github/scripts/run-gates.py",
    ".github/workflows/ci.yml",
    ".github/workflows/agent-review-refresh.yml",
    ".github/gates/gates.yaml",
    "openbank-libs/governance/rules.yaml",
    ".github/CODEOWNERS",
}


def approved_reviewers(reviews, author, head, excluded):
    """Use each person's latest decisive review; comments do not revoke approval."""
    if not re.fullmatch(r"[0-9a-f]{40}", head or ""):
        raise Undetermined("missing or malformed current PR head SHA")
    latest = {}
    for review in sorted(reviews, key=lambda r: r["id"]):
        user = review.get("user") or {}
        login = user.get("login", "").lower()
        if (not login or login == author.lower() or user.get("type") != "User"
                or login.endswith("[bot]") or login in excluded):
            continue
        if review.get("state") in {"APPROVED", "CHANGES_REQUESTED", "DISMISSED"}:
            latest[login] = review
    # Outstanding change requests fail closed, including requests on an older commit.
    if any(r["state"] == "CHANGES_REQUESTED" for r in latest.values()):
        return []
    return [login for login, r in latest.items()
            if r["state"] == "APPROVED" and r.get("commit_id") == head]


def human_review_allows(n, files, cfg):
    if any(f in REVIEW_POLICY_PATHS for f in files):
        return False
    pr = _gh(["api", f"repos/{REPO}/pulls/{n}"])
    head = pr["head"]["sha"]
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if event_path:
        try:
            event = json.loads(Path(event_path).read_text())
            if event.get("pull_request", {}).get("head", {}).get("sha") != head:
                raise Undetermined("workflow event is not for the current PR head")
        except (OSError, ValueError) as exc:
            raise Undetermined("cannot read workflow head identity") from exc
    if pr.get("state") != "open" or pr.get("draft"):
        return False
    pages = _gh(["api", f"repos/{REPO}/pulls/{n}/reviews?per_page=100",
                 "--paginate", "--slurp"])
    excluded = {login.lower() for login in
                cfg.get("agent_accounts", []) + cfg.get("automation_accounts", [])}
    candidates = approved_reviewers([r for page in pages for r in page],
                                    pr["user"]["login"], head, excluded)
    qualified = []
    for login in candidates:
        permission = _gh(["api", f"repos/{REPO}/collaborators/{login}/permission"])
        if permission.get("permission") in {"admin", "maintain", "write"}:
            qualified.append(login)
    # Conservative: two independent reviewers for every protected change.
    if len(qualified) < 2:
        return False
    current = _gh(["api", f"repos/{REPO}/pulls/{n}"])
    if current["head"]["sha"] != head or current.get("state") != "open":
        raise Undetermined("PR changed during review evaluation")
    return True


OWNER_DECISION = re.compile(r"^/agent-pr (approve|revoke) ([0-9]+) ([0-9a-f]{40})$")


def owner_decision_allows(comments, owner, n, head, head_created_at):
    """The last exact owner command for this PR and head controls the verdict."""
    decisions = []
    for comment in comments:
        user = comment.get("user") or {}
        if user.get("login", "").lower() != owner.lower() or user.get("type") != "User":
            continue
        match = OWNER_DECISION.fullmatch((comment.get("body") or "").strip())
        if not match or int(match.group(2)) != n or match.group(3) != head:
            continue
        if (comment.get("created_at") or "") < head_created_at:
            continue
        decisions.append((comment["id"], match.group(1)))
    return bool(decisions) and max(decisions)[1] == "approve"


def latest_owner_decision(comments, owner, n):
    """(action, sha, created_at) of the owner's LAST `/agent-pr` command for PR n, any SHA.

    Latest-wins across SHAs: a revoke naming ANY commit of this PR, posted after an approval,
    withdraws it. That is stricter than the exact-head rule needs, on purpose — an approval
    that is carried forward across commits must be withdrawable without guessing which SHA
    the owner meant."""
    latest = None
    for comment in comments:
        user = comment.get("user") or {}
        if user.get("login", "").lower() != owner.lower() or user.get("type") != "User":
            continue
        match = OWNER_DECISION.fullmatch((comment.get("body") or "").strip())
        if not match or int(match.group(2)) != n:
            continue
        key = comment["id"]
        if latest is None or key > latest[0]:
            latest = (key, match.group(1), match.group(3), comment.get("created_at") or "")
    return None if latest is None else latest[1:]


# GitHub's compare API returns at most 300 files and then silently stops. A patch set that
# was cut off cannot be compared, so it is undetermined rather than "equal".
COMPARE_FILE_CAP = 300
# Upper bound on base-merge commits walked between an approval and the head. Exceeding it is
# undetermined, never "no content change".
MAX_CARRY_OVER_COMMITS = 50


def _is_sha(value):
    return bool(re.fullmatch(r"[0-9a-f]{40}", value or ""))


def net_patch(base_sha, sha):
    """The PR's own change as of `sha`: the diff from merge-base(base, sha) to sha, per file.

    Require both rendered patch text and the complete Git blob identity. GitHub does not
    attest that a rendered per-file patch is complete; equal rendered prefixes alone cannot
    prove equal content. A base merge that changes unrelated lines in a PR-touched file may
    now require re-approval even when the rendered patch is equal. That conservative denial
    is preferable to carrying approval across an unverified content change."""
    data = _gh(["api", f"repos/{REPO}/compare/{base_sha}...{sha}"])
    if data.get("status") not in {"ahead", "diverged"}:
        raise Undetermined(f"compare {base_sha[:10]}...{sha[:10]} is `{data.get('status')}` — no PR change to compare")
    files = data.get("files")
    if not isinstance(files, list) or not files:
        raise Undetermined(f"compare {base_sha[:10]}...{sha[:10]} returned no file list")
    if len(files) >= COMPARE_FILE_CAP:
        raise Undetermined(f"compare {base_sha[:10]}...{sha[:10]} hit the {COMPARE_FILE_CAP}-file cap — possibly truncated")
    out = []
    for f in files:
        if "patch" not in f or f.get("filename") is None or not _is_sha(f.get("sha")):
            # Binary file, or a patch GitHub declined to render: its content cannot be compared.
            raise Undetermined(f"compare {sha[:10]}: incomplete file identity for `{f.get('filename')}`")
        out.append((f["filename"], f.get("status"), f.get("previous_filename"), f["patch"], f["sha"]))
    return sorted(out, key=lambda t: t[0])


def only_base_merges_since(approved, head, base_sha):
    """Return base-merge SHAs in chronological order if walking FIRST parents from head
    reaches `approved`; otherwise return None.

    Every walked commit must be a two-parent merge whose SECOND parent is contained in the
    base branch. Commits pulled in through those second parents are main's own commits.
    Anything else — a normal commit, octopus, merge of another branch, or force-push —
    returns None."""
    cur = head
    merges = []
    for _ in range(MAX_CARRY_OVER_COMMITS):
        if cur == approved:
            return list(reversed(merges))
        commit = _gh(["api", f"repos/{REPO}/commits/{cur}"])
        parents = [p.get("sha") for p in (commit.get("parents") or [])]
        if len(parents) != 2 or not all(_is_sha(p) for p in parents):
            return None
        reach = _gh(["api", f"repos/{REPO}/compare/{base_sha}...{parents[1]}"])
        if reach.get("status") not in {"behind", "identical"}:
            return None
        merges.append(cur)
        cur = parents[0]
    if cur == approved:
        return list(reversed(merges))
    raise Undetermined(f"more than {MAX_CARRY_OVER_COMMITS} commits between approval and head")


def content_unchanged_since(approved, head, base_sha):
    """Approval at `approved` carries to `head` iff only base merges happened in between AND
    the PR's net patch and blob identity remain equal after EVERY merge. Checking only the
    endpoints would allow an evil merge that changes the PR followed by one that restores it."""
    if not (_is_sha(approved) and _is_sha(head) and _is_sha(base_sha)):
        raise Undetermined("malformed SHA in carry-over evaluation")
    merges = only_base_merges_since(approved, head, base_sha)
    if merges is None:
        return False
    approved_patch = net_patch(base_sha, approved)
    for merge in merges:
        if net_patch(base_sha, merge) != approved_patch:
            return False
    return True


def owner_approval_allows(n, files):
    # This route cannot introduce or amend its own authorization code.
    if any(f in REVIEW_POLICY_PATHS for f in files):
        return False
    pr = _gh(["api", f"repos/{REPO}/pulls/{n}"])
    head = pr["head"]["sha"]
    if not _is_sha(head):
        raise Undetermined("missing or malformed current PR head SHA")
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if event_path:
        try:
            event = json.loads(Path(event_path).read_text())
            if event.get("pull_request", {}).get("head", {}).get("sha") != head:
                raise Undetermined("workflow event is not for the current PR head")
        except (OSError, ValueError) as exc:
            raise Undetermined("cannot read workflow head identity") from exc
    if pr.get("state") != "open" or pr.get("draft"):
        return False
    owner = _gh(["api", f"repos/{REPO}"])["owner"]["login"]
    if not owner or owner.lower() in {"", "null"}:
        raise Undetermined("repository owner is unavailable")
    commits = _gh(["api", f"repos/{REPO}/commits/{head}"])
    head_created_at = commits["commit"]["committer"]["date"]
    pages = _gh(["api", f"repos/{REPO}/issues/{n}/comments?per_page=100",
                 "--paginate", "--slurp"])
    comments = [c for page in pages for c in page]
    latest = latest_owner_decision(comments, owner, n)
    if latest is None or latest[0] != "approve":
        return False
    if owner_decision_allows(comments, owner, n, head, head_created_at):
        allowed = True
    elif latest[1] != head:
        # Carry-over: the owner approved an EARLIER commit; base merges since then must not
        # void it, any change to the PR's own content must (see content_unchanged_since).
        approved = latest[1]
        approved_at = _gh(["api", f"repos/{REPO}/commits/{approved}"])["commit"]["committer"]["date"]
        if latest[2] < approved_at:
            return False
        base_ref = (pr.get("base") or {}).get("ref") or ""
        if not base_ref:
            raise Undetermined("PR base branch is unavailable")
        base_sha = _gh(["api", f"repos/{REPO}/commits/{base_ref}"]).get("sha", "")
        allowed = content_unchanged_since(approved, head, base_sha)
    else:
        allowed = False
    if not allowed:
        return False
    current = _gh(["api", f"repos/{REPO}/pulls/{n}"])
    if current["head"]["sha"] != head or current.get("state") != "open":
        raise Undetermined("PR changed during owner approval evaluation")
    return True


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


def parse_name_status_z(raw):
    """Return (changed paths, added paths) from `git diff --name-status -z`."""
    fields = raw.split("\0")
    if fields and fields[-1] == "":
        fields.pop()
    files, added = [], set()
    index = 0
    while index < len(fields):
        status = fields[index]
        index += 1
        width = 2 if status.startswith(("R", "C")) else 1
        if index + width > len(fields):
            raise Undetermined("git diff returned malformed name-status data")
        paths = fields[index:index + width]
        index += width
        path = paths[-1]
        files.append(path)
        if status == "A":
            added.add(path)
    return files, frozenset(added)


def fetch_event_pr(n):
    """Read identity from the event and paths from the already-checked-out PR diff."""
    event_path = os.environ.get("GITHUB_EVENT_PATH", "")
    base = os.environ.get("PR_DIFF_BASE", "")
    head = os.environ.get("GITHUB_SHA", "HEAD")
    if not event_path or not base or not os.path.exists(event_path):
        return None
    try:
        with open(event_path) as fh:
            pull = json.load(fh).get("pull_request") or {}
    except (OSError, ValueError, json.JSONDecodeError) as e:
        raise Undetermined(f"could not read pull_request event: {e}") from e
    if int(pull.get("number") or 0) != n:
        return None
    user = pull.get("user") or {}
    head_data = pull.get("head") or {}
    author = user.get("login") or ""
    branch = head_data.get("ref") or ""
    if not author or not branch:
        raise Undetermined(f"PR #{n}: event omitted author/headRefName")
    proc = subprocess.run(
        ["git", "diff", "--name-status", "--find-renames", "-z", base, head],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise Undetermined(f"git diff {base} {head} failed (rc={proc.returncode}): {proc.stderr.strip()}")
    files, added = parse_name_status_z(proc.stdout)
    is_bot = user.get("type") == "Bot" or author.endswith("[bot]")
    return author, is_bot, branch, files, added


def fetch_pr(n):
    local = fetch_event_pr(n)
    if local is not None:
        return local
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

    # The CI fast path derives paths from the checked-out merge diff. Preserve regular,
    # added, renamed and copied records, including spaces, without a REST files call.
    try:
        parsed_files, parsed_added = parse_name_status_z(
            "M\0plain.kt\0A\0new file.kt\0R100\0old.kt\0renamed.kt\0C090\0source.kt\0copy.kt\0"
        )
        if parsed_files != ["plain.kt", "new file.kt", "renamed.kt", "copy.kt"]:
            failures.append(f"name-status parser returned wrong paths: {parsed_files}")
        if parsed_added != frozenset({"new file.kt"}):
            failures.append(f"name-status parser returned wrong added paths: {parsed_added}")
        try:
            parse_name_status_z("R100\0only-old.kt\0")
            failures.append("malformed rename was accepted")
        except Undetermined:
            pass
    except Undetermined as e:
        failures.append(f"valid name-status fixture was rejected: {e}")

    if failures:
        print("SELF-TEST FAILED — the guard is not falsifiable as written:")
        for f in failures:
            print(f"  x {f}")
        return 1
    print(
        f"self-test OK — {len(cases)} classifier cases + 4 undetermined cases + diff parser, "
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
        if code == 1 and owner_approval_allows(n, files):
            code, msg = 0, ("protected changes explicitly approved by repository owner on the "
                            "current head, or on an earlier head followed only by content-neutral base merges")
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
