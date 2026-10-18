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
The privileged step acts only on trees it built itself, from verified heads, with trusted
generators. Nothing produced by code a PR could influence is ever consumed — it is at most a hint
compared hash-against-hash with what this script built. Concretely, one job, three steps:

  A  rebuild   (no App token; GITHUB_TOKEN read-only)
               Read origin/main LIVE and use it as the base. List candidates LIVE, run qualifies()
               on live checks and live files, fetch each head and require the fetched sha to equal
               the API's head sha. Rebuild every train the bisect can ask for (2N-1) with plain
               `git merge --squash` of those verified shas, in batch order. Overlay the generator
               paths from origin/main, assert they then equal the base, and run the generators in a
               scrubbed, allowlisted environment. Record each train's commit, tree, applied set and
               dropped set — all computed here, keyed by the batch it was built for.
  B  verify    CI is untrusted by nature: it only reports on a tree. The tree CI ran on is read off
               the carrier PR's LIVE head and compared with the tree step A recorded. Any mismatch,
               including one byte, rejects the train.
  C  publish   (App token minted only now) Push THIS script's own commits — never anything read
               from a plan — to `train/<run_id>-<attempt>`, a name derived here and validated with
               the regex and `git check-ref-format`; nothing else is ever pushed or deleted. Post
               `merge-train` on the verified head and merge with --match-head-commit.
  Comments carry fixed text; no PR- or plan-derived string is interpolated.

MODES
-----
  rebuild --state DIR --run-id N --attempt N   step A; writes DIR/state.json (no App token)
  publish --state DIR --run-id N --attempt N   steps B + C; --shadow posts, never merges
  --dry-run                                    rebuild only prints; publish refuses to run
  --self-test                                  pure logic + a synthetic git repository (no network)
  --check-declaration                          merge-train.yml still wires the steps per ADR-0343
