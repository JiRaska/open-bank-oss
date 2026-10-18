#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Merge train for `main` (ADR-0343): batch armed green PRs, verify the batch as ONE tree, merge in order.

WHY THIS EXISTS
---------------
`main-protection` requires an up-to-date branch (strict required status checks). That closes the
measured defect class — green PRs that merge cleanly and are jointly invalid (19 of 20 main breaks
on 2026-08-22, ADR-0272) — at a structural cost: one merge per CI latency, ~36/day against ~197/day
of demand, with every other candidate re-running CI after each merge. GitHub's merge queue is the
native answer and is unavailable to a user-owned repository. This script is the queue, as a bot.

WHAT IT DOES, per tick
----------------------
  1. Read the required contexts LIVE from the branch rules (never a list in here).
  2. Candidates = open, non-draft, same-repo PRs with auto-merge armed, every required context
     (other than this script's own `merge-train`) `success` at the head, no `agent-pr-guard`
     refusal, head not under an excluded prefix (`codex/`). Oldest first, at most --batch-size.
  3. FAST LANE: a candidate whose files are disjoint from what `main` changed since its merge-base
     AND from every other candidate's files, and that touches no protected path (shared libs,
     build logic, gitops, ADRs, workflows, openapi.yaml, anything in the regen-derived inventory),
     gets `merge-train` = success directly. Its own CI already proved the only thing a train could.
  4. TRAIN: `train/<utc-ts>` = origin/main + `git merge --squash` of each remaining candidate in
     order. A member that does not apply cleanly is DROPPED (PR comment), never failed. Then the
     derived-file generators run over the whole batch and their diff is committed.
  5. VERIFY: push, open a draft carrier PR labelled `merge-train` (the PR lane is where CI runs),
     wait for the required contexts on the train head, close the carrier, delete the branch.
  6. GREEN: per member in order — post `merge-train` = success on its head, squash-merge it, and
     assert main advanced only by that merge (a disjoint foreign merge is tolerated, by the same
     rule as the fast lane; any other stops the run and defers the rest).
     RED: bisect by halving, each half its own train over the same main. The longest verified
     PREFIX merges; a culprit gets a comment with the failing contexts and `merge-train` = failure.

MODES
-----
  --self-test          pure logic + a synthetic git repository with real conflicts (no network)
  --check-declaration  assert merge-train.yml still wires this script the way ADR-0343 requires
  --dry-run            enumerate, classify, build the train locally; push nothing, post nothing
  --shadow             everything except the merge: statuses and comments are posted (phase 0)
  (default)            live
"""
from __future__ import annotations

import argparse
import datetime as dt
import fnmatch
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field

API = os.environ.get("GITHUB_API_URL", "https://api.github.com")
WORKFLOW = pathlib.Path(".github/workflows/merge-train.yml")
REGEN_INVENTORY = pathlib.Path(".github/scripts/regen-derived.sh")

# The commit-status context this script owns. check-ruleset-context-parity.py reads this
# declaration, which is what lets the context be REQUIRED although no pull_request job emits it.
STATUS_CONTEXT = "merge-train"
EXCLUDED_BRANCH_PREFIXES = ("codex/",)
CARRIER_LABEL = "merge-train"
CARRIER_BRANCH_PREFIX = "train/"
# Contexts that judge the PR object (its body, its issue link), not the tree. Green on every
# member already; a carrier PR cannot meaningfully re-earn them and must not wait on them.
TREE_INDEPENDENT_CONTEXTS = frozenset({"issue-hygiene"})
GUARD_CONTEXT_HINT = "agent-pr-guard"
OK_STATES = frozenset({"success"})
# Fast-lane exclusions. Prefixes are directory prefixes; globs are fnmatch over the whole path.
PROTECTED_PREFIXES = ("openbank-libs/", "build-logic/", "openbank-infra/gitops/", "docs/adr/", ".github/")
PROTECTED_GLOBS = ("*/src/main/resources/openapi.yaml",)
REGEN_COMMANDS = ("bash .github/scripts/regen-derived.sh --all", "bash docs/adr/gen-index.sh")
REGEN_COMMIT_SUBJECT = "chore(train): regenerate derived files"
COMMENT_MARK = "<!-- merge-train -->"


def log(msg: str) -> None:
    print(msg, flush=True)


# --------------------------------------------------------------------------- pure: candidates
@dataclass
class Member:
    number: int
    title: str
    body: str
    head_sha: str
    head_ref: str
    created_at: str
    ref: str = ""            # git ref the train squashes (local branch or fetched PR head)
    files: list[str] = field(default_factory=list)


def qualifies(pr: dict, required: list[str], checks: dict[str, str | None]) -> tuple[bool, str]:
    """`checks` maps context -> conclusion/state (None = still running, absent = never reported)."""
    if pr.get("draft"):
        return False, "draft"
    if not pr.get("auto_merge"):
        return False, "auto-merge not armed"
    if not pr.get("same_repo", True):
        return False, "head is a fork"
    ref = pr.get("head_ref") or ""
    for pre in EXCLUDED_BRANCH_PREFIXES:
        if ref.startswith(pre):
            return False, f"excluded prefix {pre}"
    if not required:
        return False, "no required contexts resolved — refusing to judge green"
    for name, state in checks.items():
        if GUARD_CONTEXT_HINT in name and state not in OK_STATES and state is not None:
            return False, f"{name} refused ({state})"
    needed = [c for c in required if c != STATUS_CONTEXT]
    for c in needed:
        if c not in checks:
            return False, f"required context never reported: {c}"
        if checks[c] is None:
            return False, f"required context pending: {c}"
        if checks[c] not in OK_STATES:
            return False, f"required context {checks[c]}: {c}"
    return True, "armed + all required green"


def order_candidates(prs: list[dict]) -> list[dict]:
    return sorted(prs, key=lambda p: (p.get("created_at") or "", p["number"]))


# --------------------------------------------------------------------------- pure: fast lane
def regen_inventory_fragments(script_text: str) -> list[str]:
    """The source path fragments of regen-derived.sh's GENERATORS inventory ("id|cmd|frag frag")."""
    frags: list[str] = []
    for m in re.finditer(r'^\s*"([^"|]+)\|([^"|]+)\|([^"]*)"', script_text, re.MULTILINE):
        frags += m.group(3).split()
    return frags


