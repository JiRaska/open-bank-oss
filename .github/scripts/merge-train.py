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

TWO JOBS, ONE TRUST BOUNDARY
----------------------------
The train tree contains PR-authored code, and the derived-file generators RUN inside it. A single
job holding the App token while it ran them would hand any candidate PR a token that can post the
`merge-train` status and merge — a review bypass for every PR. So:

  build    (no secrets; GITHUB_TOKEN read-only, checkout without credentials)
           enumerate candidates, decide the fast lane, stack `train/<ts>` over origin/main for
           the whole batch AND every bisect half (2N-1 trains), run the generators, and emit a
           plan.json plus one git bundle per train. Generators are taken from the TRUSTED BASE
           (`git archive origin/main -- <generator paths>` overlaid onto the train tree) and run
           with a scrubbed allowlisted environment; a member that changes a generator path never
           qualifies, and the overlay asserts it anyway.
  publish  (App token) treats EVERYTHING the build job produced as attacker-controlled: the
           build job ran PR code, so plan.json and the bundles are its output. publish
           re-derives every security-relevant fact live with git and gh only — each member is
           re-fetched (head sha must equal the plan's, armed, open, green, qualifies() re-run on
           live checks and files), the fast lane is reclassified from live diffs, and every
           bundle is proven commit by commit: `PR-Number:` trailers in plan order, each commit's
           tree equal to an execution-free `git merge-tree` of the verified PR head onto the
           previous commit, and the one optional regeneration commit confined to the derived
           output set declared in origin/main's regen-derived.sh. Only then: push, carry the
           train through PR CI on a draft PR, post the `merge-train` status on the verified head
           and squash-merge with --match-head-commit. No file from any train tree is ever executed.