"""
from __future__ import annotations

import argparse
import base64
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
# The ONLY refs this script ever pushes or deletes. Derived from the run, never read from input.
TRAIN_BRANCH_RE = re.compile(r"^train/[0-9]+-[0-9]+$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
# Contexts that judge the PR object (its body, its issue link), not the tree. Green on every
# member already; a carrier PR cannot meaningfully re-earn them and must not wait on them.
TREE_INDEPENDENT_CONTEXTS = frozenset({"issue-hygiene"})
GUARD_CONTEXT_HINT = "agent-pr-guard"
OK_STATES = frozenset({"success"})
# Fast-lane exclusions. Prefixes are directory prefixes; globs are fnmatch over the whole path.
PROTECTED_PREFIXES = ("openbank-libs/", "build-logic/", "openbank-infra/gitops/", "docs/adr/", ".github/")
PROTECTED_GLOBS = ("*/src/main/resources/openapi.yaml",)
# Code step A EXECUTES against a rebuilt tree. A PR touching any of these never qualifies (it would
# run its own change with the generators' authority); step A also overlays these paths from the
# trusted base before running anything and refuses the train if one still differs.
GENERATOR_PREFIXES = (".github/scripts/",)
GENERATOR_GLOBS = ("docs/adr/*.sh", "openbank-infra/gitops/*/gen-*", "openbank-infra/gitops/*/*/gen-*",
                   "openbank-infra/gitops/*/*/*/gen-*")
GENERATOR_OVERLAY_ROOTS = (".github/scripts", "docs/adr", "openbank-infra/gitops")
REGEN_COMMANDS = ("bash .github/scripts/regen-derived.sh --all", "bash docs/adr/gen-index.sh")
# The ONLY environment the generators see. No token of any kind, and no RUNNER_TEMP / GITHUB_*
# paths (the state file and the step-handoff files live there).
GENERATOR_ENV_ALLOWLIST = ("PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
                           "PYTHONIOENCODING", "PYTHONDONTWRITEBYTECODE", "SSL_CERT_FILE")
REGEN_COMMIT_SUBJECT = "chore(train): regenerate derived files"
COMMENT_MARK = "<!-- merge-train -->"
STATE_FILE = "state.json"
# Fixed comment text. Nothing from a PR, a plan, a branch or a commit is ever interpolated.
DROP_MESSAGE = ("merge-train: this PR did not apply cleanly on top of main together with the PRs ahead of "
                "it in this train, so it was left out of the train. Rebase on main; the next train "
                "picks it up again.")
CULPRIT_MESSAGE = ("merge-train: a train over main isolated this PR as the cause of a failing required "
                   "check, and its `merge-train` status is now failure. Push a fix; the next train that "
                   "passes clears it. The workflow run linked from the status has the details.")


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
    ref: str = ""            # local git ref holding the VERIFIED head
    files: list[str] = field(default_factory=list)


def is_generator_path(path: str) -> bool:
    if any(path.startswith(p) for p in GENERATOR_PREFIXES):
        return True
    return any(fnmatch.fnmatch(path, g) for g in GENERATOR_GLOBS)


def qualifies(pr: dict, required: list[str], checks: dict[str, str | None]) -> tuple[bool, str]:
    """`checks` maps context -> conclusion/state (None = still running, absent = never reported)."""
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
        if is_generator_path(f):
            return False, f"modifies a generator path ({f}) — manual lane, never a train"
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


# --------------------------------------------------------------------------- pure: messages, names
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


def ref_format_ok(name: str) -> bool:
    return subprocess.run(["git", "check-ref-format", f"refs/heads/{name}"], capture_output=True).returncode == 0


def train_branch(run_id: str, attempt: str) -> str:
    """The one branch name this run may push or delete. Derived from the run; validated twice."""
    name = f"train/{run_id}-{attempt}"
    assert_train_branch(name)
    return name


def assert_train_branch(name: str) -> None:
    if not TRAIN_BRANCH_RE.fullmatch(name or "") or not ref_format_ok(name):
        raise ValueError(f"refusing ref {name!r}: not of the form train/<run_id>-<attempt>")


def base_current(live_main: str, base: str) -> str | None:
    """Step C may act only over the exact main step A rebuilt on."""
    if not SHA_RE.fullmatch(base or "") or not SHA_RE.fullmatch(live_main or ""):
        return "base or live main is not a full sha"
    if live_main != base:
        return f"main moved since the rebuild ({base[:12]} -> {live_main[:12]}); the next tick rebuilds"
    return None


# --------------------------------------------------------------------------- pure: bisect + binding
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


@dataclass
class TrainRecord:
    key: str
    sha: str
    tree: str
    applied: list[int]
    dropped: dict[int, str]            # reason is for the log only; it never reaches a comment
    commits: int

    def to_json(self) -> dict:
        return {"key": self.key, "sha": self.sha, "tree": self.tree, "applied": self.applied,
                "dropped": sorted(self.dropped), "commits": self.commits}

    @staticmethod
    def from_json(d: dict) -> "TrainRecord":
        return TrainRecord(d["key"], d["sha"], d["tree"], [int(x) for x in d["applied"]],
                           {int(n): "dropped in rebuild" for n in d["dropped"]}, int(d["commits"]))


def bind_problem(batch: list[Member], rec: TrainRecord | None) -> str | None:
    """A record is usable for exactly the batch it was built for: same key, applied an ordered
    subsequence of the batch, applied + dropped == the batch."""
    if rec is None:
        return "no train was built for this batch"
    numbers = [m.number for m in batch]
    if rec.key != train_key(batch):
        return f"record key {rec.key} is not batch {train_key(batch)}"
    it = iter(numbers)
    if not all(n in it for n in rec.applied):
        return f"applied {rec.applied} is not an ordered subset of batch {numbers}"
    if set(rec.applied) | set(rec.dropped) != set(numbers) or set(rec.applied) & set(rec.dropped):
        return f"applied {rec.applied} + dropped {sorted(rec.dropped)} != batch {numbers}"
    if not SHA_RE.fullmatch(rec.sha) or not SHA_RE.fullmatch(rec.tree):
        return "record sha/tree is not a full sha"
    return None


def ci_tree_problem(git, ci_head: str, own: TrainRecord) -> str | None:
    """Step B: the tree CI ran on must be byte-identical to the tree this script built."""
    if not SHA_RE.fullmatch(ci_head or ""):
        return f"CI head {ci_head!r} is not a full sha"
    r = git("rev-parse", "--verify", "--quiet", f"{ci_head}^{{tree}}", check=False)
    if r.returncode != 0:
        return f"CI head {ci_head[:12]} is not present locally"
    tree = r.stdout.strip()
    if tree != own.tree:
        return f"CI ran on tree {tree[:12]}, this run built {own.tree[:12]}"
    return None


# --------------------------------------------------------------------------- git: step A
def sh(cmd: list[str], cwd: str | None = None, check: bool = True, env: dict | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, cwd=cwd, text=True, capture_output=True, check=check, env=env)


def generator_env() -> dict[str, str]:
    env = {k: os.environ[k] for k in GENERATOR_ENV_ALLOWLIST if k in os.environ}
    env.update({"GIT_AUTHOR_NAME": "openbank-gitops-bot", "GIT_COMMITTER_NAME": "openbank-gitops-bot",
                "GIT_AUTHOR_EMAIL": "gitops-bot@openbank.invalid", "GIT_COMMITTER_EMAIL": "gitops-bot@openbank.invalid",
                "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull})
    return env


class TrainBuilder:
    """Rebuilds train commits from VERIFIED heads in a working copy. Pure git + trusted generators."""

    def __init__(self, workdir: str, regen_commands: tuple[str, ...] = REGEN_COMMANDS,
                 overlay_roots: tuple[str, ...] = GENERATOR_OVERLAY_ROOTS):
        self.workdir = workdir
        self.regen_commands = regen_commands
        self.overlay_roots = overlay_roots
        self.env = generator_env()

    def git(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        return sh(["git", *args], cwd=self.workdir, check=check, env=self.env)

    def build(self, base: str, members: list[Member]) -> TrainRecord:
        self.git("checkout", "-q", "--detach", "-f", base)
        self.git("clean", "-qfdx")
        applied: list[Member] = []
        dropped: dict[int, str] = {}
        for m in members:
            r = self.git("merge", "--squash", "--no-commit", m.ref, check=False)
            if r.returncode != 0:
                conflicts = self.git("diff", "--name-only", "--diff-filter=U", check=False).stdout.split()
                self.git("merge", "--abort", check=False)
                self.git("reset", "-q", "--hard", "HEAD")
                dropped[m.number] = f"conflicts in {conflicts[:5]}"
                continue
            staged = self.git("diff", "--cached", "--name-only").stdout.split()
            if not staged:
                self.git("reset", "-q", "--hard", "HEAD")
                dropped[m.number] = "empty after squash"
                continue
            tampered = [f for f in staged if is_generator_path(f)]
            if tampered:
                # Belt: qualifies() already refused it. Braces: never run a member's generator.
                self.git("reset", "-q", "--hard", "HEAD")
                dropped[m.number] = f"modifies a generator path ({tampered[0]})"
                continue
            self.git("commit", "-q", "-m", squash_message(m))
            applied.append(m)
        commits = len(applied)
        if applied:
            try:
                self.overlay_trusted_generators(base)
            except RuntimeError as exc:
                log(f"::error::train {train_key(members)}: {exc}")
                self.git("reset", "-q", "--hard", base)
                for m in applied:
                    dropped[m.number] = f"train refused: {exc}"
                applied, commits = [], 0
            else:
                if self.regenerate():
                    commits += 1
        sha = self.git("rev-parse", "HEAD").stdout.strip()
        tree = self.git("rev-parse", "HEAD^{tree}").stdout.strip()
        return TrainRecord(train_key(members), sha, tree, [m.number for m in applied], dropped, commits)

    def overlay_trusted_generators(self, base: str) -> None:
        """Replace every generator path in the tree with the TRUSTED BASE's copy, and refuse if a
        generator path still differs from the base (modified, or added and so not in the base)."""
        roots = [p for p in self.overlay_roots
                 if self.git("cat-file", "-e", f"{base}:{p}", check=False).returncode == 0]
        if roots:
            archive = subprocess.run(["git", "archive", base, "--", *roots], cwd=self.workdir,
                                     capture_output=True, check=True, env=self.env).stdout
            subprocess.run(["tar", "-x", "-C", self.workdir], input=archive, check=True, capture_output=True)
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

    def files_of(self, a: str, b: str) -> list[str]:
        return self.git("diff", "--name-only", f"{a}...{b}").stdout.split()


def prebuild(builder: TrainBuilder, base: str, members: list[Member]) -> dict[str, TrainRecord]:
    """Rebuild the whole batch and every bisect half over the same base."""
    trains: dict[str, TrainRecord] = {}
    for batch in bisect_tree(members):
        rec = builder.build(base, batch)
        trains[rec.key] = rec
        log(f"  rebuilt {rec.key}: applied={rec.applied} dropped={sorted(rec.dropped)} tree={rec.tree[:12]}")
    return trains


# --------------------------------------------------------------------------- orchestration
def run_train(hub, base_sha: str, members: list[Member], trains: dict[str, TrainRecord], verify,
              fragments: list[str], train_files: list[str], invalid: frozenset[int] = frozenset()) -> Outcome:
    """Bisect over this run's OWN trains, then the status/merge phase. `verify(rec) -> (status, failing)`."""
    out = Outcome()

    def verify_batch(batch: list[Member]) -> tuple[str, list[str]]:
        rec = trains.get(train_key(batch))
        problem = bind_problem(batch, rec)
        if problem or set(rec.applied) & invalid:
            log(f"::warning::batch {train_key(batch)} unusable: {problem or 'carries a member rejected live'}")
            return "unknown", []
        for n in rec.dropped:
            if n not in out.dropped:
                out.dropped[n] = rec.dropped[n]
                hub.comment(n, DROP_MESSAGE)
        kept = [m for m in batch if m.number in rec.applied]
        batch[:] = kept   # bisect halves what APPLIED, so a dropped member is never a culprit
        if not kept:
            return "green", []
        status, failing = verify(rec)
        log(f"  train {rec.key} ({rec.applied}) -> {status} {failing}")
        return status, failing

    bisect(list(members), verify_batch, out)
    out.deferred = [m for m in out.deferred if m.number not in out.dropped]

    for n in out.culprits:
        m = next(x for x in members if x.number == n)
        hub.post_status(m.head_sha, "failure", f"isolated as the cause of a red train over main {base_sha[:12]}")
        hub.comment(n, CULPRIT_MESSAGE)

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