def is_protected(path: str, fragments: list[str]) -> str | None:
    for pre in PROTECTED_PREFIXES:
        if path.startswith(pre):
            return pre
    for g in PROTECTED_GLOBS:
        if fnmatch.fnmatch(path, g):
            return g
    # Same semantics as regen-derived.sh's matches_any(): a fixed-string substring match.
    for frag in fragments:
        if frag in path:
            return f"regen-derived inventory ({frag})"
    return None


def fast_lane_eligible(pr_files: list[str], main_files: list[str], other_files: list[str],
                       fragments: list[str]) -> tuple[bool, str]:
    for f in pr_files:
        why = is_protected(f, fragments)
        if why:
            return False, f"touches protected path {f} ({why})"
    overlap = sorted(set(pr_files) & set(main_files))
    if overlap:
        return False, f"overlaps main since merge-base: {overlap[0]}"
    overlap = sorted(set(pr_files) & set(other_files))
    if overlap:
        return False, f"overlaps another candidate: {overlap[0]}"
    return True, "disjoint from main and from the batch, no protected path"


def foreign_advance_tolerable(foreign_files: list[str], train_files: list[str],
                              fragments: list[str]) -> bool:
    """A foreign merge during the train is harmless iff it would have fast-laned past the train."""
    ok, _ = fast_lane_eligible(foreign_files, train_files, [], fragments)
    return ok


# --------------------------------------------------------------------------- pure: messages
def first_paragraph(body: str | None) -> str:
    text = (body or "").replace("\r", "")
    for para in re.split(r"\n\s*\n", text):
        lines = [ln for ln in para.strip().splitlines() if not ln.strip().startswith(("#", "<!--"))]
        if lines:
            return "\n".join(lines).strip()
    return ""


def squash_message(m: Member) -> str:
    para = first_paragraph(m.body)
    return f"{m.title}\n\n{para}\n\nPR-Number: #{m.number}\n" if para else f"{m.title}\n\nPR-Number: #{m.number}\n"


# --------------------------------------------------------------------------- pure: bisect
@dataclass
class Outcome:
    mergeable: list[Member] = field(default_factory=list)      # longest verified prefix
    culprits: dict[int, list[str]] = field(default_factory=dict)
    deferred: list[Member] = field(default_factory=list)
    dropped: dict[int, str] = field(default_factory=dict)
    trains: int = 0
    unverified: bool = False


def bisect(members: list[Member], verify, out: Outcome, prefix_open: bool = True) -> None:
    """verify(members) -> (status, failing) with status in {green, red, unknown}.

    Depth-first, left half first. `prefix_open` is True while every train to the left of this
    one was green, i.e. while a green result here still extends the mergeable PREFIX. A green
    train to the right of a red one is verified over main, not over the prefix, so it is deferred.
    """
    if not members:
        return
    status, failing = verify(members)
    out.trains += 1
    if status == "unknown":
        out.unverified = True
        out.deferred += members
        return
    if status == "green":
        if prefix_open:
            out.mergeable += members
        else:
            out.deferred += members
        return
    if len(members) == 1:
        out.culprits[members[0].number] = failing
        return
    mid = len(members) // 2
    bisect(members[:mid], verify, out, prefix_open)
    # The right half can never extend the prefix: the whole was red, so even a green right half
    # is only verified over main, not over the left half. A red whole whose two halves are both
    # green has no single culprit (the pair conflicts): the left half merges, the right half is
    # deferred and fails on its own against the new main, which is what identifies it.
    bisect(members[mid:], verify, out, False)


# --------------------------------------------------------------------------- git
def sh(cmd: list[str], cwd: str | None = None, check: bool = True, env: dict | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, cwd=cwd, text=True, capture_output=True, check=check, env=env)