WHAT IT DOES, per tick
----------------------
  1. Required contexts are read LIVE from the branch rules (never a list in here).
  2. Candidates = open, non-draft, same-repo PRs with auto-merge armed, every required context
     (other than this script's own `merge-train`) `success` at the head, no `agent-pr-guard`
     refusal, head not under `codex/`, touching no generator path. Oldest first, <= --batch-size.
  3. FAST LANE: files disjoint from what main changed since the merge-base AND from every other
     candidate, and no protected path -> `merge-train` = success directly.
  4. TRAIN: origin/main + `git merge --squash` of each member in order; a member that does not
     apply cleanly is DROPPED (PR comment), never failed. Generators run over the whole batch and
     their diff is committed.
  5. GREEN: per member in order — status, squash-merge, assert main advanced only by that merge
     (a disjoint foreign merge is tolerated by the fast-lane rule; any other defers the rest).
     RED: bisect by halving using the pre-built halves. The longest verified PREFIX merges; a
     culprit gets a comment naming the failing contexts and `merge-train` = failure.

MODES
-----
  build --out DIR        (no secrets) plan + bundles
  publish --plan DIR     (App token)  verify + status + merge; --shadow posts, never merges
  --dry-run              publish: push/post/merge nothing, print what would happen
  --self-test            pure logic + a synthetic git repository with real conflicts, a token
                         exfiltration probe and a generator-tampering member (no network)
  --check-declaration    assert merge-train.yml still wires the two jobs the way ADR-0343 requires
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
# Code the build job EXECUTES against the train tree. A member touching any of these cannot ride a
# train (it would be running its own change with the generators' authority); the build job also
# overlays these paths from the trusted base before running anything, and asserts the overlay
# changed nothing. Both halves are load-bearing — see the self-test.
GENERATOR_PREFIXES = (".github/scripts/",)
GENERATOR_GLOBS = ("docs/adr/*.sh", "openbank-infra/gitops/*/gen-*", "openbank-infra/gitops/*/*/gen-*",
                   "openbank-infra/gitops/*/*/*/gen-*")
GENERATOR_OVERLAY_ROOTS = (".github/scripts", "docs/adr", "openbank-infra/gitops")
REGEN_COMMANDS = ("bash .github/scripts/regen-derived.sh --all", "bash docs/adr/gen-index.sh")
# The ONLY environment the generators see. No token of any kind is in this list, by construction.
GENERATOR_ENV_ALLOWLIST = ("PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TMP", "TEMP", "TZ",
                           "PYTHONIOENCODING", "PYTHONDONTWRITEBYTECODE", "SSL_CERT_FILE", "RUNNER_TEMP")
REGEN_COMMIT_SUBJECT = "chore(train): regenerate derived files"
COMMENT_MARK = "<!-- merge-train -->"
PLAN_FILE = "plan.json"


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

    def to_json(self) -> dict:
        return {"number": self.number, "title": self.title, "body": self.body, "head_sha": self.head_sha,
                "head_ref": self.head_ref, "created_at": self.created_at, "files": self.files}

    @staticmethod
    def from_json(d: dict) -> "Member":
        return Member(d["number"], d["title"], d.get("body", ""), d["head_sha"], d["head_ref"],
                      d["created_at"], files=list(d.get("files", [])))


def is_generator_path(path: str) -> bool:
    if any(path.startswith(p) for p in GENERATOR_PREFIXES):
        return True
    return any(fnmatch.fnmatch(path, g) for g in GENERATOR_GLOBS)


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
    for f in pr.get("files") or []:
        if is_generator_path(f):
            return False, f"modifies a generator path ({f}) — manual lane, never a train"
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


def train_key(members: list[Member]) -> str:
    return "-".join(str(m.number) for m in members)


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


def bisect_tree(members: list[Member]) -> list[list[Member]]:
    """Every batch the bisect above can ask for, whole first: 2N-1 lists for N members."""
    if not members:
        return []
    out = [members]
    if len(members) > 1:
        mid = len(members) // 2
        out += bisect_tree(members[:mid]) + bisect_tree(members[mid:])
    return out


# --------------------------------------------------------------------------- git: build side
def sh(cmd: list[str], cwd: str | None = None, check: bool = True, env: dict | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, cwd=cwd, text=True, capture_output=True, check=check, env=env)


def generator_env() -> dict[str, str]:
    env = {k: os.environ[k] for k in GENERATOR_ENV_ALLOWLIST if k in os.environ}
    env.update({"GIT_AUTHOR_NAME": "openbank-gitops-bot", "GIT_COMMITTER_NAME": "openbank-gitops-bot",
                "GIT_AUTHOR_EMAIL": "gitops-bot@openbank.invalid", "GIT_COMMITTER_EMAIL": "gitops-bot@openbank.invalid",
                "GIT_CONFIG_NOSYSTEM": "1"})
    return env


@dataclass
class TrainRecord:
    key: str
    branch: str
    sha: str
    applied: list[int]
    dropped: dict[int, str]
    commits: int                       # commits above base: len(applied) (+1 regen)
    bundle: str = ""

    def to_json(self) -> dict:
        return {"key": self.key, "branch": self.branch, "sha": self.sha, "applied": self.applied,
                "dropped": {str(k): v for k, v in self.dropped.items()}, "commits": self.commits, "bundle": self.bundle}

    @staticmethod
    def from_json(d: dict) -> "TrainRecord":
        return TrainRecord(d["key"], d["branch"], d["sha"], list(d["applied"]),
                           {int(k): v for k, v in d["dropped"].items()}, d["commits"], d.get("bundle", ""))


class TrainBuilder:
    """Builds train branches in a working copy. Pure git; no GitHub, no secrets."""

    def __init__(self, workdir: str, regen_commands: tuple[str, ...] = REGEN_COMMANDS,
                 overlay_roots: tuple[str, ...] = GENERATOR_OVERLAY_ROOTS):
        self.workdir = workdir
        self.regen_commands = regen_commands
        self.overlay_roots = overlay_roots
        self.env = generator_env()

    def git(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        return sh(["git", *args], cwd=self.workdir, check=check, env=self.env)

    def build(self, base: str, branch: str, members: list[Member]) -> TrainRecord:
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
            staged = self.git("diff", "--cached", "--name-only").stdout.split()
            if not staged:
                self.git("reset", "-q", "--hard", "HEAD")
                dropped[m.number] = "empty after squash (already on main?)"
                continue
            tampered = [f for f in staged if is_generator_path(f)]
            if tampered:
                # Belt: qualifies() already refused it. Braces: never run a member's generator.
                self.git("reset", "-q", "--hard", "HEAD")
                dropped[m.number] = f"modifies a generator path ({tampered[0]}) — manual lane, never a train"
                continue
            self.git("commit", "-q", "-m", squash_message(m))
            applied.append(m)
        commits = len(applied)
        if applied:
            try:
                self.overlay_trusted_generators(base)
            except RuntimeError as exc:
                # The train tree cannot be trusted to run anything: nobody rides it. Every member
                # is dropped with the reason, the branch is reset to base, and the plan says so.
                log(f"::error::{branch}: {exc}")
                self.git("reset", "-q", "--hard", base)
                for m in applied:
                    dropped[m.number] = f"train refused: {exc}"
                return TrainRecord(train_key(members), branch, base, [], dropped, 0)
            if self.regenerate():
                commits += 1
        sha = self.git("rev-parse", "HEAD").stdout.strip()
        return TrainRecord(train_key(members), branch, sha, [m.number for m in applied], dropped, commits)

    def overlay_trusted_generators(self, base: str) -> None:
        """Replace every generator path in the train tree with the TRUSTED BASE's copy, and assert
        that no generator path then differs from HEAD (if one does, a member smuggled a generator
        change past qualifies() and the build must not run it)."""
        roots = [p for p in self.overlay_roots
                 if self.git("cat-file", "-e", f"{base}:{p}", check=False).returncode == 0]
        if roots:
            archive = subprocess.run(["git", "archive", base, "--", *roots], cwd=self.workdir,
                                     capture_output=True, check=True, env=self.env).stdout
            subprocess.run(["tar", "-x", "-C", self.workdir], input=archive, check=True, capture_output=True)
        # Generator paths that exist in HEAD but not in base survive the overlay — that is an ADDED
        # generator, and it is just as untrusted as a modified one.
        added = [f for f in self.git("diff", "--name-only", "--diff-filter=A", base, "HEAD").stdout.split() if is_generator_path(f)]
        dirty = [ln[3:].strip() for ln in self.git("status", "--porcelain").stdout.splitlines() if is_generator_path(ln[3:].strip())]
        if added or dirty:
            raise RuntimeError(f"generator paths differ from the trusted base after overlay: added={added[:5]} modified={dirty[:5]}")

    def regenerate(self) -> bool:
        for cmd in self.regen_commands:
            r = subprocess.run(shlex.split(cmd), cwd=self.workdir, text=True, capture_output=True, env=self.env)
            if r.returncode != 0:
                # A generator that cannot run is a red train, not a silent skip: commit nothing
                # for it and let CI say so on the carrier.
                log(f"::warning::regen command failed ({cmd}): {r.stderr.strip()[-300:]}")
        changed = self.git("ls-files", "-m", "-o", "--exclude-standard").stdout.split()
        if not changed:
            return False
        self.git("add", "--", *changed)
        self.git("commit", "-q", "-m", REGEN_COMMIT_SUBJECT)
        return True

    def bundle(self, record: TrainRecord, base: str, out_dir: str) -> str:
        path = os.path.join(out_dir, f"train-{record.key}.bundle")
        # A bundle head must be a ref name, never a bare sha ("Refusing to create empty bundle").
        self.git("bundle", "create", path, record.branch, f"^{base}")
        record.bundle = os.path.basename(path)
        return path

    def files_of(self, a: str, b: str) -> list[str]:
        return self.git("diff", "--name-only", f"{a}...{b}").stdout.split()

    def merge_base(self, a: str, b: str) -> str:
        return self.git("merge-base", a, b).stdout.strip()


def prebuild(builder: TrainBuilder, base: str, members: list[Member], out_dir: str | None,
             stamp: str) -> dict[str, TrainRecord]:
    """Build the whole batch and every bisect half over the same base; bundle each when out_dir."""
    trains: dict[str, TrainRecord] = {}
    for i, batch in enumerate(bisect_tree(members), 1):
        rec = builder.build(base, f"{CARRIER_BRANCH_PREFIX}{stamp}-{i}", batch)
        if out_dir:
            builder.bundle(rec, base, out_dir)
        trains[rec.key] = rec
        log(f"  built {rec.branch} {rec.key}: applied={rec.applied} dropped={list(rec.dropped)} sha={rec.sha[:12]}")
    return trains


# --------------------------------------------------------------------------- orchestration (publish)
def run_train(hub, base_sha: str, members: list[Member], trains: dict[str, TrainRecord], verify,
              fragments: list[str], train_files: list[str], invalid: frozenset[int] = frozenset()) -> Outcome:
    """Bisect over PRE-BUILT trains, then the status/merge phase. `verify(record) -> (status, failing)`.
    `invalid` = members that failed live revalidation: any train carrying one is unusable."""
    out = Outcome()

    def verify_batch(batch: list[Member]) -> tuple[str, list[str]]:
        rec = trains.get(train_key(batch))
        if rec is None or set(rec.applied) & invalid:
            return "unknown", []
        for n, why in rec.dropped.items():
            if n not in out.dropped:
                out.dropped[n] = why
                hub.comment(n, f"merge-train: dropped from train `{rec.branch}` — {why}. Rebase or resolve, then it is picked up again.")
        kept = [m for m in batch if m.number in rec.applied]
        batch[:] = kept   # bisect halves what APPLIED, so a dropped member is never a culprit
        if not kept:
            return "green", []
        status, failing = verify(rec)
        log(f"  train {rec.branch} ({rec.applied}) -> {status} {failing}")
        return status, failing

    bisect(list(members), verify_batch, out)
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
        merged = hub.merge(m.number, m.head_sha)
        if merged:
            expected = merged
        elif not (hub.dry_run or hub.shadow):
            log(f"::warning::#{m.number} did not merge; deferring the rest")
            out.deferred = out.mergeable[i + 1:] + out.deferred
            out.mergeable = out.mergeable[:i + 1]
            break
    return out


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


def pr_files(repo: str, number: int) -> list[str]:
    return [f["filename"] for f in paginate(f"/repos/{repo}/pulls/{number}/files")]


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

    def merge(self, number: int, head_sha: str) -> str | None:
        """Squash-merge exactly the verified head; returns the merge commit sha once MERGED, else None."""
        if self.dry_run or self.shadow:
            log(f"  [{'dry-run' if self.dry_run else 'shadow'}] would squash-merge #{number} @ {head_sha[:12]}")
            return None
        # Auto-merge is armed on every member, so once the required `merge-train` status lands
        # GitHub may merge it before this call; a refusal here is therefore not an error.
        # --match-head-commit: a push between verification and merge makes GitHub refuse.
        self.gh("pr", "merge", str(number), "--squash", "--match-head-commit", head_sha, check=False)
        for _ in range(36):
            pr = get(f"/repos/{self.repo}/pulls/{number}")
            if pr.get("merged"):
                return pr["merge_commit_sha"] if pr.get("head", {}).get("sha") == head_sha else None
            time.sleep(5)
        return None

    def main_sha(self) -> str:
        return get(f"/repos/{self.repo}/git/ref/heads/{self.branch}")["object"]["sha"]

    def compare_files(self, base: str, head: str) -> list[str]:
        return [f["filename"] for f in paginate(f"/repos/{self.repo}/compare/{base}...{head}", "files")]

    def open_carrier(self, branch: str, numbers: list[int], base_sha: str) -> int | None:
        if self.dry_run:
            return None
        body = (f"{COMMENT_MARK}\nMerge-train carrier (ADR-0343). Verifies, over main `{base_sha[:12]}`, in order:\n\n"
                + "\n".join(f"- Refs #{n}" for n in numbers)
                + "\n\nClosed automatically when the train finishes. Never merge this PR.\n")
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as fh:
            fh.write(body)
        r = self.gh("pr", "create", "--draft", "--base", self.branch, "--head", branch,
                    "--title", f"merge-train: {len(numbers)} PR(s) over {base_sha[:12]}",
                    "--body-file", fh.name, "--label", CARRIER_LABEL, check=False)
        os.unlink(fh.name)
        if r.returncode != 0:
            log(f"::warning::carrier PR not created: {r.stderr.strip()[-300:]}")
            return None
        m = re.search(r"/pull/(\d+)", r.stdout)
        return int(m.group(1)) if m else None

    def close_carrier(self, number: int | None, branch: str, git) -> None:
        if number is not None:
            self.gh("pr", "close", str(number), "--delete-branch", check=False)
        git("push", "-q", "origin", "--delete", branch, check=False)


def verify_bundle(git, plan_dir: str, rec: TrainRecord, base_sha: str) -> str | None:
    """Fetch the bundle and prove it is `base_sha` + exactly the commits the plan says; else why not."""
    path = os.path.join(plan_dir, rec.bundle)
    if not os.path.isfile(path):
        return f"bundle missing: {rec.bundle}"
    if git("bundle", "verify", path, check=False).returncode != 0:
        return "git bundle verify failed (prerequisite base not present or bundle corrupt)"
    git("fetch", "-q", path, f"refs/heads/{rec.branch}")
    fetched = git("rev-parse", "FETCH_HEAD").stdout.strip()
    if fetched != rec.sha:
        return f"bundle head {fetched[:12]} is not the sha the plan recorded ({rec.sha[:12]})"
    if git("merge-base", "--is-ancestor", base_sha, rec.sha, check=False).returncode != 0:
        return f"train {rec.sha[:12]} is not a descendant of base {base_sha[:12]}"
    n = int(git("rev-list", "--count", f"{base_sha}..{rec.sha}").stdout.strip())
    if n != rec.commits:
        return f"train has {n} commits above base, plan says {rec.commits}"
    return None


def derived_output_patterns(script_text: str) -> tuple[list[str], list[str]]:
    """(full-rewrite globs, annotation-only globs) from regen-derived.sh's DERIVED_* arrays."""
    def block(name: str) -> list[str]:
        m = re.search(rf"^{name}=\((.*?)^\)", script_text, re.MULTILINE | re.DOTALL)
        return re.findall(r'"([^"]+)"', m.group(1)) if m else []
    return block("DERIVED_OUTPUTS"), block("DERIVED_ANNOTATION_ONLY")


def safe_bundle_name(name: str) -> bool:
    return bool(name) and "/" not in name and "\\" not in name and ".." not in name and not os.path.isabs(name) \
        and name.endswith(".bundle")


def revalidate(entry: dict, live: dict | None, required: list[str], live_checks: dict[str, str | None]) -> tuple[Member | None, str]:
    """A plan entry is only as good as the LIVE PR behind it. `live` is the PR view as build()
    builds it (plus title/body), `live_checks` the head's contexts right now. Pure."""
    if live is None:
        return None, "PR not found live"
    if live.get("state", "open") != "open":
        return None, f"PR is {live.get('state')}"
    if live.get("head_sha") != entry["head_sha"]:
        return None, f"head moved: plan {entry['head_sha'][:12]}, live {str(live.get('head_sha'))[:12]}"
    ok, why = qualifies(live, required, live_checks)
    if not ok:
        return None, f"no longer qualifies: {why}"
    return Member(entry["number"], live.get("title", ""), live.get("body", "") or "", live["head_sha"],
                  live.get("head_ref", ""), live.get("created_at", ""), files=list(live.get("files") or [])), "ok"


def regen_commit_problem(git, commit: str, outputs: list[str], annotation_only: list[str]) -> str | None:
    """The regeneration commit is the one unreviewed commit in a train; bound it to the declared outputs."""
    if git("log", "-1", "--format=%s", commit).stdout.strip() != REGEN_COMMIT_SUBJECT:
        return f"extra commit {commit[:12]} is neither a member squash nor the regeneration commit"
    for path in git("diff", "--name-only", f"{commit}^", commit).stdout.split():
        if any(fnmatch.fnmatch(path, g) for g in outputs):
            continue
        if any(fnmatch.fnmatch(path, g) for g in annotation_only):
            lines = [ln for ln in git("diff", "-U0", f"{commit}^", commit, "--", path).stdout.splitlines()
                     if (ln.startswith("+") or ln.startswith("-")) and not ln.startswith(("+++", "---"))]
            if all("policy-checksum" in ln for ln in lines):
                continue
            return f"regeneration commit changes more than the policy-checksum annotation in {path}"
        return f"regeneration commit touches {path}, which no generator is declared to write"
    return None


def verify_train(git, plan_dir: str, rec: TrainRecord, base_sha: str, heads: dict[int, str],
                 outputs: list[str], annotation_only: list[str]) -> str | None:
    """Prove a bundle is base + the plan's squashes (rebuilt execution-free) + at most one bounded
    regeneration commit. `heads` maps PR number -> LIVE-verified head sha. Returns why not, or None."""
    if not safe_bundle_name(rec.bundle):
        return f"unsafe bundle name {rec.bundle!r}"
    problem = verify_bundle(git, plan_dir, rec, base_sha)
    if problem:
        return problem
    commits = git("rev-list", "--reverse", f"{base_sha}..{rec.sha}").stdout.split()
    if len(commits) not in (len(rec.applied), len(rec.applied) + 1):
        return f"{len(commits)} commits for {len(rec.applied)} members"
    prev = base_sha
    for i, c in enumerate(commits):
        numbers = [int(n) for n in re.findall(r"^PR-Number: #(\d+)$", git("log", "-1", "--format=%B", c).stdout, re.MULTILINE)]
        if i == len(rec.applied):
            if numbers:
                return f"commit {c[:12]} carries a PR-Number trailer beyond the plan's members"
            return regen_commit_problem(git, c, outputs, annotation_only)
        if numbers != [rec.applied[i]]:
            return f"commit {c[:12]} trailer {numbers} != plan member #{rec.applied[i]}"
        head = heads.get(rec.applied[i])
        if not head:
            return f"member #{rec.applied[i]} has no live-verified head"
        mt = git("merge-tree", "--write-tree", prev, head, check=False)
        if mt.returncode != 0:
            return f"#{rec.applied[i]} does not merge cleanly onto {prev[:12]} (merge-tree rc={mt.returncode})"
        want = mt.stdout.split()[0]
        got = git("rev-parse", f"{c}^{{tree}}").stdout.strip()
        if want != got:
            return f"commit {c[:12]} tree {got[:12]} != squash of #{rec.applied[i]} onto {prev[:12]} ({want[:12]})"
        prev = c
    return None


class GitHubVerifier:
    """Push a pre-built train, carry it through CI on a draft PR, read the required contexts off its head.
    Runs git and gh only; nothing from the train tree is checked out or executed."""

    def __init__(self, hub: Hub, git, plan_dir: str, base_sha: str, required: list[str],
                 wait_minutes: int, poll_seconds: int, prove):
        self.hub, self.git, self.plan_dir, self.base_sha, self.required = hub, git, plan_dir, base_sha, required
        self.wait_minutes, self.poll_seconds, self.prove = wait_minutes, poll_seconds, prove

    def __call__(self, rec: TrainRecord) -> tuple[str, list[str]]:
        problem = self.prove(rec)
        if problem:
            log(f"::error::refusing to publish {rec.branch}: {problem}")
            return "unknown", []
        if self.hub.dry_run:
            log(f"  [dry-run] would push {rec.branch}@{rec.sha[:12]} and wait for {self.required}")
            return "unknown", []
        self.git("push", "-q", "-f", "origin", f"{rec.sha}:refs/heads/{rec.branch}")
        carrier = self.hub.open_carrier(rec.branch, rec.applied, self.base_sha)
        deadline = time.time() + self.wait_minutes * 60
        failing: list[str] = []
        status = "unknown"
        try:
            while time.time() < deadline:
                checks = head_checks(self.hub.repo, rec.sha)
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
                log(f"::warning::train {rec.sha[:12]} did not finish CI within {self.wait_minutes} min — unverified")
        finally:
            self.hub.close_carrier(carrier, rec.branch, self.git)
        return status, failing


# --------------------------------------------------------------------------- modes: build / publish
def build(args: argparse.Namespace) -> int:
    repo, branch = args.repo, args.branch
    required, strict = required_policy(repo, branch)
    member_required = [c for c in required if c != STATUS_CONTEXT]
    log(f"required={required} strict={strict}")
    fragments = regen_inventory_fragments(REGEN_INVENTORY.read_text()) if REGEN_INVENTORY.exists() else []
    out_dir = args.out
    os.makedirs(out_dir, exist_ok=True)

    candidates: list[Member] = []
    for p in order_candidates(paginate(f"/repos/{repo}/pulls?state=open&base={branch}")):
        if (p.get("head") or {}).get("ref", "").startswith(CARRIER_BRANCH_PREFIX):
            continue
        armed = bool(p.get("auto_merge")) and not p.get("draft")
        view = {"draft": p.get("draft"), "auto_merge": p.get("auto_merge"), "number": p["number"],
                "same_repo": (p.get("head", {}).get("repo") or {}).get("full_name") == repo,
                "head_ref": p["head"]["ref"], "created_at": p["created_at"],
                "files": pr_files(repo, p["number"]) if armed else []}
        ok, why = qualifies(view, member_required, head_checks(repo, p["head"]["sha"]) if armed else {})
        log(f"#{p['number']:>6} {'CANDIDATE' if ok else 'skip     '} {why}")
        if ok:
            candidates.append(Member(p["number"], p["title"], p.get("body") or "", p["head"]["sha"],
                                     p["head"]["ref"], p["created_at"], files=view["files"]))
        if len(candidates) >= args.batch_size:
            break

    plan: dict = {"repo": repo, "branch": branch, "required": required, "strict": strict,
                  "built_at": dt.datetime.now(dt.timezone.utc).isoformat(), "fast_lane": [], "members": [], "trains": {}}
    if not candidates:
        log("nothing to do")
        plan["base_sha"] = ""
        pathlib.Path(out_dir, PLAN_FILE).write_text(json.dumps(plan, indent=1))
        write_output("has_work", "false")
        return 0

    builder = TrainBuilder(args.workdir or os.getcwd())
    builder.git("fetch", "-q", "origin", branch, *[f"+refs/pull/{m.number}/head:refs/train/pr-{m.number}" for m in candidates])
    base_sha = builder.git("rev-parse", f"origin/{branch}").stdout.strip()
    plan["base_sha"] = base_sha
    for m in candidates:
        m.ref = f"refs/train/pr-{m.number}"
        m.files = builder.files_of(base_sha, m.ref)

    train_members: list[Member] = []
    for m in candidates:
        mb = builder.merge_base(f"origin/{branch}", m.ref)
        main_files = builder.files_of(mb, f"origin/{branch}")
        others = [f for o in candidates if o is not m for f in o.files]
        ok, why = fast_lane_eligible(m.files, main_files, others, fragments)
        log(f"#{m.number} {'FAST LANE' if ok else 'train'}: {why}")
        (plan["fast_lane"].append(m.to_json()) if ok else train_members.append(m))

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    trains = prebuild(builder, base_sha, train_members, out_dir, stamp) if train_members else {}
    whole = trains.get(train_key(train_members))
    plan["members"] = [m.to_json() for m in train_members]
    plan["trains"] = {k: r.to_json() for k, r in trains.items()}
    plan["train_files"] = builder.files_of(base_sha, whole.sha) if whole else []
    pathlib.Path(out_dir, PLAN_FILE).write_text(json.dumps(plan, indent=1))
    write_output("has_work", "true")
    log(f"plan written: base {base_sha[:12]}, fast-lane {len(plan['fast_lane'])}, train members {len(train_members)}, trains {len(trains)}")
    return 0


def live_pr_view(repo: str, number: int) -> dict | None:
    code, p = api("GET", f"/repos/{repo}/pulls/{number}")
    if code != 200:
        return None
    return {"number": number, "state": p.get("state"), "draft": p.get("draft"), "auto_merge": p.get("auto_merge"),
            "same_repo": (p.get("head", {}).get("repo") or {}).get("full_name") == repo,
            "head_ref": p["head"]["ref"], "head_sha": p["head"]["sha"], "created_at": p["created_at"],
            "title": p.get("title", ""), "body": p.get("body") or "", "files": pr_files(repo, number)}


def publish(args: argparse.Namespace) -> int:
    """Everything in `plan` came out of a job that ran PR code. Nothing in it is believed; it is a
    set of CLAIMS that this function re-derives live, with git and gh, before acting on any."""
    plan = json.loads(pathlib.Path(args.plan, PLAN_FILE).read_text())
    repo, branch, base_sha = str(plan["repo"]), str(plan["branch"]), str(plan.get("base_sha") or "")
    if repo != args.repo or branch != args.branch:
        log(f"::error::plan is for {repo}@{branch}, this job is {args.repo}@{args.branch}")
        return 2
    hub = Hub(repo, branch, args.run_url, args.dry_run, args.shadow)
    mode = "dry-run" if args.dry_run else "shadow" if args.shadow else "LIVE"
    required, strict = required_policy(repo, branch)
    member_required = [c for c in required if c != STATUS_CONTEXT]
    log(f"required={required} strict={strict} mode={mode} plan base {base_sha[:12] or '(none)'}")
    if STATUS_CONTEXT not in required and not (args.shadow or args.dry_run):
        log(f"::error::{STATUS_CONTEXT} is not a required context yet — refusing to merge outside shadow mode (ADR-0343 phase 1 not reached)")
        return 2
    if not base_sha:
        log("nothing to do")
        return 0
    workdir = args.workdir or os.getcwd()

    def git(*a: str, check: bool = True) -> subprocess.CompletedProcess:
        return sh(["git", *a], cwd=workdir, check=check)

    git("fetch", "-q", "origin", branch)
    live_main = git("rev-parse", f"origin/{branch}").stdout.strip()
    if git("merge-base", "--is-ancestor", base_sha, live_main, check=False).returncode != 0:
        log(f"::error::plan base {base_sha[:12]} is not an ancestor of {branch} ({live_main[:12]}) — refusing the plan")
        return 2
    if live_main != base_sha:
        log(f"main moved since the plan ({base_sha[:12]} -> {live_main[:12]}); the foreign-advance rule decides per merge")

    # Trusted inputs come from origin/main, never from the plan or a train tree.
    trusted_regen = git("show", f"origin/{branch}:{REGEN_INVENTORY.as_posix()}", check=False).stdout
    fragments = regen_inventory_fragments(trusted_regen)
    outputs, annotation_only = derived_output_patterns(trusted_regen)
    if not outputs:
        log("::error::origin/main declares no DERIVED_OUTPUTS — cannot bound a regeneration commit, refusing")
        return 2

    # 1. Re-derive every member live. The plan's head_sha is a claim; the live PR decides.
    entries = [("fast", e) for e in plan.get("fast_lane", [])] + [("train", e) for e in plan.get("members", [])]
    live: dict[int, Member] = {}
    invalid: set[int] = set()
    for lane, e in entries:
        n = int(e["number"])
        view = live_pr_view(repo, n)
        checks = head_checks(repo, view["head_sha"]) if view else {}
        m, why = revalidate(e, view, member_required, checks)
        if m is None:
            log(f"#{n} ({lane}) REJECTED live: {why}")
            invalid.add(n)
        else:
            live[n] = m
    if live:
        git("fetch", "-q", "origin", *[f"+refs/pull/{n}/head:refs/train/pr-{n}" for n in live])
    heads: dict[int, str] = {}
    for n, m in list(live.items()):
        fetched = git("rev-parse", f"refs/train/pr-{n}").stdout.strip()
        if fetched != m.head_sha:
            log(f"#{n} REJECTED: refs/pull/{n}/head is {fetched[:12]}, live API says {m.head_sha[:12]}")
            invalid.add(n)
            del live[n]
        else:
            heads[n] = fetched

    # 2. Reclassify the fast lane from live diffs; the plan's classification is ignored.
    fast_numbers = [int(e["number"]) for e in plan.get("fast_lane", []) if int(e["number"]) in live]
    for n in fast_numbers:
        m = live[n]
        mb = git("merge-base", f"origin/{branch}", m.head_sha).stdout.strip()
        main_files = git("diff", "--name-only", f"{mb}..origin/{branch}").stdout.split()
        others = [f for o, om in live.items() if o != n for f in om.files]
        ok, why = fast_lane_eligible(m.files, main_files, others, fragments)
        if ok:
            log(f"#{n} FAST LANE (live): {why}")
            hub.post_status(m.head_sha, "success", f"fast lane: disjoint from main {base_sha[:12]} and the batch")
        else:
            log(f"#{n} plan said fast lane, live says no ({why}) — nothing posted, next tick re-plans")

    # 3. Trains: members in plan order; every bundle is proven before it is pushed.
    members = [live[int(e["number"])] if int(e["number"]) in live else Member.from_json(e) for e in plan.get("members", [])]
    trains = {k: TrainRecord.from_json(r) for k, r in plan.get("trains", {}).items()}
    out = Outcome()
    if members:
        train_required = [c for c in required if c != STATUS_CONTEXT and c not in TREE_INDEPENDENT_CONTEXTS]

        def prove(rec: TrainRecord) -> str | None:
            return verify_train(git, args.plan, rec, base_sha, heads, outputs, annotation_only)

        verifier = GitHubVerifier(hub, git, args.plan, base_sha, train_required, args.wait_minutes, args.poll_seconds, prove)
        train_files = [f for m in members for f in m.files]
        out = run_train(hub, base_sha, members, trains, verifier, fragments, train_files, frozenset(invalid))
    line = (f"merge-train over {base_sha[:12]} ({mode}): {out.trains} train(s), fast-lane {len(fast_numbers)}, "
            f"verified {[m.number for m in out.mergeable]}, culprits {dict(out.culprits)}, "
            f"dropped {dict(out.dropped)}, deferred {[m.number for m in out.deferred]}, rejected live {sorted(invalid)}"
            + (", UNVERIFIED" if out.unverified else ""))
    log(line)
    write_summary([line])
    return 0


def write_summary(lines: list[str]) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a") as fh:
            fh.write("\n".join(lines) + "\n")


def write_output(key: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as fh:
            fh.write(f"{key}={value}\n")


# --------------------------------------------------------------------------- declaration gate
DECLARATION = [
    ("schedule", "schedule:"),
    ("manual dispatch", "workflow_dispatch:"),
    ("concurrency group", "group: merge-train"),
    ("never cancels a train in flight", "cancel-in-progress: false"),
    ("shadow mode is the default", "MERGE_TRAIN_SHADOW: \"true\""),
    ("regen needs opa", "openpolicyagent.org/downloads/"),
    ("plan travels as an artifact", "actions/upload-artifact@"),
    ("publish consumes the artifact", "actions/download-artifact@"),
]
BUILD_JOB = [
    ("build runs the build mode", "merge-train.py build"),
    ("build checks out WITHOUT credentials", "persist-credentials: false"),
]
BUILD_JOB_FORBIDDEN = [
    ("build must not mint the App token", "create-github-app-token"),
    ("build must not see the App secrets", "GITOPS_APP_"),
    ("build must not run publish", "merge-train.py publish"),
]
PUBLISH_JOB = [
    ("publish mints the App token", "actions/create-github-app-token@"),
    ("publish puts the App token in GH_TOKEN", "GH_TOKEN: ${{ steps.app_token.outputs.token }}"),
    ("publish runs the publish mode", "merge-train.py publish"),
    ("publish needs build", "needs: build"),
]
PUBLISH_JOB_FORBIDDEN = [
    ("publish must not run generators", "regen-derived.sh"),
    ("publish must not run the build mode", "merge-train.py build"),
    ("publish must not install opa (it runs no tree code)", "openpolicyagent.org"),
]


def job_sections(text: str) -> dict[str, str]:
    """The text of each top-level job under `jobs:` (2-space indented keys), by job id."""
    m = re.search(r"^jobs:\s*$", text, re.MULTILINE)
    if not m:
        return {}
    body = text[m.end():]
    parts = re.split(r"^  ([A-Za-z0-9_-]+):\s*$", body, flags=re.MULTILINE)
    return {parts[i]: parts[i + 1] for i in range(1, len(parts) - 1, 2)}


def check_declaration() -> int:
    if not WORKFLOW.exists():
        print(f"::error::{WORKFLOW} missing")
        print("SUBJECTS=0")
        return 1
    text = WORKFLOW.read_text()
    jobs = job_sections(text)
    fails = [label for label, needle in DECLARATION if needle not in text]
    if "contents: write" in text:
        fails.append("GITHUB_TOKEN must stay read-only (pushes use the App token)")
    for job, must, must_not in (("build", BUILD_JOB, BUILD_JOB_FORBIDDEN), ("publish", PUBLISH_JOB, PUBLISH_JOB_FORBIDDEN)):
        section = jobs.get(job)
        if section is None:
            fails.append(f"job `{job}` missing")
            continue
        fails += [label for label, needle in must if needle not in section]
        fails += [label for label, needle in must_not if needle in section]
    for f in fails:
        print(f"::error::merge-train.yml declaration: {f}")
    n = len(DECLARATION) + 1 + len(BUILD_JOB) + len(BUILD_JOB_FORBIDDEN) + len(PUBLISH_JOB) + len(PUBLISH_JOB_FORBIDDEN)
    print(f"SUBJECTS={n}")
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

    def merge(self, number, head_sha=""):
        if self.shadow:
            return None
        self.merged.append(number)
        self.main = f"merge-{number}"
        return self.main

    def main_sha(self):
        return self.main

    def compare_files(self, base, head):
        return self.foreign


SYN_GENERATOR = ".github/scripts/regen.py"
# The synthetic generator doubles as an exfiltration PROBE: it records the environment it ran in,
# to the path in PROBE_OUT (handed in explicitly by the test; it is not in the allowlist either).
SYN_GENERATOR_SRC = (
    "import json,os,pathlib\n"
    "os.makedirs('derived',exist_ok=True)\n"
    "pathlib.Path('derived/index.txt').write_text('\\n'.join(sorted(os.listdir('services')))+'\\n')\n"
    "pathlib.Path(os.environ.get('PROBE_OUT') or os.devnull).write_text("
    "json.dumps({'GH_TOKEN': os.environ.get('GH_TOKEN',''), 'keys': sorted(os.environ)}))\n"
)


def _synthetic_repo(root: str) -> dict[str, str]:
    """A repo whose CI has three contexts, each falsifiable:
       gates (data)        count.txt must equal the number of files under services/
       Validate manifests  bad.txt must not exist
       derived             derived/index.txt must equal the regenerated listing of services/"""
    env = {**generator_env(), "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}

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
    w("rollouts/a.yaml", "kind: Rollout\nmetadata:\n  annotations:\n    openbank.tech/policy-checksum: \"old\"\nspec:\n  replicas: 1\n")
    w(SYN_GENERATOR, SYN_GENERATOR_SRC)
    w("ci.py", "import os,pathlib,sys\nfail=[]\n"
               "if int(open('count.txt').read()) != len(os.listdir('services')): fail.append('gates (data)')\n"
               "if os.path.exists('bad.txt'): fail.append('Validate manifests')\n"
               "want='\\n'.join(sorted(os.listdir('services')))+'\\n'\n"
               "if not os.path.exists('derived/index.txt') or open('derived/index.txt').read()!=want: fail.append('derived')\n"
               "print(','.join(fail)); sys.exit(1 if fail else 0)\n")
    subprocess.run([sys.executable, SYN_GENERATOR], cwd=root, check=True, env=env)
    g("add", "-A")
    g("commit", "-q", "-m", "base")
    base = g("rev-parse", "HEAD").stdout.strip()

    def pr(name, edits):
        g("checkout", "-q", "-b", name, "main")
        for rel, text in edits.items():
            w(rel, text)
        g("add", "-A")
        g("commit", "-q", "-m", name)
        g("checkout", "-q", "main")

    # Semantic pair: each alone green (count 2 == 2 services), together red (count 2, 3 services).
    pr("pA", {"services/a.txt": "a\n", "count.txt": "2\n"})
    pr("pB", {"services/b.txt": "b\n", "count.txt": "2\n"})
    # Textual conflict with pA on count.txt.
    pr("pC", {"count.txt": "7\n", **{f"services/c{i}.txt": "c\n" for i in range(1, 7)}})
    # Independent docs PRs + one bad one (#3 of the bisect batch).
    pr("p1", {"docs/one.md": "1\n"})
    pr("p2", {"docs/two.md": "2\n"})
    pr("p3", {"bad.txt": "x\n"})
    pr("p4", {"docs/four.md": "4\n"})
    # Needs regeneration: adds a service and bumps the count, but does not touch derived/index.txt.
    pr("pR", {"services/r.txt": "r\n", "count.txt": "2\n"})
    # Tampers with the generator: a malicious regen.py that would write the token to the tree.
    pr("pG", {SYN_GENERATOR: "import os,pathlib\npathlib.Path('leaked.txt').write_text(os.environ.get('GH_TOKEN','none'))\n",
              "docs/g.md": "g\n"})
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
    base_pr = {"draft": False, "auto_merge": {"enabled_by": "x"}, "same_repo": True, "head_ref": "feat/x", "number": 1,
               "files": ["openbank-fx-service/A.kt"]}
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
    ok, why = qualifies({**base_pr, "files": [".github/scripts/regen-derived.sh"]}, req, green)
    check("qualifies: a PR touching regen-derived.sh is disqualified (manual lane)", not ok and "generator" in why, why)
    for p, want in [(".github/scripts/x.py", True), ("docs/adr/gen-index.sh", True), ("docs/adr/lib-frontmatter.sh", True),
                    ("openbank-infra/gitops/components/x/gen-x-opa-bundle.sh", True),
                    ("docs/adr/0343-x.md", False), ("openbank-fx-service/A.kt", False), (".github/workflows/ci.yml", False)]:
        check(f"generator path: {p} -> {want}", is_generator_path(p) == want)
    ordered = order_candidates([{"number": 9, "created_at": "2026-02-01T00:00:00Z"}, {"number": 3, "created_at": "2026-01-01T00:00:00Z"}])
    check("order: oldest first", [p["number"] for p in ordered] == [3, 9])

    # ---- fast lane -----------------------------------------------------------------------
    frags = regen_inventory_fragments(REGEN_INVENTORY.read_text()) if REGEN_INVENTORY.exists() else []
    check("inventory: fragments parsed from regen-derived.sh", "prompts/registry.yaml" in frags, str(frags))
    check("fast lane: disjoint service file qualifies",
          fast_lane_eligible(["openbank-fx-service/src/main/kotlin/A.kt"], ["openbank-ledger-service/x.kt"], [], frags)[0])
    check("fast lane: docs/adr/ does NOT qualify", not fast_lane_eligible(["docs/adr/0343-x.md"], [], [], frags)[0])
    check("fast lane: overlap with main diff does NOT qualify",
          not fast_lane_eligible(["openbank-fx-service/A.kt"], ["openbank-fx-service/A.kt"], [], frags)[0])
    check("fast lane: overlap with another candidate does NOT qualify",
          not fast_lane_eligible(["openbank-fx-service/A.kt"], [], ["openbank-fx-service/A.kt"], frags)[0])
    check("fast lane: openapi.yaml does NOT qualify",
          not fast_lane_eligible(["openbank-fx-service/src/main/resources/openapi.yaml"], [], [], frags)[0])
    check("fast lane: regen inventory source does NOT qualify", not fast_lane_eligible(["prompts/registry.yaml"], [], [], frags)[0])
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
    check("bisect tree: 2N-1 batches, whole first", len(bisect_tree(mk(1, 2, 3, 4))) == 7 and bisect_tree(mk(1, 2, 3, 4))[0] == mk(1, 2, 3, 4))

    # ---- the trust boundary + synthetic repository (real git, real conflicts) -------------
    saved = {k: os.environ.get(k) for k in ("GH_TOKEN", "GITHUB_TOKEN", "ACTIONS_RUNTIME_TOKEN")}
    os.environ.update({"GH_TOKEN": "ghs_SELFTEST_MUST_NOT_LEAK", "GITHUB_TOKEN": "ghs_2", "ACTIONS_RUNTIME_TOKEN": "x"})
    try:
        env = generator_env()
        check("generator env: no key containing TOKEN, GH_TOKEN absent",
              not any("TOKEN" in k for k in env) and "GH_TOKEN" not in env, str(sorted(env)))

        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as bundles:
            info = _synthetic_repo(d)
            probe = os.path.join(bundles, "probe.json")
            builder = TrainBuilder(d, regen_commands=(f"{sys.executable} {SYN_GENERATOR}",), overlay_roots=(".github/scripts", "docs/adr"))
            builder.env["PROBE_OUT"] = probe

            def local_verify(rec: TrainRecord):
                builder.git("checkout", "-q", rec.sha)
                r = subprocess.run([sys.executable, "ci.py"], cwd=d, text=True, capture_output=True)
                return ("green", []) if r.returncode == 0 else ("red", [c for c in r.stdout.strip().split(",") if c])

            def members(*names):
                return [Member(i + 1, n, f"body of {n}", builder.git("rev-parse", n).stdout.strip(), n, f"2026-01-0{i + 1}", ref=n)
                        for i, n in enumerate(names)]

            def train(hub, names, out_dir=None, b=None):
                b = b or builder
                ms = members(*names)
                trains = prebuild(b, info["base"], ms, out_dir, "T")
                whole = trains[train_key(ms)]
                return run_train(hub, info["base"], ms, trains, local_verify, [], b.files_of(info["base"], whole.sha))

            # NEGATIVE 0 (the finding): the generator runs while GH_TOKEN is set in os.environ and
            # must not see it. The probe is the generator's OWN record of its environment.
            hub = FakeHub(info["base"])
            o = train(hub, ("pR",))
            rec = json.loads(pathlib.Path(probe).read_text())
            check("exfiltration probe: generator saw an EMPTY GH_TOKEN while the process had one",
                  rec["GH_TOKEN"] == "" and os.environ["GH_TOKEN"] == "ghs_SELFTEST_MUST_NOT_LEAK", str(rec)[:200])
            check("exfiltration probe: no *TOKEN* key reached the generator", not any("TOKEN" in k for k in rec["keys"]), str(rec["keys"]))
            check("regen: with regeneration pR merges", hub.merged == [1], f"{o}")

            # NEGATIVE 0b: a member that rewrites the generator is dropped by the build even when
            # forced past qualifies(); its leaked.txt never exists; the base generator ran instead.
            hub = FakeHub(info["base"])
            o = train(hub, ("p1", "pG"))
            check("generator tampering: pG dropped with a generator reason, p1 merged",
                  2 in o.dropped and "generator" in o.dropped[2] and hub.merged == [1], f"{o}")
            check("generator tampering: leaked.txt was never written", not pathlib.Path(d, "leaked.txt").exists())
            # Overlay belt-and-braces: tamper the generator in the TRAIN tree by hand and prove the
            # overlay restores it; then ADD a generator and prove the assertion refuses the build.
            builder.git("checkout", "-q", "-B", "train/tamper", info["base"])
            pathlib.Path(d, SYN_GENERATOR).write_text("print('evil')\n")
            builder.git("commit", "-q", "-am", "tamper")
            try:
                builder.overlay_trusted_generators(info["base"])
                restored, raised = pathlib.Path(d, SYN_GENERATOR).read_text() == SYN_GENERATOR_SRC, False
            except RuntimeError:
                restored, raised = pathlib.Path(d, SYN_GENERATOR).read_text() == SYN_GENERATOR_SRC, True
            check("overlay: a modified generator in the train is restored from base AND refused", restored and raised)
            builder.git("checkout", "-q", "-f", "-B", "train/tamper2", info["base"])
            pathlib.Path(d, ".github/scripts/new-evil.sh").write_text("echo evil\n")
            builder.git("add", "-A")
            builder.git("commit", "-q", "-m", "adds a generator")
            try:
                builder.overlay_trusted_generators(info["base"])
                raised = False
            except RuntimeError:
                raised = True
            check("overlay: a generator ADDED by the train (absent from base) makes the build refuse", raised)
            builder.git("checkout", "-q", "-f", "main")

            # Controls: each of the semantic pair is green ALONE (otherwise the next case proves nothing).
            for n in ("pA", "pB"):
                hub = FakeHub(info["base"])
                o = train(hub, (n,))
                check(f"synthetic control: {n} alone is green and merges", bool(o.mergeable and hub.merged), f"{o}")

            # NEGATIVE 1: two PRs green alone, jointly invalid via count.txt -> the train MUST go red.
            hub = FakeHub(info["base"])
            o = train(hub, ("pA", "pB"))
            check("semantic conflict: train of [pA,pB] went red (trains > 1)", o.trains == 3, f"trains={o.trains}")
            check("semantic conflict: pA merges as the verified prefix, pB is deferred, no culprit",
                  [x.number for x in o.mergeable] == [1] and [x.number for x in o.deferred] == [2] and not o.culprits, f"{o}")
            check("semantic conflict: pB was NOT merged", hub.merged == [1], str(hub.merged))

            # NEGATIVE 2: bisect over a real tree, #3 bad; the bundles also round-trip into a clean clone.
            hub = FakeHub(info["base"])
            o = train(hub, ("p1", "p2", "p3", "p4"), out_dir=bundles)
            check("bisect (git): culprit is p3", list(o.culprits) == [3] and o.culprits[3] == ["Validate manifests"], f"{o}")
            check("bisect (git): p1,p2 merged, p4 deferred", hub.merged == [1, 2] and [x.number for x in o.deferred] == [4], f"{hub.merged} {o.deferred}")
            check("bisect (git): culprit got a failure status + comment",
                  any(st[1] == "failure" for st in hub.statuses) and any(c[0] == 3 for c in hub.comments))
            check("bisect (git): merged members got success statuses", sum(1 for st in hub.statuses if st[1] == "success") == 2)
            check("bundles: one per bisect node (7 for 4 members)", len([f for f in os.listdir(bundles) if f.endswith(".bundle")]) == 7, str(os.listdir(bundles)))
            with tempfile.TemporaryDirectory() as clone:
                sh(["git", "clone", "-q", "--branch", "main", d, clone])

                def cg(*a, check=True):
                    return sh(["git", *a], cwd=clone, check=check, env=generator_env())
                heads = sh(["git", "bundle", "list-heads", os.path.join(bundles, "train-1-2.bundle")]).stdout.split()
                good = TrainRecord("1-2", heads[1].removeprefix("refs/heads/"), heads[0], [1, 2], {}, 2, "train-1-2.bundle")
                forged = TrainRecord(good.key, good.branch, "0" * 40, good.applied, {}, 2, good.bundle)
                check("bundle: a plan whose sha disagrees with the bundle head is refused", "not the sha" in (verify_bundle(cg, bundles, forged, info["base"]) or ""))
                check("bundle: verifies and fetches into a clone that has only base", verify_bundle(cg, bundles, good, info["base"]) is None,
                      str(verify_bundle(cg, bundles, good, info["base"])))
                check("bundle: wrong base is refused", verify_bundle(cg, bundles, good, good.sha) is not None)
                lying = TrainRecord(good.key, good.branch, good.sha, good.applied, {}, 9, good.bundle)
                check("bundle: a plan claiming a different commit count is refused", "commits" in (verify_bundle(cg, bundles, lying, info["base"]) or ""))
                check("bundle: a missing file is refused", "missing" in (verify_bundle(cg, bundles, TrainRecord("x", "x", good.sha, [], {}, 1, "nope.bundle"), info["base"]) or ""))

            # ---- publish-side proofs: the plan and the bundles are UNTRUSTED (build ran PR code) ----
            outputs, annot = ["derived/*"], ["rollouts/*.yaml"]
            check("derived outputs: parsed from the real regen-derived.sh",
                  "docs/adr/index.json" in derived_output_patterns(REGEN_INVENTORY.read_text())[0] if REGEN_INVENTORY.exists() else True)
            heads = {i + 1: builder.git("rev-parse", n).stdout.strip() for i, n in enumerate(("p1", "p2"))}
            heads_r = {1: builder.git("rev-parse", "pR").stdout.strip()}

            def forge(branch: str, start: str, applied: list[int], edits: dict[str, str], message: str, bundle_name: str | None = None) -> TrainRecord:
                """A bundle an attacker-controlled build job might hand over."""
                builder.git("checkout", "-q", "-f", "-B", branch, start)
                for rel, text in edits.items():
                    pathlib.Path(d, rel).parent.mkdir(parents=True, exist_ok=True)
                    pathlib.Path(d, rel).write_text(text)
                builder.git("add", "-A")
                builder.git("commit", "-q", "-m", message)
                sha = builder.git("rev-parse", "HEAD").stdout.strip()
                n = int(builder.git("rev-list", "--count", f"{info['base']}..{sha}").stdout.strip())
                rec = TrainRecord(train_key([Member(a, "", "", "", "", "") for a in applied]), branch, sha, applied, {}, n)
                own = os.path.join(bundles, f"forged-{branch.replace('/', '_')}.bundle")
                builder.git("bundle", "create", own, branch, f"^{info['base']}")
                rec.bundle = bundle_name if bundle_name is not None else os.path.basename(own)
                return rec

            with tempfile.TemporaryDirectory() as clone:
                sh(["git", "clone", "-q", "--branch", "main", d, clone])

                def cg(*a, check=True):
                    return sh(["git", *a], cwd=clone, check=check, env=generator_env())
                # genuine trains prove clean: [p1,p2] (no regen) and [pR] (with a regen commit in derived/)
                good12 = prebuild(builder, info["base"], members("p1", "p2"), bundles, "G")[ "1-2"]
                goodR = prebuild(builder, info["base"], members("pR"), bundles, "R")["1"]
                check("proof: a genuine two-member train verifies", verify_train(cg, bundles, good12, info["base"], heads, outputs, annot) is None,
                      str(verify_train(cg, bundles, good12, info["base"], heads, outputs, annot)))
                check("proof: a genuine train with a regeneration commit in the declared outputs verifies",
                      verify_train(cg, bundles, goodR, info["base"], heads_r, outputs, annot) is None,
                      str(verify_train(cg, bundles, goodR, info["base"], heads_r, outputs, annot)))
                # NEGATIVE: forged head_sha — the plan names a head the live PR does not have
                entry = {"number": 1, "head_sha": "f" * 40}
                live_view = {"number": 1, "state": "open", "draft": False, "auto_merge": {"x": 1}, "same_repo": True,
                             "head_ref": "p1", "head_sha": heads[1], "files": ["docs/one.md"], "title": "p1", "body": "", "created_at": "t"}
                m_, why = revalidate(entry, live_view, ["all-green"], {"all-green": "success"})
                check("proof: forged plan head_sha is rejected by live revalidation", m_ is None and "head moved" in why, why)
                m_, why = revalidate({"number": 1, "head_sha": heads[1]}, live_view, ["all-green"], {"all-green": "success"})
                check("proof: matching head + armed + green revalidates", m_ is not None and m_.head_sha == heads[1], why)
                m_, why = revalidate({"number": 1, "head_sha": heads[1]}, {**live_view, "auto_merge": None}, ["all-green"], {"all-green": "success"})
                check("proof: a member disarmed since the plan is rejected", m_ is None, why)
                m_, why = revalidate({"number": 1, "head_sha": heads[1]}, {**live_view, "state": "closed"}, ["all-green"], {"all-green": "success"})
                check("proof: a member closed since the plan is rejected", m_ is None, why)
                # NEGATIVE: a plan whose member head differs from the live head -> tree mismatch
                check("proof: a train squashed from a different head than the live one is rejected (tree)",
                      "tree" in (verify_train(cg, bundles, good12, info["base"], {1: heads[2], 2: heads[1]}, outputs, annot) or ""))
                # NEGATIVE: fast_lane entry touching docs/adr/ — the plan's classification is ignored
                ok_, why = fast_lane_eligible(["docs/adr/0001-x.md"], [], [], frags)
                check("proof: a plan fast_lane entry whose LIVE files touch docs/adr/ is not posted", not ok_ and "protected" in why, why)
                # NEGATIVE: extra non-regen commit on top of a genuine train
                extra = forge("train/X1", good12.sha, [1, 2], {"evil.txt": "x\n"}, "innocent looking")
                check("proof: a bundle carrying an extra non-regeneration commit is rejected",
                      "extra commit" in (verify_train(cg, bundles, extra, info["base"], heads, outputs, annot) or ""),
                      str(verify_train(cg, bundles, extra, info["base"], heads, outputs, annot)))
                # NEGATIVE: a regeneration commit touching a path no generator writes
                badregen = forge("train/X2", good12.sha, [1, 2], {"evil.txt": "x\n"}, REGEN_COMMIT_SUBJECT)
                check("proof: a regeneration commit touching a non-inventory path is rejected",
                      "no generator is declared" in (verify_train(cg, bundles, badregen, info["base"], heads, outputs, annot) or ""),
                      str(verify_train(cg, bundles, badregen, info["base"], heads, outputs, annot)))
                # annotation-only files: checksum line may change, anything else may not
                okann = forge("train/X3", good12.sha, [1, 2], {"rollouts/a.yaml": "kind: Rollout\nmetadata:\n  annotations:\n    openbank.tech/policy-checksum: \"new\"\nspec:\n  replicas: 1\n"}, REGEN_COMMIT_SUBJECT)
                check("proof: a regeneration commit restamping only the policy-checksum line is accepted",
                      verify_train(cg, bundles, okann, info["base"], heads, outputs, annot) is None,
                      str(verify_train(cg, bundles, okann, info["base"], heads, outputs, annot)))
                badann = forge("train/X4", good12.sha, [1, 2], {"rollouts/a.yaml": "kind: Rollout\nmetadata:\n  annotations:\n    openbank.tech/policy-checksum: \"new\"\nspec:\n  replicas: 99\n"}, REGEN_COMMIT_SUBJECT)
                check("proof: a regeneration commit changing more than the annotation is rejected",
                      "more than the policy-checksum" in (verify_train(cg, bundles, badann, info["base"], heads, outputs, annot) or ""))
                # NEGATIVE: a commit with the right trailer but the wrong content
                wrong = forge("train/X5", info["base"], [1], {"docs/one.md": "tampered\n"}, "p1\n\nPR-Number: #1")
                check("proof: a squash whose tree is not the squash of the live head is rejected",
                      "tree" in (verify_train(cg, bundles, wrong, info["base"], heads, outputs, annot) or ""))
                # NEGATIVE: trailers out of order
                swapped = verify_train(cg, bundles, TrainRecord("2-1", good12.branch, good12.sha, [2, 1], {}, 2, good12.bundle), info["base"], heads, outputs, annot) or ""
                check("proof: trailers that do not match the plan order are rejected", "trailer" in swapped, swapped)
                # NEGATIVE: bundle names that escape the plan directory
                for bad in ("../x.bundle", "/tmp/x.bundle", "a/b.bundle", "x.txt", ""):
                    check(f"proof: bundle name {bad!r} is refused before any file is touched", not safe_bundle_name(bad)
                          and "unsafe" in (verify_train(cg, bundles, TrainRecord("1-2", good12.branch, good12.sha, [1, 2], {}, 2, bad), info["base"], heads, outputs, annot) or ""))
                check("proof: a plain bundle name is accepted", safe_bundle_name("train-1-2.bundle"))
                # invalid member: a train carrying it is unusable even if the bundle is perfect
                hub = FakeHub(info["base"])
                ms = members("p1", "p2")
                o = run_train(hub, info["base"], ms, prebuild(builder, info["base"], ms, None, "I"), local_verify, [], [], frozenset({2}))
                check("proof: a train carrying a live-rejected member is deferred, nothing merges", hub.merged == [] and o.unverified)
            builder.git("checkout", "-q", "-f", "main")

            # Conflict drop: pC conflicts textually with pA -> dropped with a comment, pA still merges.
            hub = FakeHub(info["base"])
            o = train(hub, ("pA", "pC"))
            check("conflict drop: pC dropped, commented, pA merged", 2 in o.dropped and hub.merged == [1] and any(c[0] == 2 for c in hub.comments), f"{o} {hub.comments}")

            # Regeneration control: pR is red alone WITHOUT regen, and the regen commit is the tip with it.
            no_regen = TrainBuilder(d, regen_commands=(), overlay_roots=(".github/scripts",))
            hub = FakeHub(info["base"])
            o = train(hub, ("pR",), b=no_regen)
            check("regen control: without regeneration pR is a culprit", list(o.culprits) == [1], f"{o}")
            hub = FakeHub(info["base"])
            o = train(hub, ("pR",))
            subj = builder.git("log", "-1", "--format=%s", "train/T-1").stdout.strip()
            check("regen: train tip is the regeneration commit", subj == REGEN_COMMIT_SUBJECT, subj)
            trailer = builder.git("log", "-1", "--format=%B", "train/T-1~1").stdout
            check("squash commit carries PR-Number trailer", "PR-Number: #1" in trailer, trailer)

            # Shadow: statuses posted, nothing merged.
            hub = FakeHub(info["base"])
            hub.shadow = True
            o = train(hub, ("p1", "p2"))
            check("shadow: success statuses posted, zero merges", len(hub.statuses) == 2 and hub.merged == [])

            # Foreign merge mid-train: overlapping -> stop before the first; disjoint -> continue.
            hub = FakeHub("foreign")
            hub.foreign = ["docs/two.md"]
            o = train(hub, ("p1", "p2"))
            check("foreign merge touching train files stops the merges", hub.merged == [] and [x.number for x in o.deferred] == [1, 2], f"{hub.merged} {o}")
            hub = FakeHub("foreign")
            hub.foreign = ["unrelated/file.txt"]
            o = train(hub, ("p1", "p2"))
            check("disjoint foreign merge is tolerated", hub.merged == [1, 2], str(hub.merged))
    finally:
        for k, v in saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v

    for f in fails:
        print(f"  [FAIL] {f}")
    print(f"SUBJECTS={len(ran)}")
    print(f"merge-train self-test: {len(ran)} cases, {len(fails)} failed")
    return 1 if fails else 0


# --------------------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("mode", nargs="?", choices=["build", "publish"])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-declaration", action="store_true")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--branch", default="main")
    ap.add_argument("--batch-size", type=int, default=int(os.environ.get("TRAIN_BATCH_SIZE", "8")))
    ap.add_argument("--out", default="", help="build: directory for plan.json + bundles")
    ap.add_argument("--plan", default="", help="publish: directory holding plan.json + bundles")
    ap.add_argument("--wait-minutes", type=int, default=90)
    ap.add_argument("--poll-seconds", type=int, default=60)
    ap.add_argument("--workdir", default="")
    ap.add_argument("--run-url", default=os.environ.get("MERGE_TRAIN_RUN_URL", ""))
    ap.add_argument("--dry-run", action="store_true", help="publish: push/post/merge nothing, print what would happen")
    ap.add_argument("--shadow", action="store_true", help="publish: post statuses and comments, never merge (phase 0)")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.check_declaration:
        return check_declaration()
    if not args.mode:
        ap.error("mode is required: build | publish (or --self-test / --check-declaration)")
    if "GH_TOKEN" not in os.environ:
        ap.error("GH_TOKEN is required (read-only is enough for build)")
    if args.mode == "build":
        if not args.repo or not args.out:
            ap.error("build needs --repo (or GITHUB_REPOSITORY) and --out")
        return build(args)
    if not args.plan or not args.repo:
        ap.error("publish needs --plan and --repo (or GITHUB_REPOSITORY)")
    return publish(args)


if __name__ == "__main__":
    sys.exit(main())