def pr_view(repo: str, p: dict) -> dict:
    return {"number": p["number"], "state": p.get("state"), "draft": p.get("draft"), "auto_merge": p.get("auto_merge"),
            "same_repo": (p.get("head", {}).get("repo") or {}).get("full_name") == repo,
            "head_ref": p["head"]["ref"], "head_sha": p["head"]["sha"], "created_at": p["created_at"],
            "title": p.get("title", ""), "body": p.get("body") or ""}


def git_auth_env(token: str) -> dict[str, str]:
    """Credentials for ONE git process, via env-scoped config — never argv, never on disk."""
    basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    return {**os.environ, "GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "http.https://github.com/.extraheader",
            "GIT_CONFIG_VALUE_0": f"AUTHORIZATION: basic {basic}"}


class Pusher:
    """The only code path that writes refs. It accepts a sha and pushes it to the run's own branch."""

    def __init__(self, git, branch: str, repo_url: str, env: dict | None):
        assert_train_branch(branch)
        self.git, self.branch, self.repo_url, self.env = git, branch, repo_url, env

    def push(self, sha: str) -> None:
        assert_train_branch(self.branch)
        if not SHA_RE.fullmatch(sha):
            raise ValueError(f"refusing to push non-sha {sha!r}")
        self.git("push", "-q", "-f", self.repo_url, f"{sha}:refs/heads/{self.branch}", env=self.env)

    def delete(self) -> None:
        assert_train_branch(self.branch)
        self.git("push", "-q", self.repo_url, "--delete", f"refs/heads/{self.branch}", check=False, env=self.env)