class TrainBuilder:
    """Builds train branches in a working copy. Pure git; no GitHub."""

    def __init__(self, workdir: str, regen_commands: tuple[str, ...] = REGEN_COMMANDS):
        self.workdir = workdir
        self.regen_commands = regen_commands
        self.env = {**os.environ, "GIT_AUTHOR_NAME": "openbank-gitops-bot", "GIT_COMMITTER_NAME": "openbank-gitops-bot",
                    "GIT_AUTHOR_EMAIL": "gitops-bot@openbank.invalid", "GIT_COMMITTER_EMAIL": "gitops-bot@openbank.invalid"}

    def git(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        return sh(["git", *args], cwd=self.workdir, check=check, env=self.env)

    def build(self, base: str, branch: str, members: list[Member]) -> tuple[str, list[Member], dict[int, str]]:
        """-> (train_sha, applied members in order, {dropped number: reason})."""
        self.git("checkout", "-q", "-B", branch, base)
        applied: list[Member] = []
        dropped: dict[int, str] = {}
        for m in members:
            r = self.git("merge", "--squash", "--no-commit", m.ref, check=False)
            if r.returncode != 0:
                conflicts = self.git("diff", "--name-only", "--diff-filter=U", check=False).stdout.split()
                self.git("merge", "--abort", check=False)
                self.git("reset", "-q", "--hard", "HEAD")
                dropped[m.number] = f"does not apply cleanly on {base[:12]} after {[x.number for x in applied]}: conflicts in {conflicts[:5]}"
                continue
            if not self.git("status", "--porcelain").stdout.strip():
                self.git("reset", "-q", "--hard", "HEAD")
                dropped[m.number] = "empty after squash (already on main?)"
                continue
            self.git("commit", "-q", "-m", squash_message(m))
            applied.append(m)
        if applied:
            self.regenerate()
        return self.git("rev-parse", "HEAD").stdout.strip(), applied, dropped

    def regenerate(self) -> bool:
        for cmd in self.regen_commands:
            r = subprocess.run(shlex.split(cmd), cwd=self.workdir, text=True, capture_output=True, env=self.env)
            if r.returncode != 0:
                # A generator that cannot run is a red train, not a silent skip: commit nothing,
                # let CI say so on the carrier.
                log(f"::warning::regen command failed ({cmd}): {r.stderr.strip()[-300:]}")
        changed = self.git("ls-files", "-m", "-o", "--exclude-standard").stdout.split()
        if not changed:
            return False
        self.git("add", "--", *changed)
        self.git("commit", "-q", "-m", REGEN_COMMIT_SUBJECT)
        return True

    def files_of(self, a: str, b: str) -> list[str]:
        return self.git("diff", "--name-only", f"{a}...{b}").stdout.split()

    def merge_base(self, a: str, b: str) -> str:
        return self.git("merge-base", a, b).stdout.strip()


# --------------------------------------------------------------------------- GitHub side
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


def paginate(path: str, key: str | None = None) -> list:
    out: list = []
    page = 1
    sep = "&" if "?" in path else "?"
    while True:
        data = get(f"{path}{sep}per_page=100&page={page}")
        items = data[key] if key else data
        out += items
        if len(items) < 100:
            return out
        page += 1


def required_policy(repo: str, branch: str) -> tuple[list[str], bool]:
    rules = get(f"/repos/{repo}/rules/branches/{branch}")
    ctx: list[str] = []
    strict = False
    for r in rules:
        if r.get("type") == "required_status_checks":
            p = r["parameters"]
            ctx += [c["context"] for c in p["required_status_checks"]]
            strict |= p.get("strict_required_status_checks_policy") is True
    return sorted(set(ctx)), strict


def head_checks(repo: str, sha: str) -> dict[str, str | None]:
    latest: dict[str, dict] = {}
    for check in paginate(f"/repos/{repo}/commits/{sha}/check-runs", "check_runs"):
        if check["name"] not in latest or check["id"] > latest[check["name"]]["id"]:
            latest[check["name"]] = check
    out = {name: (c["conclusion"] if c["status"] == "completed" else None) for name, c in latest.items()}
    for st in sorted(get(f"/repos/{repo}/commits/{sha}/status")["statuses"], key=lambda s: s["id"], reverse=True):
        out.setdefault(st["context"], None if st["state"] == "pending" else st["state"])
    return out


class Hub:
    """Every GitHub side effect, in one place, so --dry-run/--shadow and the self-test can swap it."""

    def __init__(self, repo: str, branch: str, run_url: str, dry_run: bool, shadow: bool):
        self.repo, self.branch, self.run_url, self.dry_run, self.shadow = repo, branch, run_url, dry_run, shadow

    def gh(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        return sh(["gh", *args, "-R", self.repo], check=check)

    def post_status(self, sha: str, state: str, description: str) -> None:
        if self.dry_run:
            log(f"  [dry-run] status {STATUS_CONTEXT}={state} on {sha[:12]}: {description}")
            return
        code, data = api("POST", f"/repos/{self.repo}/statuses/{sha}",
                         {"state": state, "context": STATUS_CONTEXT, "description": description[:140],
                          "target_url": self.run_url})
        if code != 201:
            raise SystemExit(f"::error::POST status on {sha} -> {code}: {data}")

    def comment(self, number: int, text: str) -> None:
        if self.dry_run:
            log(f"  [dry-run] comment on #{number}: {text.splitlines()[0]}")
            return
        api("POST", f"/repos/{self.repo}/issues/{number}/comments", {"body": f"{COMMENT_MARK}\n{text}"})

    def merge(self, number: int) -> str | None:
        """Squash-merge; returns the merge commit sha once the PR reads MERGED, else None."""
        if self.dry_run or self.shadow:
            log(f"  [{'dry-run' if self.dry_run else 'shadow'}] would squash-merge #{number}")
            return None
        # Auto-merge is armed on every member, so once the required `merge-train` status lands
        # GitHub may merge it before this call; a refusal here is therefore not an error.
        self.gh("pr", "merge", str(number), "--squash", check=False)
        for _ in range(36):
            pr = get(f"/repos/{self.repo}/pulls/{number}")
            if pr.get("merged"):
                return pr["merge_commit_sha"]
            time.sleep(5)
        return None

    def main_sha(self) -> str:
        return get(f"/repos/{self.repo}/git/ref/heads/{self.branch}")["object"]["sha"]

    def compare_files(self, base: str, head: str) -> list[str]:
        return [f["filename"] for f in paginate(f"/repos/{self.repo}/compare/{base}...{head}", "files")]

    def pr_files(self, number: int) -> list[str]:
        return [f["filename"] for f in paginate(f"/repos/{self.repo}/pulls/{number}/files")]

    def open_carrier(self, branch: str, members: list[Member], base_sha: str) -> int | None:
        if self.dry_run:
            return None
        body = (f"{COMMENT_MARK}\nMerge-train carrier (ADR-0343). Verifies, over main `{base_sha[:12]}`, in order:\n\n"
                + "\n".join(f"- Refs #{m.number} — {m.title}" for m in members)
                + "\n\nClosed automatically when the train finishes. Never merge this PR.\n")
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as fh:
            fh.write(body)
        r = self.gh("pr", "create", "--draft", "--base", self.branch, "--head", branch,
                    "--title", f"merge-train: {len(members)} PR(s) over {base_sha[:12]}",
                    "--body-file", fh.name, "--label", CARRIER_LABEL, check=False)
        os.unlink(fh.name)
        if r.returncode != 0:
            log(f"::warning::carrier PR not created: {r.stderr.strip()[-300:]}")
            return None
        m = re.search(r"/pull/(\d+)", r.stdout)
        return int(m.group(1)) if m else None

    def close_carrier(self, number: int | None, branch: str, builder: "TrainBuilder") -> None:
        if number is not None:
            self.gh("pr", "close", str(number), "--delete-branch", check=False)
        builder.git("push", "-q", "origin", "--delete", branch, check=False)


class GitHubVerifier:
    """Push the train, carry it through CI on a draft PR, read the required contexts off its head."""

    def __init__(self, hub: Hub, builder: TrainBuilder, required: list[str], wait_minutes: int, poll_seconds: int):
        self.hub, self.builder, self.required = hub, builder, required
        self.wait_minutes, self.poll_seconds = wait_minutes, poll_seconds

    def __call__(self, members: list[Member], train_branch: str, train_sha: str, base_sha: str) -> tuple[str, list[str]]:
        if self.hub.dry_run:
            log(f"  [dry-run] would push {train_branch}@{train_sha[:12]} and wait for {self.required}")
            return "unknown", []
        self.builder.git("push", "-q", "-f", "origin", f"{train_sha}:refs/heads/{train_branch}")
        carrier = self.hub.open_carrier(train_branch, members, base_sha)
        deadline = time.time() + self.wait_minutes * 60
        failing: list[str] = []
        status = "unknown"
        try:
            while time.time() < deadline:
                checks = head_checks(self.hub.repo, train_sha)
                pending = [c for c in self.required if c not in checks or checks[c] is None]
                failing = [c for c in self.required if checks.get(c) not in (None, *OK_STATES)]
                if failing:
                    status = "red"
                    break
                if not pending:
                    status = "green"
                    break
                time.sleep(self.poll_seconds)
            else:
                log(f"::warning::train {train_sha[:12]} did not finish CI within {self.wait_minutes} min — unverified")
        finally:
            self.hub.close_carrier(carrier, train_branch, self.builder)
        return status, failing


# --------------------------------------------------------------------------- orchestration
def run_train(hub: Hub, builder: TrainBuilder, base_sha: str, members: list[Member], verify,
              fragments: list[str], label: str = "") -> Outcome:
    """Build + bisect + (shadow/live) status/merge phase. `verify(members, branch, sha, base)`."""
    out = Outcome()
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    counter = [0]
    train_files: list[str] = []

    def verify_batch(batch: list[Member]) -> tuple[str, list[str]]:
        counter[0] += 1
        branch = f"{CARRIER_BRANCH_PREFIX}{stamp}{label}-{counter[0]}"
        sha, applied, dropped = builder.build(base_sha, branch, batch)
        for n, why in dropped.items():
            if n not in out.dropped:
                out.dropped[n] = why
                hub.comment(n, f"merge-train: dropped from train `{branch}` — {why}. Rebase or resolve, then it is picked up again.")
        kept = [m for m in batch if m.number not in dropped]
        if not kept:
            return "green", []
        if counter[0] == 1:
            train_files[:] = builder.files_of(base_sha, sha)
        status, failing = verify(kept, branch, sha, base_sha)
        log(f"  train {branch} ({[m.number for m in kept]}) -> {status} {failing}")
        batch[:] = kept   # bisect halves what APPLIED, so a dropped member is never a culprit
        return status, failing

    bisect(members, verify_batch, out)
    out.deferred = [m for m in out.deferred if m.number not in out.dropped]

    for n, failing in out.culprits.items():
        m = next(x for x in members if x.number == n)
        hub.post_status(m.head_sha, "failure", f"failed on train over main {base_sha[:12]}: {', '.join(failing)[:80]}")
        hub.comment(n, f"merge-train: this PR is the culprit of a red train over main `{base_sha[:12]}` — "
                       f"failing contexts: {', '.join(failing) or '(unknown)'}. Push a fix; the status clears on the next train that passes.")

    expected = base_sha
    for i, m in enumerate(out.mergeable):
        if not (hub.dry_run or hub.shadow):
            actual = hub.main_sha()
            if actual != expected:
                foreign = hub.compare_files(expected, actual)
                if foreign_advance_tolerable(foreign, train_files, fragments):
                    log(f"  main advanced by a disjoint foreign merge ({actual[:12]}); continuing")
                    expected = actual
                else:
                    log(f"::warning::main advanced by a foreign merge touching train files ({actual[:12]}); deferring {[x.number for x in out.mergeable[i:]]}")
                    out.deferred = out.mergeable[i:] + out.deferred
                    out.mergeable = out.mergeable[:i]
                    break
        hub.post_status(m.head_sha, "success", f"verified on train over main {base_sha[:12]}")
        merged = hub.merge(m.number)
        if merged:
            expected = merged
        elif not (hub.dry_run or hub.shadow):
            log(f"::warning::#{m.number} did not merge; deferring the rest")
            out.deferred = out.mergeable[i + 1:] + out.deferred
            out.mergeable = out.mergeable[:i + 1]
            break
    return out


def live(args: argparse.Namespace) -> int:
    repo, branch = args.repo, args.branch
    hub = Hub(repo, branch, args.run_url, args.dry_run, args.shadow)
    required, strict = required_policy(repo, branch)
    member_required = [c for c in required if c != STATUS_CONTEXT]
    train_required = [c for c in member_required if c not in TREE_INDEPENDENT_CONTEXTS]
    log(f"required={required} strict={strict} mode={'dry-run' if args.dry_run else 'shadow' if args.shadow else 'LIVE'}")
    if STATUS_CONTEXT not in required and not (args.shadow or args.dry_run):
        log(f"::error::{STATUS_CONTEXT} is not a required context yet — refusing to merge outside shadow mode (ADR-0343 phase 1 not reached)")
        return 2

    fragments = regen_inventory_fragments(REGEN_INVENTORY.read_text()) if REGEN_INVENTORY.exists() else []
    candidates: list[Member] = []
    for p in order_candidates(paginate(f"/repos/{repo}/pulls?state=open&base={branch}")):
        if (p.get("head") or {}).get("ref", "").startswith(CARRIER_BRANCH_PREFIX):
            continue
        view = {"draft": p.get("draft"), "auto_merge": p.get("auto_merge"), "number": p["number"],
                "same_repo": (p.get("head", {}).get("repo") or {}).get("full_name") == repo,
                "head_ref": p["head"]["ref"], "created_at": p["created_at"]}
        ok, why = qualifies(view, member_required, head_checks(repo, p["head"]["sha"]) if view["auto_merge"] and not view["draft"] else {})
        log(f"#{p['number']:>6} {'CANDIDATE' if ok else 'skip     '} {why}")
        if ok:
            candidates.append(Member(p["number"], p["title"], p.get("body") or "", p["head"]["sha"],
                                     p["head"]["ref"], p["created_at"]))
        if len(candidates) >= args.batch_size:
            break
    if not candidates:
        log("nothing to do")
        write_summary(["merge-train: no candidates"])
        return 0

    workdir = args.workdir or os.getcwd()
    builder = TrainBuilder(workdir)
    builder.git("fetch", "-q", "origin", branch, *[f"+refs/pull/{m.number}/head:refs/train/pr-{m.number}" for m in candidates])
    base_sha = builder.git("rev-parse", f"origin/{branch}").stdout.strip()
    for m in candidates:
        m.ref = f"refs/train/pr-{m.number}"
        m.files = builder.files_of(base_sha, m.ref)

    # Fast lane.
    train_members: list[Member] = []
    for m in candidates:
        mb = builder.merge_base(f"origin/{branch}", m.ref)
        main_files = builder.files_of(mb, f"origin/{branch}")
        others = [f for o in candidates if o is not m for f in o.files]
        ok, why = fast_lane_eligible(m.files, main_files, others, fragments)
        if ok:
            log(f"#{m.number} FAST LANE: {why}")
            hub.post_status(m.head_sha, "success", f"fast lane: disjoint from main {base_sha[:12]} and the batch")
        else:
            log(f"#{m.number} train: {why}")
            train_members.append(m)

    out = Outcome()
    if train_members:
        verifier = GitHubVerifier(hub, builder, train_required, args.wait_minutes, args.poll_seconds)
        out = run_train(hub, builder, base_sha, train_members, verifier, fragments)
    lines = [f"merge-train over {base_sha[:12]} ({'shadow' if args.shadow else 'dry-run' if args.dry_run else 'live'}): "
             f"{out.trains} train(s), fast-lane {len(candidates) - len(train_members)}, "
             f"verified {[m.number for m in out.mergeable]}, culprits {dict(out.culprits)}, "
             f"dropped {dict(out.dropped)}, deferred {[m.number for m in out.deferred]}"
             + (", UNVERIFIED (CI timeout)" if out.unverified else "")]
    log(lines[0])
    write_summary(lines)
    return 0


def write_summary(lines: list[str]) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a") as fh:
            fh.write("\n".join(lines) + "\n")


# --------------------------------------------------------------------------- declaration gate
DECLARATION = [
    ("App token minted", "actions/create-github-app-token@"),
    ("App token in GH_TOKEN", "GH_TOKEN: ${{ steps.app_token.outputs.token }}"),
    ("schedule", "schedule:"),
    ("manual dispatch", "workflow_dispatch:"),
    ("concurrency group", "group: merge-train"),
    ("never cancels a train in flight", "cancel-in-progress: false"),
    ("shadow mode is the default", "MERGE_TRAIN_SHADOW: \"true\""),
    ("script invoked", "python3 .github/scripts/merge-train.py"),
    ("regen needs opa", "openpolicyagent.org/downloads/"),
]


def check_declaration() -> int:
    if not WORKFLOW.exists():
        print(f"::error::{WORKFLOW} missing")
        print("SUBJECTS=0")
        return 1
    text = WORKFLOW.read_text()
    fails = [label for label, needle in DECLARATION if needle not in text]
    if "contents: write" in text:
        fails.append("GITHUB_TOKEN must stay read-only (pushes use the App token)")
    for f in fails:
        print(f"::error::merge-train.yml declaration: {f}")
    print(f"SUBJECTS={len(DECLARATION) + 1}")
    print("merge-train declaration OK" if not fails else f"{len(fails)} declaration failure(s)")
    return 1 if fails else 0


# --------------------------------------------------------------------------- self-test
class FakeHub(Hub):
    def __init__(self, main: str = "base"):
        super().__init__("o/r", "main", "http://run", dry_run=False, shadow=False)
        self.statuses: list[tuple[str, str, str]] = []
        self.comments: list[tuple[int, str]] = []
        self.merged: list[int] = []
        self.main = main
        self.foreign: list[str] = []

    def post_status(self, sha, state, description):
        self.statuses.append((sha, state, description))

    def comment(self, number, text):
        self.comments.append((number, text))

    def merge(self, number):
        if self.shadow:
            return None
        self.merged.append(number)
        self.main = f"merge-{number}"
        return self.main

    def main_sha(self):
        return self.main

    def compare_files(self, base, head):
        return self.foreign


def _synthetic_repo(root: str) -> dict[str, str]:
    """A repo whose CI has three contexts, each falsifiable:
       gates (data)        count.txt must equal the number of files under services/
       Validate manifests  bad.txt must not exist
       derived             derived/index.txt must equal the regenerated listing of services/"""
    env = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}

    def g(*a, check=True):
        return subprocess.run(["git", *a], cwd=root, text=True, capture_output=True, check=check, env=env)

    def w(rel, text):
        p = pathlib.Path(root, rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    g("init", "-q", "-b", "main")
    g("config", "commit.gpgsign", "false")
    w("services/s1.txt", "s1\n")
    w("count.txt", "1\n")
    w("regen.py", "import os,pathlib\nos.makedirs('derived',exist_ok=True)\n"
                  "pathlib.Path('derived/index.txt').write_text('\\n'.join(sorted(os.listdir('services')))+'\\n')\n")
    w("ci.py", "import os,pathlib,sys\nfail=[]\n"
               "if int(open('count.txt').read()) != len(os.listdir('services')): fail.append('gates (data)')\n"
               "if os.path.exists('bad.txt'): fail.append('Validate manifests')\n"
               "want='\\n'.join(sorted(os.listdir('services')))+'\\n'\n"
               "if not os.path.exists('derived/index.txt') or open('derived/index.txt').read()!=want: fail.append('derived')\n"
               "print(','.join(fail)); sys.exit(1 if fail else 0)\n")
    subprocess.run([sys.executable, "regen.py"], cwd=root, check=True)
    g("add", "-A")
    g("commit", "-q", "-m", "base")
    base = g("rev-parse", "HEAD").stdout.strip()

    def pr(name, edits):
        g("checkout", "-q", "-b", name, "main")
        for rel, text in edits.items():
            if text is None:
                os.unlink(os.path.join(root, rel))
            else:
                w(rel, text)
        g("add", "-A")
        g("commit", "-q", "-m", name)
        g("checkout", "-q", "main")

    # Semantic pair: each alone green (count 2 == 2 services), together red (count 2, 3 services).
    pr("pA", {"services/a.txt": "a\n", "count.txt": "2\n"})
    pr("pB", {"services/b.txt": "b\n", "count.txt": "2\n"})
    # Textual conflict with pA on count.txt.
    pr("pC", {"count.txt": "7\n", "services/c1.txt": "c\n", "services/c2.txt": "c\n", "services/c3.txt": "c\n",
              "services/c4.txt": "c\n", "services/c5.txt": "c\n", "services/c6.txt": "c\n"})
    # Independent docs PRs + one bad one (#3 of the bisect batch).
    pr("p1", {"docs/one.md": "1\n"})
    pr("p2", {"docs/two.md": "2\n"})
    pr("p3", {"bad.txt": "x\n"})
    pr("p4", {"docs/four.md": "4\n"})
    # Needs regeneration: adds a service and bumps the count, but does not touch derived/index.txt.
    pr("pR", {"services/r.txt": "r\n", "count.txt": "2\n"})
    return {"base": base}


def self_test() -> int:
    fails: list[str] = []
    ran: list[str] = []

    def check(label: str, cond: bool, detail: str = "") -> None:
        ran.append(label)
        if not cond:
            fails.append(f"{label}: {detail}")

    # ---- qualifies ----------------------------------------------------------------------
    req = ["all-green", "Gitleaks", STATUS_CONTEXT]
    green = {"all-green": "success", "Gitleaks": "success"}
    base_pr = {"draft": False, "auto_merge": {"enabled_by": "x"}, "same_repo": True, "head_ref": "feat/x", "number": 1}
    check("qualifies: armed + green (merge-train itself not needed)", qualifies(base_pr, req, green)[0])
    check("qualifies: draft refused", not qualifies({**base_pr, "draft": True}, req, green)[0])
    check("qualifies: unarmed refused", not qualifies({**base_pr, "auto_merge": None}, req, green)[0])
    check("qualifies: codex/ excluded", not qualifies({**base_pr, "head_ref": "codex/x"}, req, green)[0])
    check("qualifies: fork refused", not qualifies({**base_pr, "same_repo": False}, req, green)[0])
    check("qualifies: pending refused", not qualifies(base_pr, req, {**green, "Gitleaks": None})[0])
    check("qualifies: failure refused", not qualifies(base_pr, req, {**green, "Gitleaks": "failure"})[0])
    check("qualifies: never-reported refused", not qualifies(base_pr, req, {"all-green": "success"})[0])
    check("qualifies: agent-pr-guard refusal", not qualifies(base_pr, req, {**green, "gates / agent-pr-guard": "failure"})[0])
    check("qualifies: no required set -> refuse", not qualifies(base_pr, [], green)[0])
    ordered = order_candidates([{"number": 9, "created_at": "2026-02-01T00:00:00Z"}, {"number": 3, "created_at": "2026-01-01T00:00:00Z"}])
    check("order: oldest first", [p["number"] for p in ordered] == [3, 9])

    # ---- fast lane -----------------------------------------------------------------------
    frags = regen_inventory_fragments(REGEN_INVENTORY.read_text()) if REGEN_INVENTORY.exists() else []
    check("inventory: fragments parsed from regen-derived.sh", "prompts/registry.yaml" in frags, str(frags))
    check("fast lane: disjoint service file qualifies",
          fast_lane_eligible(["openbank-fx-service/src/main/kotlin/A.kt"], ["openbank-ledger-service/x.kt"], [], frags)[0])
    check("fast lane: docs/adr/ does NOT qualify",
          not fast_lane_eligible(["docs/adr/0343-x.md"], [], [], frags)[0])
    check("fast lane: overlap with main diff does NOT qualify",
          not fast_lane_eligible(["openbank-fx-service/A.kt"], ["openbank-fx-service/A.kt"], [], frags)[0])
    check("fast lane: overlap with another candidate does NOT qualify",
          not fast_lane_eligible(["openbank-fx-service/A.kt"], [], ["openbank-fx-service/A.kt"], frags)[0])
    check("fast lane: openapi.yaml does NOT qualify",
          not fast_lane_eligible(["openbank-fx-service/src/main/resources/openapi.yaml"], [], [], frags)[0])
    check("fast lane: regen inventory source does NOT qualify",
          not fast_lane_eligible(["prompts/registry.yaml"], [], [], frags)[0])
    check("fast lane: .github/ and build-logic/ do NOT qualify",
          not fast_lane_eligible([".github/workflows/x.yml"], [], [], frags)[0]
          and not fast_lane_eligible(["build-logic/x.kts"], [], [], frags)[0])
    check("foreign advance: disjoint tolerated, overlapping not",
          foreign_advance_tolerable(["a/x.kt"], ["b/y.kt"], frags) and not foreign_advance_tolerable(["a/x.kt"], ["a/x.kt"], frags))

    # ---- messages ------------------------------------------------------------------------
    m = Member(42, "fix(fx): rate", "## Summary\n\nFirst para line 1\nline 2\n\nsecond", "s", "r", "t")
    msg = squash_message(m)
    check("squash message: title, first paragraph, trailer",
          msg.startswith("fix(fx): rate\n\nFirst para line 1\nline 2\n") and msg.rstrip().endswith("PR-Number: #42"), msg)

    # ---- bisect, pure --------------------------------------------------------------------
    def mk(*ns):
        return [Member(n, f"pr {n}", "", f"sha{n}", f"b{n}", "t") for n in ns]

    def verify_bad3(batch):
        return ("red", ["Validate manifests"]) if any(x.number == 3 for x in batch) else ("green", [])

    out = Outcome()
    bisect(mk(1, 2, 3, 4), verify_bad3, out)
    check("bisect: culprit is #3 of four", list(out.culprits) == [3], str(out.culprits))
    check("bisect: mergeable prefix is [1,2]", [x.number for x in out.mergeable] == [1, 2], str(out.mergeable))
    check("bisect: #4 deferred (green alone, not verified over the prefix)", [x.number for x in out.deferred] == [4])
    check("bisect: five trains for a 4-batch with one culprit", out.trains == 5, str(out.trains))
    out = Outcome()
    bisect(mk(1, 2, 3, 4), lambda b: ("green", []), out)
    check("bisect: all-green batch is one train, all mergeable", out.trains == 1 and len(out.mergeable) == 4)
    out = Outcome()
    bisect(mk(1), lambda b: ("unknown", []), out)
    check("bisect: unverified defers, no culprit", out.unverified and not out.culprits and len(out.deferred) == 1)

    # ---- synthetic repository: real git, real conflicts ----------------------------------
    with tempfile.TemporaryDirectory() as d:
        info = _synthetic_repo(d)
        builder = TrainBuilder(d, regen_commands=(f"{sys.executable} regen.py",))

        def local_verify(members, branch, sha, base):
            builder.git("checkout", "-q", sha)
            r = subprocess.run([sys.executable, "ci.py"], cwd=d, text=True, capture_output=True)
            return ("green", []) if r.returncode == 0 else ("red", [c for c in r.stdout.strip().split(",") if c])

        def members(*names):
            return [Member(i + 1, n, f"body of {n}", builder.git("rev-parse", n).stdout.strip(), n, f"2026-01-0{i + 1}", ref=n)
                    for i, n in enumerate(names)]

        # Control: each of the semantic pair is green ALONE (otherwise the next case proves nothing).
        for n in ("pA", "pB"):
            hub = FakeHub(info["base"])
            o = run_train(hub, builder, info["base"], members(n), local_verify, [])
            check(f"synthetic control: {n} alone is green and merges", o.mergeable and hub.merged, f"{o}")

        # NEGATIVE 1: two PRs green alone, jointly invalid via count.txt -> the train MUST go red.
        hub = FakeHub(info["base"])
        o = run_train(hub, builder, info["base"], members("pA", "pB"), local_verify, [])
        check("semantic conflict: train of [pA,pB] went red (trains > 1)", o.trains == 3, f"trains={o.trains}")
        check("semantic conflict: pA merges as the verified prefix, pB is deferred, no culprit",
              [x.number for x in o.mergeable] == [1] and [x.number for x in o.deferred] == [2] and not o.culprits, f"{o}")
        check("semantic conflict: pB was NOT merged", hub.merged == [1], str(hub.merged))

        # NEGATIVE 2: bisect over a real tree, #3 bad.
        hub = FakeHub(info["base"])
        o = run_train(hub, builder, info["base"], members("p1", "p2", "p3", "p4"), local_verify, [])
        check("bisect (git): culprit is p3", list(o.culprits) == [3] and o.culprits[3] == ["Validate manifests"], f"{o}")
        check("bisect (git): p1,p2 merged, p4 deferred", hub.merged == [1, 2] and [x.number for x in o.deferred] == [4], f"{hub.merged} {o.deferred}")
        check("bisect (git): culprit got a failure status + comment",
              any(st[1] == "failure" for st in hub.statuses) and any(c[0] == 3 for c in hub.comments))
        check("bisect (git): merged members got success statuses",
              sum(1 for st in hub.statuses if st[1] == "success") == 2)

        # Conflict drop: pC conflicts textually with pA -> dropped with a comment, pA still merges.
        hub = FakeHub(info["base"])
        o = run_train(hub, builder, info["base"], members("pA", "pC"), local_verify, [])
        check("conflict drop: pC dropped, commented, pA merged", 2 in o.dropped and hub.merged == [1] and any(c[0] == 2 for c in hub.comments), f"{o} {hub.comments}")

        # Regeneration: pR is red alone WITHOUT regen (control), green with it, and the regen commit exists.
        no_regen = TrainBuilder(d, regen_commands=())
        hub = FakeHub(info["base"])
        o = run_train(hub, no_regen, info["base"], members("pR"), local_verify, [])
        check("regen control: without regeneration pR is a culprit", list(o.culprits) == [1], f"{o}")
        hub = FakeHub(info["base"])
        o = run_train(hub, builder, info["base"], members("pR"), local_verify, [])
        check("regen: with regeneration pR merges", hub.merged == [1], f"{o}")
        subj = builder.git("log", "-1", "--format=%s", "HEAD").stdout.strip()
        check("regen: train tip is the regeneration commit", subj == REGEN_COMMIT_SUBJECT, subj)
        trailer = builder.git("log", "-1", "--format=%B", "HEAD~1").stdout
        check("squash commit carries PR-Number trailer", "PR-Number: #1" in trailer, trailer)

        # Shadow: statuses posted, nothing merged.
        hub = FakeHub(info["base"])
        hub.shadow = True
        o = run_train(hub, builder, info["base"], members("p1", "p2"), local_verify, [])
        check("shadow: success statuses posted, zero merges", len(hub.statuses) == 2 and hub.merged == [])

        # Foreign merge mid-train: overlapping -> stop after the first; disjoint -> continue.
        hub = FakeHub(info["base"])
        hub.main = "foreign"
        hub.foreign = ["docs/two.md"]
        o = run_train(hub, builder, info["base"], members("p1", "p2"), local_verify, [])
        check("foreign merge touching train files stops the merges", hub.merged == [] and [x.number for x in o.deferred] == [1, 2], f"{hub.merged} {o}")
        hub = FakeHub(info["base"])
        hub.main = "foreign"
        hub.foreign = ["unrelated/file.txt"]
        o = run_train(hub, builder, info["base"], members("p1", "p2"), local_verify, [])
        check("disjoint foreign merge is tolerated", hub.merged == [1, 2], str(hub.merged))

    for f in fails:
        print(f"  [FAIL] {f}")
    print(f"SUBJECTS={len(ran)}")
    print(f"merge-train self-test: {len(ran)} cases, {len(fails)} failed")
    return 1 if fails else 0


# --------------------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-declaration", action="store_true")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--branch", default="main")
    ap.add_argument("--batch-size", type=int, default=int(os.environ.get("TRAIN_BATCH_SIZE", "8")))
    ap.add_argument("--wait-minutes", type=int, default=90)
    ap.add_argument("--poll-seconds", type=int, default=60)
    ap.add_argument("--workdir", default="")
    ap.add_argument("--run-url", default=os.environ.get("MERGE_TRAIN_RUN_URL", ""))
    ap.add_argument("--dry-run", action="store_true", help="enumerate and build locally; push/post/merge nothing")
    ap.add_argument("--shadow", action="store_true", help="post statuses and comments, never merge (phase 0)")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.check_declaration:
        return check_declaration()
    if not args.repo:
        ap.error("--repo or GITHUB_REPOSITORY is required")
    if not args.dry_run and "GH_TOKEN" not in os.environ:
        ap.error("GH_TOKEN is required outside --dry-run")
    return live(args)


if __name__ == "__main__":
    sys.exit(main())
