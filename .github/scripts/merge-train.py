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

THE INVARIANT
-------------
  * The App token never shares a runner with PR data being processed by code.
  * Privileged jobs act only on trees they re-derived themselves with execution-free git
    (`git merge-tree --write-tree`, `git commit-tree`, `git ls-tree`) — never on files a build wrote.
  * The one thing a privileged job cannot re-derive execution-free — the regeneration commit's
    CONTENT — is bounded by path (DERIVED_OUTPUTS, read from origin/main) and then proven by main's
    own derived-freshness gates, which CI runs on the exact train tree. Gate scripts, workflows and
    generators in that tree are main's, because a PR touching any of `.github/` never qualifies.

THREE JOBS, EACH ON ITS OWN RUNNER
----------------------------------
  build    no secrets. Lists candidates live, squashes the verified heads in order, overlays the
           generators from origin/main, regenerates in a scrubbed environment, and uploads ONE git
           bundle holding ONE train. Everything it produces is untrusted.
  publish  App token, fresh clone of origin/main, this script taken from origin/main and run with
           `python3 -I -S`, isolated git config. Recomputes the batch live, rebuilds every member
           squash with `git merge-tree --write-tree` onto its OWN commits, and requires each tree
           to equal the bundle's commit for that step. Accepts the bundle's single regeneration
           commit only within DERIVED_OUTPUTS, with no symlink or gitlink. Rejects any train whose
           tree has a symlink/gitlink main does not. Then commits its OWN chain (commit-tree over
           the verified trees), pushes it to `train/<run_id>-<attempt>`, records `tree=<sha>` as an
           App status on that commit, and opens the draft carrier PR so CI runs.
  settle   a later tick; App token; same isolation. For each App-authored carrier whose CI is done:
           the App record must exist on the exact head, its tree must equal the commit's tree, the
           chain's base must be live main, and every required context must be green on that sha.
           Green: `merge-train` success on each verified member head, merge with the head sha
           pinned. Red: halve (an App `bisect size N` status) or, at one member, name the culprit.
           Also computes the fast lane from live data. Comments are fixed text.

MODES
-----
  build --out DIR                  (no secrets) DIR/train.bundle, or nothing
  publish --bundle DIR             (App token)  verify + push own commits + open carrier
  settle                           (App token)  judge finished carriers, merge, bisect, fast lane
  --shadow                         settle posts statuses and comments, never merges
  --self-test                      pure logic + a synthetic repository driven through real ticks
  --check-declaration              merge-train.yml still wires the three jobs per ADR-0343