class Hub:
    """Every GitHub side effect, in one place, so --shadow and the self-test can swap it."""

    def __init__(self, repo: str, branch: str, run_url: str, dry_run: bool, shadow: bool):
        self.repo, self.branch, self.run_url, self.dry_run, self.shadow = repo, branch, run_url, dry_run, shadow

    def gh(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        return sh(["gh", *args, "-R", self.repo], check=check)

    def post_status(self, sha: str, state: str, description: str) -> None:
        if not SHA_RE.fullmatch(sha):
            raise SystemExit(f"::error::refusing to post a status on non-sha {sha!r}")
        code, data = api("POST", f"/repos/{self.repo}/statuses/{sha}",
                         {"state": state, "context": STATUS_CONTEXT, "description": description[:140],
                          "target_url": self.run_url})
        if code != 201:
            raise SystemExit(f"::error::POST status on {sha} -> {code}: {data}")

    def comment(self, number: int, text: str) -> None:
        api("POST", f"/repos/{self.repo}/issues/{int(number)}/comments",
            {"body": f"{COMMENT_MARK}\n{text}\n\n{self.run_url}"})

    def merge(self, number: int, head_sha: str) -> str | None:
        """Squash-merge exactly the verified head; returns the merge commit sha once MERGED, else None."""
        if self.dry_run or self.shadow:
            log(f"  [{'dry-run' if self.dry_run else 'shadow'}] would squash-merge #{number} @ {head_sha[:12]}")
            return None
        # Auto-merge is armed on every member, so once the required `merge-train` status lands
        # GitHub may merge it before this call; a refusal here is therefore not an error.
        # --match-head-commit: a push between verification and merge makes GitHub refuse.
        self.gh("pr", "merge", str(int(number)), "--squash", "--match-head-commit", head_sha, check=False)
        for _ in range(36):
            pr = get(f"/repos/{self.repo}/pulls/{int(number)}")
            if pr.get("merged"):
                return pr["merge_commit_sha"] if pr.get("head", {}).get("sha") == head_sha else None
            time.sleep(5)
        return None

    def main_sha(self) -> str:
        return get(f"/repos/{self.repo}/git/ref/heads/{self.branch}")["object"]["sha"]

    def compare_files(self, base: str, head: str) -> list[str]:
        return [f["filename"] for f in paginate(f"/repos/{self.repo}/compare/{base}...{head}", "files")]

    def open_carrier(self, branch: str) -> int | None:
        assert_train_branch(branch)
        body = (f"{COMMENT_MARK}\nMerge-train carrier (ADR-0343). Each bisect step force-pushes the next train "
                f"to this branch; CI runs on every push. Closed automatically when the run ends. Never merge this PR.\n")
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as fh:
            fh.write(body)
        r = self.gh("pr", "create", "--draft", "--base", self.branch, "--head", branch,
                    "--title", f"merge-train carrier {branch}", "--body-file", fh.name, "--label", CARRIER_LABEL, check=False)
        os.unlink(fh.name)
        if r.returncode != 0:
            log(f"::warning::carrier PR not created: {r.stderr.strip()[-300:]}")
            return None
        m = re.search(r"/pull/(\d+)", r.stdout)
        return int(m.group(1)) if m else None

    def carrier_head(self, number: int) -> str:
        return get(f"/repos/{self.repo}/pulls/{int(number)}")["head"]["sha"]

    def close_carrier(self, number: int | None) -> None:
        if number is not None:
            self.gh("pr", "close", str(int(number)), check=False)


class GitHubVerifier:
    """Step B + the push half of step C, per train the bisect asks for."""

    def __init__(self, hub: Hub, git, pusher: Pusher, required: list[str], wait_minutes: int, poll_seconds: int):
        self.hub, self.git, self.pusher, self.required = hub, git, pusher, required
        self.wait_minutes, self.poll_seconds = wait_minutes, poll_seconds
        self.carrier: int | None = None

    def __call__(self, rec: TrainRecord) -> tuple[str, list[str]]:
        self.pusher.push(rec.sha)                      # this run's own commit, never input
        if self.carrier is None:
            self.carrier = self.hub.open_carrier(self.pusher.branch)
            if self.carrier is None:
                return "unknown", []
        deadline = time.time() + self.wait_minutes * 60
        while time.time() < deadline:
            ci_head = self.hub.carrier_head(self.carrier)
            if ci_head != rec.sha:
                # Someone else moved the branch, or GitHub has not caught up yet. Fetch and judge
                # by TREE: only a byte-identical tree is the train this run built.
                self.git("fetch", "-q", "origin", f"refs/heads/{self.pusher.branch}", check=False)
                problem = ci_tree_problem(self.git, ci_head, rec)
                if problem:
                    log(f"::error::train {rec.key}: {problem} — rejected")
                    return "unknown", []
            checks = head_checks(self.hub.repo, ci_head)
            failing = [c for c in self.required if checks.get(c) not in (None, *OK_STATES) and c in checks]
            pending = [c for c in self.required if c not in checks or checks[c] is None]
            if failing:
                return "red", failing
            if not pending:
                if ci_tree_problem(self.git, ci_head, rec):
                    return "unknown", []
                return "green", []
            time.sleep(self.poll_seconds)
        log(f"::warning::train {rec.key} did not finish CI within {self.wait_minutes} min — unverified")
        return "unknown", []

    def finish(self) -> None:
        self.hub.close_carrier(self.carrier)
        self.pusher.delete()


# --------------------------------------------------------------------------- modes
def rebuild(args: argparse.Namespace) -> int:
    """Step A. Holds no App token; GH_TOKEN here is the read-only GITHUB_TOKEN, and the generators
    never see it (scrubbed env). Everything recorded is computed here from live data."""
    repo, branch = args.repo, args.branch
    train_branch(args.run_id, args.attempt)       # fail early on a malformed run id
    state_dir = pathlib.Path(args.state)
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    required, strict = required_policy(repo, branch)
    member_required = [c for c in required if c != STATUS_CONTEXT]
    log(f"required={required} strict={strict}")
    workdir = args.workdir or os.getcwd()

    def git(*a: str, check: bool = True) -> subprocess.CompletedProcess:
        return sh(["git", *a], cwd=workdir, check=check, env=generator_env())

    git("fetch", "-q", "origin", f"+refs/heads/{branch}:refs/remotes/origin/{branch}")
    base = git("rev-parse", f"refs/remotes/origin/{branch}").stdout.strip()   # LIVE main; nothing else
    trusted_regen = git("show", f"{base}:{REGEN_INVENTORY.as_posix()}", check=False).stdout
    fragments = regen_inventory_fragments(trusted_regen)

    candidates: list[Member] = []
    for p in order_candidates(paginate(f"/repos/{repo}/pulls?state=open&base={branch}")):
        if TRAIN_BRANCH_RE.fullmatch(p["head"]["ref"]):
            continue
        view = pr_view(repo, p)
        armed = bool(view["auto_merge"]) and not view["draft"]
        view["files"] = pr_files(repo, p["number"]) if armed else []
        ok, why = qualifies(view, member_required, head_checks(repo, view["head_sha"]) if armed else {})
        log(f"#{p['number']:>6} {'CANDIDATE' if ok else 'skip     '} {why}")
        if ok:
            candidates.append(Member(view["number"], view["title"], view["body"], view["head_sha"],
                                     view["head_ref"], view["created_at"], files=view["files"]))
        if len(candidates) >= args.batch_size:
            break

    verified: list[Member] = []
    if candidates:
        git("fetch", "-q", "origin", *[f"+refs/pull/{m.number}/head:refs/merge-train/pr-{m.number}" for m in candidates])
    for m in candidates:
        fetched = git("rev-parse", f"refs/merge-train/pr-{m.number}").stdout.strip()
        if fetched != m.head_sha:
            log(f"#{m.number} skip: fetched head {fetched[:12]} != API head {m.head_sha[:12]} (pushed meanwhile)")
            continue
        m.ref = fetched                               # squash the SHA, never a movable ref
        m.files = git("diff", "--name-only", f"{base}...{fetched}").stdout.split()
        verified.append(m)

    fast: list[Member] = []
    train_members: list[Member] = []
    for m in verified:
        mb = git("merge-base", base, m.head_sha).stdout.strip()
        main_files = git("diff", "--name-only", f"{mb}..{base}").stdout.split()
        others = [f for o in verified if o is not m for f in o.files]
        ok, why = fast_lane_eligible(m.files, main_files, others, fragments)
        log(f"#{m.number} {'FAST LANE' if ok else 'train'}: {why}")
        (fast if ok else train_members).append(m)

    trains: dict[str, TrainRecord] = {}
    train_files: list[str] = []
    if train_members and not args.dry_run:
        wt = tempfile.mkdtemp(prefix="merge-train-wt-")
        git("worktree", "add", "-q", "--detach", wt, base)
        try:
            builder = TrainBuilder(wt)
            trains = prebuild(builder, base, train_members)
            whole = trains.get(train_key(train_members))
            train_files = builder.files_of(base, whole.sha) if whole else []
        finally:
            git("worktree", "remove", "--force", wt, check=False)
        for rec in trains.values():                   # keep the objects reachable for step C
            git("update-ref", f"refs/merge-train/train-{rec.key}", rec.sha)

    state = {"repo": repo, "branch": branch, "base": base, "run_id": args.run_id, "attempt": args.attempt,
             "built_at": dt.datetime.now(dt.timezone.utc).isoformat(),
             "fast_lane": [{"number": m.number, "head_sha": m.head_sha} for m in fast],
             "members": [{"number": m.number, "head_sha": m.head_sha, "files": m.files} for m in train_members],
             "trains": {k: r.to_json() for k, r in trains.items()}, "train_files": train_files}
    (state_dir / STATE_FILE).write_text(json.dumps(state, indent=1))
    has_work = bool(fast or train_members)
    write_output("has_work", "true" if has_work and not args.dry_run else "false")
    log(f"rebuild over {base[:12]}: fast-lane {[m.number for m in fast]}, train {[m.number for m in train_members]}, trains {len(trains)}")
    return 0


def publish(args: argparse.Namespace) -> int:
    """Steps B + C. Reads state written by step A of THIS job, then re-checks everything that can
    move (main, each head, armed/green) live before acting; refs and trees are re-derived locally."""
    if args.dry_run:
        log("::error::publish has no dry-run; run rebuild --dry-run instead")
        return 2
    state = json.loads(pathlib.Path(args.state, STATE_FILE).read_text())
    repo, branch, base = args.repo, args.branch, str(state.get("base", ""))
    branch_name = train_branch(args.run_id, args.attempt)
    if (state.get("run_id"), state.get("attempt")) != (args.run_id, args.attempt):
        log("::error::state was written by a different run — refusing")
        return 2
    hub = Hub(repo, branch, args.run_url, False, args.shadow)
    required, strict = required_policy(repo, branch)
    member_required = [c for c in required if c != STATUS_CONTEXT]
    log(f"required={required} strict={strict} mode={'shadow' if args.shadow else 'LIVE'} base {base[:12]}")
    if STATUS_CONTEXT not in required and not args.shadow:
        log(f"::error::{STATUS_CONTEXT} is not a required context yet — refusing to merge outside shadow mode (ADR-0343 phase 1 not reached)")
        return 2
    problem = base_current(hub.main_sha(), base)
    if problem:
        log(f"::warning::{problem}")
        write_summary([f"merge-train: {problem}"])
        return 0
    workdir = args.workdir or os.getcwd()

    def git(*a: str, check: bool = True, env: dict | None = None) -> subprocess.CompletedProcess:
        return sh(["git", *a], cwd=workdir, check=check, env=env)

    # Every head is re-read live; anything that moved, disarmed, closed or went red is out.
    live: dict[int, Member] = {}
    invalid: set[int] = set()
    for e in state["fast_lane"] + state["members"]:
        n = int(e["number"])
        p = get(f"/repos/{repo}/pulls/{n}")
        view = pr_view(repo, p)
        view["files"] = pr_files(repo, n)
        ok, why = qualifies(view, member_required, head_checks(repo, view["head_sha"]))
        if not ok or view["head_sha"] != e["head_sha"]:
            log(f"#{n} rejected live: {why if not ok else 'head moved since the rebuild'}")
            invalid.add(n)
            continue
        live[n] = Member(n, view["title"], view["body"], view["head_sha"], view["head_ref"], view["created_at"],
                         files=view["files"])

    fragments = regen_inventory_fragments(git("show", f"{base}:{REGEN_INVENTORY.as_posix()}", check=False).stdout)
    for e in state["fast_lane"]:
        m = live.get(int(e["number"]))
        if m:
            hub.post_status(m.head_sha, "success", f"fast lane: disjoint from main {base[:12]} and the batch")

    members = [live.get(int(e["number"])) or Member(int(e["number"]), "", "", e["head_sha"], "", "") for e in state["members"]]
    trains: dict[str, TrainRecord] = {}
    for k, r in state["trains"].items():
        rec = TrainRecord.from_json(r)
        # The commit must be the one step A left in THIS repository, with the tree step A recorded.
        local = git("rev-parse", "--verify", "--quiet", f"refs/merge-train/train-{rec.key}", check=False).stdout.strip()
        tree = git("rev-parse", "--verify", "--quiet", f"{local}^{{tree}}", check=False).stdout.strip() if local else ""
        if local != rec.sha or tree != rec.tree or git("merge-base", "--is-ancestor", base, rec.sha, check=False).returncode:
            log(f"::error::train {k}: local object does not match the rebuild record — dropped")
            continue
        trains[k] = rec
    out = Outcome()
    if members:
        pusher = Pusher(git, branch_name, f"https://github.com/{repo}.git", git_auth_env(os.environ["GH_TOKEN"]))
        train_required = [c for c in required if c != STATUS_CONTEXT and c not in TREE_INDEPENDENT_CONTEXTS]
        verifier = GitHubVerifier(hub, git, pusher, train_required, args.wait_minutes, args.poll_seconds)
        try:
            out = run_train(hub, base, members, trains, verifier, fragments, state.get("train_files", []), frozenset(invalid))
        finally:
            verifier.finish()
    line = (f"merge-train over {base[:12]} ({'shadow' if args.shadow else 'live'}): {out.trains} train(s), "
            f"fast-lane {len(state['fast_lane'])}, verified {[m.number for m in out.mergeable]}, "
            f"culprits {sorted(out.culprits)}, dropped {sorted(out.dropped)}, deferred {[m.number for m in out.deferred]}, "
            f"rejected live {sorted(invalid)}" + (", UNVERIFIED" if out.unverified else ""))
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
    ("checkout leaves no credential behind", "persist-credentials: false"),
    ("step A runs rebuild", "merge-train.py rebuild"),
    ("step C runs publish", "merge-train.py publish"),
    ("App token is minted", "actions/create-github-app-token@"),
    ("publish holds the App token", "GH_TOKEN: ${{ steps.app_token.outputs.token }}"),
    ("branch derives from the run id", "--run-id \"${{ github.run_id }}\""),
]
FORBIDDEN = [
    ("no build output is handed over as an artifact", "upload-artifact"),
    ("no build output is consumed from an artifact", "download-artifact"),
    ("generators never run from the workflow directly", "regen-derived.sh"),
]


def workflow_steps(text: str) -> list[str]:
    """The text of every step (`      - ` items under `steps:`), in order."""
    return [s for s in re.split(r"^      - ", text, flags=re.MULTILINE)[1:]]


def check_declaration() -> int:
    if not WORKFLOW.exists():
        print(f"::error::{WORKFLOW} missing")
        print("SUBJECTS=0")
        return 1
    text = WORKFLOW.read_text()
    fails = [label for label, needle in DECLARATION if needle not in text]
    # A mention in a comment is not an invocation; the forbidden list judges executable lines only.
    code = "\n".join(ln for ln in text.splitlines() if not ln.lstrip().startswith("#"))
    fails += [label for label, needle in FORBIDDEN if needle in code]
    if "contents: write" in text:
        fails.append("GITHUB_TOKEN must stay read-only (pushes use the App token)")
    steps = workflow_steps(text)

    def idx(needle: str) -> int:
        return next((i for i, s in enumerate(steps) if needle in s), -1)
    i_rebuild, i_mint, i_publish = idx("merge-train.py rebuild"), idx("create-github-app-token"), idx("merge-train.py publish")
    if not (0 <= i_rebuild < i_mint < i_publish):
        fails.append(f"step order must be rebuild < mint App token < publish (got {i_rebuild}, {i_mint}, {i_publish})")
    if i_rebuild >= 0 and ("app_token" in steps[i_rebuild] or "GITOPS_APP" in steps[i_rebuild]):
        fails.append("the rebuild step must not reference the App token or its secrets")
    if any("GITOPS_APP" in s for s in steps[:max(i_mint, 0)]):
        fails.append("no step before the mint may reference the App secrets")
    for f in fails:
        print(f"::error::merge-train.yml declaration: {f}")
    n = len(DECLARATION) + len(FORBIDDEN) + 4
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
               "files": ["openbank-fx-service/A.kt"], "state": "open"}
    check("qualifies: armed + green (merge-train itself not needed)", qualifies(base_pr, req, green)[0])
    check("qualifies: closed refused", not qualifies({**base_pr, "state": "closed"}, req, green)[0])
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

    # ---- review-3 negative 1: branch names come from the run, never from input -----------
    check("branch: train/<run_id>-<attempt> is derived and valid", train_branch("123", "1") == "train/123-1")
    for hostile in ("refs/heads/main", "../x", "main", "train/1-1/../main", "train/abc-1", "train/1-1.lock",
                    "train/1-1 ", "train/-1"):
        try:
            assert_train_branch(hostile)
            refused = False
        except ValueError:
            refused = True
        check(f"branch: {hostile!r} refused by assert_train_branch", refused)
    for rid, att in (("../x", "1"), ("1", "1/../main"), ("", "1")):
        try:
            train_branch(rid, att)
            refused = False
        except ValueError:
            refused = True
        check(f"branch: run id {rid!r}/{att!r} cannot produce a ref", refused)
    calls: list[tuple] = []

    def rec_git(*a, check=True, env=None):
        calls.append(a)
        return subprocess.CompletedProcess(a, 0, "", "")
    try:
        Pusher(rec_git, "refs/heads/main", "u", None)
        made = True
    except ValueError:
        made = False
    check("branch: a Pusher cannot even be built for refs/heads/main", not made and calls == [])
    p_ok = Pusher(rec_git, "train/7-1", "u", None)
    p_ok.push("a" * 40)
    p_ok.delete()
    check("branch: the only refs pushed/deleted are refs/heads/train/7-1",
          [c for c in calls if "push" in c] == [("push", "-q", "-f", "u", "a" * 40 + ":refs/heads/train/7-1"),
                                                ("push", "-q", "u", "--delete", "refs/heads/train/7-1")], str(calls))
    try:
        p_ok.push("HEAD")
        pushed_symbolic = True
    except ValueError:
        pushed_symbolic = False
    check("branch: a Pusher pushes full shas only", not pushed_symbolic)

    # ---- review-3 negative 2: stale base -------------------------------------------------
    check("base: equal to live main is current", base_current("a" * 40, "a" * 40) is None)
    check("base: an ancestor of live main (stale) is refused", (base_current("b" * 40, "a" * 40) or "").startswith("main moved"))
    check("base: a non-sha base is refused", base_current("a" * 40, "main") is not None)

    # ---- bisect, pure --------------------------------------------------------------------
    def mk(*ns):
        return [Member(n, f"pr {n}", "", f"sha{n}", f"b{n}", "t") for n in ns]

    def fake_trains(batch_members, dropped_text=None):
        out = {}
        for b in bisect_tree(batch_members):
            out[train_key(b)] = TrainRecord(train_key(b), "a" * 40, "b" * 40, [x.number for x in b], {}, len(b))
        return out

    def verify_bad3(rec):
        return ("red", ["Validate manifests"]) if 3 in rec.applied else ("green", [])

    def pure_run(ms, verify, trains=None, invalid=frozenset()):
        hub = FakeHub("a" * 40)
        o = run_train(hub, "a" * 40, ms, trains if trains is not None else fake_trains(ms), verify, [], [], invalid)
        return hub, o

    hub, out = pure_run(mk(1, 2, 3, 4), verify_bad3)
    check("bisect: culprit is #3 of four", list(out.culprits) == [3], str(out.culprits))
    check("bisect: mergeable prefix is [1,2]", [x.number for x in out.mergeable] == [1, 2], str(out.mergeable))
    check("bisect: #4 deferred (green alone, not verified over the prefix)", [x.number for x in out.deferred] == [4])
    check("bisect: five trains for a 4-batch with one culprit", out.trains == 5, str(out.trains))
    check("bisect: culprit comment is the fixed text", hub.comments == [(3, CULPRIT_MESSAGE)], str(hub.comments))
    hub, out = pure_run(mk(1, 2, 3, 4), lambda r: ("green", []))
    check("bisect: all-green batch is one train, all mergeable", out.trains == 1 and len(out.mergeable) == 4)
    hub, out = pure_run(mk(1), lambda r: ("unknown", []))
    check("bisect: unverified defers, no culprit", out.unverified and not out.culprits and len(out.deferred) == 1)
    check("bisect tree: 2N-1 batches, whole first", len(bisect_tree(mk(1, 2, 3, 4))) == 7 and bisect_tree(mk(1, 2, 3, 4))[0] == mk(1, 2, 3, 4))

    # ---- review-3 negative 3: a record is bound to its batch -----------------------------
    ms = mk(1, 2)
    good = TrainRecord("1-2", "a" * 40, "b" * 40, [1, 2], {}, 2)
    check("binding: own record for its batch is usable", bind_problem(ms, good) is None)
    outside = TrainRecord("1-2", "a" * 40, "b" * 40, [1, 2, 9], {}, 3)
    check("binding: applied containing a PR outside the batch is refused", "ordered subset" in (bind_problem(ms, outside) or ""), str(bind_problem(ms, outside)))
    check("binding: a record keyed for another batch is refused", bind_problem(ms, TrainRecord("1-2-9", "a" * 40, "b" * 40, [1, 2], {}, 2)) is not None)
    check("binding: reordered applied is refused", bind_problem(ms, TrainRecord("1-2", "a" * 40, "b" * 40, [2, 1], {}, 2)) is not None)
    check("binding: a member neither applied nor dropped is refused", bind_problem(ms, TrainRecord("1-2", "a" * 40, "b" * 40, [1], {}, 1)) is not None)
    hub, out = pure_run(ms, lambda r: ("green", []), trains={"1-2": outside, "1": good, "2": good})
    check("binding: run_train with an unbound record merges nothing", hub.merged == [] and out.unverified, f"{hub.merged} {out}")

    # ---- review-3 negative 4: comments carry fixed text only -----------------------------
    ms = mk(1, 2)
    trains = {"1-2": TrainRecord("1-2", "a" * 40, "b" * 40, [2], {1: "@everyone see https://evil"}, 1),
              "1": TrainRecord("1", "a" * 40, "b" * 40, [], {1: "@everyone"}, 0),
              "2": TrainRecord("2", "a" * 40, "b" * 40, [2], {}, 1)}
    hub, out = pure_run(ms, lambda r: ("green", []), trains=trains)
    check("comments: a dropped reason '@everyone' never reaches a comment",
          hub.comments == [(1, DROP_MESSAGE)] and not any("@everyone" in c[1] for c in hub.comments), str(hub.comments))
    check("comments: state serialisation carries dropped NUMBERS only",
          TrainRecord.from_json(trains["1-2"].to_json()).dropped == {1: "dropped in rebuild"} and
          "@everyone" not in json.dumps(trains["1-2"].to_json()))
    check("comments: the fixed texts contain no format placeholders", "{" not in DROP_MESSAGE + CULPRIT_MESSAGE)

    # ---- the trust boundary + synthetic repository (real git, real conflicts) -------------
    saved = {k: os.environ.get(k) for k in ("GH_TOKEN", "GITHUB_TOKEN", "ACTIONS_RUNTIME_TOKEN", "RUNNER_TEMP")}
    os.environ.update({"GH_TOKEN": "ghs_SELFTEST_MUST_NOT_LEAK", "GITHUB_TOKEN": "ghs_2", "ACTIONS_RUNTIME_TOKEN": "x",
                       "RUNNER_TEMP": "/runner/temp"})
    try:
        env = generator_env()
        check("generator env: no *TOKEN* key, no RUNNER_TEMP", not any("TOKEN" in k for k in env) and "RUNNER_TEMP" not in env,
              str(sorted(env)))

        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as scratch:
            info = _synthetic_repo(d)
            probe = os.path.join(scratch, "probe.json")
            builder = TrainBuilder(d, regen_commands=(f"{sys.executable} {SYN_GENERATOR}",), overlay_roots=(".github/scripts", "docs/adr"))
            builder.env["PROBE_OUT"] = probe

            def local_verify(rec: TrainRecord):
                builder.git("checkout", "-q", "-f", rec.sha)
                r = subprocess.run([sys.executable, "ci.py"], cwd=d, text=True, capture_output=True)
                return ("green", []) if r.returncode == 0 else ("red", [c for c in r.stdout.strip().split(",") if c])

            def members(*names):
                return [Member(i + 1, n, f"body of {n}", builder.git("rev-parse", n).stdout.strip(), n, f"2026-01-0{i + 1}",
                               ref=builder.git("rev-parse", n).stdout.strip())
                        for i, n in enumerate(names)]

            def train(hub, names, b=None):
                b = b or builder
                ms = members(*names)
                trains = prebuild(b, info["base"], ms)
                whole = trains[train_key(ms)]
                return run_train(hub, info["base"], ms, trains, local_verify, [], b.files_of(info["base"], whole.sha))

            # The finding of review 1: the generator runs while GH_TOKEN is set and must not see it.
            hub = FakeHub(info["base"])
            o = train(hub, ("pR",))
            rec = json.loads(pathlib.Path(probe).read_text())
            check("exfiltration probe: generator saw an EMPTY GH_TOKEN while the process had one",
                  rec["GH_TOKEN"] == "" and os.environ["GH_TOKEN"] == "ghs_SELFTEST_MUST_NOT_LEAK", str(rec)[:200])
            check("exfiltration probe: no *TOKEN* key reached the generator", not any("TOKEN" in k for k in rec["keys"]), str(rec["keys"]))
            check("regen: with regeneration pR merges", hub.merged == [1], f"{o}")

            # A member that rewrites the generator is dropped even when forced past qualifies().
            hub = FakeHub(info["base"])
            o = train(hub, ("p1", "pG"))
            check("generator tampering: pG dropped, p1 merged, comment is the fixed text",
                  2 in o.dropped and hub.merged == [1] and (2, DROP_MESSAGE) in hub.comments, f"{o} {hub.comments}")
            check("generator tampering: leaked.txt was never written", not pathlib.Path(d, "leaked.txt").exists())
            builder.git("checkout", "-q", "-f", "--detach", info["base"])
            pathlib.Path(d, SYN_GENERATOR).write_text("print('evil')\n")
            builder.git("commit", "-q", "-am", "tamper")
            try:
                builder.overlay_trusted_generators(info["base"])
                raised = False
            except RuntimeError:
                raised = True
            check("overlay: a modified generator is restored from base AND refused",
                  raised and pathlib.Path(d, SYN_GENERATOR).read_text() == SYN_GENERATOR_SRC)
            builder.git("checkout", "-q", "-f", "--detach", info["base"])
            pathlib.Path(d, ".github/scripts/new-evil.sh").write_text("echo evil\n")
            builder.git("add", "-A")
            builder.git("commit", "-q", "-m", "adds a generator")
            try:
                builder.overlay_trusted_generators(info["base"])
                raised = False
            except RuntimeError:
                raised = True
            check("overlay: a generator ADDED (absent from base) makes the rebuild refuse", raised)
            builder.git("checkout", "-q", "-f", "main")

            # Controls: each of the semantic pair is green ALONE (otherwise the next case proves nothing).
            for n in ("pA", "pB"):
                hub = FakeHub(info["base"])
                o = train(hub, (n,))
                check(f"synthetic control: {n} alone is green and merges", bool(o.mergeable and hub.merged), f"{o}")

            # Two PRs green alone, jointly invalid via count.txt -> the train MUST go red.
            hub = FakeHub(info["base"])
            o = train(hub, ("pA", "pB"))
            check("semantic conflict: train of [pA,pB] went red (trains > 1)", o.trains == 3, f"trains={o.trains}")
            check("semantic conflict: pA merges as the verified prefix, pB is deferred, no culprit",
                  [x.number for x in o.mergeable] == [1] and [x.number for x in o.deferred] == [2] and not o.culprits, f"{o}")
            check("semantic conflict: pB was NOT merged", hub.merged == [1], str(hub.merged))

            # Bisect over a real tree, #3 bad.
            hub = FakeHub(info["base"])
            o = train(hub, ("p1", "p2", "p3", "p4"))
            check("bisect (git): culprit is p3", list(o.culprits) == [3] and o.culprits[3] == ["Validate manifests"], f"{o}")
            check("bisect (git): p1,p2 merged, p4 deferred", hub.merged == [1, 2] and [x.number for x in o.deferred] == [4], f"{hub.merged} {o.deferred}")
            check("bisect (git): culprit got a failure status + fixed comment",
                  any(st[1] == "failure" for st in hub.statuses) and (3, CULPRIT_MESSAGE) in hub.comments)
            check("bisect (git): merged members got success statuses", sum(1 for st in hub.statuses if st[1] == "success") == 2)

            # Textual conflict -> dropped with the fixed comment, pA still merges.
            hub = FakeHub(info["base"])
            o = train(hub, ("pA", "pC"))
            check("conflict drop: pC dropped with the fixed comment, pA merged",
                  2 in o.dropped and hub.merged == [1] and hub.comments == [(2, DROP_MESSAGE)], f"{o} {hub.comments}")

            # Regeneration control: pR is red WITHOUT regen; with it, the tip is the regen commit.
            no_regen = TrainBuilder(d, regen_commands=(), overlay_roots=(".github/scripts",))
            hub = FakeHub(info["base"])
            o = train(hub, ("pR",), b=no_regen)
            check("regen control: without regeneration pR is a culprit", list(o.culprits) == [1], f"{o}")
            own = prebuild(builder, info["base"], members("pR"))["1"]
            check("regen: own train tip is the regeneration commit",
                  builder.git("log", "-1", "--format=%s", own.sha).stdout.strip() == REGEN_COMMIT_SUBJECT)
            check("squash commit carries PR-Number trailer", "PR-Number: #1" in builder.git("log", "-1", "--format=%B", f"{own.sha}~1").stdout)
            check("rebuild is deterministic in tree: same heads, same base -> same tree",
                  prebuild(builder, info["base"], members("pR"))["1"].tree == own.tree)

            # ---- review-3 negatives 5 + 6: CI head tree vs own rebuilt tree ---------------
            check("tree: CI ran on this run's own commit -> accepted", ci_tree_problem(builder.git, own.sha, own) is None)

            def ci_head_with(edit) -> str:
                builder.git("checkout", "-q", "-f", "--detach", f"{own.sha}~1")
                edit()
                builder.git("add", "-A")
                builder.git("commit", "-q", "-m", REGEN_COMMIT_SUBJECT)
                return builder.git("rev-parse", "HEAD").stdout.strip()

            def regen_plus_checksum_line():
                subprocess.run([sys.executable, SYN_GENERATOR], cwd=d, check=True, env=generator_env())
                path = pathlib.Path(d, "rollouts/a.yaml")
                path.write_text(path.read_text() + "  injected: true  # policy-checksum\n")
            ci5 = ci_head_with(regen_plus_checksum_line)
            p5 = ci_tree_problem(builder.git, ci5, own) or ""
            check("tree: a regen commit adding a non-checksum line in a gitops yaml is rejected", p5.startswith("CI ran on tree"), p5)

            def regen_plus_one_byte():
                subprocess.run([sys.executable, SYN_GENERATOR], cwd=d, check=True, env=generator_env())
                path = pathlib.Path(d, "derived/index.txt")
                path.write_text(path.read_text() + "x")
            ci6 = ci_head_with(regen_plus_one_byte)
            p6 = ci_tree_problem(builder.git, ci6, own) or ""
            check("tree: a CI tree differing by one byte from the rebuilt tree is rejected", p6.startswith("CI ran on tree"), p6)
            ci_same = ci_head_with(lambda: subprocess.run([sys.executable, SYN_GENERATOR], cwd=d, check=True, env=generator_env()))
            check("tree: a different commit with a byte-identical tree is accepted (tree, not sha, is the claim)",
                  ci_same != own.sha and ci_tree_problem(builder.git, ci_same, own) is None)
            check("tree: an unknown CI head is rejected", ci_tree_problem(builder.git, "f" * 40, own) is not None)
            builder.git("checkout", "-q", "-f", "main")

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
    ap.add_argument("mode", nargs="?", choices=["rebuild", "publish"])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-declaration", action="store_true")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--branch", default="main")
    ap.add_argument("--batch-size", type=int, default=int(os.environ.get("TRAIN_BATCH_SIZE", "8")))
    ap.add_argument("--state", default="", help="directory for state.json (step A writes, step C reads)")
    ap.add_argument("--run-id", default="", help="GITHUB_RUN_ID; the train branch derives from it")
    ap.add_argument("--attempt", default="", help="GITHUB_RUN_ATTEMPT")
    ap.add_argument("--wait-minutes", type=int, default=90)
    ap.add_argument("--poll-seconds", type=int, default=60)
    ap.add_argument("--workdir", default="")
    ap.add_argument("--run-url", default=os.environ.get("MERGE_TRAIN_RUN_URL", ""))
    ap.add_argument("--dry-run", action="store_true", help="rebuild: classify only, build nothing; publish refuses")
    ap.add_argument("--shadow", action="store_true", help="publish: post statuses and comments, never merge (phase 0)")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.check_declaration:
        return check_declaration()
    if not args.mode:
        ap.error("mode is required: rebuild | publish (or --self-test / --check-declaration)")
    if not args.repo or not args.state or not args.run_id or not args.attempt:
        ap.error("--repo, --state, --run-id and --attempt are required")
    if "GH_TOKEN" not in os.environ:
        ap.error("GH_TOKEN is required (read-only for rebuild, the App token for publish)")
    return rebuild(args) if args.mode == "rebuild" else publish(args)


if __name__ == "__main__":
    sys.exit(main())