"""
from __future__ import annotations

import argparse
import base64
import fnmatch
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from dataclasses import dataclass, field

API = os.environ.get("GITHUB_API_URL", "https://api.github.com")
WORKFLOW = pathlib.Path(".github/workflows/merge-train.yml")
REGEN_INVENTORY = ".github/scripts/regen-derived.sh"

# The commit-status context this script owns. check-ruleset-context-parity.py reads this
# declaration, which is what lets the context be REQUIRED although no pull_request job emits it.
STATUS_CONTEXT = "merge-train"
RECORD_CONTEXT = "merge-train/record"          # publish's record of the tree it pushed
BOT_LOGIN = "openbank-gitops-bot[bot]"          # the only creator whose statuses/PRs are trusted
EXCLUDED_BRANCH_PREFIXES = ("codex/",)
CARRIER_LABEL = "merge-train"
TRAIN_BRANCH_RE = re.compile(r"^train/[0-9]+-[0-9]+$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
BUNDLE_FILE = "train.bundle"
BUNDLE_REF = "refs/heads/merge-train-bundle"
# Contexts that judge the PR object (its body, its issue link), not the tree.
TREE_INDEPENDENT_CONTEXTS = frozenset({"issue-hygiene"})
GUARD_CONTEXT_HINT = "agent-pr-guard"
OK_STATES = frozenset({"success"})
# Fast-lane exclusions.
PROTECTED_PREFIXES = ("openbank-libs/", "build-logic/", "openbank-infra/gitops/", "docs/adr/", ".github/")
PROTECTED_GLOBS = ("*/src/main/resources/openapi.yaml",)
# A member touching any of these never rides a train: generators, gate scripts, gates.yaml, the
# regen inventory and the WORKFLOWS that run the freshness gates all live under `.github/`, and
# the regeneration-content argument rests on CI running main's copies of them. Everything ELSE the
# generator runner executes is derived from origin/main's regen-derived.sh by `Untrainable` —
# never from a fixed-depth glob, which a deeper file walks straight past.
UNTRAINABLE_PREFIXES = (".github/",)
SOURCED_BY_GEN_INDEX = "docs/adr/*.sh"         # gen-index.sh sources its sibling libraries
GENERATOR_OVERLAY_ROOTS = (".github/scripts", "docs/adr", "openbank-infra/gitops")
REGEN_COMMANDS = ("bash .github/scripts/regen-derived.sh --all", "bash docs/adr/gen-index.sh")
SPECIAL_MODES = ("120000", "160000")           # symlink, gitlink
GENERATOR_ENV_ALLOWLIST = ("PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
                           "PYTHONIOENCODING", "PYTHONDONTWRITEBYTECODE", "SSL_CERT_FILE")
REGEN_COMMIT_SUBJECT = "chore(train): regenerate derived files"
OWN_MEMBER_RE = re.compile(r"^merge-train: member #([0-9]+)\n\nPR-Number: #\1\nPR-Head: ([0-9a-f]{40})\n$")
OWN_REGEN_MSG = "merge-train: regenerate derived files\n"
BISECT_RE = re.compile(r"^bisect size ([0-9]+)$")
RECORD_RE = re.compile(r"^tree=([0-9a-f]{40})$")
COMMENT_MARK = "<!-- merge-train -->"
# Fixed comment text. Nothing from a PR, a bundle, a branch or a commit is ever interpolated.
DROP_MESSAGE = ("merge-train: this PR did not apply cleanly on top of main together with the PRs ahead of "
                "it in this train, so it was left out. Rebase on main; the next train picks it up again.")
CULPRIT_MESSAGE = ("merge-train: a train over main isolated this PR as the cause of a failing required "
                   "check, and its `merge-train` status is now failure. Push a fix; the next train that "
                   "passes clears it. The workflow run linked from the status has the details.")
MANUAL_LANE_MESSAGE = ("merge-train: the train containing only this PR could not be verified by the merge "
                       "train (for example, it adds a symlink or its regeneration touches a file outside the "
                       "derived-output inventory). It needs the owner's manual lane; see ADR-0343.")


def log(msg: str) -> None:
    print(msg, flush=True)


# --------------------------------------------------------------------------- pure: paths
def glob_match(pattern: str, path: str) -> bool:
    """fnmatch where `*` never crosses a `/` (fnmatch's `*` does)."""
    rx = "".join("[^/]*" if c == "*" else "[^/]" if c == "?" else re.escape(c) for c in pattern)
    return re.fullmatch(rx, path) is not None


class Untrainable:
    """Every path the generator runner would EXECUTE, derived from origin/main's regen-derived.sh:
    its `find <root> -name '<pattern>'` enumeration (any depth, as `find` recurses), every program
    its GENERATORS inventory and REGEN_COMMANDS invoke, the libraries gen-index.sh sources, and all
    of `.github/`. If main's script cannot be parsed, it fails CLOSED: any `gen-*` file anywhere."""

    def __init__(self, regen_text: str):
        m = re.search(r"find\s+(\S+)\s+-name\s+'([^']+)'", regen_text or "")
        self.find_root = m.group(1).rstrip("/") if m else None
        self.find_name = m.group(2) if m else None
        commands = [c for c in generator_commands(regen_text or "") if c != "__BUNDLES__"] + list(REGEN_COMMANDS)
        self.programs = {tok for c in commands for tok in shlex.split(c) if "/" in tok and tok.endswith((".py", ".sh"))}

    def __call__(self, path: str) -> bool:
        if any(path.startswith(p) for p in UNTRAINABLE_PREFIXES) or path in self.programs:
            return True
        if glob_match(SOURCED_BY_GEN_INDEX, path):
            return True
        name = path.rsplit("/", 1)[-1]
        if self.find_root is None:
            return name.startswith("gen-")
        return path.startswith(self.find_root + "/") and fnmatch.fnmatch(name, self.find_name)


def generator_commands(script_text: str) -> list[str]:
    return [m.group(2) for m in re.finditer(r'^\s*"([^"|]+)\|([^"|]+)\|([^"]*)"', script_text, re.MULTILINE)]


FAIL_CLOSED_UNTRAINABLE = Untrainable("")


def derived_output_patterns(script_text: str) -> list[str]:
    m = re.search(r"^DERIVED_OUTPUTS=\((.*?)^\)", script_text, re.MULTILINE | re.DOTALL)
    return re.findall(r'"([^"]+)"', m.group(1)) if m else []


def regen_inventory_fragments(script_text: str) -> list[str]:
    """The source path fragments of regen-derived.sh's GENERATORS inventory ("id|cmd|frag frag")."""
    frags: list[str] = []
    for m in re.finditer(r'^\s*"([^"|]+)\|([^"|]+)\|([^"]*)"', script_text, re.MULTILINE):
        frags += m.group(3).split()
    return frags


# --------------------------------------------------------------------------- pure: candidates
@dataclass
class Member:
    number: int
    title: str
    body: str
    head_sha: str
    head_ref: str
    created_at: str
    ref: str = ""
    files: list[str] = field(default_factory=list)


def qualifies(pr: dict, required: list[str], checks: dict[str, str | None],
              app: dict[str, tuple[str, str]] | None = None, untrainable=None) -> tuple[bool, str]:
    """`checks`: context -> conclusion (None = running). `app`: the App's own latest statuses.
    `pr["files"]` must come from `git diff` against the verified head, never from the files API."""
    untrainable = untrainable or FAIL_CLOSED_UNTRAINABLE
    if pr.get("state", "open") != "open":
        return False, f"state {pr.get('state')}"
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
        if untrainable(f):
            return False, f"touches {f} (workflows, gates, generators, inventory) — manual lane, never a train"
    if (app or {}).get(STATUS_CONTEXT, ("", ""))[0] == "failure":
        return False, "merge-train failure on this head (culprit or manual lane) — waits for a push"
    if not required:
        return False, "no required contexts resolved — refusing to judge green"
    for name, state in checks.items():
        if GUARD_CONTEXT_HINT in name and state not in OK_STATES and state is not None:
            return False, f"{name} refused ({state})"
    for c in (c for c in required if c != STATUS_CONTEXT):
        if c not in checks:
            return False, f"required context never reported: {c}"
        if checks[c] is None:
            return False, f"required context pending: {c}"
        if checks[c] not in OK_STATES:
            return False, f"required context {checks[c]}: {c}"
    return True, "armed + all required green"


def order_candidates(prs: list[dict]) -> list[dict]:
    return sorted(prs, key=lambda p: (p.get("created_at") or "", p["number"]))


def is_protected(path: str, fragments: list[str]) -> str | None:
    for pre in PROTECTED_PREFIXES:
        if path.startswith(pre):
            return pre
    for g in PROTECTED_GLOBS:
        if fnmatch.fnmatch(path, g):
            return g
    for frag in fragments:          # same semantics as regen-derived.sh's matches_any()
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


def bisect_size(app: dict[str, tuple[str, str]]) -> int | None:
    state, desc = app.get(STATUS_CONTEXT, ("", ""))
    m = BISECT_RE.fullmatch(desc or "") if state == "pending" else None
    return int(m.group(1)) if m else None


def select_batch(train_cands: list[Member], sizes: dict[int, int | None], default: int) -> list[Member]:
    """Oldest first. A bisect size on the OLDEST candidate caps the batch (halving across ticks)."""
    if not train_cands:
        return []
    limit = sizes.get(train_cands[0].number) or default
    return train_cands[:max(1, min(limit, default))]


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


def own_member_message(n: int, head: str) -> str:
    return f"merge-train: member #{n}\n\nPR-Number: #{n}\nPR-Head: {head}\n"


def train_branch(run_id: str, attempt: str) -> str:
    name = f"train/{run_id}-{attempt}"
    assert_train_branch(name)
    return name


def assert_train_branch(name: str) -> None:
    ok = bool(TRAIN_BRANCH_RE.fullmatch(name or "")) and subprocess.run(
        ["git", "check-ref-format", f"refs/heads/{name}"], capture_output=True).returncode == 0
    if not ok:
        raise ValueError(f"refusing ref {name!r}: not of the form train/<run_id>-<attempt>")


# --------------------------------------------------------------------------- git
def sh(cmd: list[str], cwd: str | None = None, check: bool = True, env: dict | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, cwd=cwd, text=True, capture_output=True, check=check, env=env)


def base_env() -> dict[str, str]:
    """Allowlisted environment with isolated git config. No token of any kind."""
    env = {k: os.environ[k] for k in GENERATOR_ENV_ALLOWLIST if k in os.environ}
    env.update({"GIT_AUTHOR_NAME": "openbank-gitops-bot", "GIT_COMMITTER_NAME": "openbank-gitops-bot",
                "GIT_AUTHOR_EMAIL": "gitops-bot@openbank.invalid", "GIT_COMMITTER_EMAIL": "gitops-bot@openbank.invalid",
                "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_TERMINAL_PROMPT": "0"})
    return env


def generator_env() -> dict[str, str]:
    """The ONLY environment build's generators see: no token, no RUNNER_TEMP, no GITHUB_*."""
    return base_env()


class Git:
    """Isolated git: no system/global config, no hooks, no fsmonitor; credentials only per call."""

    def __init__(self, workdir: str, token: str | None = None):
        self.workdir, self.token = workdir, token

    def __call__(self, *args: str, check: bool = True, auth: bool = False) -> subprocess.CompletedProcess:
        env = base_env()
        if auth and self.token:
            basic = base64.b64encode(f"x-access-token:{self.token}".encode()).decode()
            env.update({"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "http.https://github.com/.extraheader",
                        "GIT_CONFIG_VALUE_0": f"AUTHORIZATION: basic {basic}"})
        return sh(["git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", *args],
                  cwd=self.workdir, check=check, env=env)

    def tree(self, rev: str) -> str:
        r = self("rev-parse", "--verify", "--quiet", f"{rev}^{{tree}}", check=False)
        return r.stdout.strip() if r.returncode == 0 else ""

    def special_entries(self, rev: str) -> set[tuple[str, str, str]]:
        """{(mode, oid, path)} for every symlink/gitlink in the tree of `rev`."""
        entries = set()
        for rec in self("ls-tree", "-r", "-z", rev).stdout.split("\0"):
            if not rec:
                continue
            meta, _, path = rec.partition("\t")
            mode, _typ, oid = meta.split()
            if mode in SPECIAL_MODES:
                entries.add((mode, oid, path))
        return entries


class Pusher:
    """The only code path that writes refs: a full sha to the run's own train branch, or its delete."""

    def __init__(self, git, branch: str, remote: str):
        assert_train_branch(branch)
        self.git, self.branch, self.remote = git, branch, remote

    def push(self, sha: str) -> None:
        assert_train_branch(self.branch)
        if not SHA_RE.fullmatch(sha):
            raise ValueError(f"refusing to push non-sha {sha!r}")
        self.git("push", "-q", "-f", self.remote, f"{sha}:refs/heads/{self.branch}", auth=True)

    def delete(self) -> None:
        assert_train_branch(self.branch)
        self.git("push", "-q", self.remote, "--delete", f"refs/heads/{self.branch}", check=False, auth=True)


# --------------------------------------------------------------------------- build (untrusted)
class TrainBuilder:
    """Squashes verified heads in a working copy and regenerates with main's generators. No secrets."""

    def __init__(self, workdir: str, regen_commands: tuple[str, ...] = REGEN_COMMANDS,
                 overlay_roots: tuple[str, ...] = GENERATOR_OVERLAY_ROOTS, untrainable=None):
        self.workdir, self.regen_commands, self.overlay_roots = workdir, regen_commands, overlay_roots
        self.untrainable = untrainable or FAIL_CLOSED_UNTRAINABLE
        self.env = generator_env()
        self.quarantined: list[str] = []

    def git(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        return sh(["git", "-c", "core.hooksPath=/dev/null", *args], cwd=self.workdir, check=check, env=self.env)

    def build(self, base: str, members: list[Member]) -> tuple[str, list[int], list[int]]:
        self.git("checkout", "-q", "--detach", "-f", base)
        self.git("clean", "-qfdx")
        applied: list[int] = []
        dropped: list[int] = []
        for m in members:
            r = self.git("merge", "--squash", "--no-commit", m.ref, check=False)
            staged = self.git("diff", "--cached", "--name-only").stdout.split() if r.returncode == 0 else []
            if r.returncode != 0 or not staged or any(self.untrainable(f) for f in staged):
                self.git("merge", "--abort", check=False)
                self.git("reset", "-q", "--hard", "HEAD")
                dropped.append(m.number)
                continue
            self.git("commit", "-q", "-m", squash_message(m))
            applied.append(m.number)
        if applied:
            self.overlay_trusted_generators(base)
            self.quarantine_new_executables(base)
            try:
                self.regenerate()
            finally:
                self.restore_quarantined()
        return self.git("rev-parse", "HEAD").stdout.strip(), applied, dropped

    def quarantine_new_executables(self, base: str) -> None:
        """Before any generator runs: remove from the working tree every file the runner would
        execute that does not exist in origin/main, at any depth. A generator added by a PR can
        never run, whichever check upstream missed it."""
        in_base = set(self.git("ls-tree", "-r", "--name-only", base).stdout.split())
        on_disk = self.git("ls-files", "--cached", "--others", "--exclude-standard").stdout.split()
        self.quarantined = [f for f in on_disk if self.untrainable(f) and f not in in_base]
        for f in self.quarantined:
            os.unlink(os.path.join(self.workdir, f))
            log(f"::warning::quarantined {f}: not on main, would be executed by the generator runner")

    def restore_quarantined(self) -> None:
        tracked = [f for f in self.quarantined if self.git("cat-file", "-e", f"HEAD:{f}", check=False).returncode == 0]
        if tracked:
            self.git("checkout", "HEAD", "--", *tracked)

    def overlay_trusted_generators(self, base: str) -> None:
        roots = [p for p in self.overlay_roots if self.git("cat-file", "-e", f"{base}:{p}", check=False).returncode == 0]
        if roots:
            archive = subprocess.run(["git", "archive", base, "--", *roots], cwd=self.workdir,
                                     capture_output=True, check=True, env=self.env).stdout
            subprocess.run(["tar", "-x", "-C", self.workdir], input=archive, check=True, capture_output=True)
        dirty = [ln[3:].strip() for ln in self.git("status", "--porcelain").stdout.splitlines() if self.untrainable(ln[3:].strip())]
        if dirty:
            raise RuntimeError(f"generator paths differ from the trusted base after overlay: {dirty[:5]}")

    def regenerate(self) -> bool:
        for cmd in self.regen_commands:
            r = subprocess.run(shlex.split(cmd), cwd=self.workdir, text=True, capture_output=True, env=self.env)
            if r.returncode != 0:
                log(f"::warning::regen command failed ({cmd}): {r.stderr.strip()[-300:]}")
        self.restore_quarantined()
        changed = self.git("ls-files", "-m", "-o", "--exclude-standard").stdout.split()
        if not changed:
            return False
        self.git("add", "--", *changed)
        self.git("commit", "-q", "-m", REGEN_COMMIT_SUBJECT)
        return True


# --------------------------------------------------------------------------- publish (trusted)
class Reject(Exception):
    def __init__(self, reason: str, member_fault: bool = True):
        super().__init__(reason)
        self.member_fault = member_fault


@dataclass
class Verified:
    tip: str
    tree: str
    applied: list[int]
    dropped: list[int]


def verify_bundle_train(git: Git, base: str, members: list[Member], bundle_tip: str,
                        outputs: list[str]) -> Verified:
    """Re-derive the train execution-free and require the bundle to match it step by step.
    Returns publish's OWN commit chain (commit-tree over verified trees) — the bundle's commits are
    never pushed. Raises Reject."""
    if not SHA_RE.fullmatch(bundle_tip):
        raise Reject("bundle tip is not a full sha", member_fault=False)
    chain = git("rev-list", "--reverse", "--first-parent", f"{base}..{bundle_tip}").stdout.split()
    every = git("rev-list", f"{base}..{bundle_tip}").stdout.split()
    if not chain or len(every) != len(chain):
        raise Reject("bundle is not one linear chain over the live base", member_fault=False)
    if git("rev-parse", f"{chain[0]}^").stdout.strip() != base:
        raise Reject("bundle chain does not start at the live base", member_fault=False)
    for c in chain:
        if len(git("rev-list", "--parents", "-n", "1", c).stdout.split()) != 2:
            raise Reject(f"bundle commit {c[:12]} is not a single-parent commit", member_fault=False)

    own, own_tree = base, git.tree(base)
    applied: list[int] = []
    dropped: list[int] = []
    idx = 0
    for m in members:
        r = git("merge-tree", "--write-tree", own, m.head_sha, check=False)
        if r.returncode not in (0, 1):
            raise Reject(f"merge-tree failed for #{m.number}: rc={r.returncode}", member_fault=False)
        tree = r.stdout.split()[0] if r.stdout.split() else ""
        if r.returncode == 1 or tree == own_tree:
            dropped.append(m.number)
            continue
        if idx >= len(chain):
            raise Reject(f"bundle has no commit for #{m.number}", member_fault=False)
        if git.tree(chain[idx]) != tree:
            raise Reject(f"bundle commit for #{m.number} differs from the merge-tree rebuild", member_fault=False)
        own = git("commit-tree", tree, "-p", own, "-m", own_member_message(m.number, m.head_sha)).stdout.strip()
        own_tree = tree
        applied.append(m.number)
        idx += 1
    rest = chain[idx:]
    if len(rest) > 1:
        raise Reject(f"bundle carries {len(rest)} commits beyond its members", member_fault=False)
    if rest:
        regen = rest[0]
        raw = git("diff-tree", "-r", "--raw", "-z", "--no-commit-id", f"{regen}^", regen).stdout.split("\0")
        i = 0
        while i < len(raw) - 1:
            meta, path = raw[i], raw[i + 1]
            i += 2
            if not meta.startswith(":"):
                continue
            new_mode = meta[1:].split()[1]
            if new_mode in SPECIAL_MODES:
                raise Reject(f"regeneration commit adds a symlink/gitlink: {path}")
            if not any(glob_match(g, path) for g in outputs):
                raise Reject(f"regeneration commit touches {path}, which is not a declared derived output")
        own = git("commit-tree", git.tree(regen), "-p", own, "-m", OWN_REGEN_MSG).stdout.strip()
        own_tree = git.tree(regen)
    if not applied:
        raise Reject("no member applied", member_fault=False)
    extra = git.special_entries(own) - git.special_entries(base)
    if extra:
        raise Reject(f"train tree contains a symlink/gitlink main does not: {sorted(p for _, _, p in extra)[:3]}")
    return Verified(own, own_tree, applied, dropped)


# --------------------------------------------------------------------------- settle (trusted)
def parse_own_chain(git: Git, tip: str, limit: int = 64) -> tuple[str, list[tuple[int, str]]] | None:
    """Walk publish's own commits down from `tip`. -> (base, [(pr, head)]) or None if not ours."""
    members: list[tuple[int, str]] = []
    cur = tip
    for _ in range(limit):
        msg = git("log", "-1", "--format=%B", cur).stdout.rstrip("\n") + "\n"
        m = OWN_MEMBER_RE.fullmatch(msg)
        if not (m or (msg == OWN_REGEN_MSG and cur == tip)):
            break
        if m:
            members.append((int(m.group(1)), m.group(2)))
        parents = git("rev-list", "--parents", "-n", "1", cur).stdout.split()[1:]
        if len(parents) != 1:
            return None
        cur = parents[0]
    else:
        return None
    if not members:
        return None
    return cur, list(reversed(members))


def record_problem(record_desc: str | None, record_creator: str | None, sha_tree: str) -> str | None:
    if record_creator != BOT_LOGIN:
        return "no merge-train/record status from the App on this exact sha"
    m = RECORD_RE.fullmatch(record_desc or "")
    if not m:
        return "malformed record"
    if not sha_tree or m.group(1) != sha_tree:
        return f"tree {sha_tree[:12] or '?'} is not the recorded tree {m.group(1)[:12]}"
    return None


# --------------------------------------------------------------------------- GitHub
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


class GitHub:
    """Every GitHub read and write. The self-test substitutes a fake with the same methods."""

    def __init__(self, repo: str, branch: str, run_url: str):
        self.repo, self.branch, self.run_url = repo, branch, run_url
        self.remote = f"https://github.com/{repo}.git"

    def head_refspec(self, n: int) -> str:
        return f"refs/pull/{int(n)}/head"

    def required(self) -> list[str]:
        ctx: list[str] = []
        for r in get(f"/repos/{self.repo}/rules/branches/{self.branch}"):
            if r.get("type") == "required_status_checks":
                ctx += [c["context"] for c in r["parameters"]["required_status_checks"]]
        return sorted(set(ctx))

    def main_sha(self) -> str:
        return get(f"/repos/{self.repo}/git/ref/heads/{self.branch}")["object"]["sha"]

    def open_prs(self) -> list[dict]:
        return paginate(f"/repos/{self.repo}/pulls?state=open&base={self.branch}")

    def pr(self, n: int) -> dict:
        return get(f"/repos/{self.repo}/pulls/{int(n)}")

    def checks(self, sha: str) -> dict[str, str | None]:
        latest: dict[str, dict] = {}
        for check in paginate(f"/repos/{self.repo}/commits/{sha}/check-runs", "check_runs"):
            if check["name"] not in latest or check["id"] > latest[check["name"]]["id"]:
                latest[check["name"]] = check
        out = {name: (c["conclusion"] if c["status"] == "completed" else None) for name, c in latest.items()}
        for st in sorted(get(f"/repos/{self.repo}/commits/{sha}/status")["statuses"], key=lambda s: s["id"], reverse=True):
            out.setdefault(st["context"], None if st["state"] == "pending" else st["state"])
        return out

    def app_statuses(self, sha: str) -> dict[str, tuple[str, str]]:
        """The App's own latest status per context on `sha` — statuses by anyone else are ignored."""
        out: dict[str, tuple[str, str]] = {}
        for st in sorted(paginate(f"/repos/{self.repo}/commits/{sha}/statuses"), key=lambda s: s["id"], reverse=True):
            if (st.get("creator") or {}).get("login") == BOT_LOGIN:
                out.setdefault(st["context"], (st["state"], st.get("description") or ""))
        return out

    def post_status(self, sha: str, state: str, description: str, context: str = STATUS_CONTEXT) -> None:
        if not SHA_RE.fullmatch(sha):
            raise SystemExit(f"::error::refusing a status on non-sha {sha!r}")
        code, data = api("POST", f"/repos/{self.repo}/statuses/{sha}",
                         {"state": state, "context": context, "description": description[:140], "target_url": self.run_url})
        if code != 201:
            raise SystemExit(f"::error::POST status on {sha} -> {code}: {data}")

    def comment(self, n: int, text: str) -> None:
        api("POST", f"/repos/{self.repo}/issues/{int(n)}/comments", {"body": f"{COMMENT_MARK}\n{text}\n\n{self.run_url}"})

    def merge(self, n: int, head: str) -> str | None:
        """Squash-merge pinned to the verified head sha (the API's `sha` = --match-head-commit)."""
        code, data = api("PUT", f"/repos/{self.repo}/pulls/{int(n)}/merge", {"merge_method": "squash", "sha": head})
        return data.get("sha") if code == 200 and isinstance(data, dict) and data.get("merged") else None

    def create_carrier(self, branch: str) -> int | None:
        assert_train_branch(branch)
        code, data = api("POST", f"/repos/{self.repo}/pulls", {
            "title": f"merge-train carrier {branch}", "head": branch, "base": self.branch, "draft": True,
            "body": f"{COMMENT_MARK}\nMerge-train carrier (ADR-0343): CI on this head verifies one train. "
                    "Closed automatically by the next settle. Never merge this PR."})
        if code != 201:
            log(f"::warning::carrier PR not created: {code} {data}")
            return None
        api("POST", f"/repos/{self.repo}/issues/{data['number']}/labels", {"labels": [CARRIER_LABEL]})
        return int(data["number"])

    def close_carrier(self, n: int) -> None:
        api("PATCH", f"/repos/{self.repo}/pulls/{int(n)}", {"state": "closed"})


def is_carrier(pr: dict, repo: str) -> bool:
    return ((pr.get("user") or {}).get("login") == BOT_LOGIN and TRAIN_BRANCH_RE.fullmatch(pr["head"]["ref"]) is not None
            and (pr["head"].get("repo") or {}).get("full_name") == repo)


def pr_view(repo: str, p: dict, files: list[str]) -> dict:
    return {"number": p["number"], "state": p.get("state"), "draft": p.get("draft"), "auto_merge": p.get("auto_merge"),
            "same_repo": (p["head"].get("repo") or {}).get("full_name") == repo, "head_ref": p["head"]["ref"],
            "head_sha": p["head"]["sha"], "created_at": p.get("created_at", ""), "title": p.get("title", ""),
            "body": p.get("body") or "", "files": files}


def live_candidates(gh, git, repo: str, required: list[str], base: str, untrainable,
                    limit: int = 64) -> tuple[list[Member], dict[int, dict]]:
    """Open PRs that qualify RIGHT NOW. Each head is fetched and must equal the API's head sha, and
    the file list is `git diff --name-only <base>...<verified head>` — the PR files API stops at
    3000 entries, so a long PR could hide a path there; git has no such cap."""
    member_required = [c for c in required if c != STATUS_CONTEXT]
    armed = [p for p in order_candidates([p for p in gh.open_prs() if not is_carrier(p, repo)])
             if p.get("auto_merge") and not p.get("draft")]
    if armed:
        git("fetch", "-q", "origin", *[f"+{gh.head_refspec(p['number'])}:refs/merge-train/pr-{p['number']}" for p in armed])
    out: list[Member] = []
    apps: dict[int, dict] = {}
    for p in armed:
        n = p["number"]
        fetched = git("rev-parse", "--verify", "--quiet", f"refs/merge-train/pr-{n}", check=False).stdout.strip()
        if fetched != p["head"]["sha"]:
            log(f"#{n:>6} skip      fetched head differs from the API head (pushed meanwhile)")
            continue
        files = git("diff", "--name-only", f"{base}...{fetched}").stdout.split()
        view = pr_view(repo, p, files)
        app = gh.app_statuses(fetched)
        ok, why = qualifies(view, member_required, gh.checks(fetched), app, untrainable)
        log(f"#{n:>6} {'CANDIDATE' if ok else 'skip     '} {why}")
        if ok:
            out.append(Member(n, view["title"], view["body"], fetched, view["head_ref"], view["created_at"],
                              ref=fetched, files=files))
            apps[n] = app
        if len(out) >= limit:
            break
    return out, apps


def partition(git, base: str, cands: list[Member], fragments: list[str]) -> tuple[list[Member], list[Member]]:
    """(fast lane, train). Computed from live git data only."""
    for m in cands:
        m.files = git("diff", "--name-only", f"{base}...{m.head_sha}").stdout.split()
    fast, train = [], []
    for m in cands:
        mb = git("merge-base", base, m.head_sha).stdout.strip()
        main_files = git("diff", "--name-only", f"{mb}..{base}").stdout.split()
        others = [f for o in cands if o is not m for f in o.files]
        ok, _ = fast_lane_eligible(m.files, main_files, others, fragments)
        (fast if ok else train).append(m)
    return fast, train


def main_regen_text(git, base: str) -> str:
    return git("show", f"{base}:{REGEN_INVENTORY}", check=False).stdout


def plan_batch(gh, git, repo: str, base: str, batch_size: int) -> tuple[list[Member], list[Member]]:
    """(fast lane, this tick's train batch) — the same function in build, publish and settle."""
    required = gh.required()
    regen_text = main_regen_text(git, base)
    cands, apps = live_candidates(gh, git, repo, required, base, Untrainable(regen_text))
    fragments = regen_inventory_fragments(regen_text)
    fast, train = partition(git, base, cands, fragments)
    return fast, select_batch(train, {n: bisect_size(a) for n, a in apps.items()}, batch_size)


def live_base(gh, git) -> str:
    git("fetch", "-q", "origin", f"+refs/heads/{gh.branch}:refs/remotes/origin/{gh.branch}")
    return git("rev-parse", f"refs/remotes/origin/{gh.branch}").stdout.strip()


# --------------------------------------------------------------------------- the three modes
def mode_build(gh, git, builder: TrainBuilder, repo: str, out_dir: str, batch_size: int) -> list[int]:
    base = live_base(gh, git)
    _fast, batch = plan_batch(gh, git, repo, base, batch_size)
    builder.untrainable = Untrainable(main_regen_text(git, base))
    os.makedirs(out_dir, exist_ok=True)
    if not batch:
        log("build: no train this tick")
        return []
    tip, applied, dropped = builder.build(base, batch)
    log(f"build over {base[:12]}: applied {applied}, dropped {dropped}")
    if applied:
        git("update-ref", BUNDLE_REF, tip)
        git("bundle", "create", os.path.join(out_dir, BUNDLE_FILE), BUNDLE_REF, f"^{base}")
    return applied


def mark_unverifiable(gh, batch: list[Member]) -> None:
    """A train publish cannot verify is halved like a red one; at one member it is the manual lane."""
    if len(batch) == 1:
        gh.post_status(batch[0].head_sha, "failure", "not verifiable by the merge train: manual lane")
        gh.comment(batch[0].number, MANUAL_LANE_MESSAGE)
        return
    for m in batch:
        gh.post_status(m.head_sha, "pending", f"bisect size {max(1, len(batch) // 2)}")


def mode_publish(gh, git, repo: str, bundle_dir: str, run_id: str, attempt: str, batch_size: int) -> str:
    """Returns one of: idle, in-flight, rejected, published."""
    if any(is_carrier(p, repo) for p in gh.open_prs()):
        return "in-flight"
    branch = train_branch(run_id, attempt)
    base = live_base(gh, git)
    _fast, batch = plan_batch(gh, git, repo, base, batch_size)
    bundle = os.path.join(bundle_dir, BUNDLE_FILE)
    if not batch or not os.path.isfile(bundle):
        return "idle"
    outputs = derived_output_patterns(git("show", f"{base}:{REGEN_INVENTORY}", check=False).stdout)
    if not outputs:
        log("::error::origin/main declares no DERIVED_OUTPUTS — refusing")
        return "rejected"
    try:
        if git("bundle", "verify", bundle, check=False).returncode != 0:
            raise Reject("bundle does not verify against this clone (stale base or corrupt)", member_fault=False)
        git("fetch", "-q", bundle, f"+{BUNDLE_REF}:refs/merge-train/bundle")
        tip = git("rev-parse", "refs/merge-train/bundle").stdout.strip()
        v = verify_bundle_train(git, base, batch, tip, outputs)
    except Reject as exc:
        log(f"::warning::train rejected: {exc}")
        if exc.member_fault:
            mark_unverifiable(gh, batch)
        return "rejected"
    for n in v.dropped:
        gh.comment(n, DROP_MESSAGE)
    Pusher(git, branch, gh.remote).push(v.tip)
    gh.post_status(v.tip, "success", f"tree={v.tree}", context=RECORD_CONTEXT)
    gh.create_carrier(branch)
    log(f"published {branch} over {base[:12]}: members {v.applied}, dropped {v.dropped}")
    return "published"


def mode_settle(gh, git, repo: str, shadow: bool) -> dict:
    """Judge every finished App carrier, then post the fast lane from live data."""
    summary: dict = {"in_flight": False, "merged": [], "culprits": [], "bisected": [], "rejected": [], "fast": []}
    required = gh.required()
    train_required = [c for c in required if c != STATUS_CONTEXT and c not in TREE_INDEPENDENT_CONTEXTS]
    for p in [p for p in gh.open_prs() if is_carrier(p, repo)]:
        sha, branch, carrier = p["head"]["sha"], p["head"]["ref"], p["number"]
        checks = gh.checks(sha)
        failing = [c for c in train_required if checks.get(c) not in (None, *OK_STATES)]
        if not failing and any(checks.get(c) is None for c in train_required):
            summary["in_flight"] = True
            continue
        got = git("fetch", "-q", "origin", f"+refs/heads/{branch}:refs/merge-train/settle", check=False).returncode == 0
        fetched = git("rev-parse", "refs/merge-train/settle").stdout.strip() if got else ""
        rec_state, rec_desc = gh.app_statuses(sha).get(RECORD_CONTEXT, ("", ""))
        problem = None if fetched == sha else "carrier branch missing or moved under the carrier"
        problem = problem or record_problem(rec_desc if rec_state == "success" else None,
                                            BOT_LOGIN if rec_state == "success" else None, git.tree(sha))
        chain = None if problem else parse_own_chain(git, sha)
        if not problem and chain is None:
            problem = "head is not a chain of the train's own commits"
        live_main = gh.main_sha()
        if not problem and chain[0] != live_main:
            problem = "main moved since publish — the next tick rebuilds"
        if problem:
            log(f"::warning::carrier #{carrier}: {problem}")
            summary["rejected"].append(carrier)
        elif not failing:
            expected = live_main
            for n, head in chain[1]:
                pr = gh.pr(n)
                if pr.get("state") != "open" or pr["head"]["sha"] != head:
                    log(f"#{n} changed since the train was verified; stopping")
                    break
                if not shadow and gh.main_sha() != expected:
                    log("main advanced by a foreign merge; stopping, the next tick rebuilds")
                    break
                gh.post_status(head, "success", f"verified on train over main {chain[0][:12]}")
                if shadow:
                    continue
                merged = gh.merge(n, head)
                if not merged:
                    log(f"#{n} did not merge; stopping")
                    break
                expected = merged
                summary["merged"].append(n)
        else:
            members = chain[1]
            if len(members) == 1:
                n, head = members[0]
                gh.post_status(head, "failure", f"isolated as the cause of a red train over main {chain[0][:12]}")
                gh.comment(n, CULPRIT_MESSAGE)
                summary["culprits"].append(n)
            else:
                for _n, head in members:
                    gh.post_status(head, "pending", f"bisect size {max(1, len(members) // 2)}")
                summary["bisected"].append([n for n, _ in members])
        gh.close_carrier(carrier)
        if TRAIN_BRANCH_RE.fullmatch(branch):
            Pusher(git, branch, gh.remote).delete()
    # Fast lane, from live data only — nothing here reads a stored classification.
    base = live_base(gh, git)
    regen_text = main_regen_text(git, base)
    cands, apps = live_candidates(gh, git, repo, required, base, Untrainable(regen_text))
    fragments = regen_inventory_fragments(regen_text)
    fast, _train = partition(git, base, cands, fragments)
    for m in fast:
        if apps.get(m.number, {}).get(STATUS_CONTEXT, ("",))[0] != "success":
            gh.post_status(m.head_sha, "success", f"fast lane: disjoint from main {base[:12]} and the batch")
        summary["fast"].append(m.number)
    return summary


def write_output(key: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as fh:
            fh.write(f"{key}={value}\n")


# --------------------------------------------------------------------------- declaration gate
TOP = [
    ("schedule", "schedule:"),
    ("manual dispatch", "workflow_dispatch:"),
    ("concurrency group", "group: merge-train"),
    ("never cancels a train in flight", "cancel-in-progress: false"),
    ("shadow mode is the default", "MERGE_TRAIN_SHADOW: \"true\""),
]
_ISOLATION = [("script from origin/main", "show origin/main:.github/scripts/merge-train.py"),
              ("isolated python", "python3 -I -S"), ("no system git config", "GIT_CONFIG_NOSYSTEM: \"1\""),
              ("no global git config", "GIT_CONFIG_GLOBAL: /dev/null"), ("no credential left", "persist-credentials: false"),
              ("mints the App token", "actions/create-github-app-token@")]
JOB_MUST = {
    "build": [("build runs build", "merge-train.py build"), ("build: no credential left", "persist-credentials: false"),
              ("build regenerates with opa", "openpolicyagent.org/downloads/"),
              ("build hands over a bundle", "actions/upload-artifact@")],
    "publish": [(f"publish: {a}", b) for a, b in _ISOLATION] + [("publish takes the bundle", "actions/download-artifact@"),
                                                              ("publish runs publish", "\" publish")],
    "settle": [(f"settle: {a}", b) for a, b in _ISOLATION] + [("settle runs settle", "\" settle")],
}
JOB_FORBID = {
    "build": [("build must not mint the App token", "create-github-app-token"), ("build must not see App secrets", "GITOPS_APP_")],
    "publish": [("publish runs no repo script", "python3 .github/"), ("publish runs no generator", "regen-derived.sh"),
                ("publish installs no opa", "openpolicyagent.org"), ("publish installs no pyyaml", "install-pyyaml")],
    "settle": [("settle runs no repo script", "python3 .github/"), ("settle runs no generator", "regen-derived.sh"),
               ("settle takes no artifact", "download-artifact"), ("settle installs no opa", "openpolicyagent.org")],
}


def job_sections(text: str) -> dict[str, str]:
    m = re.search(r"^jobs:\s*$", text, re.MULTILINE)
    if not m:
        return {}
    parts = re.split(r"^  ([A-Za-z0-9_-]+):\s*$", text[m.end():], flags=re.MULTILINE)
    return {parts[i]: parts[i + 1] for i in range(1, len(parts) - 1, 2)}


def check_declaration() -> int:
    if not WORKFLOW.exists():
        print(f"::error::{WORKFLOW} missing")
        print("SUBJECTS=0")
        return 1
    text = WORKFLOW.read_text()
    code = "\n".join(ln for ln in text.splitlines() if not ln.lstrip().startswith("#"))
    jobs = job_sections(code)
    fails = [label for label, needle in TOP if needle not in code]
    if "contents: write" in code:
        fails.append("GITHUB_TOKEN must stay read-only (writes use the App token)")
    for job in JOB_MUST:
        sec = jobs.get(job)
        if sec is None:
            fails.append(f"job `{job}` missing")
            continue
        fails += [label for label, needle in JOB_MUST[job] if needle not in sec]
        fails += [label for label, needle in JOB_FORBID[job] if needle in sec]
    for f in fails:
        print(f"::error::merge-train.yml declaration: {f}")
    n = len(TOP) + 1 + sum(len(v) for v in JOB_MUST.values()) + sum(len(v) for v in JOB_FORBID.values())
    print(f"SUBJECTS={n}")
    print("merge-train declaration OK" if not fails else f"{len(fails)} declaration failure(s)")
    return 1 if fails else 0


# --------------------------------------------------------------------------- self-test
SYN_GENERATOR = ".github/scripts/regen.py"
SYN_GENERATOR_SRC = (
    "import json,os,pathlib\n"
    "os.makedirs('derived',exist_ok=True)\n"
    "pathlib.Path('derived/index.txt').write_text('\\n'.join(sorted(os.listdir('services')))+'\\n')\n"
    "pathlib.Path(os.environ.get('PROBE_OUT') or os.devnull).write_text("
    "json.dumps({'GH_TOKEN': os.environ.get('GH_TOKEN',''), 'keys': sorted(os.environ)}))\n"
    # Like regen-derived.sh's run_bundles: execute every bundle generator, at ANY depth.
    "import fnmatch,subprocess\n"
    "for r,_,fs in os.walk('openbank-infra/gitops'):\n"
    "    for f in fs:\n"
    "        if fnmatch.fnmatch(f,'gen-*opa-bundle*.sh'): subprocess.run(['bash',os.path.join(r,f)])\n"
)
SYN_REQUIRED = ["gates (data)", "Validate manifests", "derived", "issue-hygiene", STATUS_CONTEXT]
# The GENERATORS fragments make docs/, services/, count.txt and bad.txt protected, so PRs touching
# them ride trains (as real inventory sources do) instead of the fast lane; svc/ stays fast-lane.
SYN_INVENTORY = ('GENERATORS=(\n  "syn|python3 x|services/ docs/ count.txt bad.txt"\n)\n'
                 'DERIVED_OUTPUTS=(\n  "derived/*"\n)\n'
                 "  done < <(find openbank-infra/gitops -name 'gen-*opa-bundle*.sh' | sort)\n")
SYN_DEEP_GENERATOR = "openbank-infra/gitops/a/b/c/d/gen-x-opa-bundle.sh"


class FakeGitHub:
    """A GitHub stand-in over a synthetic origin: PRs are local branches, CI is ci.py on train heads
    (PR heads report green, as their own CI did), merges are squashes onto origin's main."""

    def __init__(self, origin: str, scratch: str):
        self.origin, self.scratch, self.branch, self.run_url, self.remote = origin, scratch, "main", "http://run", origin
        self.prs: dict[int, dict] = {}
        self.statuses: dict[str, list[tuple[str, str, str, str]]] = {}
        self.comments: list[tuple[int, str]] = []
        self.merged: list[int] = []
        self.carriers: dict[int, dict] = {}
        self._ci: dict[str, dict] = {}
        self.foreign_after_merge: str | None = None

    def g(self, *a, check=True):
        return sh(["git", "-c", "core.hooksPath=/dev/null", *a], cwd=self.origin, check=check, env=base_env())

    def add_pr(self, n: int, branch: str, created: str):
        self.prs[n] = {"number": n, "state": "open", "draft": False, "auto_merge": {"x": 1}, "title": branch, "body": "",
                       "created_at": created, "user": {"login": "someone"},
                       "head": {"ref": branch, "sha": self.g("rev-parse", branch).stdout.strip(), "repo": {"full_name": "o/r"}}}

    def head_refspec(self, n): return f"refs/heads/{self.prs[n]['head']['ref']}"
    def required(self): return list(SYN_REQUIRED)
    def main_sha(self): return self.g("rev-parse", "main").stdout.strip()
    def open_prs(self): return [p for p in list(self.prs.values()) + list(self.carriers.values()) if p["state"] == "open"]
    def pr(self, n): return self.prs[n]

    def pr_files(self, n):
        return self.g("diff", "--name-only", f"main...{self.prs[n]['head']['sha']}").stdout.split()

    def checks(self, sha):
        if any(p["head"]["sha"] == sha for p in self.prs.values()):
            out = {c: "success" for c in SYN_REQUIRED[:4]}
        else:
            if sha not in self._ci:
                wt = tempfile.mkdtemp(dir=self.scratch)
                self.g("worktree", "add", "-q", "--detach", wt, sha)
                r = subprocess.run([sys.executable, "ci.py"], cwd=wt, text=True, capture_output=True)
                failing = [c for c in r.stdout.strip().split(",") if c]
                self.g("worktree", "remove", "--force", wt)
                self._ci[sha] = {**{c: ("failure" if c in failing else "success") for c in SYN_REQUIRED[:3]}, "issue-hygiene": "success"}
            out = dict(self._ci[sha])
        for ctx, state, _desc, _creator in reversed(self.statuses.get(sha, [])):
            out.setdefault(ctx, None if state == "pending" else state)
        return out

    def app_statuses(self, sha):
        out = {}
        for ctx, state, desc, creator in reversed(self.statuses.get(sha, [])):
            if creator == BOT_LOGIN:
                out.setdefault(ctx, (state, desc))
        return out

    def post_status(self, sha, state, description, context=STATUS_CONTEXT, creator=BOT_LOGIN):
        self.statuses.setdefault(sha, []).append((context, state, description, creator))

    def comment(self, n, text): self.comments.append((n, text))

    def merge(self, n, head):
        if self.prs[n]["head"]["sha"] != head:
            return None
        self.g("checkout", "-q", "-f", "main")
        if self.g("merge", "--squash", head, check=False).returncode != 0:
            self.g("merge", "--abort", check=False)
            self.g("reset", "-q", "--hard")
            return None
        self.g("commit", "-q", "-m", f"#{n}")
        sha = self.main_sha()
        self.prs[n]["state"] = "closed"
        self.merged.append(n)
        if self.foreign_after_merge:
            p = pathlib.Path(self.origin, self.foreign_after_merge)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("foreign\n")
            self.g("add", "-A")
            self.g("commit", "-q", "-m", "foreign")
            self.foreign_after_merge = None
        return sha

    def create_carrier(self, branch):
        n = 9000 + len(self.carriers)
        self.carriers[n] = {"number": n, "state": "open", "draft": True, "user": {"login": BOT_LOGIN}, "created_at": "",
                            "head": {"ref": branch, "sha": self.g("rev-parse", branch).stdout.strip(), "repo": {"full_name": "o/r"}}}
        return n

    def close_carrier(self, n): self.carriers[n]["state"] = "closed"


def _synthetic_origin(root: str) -> None:
    env = base_env()

    def g(*a):
        return subprocess.run(["git", *a], cwd=root, text=True, capture_output=True, check=True, env=env)

    def w(rel, text):
        p = pathlib.Path(root, rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    os.makedirs(root)
    g("init", "-q", "-b", "main")
    w("services/s1.txt", "s1\n")
    w("count.txt", "1\n")
    w(SYN_GENERATOR, SYN_GENERATOR_SRC)
    w(REGEN_INVENTORY, SYN_INVENTORY)
    w("ci.py", "import os,sys\nfail=[]\n"
               "if int(open('count.txt').read()) != len(os.listdir('services')): fail.append('gates (data)')\n"
               "if os.path.exists('bad.txt'): fail.append('Validate manifests')\n"
               "want='\\n'.join(sorted(os.listdir('services')))+'\\n'\n"
               "if not os.path.exists('derived/index.txt') or open('derived/index.txt').read()!=want: fail.append('derived')\n"
               "print(','.join(fail)); sys.exit(1 if fail else 0)\n")
    subprocess.run([sys.executable, SYN_GENERATOR], cwd=root, check=True, env=env)
    g("add", "-A")
    g("commit", "-q", "-m", "base")

    def pr(name, edits, link=None):
        g("checkout", "-q", "-b", name, "main")
        for rel, text in edits.items():
            w(rel, text)
        if link:
            os.symlink(link[1], os.path.join(root, link[0]))
        g("add", "-A")
        g("commit", "-q", "-m", name)
        g("checkout", "-q", "main")

    pr("pA", {"services/a.txt": "a\n", "count.txt": "2\n"})
    pr("pB", {"services/b.txt": "b\n", "count.txt": "2\n"})
    pr("p1", {"docs/one.md": "1\n"})
    pr("p2", {"docs/two.md": "2\n"})
    pr("p3", {"bad.txt": "x\n"})
    pr("p4", {"docs/four.md": "4\n"})
    pr("pR", {"services/r.txt": "r\n", "count.txt": "2\n"})
    pr("pL", {"docs/l.md": "l\n"}, link=("docs-link", "/etc/passwd"))
    pr("pG", {SYN_GENERATOR: "import os,pathlib\npathlib.Path('leaked.txt').write_text(os.environ.get('GH_TOKEN','none'))\n"})
    pr("pW", {".github/gates/gates.yaml": "gates: []\n"})
    pr("pX", {"docs/adr/0001-x.md": "x\n"})
    pr("pF", {"svc/fast.txt": "f\n"})
    # A bundle generator deeper than any fixed-depth glob reaches; it would leave a marker if run.
    pr("pD", {SYN_DEEP_GENERATOR: f"touch {os.path.join(os.path.dirname(root), 'deep-generator-ran')}\n",
              "docs/d.md": "d\n"})


def self_test() -> int:
    fails: list[str] = []
    ran: list[str] = []

    def check(label: str, cond: bool, detail: str = "") -> None:
        ran.append(label)
        if not cond:
            fails.append(f"{label}: {detail}")

    # ---- qualifies -----------------------------------------------------------------------
    req = ["all-green", "Gitleaks", STATUS_CONTEXT]
    green = {"all-green": "success", "Gitleaks": "success"}
    base_pr = {"draft": False, "auto_merge": {"x": 1}, "same_repo": True, "head_ref": "feat/x", "state": "open",
               "files": ["openbank-fx-service/A.kt"]}
    check("qualifies: armed + green", qualifies(base_pr, req, green)[0])
    for label, over, checks in [("closed", {"state": "closed"}, green), ("draft", {"draft": True}, green),
                                ("unarmed", {"auto_merge": None}, green), ("codex/", {"head_ref": "codex/x"}, green),
                                ("fork", {"same_repo": False}, green), ("pending", {}, {**green, "Gitleaks": None}),
                                ("failure", {}, {**green, "Gitleaks": "failure"}), ("never reported", {}, {"all-green": "success"}),
                                ("agent-pr-guard", {}, {**green, "gates / agent-pr-guard": "failure"})]:
        check(f"qualifies: {label} refused", not qualifies({**base_pr, **over}, req, checks)[0])
    check("qualifies: no required set refused", not qualifies(base_pr, [], green)[0])
    real_regen = pathlib.Path(REGEN_INVENTORY).read_text() if pathlib.Path(REGEN_INVENTORY).exists() else ""
    U = Untrainable(real_regen)
    check("untrainable: main's find enumeration is parsed", (U.find_root, U.find_name) ==
          ("openbank-infra/gitops/components", "gen-*opa-bundle*.sh"), f"{U.find_root} {U.find_name}")
    for f in (".github/scripts/regen-derived.sh", ".github/gates/gates.yaml", ".github/scripts/check-x.py",
              ".github/workflows/ci.yml", "docs/adr/gen-index.sh", "docs/adr/lib-frontmatter.sh",
              "openbank-infra/gitops/components/x/gen-x-opa-bundle.sh",
              "openbank-infra/gitops/components/a/b/c/d/gen-x-opa-bundle.sh"):
        ok, why = qualifies({**base_pr, "files": [f]}, req, green, None, U)
        check(f"qualifies: member touching {f} is refused (manual lane)", not ok and "manual lane" in why, why)
    for f in ("openbank-libs/governance/rules.yaml", "docs/adr/0343-x.md", "openbank-infra/gitops/components/x/x.yaml"):
        check(f"untrainable: data file {f} is NOT untrainable", not U(f))
    check("untrainable: an unparsable runner fails closed (any gen-* at any depth)",
          Untrainable("")("openbank-infra/gitops/a/b/c/d/gen-x-opa-bundle.sh") and Untrainable("")("x/y/z/gen-q.py"))
    check("qualifies: an App failure (culprit) on the head waits for a push",
          not qualifies(base_pr, req, green, {STATUS_CONTEXT: ("failure", "x")})[0])
    check("order: oldest first", [p["number"] for p in order_candidates(
        [{"number": 9, "created_at": "2026-02"}, {"number": 3, "created_at": "2026-01"}])] == [3, 9])

    # ---- paths / fast lane / batch ---------------------------------------------------------
    check("glob: `*` does not cross `/`", glob_match("a/*-opa-bundle.yaml", "a/x-opa-bundle.yaml")
          and not glob_match("a/*-opa-bundle.yaml", "a/b/x-opa-bundle.yaml"))
    real = pathlib.Path(REGEN_INVENTORY).read_text() if pathlib.Path(REGEN_INVENTORY).exists() else ""
    outs = derived_output_patterns(real)
    check("inventory: DERIVED_OUTPUTS parsed from the real regen-derived.sh", "docs/adr/index.json" in outs, str(outs))
    check("inventory: no Rollout manifest is a derived output",
          not any(glob_match(g, "openbank-infra/gitops/components/ledger/ledger-service.yaml") for g in outs))
    frags = regen_inventory_fragments(real)
    check("fast lane: disjoint service file qualifies", fast_lane_eligible(["svc/a.kt"], ["other/x"], [], frags)[0])
    for f in ("docs/adr/0343-x.md", "openbank-fx-service/src/main/resources/openapi.yaml", ".github/x.yml",
              "build-logic/x.kts", "prompts/registry.yaml"):
        check(f"fast lane: {f} does NOT qualify", not fast_lane_eligible([f], [], [], frags)[0])
    check("fast lane: overlap with main or the batch does NOT qualify",
          not fast_lane_eligible(["a"], ["a"], [], frags)[0] and not fast_lane_eligible(["a"], [], ["a"], frags)[0])
    mk = lambda *ns: [Member(n, "", "", "a" * 40, "", "") for n in ns]  # noqa: E731
    check("batch: default size", [m.number for m in select_batch(mk(1, 2, 3), {}, 2)] == [1, 2])
    check("batch: a bisect size on the oldest caps it", [m.number for m in select_batch(mk(1, 2, 3, 4), {1: 2}, 8)] == [1, 2])
    check("bisect status: only a pending `bisect size N` counts",
          bisect_size({STATUS_CONTEXT: ("pending", "bisect size 2")}) == 2
          and bisect_size({STATUS_CONTEXT: ("success", "bisect size 2")}) is None
          and bisect_size({STATUS_CONTEXT: ("pending", "bisect size 2; rm -rf")}) is None)

    # ---- branch names ----------------------------------------------------------------------
    check("branch: derived from the run", train_branch("123", "1") == "train/123-1")
    for hostile in ("refs/heads/main", "../x", "main", "train/1-1/../main", "train/abc-1", "train/1-1.lock", "train/-1"):
        try:
            assert_train_branch(hostile)
            refused = False
        except ValueError:
            refused = True
        check(f"branch: {hostile!r} refused", refused)
    calls: list[tuple] = []

    def rec_git(*a, check=True, auth=False):
        calls.append(a)
        return subprocess.CompletedProcess(a, 0, "", "")
    try:
        Pusher(rec_git, "refs/heads/main", "u")
        built = True
    except ValueError:
        built = False
    p_ok = Pusher(rec_git, "train/7-1", "u")
    p_ok.push("a" * 40)
    p_ok.delete()
    check("branch: only refs/heads/train/7-1 is ever pushed or deleted",
          not built and calls == [("push", "-q", "-f", "u", "a" * 40 + ":refs/heads/train/7-1"),
                                  ("push", "-q", "u", "--delete", "refs/heads/train/7-1")], str(calls))

    # ---- settle record ---------------------------------------------------------------------
    t1, t2 = "a" * 40, "b" * 40
    check("record: App record with the commit's tree is accepted", record_problem(f"tree={t1}", BOT_LOGIN, t1) is None)
    check("record: a sha whose tree differs from the record is refused",
          "not the recorded tree" in (record_problem(f"tree={t1}", BOT_LOGIN, t2) or ""))
    check("record: a record by any other creator is refused", record_problem(f"tree={t1}", "someone", t1) is not None)
    check("record: a malformed record is refused", record_problem(f"tree={t1} members=1", BOT_LOGIN, t1) is not None)
    check("comments: fixed texts carry no placeholders", "{" not in DROP_MESSAGE + CULPRIT_MESSAGE + MANUAL_LANE_MESSAGE)

    # ---- real git, real ticks ----------------------------------------------------------------
    saved = {k: os.environ.get(k) for k in ("GH_TOKEN", "GITHUB_TOKEN", "RUNNER_TEMP")}
    os.environ.update({"GH_TOKEN": "ghs_SELFTEST_MUST_NOT_LEAK", "GITHUB_TOKEN": "ghs_2", "RUNNER_TEMP": "/runner/temp"})
    try:
        env = generator_env()
        check("generator env: no *TOKEN*, no RUNNER_TEMP, isolated git config",
              not any("TOKEN" in k for k in env) and "RUNNER_TEMP" not in env and env["GIT_CONFIG_GLOBAL"] == os.devnull)
        with tempfile.TemporaryDirectory() as root:
            origin = os.path.join(root, "origin")
            _synthetic_origin(origin)
            gh = FakeGitHub(origin, root)
            base0 = gh.main_sha()
            numbers = {"pA": 11, "pB": 12, "p1": 1, "p2": 2, "p3": 3, "p4": 4, "pR": 21, "pL": 31, "pG": 32, "pW": 35, "pD": 41,
                       "pX": 33, "pF": 34}
            probe = os.path.join(root, "probe.json")
            counter = [0]

            def clone(tag):
                counter[0] += 1
                d = os.path.join(root, f"{tag}-{counter[0]}")
                sh(["git", "clone", "-q", "--branch", "main", origin, d], env=base_env())
                return d

            def tick(regen=None, batch_size=8):
                """settle -> build -> publish, each in its own fresh clone, as on three runners."""
                s = mode_settle(gh, Git(clone("settle")), "o/r", False)
                if s["in_flight"]:
                    return s, "in-flight"
                counter[0] += 1
                bdir = clone("build")
                b = TrainBuilder(bdir, regen_commands=regen or (f"{sys.executable} {SYN_GENERATOR}",),
                                 overlay_roots=(".github/scripts",))
                b.env["PROBE_OUT"] = probe
                out = os.path.join(root, f"bundle-{counter[0]}")
                mode_build(gh, Git(bdir), b, "o/r", out, batch_size)
                return s, mode_publish(gh, Git(clone("publish")), "o/r", out, str(counter[0]), "1", batch_size)

            def open_only(*branches):
                for br in branches:
                    gh.add_pr(numbers[br], br, f"2026-01-{numbers[br]:02d}")

            def drain(max_ticks=12, **kw):
                for _ in range(max_ticks):
                    s, st = tick(**kw)
                    if st == "idle" and not s["in_flight"]:
                        return

            def reset():
                for p in gh.prs.values():
                    p["state"] = "closed"
                for c in gh.carriers.values():
                    c["state"] = "closed"
                gh.merged.clear()
                gh.comments.clear()
                gh.statuses.clear()
                gh.g("checkout", "-q", "-f", "main")
                gh.g("reset", "-q", "--hard", base0)

            # 4-PR batch, #3 bad: bisect across ticks isolates #3; 1, 2, 4 merge.
            open_only("p1", "p2", "p3", "p4")
            drain()
            check("ticks: 4-PR batch with #3 bad merges 1, 2 and 4", sorted(gh.merged) == [1, 2, 4], str(gh.merged))
            check("ticks: #3 is the culprit (App failure + fixed comment)",
                  (3, CULPRIT_MESSAGE) in gh.comments
                  and gh.app_statuses(gh.prs[3]["head"]["sha"]).get(STATUS_CONTEXT, ("",))[0] == "failure", str(gh.comments))
            check("ticks: every carrier was closed", all(c["state"] == "closed" for c in gh.carriers.values()))
            reset()

            # Semantic pair green alone, red together: one merges, the other is the culprit.
            open_only("pA", "pB")
            drain()
            check("semantic pair: exactly one merges, the other is the culprit",
                  gh.merged == [11] and (12, CULPRIT_MESSAGE) in gh.comments, f"{gh.merged} {gh.comments}")
            reset()

            # Regeneration: pR needs derived/index.txt regenerated; the train carries it and merges.
            open_only("pR")
            drain()
            check("regen: pR merges through a train whose regeneration commit is in the inventory", gh.merged == [21], str(gh.merged))
            rec = json.loads(pathlib.Path(probe).read_text())
            check("exfiltration probe: the generator saw no GH_TOKEN and no *TOKEN* key",
                  rec["GH_TOKEN"] == "" and not any("TOKEN" in k for k in rec["keys"]), str(rec)[:160])
            reset()

            # NEGATIVE: a symlink in the train tree.
            open_only("pL")
            drain()
            check("symlink: a train adding a symlink never merges; the PR gets the manual-lane comment",
                  gh.merged == [] and (31, MANUAL_LANE_MESSAGE) in gh.comments, f"{gh.merged} {gh.comments}")
            reset()

            # NEGATIVE: a regeneration commit touching a non-inventory path.
            open_only("pR")
            drain(regen=(f"{sys.executable} {SYN_GENERATOR}", f"{sys.executable} -c \"open('evil.txt','w').write('x')\""))
            check("regen outside inventory: train rejected, nothing merges", gh.merged == [] and (21, MANUAL_LANE_MESSAGE) in gh.comments,
                  f"{gh.merged} {gh.comments}")
            reset()

            # NEGATIVE: members touching a generator or a gate file never qualify.
            open_only("pG", "pW")
            drain()
            check("gate-file members: never qualify, never merge, nothing leaks",
                  gh.merged == [] and not gh.carriers.get(max(gh.carriers, default=0), {}).get("state") == "open"
                  and not os.path.exists(os.path.join(origin, "leaked.txt")) and not gh.comments, f"{gh.merged} {gh.comments}")
            reset()

            # NEGATIVE (review 5): a bundle generator deeper than any fixed glob.
            marker = os.path.join(root, "deep-generator-ran")
            open_only("pD")
            drain()
            check("deep generator: the PR never qualifies, never merges, and its generator never ran",
                  gh.merged == [] and not os.path.exists(marker), f"{gh.merged} ran={os.path.exists(marker)}")
            bdir = clone("build")
            b = TrainBuilder(bdir, regen_commands=(f"{sys.executable} {SYN_GENERATOR}",), overlay_roots=(".github/scripts",),
                             untrainable=Untrainable(SYN_INVENTORY))
            b.git("fetch", "-q", "origin", "pD")
            head = b.git("rev-parse", "FETCH_HEAD").stdout.strip()
            _tip, applied, dropped = b.build(base0, [Member(41, "pD", "", head, "pD", "t", ref=head)])
            check("deep generator: forced past qualifies, build drops it and it still never runs",
                  applied == [] and dropped == [41] and not os.path.exists(marker), f"{applied} {dropped}")
            b.git("checkout", "-q", "-f", "--detach", head)       # the file present in the working tree
            b.quarantine_new_executables(base0)
            b.regenerate()
            check("deep generator: quarantine removes a not-on-main executable before any generator runs",
                  b.quarantined == [SYN_DEEP_GENERATOR] and not os.path.exists(marker)
                  and os.path.exists(os.path.join(bdir, SYN_DEEP_GENERATOR)), f"{b.quarantined}")
            reset()

            # NEGATIVE (review 5): the files API is capped at 3000; a long PR hides a path there.
            open_only("pW")
            api_calls = []
            gh.pr_files = lambda n: api_calls.append(n) or [f"svc/f{i}.txt" for i in range(3000)]
            cands, _apps = live_candidates(gh, Git(clone("settle")), "o/r", gh.required(), base0, Untrainable(SYN_INVENTORY))
            check("files: a 3000-entry API list hiding .github/gates/gates.yaml does not admit the PR",
                  all(m.number != 35 for m in cands) and api_calls == [], f"{[m.number for m in cands]} api={api_calls}")
            del gh.pr_files
            reset()

            # Fast lane: computed live in settle. A docs/adr member is never fast-laned, and settle
            # has no input through which a stored classification could be injected.
            open_only("pF", "pX")
            sm = mode_settle(gh, Git(clone("settle")), "o/r", False)
            check("fast lane: a disjoint PR gets success from settle", 34 in sm["fast"]
                  and gh.app_statuses(gh.prs[34]["head"]["sha"]).get(STATUS_CONTEXT, ("",))[0] == "success")
            check("fast lane: a docs/adr member is not fast-laned, and settle takes no state input",
                  33 not in sm["fast"] and not gh.app_statuses(gh.prs[33]["head"]["sha"])
                  and set(mode_settle.__code__.co_varnames[:mode_settle.__code__.co_argcount]) == {"gh", "git", "repo", "shadow"})
            reset()

            # Bundle verification, directly.
            open_only("p1", "p2")
            pub = Git(clone("publish"))
            base = live_base(gh, pub)
            _f, batch = plan_batch(gh, pub, "o/r", base, 8)
            bdir = clone("build")
            b = TrainBuilder(bdir, regen_commands=(), overlay_roots=(".github/scripts",))
            for m in batch:
                b.git("fetch", "-q", "origin", f"+{gh.head_refspec(m.number)}:refs/x/{m.number}")
            tip, _applied, _d = b.build(base, batch)

            def bundle_of(rev, tag):
                b.git("update-ref", BUNDLE_REF, rev)
                path = os.path.join(root, f"{tag}.bundle")
                b.git("bundle", "create", path, BUNDLE_REF, f"^{base}")
                pub("fetch", "-q", path, f"+{BUNDLE_REF}:refs/x/{tag}")
                return pub("rev-parse", f"refs/x/{tag}").stdout.strip()

            def verdict(rev, base_=None):
                try:
                    return verify_bundle_train(pub, base_ or base, batch, rev, ["derived/*"])
                except Reject as exc:
                    return str(exc)
            v = verdict(bundle_of(tip, "honest"))
            check("bundle: an honest bundle verifies; publish pushes its OWN commits with the same tree",
                  isinstance(v, Verified) and v.applied == [1, 2] and v.tree == pub.tree(tip) and v.tip != tip, str(v))
            b.git("checkout", "-q", "-f", "--detach", f"{tip}~1")
            pathlib.Path(bdir, "docs/one.md").write_text("1\n ")
            b.git("commit", "-qam", "squash with one extra byte")
            b.git("cherry-pick", "--allow-empty", tip)
            v = verdict(bundle_of(b.git("rev-parse", "HEAD").stdout.strip(), "onebyte"))
            check("bundle: a member tree one byte off the merge-tree rebuild is rejected",
                  isinstance(v, str) and "differs from the merge-tree rebuild" in v, str(v))
            b.git("checkout", "-q", "-f", "--detach", tip)
            pathlib.Path(bdir, "docs/extra.md").write_text("x\n")
            b.git("add", "-A")
            b.git("commit", "-qm", "extra member outside the batch")
            v = verdict(bundle_of(b.git("rev-parse", "HEAD").stdout.strip(), "extra"))
            check("bundle: an extra commit outside the batch is rejected", isinstance(v, str) and "not a declared derived output" in v, str(v))
            b.git("checkout", "-q", "-f", "--detach", tip)
            os.symlink("/etc/passwd", os.path.join(bdir, "derived", "x"))
            b.git("add", "-A")
            b.git("commit", "-qm", REGEN_COMMIT_SUBJECT)
            v = verdict(bundle_of(b.git("rev-parse", "HEAD").stdout.strip(), "regenlink"))
            check("bundle: a regeneration commit adding a symlink inside the inventory is rejected",
                  isinstance(v, str) and "symlink" in v, str(v))
            gh.g("checkout", "-q", "-f", "main")
            pathlib.Path(origin, "later.txt").write_text("x\n")
            gh.g("add", "-A")
            gh.g("commit", "-qm", "later")
            live = live_base(gh, pub)
            v = verdict(pub("rev-parse", "refs/x/honest").stdout.strip(), live)
            check("bundle: a train over a stale base is rejected", isinstance(v, str) and "live base" in v, str(v))
            reset()

            # NEGATIVE: settle on a carrier head whose tree is not publish's recorded tree.
            open_only("p1")
            tick()
            carrier = next(c for c in gh.carriers.values() if c["state"] == "open")
            gh.g("checkout", "-q", "-f", "--detach", carrier["head"]["sha"])
            pathlib.Path(origin, "docs/one.md").write_text("1\nx")
            gh.g("commit", "-qam", own_member_message(1, gh.prs[1]["head"]["sha"]))
            moved = gh.g("rev-parse", "HEAD").stdout.strip()
            gh.g("update-ref", f"refs/heads/{carrier['head']['ref']}", moved)
            gh.g("checkout", "-q", "-f", "main")
            carrier["head"]["sha"] = moved
            sm = mode_settle(gh, Git(clone("settle")), "o/r", False)
            check("settle: a head whose tree is not the App-recorded tree merges nothing",
                  gh.merged == [] and carrier["number"] in sm["rejected"], f"{gh.merged} {sm}")
            # ... and a record posted by anyone but the App does not count either.
            gh.post_status(moved, "success", f"tree={Git(origin).tree(moved)}", context=RECORD_CONTEXT, creator="someone")
            gh.g("update-ref", f"refs/heads/{carrier['head']['ref']}", moved)   # settle deleted it
            carrier["state"] = "open"
            sm = mode_settle(gh, Git(clone("settle")), "o/r", False)
            check("settle: a record forged by a non-App creator merges nothing", gh.merged == [] and carrier["number"] in sm["rejected"])
            reset()

            # Shadow: statuses, no merges.
            open_only("p1")
            tick()
            mode_settle(gh, Git(clone("settle")), "o/r", True)
            check("shadow: settle posts success on the verified head and merges nothing",
                  gh.merged == [] and gh.app_statuses(gh.prs[1]["head"]["sha"]).get(STATUS_CONTEXT, ("",))[0] == "success")
            reset()

            # A foreign merge landing during settle stops the merges after the first.
            open_only("p1", "p2")
            tick()
            gh.foreign_after_merge = "svc/foreign.txt"
            mode_settle(gh, Git(clone("settle")), "o/r", False)
            check("foreign merge mid-settle: merging stops, the rest waits for the next tick", gh.merged == [1], str(gh.merged))
            reset()
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
    ap.add_argument("mode", nargs="?", choices=["build", "publish", "settle"])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-declaration", action="store_true")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--branch", default="main")
    ap.add_argument("--batch-size", type=int, default=int(os.environ.get("TRAIN_BATCH_SIZE", "8")))
    ap.add_argument("--out", default="", help="build: directory for train.bundle")
    ap.add_argument("--bundle", default="", help="publish: directory holding train.bundle")
    ap.add_argument("--run-id", default="")
    ap.add_argument("--attempt", default="")
    ap.add_argument("--workdir", default="")
    ap.add_argument("--run-url", default=os.environ.get("MERGE_TRAIN_RUN_URL", ""))
    ap.add_argument("--shadow", action="store_true", help="settle: post statuses and comments, never merge (phase 0)")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.check_declaration:
        return check_declaration()
    if not args.mode or not args.repo:
        ap.error("mode and --repo are required (or --self-test / --check-declaration)")
    if "GH_TOKEN" not in os.environ:
        ap.error("GH_TOKEN is required (read-only for build, the App token for publish/settle)")
    gh = GitHub(args.repo, args.branch, args.run_url)
    workdir = args.workdir or os.getcwd()
    if args.mode == "build":
        if not args.out:
            ap.error("build needs --out")
        applied = mode_build(gh, Git(workdir), TrainBuilder(workdir), args.repo, args.out, args.batch_size)
        write_output("has_train", "true" if applied else "false")
        return 0
    git = Git(workdir, token=os.environ["GH_TOKEN"])
    if args.mode == "publish":
        if not args.bundle or not args.run_id or not args.attempt:
            ap.error("publish needs --bundle, --run-id and --attempt")
        log(f"publish: {mode_publish(gh, git, args.repo, args.bundle, args.run_id, args.attempt, args.batch_size)}")
        return 0
    s = mode_settle(gh, git, args.repo, args.shadow)
    log(f"settle: {s}")
    write_output("in_flight", "true" if s["in_flight"] else "false")
    return 0


if __name__ == "__main__":
    sys.exit(main())
