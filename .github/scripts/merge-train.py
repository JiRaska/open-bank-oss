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
    own derived-freshness gates, which CI runs on the exact train tree. Workflows and generators in
    that tree are main's, because a PR touching any of them never qualifies (under `.github/` only
    prose, gate-input data and allow-listed gate code ride a train — `Untrainable`, GATE_CODE_GLOBS),
    and a train carrying gate code never carries a regeneration commit, so its gates are main's too.

THREE JOBS, EACH ON ITS OWN RUNNER
----------------------------------
  build    no secrets. Lists candidates live, squashes the verified heads in order, overlays the
           generators from origin/main, regenerates in a scrubbed environment, and uploads ONE git
           bundle holding ONE train. Everything it produces is untrusted.
  publish  App token, fresh clone of origin/main, this script taken from origin/main and run with
           `python3 -I -S`, isolated git config. Admits build's declared batch (members.txt) only
           where each entry is a live train candidate at its live head, rebuilds every member
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
           A cancelled/stale context (or skipped next to a cancellation) is INCONCLUSIVE, never red:
           its jobs are re-run once (App `merge-train/rerun` marker), then the carrier is in flight;
           still inconclusive after that, it closes with no verdict. Only a real failure blames.
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
import contextlib
import datetime
import fnmatch
import inspect
import io
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
REGEN_INVENTORY = ".github/scripts/regen-derived.sh"

# The commit-status context this script owns. check-ruleset-context-parity.py reads this
# declaration, which is what lets the context be REQUIRED although no pull_request job emits it.
STATUS_CONTEXT = "merge-train"
RECORD_CONTEXT = "merge-train/record"          # publish's record of the tree it pushed
BOT_LOGIN = "openbank-gitops-bot[bot]"          # the only creator whose statuses/PRs are trusted
BOT_ID = 305_862_397                             # its numeric user id (carrier #13817); merge_train_carrier pins it too
EXCLUDED_BRANCH_PREFIXES = ("codex/",)
OWNER_APPROVAL_CONTEXT = "merge-train/owner-approval"
OWNER_APPROVAL_WORKFLOW = pathlib.Path(".github/workflows/merge-train-owner-approval.yml")
CARRIER_LABEL = "merge-train"
TRAIN_BRANCH_RE = re.compile(r"^train/[0-9]+-[0-9]+$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
BUNDLE_FILE = "train.bundle"
MANIFEST_FILE = "members.txt"   # build's declared batch: "<number> <head sha>" per line, in order
DROPS_FILE = "drops.txt"        # build's dropped members, same format: a HINT publish re-derives, never trusts
BUNDLE_REF = "refs/heads/merge-train-bundle"
# Contexts that judge the PR object (its body, its issue link), not the tree.
TREE_INDEPENDENT_CONTEXTS = frozenset({"issue-hygiene"})
GUARD_CONTEXT_HINT = "agent-pr-guard"
STALE_RED_CAP = 2      # stale-red refreshes per settle tick (ADR-0343)
STALE_RED_WALK = 20    # first-parent commits searched for main's own CI verdict
# A strict-lane reservation (ADR-0343) holds main for one PR's CI and is released when that CI STALLS,
# not when it is merely slow (#13400). A fixed 64 min bound (2 x the ci.yml p95) livelocked #13204: its
# Services CI (336 jobs, 168 run) took 67.8 / 70.4 / 87.1 min per head, so every reservation lapsed
# minutes before green, main moved, and the next update-branch restarted the clock. Progress = the
# newest started_at/completed_at of any check run on the head (GitHub's clock, not the author's).
# Stall window: 3 x the longest gap between consecutive job completions on those four heads (4.8 min,
# measured 2026-10-22 from /actions/runs/{id}/attempts/1/jobs). The ceiling bounds a CI that keeps
# trickling forever; it is measured from the head's committer time, which update-branch sets.
RESERVATION_STALL_S = 15 * 60
RESERVATION_CEILING_S = 4 * 3600
# Lane fairness (ADR-0343): after a strict-lane merge the train gets the next turn — no strict-lane
# update-branch while train candidates wait — for at most this long after that merge, so a train that
# cannot be built never freezes the strict lane. Two ticks of the */10 cron at its measured p90 gap.
# Measured 2026-10-21 over 12.5 h of merge-train.yml: 11 carriers published, 0 train merges, 13
# strict-lane merges — every strict merge was followed at once by the next strict update-branch.
TRAIN_TURN_S = 30 * 60
# Green carrier first (#13400): a VERIFIED green carrier merges before any strict-lane PR is served in
# the same tick — it is never held for, nor voided by, a strict merge. Measured 2026-10-24 08:00Z to
# 2026-10-25 11:50Z: 25 merges and 21 carriers; carrier #13853 went green at 10:29Z, was held for green
# strict #13806 which merged in that very tick, and was rebuilt as #13857 (~1 h of CI lost); #13790 was
# voided by #13679 the same way. Ten full-CI carriers ran 51-91 min (median ~61); strict merges arrive
# about one an hour, so letting strict pre-empt a RUNNING carrier as well would void nearly every one.
# A strict PR therefore waits for a running carrier, but only while that carrier is younger than this:
# 2 x the ~61 min median and above the 91 min longest measured run. Older, the carrier no longer holds
# the strict lane (it may still merge if it goes green first).
CARRIER_PRIORITY_S = 120 * 60
SQUASH_PR_RE = re.compile(r"(?:\(#|^#)(\d+)\)?$")
OK_STATES = frozenset({"success"})
# A cancelled (or stale) check ran nothing that could be judged — "Canceling since a higher priority
# waiting request exists" on carrier #13452 blamed #13137 for a job that executed no step. Such a
# context is INCONCLUSIVE: never red, never a bisect, never a culprit. A `skipped` context is
# inconclusive only next to a cancellation on the same head (a job skipped because its `needs`
# was cancelled); on its own it keeps today's meaning.
INCONCLUSIVE_STATES = frozenset({"cancelled", "stale"})
# Extra gates (ADR-0343): checks beyond the ruleset-required contexts that the train itself refuses to
# serve over when they are red on a PR head. ONE declaration, `merge_train.extra_gates` in rules.yaml,
# read from the live main being judged — never listed here. Absent on a head = PENDING (fail closed):
# not applicable only when the pinned workflow has COMPLETED for that head without producing the check.
RULES_FILE = "openbank-libs/governance/rules.yaml"
EXTRA_GATE_CONTEXT = "merge-train/extra-gate"     # App marker: the author was told once for this head
EXTRA_GATE_NA_STATES = frozenset({"skipped", "neutral"})
EXTRA_GATE_MESSAGE = ("merge-train: this PR is not served while a declared extra gate (`merge_train.extra_gates` in "
                      "rules.yaml, e.g. Security scan / Trivy) is red on its head. It stays armed: push a commit that "
                      "turns the scan green and the train picks it up on its own. Do not disable auto-merge for this.")
# Notices (#13400): a candidate build dropped because it conflicts with MAIN, or a strict-lane PR whose
# update-branch GitHub refused, was dropped/retried every tick in silence while GitHub showed BLOCKED,
# not DIRTY. The author is told once per head: a `merge-train` failure carrying one of NOTICE_PREFIXES
# (INFORMATION, not a verdict — qualifies ignores it, so no merge decision changes and a later train
# that applies the head overwrites it), one comment, and the NOTICE_CONTEXT marker. All per-sha: a
# push starts clean.
NOTICE_CONTEXT = "merge-train/notice"           # App marker: the author was told once for this head
CONFLICT_PREFIX = "conflicts with main "
REFUSED_PREFIX = "update-branch refused over main "
NOTICE_PREFIXES = (CONFLICT_PREFIX, REFUSED_PREFIX)
CONFLICT_COMMENT = ("merge-train: this PR conflicts with main, so every train leaves it out and GitHub can still "
                    "show it as BLOCKED rather than conflicting. Merge main into the branch (or rebase) and push; "
                    "the new head is picked up on its own. It stays armed.")
REFUSED_COMMENT = ("merge-train: GitHub refused to update this branch with main (update-branch), so the strict "
                   "lane cannot serve it. Merge main into the branch and push; the new head is picked up on its "
                   "own. It stays armed.")
STRICT_CONFLICT_COMMENT = ("merge-train: conflict — this strict-lane PR conflicts with main, so it cannot be "
                           "update-branched and holds no reservation: the strict queue moves on without it. Merge "
                           "main into the branch (or rebase), resolve the conflict and push; the new head is picked "
                           "up on its own. It stays armed.")
NOTICE_PATH_CAP = 20
RERUN_CONTEXT = "merge-train/rerun"              # App marker: this carrier's inconclusive jobs were re-run once
DEFER_CONTEXT = "merge-train/strict-deferral"   # App marker: this running strict head yielded once to a carrier
# Every culprit status starts with CULPRIT_PREFIX; a member carrying it waits for a push. A failure
# whose description starts with LEGACY_CULPRIT_PREFIX was written before the inconclusive rule and
# the carrier-attestation rule existed (#12506, #13137 were marked by those defects), so it is NOT
# final: the member re-qualifies and rides one more train, which overwrites the mark either way —
# success when it was an artifact, a CULPRIT_PREFIX failure when it was real. Self-retiring: no
# new status is ever written with the legacy text.
CULPRIT_PREFIX = "culprit: "
LEGACY_CULPRIT_PREFIX = "isolated as the cause of a red train"
# Fast-lane exclusions.
PROTECTED_PREFIXES = ("openbank-libs/", "build-logic/", "openbank-infra/gitops/", "docs/adr/", ".github/")
PROTECTED_GLOBS = ("*/src/main/resources/openapi.yaml",)
# A member touching any of these never rides a train: generators, privileged gate scripts, the
# regen inventory and the WORKFLOWS that run the freshness gates all live under `.github/`, and
# the regeneration-content argument rests on CI running main's copies of them. Everything ELSE the
# generator runner executes is derived from origin/main's regen-derived.sh by `Untrainable` —
# never from a fixed-depth glob, which a deeper file walks straight past.
# Under `.github/` the default is UNTRAINABLE; only prose and gate-input data (*.md, *.txt) ride a
# train, and not where such a file still decides what executes: workflows and actions (any file),
# pip requirement pins (they choose what gets installed) and agent prompts (they instruct an agent
# that holds tools). Every script and every extension-less file stay out, except the allow-listed
# gate code below (#13400), which never shares a train with a regeneration commit.
UNTRAINABLE_PREFIXES = (".github/workflows/", ".github/actions/", ".github/agent-prompts/")
GITHUB_DIR = ".github/"
GITHUB_TRAINABLE_SUFFIXES = (".md", ".txt")
GITHUB_UNTRAINABLE_NAME_GLOBS = ("*requirements*",)
# GATE CODE that rides a train (#13400) — an ALLOW-LIST, so an unknown `.github/` path stays strict.
# A check script is reviewed at arm time like any code, and the carrier's CI runs the
# COMBINED head: the gate is judged together with the code it judges exactly as in the strict lane,
# and bisect isolates a culprit. The one thing the strict lane gave that a train does not is that
# "regeneration content is proven by MAIN's gates" — so a train carrying gate code never carries a
# regeneration commit (TrainBuilder separates them; publish rejects the mix). Then the carrier tree
# is only reviewed member diffs, the strict lane's trust exactly.
# gates.yaml is NOT gate code here (#13837): it DECLARES how every gate runs (run, selftest, mode,
# when, budget_seconds), so one edit can retire a PRIVILEGED_GATE_CODE script without touching it.
# Telling a privileged entry's edit from an ordinary one needs a YAML parse, and settle/publish run
# under `python3 -I -S` (no site-packages, so no PyYAML; the stdlib has no YAML parser); a
# hand-rolled parse of a document with anchors and duplicate-key hazards is a grep in disguise.
# So every gates.yaml change takes the strict lane, judged by main's own gates.
GATE_CODE_GLOBS = (".github/scripts/check-*.py", ".github/scripts/check-*.sh")
# Check scripts that prove a DERIVED_OUTPUTS entry (ADR-0343 §Security): defence in depth on top of
# the separation above.
FRESHNESS_GATE_CODE = (".github/scripts/check-adr-registry.sh", ".github/scripts/check-eu-ai-act.sh")
# ...and gate code that stays strict because the train's own verdict depends on it: run by the pinned
# extra-gate workflow (security.yml), judging a CARRIER through merge_train_carrier, or judging the
# merge machinery itself (ruleset / merge-train context). The self-test re-derives this set from the
# real tree and fails when a new script of that kind is missing here.
PRIVILEGED_GATE_CODE = FRESHNESS_GATE_CODE + (
    ".github/scripts/check-codeql-compile-coverage.py",     # security.yml (extra gate's workflow)
    ".github/scripts/check-security-checklist.py",          # passes a carrier on its chain + App record
    ".github/scripts/check-api-contract.py",                # judges a carrier by its members
    ".github/scripts/check-coverage-floor-ratchet.py",      # judges a carrier by its members
    ".github/scripts/check-deploy-holds.py",                # reads the merge-train / ruleset contract
    ".github/scripts/check-ruleset-context-parity.py",      # reads the merge-train / ruleset contract
    ".github/scripts/check-ruleset-bypass-actors.py",       # judges the ruleset (who may bypass merge rules)
    ".github/scripts/check-branch-protection.py",           # judges branch protection on main
    ".github/scripts/check-agent-pr-guard.py",              # agent-pr-guard: the train reads it as a refusal
    ".github/scripts/check-agent-bounded-write-surface.py", # reads branch protection on main
    ".github/scripts/check-merged-past-red-check.py",       # reads the rulesets
)
# Files that SUPPRESS security findings (scanner allowlists and rule sets). They were trainable as
# plain data; a loosened one must take the owner's strict lane, wherever it lives.
SECURITY_SUPPRESSION_PATHS = (".gitleaksignore", ".gitleaks.toml", ".trivyignore", ".semgrep.yml", ".semgrep.yaml")
SECURITY_SUPPRESSION_PREFIXES = (".semgrep/",)
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


def unsafe_path(path: str) -> bool:
    """A path with a control character (newline, tab, ESC...) is refused outright. git QUOTES such
    paths in its default output, so every string comparison against them silently misses."""
    return any(ord(c) < 32 or ord(c) == 127 for c in path)


def zpaths(out: str) -> list[str]:
    """Split git `-z` output. Callers must pass `-z`: only NUL cannot occur inside a path."""
    return [p for p in out.split("\0") if p]


@dataclass
class Change:
    status: str
    old_mode: str
    new_mode: str
    paths: list[str]          # one path, or old AND new for a rename/copy entry


def changed_entries(git, *revs: str, cached: bool = False) -> list[Change]:
    """THE one way to ask git what changed. `--no-renames`: with rename detection on, a rename is
    reported under its NEW path only, so renaming a generator to `docs/x.md` would hide that the
    generator was deleted. `--raw -z`: NUL-separated, with modes (the symlink/gitlink check needs
    them). Every path of every entry is returned, and a rename/copy entry yields BOTH paths."""
    args = ["diff", "--no-renames", "--no-ext-diff", "--raw", "-z"] + (["--cached"] if cached else []) + list(revs)
    toks = git(*args).stdout.split("\0")
    out: list[Change] = []
    i = 0
    while i < len(toks):
        meta = toks[i]
        if not meta.startswith(":"):
            i += 1
            continue
        fields = meta[1:].split()
        status = fields[4] if len(fields) > 4 else "?"
        n = 2 if status[:1] in ("R", "C") else 1
        out.append(Change(status, fields[0], fields[1], toks[i + 1:i + 1 + n]))
        i += 1 + n
    return out


def changed_paths(git, *revs: str, cached: bool = False) -> list[str]:
    seen: list[str] = []
    for c in changed_entries(git, *revs, cached=cached):
        for path in c.paths:
            if path not in seen:
                seen.append(path)
    return seen


class Untrainable:
    """Every path the generator runner would EXECUTE, derived from origin/main's regen-derived.sh:
    its `find <root> -name '<pattern>'` enumeration (any depth, as `find` recurses), every program
    its GENERATORS inventory and REGEN_COMMANDS invoke, the libraries gen-index.sh sources, and
    everything under `.github/` except prose and gate-input data (see GITHUB_TRAINABLE_SUFFIXES). If main's script cannot be parsed, it fails CLOSED: any `gen-*` file anywhere."""

    def __init__(self, regen_text: str):
        m = re.search(r"find\s+(\S+)\s+-name\s+'([^']+)'", regen_text or "")
        self.find_root = m.group(1).rstrip("/") if m else None
        self.find_name = m.group(2) if m else None
        commands = [c for c in generator_commands(regen_text or "") if c != "__BUNDLES__"] + list(REGEN_COMMANDS)
        self.programs = {tok for c in commands for tok in shlex.split(c) if "/" in tok and tok.endswith((".py", ".sh"))}

    def __call__(self, path: str) -> bool:
        if any(path.startswith(p) for p in UNTRAINABLE_PREFIXES) or path in self.programs:
            return True
        if path in SECURITY_SUPPRESSION_PATHS or path.startswith(SECURITY_SUPPRESSION_PREFIXES):
            return True
        if glob_match(SOURCED_BY_GEN_INDEX, path):
            return True
        name = path.rsplit("/", 1)[-1]
        if path.startswith(GITHUB_DIR):
            if gate_code(path):
                return False
            return (not name.endswith(GITHUB_TRAINABLE_SUFFIXES)
                    or any(fnmatch.fnmatch(name, g) for g in GITHUB_UNTRAINABLE_NAME_GLOBS))
        if self.find_root is None:
            return name.startswith("gen-")
        return path.startswith(self.find_root + "/") and fnmatch.fnmatch(name, self.find_name)


def gate_code(path: str) -> bool:
    """Reviewed gate code that may ride a train (GATE_CODE_GLOBS), never a PRIVILEGED_GATE_CODE path."""
    return path not in PRIVILEGED_GATE_CODE and any(glob_match(g, path) for g in GATE_CODE_GLOBS)


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


def parse_extra_gates(text: str) -> dict[str, str] | None:
    """`merge_train.extra_gates` from rules.yaml text: check name -> the workflow file that must produce
    it, from items `- {workflow: <path>, check: <name>}`. Runs under `python3 -I -S` (no PyYAML), so it
    reads exactly that one shape; None = key absent, empty or ANY item malformed (callers fail closed)."""
    lines = text.splitlines()
    try:
        i = next(k for k, ln in enumerate(lines) if re.fullmatch(r"merge_train:\s*(#.*)?", ln))
    except StopIteration:
        return None
    out: dict[str, str] | None = None
    for ln in lines[i + 1:]:
        if not ln.strip() or ln.lstrip().startswith("#"):
            continue
        if not ln.startswith(" "):
            break
        if out is None:
            if re.fullmatch(r"  extra_gates:\s*(#.*)?", ln):
                out = {}
            continue
        if not ln.startswith("    "):
            break
        m = re.fullmatch(r"    - \{\s*workflow:\s*(\.github/workflows/[A-Za-z0-9._-]+\.ya?ml)\s*,"
                         r"\s*check:\s*\"?([^\"{},]+?)\"?\s*\}\s*(#.*)?", ln)
        if not m:
            return None
        out[m.group(2)] = m.group(1)
    return out or None


def train_extra_gates(git, base: str) -> dict[str, str] | None:
    """TRAIN_EXTRA_GATES (check name -> producing workflow) as declared on `base` (live main) — the single
    source every lane reads. None = missing or unparseable: the caller serves NOTHING (fail closed)."""
    gates = parse_extra_gates(git("show", f"{base}:{RULES_FILE}", check=False).stdout)
    if gates is None:
        log(f"::error::{RULES_FILE} on {base[:12]}: merge_train.extra_gates missing or unparseable — "
            "the train serves no PR until it is fixed")
    return gates


def pinned_checks(gh, sha: str, gates: dict[str, str]) -> dict[str, str | None]:
    """gh.checks(sha) with every extra gate replaced by its PINNED verdict (gh.extra_checks): only runs
    from the declared workflow count, and any failing one of them is red — a same-name run from any
    other app or workflow can neither turn it green nor mask a red."""
    checks = {k: v for k, v in gh.checks(sha).items() if k not in gates}
    checks.update(gh.extra_checks(sha, gates))
    return checks


def extra_gate_red(checks: dict[str, str | None], extra) -> list[str]:
    """Declared extra gates that are red on this head. Skipped / neutral = not applicable; absent is not
    red here, but `qualifies` and `carrier_contexts` hold it as pending (fail closed)."""
    return [g for g in extra or () if g in checks and checks[g] is not None
            and checks[g] not in OK_STATES and checks[g] not in EXTRA_GATE_NA_STATES]


def extra_verdict(states: list[str | None]) -> str | None:
    """One pinned extra gate from all its runs on a head: any red wins, then running, then green/n.a."""
    red = [s for s in states if s is not None and s not in OK_STATES and s not in EXTRA_GATE_NA_STATES]
    if red:
        return "failure" if "failure" in red else red[0]
    if any(s is None for s in states):
        return None
    return "success" if any(s in OK_STATES for s in states) else states[0]


def owner_approval_valid(number: int, ref: str, app: dict | None) -> bool:
    """Only the latest App status on this exact head can admit an owner-approved codex PR.

    app_statuses() authenticates the producer and SHA. The canonical description additionally
    binds the PR number, so two PRs sharing a head cannot borrow each other's approval.
    """
    if not any(ref.startswith(pre) for pre in EXCLUDED_BRANCH_PREFIXES):
        return True
    return (type(number) is int and number > 0
            and (app or {}).get(OWNER_APPROVAL_CONTEXT) == ("success", f"owner-approved PR #{number}"))


def live_source_intent(gh, number: int, head: str) -> bool:
    """Re-read a SOURCE PR immediately before acting, for every branch prefix.

    A planner's armed snapshot expires while it judges other PRs or verifies a bundle. Internal
    carriers are deliberately draft/disarmed and are never passed here. This is a last read, not
    an atomic lock with GitHub's subsequent write; the merge API still pins the exact head.
    """
    with deciding():
        pr = gh.pr(number)
        if not isinstance(pr, dict):
            return False
        source = pr.get("head")
        if (pr.get("number") != number or pr.get("state") != "open" or pr.get("draft") is not False
                or not isinstance(pr.get("auto_merge"), dict) or not pr["auto_merge"]
                or not isinstance(source, dict) or source.get("sha") != head):
            return False
        target = pr.get("base")
        if not isinstance(target, dict) or target.get("ref") != gh.branch:
            return False
        for endpoint in (source, target):
            repo = endpoint.get("repo")
            if not isinstance(repo, dict) or repo.get("full_name") != gh.repo:
                return False
        ref = source.get("ref")
        if not isinstance(ref, str) or not ref or TRAIN_BRANCH_RE.fullmatch(ref):
            return False
        app = gh.app_statuses(head) if any(ref.startswith(pre) for pre in EXCLUDED_BRANCH_PREFIXES) else None
        return owner_approval_valid(number, ref, app)


def qualifies(pr: dict, required: list[str], checks: dict[str, str | None],
              app: dict[str, tuple[str, str]] | None = None, untrainable=None,
              extra: list[str] | None = None) -> tuple[bool, str]:
    """`checks`: context -> conclusion (None = running). `app`: the App's own latest statuses.
    `pr["files"]` must come from `git diff` against the verified head, never from the files API.
    `extra`: TRAIN_EXTRA_GATES — red refuses, running or never reported waits (fail closed), and only
    skipped/neutral (which GitHub.extra_checks also reports when every pinned run SUCCEEDED without it)
    is n/a — and not even that when the PR's own diff touches the gate's pinned workflow (red)."""
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
    if not owner_approval_valid(pr.get("number"), ref, app):
        return False, "codex PR needs owner approval on this exact head"
    for f in pr.get("files") or []:
        if unsafe_path(f):
            return False, "a path contains a control character — manual lane, never a train"
        if untrainable(f):
            return False, f"touches {f} (workflows, gates, generators, inventory) — manual lane, never a train"
    if pr.get("special_paths") and any(gate_code(f) for f in pr.get("files") or []):
        # The strict lane refuses a symlink/gitlink outright; on the train publish would halve the
        # whole carrier for it. Gate code next to one never rides (review of #13750).
        return False, "gate code next to a symlink or gitlink — never a train"
    app_state, app_desc = (app or {}).get(STATUS_CONTEXT, ("", ""))
    if app_state == "failure" and not app_desc.startswith((LEGACY_CULPRIT_PREFIX, *NOTICE_PREFIXES)):
        return False, "merge-train failure on this head (culprit or manual lane) — waits for a push"
    if not required:
        return False, "no required contexts resolved — refusing to judge green"
    for name, state in checks.items():
        if GUARD_CONTEXT_HINT in name and state not in OK_STATES and state is not None:
            return False, f"{name} refused ({state})"
    member = [c for c in required if c != STATUS_CONTEXT]
    for c in member:
        if c in checks and checks[c] is not None and checks[c] not in OK_STATES:
            return False, f"required context {checks[c]}: {c}"
    for g in extra_gate_red(checks, extra):
        return False, f"extra gate red: {g}" + ("" if checks[g] == "failure" else f" ({checks[g]})")
    for g in own_workflow_red({c: v for c, v in checks.items() if c in (extra or {})}, extra, pr.get("files")):
        if g in checks:   # absent stays "never reported" (pending) below; a settled n/a is red
            return False, f"extra gate red: {g} (the PR changes the extra gate's own workflow)"
    for c in member:
        if c not in checks:
            return False, f"required context never reported: {c}"
        if checks[c] is None:
            return False, f"required context pending: {c}"
    for g in extra or ():
        if g not in checks:
            return False, f"extra gate never reported: {g}"
        if checks[g] is None:
            return False, f"extra gate pending: {g}"
    return True, "armed + all required green"


def strict_lane_state(pr: dict, required: list[str], checks: dict[str, str | None],
                      app: dict[str, tuple[str, str]] | None, untrainable, modes: list[str],
                      main_red: dict[str, str] | None = None, extra: list[str] | None = None) -> tuple[str | None, str]:
    """The strict lane (ADR-0343): a PR the train refuses ONLY because it is manual-lane (an executable
    `.github/` path, a generator, gates.yaml, the inventory) is judged exactly as strict up-to-date used
    to judge every PR. Returns ("green" | "pending" | None, why). Every other refusal in `qualifies` —
    closed, draft, disarmed, fork, excluded prefix, a merge-train failure, agent-pr-guard, red — still
    applies, and a control-character path or a symlink/gitlink never enters this lane at all.
    "waiting": every required context it has failed is red on main's tip too (`main_red`, context ->
    the main commit that verdict came from) — it cannot go green until main is fixed, so it must
    neither hold the lane nor be served."""
    files = pr.get("files") or []
    if any(unsafe_path(f) for f in files):
        return None, "a path contains a control character — never the strict lane"
    if any(m in SPECIAL_MODES for m in modes):
        return None, "adds a symlink or gitlink — never the strict lane"
    untrainable = untrainable or FAIL_CLOSED_UNTRAINABLE
    if not any(untrainable(f) for f in files):
        return None, "not manual-lane"
    ok, why = qualifies(pr, required, checks, app, untrainable=lambda _f: False, extra=extra)
    if ok:
        return "green", why
    member = [c for c in required if c != STATUS_CONTEXT]
    failed = [c for c in member if checks.get(c) is not None and checks.get(c) not in OK_STATES]
    if main_red and failed and why.startswith("required context") and set(failed) <= set(main_red):
        return "waiting", main_red_reason(failed, main_red)
    red_extra = extra_gate_red(checks, extra)
    if main_red and red_extra and why.startswith("extra gate red") and set(red_extra) <= set(main_red):
        return "waiting", main_red_reason(red_extra, main_red)
    # "never reported" is the normal state for the first minutes after update-branch (no workflow has
    # registered its check yet): it is pending, or the fresh head holds no reservation exactly when
    # it needs one (#13485).
    if why.startswith(("required context pending", "required context never reported", "extra gate pending",
                           "extra gate never reported")) and not any(
            checks.get(c) is not None and checks.get(c) not in OK_STATES for c in member if c in checks):
        return "pending", why
    return None, why


def own_workflow_red(checks: dict[str, str | None], extra, files: list[str] | None) -> list[str]:
    """Declared extra gates that are absent or not applicable on a head whose diff touches the gate's
    OWN pinned workflow (#13400): a PR that removes or renames the job must not thereby skip it, so the
    n/a is red here — even when every pinned run succeeded. A present green gate is unaffected."""
    touched = set(files or ())
    return [g for g, wf in (extra or {}).items()
            if wf in touched and (g not in checks or checks[g] in EXTRA_GATE_NA_STATES)]


def own_workflow_checks(checks: dict[str, str | None], extra, files: list[str] | None) -> dict[str, str | None]:
    """Carrier form of own_workflow_red over the union of member diffs: the n/a becomes "failure".
    `files` None = the carrier diff could not be read: an n/a gate is held pending (fail closed)."""
    if files is None:
        return {**checks, **{g: None for g in extra or {} if checks.get(g) in EXTRA_GATE_NA_STATES}}
    return {**checks, **{g: "failure" for g in own_workflow_red(checks, extra, files)}}


def carrier_contexts(required: list[str], extra, checks: dict[str, str | None]) -> list[str]:
    """What a carrier is judged on: the train's required contexts plus every declared extra gate."""
    # Absent counts (classify_carrier reads it as pending): only an explicit skipped/neutral is n/a.
    return required + [g for g in extra if checks.get(g) not in EXTRA_GATE_NA_STATES]


def classify_carrier(checks: dict[str, str | None], required: list[str]) -> tuple[list[str], list[str], list[str]]:
    """(failing, pending, inconclusive) over `required`. Only `failing` may ever blame a member."""
    cancelled_here = any(v in INCONCLUSIVE_STATES for v in checks.values())
    inconclusive = [c for c in required if checks.get(c) in INCONCLUSIVE_STATES
                    or (checks.get(c) == "skipped" and cancelled_here)]
    pending = [c for c in required if checks.get(c) is None]
    failing = [c for c in required if checks.get(c) not in (None, *OK_STATES) and c not in inconclusive]
    return failing, pending, inconclusive


# A gate that PASSED on content but overran its wall-time budget, or could not read its subject (NOT
# EVALUATED — a timed-out API, an unreadable carrier member), says nothing about the members (#13400:
# carrier #13501 blamed #13137 for two gates that printed "every member ... passes" and "none lowered").
# Read from run-gates.py's --json `failure_kind`, the gate runner's own machine-readable verdict.
INCONCLUSIVE_GATE_KINDS = frozenset({"budget", "not_evaluated"})
GATE_SHARD_JOB_RE = re.compile(r"^gates \(([a-z0-9-]+)\)$")   # ci.yml `gates (${{ matrix.slug }})`
GATE_AGGREGATE_JOB = "Validate manifests"                      # red exactly when a shard is red
GATE_ARTIFACT_PREFIX = "gate-results-"                         # ci.yml uploads gate-results-<slug>-a<attempt>


def gate_failures_inconclusive(failed_jobs: list[str], records: dict[str, list | None]) -> tuple[bool, str]:
    """(True, why) only when EVERY failed job of the carrier's CI run is a gate shard whose every non-ok
    gate failed for an inconclusive kind (or the aggregate that mirrors the shards). `records` maps shard
    slug -> that shard's --json records (None = unreadable). Anything unknown is conclusive: a missing
    artifact, an old runner without `failure_kind`, a failed non-gate job, an unfalsified gate."""
    shards = []
    for job in failed_jobs:
        m = GATE_SHARD_JOB_RE.fullmatch(job)
        if m:
            shards.append(m.group(1))
        elif job != GATE_AGGREGATE_JOB:
            return False, f"job `{job}` failed"
    if not shards:
        return False, "no gate shard failed"
    kinds = []
    for slug in shards:
        recs = records.get(slug)
        if not isinstance(recs, list):
            return False, f"gate results of `{slug}` unreadable"
        bad = [r for r in recs if isinstance(r, dict) and r.get("status") in ("failed", "unfalsified")]
        if not bad:
            return False, f"shard `{slug}` failed with no failed gate recorded"
        for r in bad:
            if r.get("failure_kind") not in INCONCLUSIVE_GATE_KINDS:
                return False, f"gate `{r.get('id')}` failed ({r.get('failure_kind') or 'unknown kind'})"
            kinds.append(f"{r.get('id')}: {str(r.get('failure_kind')).replace('_', ' ')}")
    return True, "; ".join(kinds)


def culprit_description(failing: list[str], base: str) -> str:
    return f"{CULPRIT_PREFIX}{', '.join(failing)} red on a train over main {base[:12]}"[:140]


def main_red_reason(ctxs, main_red: dict[str, str]) -> str:
    ctxs = sorted(ctxs)
    return f"waiting for main ({', '.join(ctxs)} red on main {main_red[ctxs[0]][:12]})"


def main_red_contexts(gh, git, required: list[str], base: str, cache: dict | None = None) -> dict[str, str]:
    """Required contexts (merge-train aside) that are FAILURE in main's latest completed CI at `base`:
    context -> the main commit carrying that verdict. A still-running main run reads as its nearest
    first-parent ancestor's verdict, so a fix that just merged keeps main red until its own push run reports."""
    # checks(sha) already returns every context. Share each commit's complete result within this
    # evaluation, including when build/publish omit cache; otherwise the history is fetched again
    # for every context. Never retain this implicit cache across evaluations: CI results can change.
    if cache is None:
        cache = {}
    out: dict[str, str] = {}
    # Extra gates count too: a scan red on main itself makes PRs wait and never makes anyone a culprit.
    for ctx in sorted({c for c in required if c != STATUS_CONTEXT} | set(train_extra_gates(git, base) or {})):
        res = main_ci_result(gh, git, ctx, base, cache)
        if res and res[1] == "failure":
            out[ctx] = res[0]
    return out


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


def batch_cap(applied: list[Member], sizes: dict[int, int | None], default: int) -> int:
    """How many members a train may APPLY: the smallest `bisect size N` among the members that
    actually APPLY, else the default. Two earlier rules failed against the live queue:
    - read off the OLDEST candidate only (#13794): measured 2026-10-25 (#13801..#13817), the oldest
      candidate #13697 was dropped by every build (its squash is a no-op on main), so it never rode,
      never got a status, and the cap stayed 8 while the five halved members carried `bisect size 2`
      through six carriers; a newly armed older PR (#13710) defeated it the same way;
    - the smallest size over the candidate WINDOW: a member that carries `bisect size 1` and is then
      dropped by every build would pin the whole train at one forever (review of #13840).
    A dropped candidate's size therefore never counts — build applies one member at a time and stops
    before the member that would exceed the cap of those applied so far (TrainBuilder._build)."""
    got = [sizes[m.number] for m in applied if sizes.get(m.number)]
    return max(1, min([default, *got]))


def select_batch(train_cands: list[Member], sizes: dict[int, int | None], default: int) -> list[Member]:
    """Oldest first: the default window. The cap (batch_cap) bounds the members build APPLIES, not the
    candidates it tries — a candidate build drops (no-op, conflict, untrainable) must not consume a slot,
    or a permanently dropped oldest PR at cap 1 would starve the train."""
    return train_cands[:max(1, default)] if train_cands else []


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
                "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_TERMINAL_PROMPT": "0",
                # Never put the script's directory or the cwd on sys.path: main's bundle scripts run
                # `python3 -` from their own directory, where a PR-added re.py would shadow `re`.
                "PYTHONSAFEPATH": "1"})
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
        self.overlaid_gate: list[str] = []
        # Path fragments main's inventory declares as generator INPUTS (set from origin/main by
        # mode_build). Only added files under these stay visible while the generators run.
        self.declared_inputs: list[str] = []
        self.keep_declared_inputs = True
        # #13400: gate code and a regeneration commit never share a train; members held back for that
        # are DEFERRED (no drop notice; the next tick takes them), never dropped.
        self.separate_gate_code = True
        self.deferred: list[int] = []
        self.sizes: dict[int, int | None] = {}   # live `bisect size N` per candidate (batch_cap)
        self.default_size: int | None = None      # batch size; None = no cap (direct builds in tests)
        self.attempted: list[int] = []       # members _build tried (applied or dropped), in order

    def git(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        return sh(["git", "-c", "core.hooksPath=/dev/null", *args], cwd=self.workdir, check=check, env=self.env)

    def build(self, base: str, members: list[Member]) -> tuple[str, list[int], list[int]]:
        """(tip, applied, dropped); self.deferred lists members held back for the next train."""
        self.deferred = []
        tip, applied, dropped, regen, gate = self._build(base, members)
        if not (self.separate_gate_code and regen and gate):
            return tip, applied, dropped
        # Keep the class of the OLDEST applied member, so neither class starves behind the other.
        keep_gate = applied[0] in gate
        keep = [m for m in members if (m.number in gate) == keep_gate]
        log(f"build: gate code {sorted(gate)} and a regeneration commit never share a train — "
            f"keeping the {'gate-code' if keep_gate else 'other'} member(s)")
        tip, applied2, dropped2, regen2, gate2 = self._build(base, keep)
        if regen2 and gate2:
            # Main itself needs regeneration: gate code cannot ride until it does not.
            keep = [m for m in members if m.number not in gate]
            tip, applied2, dropped2, regen2, gate2 = self._build(base, keep) if keep else (base, [], [], False, [])
        kept = {m.number for m in keep}
        self.deferred = [m.number for m in members if m.number not in kept]
        log(f"build: deferred {self.deferred} to the next train")
        return tip, applied2, dropped2

    def _build(self, base: str, members: list[Member]) -> tuple[str, list[int], list[int], bool, list[int]]:
        self.git("checkout", "-q", "--detach", "-f", base)
        self.git("clean", "-qfdx")
        applied: list[int] = []
        dropped: list[int] = []
        gate: list[int] = []
        self.attempted = []
        by_number = {m.number: m for m in members}
        for m in members:
            r = self.git("merge", "--squash", "--no-commit", m.ref, check=False)
            staged = changed_paths(self.git, cached=True) if r.returncode == 0 else []
            if r.returncode != 0 or not staged or any(unsafe_path(f) or self.untrainable(f) for f in staged):
                self.git("merge", "--abort", check=False)
                self.git("reset", "-q", "--hard", "HEAD")
                self.attempted.append(m.number)
                dropped.append(m.number)
                continue
            if self.default_size is not None:
                cap = batch_cap([by_number[n] for n in applied] + [m], self.sizes, self.default_size)
                if len(applied) + 1 > cap:
                    # The bisect cap counts APPLIED members only; this one and the rest are not
                    # tried this train (not dropped, no notice) and lead the next one.
                    self.git("reset", "-q", "--hard", "HEAD")
                    break
            self.attempted.append(m.number)
            self.git("commit", "-q", "-m", squash_message(m))
            applied.append(m.number)
            if any(gate_code(f) for f in staged):
                gate.append(m.number)
        regen = False
        if applied:
            try:
                self.overlay_trusted_generators(base)
            except RuntimeError as exc:
                # A member changed what the runner would execute: nobody rides this train.
                log(f"::warning::train refused before any generator ran: {exc}")
                self.git("checkout", "-q", "--detach", "-f", base)
                return base, [], dropped + applied, False, []
            self.quarantine_new_executables(base)
            try:
                regen = self.regenerate()
            finally:
                self.restore_quarantined()
                self.restore_overlaid_gate()
        return self.git("rev-parse", "HEAD").stdout.strip(), applied, dropped, regen, gate

    def quarantine_new_executables(self, base: str) -> None:
        """Before any generator runs: remove from the working tree every file that is not on
        origin/main — except the files main's inventory declares as generator INPUTS (a new ADR, a
        new version.txt), which the generators must see or the train is wrong. A kept file is
        never untrainable, and PYTHONSAFEPATH keeps it off sys.path, so it cannot be imported in
        place of a module. A generator or a module added by a PR therefore never runs."""
        in_base = set(zpaths(self.git("ls-tree", "-r", "-z", "--name-only", base).stdout))
        on_disk = zpaths(self.git("ls-files", "-z", "--cached", "--others", "--exclude-standard").stdout)

        def keep(f: str) -> bool:
            return (self.keep_declared_inputs and not unsafe_path(f) and not self.untrainable(f)
                    and any(frag in f for frag in self.declared_inputs))
        self.quarantined = [f for f in on_disk if f not in in_base and not keep(f)]
        for f in self.quarantined:
            os.unlink(os.path.join(self.workdir, f))
        if self.quarantined:
            log(f"quarantined {len(self.quarantined)} file(s) not on main while the generators run")

    def restore_overlaid_gate(self) -> None:
        """Put the member's gate code back after the generators ran over main's copy."""
        gate, self.overlaid_gate = self.overlaid_gate, []
        for e in gate:
            if e.startswith("??"):
                os.unlink(os.path.join(self.workdir, e[3:]))
        tracked = [e[3:] for e in gate if not e.startswith("??")]
        if tracked:
            self.git("checkout", "HEAD", "--", *tracked)

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
        entries = [e for e in zpaths(self.git("status", "--porcelain", "--no-renames", "--untracked-files=all", "-z").stdout)
                   if len(e) > 3]
        # The overlay exists to put main's GENERATORS back. A trainable data file under an overlay
        # root (a baseline, an ADR) is the member's content and must stay so — otherwise the regen
        # commit carries main's copy as a revert and publish rejects it as a non-derived path.
        # Gate code is NOT restored: the generators see main's copy, as they see main's generators;
        # regenerate() puts the member's copy back before anything is committed (review of #13750).
        self.overlaid_gate = [e for e in entries if gate_code(e[3:])]
        data = [e for e in entries if not self.untrainable(e[3:]) and not gate_code(e[3:])]
        modified = [e[3:] for e in data if not e.startswith("??")]
        if modified:
            self.git("checkout", "HEAD", "--", *modified)
        for e in data:
            if e.startswith("??"):
                os.unlink(os.path.join(self.workdir, e[3:]))
        dirty = [e[3:] for e in entries if self.untrainable(e[3:])]
        if dirty:
            raise RuntimeError(f"generator paths differ from the trusted base after overlay: {dirty[:5]}")

    def regenerate(self) -> bool:
        for cmd in self.regen_commands:
            r = subprocess.run(shlex.split(cmd), cwd=self.workdir, text=True, capture_output=True, env=self.env)
            if r.returncode != 0:
                log(f"::warning::regen command failed ({cmd}): {r.stderr.strip()[-300:]}")
        self.restore_quarantined()
        self.restore_overlaid_gate()
        changed = zpaths(self.git("ls-files", "-z", "-m", "-o", "--exclude-standard").stdout)
        if any(unsafe_path(f) for f in changed):
            raise RuntimeError("a generator wrote a path with a control character — refusing the train")
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
    gate: list[int] = []
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
        if any(gate_code(f) for f in changed_paths(git, own_tree, tree)):
            gate.append(m.number)
        own = git("commit-tree", tree, "-p", own, "-m", own_member_message(m.number, m.head_sha)).stdout.strip()
        own_tree = tree
        applied.append(m.number)
        idx += 1
    rest = chain[idx:]
    if len(rest) > 1:
        raise Reject(f"bundle carries {len(rest)} commits beyond its members", member_fault=False)
    if rest and gate:
        # #13400: the regeneration content is proven by MAIN's gates only while no member changed one.
        raise Reject(f"regeneration commit next to gate-code member(s) {gate}", member_fault=False)
    if rest:
        regen = rest[0]
        for change in changed_entries(git, f"{regen}^", regen):
            for path in change.paths:
                if unsafe_path(path):
                    raise Reject("regeneration commit touches a path with a control character")
                if change.new_mode in SPECIAL_MODES:
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
# Transient GitHub API failures (#13400). On 2026-10-26 one 503 in publish (16:33Z, a check-runs GET) and
# one installation-quota 403 in build (16:39Z) each killed a tick's carrier, ~25 min of fast lane lost.
# ONE policy, here, in the only helper every mode's API call goes through:
# - retried with bounded exponential backoff: 502/503/504, a connection reset/refusal or timeout, and a
#   SECONDARY rate limit (403/429 carrying Retry-After). Only for GET — a write whose response was lost
#   may have been applied (a retried update-branch would answer 422 and read as a refusal), so a
#   transient failure on a write aborts the tick instead;
# - PRIMARY quota exhaustion (403/429 with x-ratelimit-remaining: 0) is never retried: the tick aborts at
#   once, logging the reset time — waiting in-process could only burn runner minutes until it;
# - everything else (401, 403 without a rate-limit signal, 404, 422, ...) is returned exactly as before,
#   so every caller stays fail-closed on it.
# An abort raises ApiAbort, which main() turns into a clean exit: no member is blamed, nothing is
# bisected or halved, the batch stays armed for the next tick. Sleep is bounded per call
# (API_RETRY_ATTEMPTS, API_BACKOFF_CAP_S) and per process (API_RETRY_BUDGET_S, 5 min against the
# 30-45 min step timeouts in merge-train.yml).
TRANSIENT_HTTP = frozenset({502, 503, 504})
API_RETRY_ATTEMPTS = 5
API_BACKOFF_BASE_S = 2.0
API_BACKOFF_CAP_S = 60.0
API_RETRY_BUDGET_S = 300.0
_api_slept = [0.0]
# Quota (#13400): one tick read the same check-runs of a sha up to three times (checks, ci_activity,
# extra_checks) and the open-PR list three times. Those GETs are memoised ONLY inside planning_reads()
# (settle's view of main and the armed PRs, build's batch plan), and EVERY write clears the memo.
# Cache safety (#13988 review): a memoised read is never a write's verdict. Outside planning_reads()
# nothing is memoised (publish plans fresh, extra-gate runs included), and the writes that act on a
# verdict re-read it live inside deciding(), where the memo neither serves nor stores: GitHub.merge
# re-reads the ruleset guard and the head's required contexts, and a `merge-train` success status
# re-reads the head's required contexts — in the GitHub class itself, so no call site can skip it.
# Never memoised anywhere: the main ref and a single PR (`pulls/<n>`).
_GET_MEMO: dict[str, bytes] = {}
_MEMO_SCOPE = [0]    # > 0 inside planning_reads()
_DECIDING = [0]      # > 0 inside deciding(): verdict reads bypass the memo entirely


@contextlib.contextmanager
def planning_reads():
    """Reads that only classify and plan: memoised (until the next write)."""
    _MEMO_SCOPE[0] += 1
    try:
        yield
    finally:
        _MEMO_SCOPE[0] -= 1
        if not _MEMO_SCOPE[0]:
            _GET_MEMO.clear()


@contextlib.contextmanager
def deciding():
    """Reads a write is about to act on: always live, never memoised, whatever scope encloses them."""
    _DECIDING[0] += 1
    try:
        yield
    finally:
        _DECIDING[0] -= 1
GET_MEMO_RE = re.compile(r"^/repos/[^/]+/[^/]+/(?:commits/[0-9a-f]{40}/(?:check-runs|status|statuses)"
                         r"|rules/branches/[^/?]+|actions/runs\?head_sha=[0-9a-f]{40}|pulls\?state=open&base=[^&]+)(?:[?&].*)?$")


class ApiAbort(Exception):
    """The GitHub API is unavailable for this tick (transient retries spent, or primary quota out).
    Never a verdict about any PR: main() exits cleanly and the next tick starts over."""

    def __init__(self, kind: str, detail: str, reset: int | None = None):
        super().__init__(f"{kind}: {detail}")
        self.kind, self.detail, self.reset = kind, detail, reset


def _http_open(req, timeout):
    """(status, body bytes, headers) for a 2xx/3xx; raises urllib errors otherwise. Replaced in self-test."""
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read(), r.headers


def _api_sleep(s: float) -> None:
    time.sleep(s)


def _header(headers, name: str) -> str:
    try:
        return (headers.get(name) if headers is not None else None) or ""
    except AttributeError:
        return ""


def api_retry_delay(method: str, code: int | None, headers, attempt: int) -> float | None:
    """Seconds to wait before retrying, or None when this failure is not retried. Raises ApiAbort for
    primary quota exhaustion. `code` None = connection reset/refused or timeout (no response)."""
    if code in (403, 429):
        if _header(headers, "x-ratelimit-remaining") == "0":
            reset = _header(headers, "x-ratelimit-reset")
            when = (datetime.datetime.fromtimestamp(int(reset), datetime.timezone.utc).strftime("%H:%M:%SZ")
                    if reset.isdigit() else "unknown")
            raise ApiAbort("quota", f"primary rate limit exhausted, resets at {when}",
                           int(reset) if reset.isdigit() else None)
        retry_after = _header(headers, "retry-after")
        if not retry_after.isdigit():
            return None                       # auth/permission 403: fail closed, never retried
        if method != "GET":
            raise ApiAbort("secondary", f"secondary rate limit on a {method} (Retry-After {retry_after}s)")
        return float(retry_after)
    if code is not None and code not in TRANSIENT_HTTP:
        return None
    if method != "GET":
        raise ApiAbort("transient", f"{method} failed with {code or 'no response'}; a write is never retried")
    return min(API_BACKOFF_CAP_S, API_BACKOFF_BASE_S * (2 ** attempt))


def api(method: str, path: str, body: dict | None = None) -> tuple[int, object]:
    data = json.dumps(body).encode() if body is not None else None
    memo = (method == "GET" and _MEMO_SCOPE[0] > 0 and not _DECIDING[0]
            and GET_MEMO_RE.match(path) is not None)
    if method != "GET":
        _GET_MEMO.clear()
    elif memo and path in _GET_MEMO:
        return 200, json.loads(_GET_MEMO[path])
    for attempt in range(API_RETRY_ATTEMPTS):
        req = urllib.request.Request(API + path, method=method, data=data)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        req.add_header("Authorization", f"Bearer {os.environ['GH_TOKEN']}")
        try:
            status, raw, _h = _http_open(req, 30)
            if memo and status == 200:
                _GET_MEMO[path] = raw or b"null"
            return status, json.loads(raw or b"null")
        except urllib.error.HTTPError as e:
            code, headers, text = e.code, e.headers, e.read().decode(errors="replace")[:300]
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            code, headers, text = None, None, f"{type(e).__name__}: {e}"
        delay = api_retry_delay(method, code, headers, attempt)
        if delay is None:
            return code, text                 # not transient: the caller decides, fail-closed as before
        if attempt + 1 >= API_RETRY_ATTEMPTS or _api_slept[0] + delay > API_RETRY_BUDGET_S:
            raise ApiAbort("transient", f"{method} {path} -> {code or 'no response'} after {attempt + 1} attempt(s)")
        log(f"::notice::{method} {path} -> {code or 'no response'}: retry {attempt + 1} in {delay:.0f}s")
        _api_sleep(delay)
        _api_slept[0] += delay
    raise ApiAbort("transient", f"{method} {path}: retries exhausted")   # unreachable: the loop returns or raises


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


def ruleset_guard(rules, app_id) -> str | None:
    """Why settle must NOT merge yet, or None when the ruleset says the train owns merging (ADR-0343 phase 1).

    Both must hold: `merge-train` is a required context pinned to THIS App's integration id (so no other
    actor's status can satisfy it), and strict up-to-date is off on every rule that carries it. Anything
    unreadable or unparseable refuses: the guard fails closed."""
    try:
        want = int(str(app_id).strip())
    except (TypeError, ValueError):
        return "GITOPS_APP_ID is not an integer, cannot verify the pin"
    pinned, strict = False, False
    for r in rules if isinstance(rules, list) else []:
        if not isinstance(r, dict) or r.get("type") != "required_status_checks":
            continue
        params = r.get("parameters") or {}
        strict = strict or bool(params.get("strict_required_status_checks_policy"))
        pinned = pinned or any(isinstance(c, dict) and c.get("context") == STATUS_CONTEXT and c.get("integration_id") == want
                               for c in params.get("required_status_checks") or [])
    if not pinned:
        return f"ruleset does not require `{STATUS_CONTEXT}` pinned to integration {want}"
    if strict:
        return "strict up-to-date is still on"
    return None


def owner_approval_error(repo: str, number: int, head: str, decision: str,
                         env: dict, metadata: dict, pr: dict) -> str | None:
    """Fail closed before writing a decision; the workflow performs the same actor checks pre-token."""
    owner = metadata.get("owner") or {}
    if (metadata.get("full_name") != repo or owner.get("type") != "User"
            or env.get("GITHUB_REPOSITORY") != repo
            or env.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
            or env.get("GITHUB_REF") != "refs/heads/main"
            or env.get("GITHUB_RUN_ATTEMPT") != "1"
            or env.get("GITHUB_ACTOR") != owner.get("login")
            or env.get("GITHUB_TRIGGERING_ACTOR") != owner.get("login")
            or env.get("GITHUB_ACTOR_ID") != str(owner.get("id"))):
        return "only a first-attempt main dispatch by the live User repository owner may decide"
    if type(number) is not int or number < 1 or not isinstance(head, str) or not SHA_RE.fullmatch(head) or decision not in ("approve", "revoke"):
        return "invalid PR number, full head SHA or decision"
    target, base = pr.get("head") or {}, pr.get("base") or {}
    if (pr.get("number") != number
            or (decision == "approve" and (pr.get("state") != "open" or pr.get("draft")))
            or target.get("sha") != head or (target.get("repo") or {}).get("full_name") != repo
            or base.get("ref") != "main" or (base.get("repo") or {}).get("full_name") != repo
            or not any((target.get("ref") or "").startswith(pre) for pre in EXCLUDED_BRANCH_PREFIXES)):
        return "decision requires an open, non-draft, same-repository codex PR to main at the supplied head"
    return None


def owner_status_line(statuses: list) -> str | None:
    """Newest owner-approval status created by the App itself (login AND id); anyone else's is ignored.
    The description is attacker-influenceable text on a shared summary, so strip all but plain
    printable ASCII (no markdown/HTML metacharacters) and truncate."""
    mine = [st for st in statuses if st.get("context") == OWNER_APPROVAL_CONTEXT
            and (st.get("creator") or {}).get("login") == BOT_LOGIN and (st.get("creator") or {}).get("id") == BOT_ID]
    if not mine:
        return None
    st = max(mine, key=lambda x: x["id"])
    desc = re.sub(r"[^A-Za-z0-9 #:._-]", "", str(st.get("description") or ""))[:80]
    return f"state={re.sub(r'[^a-z_]', '', str(st.get('state')))[:20]} description={desc}"


def mode_owner_approval(gh, number: int, head: str, decision: str) -> None:
    metadata = get(f"/repos/{gh.repo}")
    pr = gh.pr(number)
    error = owner_approval_error(gh.repo, number, head, decision, os.environ, metadata, pr)
    if error:
        raise SystemExit(f"::error::{error}")
    state = "success" if decision == "approve" else "failure"
    description = f"owner-{'approved' if decision == 'approve' else 'revoked'} PR #{number}"
    # This is a decision record, never a CI verdict or the required merge-train context.
    gh.post_status(head, state, description, context=OWNER_APPROVAL_CONTEXT)
    log(f"owner decision: {decision} PR #{number} at {head}")


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

    def merge_guard(self) -> str | None:
        code, data = api("GET", f"/repos/{self.repo}/rules/branches/{self.branch}")
        if code != 200:
            return f"cannot read the ruleset ({code})"
        return ruleset_guard(data, os.environ.get("GITOPS_APP_ID", ""))

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

    def ci_activity(self, sha: str) -> float | None:
        """Newest started_at/completed_at of any check run on `sha` (epoch), None when none started.
        GitHub stamps these, so unlike the committer time they cannot be author-dated."""
        stamps = [c.get(k) for c in paginate(f"/repos/{self.repo}/commits/{sha}/check-runs", "check_runs")
                  for k in ("started_at", "completed_at")]
        epochs = [datetime.datetime.fromisoformat(t.replace("Z", "+00:00")).timestamp() for t in stamps if t]
        return max(epochs) if epochs else None

    def extra_checks(self, sha: str, gates: dict[str, str]) -> dict[str, str | None]:
        """Pinned extra-gate verdicts on `sha`: only check runs by the `github-actions` app whose workflow
        run's path is the declared workflow. Any completed non-success, non-skipped run = that state (red
        wins over every later green); else running = None; else success/skipped. No pinned check run:
        "skipped" (not applicable) ONLY when the pinned workflow has a run for `sha` and every such run
        completed with conclusion success; "failure" when one failed; None (pending) when one ended
        cancelled/timed_out/startup_failure/action_required; absent while none has completed — every
        caller holds absent and None as pending (fail closed, #13400)."""
        runs: dict[str, list[str | None]] = {}
        for check in paginate(f"/repos/{self.repo}/commits/{sha}/check-runs", "check_runs"):
            name = check.get("name")
            if name not in gates or (check.get("app") or {}).get("slug") != "github-actions":
                continue
            m = re.search(r"/actions/runs/([0-9]+)/job", check.get("details_url") or check.get("html_url") or "")
            if not m:
                continue
            path = self._run_path(int(m.group(1)))
            if path.split("@", 1)[0] != gates[name]:
                continue
            runs.setdefault(name, []).append(check["conclusion"] if check["status"] == "completed" else None)
        out = {name: extra_verdict(states) for name, states in runs.items()}
        missing = [n for n in gates if n not in out]
        if missing:
            wruns = paginate(f"/repos/{self.repo}/actions/runs?head_sha={sha}", "workflow_runs")
            for name in missing:
                pinned = [r for r in wruns if r.get("head_sha", sha) == sha
                          and (r.get("path") or "").split("@", 1)[0] == gates[name]]
                if not pinned or any(r.get("status") != "completed" for r in pinned):
                    continue   # absent: no completed pinned run yet — pending (fail closed)
                concl = [r.get("conclusion") for r in pinned]
                # #13400: n/a ONLY when every pinned run SUCCEEDED. A failed run is red; any other
                # conclusion (cancelled, timed_out, startup_failure, action_required, ...) proves
                # nothing — pending, never served.
                out[name] = ("failure" if "failure" in concl
                             else "skipped" if all(c == "success" for c in concl) else None)
        return out

    def _run_path(self, run_id: int) -> str:
        cache = self.__dict__.setdefault("_paths", {})
        if run_id not in cache:
            cache[run_id] = (get(f"/repos/{self.repo}/actions/runs/{run_id}") or {}).get("path") or ""
        return cache[run_id]

    def app_statuses(self, sha: str) -> dict[str, tuple[str, str]]:
        """The App's own latest status per context on `sha` — statuses by anyone else are ignored."""
        out: dict[str, tuple[str, str]] = {}
        for st in sorted(paginate(f"/repos/{self.repo}/commits/{sha}/statuses"), key=lambda s: s["id"], reverse=True):
            if (st.get("creator") or {}).get("login") == BOT_LOGIN:
                out.setdefault(st["context"], (st["state"], st.get("description") or ""))
        return out

    def unverified(self, sha: str, guard: bool) -> str | None:
        """Why a write acting on `sha`'s verdict must NOT happen, re-read live (deciding): the ruleset —
        and, when `guard`, the merge guard on it — and every required context but `merge-train` itself
        must be green on the exact head NOW. None = verified."""
        with deciding():
            code, rules = api("GET", f"/repos/{self.repo}/rules/branches/{self.branch}")
            if code != 200 or not isinstance(rules, list):
                return f"cannot re-read the ruleset ({code})"
            if guard:
                why = ruleset_guard(rules, os.environ.get("GITOPS_APP_ID", ""))
                if why:
                    return f"merge guard: {why}"
            required = sorted({c["context"] for r in rules if r.get("type") == "required_status_checks"
                               for c in r["parameters"]["required_status_checks"]})
            checks = self.checks(sha)
        bad = [c for c in required if c != STATUS_CONTEXT and checks.get(c) != "success"]
        return f"required context(s) not green on the head now: {', '.join(bad)}" if bad else None

    def post_status(self, sha: str, state: str, description: str, context: str = STATUS_CONTEXT,
                    *, source_number: int | None = None) -> None:
        if not SHA_RE.fullmatch(sha):
            raise SystemExit(f"::error::refusing a status on non-sha {sha!r}")
        if state == "success" and context == STATUS_CONTEXT:
            if type(source_number) is not int or source_number <= 0:
                raise SystemExit("::error::merge-train success requires a source PR number")
            why = self.unverified(sha, guard=False)
            if why:
                log(f"::warning::no merge-train success on {sha[:12]}: {why}")
                return
            # A success status can release native auto-merge before our explicit merge call.
            if not live_source_intent(self, source_number, sha):
                log(f"::notice::#{source_number} source intent changed — no merge-train success")
                return
        code, data = api("POST", f"/repos/{self.repo}/statuses/{sha}",
                         {"state": state, "context": context, "description": description[:140], "target_url": self.run_url})
        if code != 201:
            raise SystemExit(f"::error::POST status on {sha} -> {code}: {data}")

    def comment(self, n: int, text: str) -> None:
        api("POST", f"/repos/{self.repo}/issues/{int(n)}/comments", {"body": f"{COMMENT_MARK}\n{text}\n\n{self.run_url}"})

    def merge(self, n: int, head: str) -> str | None:
        """Squash-merge pinned to the verified head sha (the API's `sha` = --match-head-commit)."""
        why = self.unverified(head, guard=True)   # live verdict and ruleset (cache safety)
        if why:
            log(f"::warning::#{n} not merged: {why}")
            return None
        if not live_source_intent(self, n, head):  # last read, after potentially slow verdict reads
            log(f"::notice::#{n} source intent or head changed — not merged")
            return None
        code, data = api("PUT", f"/repos/{self.repo}/pulls/{int(n)}/merge", {"merge_method": "squash", "sha": head})
        return data.get("sha") if code == 200 and isinstance(data, dict) and data.get("merged") else None

    def rerun_inconclusive(self, sha: str, contexts: list[str]) -> bool:
        """Re-run the cancelled (or inconclusively failed, #13400) jobs of the workflow runs behind
        `contexts` on `sha` (once per carrier; the caller records it). True only when every run accepted."""
        latest: dict[str, dict] = {}
        for check in paginate(f"/repos/{self.repo}/commits/{sha}/check-runs", "check_runs"):
            if check["name"] in contexts and (check["name"] not in latest or check["id"] > latest[check["name"]]["id"]):
                latest[check["name"]] = check
        runs = set()
        for check in latest.values():
            m = re.search(r"/actions/runs/([0-9]+)/job", check.get("details_url") or check.get("html_url") or "")
            if not m:
                return False
            runs.add(int(m.group(1)))
        if not runs:
            return False
        return all(api("POST", f"/repos/{self.repo}/actions/runs/{r}/rerun-failed-jobs", {})[0] == 201 for r in sorted(runs))

    def failures_inconclusive(self, sha: str, failing: list[str]) -> tuple[bool, str]:
        """Whether the carrier's failing contexts failed ONLY for inconclusive gate kinds (budget overrun,
        NOT EVALUATED), from the gate runner's own --json artifacts — never from the log. Any read that
        does not succeed answers False: today's conclusive verdict, never a silent pass."""
        latest: dict[str, dict] = {}
        for check in paginate(f"/repos/{self.repo}/commits/{sha}/check-runs", "check_runs"):
            if check["name"] in failing and (check["name"] not in latest or check["id"] > latest[check["name"]]["id"]):
                latest[check["name"]] = check
        if set(latest) != set(failing):
            return False, "a failing context is not a check run"
        runs = set()
        for check in latest.values():
            m = re.search(r"/actions/runs/([0-9]+)/job", check.get("details_url") or check.get("html_url") or "")
            if not m:
                return False, f"`{check['name']}` has no workflow run"
            runs.add(int(m.group(1)))
        why = []
        for run in sorted(runs):
            code, run_info = api("GET", f"/repos/{self.repo}/actions/runs/{run}")
            attempt = run_info.get("run_attempt") if code == 200 and isinstance(run_info, dict) else None
            if type(attempt) is not int or attempt < 1:
                return False, f"workflow run `{run}` has no current attempt"
            jobs = paginate(f"/repos/{self.repo}/actions/runs/{run}/jobs?filter=latest", "jobs")
            failed_jobs = [j for j in jobs if j.get("conclusion") not in ("success", "skipped", "neutral", None)]
            if any(j.get("run_attempt") != attempt for j in failed_jobs):
                return False, f"a failing job of run `{run}` is not from attempt {attempt}"
            failed = [j["name"] for j in failed_jobs]
            arts: dict[str, dict] = {}
            for a in paginate(f"/repos/{self.repo}/actions/runs/{run}/artifacts", "artifacts"):
                name = a.get("name") or ""
                if name.startswith(GATE_ARTIFACT_PREFIX) and not a.get("expired") and (
                        name not in arts or a["id"] > arts[name]["id"]):
                    arts[name] = a
            records: dict[str, list | None] = {}
            for job in failed:
                m = GATE_SHARD_JOB_RE.fullmatch(job)
                if m:
                    a = arts.get(f"{GATE_ARTIFACT_PREFIX}{m.group(1)}-a{attempt}")
                    records[m.group(1)] = self.artifact_json(a["id"]) if a else None
            ok, reason = gate_failures_inconclusive(failed, records)
            if not ok:
                return False, reason
            why.append(reason)
        return True, "; ".join(why)

    def artifact_json(self, artifact_id: int) -> list | None:
        """The single JSON file inside an artifact zip. The archive URL redirects to blob storage, which
        must NOT receive the GitHub token, so the redirect is followed by hand without it."""
        import io
        import zipfile

        class _NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None
        req = urllib.request.Request(f"{API}/repos/{self.repo}/actions/artifacts/{int(artifact_id)}/zip")
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("Authorization", f"Bearer {os.environ['GH_TOKEN']}")
        try:
            try:
                with urllib.request.build_opener(_NoRedirect).open(req, timeout=30) as r:
                    blob = r.read()
            except urllib.error.HTTPError as e:
                if e.code not in (301, 302, 303, 307, 308) or not e.headers.get("Location", "").startswith("https://"):
                    return None
                with urllib.request.urlopen(e.headers["Location"], timeout=30) as r:
                    blob = r.read()
            with zipfile.ZipFile(io.BytesIO(blob)) as z:
                names = [n for n in z.namelist() if n.endswith(".json")]
                data = json.loads(z.read(names[0])) if len(names) == 1 else None
        except (OSError, ValueError, zipfile.BadZipFile, KeyError):
            return None
        return data if isinstance(data, list) else None

    def update_branch(self, n: int, head: str) -> bool:
        """Merge main into the PR branch, pinned to the head the strict lane judged."""
        code, _ = api("PUT", f"/repos/{self.repo}/pulls/{int(n)}/update-branch", {"expected_head_sha": head})
        return code == 202

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
                    limit: int = 64, strict: list | None = None,
                    red: list | None = None, main_red: dict[str, str] | None = None) -> tuple[list[Member], dict[int, dict]]:
    """Open PRs that qualify RIGHT NOW. Each head is fetched and must equal the API's head sha, and
    the file list is changed_paths(<base>...<verified head>) — the PR files API stops at
    3000 entries, so a long PR could hide a path there; git has no such cap."""
    member_required = [c for c in required if c != STATUS_CONTEXT]
    extra = train_extra_gates(git, base)
    if extra is None:
        return [], {}
    armed = []
    for p in order_candidates([p for p in gh.open_prs() if not is_carrier(p, repo)]):
        if p.get("auto_merge") and not p.get("draft"):
            armed.append(p)
        else:
            # Every open PR gets a decision line: a disarmed or draft PR used to be filtered out in
            # silence, so a settle log with no line for a PR could not say whether it was ever seen.
            log(f"#{p['number']:>6} skip      {'draft' if p.get('draft') else 'auto-merge not armed'}")
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
        # The ordinary candidate list is capped, but stale-red refresh must still inspect every
        # armed PR. Once full, check only the verified head's required statuses: no diff, app-status,
        # or strict-lane work is needed to decide whether it is red and belongs in the refresh queue.
        if len(out) >= limit and red is not None:
            view = pr_view(repo, p, [])
            failing = stale_red_candidate(view, member_required, pinned_checks(gh, fetched, extra))
            if failing:
                red.append((view, failing))
            continue
        entries = changed_entries(git, f"{base}...{fetched}")
        files = changed_paths(git, f"{base}...{fetched}")
        view = pr_view(repo, p, files)
        view["special_paths"] = [x for c in entries if c.new_mode in SPECIAL_MODES for x in c.paths]
        app = gh.app_statuses(fetched)
        view["app"] = app                    # strict_lane reads the notice from it, no second read
        checks = pinned_checks(gh, fetched, extra)
        ok, why = qualifies(view, member_required, checks, app, untrainable, extra=extra)
        log(f"#{n:>6} {'CANDIDATE' if ok else 'skip     '} {why}")
        # The lane-independent verdict (manual-lane paths aside): is an extra gate the reason it waits?
        xwhy = why if ok else qualifies(view, member_required, checks, app, lambda _f: False, extra=extra)[1]
        if strict is not None and xwhy.startswith("extra gate red") and EXTRA_GATE_CONTEXT not in app:
            # settle only (build never writes): tell the author once per head, never disarm.
            gh.comment(n, EXTRA_GATE_MESSAGE)
            gh.post_status(fetched, "failure", f"{xwhy} on this head; the train waits for a green scan",
                           context=EXTRA_GATE_CONTEXT)
        fixed: list[str] = []
        if not ok and strict is not None:
            modes = [m for c in changed_entries(git, f"{base}...{fetched}") for m in (c.old_mode, c.new_mode)]
            state, fixed, swhy = fix_head_state(gh, git, base, view, member_required, checks, app, modes, main_red,
                                                extra=extra)
            if state:
                view["fix"] = fixed
            else:
                state, swhy = strict_lane_state(view, member_required, checks, app, untrainable, modes, main_red, extra)
            if state:
                view["strict_why"] = swhy
                log(f"#{n:>6} strict    {state}: {swhy}")
                strict.append((state, view))
        if not ok and red is not None and not fixed:
            failing = stale_red_candidate(view, member_required, checks)
            if failing:
                red.append((view, failing))
        if ok:
            out.append(Member(n, view["title"], view["body"], fetched, view["head_ref"], view["created_at"],
                              ref=fetched, files=files))
            apps[n] = app
        if len(out) >= limit and red is None:
            break
    return out, apps


def partition(git, base: str, cands: list[Member], fragments: list[str]) -> tuple[list[Member], list[Member]]:
    """(fast lane, train). Computed from live git data only."""
    for m in cands:
        m.files = changed_paths(git, f"{base}...{m.head_sha}")
    fast, train = [], []
    for m in cands:
        mb = git("merge-base", base, m.head_sha).stdout.strip()
        main_files = changed_paths(git, mb, base)
        others = [f for o in cands if o is not m for f in o.files]
        ok, _ = fast_lane_eligible(m.files, main_files, others, fragments)
        (fast if ok else train).append(m)
    return fast, train


def main_regen_text(git, base: str) -> str:
    return git("show", f"{base}:{REGEN_INVENTORY}", check=False).stdout


def plan_train(gh, git, repo: str, base: str) -> tuple[list[Member], list[Member], dict[int, int | None]]:
    """(fast lane, every train candidate in order, bisect sizes) from live data."""
    required = gh.required()
    regen_text = main_regen_text(git, base)
    cands, apps = live_candidates(gh, git, repo, required, base, Untrainable(regen_text))
    fragments = regen_inventory_fragments(regen_text)
    fast, train = partition(git, base, cands, fragments)
    main_red = main_red_contexts(gh, git, required, base)
    if main_red and train:
        # A train over a red main is red on that context whatever its members do (the settle lane serves
        # a FIX alone, promote_fixes), so it would only burn a CI run to reach "inconclusive".
        log(f"train: none while main {base[:12]} is red on {', '.join(sorted(main_red))} — fixes go first, alone")
        train = []
    return fast, train, {n: bisect_size(a) for n, a in apps.items()}


def plan_batch(gh, git, repo: str, base: str, batch_size: int) -> tuple[list[Member], list[Member], dict[int, int | None]]:
    """(fast lane, this tick's candidate window, live bisect sizes) — the same function in build and settle."""
    fast, train, sizes = plan_train(gh, git, repo, base)
    return fast, select_batch(train, sizes, batch_size), sizes


MANIFEST_RE = re.compile(r"^([0-9]{1,9}) ([0-9a-f]{40})$")


def write_manifest(path: str, batch: list[Member]) -> None:
    pathlib.Path(path).write_text("".join(f"{m.number} {m.head_sha}\n" for m in batch))


def declared_batch(manifest_text: str, train: list[Member], sizes: dict[int, int | None],
                   batch_size: int) -> list[Member]:
    """The batch build declared, admitted only member by member against LIVE data. The manifest is
    untrusted (build ran generators over PR data), so it can only SELECT: every entry must be a
    live train candidate at exactly its live head, and the selection obeys the same size cap as
    select_batch. Re-planning instead would reject a legitimate bundle whenever any candidate
    changed state in the minutes between build and publish. Raises Reject."""
    lines = manifest_text.splitlines()
    if not lines:
        raise Reject("bundle carries no member manifest", member_fault=False)
    live = {m.number: m for m in train}
    want: dict[int, str] = {}
    for ln in lines:
        mm = MANIFEST_RE.fullmatch(ln)
        if not mm or int(mm.group(1)) in want:
            raise Reject("member manifest is malformed", member_fault=False)
        want[int(mm.group(1))] = mm.group(2)
    for n, head in want.items():
        if n not in live or live[n].head_sha != head:
            raise Reject(f"declared member #{n} is no longer a live train candidate at that head — the next tick rebuilds",
                         member_fault=False)
    batch = [m for m in train if m.number in want]
    if len(batch) > max(1, batch_size):
        raise Reject(f"declared batch of {len(batch)} exceeds the batch size of {batch_size}", member_fault=False)
    return batch


def live_base(gh, git) -> str:
    git("fetch", "-q", "origin", f"+refs/heads/{gh.branch}:refs/remotes/origin/{gh.branch}")
    return git("rev-parse", f"refs/remotes/origin/{gh.branch}").stdout.strip()


# --------------------------------------------------------------------------- the three modes
def mode_build(gh, git, builder: TrainBuilder, repo: str, out_dir: str, batch_size: int) -> list[int]:
    base = live_base(gh, git)
    with planning_reads():           # build writes nothing to GitHub; publish re-plans live
        _fast, batch, sizes = plan_batch(gh, git, repo, base, batch_size)
    builder.sizes, builder.default_size = sizes, batch_size
    capped = sorted({sizes[m.number] for m in batch if sizes.get(m.number)})
    if capped:
        log(f"build: bisect size(s) {capped} on candidates — the cap counts only members that apply")
    regen_text = main_regen_text(git, base)
    builder.untrainable = Untrainable(regen_text)
    builder.declared_inputs = regen_inventory_fragments(regen_text)
    os.makedirs(out_dir, exist_ok=True)
    if not batch:
        log("build: no train this tick")
        return []
    tip, applied, dropped = builder.build(base, batch)
    log(f"build over {base[:12]}: applied {applied}, dropped {dropped}")
    if dropped:
        heads = {m.number: m.head_sha for m in batch}
        pathlib.Path(out_dir, DROPS_FILE).write_text("".join(f"{n} {heads[n]}\n" for n in dropped))
    if applied:
        tried = set(builder.attempted)
        write_manifest(os.path.join(out_dir, MANIFEST_FILE),
                       [m for m in batch if m.number in tried and m.number not in builder.deferred])
        git("update-ref", BUNDLE_REF, tip)
        git("bundle", "create", os.path.join(out_dir, BUNDLE_FILE), BUNDLE_REF, f"^{base}")
    return applied


def conflict_paths(git, base: str, head: str) -> list[str] | None:
    """Paths `head` conflicts on against `base` by `git merge-tree` (execution-free), or None when it
    merges cleanly or merge-tree could not decide (anything but exit 1 is not a conflict)."""
    r = git("merge-tree", "--write-tree", "--name-only", "--no-messages", "-z", base, head, check=False)
    if r.returncode != 1:
        return None
    return sorted({f for f in r.stdout.split("\0")[1:] if f})


def notice_text(message: str, paths: list[str] | None) -> str:
    """Fixed text plus the conflicting paths as inline code. A path that could break out of the code
    span or the line (control character, backtick) is counted, never printed."""
    shown = [f for f in (paths or []) if not unsafe_path(f) and "`" not in f][:NOTICE_PATH_CAP]
    if not paths:
        return message
    more = len(paths) - len(shown)
    return message + "\n\nConflicting paths:\n" + "".join(f"- `{f}`\n" for f in shown) + (
        f"- ... and {more} more\n" if more else "")


def notice_once(gh, n: int, head: str, description: str, text: str) -> bool:
    """Tell the author once per head (NOTICE_CONTEXT marks it). The `merge-train` failure is posted
    only over no App failure, so a culprit or manual-lane verdict on the same head is never replaced."""
    app = gh.app_statuses(head)
    if NOTICE_CONTEXT in app:
        return False
    if app.get(STATUS_CONTEXT, ("", ""))[0] != "failure":
        gh.post_status(head, "failure", description)
    gh.comment(n, text)
    gh.post_status(head, "failure", description, context=NOTICE_CONTEXT)
    log(f"#{n:>6} notice    {description}")
    return True


def mode_notice_drops(gh, git, bundle_dir: str) -> list[int]:
    """Publish (App token), before anything else: build's drops file is DATA from a job that ran
    generators over PR code, so it only nominates. A line is admitted only as `<number> <sha>` of an
    open, armed PR whose live head is exactly that sha, and a notice is posted only when THIS job's own
    `git merge-tree` of that head against live main conflicts. Nothing in the file is executed."""
    path = os.path.join(bundle_dir, DROPS_FILE)
    if not os.path.isfile(path):
        return []
    lines = pathlib.Path(path).read_text()[:8192].splitlines()
    nominated = [MANIFEST_RE.fullmatch(ln) for ln in lines[:64]]
    if any(m is None for m in nominated):
        log("::warning::drops file is malformed — no notices this tick")
        return []
    base = live_base(gh, git)
    told: list[int] = []
    for n, head in {int(m.group(1)): m.group(2) for m in nominated}.items():
        p = gh.pr(n)
        if p.get("state") != "open" or not p.get("auto_merge") or p["head"]["sha"] != head:
            continue
        git("fetch", "-q", "origin", f"+{gh.head_refspec(n)}:refs/merge-train/pr-{n}")
        if git("rev-parse", "--verify", "--quiet", f"refs/merge-train/pr-{n}", check=False).stdout.strip() != head:
            continue
        paths = conflict_paths(git, base, head)
        if paths is None:
            continue   # dropped for another reason (an earlier member, a path rule): publish says so as before
        if notice_once(gh, n, head, f"{CONFLICT_PREFIX}{base[:12]}: merge main into the branch",
                       notice_text(CONFLICT_COMMENT, paths)):
            told.append(n)
    return told


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
    _fast, train, sizes = plan_train(gh, git, repo, base)
    bundle = os.path.join(bundle_dir, BUNDLE_FILE)
    if not train or not os.path.isfile(bundle):
        return "idle"
    manifest = os.path.join(bundle_dir, MANIFEST_FILE)
    try:
        batch = declared_batch(pathlib.Path(manifest).read_text() if os.path.isfile(manifest) else "",
                               train, sizes, batch_size)
    except Reject as exc:
        log(f"::warning::train rejected: {exc}")
        return "rejected"
    log(f"publish: declared batch {[m.number for m in batch]}")
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
        live = {m.number: m for m in train}
        cap = batch_cap([live[n] for n in v.applied if n in live], sizes, batch_size)
        if len(v.applied) > cap:
            raise Reject(f"train applies {len(v.applied)} member(s), over the bisect cap of {cap}", member_fault=False)
    except Reject as exc:
        log(f"::warning::train rejected: {exc}")
        if exc.member_fault:
            mark_unverifiable(gh, batch)
        return "rejected"
    for n in v.dropped:
        gh.comment(n, DROP_MESSAGE)
    heads = {m.number: m.head_sha for m in batch}
    for n in v.applied:
        if not live_source_intent(gh, n, heads[n]):
            log(f"::notice::#{n} source intent or head changed — carrier not published")
            return "rejected"
    Pusher(git, branch, gh.remote).push(v.tip)
    gh.post_status(v.tip, "success", f"tree={v.tree}", context=RECORD_CONTEXT)
    gh.create_carrier(branch)
    record_deferral(gh, git, repo, base, branch)
    log(f"published {branch} over {base[:12]}: members {v.applied}, dropped {v.dropped}")
    return "published"


def mode_settle(gh, git, repo: str, shadow: bool) -> dict:
    """Judge every finished App carrier, then post the fast lane from live data."""
    summary: dict = {"in_flight": False, "merged": [], "culprits": [], "bisected": [], "rejected": [], "fast": [],
                     "inconclusive": [], "rerun": [], "refreshed": []}
    if not shadow:
        # Phase 1 is gated on the live ruleset, not on the env flag alone: until it requires the App-pinned
        # context with strict off, behave exactly as shadow. Flip order of env vs ruleset is irrelevant.
        why = gh.merge_guard()
        if why:
            log(f"::notice::merge guard: shadow behaviour, nothing merges — {why}")
            shadow = True
    required = gh.required()
    train_required = [c for c in required if c != STATUS_CONTEXT and c not in TREE_INDEPENDENT_CONTEXTS]
    carrier_in_flight = False
    # Read the strict lane BEFORE settling any carrier: a reservation holds train merges as well as the
    # fast lane, or main moves under the reserved head's CI and it is re-updated forever (#13456).
    base, regen_text, strict, red, cands, apps, main_red, main_cache = settle_view(gh, git, repo, required)
    holder, timed_out = strict_reservation(git, base, strict, shadow,
                                           fixes=strict_fixes(gh, git, base, strict, main_red, main_cache),
                                           activity=gh.ci_activity)
    acted = False
    for p in [p for p in gh.open_prs() if is_carrier(p, repo)]:
        sha, branch, carrier = p["head"]["sha"], p["head"]["ref"], p["number"]
        extra = train_extra_gates(git, base)
        if extra is None:   # fail closed: no carrier is judged (nothing merges, nobody blamed) until fixed
            summary["in_flight"] = True
            carrier_in_flight = True
            continue
        checks = pinned_checks(gh, sha, extra)
        if any(checks.get(g) in EXTRA_GATE_NA_STATES for g in extra):
            # An n/a extra gate holds only if no member touches the gate's own workflow (#13400):
            # read the carrier's diff (= the union of member diffs) from its verified head.
            got = git("fetch", "-q", "origin", f"+refs/heads/{branch}:refs/merge-train/settle", check=False).returncode == 0
            ok = got and git("rev-parse", "refs/merge-train/settle", check=False).stdout.strip() == sha
            checks = own_workflow_checks(checks, extra, changed_paths(git, f"{base}...{sha}") if ok else None)
        failing, pending, inconclusive = classify_carrier(
            checks, carrier_contexts(train_required, extra, checks))
        gate_inconclusive = False   # inconclusive because a GATE overran its budget / was not evaluated
        main_extra = [c for c in failing if c in extra and c in main_red]
        failing = [c for c in failing if c not in main_extra]   # red on main too: never a culprit
        if main_extra and not failing:
            if pending:
                summary["in_flight"] = True
                carrier_in_flight = True
                continue
            log(f"::warning::carrier #{carrier}: red only on extra gate(s) {', '.join(main_extra)}, red on main too — no verdict")
            summary["inconclusive"].append(carrier)
            gh.close_carrier(carrier)
            if TRAIN_BRANCH_RE.fullmatch(branch):
                Pusher(git, branch, gh.remote).delete()
            continue
        if failing:
            # Red only because gates overran a budget or were NOT EVALUATED (#13400) — the same as a
            # cancelled job: no verdict about any member, so no culprit and no bisect.
            only_inconclusive, why = gh.failures_inconclusive(sha, failing)
            if only_inconclusive:
                log(f"carrier #{carrier}: {', '.join(failing)} red only for inconclusive gates ({why}) — inconclusive")
                failing, inconclusive = [], sorted(set(inconclusive) | set(failing))
                gate_inconclusive = True
        if not failing and pending and gate_inconclusive:
            # Early no-verdict close (#13400): the gate shard's job has COMPLETED and the runner's own
            # --json says every failed gate overran a budget or was not evaluated. Nothing still running
            # can turn that red into a culprit or a merge (a required context is red), so waiting ~1 h for
            # the rest of CI only delays the same close. Only when the batch can be halved: a single
            # member keeps today's wait-then-rerun-once path, since closing early could only rebuild it.
            halved = halve_without_verdict(gh, git, branch, sha)
            if halved:
                log(f"::warning::carrier #{carrier}: gate verdict inconclusive while {len(pending)} context(s) still "
                    f"run — closed now with no verdict, next batch halved, nobody blamed")
                summary["inconclusive"].append(carrier)
                summary["bisected"].append(halved)
                gh.close_carrier(carrier)
                if TRAIN_BRANCH_RE.fullmatch(branch):
                    Pusher(git, branch, gh.remote).delete()
                continue
        if not failing and pending:
            summary["in_flight"] = True
            if carrier_holds_strict(p):
                carrier_in_flight = True
            else:
                log(f"carrier #{carrier}: CI still running after > {CARRIER_PRIORITY_S // 60} min — no longer holds the strict lane")
            continue
        if not failing and inconclusive:
            # Wait for a terminal non-cancelled result: re-run the cancelled jobs ONCE, then the
            # carrier is in flight again. A second all-inconclusive verdict (or a refused rerun)
            # closes the carrier with NO verdict — nobody is blamed and the next tick rebuilds.
            if not gh.app_statuses(sha).get(RERUN_CONTEXT, ("", ""))[0] and gh.rerun_inconclusive(sha, inconclusive):
                gh.post_status(sha, "success", "rerun 1 of 1: " + ", ".join(inconclusive), context=RERUN_CONTEXT)
                log(f"carrier #{carrier}: inconclusive ({', '.join(inconclusive)}) — re-ran once, in flight")
                summary["rerun"].append(carrier)
                summary["in_flight"] = True
                carrier_in_flight = True
                continue
            log(f"::warning::carrier #{carrier}: still inconclusive ({', '.join(inconclusive)}) — closed with no verdict")
            summary["inconclusive"].append(carrier)
            if gate_inconclusive:
                halved = halve_without_verdict(gh, git, branch, sha)
                if halved:
                    log(f"carrier #{carrier}: gate verdict scales with the train — next batch halved, nobody blamed")
                    summary["bisected"].append(halved)
            gh.close_carrier(carrier)
            if TRAIN_BRANCH_RE.fullmatch(branch):
                Pusher(git, branch, gh.remote).delete()
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
        if not problem and not failing and holder is not None:
            # Green carrier first (#13400): VERIFIED green on its exact head over live main, extra gates
            # satisfied, so it merges now, before strict_lane runs below — never held for the holder and
            # voided by its merge (#13853 / #13806). The holder waits one tick and is update-branched,
            # inheriting its deferral record through the first parent, so no further carrier is built
            # under it. Before merging, withdraw any `merge-train` success the App left on the holder's
            # head (a refused merge earlier): auto-merge must not act on it while main moves under it.
            hview = next((v for _st, v in strict if v["number"] == holder), None)
            if hview is not None and not shadow:
                hstate = gh.app_statuses(hview["head_sha"]).get(STATUS_CONTEXT, ("", ""))[0]
                if hstate == "success":
                    gh.post_status(hview["head_sha"], "pending", f"strict lane: green carrier #{carrier} merges first — next tick")
            log(f"carrier #{carrier}: green — merging before strict-lane #{holder} (green carrier first)")
        acted = True
        if problem:
            log(f"::warning::carrier #{carrier}: {problem}")
            summary["rejected"].append(carrier)
        elif not failing:
            expected = live_main
            for n, head in chain[1]:
                if not live_source_intent(gh, n, head):
                    log(f"#{n} source intent or head changed since the train was verified; stopping")
                    break
                if not shadow and gh.main_sha() != expected:
                    log("main advanced by a foreign merge; stopping, the next tick rebuilds")
                    break
                gh.post_status(head, "success", f"verified on train over main {chain[0][:12]}", source_number=n)
                if shadow:
                    continue
                merged = gh.merge(n, head)
                if not merged:
                    log(f"#{n} did not merge; stopping")
                    break
                expected = merged
                summary["merged"].append(n)
        elif not [c for c in failing if c not in main_red]:
            # Red only on contexts main itself is red on: says nothing about any member — never a culprit,
            # never a bisect (a bisect ends in a culprit). Closed with no verdict; nobody is blamed.
            log(f"::warning::carrier #{carrier}: red only on {', '.join(failing)}, red on main {chain[0][:12]} too — inconclusive")
            summary["inconclusive"].append(carrier)
        else:
            members = chain[1]
            failing = [c for c in failing if c not in main_red]  # blame names only what main is green on
            if len(members) == 1:
                n, head = members[0]
                gh.post_status(head, "failure", culprit_description(failing, chain[0]))
                gh.comment(n, CULPRIT_MESSAGE)
                summary["culprits"].append(n)
            else:
                for _n, head in members:
                    gh.post_status(head, "pending", f"bisect size {max(1, len(members) // 2)}")
                summary["bisected"].append([n for n, _ in members])
        gh.close_carrier(carrier)
        if TRAIN_BRANCH_RE.fullmatch(branch):
            Pusher(git, branch, gh.remote).delete()
    if acted:  # a carrier was judged: main or member statuses may have changed — read everything again
        base, regen_text, strict, red, cands, apps, main_red, main_cache = settle_view(gh, git, repo, required)
        holder, timed_out = strict_reservation(git, base, strict, shadow,
                                               fixes=strict_fixes(gh, git, base, strict, main_red, main_cache),
                                           activity=gh.ci_activity)
    summary["main_red"] = sorted(main_red)
    fragments = regen_inventory_fragments(regen_text)
    fast, train_waiting = partition(git, base, cands, fragments)
    yield_why = train_turn(gh, git, base, train_waiting,
                           any(is_carrier(p, repo) and carrier_holds_strict(p) for p in gh.open_prs()))
    if timed_out:
        summary["timed_out"] = timed_out
        # Released: never reserving again on that head, and served after every other strict-lane PR.
        strict.sort(key=lambda sv: sv[1]["number"] in timed_out)
    # Strict lane first: an update-branch it issues starts a reservation THIS tick, so the fast lane
    # below must already see it.
    strict_lane(gh, git, base, strict, carrier_in_flight, shadow, summary, main_red, main_cache, holder, timed_out,
                yield_why)
    if (holder is not None and not carrier_in_flight and not summary["updated"] and not summary["strict"]
            and train_waiting and not main_red and not any(is_carrier(p, repo) for p in gh.open_prs())
            and deferral_state(gh, git, strict, holder) == "open"):
        # Bounded deferral (#13400): the running reservation lets ONE carrier be built from green
        # candidates during its CI; publish records the yield on the strict head (DEFER_CONTEXT).
        log(f"strict-lane #{holder} CI running, no deferral yet: a carrier may be built during it (bounded deferral)")
        summary["in_flight"] = False
        summary["deferral_window"] = holder
    elif (holder is None and len(summary["updated"]) == 1 and not carrier_in_flight and not summary["strict"]
            and train_waiting and not main_red and not any(is_carrier(p, repo) for p in gh.open_prs())
            and update_opens_deferral(gh, git, strict, summary["updated"][0])):
        # Overlap from the update tick (#13400): the update-branch just issued starts a reservation over
        # THIS main, and its one deferral is still unused, so the carrier is built over the same main in
        # this tick instead of the next one. Measured 2026-10-26 16:06-17:14Z: every update-branch tick
        # reported in_flight and built nothing, so the fast lane lost one whole tick per reservation.
        # Publish records the yield on the updated head (record_deferral: head contains the carrier's
        # base, CI pending), which then inherits it through its first parent on any re-update — the same
        # one-yield bound as above, so the strict PR is never pre-empted twice.
        n = summary["updated"][0]
        log(f"strict-lane #{n} update-branch requested over {base[:12]}, no deferral yet: a carrier may be built "
            f"over the same main during its CI (bounded deferral)")
        summary["in_flight"] = False
        summary["deferral_window"] = n
    # Fast lane, from live data only — nothing here reads a stored classification. Held while main is
    # reserved for a strict-lane PR (or was just moved by one): GitHub auto-merge acts on this status,
    # and a merge now would put the reserved head behind main again.
    hold = (f"main reserved for strict-lane #{holder}" if holder is not None
            else "strict-lane update-branch requested" if summary["updated"]
            else "a strict-lane PR merged; main moved" if summary["strict"] else None)
    for m in fast:
        if hold:
            log(f"#{m.number:>6} fast      held: {hold} — next tick")
            continue
        if apps.get(m.number, {}).get(STATUS_CONTEXT, ("",))[0] != "success":
            gh.post_status(m.head_sha, "success", f"fast lane: disjoint from main {base[:12]} and the batch",
                           source_number=m.number)
        summary["fast"].append(m.number)
    stale_red_refresh(gh, git, base, red, shadow, summary)
    return summary


def halve_without_verdict(gh, git, branch: str, sha: str) -> list[int]:
    """A carrier closed with NO verdict because a gate overran its budget or was not evaluated
    (#13400): halve the next batch (the ordinary `bisect size N` status) WITHOUT blaming anyone.
    Measured 2026-10-24: security-checklist-money-path judges every member's body in turn, so its time
    grows with the train; the same five members were rebuilt into four carriers (#13768 #13783 #13786
    #13791, ~4.5 h of CI) and each overran the same 20 s budget — rebuilding the identical batch is a
    livelock, a smaller one converges. The status only caps the next batch size: it is no verdict, the
    smaller carrier still needs every required context green, and a single member is never halved.
    Anything unreadable (branch moved, not our own chain) halves nothing — today's behaviour."""
    if git("fetch", "-q", "origin", f"+refs/heads/{branch}:refs/merge-train/settle", check=False).returncode != 0:
        return []
    if git("rev-parse", "refs/merge-train/settle", check=False).stdout.strip() != sha:
        return []
    chain = parse_own_chain(git, sha)
    if chain is None or len(chain[1]) < 2:
        return []
    for _n, head in chain[1]:
        gh.post_status(head, "pending", f"bisect size {len(chain[1]) // 2}")
    return [n for n, _ in chain[1]]


def settle_view(gh, git, repo: str, required: list[str]):
    """Live main and every armed PR judged against it: (base, regen_text, strict, red, cands, apps).
    Planning only, so its reads are memoised; every write acts on a live re-read (deciding)."""
    with planning_reads():
        return _settle_view(gh, git, repo, required)


def _settle_view(gh, git, repo: str, required: list[str]):
    base = live_base(gh, git)
    regen_text = main_regen_text(git, base)
    strict: list = []
    red: list = []
    main_cache: dict = {}
    main_red = main_red_contexts(gh, git, required, base, main_cache)
    if main_red:
        log(f"::notice::main {base[:12]} is red on {', '.join(sorted(main_red))}: no update-branch except onto a fix")
    cands, apps = live_candidates(gh, git, repo, required, base, Untrainable(regen_text), strict=strict, red=red,
                                  main_red=main_red)
    cands = promote_fixes(gh, git, repo, base, cands, strict, main_red, main_cache)
    return base, regen_text, strict, red, cands, apps, main_red, main_cache


def promote_fixes(gh, git, repo: str, base: str, cands: list, strict: list, main_red: dict[str, str],
                  cache: dict | None = None) -> list:
    """Main red (ADR-0343): a train/fast-lane candidate that demonstrably FIXES a main-red context (the
    same test as the strict lane's, fix_contexts) leaves the train and the fast lane and joins the strict
    list as a green fix, so strict_lane serves it first, alone: update-branch, then merge only the
    combined head fix_head_state verified. Returns the candidates that stay. No-op while main is green."""
    if not main_red:
        return cands
    keep = []
    for m in cands:
        view = pr_view(repo, gh.pr(m.number), m.files)
        if view["head_sha"] != m.head_sha:
            keep.append(m)
            continue
        fixed = fix_contexts(gh, git, base, view, main_red, cache)
        if not fixed:
            keep.append(m)
            continue
        view["strict_why"] = f"fix for {', '.join(fixed)} red on main {base[:12]}"
        log(f"#{m.number:>6} fix       green over a base red on {', '.join(fixed)}: served first, alone, update then verify")
        strict.append(("green", view))
    return keep


def strict_fixes(gh, git, base: str, strict: list, main_red: dict[str, str], cache: dict | None = None) -> dict[int, list[str]]:
    """Green strict-lane PRs that demonstrably fix a main-red context (see fix_contexts): number -> contexts."""
    if not main_red:
        return {}
    out: dict[int, list[str]] = {}
    for state, view in strict:
        if state == "green":
            fixed = view["fix"] if "fix" in view else fix_contexts(gh, git, base, view, main_red, cache)
            if fixed:
                out[view["number"]] = fixed
    return out


def last_merge_was_strict(gh, git, base: str) -> bool:
    """True when main's head is the squash of a PR this App merged through the strict lane: its
    head carries the App's own `strict lane:` status. Anything unreadable counts as not strict."""
    m = SQUASH_PR_RE.search(git("show", "-s", "--format=%s", base, check=False).stdout.strip())
    if not m:
        return False
    try:
        head = gh.pr(int(m.group(1)))["head"]["sha"]
    except (KeyError, TypeError, ValueError):
        return False
    return gh.app_statuses(head).get(STATUS_CONTEXT, ("", ""))[1].startswith("strict lane:")


def carrier_age_s(p: dict, now: float | None = None) -> float | None:
    """Seconds since the carrier PR was opened (GitHub's created_at), or None when unreadable."""
    try:
        t = datetime.datetime.strptime(p.get("created_at") or "", "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)
    except ValueError:
        return None
    return (time.time() if now is None else now) - t.timestamp()


def carrier_holds_strict(p: dict, now: float | None = None) -> bool:
    """A running carrier keeps the strict lane waiting only while younger than CARRIER_PRIORITY_S. An
    unreadable age holds (today's behaviour); a carrier past the bound releases the strict lane."""
    age = carrier_age_s(p, now)
    return age is None or age <= CARRIER_PRIORITY_S


def train_turn(gh, git, base: str, train_waiting: list, carrier_open: bool, now: float | None = None) -> str | None:
    """Why the strict lane must not START a reservation (update-branch) this tick, or None. Lane
    fairness (ADR-0343): (1) an open carrier is the train's in-flight turn — a reservation started
    under it holds the carrier once green and then voids it by merging (#13529, 2026-10-21); (2) right
    after a strict-lane merge, while train candidates wait, the next turn is the train's, bounded by
    TRAIN_TURN_S. Either ends by itself: the carrier settles, or a train merges (main's head is no
    longer a strict merge), or the turn expires. A reservation already running is never affected."""
    if carrier_open:
        return "a train carrier is open over main"
    if not train_waiting or not last_merge_was_strict(gh, git, base):
        return None
    now = time.time() if now is None else now
    ct = git("show", "-s", "--format=%ct", base, check=False).stdout.strip()
    if not ct.isdigit() or now - int(ct) > TRAIN_TURN_S:
        return None
    return f"train's turn after a strict-lane merge ({len(train_waiting)} train candidate(s) waiting)"


def reservation_expired(git, head: str, now: float, activity=None, green: bool = False) -> str | None:
    """Why the reservation on `head` is over, or None while its CI is live (ADR-0343, #13400).
    Expired when the head is future-dated (the committer time is author-supplied, #13485), older
    than RESERVATION_CEILING_S, or idle: nothing on it started or completed for RESERVATION_STALL_S.
    The idle clock starts at the committer time, so a CI that never starts after update-branch is
    a stall too. `activity(head)` = newest check-run started_at/completed_at (epoch) or None.
    The stall rule applies only while CI is PENDING: a `green` head is finished, not stalled, and
    merges this tick (#13608 was released ~15 min after going green). A green head keeps the
    future-stamp guard and the ceiling: a current green holder merges on the tick it goes green, so
    it reaches the ceiling only if its merge is refused for hours, and then releasing is right."""
    ct = git("show", "-s", "--format=%ct", head, check=False).stdout.strip()
    if not ct.isdigit() or now < int(ct):
        return "head committed in the future"
    if now - int(ct) > RESERVATION_CEILING_S:
        return f"> {RESERVATION_CEILING_S // 60} min ceiling"
    last = int(ct)
    seen = activity(head) if activity else None
    if seen is not None:
        last = max(last, min(seen, now))
    if not green and now - last > RESERVATION_STALL_S:
        return f"CI stalled: nothing started or completed for > {RESERVATION_STALL_S // 60} min"
    return None


def deferral_used(gh, git, head: str) -> bool:
    """The strict head, or the head it was update-branched from (first parent), already yielded."""
    parents = git("rev-list", "--parents", "-n", "1", head, check=False).stdout.split()
    return any(gh.app_statuses(sha).get(DEFER_CONTEXT, ("", ""))[0] == "success" for sha in [head] + parents[1:2])


def deferral_state(gh, git, strict: list, holder: int) -> str | None:
    """Bounded deferral (ADR-0343, #13400) for the reservation holder: "open" — its CI is still
    RUNNING and it never yielded, so ONE carrier may be built under it; "granted" — a carrier was
    published under it (DEFER_CONTEXT on THIS head), so that green carrier may merge past it; None —
    held. A VERIFIED green carrier is never held at all (green carrier first, #13400): it merges before
    strict_lane runs, even past a green head. The carrier's merge puts the head behind main and its
    update-branch inherits the record through the first parent: after that one yield no further
    carrier is built under it, so the strict PR has priority until it merges or fails."""
    view = next((v for st, v in strict if v["number"] == holder and st == "pending"), None)
    if view is None:
        return None
    head = view["head_sha"]
    if gh.app_statuses(head).get(DEFER_CONTEXT, ("", ""))[0] == "success":
        return "granted"
    return None if deferral_used(gh, git, head) else "open"


def update_opens_deferral(gh, git, strict: list, n: int) -> bool:
    """The strict PR `n` was update-branched this tick: may a carrier be built under its new head? Only
    when the head it was updated FROM never yielded (deferral_used: that head or its first parent). The
    new head's first parent is that head, so this is exactly deferral_state's "open" one tick early."""
    view = next((v for _st, v in strict if v["number"] == n), None)
    return view is not None and not deferral_used(gh, git, view["head_sha"])


def record_deferral(gh, git, repo: str, base: str, carrier_branch: str) -> list[int]:
    """Publish: a carrier published while an armed PR's up-to-date head is still running CI consumes
    that head's one deferral. Read from live data (head contains live main, a required context still
    pending); settle opened the window and judges the carrier against the record."""
    required = [c for c in gh.required() if c != STATUS_CONTEXT]
    out = []
    for p in gh.open_prs():
        if is_carrier(p, repo) or not p.get("auto_merge") or p.get("draft"):
            continue
        n, head = p["number"], p["head"]["sha"]
        checks = gh.checks(head)
        if not any(checks.get(c, None) is None for c in required) or any(
                checks.get(c) == "failure" for c in required):
            continue
        git("fetch", "-q", "origin", f"+{gh.head_refspec(n)}:refs/merge-train/pr-{n}", check=False)
        if git("merge-base", "--is-ancestor", base, head, check=False).returncode != 0 or deferral_used(gh, git, head):
            continue
        gh.post_status(head, "success", f"strict lane: yielded once to carrier {carrier_branch}; priority from here",
                       context=DEFER_CONTEXT)
        log(f"#{n:>6} strict    deferral recorded: carrier {carrier_branch} published during its CI")
        out.append(n)
    return out


def strict_reservation(git, base: str, strict: list, shadow: bool = False, now: float | None = None,
                       fixes: dict | None = None, activity=None) -> tuple[int | None, list[int]]:
    """(holder, timed_out). The holder is the oldest strict-lane PR whose head already contains live
    main and whose CI is still running with nothing failed — or has just gone green, so it merges this
    tick: main is reserved for it, so neither the
    fast lane nor a train nor another strict-lane update may move or contend for main until it
    settles (merged, failed, or timed out). A green holder that cannot merge (merge refused) is
    bounded by the ceiling, never by the stall rule. A reservation whose CI stalled, or that passed the ceiling
    (reservation_expired), is released and listed in timed_out.
    Main red (ADR-0343): a "waiting" PR never holds (it cannot go green until main is fixed). A FIX
    (`fixes`) holds only once its combined head contains live main, like every other PR — first, as
    strict_lane serves it first; behind main it holds nothing, since it never merges from there."""
    now = time.time() if now is None else now
    holder, timed_out = None, []
    fixes = fixes or {}
    ordered = sorted(strict, key=lambda sv: 0 if sv[1]["number"] in fixes else 1)
    for state, view in ordered:
        n, head = view["number"], view["head_sha"]
        if state not in ("pending", "green"):
            continue
        if git("merge-base", "--is-ancestor", base, head, check=False).returncode != 0:
            continue
        if state == "green" and shadow:
            continue  # shadow never merges it, so it must not hold anything
        why = reservation_expired(git, head, now, activity, green=state == "green")
        if why:
            log(f"#{n:>6} strict    reservation timed out ({why}): released, back of the queue")
            timed_out.append(n)
        elif holder is None:
            holder = n
    return holder, timed_out


def stale_red_candidate(pr: dict, member_required: list[str], checks: dict[str, str | None]) -> list[str]:
    """The required contexts (merge-train aside) that are FAILURE on a same-repo PR that agent-pr-guard
    did not refuse. Empty = not a stale-red candidate. live_candidates only ever passes armed, non-draft
    PRs here; attribution to main is decided later, against main's own CI."""
    if not pr.get("same_repo", True):
        return []
    for name, state in checks.items():
        if GUARD_CONTEXT_HINT in name and state not in OK_STATES and state is not None:
            return []
    return [c for c in member_required if c != STATUS_CONTEXT and checks.get(c) == "failure"]


def main_ci_result(gh, git, ctx: str, sha: str, cache: dict | None = None, depth: int = STALE_RED_WALK) -> tuple[str, str] | None:
    """main's own CI verdict for `ctx` at `sha`: the push run's result on that commit, or on the nearest
    first-parent ancestor that has a completed one. (commit, conclusion) or None."""
    walk = git("rev-list", "--first-parent", f"--max-count={depth}", sha, check=False).stdout.split()
    for c in walk:
        if cache is not None and c in cache:
            res = cache[c]
        else:
            res = gh.checks(c)
            if cache is not None:
                cache[c] = res
        if res.get(ctx) is not None and res[ctx] not in INCONCLUSIVE_STATES:   # a cancelled run says nothing about main
            return c, res[ctx]
    return None


def stale_red_refresh(gh, git, base: str, red: list, shadow: bool, summary: dict) -> None:
    """Stale-red refresh (ADR-0343): an armed PR left red only because MAIN was red when its CI ran gets
    ONE update-branch once main is green again. Every failing required context must have failed on main
    at the PR's merge-base (or its nearest ancestor with a result) and be green on the current tip; and
    the PR must be behind main. After the refresh its merge-base IS the fixed main, so a PR still red
    then fails the attribution test and is never refreshed again — no loop. Oldest first, at most
    STALE_RED_CAP per tick. Git queries and the API only; no PR code runs."""
    summary.setdefault("refreshed", [])
    cache: dict = {}
    for view, failing in red:
        if len(summary["refreshed"]) >= STALE_RED_CAP:
            break
        n, head = view["number"], view["head_sha"]
        if git("merge-base", "--is-ancestor", base, head, check=False).returncode == 0:
            log(f"#{n:>6} stale-red not behind main — red on its own merits")
            continue
        mb = git("merge-base", head, base, check=False).stdout.strip()
        if not mb:
            continue
        why = None
        for ctx in failing:
            at_base = main_ci_result(gh, git, ctx, mb, cache)
            if not at_base or at_base[1] != "failure":
                why = f"{ctx} was not red on main at its base {mb[:12]} — its own defect"
                break
            at_tip = main_ci_result(gh, git, ctx, base, cache)   # one walk per tick, not one per PR
            if not at_tip or at_tip[1] not in OK_STATES:
                why = f"{ctx} is not green on main yet — no point refreshing"
                break
        if why:
            log(f"#{n:>6} stale-red skip: {why}")
            continue
        msg = f"stale-red refresh: {', '.join(failing)} was red on main {mb[:12]}, green on {base[:12]}"
        if shadow:
            log(f"#{n:>6} {msg} (shadow: no update)")
            continue
        ok = gh.update_branch(n, head)
        log(f"#{n:>6} {msg}: update-branch {'requested' if ok else 'refused'}")
        if ok:
            summary["refreshed"].append(n)


def fix_contexts(gh, git, base: str, view: dict, main_red: dict[str, str], cache: dict | None = None) -> list[str]:
    """The main-red contexts this green PR demonstrably fixes: red in main's CI at the PR's own
    merge-base (or nearest ancestor with a verdict), green on the PR head. A PR that went green over a
    base that was still green proves nothing about the breakage and is NOT a fix."""
    mb = git("merge-base", view["head_sha"], base, check=False).stdout.strip()
    if not mb:
        return []
    out = []
    extra = train_extra_gates(git, base) or {}
    for ctx in sorted(main_red):
        if ctx in extra and gh.extra_checks(view["head_sha"], {ctx: extra[ctx]}).get(ctx) not in OK_STATES:
            continue   # an extra gate is not required: only its own pinned GREEN on the head proves a fix
        at_base = main_ci_result(gh, git, ctx, mb, cache)
        if at_base and at_base[1] == "failure":
            out.append(ctx)
    return out


def fix_head_state(gh, git, base: str, view: dict, member: list[str], checks: dict[str, str | None],
                   app: dict | None, modes: list[str], main_red: dict[str, str] | None,
                   cache: dict | None = None, extra: dict[str, str] | None = None) -> tuple[str | None, list[str], str]:
    """(state, fix set, why) for a fix PR whose head is red ONLY where main is (ADR-0343, update then
    verify). A fix is never merged untested against main: strict_lane update-branches it, and only the
    COMBINED head may merge. The fix set is what the PR claimed before that update — fix_contexts of the
    first parent of its update-branch merge (or of the head itself while it is still behind), each
    green there. On the head being judged, every context in the fix set must be green; every other
    required context green, or `failure` on main too (no worse than main); anything else refuses.
    Everything else in `qualifies` (state, draft, fork, agent-pr-guard, merge-train failure) still
    applies. Returns (None, [], why) when the PR is not such a fix; strict_lane_state then decides."""
    if not main_red:
        return None, [], "main is green"
    if any(unsafe_path(f) for f in view.get("files") or []) or any(m in SPECIAL_MODES for m in modes):
        return None, [], "unsafe path or mode"
    head = view["head_sha"]
    claim_from = head
    if git("merge-base", "--is-ancestor", base, head, check=False).returncode == 0:
        parents = git("rev-list", "--parents", "-n", "1", head, check=False).stdout.split()
        if len(parents) != 3 or git("merge-base", "--is-ancestor", base, parents[1], check=False).returncode == 0:
            return None, [], "up to date, and not the update of a fix behind main"
        claim_from = parents[1]
    claim_checks = checks if claim_from == head else (pinned_checks(gh, claim_from, extra) if extra else gh.checks(claim_from))
    claimed = [c for c in fix_contexts(gh, git, base, {**view, "head_sha": claim_from}, main_red, cache)
               if claim_checks.get(c) in OK_STATES]
    if not claimed:
        return None, [], "claims no main-red context"
    masked = {c: ("success" if c in main_red and c not in claimed and v == "failure" else v) for c, v in checks.items()}
    ok, why = qualifies(view, member, masked, app, untrainable=lambda _f: False, extra=extra)
    if ok:
        return "green", claimed, f"fix for {', '.join(claimed)} red on main {base[:12]}"
    if why.startswith(("required context pending", "required context never reported", "extra gate pending")) and not any(
            v is not None and v not in OK_STATES for c, v in masked.items() if c in member):
        return "pending", claimed, why
    return None, [], why


def strict_lane(gh, git, base: str, strict: list, carrier_in_flight: bool, shadow: bool, summary: dict,
                main_red: dict[str, str] | None = None, cache: dict | None = None,
                holder: int | None = None, timed_out: list | None = None, yield_why: str | None = None) -> None:
    """Manual-lane PRs merge exactly as strict up-to-date allowed: CI green on a head that already
    contains live main. Oldest first. Up to date + green -> status + merge pinned to that head; up to
    date + CI running -> main is RESERVED (in_flight, so no train is built under it); green but
    behind -> update-branch for the oldest one only, and none while another is reserved, so CI is never
    flooded. Nothing here executes PR code: ancestry is a git query, the rest is the API.

    Main red (ADR-0343, main-red awareness): a PR whose only failures are red on main too is
    "waiting" — skipped, never reserving. No update-branch is issued onto a red main: it could only
    manufacture main-caused red. A green PR that was red-based on a main-red context is the FIX: it
    is ranked first and update-branched like any other strict PR, holding the reservation while CI runs
    on the combined head; it merges only that head, verified by fix_head_state, pinned to its sha."""
    summary.setdefault("strict", [])
    summary.setdefault("updated", [])
    summary.setdefault("waiting", [])
    main_red = main_red or {}
    fixes: dict[int, list[str]] = {}
    for state, view in strict:
        if state == "waiting":  # never served, never reserving: logged before any merge can end the loop
            log(f"#{view['number']:>6} strict    {view.get('strict_why') or main_red_reason(list(main_red), main_red)}")
            summary["waiting"].append(view["number"])
    if main_red:
        fixes = strict_fixes(gh, git, base, strict, main_red, cache)
        strict = sorted(strict, key=lambda sv: 0 if sv[1]["number"] in fixes else 1)  # stable: oldest first within
    updated = holder is not None  # main reserved (strict_reservation): no other strict-lane action while it runs
    for state, view in strict:
        n, head = view["number"], view["head_sha"]
        if holder is not None and n != holder:
            log(f"#{n:>6} strict    held: main reserved for strict-lane #{holder}")
            continue
        if state == "waiting":
            continue
        current = git("merge-base", "--is-ancestor", base, head, check=False).returncode == 0
        if current and state == "pending" and n in (timed_out or []):
            continue
        fix = fixes.get(n)
        if current and state == "pending":
            log(f"#{n:>6} strict    up to date, CI running: main reserved for it")
            summary["in_flight"] = True
            updated = True  # one strict-lane PR in progress at a time: no other update while it runs
            continue
        # Pre-empted (#13400): a head that yielded to a carrier (DEFER_CONTEXT on it) and is now behind
        # main because that carrier merged first. Its running CI can no longer merge anything, so it is
        # re-updated now rather than after that worthless CI finishes — otherwise further carriers are
        # built while it runs behind main and the one-yield bound does not hold.
        preempted = (not current and state == "pending"
                     and gh.app_statuses(head).get(DEFER_CONTEXT, ("", ""))[0] == "success")
        if state != "green" and not preempted:
            continue
        if not current:
            if main_red and not fix:
                ctxs = sorted(main_red)
                log(f"#{n:>6} strict    behind main, not updated: {main_red_reason(ctxs, main_red)}")
                continue
            if updated or shadow:
                continue
            if yield_why:
                log(f"#{n:>6} strict    behind main, not updated: {yield_why} — next tick")
                updated = True  # one log line: no younger strict PR is updated either
                continue
            # Conflict with main (#13400): decided locally by merge-tree, before any API call. A strict
            # PR that cannot be merged with main holds nothing — it is skipped, told once per head that it
            # conflicts, and the NEXT strict PR is served in this same tick. GitHub's update-branch would
            # only refuse it again: #13739 was re-requested on every tick for ~11 h (2026-10-26) while
            # the installation token ran out (403 at 16:39Z killed a deferral carrier's build).
            told = (view.get("app") or {}).get(NOTICE_CONTEXT, ("", ""))[1]
            if told.startswith(f"{REFUSED_PREFIX}{base[:12]}"):
                # Quota (#13400): GitHub already refused THIS head over THIS main and nothing changed,
                # so asking again can only be refused again. A new head or a moved main asks again.
                log(f"#{n:>6} strict    behind main {base[:12]}: update-branch already refused for this head "
                    f"over this main — not asked again, next strict PR served")
                continue
            clash = conflict_paths(git, base, head)
            if clash and NOTICE_CONTEXT in (view.get("app") or {}):
                log(f"#{n:>6} strict    behind main {base[:12]}, conflicts with it on {len(clash)} path(s): "
                    f"already told on this head — next strict PR served")
                summary.setdefault("conflicts", []).append(n)
                continue
            if clash:
                log(f"#{n:>6} strict    behind main {base[:12]}, conflicts with it on {len(clash)} path(s): "
                    f"not updated, reservation released — next strict PR served")
                notice_once(gh, n, head, f"{CONFLICT_PREFIX}{base[:12]}: strict lane — merge main into the branch",
                            notice_text(STRICT_CONFLICT_COMMENT, clash))
                summary.setdefault("conflicts", []).append(n)
                continue
            # A fix too: never merged untested against main. Its combined head must verify (fix_head_state).
            ok = gh.update_branch(n, head)
            log(f"#{n:>6} strict    behind main {base[:12]}{' (fix for ' + ', '.join(fix) + ')' if fix else ''}: "
                f"update-branch {'requested' if ok else 'refused'}")
            if ok:
                updated = True
                summary["in_flight"] = True  # the reservation starts now: no train is built under it
                summary["updated"].append(n)
            else:
                notice_once(gh, n, head, f"{REFUSED_PREFIX}{base[:12]}: merge main into the branch",
                            notice_text(REFUSED_COMMENT, conflict_paths(git, base, head)))
            continue
        if carrier_in_flight:
            log(f"#{n:>6} strict    green, but a train is in flight over main — next tick")
            continue
        if shadow:
            log(f"#{n:>6} strict    green and up to date{' fix for ' + ', '.join(fix) if fix else ''} (shadow: no status, no merge)")
            continue
        if gh.main_sha() != base:
            log(f"#{n:>6} strict    main moved since it was read — next tick")
            break
        if not live_source_intent(gh, n, head):
            log(f"#{n:>6} strict    source intent or head changed — next tick")
            continue
        if fix:
            desc = f"strict lane: fix for {', '.join(fix)} red on main {base[:12]}, verified on its up-to-date head"
            log(f"#{n:>6} strict    fix for {', '.join(fix)} red on main {base[:12]}: combined head verified, merging")
        else:
            desc = f"strict lane: green on an up-to-date head over main {base[:12]}"
        gh.post_status(head, "success", desc, source_number=n)
        merged = gh.merge(n, head)
        if not merged:
            log(f"#{n:>6} strict    did not merge; stopping")
            break
        summary["strict"].append(n)
        summary["merged"].append(n)
        break  # main moved: every other strict-lane head is now behind it


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
    ("event-driven: main moved", "push:"),
    ("event-driven: a required-context producer finished", "workflow_run:"),
    ("event-driven: a PR was armed", "auto_merge_enabled"),
]
_ISOLATION = [("script from origin/main", "show origin/main:.github/scripts/merge-train.py"),
              ("isolated python", "python3 -I -S"), ("no system git config", "GIT_CONFIG_NOSYSTEM: \"1\""),
              ("no global git config", "GIT_CONFIG_GLOBAL: /dev/null"), ("no credential left", "persist-credentials: false"),
              ("mints the App token", "actions/create-github-app-token@")]
JOB_MUST = {
    "build": [("build runs build", "merge-train.py build"), ("build: no credential left", "persist-credentials: false"),
              ("build regenerates with opa", "openpolicyagent.org/downloads/"),
              ("build hands over a bundle", "actions/upload-artifact@"),
              ("build hands over its drops (data, for publish's notices)", "merge-train-bundle/drops.txt"),
              ("build reports drops", "has_drops: ${{ steps.build.outputs.has_drops }}")],
    "publish": [(f"publish: {a}", b) for a, b in _ISOLATION] + [("publish takes the bundle", "actions/download-artifact@"),
                                                              ("publish runs publish", "\" publish"),
                                                              ("publish also runs on drops alone (notices)",
                                                               "needs.build.outputs.has_drops == 'true'")],
    "settle": [(f"settle: {a}", b) for a, b in _ISOLATION] + [("settle runs settle", "\" settle"),
                                                           ("settle gets the App id for the ruleset guard", "GITOPS_APP_ID: ${{ secrets.GITOPS_APP_ID }}")],
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


def owner_approval_declaration_errors(text: str) -> list[str]:
    code = "\n".join(ln for ln in text.splitlines() if not ln.lstrip().startswith("#"))
    sec = job_sections(code).get("decide", "")
    guard = ("if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' "
             "&& github.actor == github.repository_owner && github.triggering_actor == github.repository_owner "
             "&& github.run_attempt == 1")
    must = [guard, "ref: main", "persist-credentials: false", "actions/create-github-app-token@",
            "show HEAD:.github/scripts/merge-train.py", "python3 -I -S", '" owner-approval',
            '--pr "$OWNER_PR" --head "$OWNER_HEAD" --decision "$OWNER_DECISION"']
    errors = [f"owner approval writer missing {needle}" for needle in must if needle not in sec]
    if "group: merge-train-owner-approval-${{ github.event_name }}-${{ github.ref }}-${{ github.actor }}-${{ inputs.pr }}" not in code or "cancel-in-progress: false" not in code:
        errors.append("owner decisions must serialize separately from untrusted PR verification")
    if sec.count("actions/checkout@") != 1 or sec.count("ref: main") != 1:
        errors.append("owner approval writer must check out main exactly once")
    for needle in (r"^[1-9][0-9]*$", r"^[0-9a-f]{40}$"):
        if needle not in sec:
            errors.append(f"owner approval writer must validate inputs with {needle}")
    if sec.index("Validate decision inputs") > sec.index("actions/checkout@"):
        errors.append("owner approval input validation must run before anything else")
    if sec.count("run:") != 4:
        errors.append("owner approval writer must have only validation, revoke-first, decision and summary commands")
    for forbidden in ("github.head_ref", "github.event.", "download-artifact", "gh pr merge", "gh pr review",
                      "contents: write", "statuses: write", "pull_request_target:"):
        if forbidden in code:
            errors.append(f"owner approval writer forbids {forbidden}")
    if "python3 .github/" in sec:
        errors.append("owner approval writer must run only its isolated main script")
    return errors


def check_declaration() -> int:
    if not WORKFLOW.exists():
        print(f"::error::{WORKFLOW} missing")
        print("SUBJECTS=0")
        return 1
    text = WORKFLOW.read_text()
    code = "\n".join(ln for ln in text.splitlines() if not ln.lstrip().startswith("#"))
    jobs = job_sections(code)
    fails = [label for label, needle in TOP if needle not in code]
    if not re.search(r'^\s*MERGE_TRAIN_SHADOW: "(true|false)"\s*$', code, re.MULTILINE):
        fails.append('MERGE_TRAIN_SHADOW must be declared as "true" or "false"')
    # The safety is a runtime property: settle must consult the live ruleset before merging, whatever the flag says.
    guard_src = inspect.getsource(mode_settle)
    if "gh.merge_guard()" not in guard_src or inspect.getsource(ruleset_guard).count("integration_id") < 1:
        fails.append("settle must gate merging on the live ruleset (merge_guard / ruleset_guard)")
    # The event-driven triggers (push, workflow_run, pull_request_target) carry no authority only while
    # nothing reads their payload and nothing checks out anything but main.
    if "github.event." in code or "github.head_ref" in code:
        fails.append("no job may read the event payload (github.event.* / github.head_ref): triggers carry no authority")
    if code.count("actions/checkout@") != len(re.findall(r"^\s+ref: main\s*$", code, re.MULTILINE)):
        fails.append("every actions/checkout must pin `ref: main` (pull_request_target/workflow_run run with secrets)")
    if "contents: write" in code:
        fails.append("GITHUB_TOKEN must stay read-only (writes use the App token)")
    for job in JOB_MUST:
        sec = jobs.get(job)
        if sec is None:
            fails.append(f"job `{job}` missing")
            continue
        fails += [label for label, needle in JOB_MUST[job] if needle not in sec]
        fails += [label for label, needle in JOB_FORBID[job] if needle in sec]
    rules = pathlib.Path(RULES_FILE)
    if not parse_extra_gates(rules.read_text() if rules.exists() else ""):
        fails.append(f"{RULES_FILE}: merge_train.extra_gates must declare at least one check (parsed as empty)")
    fails += owner_approval_declaration_errors(OWNER_APPROVAL_WORKFLOW.read_text() if OWNER_APPROVAL_WORKFLOW.exists() else "")
    for f in fails:
        print(f"::error::merge-train.yml declaration: {f}")
    n = len(TOP) + 6 + sum(len(v) for v in JOB_MUST.values()) + sum(len(v) for v in JOB_FORBID.values())
    print(f"SUBJECTS={n}")
    print("merge-train declaration OK" if not fails else f"{len(fails)} declaration failure(s)")
    return 1 if fails else 0


# --------------------------------------------------------------------------- self-test
@contextlib.contextmanager
def _synthetic_clock(epoch: int = 1767225600):
    """Keep synthetic git commits and ticks on one explicit clock, independent of suite duration.

    Reservation scenarios advance tick time explicitly relative to their commit; elapsed runner
    time is not modeled CI inactivity. Production clocks and commit environments are unchanged.
    """
    original_time, original_env = time.time, base_env

    def dated_env():
        env = original_env()
        env.update({"GIT_AUTHOR_DATE": f"{epoch} +0000", "GIT_COMMITTER_DATE": f"{epoch} +0000"})
        return env

    time.time = lambda: epoch
    globals()["base_env"] = dated_env
    try:
        yield
    finally:
        time.time = original_time
        globals()["base_env"] = original_env


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
SYN_RULES = "merge_train:\n  extra_gates:\n    - {workflow: .github/workflows/security.yml, check: Trivy}\n"
SYN_REQUIRED = ["gates (data)", "Validate manifests", "derived", "issue-hygiene", STATUS_CONTEXT]
# The GENERATORS fragments make docs/, services/, count.txt and bad.txt protected, so PRs touching
# them ride trains (as real inventory sources do) instead of the fast lane; svc/ stays fast-lane.
SYN_INVENTORY = ('GENERATORS=(\n  "syn|python3 x|services/ docs/ count.txt bad.txt"\n)\n'
                 'DERIVED_OUTPUTS=(\n  "derived/*"\n)\n'
                 "  done < <(find openbank-infra/gitops -name 'gen-*opa-bundle*.sh' | sort)\n")
SYN_DEEP_GENERATOR = "openbank-infra/gitops/a/b/c/d/gen-x-opa-bundle.sh"
SYN_HEREDOC_SCRIPT = "openbank-infra/gitops/p/gen-p-opa-bundle.sh"
SYN_NEWLINE_GENERATOR = "openbank-infra/gitops/x/gen-a\nb-opa-bundle.sh"
SYN_SHADOW_MODULE = "openbank-infra/gitops/p/yaml.py"
SYN_BASELINE = ".github/scripts/x-baseline.txt"     # gate-input data inside a generator overlay root
SYN_GATE_CODE = ".github/scripts/check-k.py"        # reviewed gate code: rides a train, never with a regen commit
SYN_MAIN_GATE_CODE = ".github/scripts/check-m.py"   # gate code main already carries


class FakeGitHub:
    """A GitHub stand-in over a synthetic origin: PRs are local branches, CI is ci.py on train heads
    (PR heads report green, as their own CI did), merges are squashes onto origin's main."""

    def __init__(self, origin: str, scratch: str):
        self.origin, self.scratch, self.branch, self.run_url, self.remote = origin, scratch, "main", "http://run", origin
        self.repo = "o/r"
        self.prs: dict[int, dict] = {}
        self.statuses: dict[str, list[tuple[str, str, str, str]]] = {}
        self.comments: list[tuple[int, str]] = []
        self.merged: list[int] = []
        self.carriers: dict[int, dict] = {}
        self._ci: dict[str, dict] = {}
        self.foreign_after_merge: str | None = None
        self.head_checks: dict[str, dict] = {}   # PR head sha -> check overrides (red, pending, guard)
        self.pending_shas: set[str] = set()      # carrier heads whose CI is still running
        self.updates: list[int] = []             # update-branch calls, in order
        self.carrier_override: dict = {}         # carrier (train head) check overrides, e.g. cancelled
        self.main_override: dict = {}            # main tip check overrides, kept apart from carriers
        self.reruns: list[tuple[str, list[str]]] = []
        self.rerun_ok = True
        self.on_rerun = None                     # callable run after a rerun request (the rerun's outcome)
        self.main_sequence: dict[str, list] = {}  # main sha -> successive check overrides, one per read
        self.pinned: dict[str, dict[str, list]] = {}  # sha -> extra gate -> states of runs from the pinned workflow
        self.xgate_unfinished: set[str] = set()   # heads whose pinned workflow has not completed (absent = pending)
        self.gate_records: dict[str, list] = {}  # shard slug -> run-gates --json records of the carrier's CI
        self.inconclusive_reads = 0
        self.activity: dict[str, float] = {}   # sha -> newest check-run start/finish (ci_activity)
        # The live ruleset as the settle guard sees it: merge-train pinned to the App, strict off.
        self.app_id, self.rs_checks, self.rs_strict = "4242", [{"context": STATUS_CONTEXT, "integration_id": 4242}], False

    def merge_guard(self):
        return ruleset_guard([{"type": "required_status_checks", "parameters": {
            "strict_required_status_checks_policy": self.rs_strict, "required_status_checks": self.rs_checks}}], self.app_id)

    def g(self, *a, check=True):
        return sh(["git", "-c", "core.hooksPath=/dev/null", *a], cwd=self.origin, check=check, env=base_env())

    def add_pr(self, n: int, branch: str, created: str):
        self.prs[n] = {"number": n, "state": "open", "draft": False, "auto_merge": {"x": 1}, "title": branch, "body": "",
                       "created_at": created, "user": {"login": "someone"},
                       "head": {"ref": branch, "sha": self.g("rev-parse", branch).stdout.strip(), "repo": {"full_name": "o/r"}},
                       "base": {"ref": self.branch, "repo": {"full_name": self.repo}}}

    def head_refspec(self, n): return f"refs/heads/{self.prs[n]['head']['ref']}"
    def required(self): return list(SYN_REQUIRED)
    def main_sha(self): return self.g("rev-parse", "main").stdout.strip()
    def open_prs(self): return [p for p in list(self.prs.values()) + list(self.carriers.values()) if p["state"] == "open"]
    def pr(self, n): return self.prs[n]

    def checks(self, sha):
        if sha in self.pending_shas:
            out = {c: None for c in SYN_REQUIRED[:4]}
        elif any(p["head"]["sha"] == sha for p in self.prs.values()):
            out = {**{c: "success" for c in SYN_REQUIRED[:4]}, **self.head_checks.get(sha, {})}
        else:
            if sha not in self._ci:
                wt = tempfile.mkdtemp(dir=self.scratch)
                self.g("worktree", "add", "-q", "--detach", wt, sha)
                r = subprocess.run([sys.executable, "ci.py"], cwd=wt, text=True, capture_output=True)
                failing = [c for c in r.stdout.strip().split(",") if c]
                self.g("worktree", "remove", "--force", wt)
                self._ci[sha] = {**{c: ("failure" if c in failing else "success") for c in SYN_REQUIRED[:3]}, "issue-hygiene": "success"}
            # carrier_override models a carrier's CI and main_override main's tip, which is read too
            # (main_red_contexts, #13517): one shared override made every red-carrier fixture "red on main".
            override = self.main_override if sha == self.main_sha() else self.carrier_override
            out = {**self._ci[sha], **override, **self.head_checks.get(sha, {})}
            if self.main_sequence.get(sha):            # main CI finishing mid-tick: next read differs
                out.update(self.main_sequence[sha].pop(0))
        for ctx, state, _desc, _creator in reversed(self.statuses.get(sha, [])):
            out.setdefault(ctx, None if state == "pending" else state)
        return {c: v for c, v in out.items() if v != "absent"}   # "absent": the context never reported

    def ci_activity(self, sha):
        return self.activity.get(sha)

    def extra_checks(self, sha, gates):
        """Pinned runs: `self.pinned[sha]` = {name: [states]} from the declared workflow; with none set, a
        head's Trivy override stands for one pinned run (an unpinned spoof lives only in checks())."""
        if sha in self.pinned:
            return {n: extra_verdict(v) for n, v in self.pinned[sha].items() if n in gates}
        plain = dict(self.head_checks.get(sha, {}))   # not self.checks(): status reads are counted by tests
        if not any(p["head"]["sha"] == sha for p in self.prs.values()):
            plain = {**self.carrier_override, **plain}
        return {n: plain.get(n, "skipped") for n in gates if n in plain or sha not in self.xgate_unfinished}

    def app_statuses(self, sha):
        out = {}
        for ctx, state, desc, creator in reversed(self.statuses.get(sha, [])):
            if creator == BOT_LOGIN:
                out.setdefault(ctx, (state, desc))
        return out

    def post_status(self, sha, state, description, context=STATUS_CONTEXT, creator=BOT_LOGIN, *, source_number=None):
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

    def failures_inconclusive(self, sha, failing):
        self.inconclusive_reads += 1
        return gate_failures_inconclusive(list(failing), self.gate_records)

    def rerun_inconclusive(self, sha, contexts):
        self.reruns.append((sha, list(contexts)))
        if self.rerun_ok and self.on_rerun:
            self.on_rerun()
        return self.rerun_ok

    def update_branch(self, n, head):
        p = self.prs[n]
        if p["head"]["sha"] != head:
            return False
        # A fresh branch, so the fixture branch itself stays as cut for later cases.
        br = f"{p['head']['ref']}-upd{len(self.updates)}"
        self.g("checkout", "-q", "-f", "-B", br, head)
        self.g("merge", "-q", "--no-edit", "main")
        self.g("checkout", "-q", "-f", "main")
        p["head"]["ref"], p["head"]["sha"] = br, self.g("rev-parse", br).stdout.strip()
        self.updates.append(n)
        return True

    def create_carrier(self, branch):
        n = 9000 + len(self.carriers)
        self.carriers[n] = {"number": n, "state": "open", "draft": True, "user": {"login": BOT_LOGIN}, "created_at": "",
                            "head": {"ref": branch, "sha": self.g("rev-parse", branch).stdout.strip(), "repo": {"full_name": "o/r"}}}
        return n

    def close_carrier(self, n): self.carriers[n]["state"] = "closed"


class HttpFixture:
    """The GitHub REST API as `GitHub` sees it, served from a FakeGitHub: patched in place of
    urllib.request.urlopen, so the REAL GitHub class, api() and its retry policy run. `faults` is a list
    of [predicate(method, path), (code, headers) | "reset", times] — `times` None = every attempt."""

    def __init__(self, fake, faults=None):
        self.fake, self.faults, self.calls = fake, [list(f) for f in (faults or [])], []
        self.after: list[list] = []   # [predicate(method, path), action(), times]: state drifting mid-tick

    def sync_refs(self):
        for n, p in self.fake.prs.items():
            if p["state"] == "open":
                self.fake.g("update-ref", f"refs/pull/{n}/head", p["head"]["sha"])

    def __call__(self, req, timeout=None):
        import email.message
        import urllib.parse
        method, path = req.get_method(), req.full_url[len(API):]
        self.calls.append((method, path))
        for f in self.faults:
            if f[2] != 0 and f[0](method, path):
                if f[2] is not None:
                    f[2] -= 1
                if f[1] == "reset":
                    raise ConnectionResetError("connection reset by peer")
                code, hdrs = f[1]
                h = email.message.Message()
                for k, v in hdrs.items():
                    h[k] = v
                raise urllib.error.HTTPError(req.full_url, code, "fault", h, io.BytesIO(b'{"message": "fault"}'))
        u = urllib.parse.urlsplit(path)
        page = int(urllib.parse.parse_qs(u.query).get("page", ["1"])[0])
        rest = u.path.split("/", 4)[4]
        body = json.loads(req.data) if req.data else None
        code, out = self.route(method, rest, u.query, body, page)
        for a in self.after:
            if a[2] != 0 and a[0](method, path):
                a[2] -= 1
                a[1]()
        if code >= 400:
            raise urllib.error.HTTPError(req.full_url, code, "no", email.message.Message(), io.BytesIO(b"{}"))
        raw = json.dumps(out).encode()

        class _R:
            status, headers = code, {}

            def read(self):
                return raw

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False
        return _R()

    def route(self, method, rest, query, body, page):
        fk = self.fake
        pg = (lambda items: items if page == 1 else [])
        every = list(fk.prs.values()) + list(fk.carriers.values())
        m = re.fullmatch(r"commits/([0-9a-f]{40})/(check-runs|status|statuses)", rest)
        if method == "GET" and rest.startswith("rules/branches/"):
            return 200, [{"type": "required_status_checks", "parameters": {
                "strict_required_status_checks_policy": fk.rs_strict,
                "required_status_checks": [{"context": c, "integration_id": 4242 if c == STATUS_CONTEXT else None}
                                           for c in SYN_REQUIRED]}}]
        if method == "GET" and rest == "git/ref/heads/main":
            return 200, {"object": {"sha": fk.main_sha()}}
        if method == "GET" and rest == "pulls":
            return 200, pg([{**p, "user": p.get("user") or {"login": "someone"}} for p in fk.open_prs()])
        if method == "GET" and re.fullmatch(r"pulls/[0-9]+", rest):
            n = int(rest.split("/")[1])
            return 200, next(p for p in every if p["number"] == n)
        if method == "GET" and m and m.group(2) == "check-runs":
            ci = {c: v for c, v in fk.checks(m.group(1)).items() if c not in {x[0] for x in fk.statuses.get(m.group(1), [])}}
            return 200, {"check_runs": pg([{"id": i + 1, "name": c, "status": "in_progress" if v is None else "completed",
                                            "conclusion": v, "app": {"slug": "fixture"},
                                            "started_at": "2026-01-01T00:00:00Z"} for i, (c, v) in enumerate(sorted(ci.items()))])}
        if method == "GET" and m and m.group(2) == "status":
            return 200, {"statuses": []}
        if method == "GET" and m:
            return 200, pg([{"id": i + 1, "context": c, "state": st, "description": d, "creator": {"login": who}}
                            for i, (c, st, d, who) in enumerate(fk.statuses.get(m.group(1), []))])
        if method == "GET" and rest == "actions/runs":
            sha = re.search(r"head_sha=([0-9a-f]{40})", query).group(1)
            return 200, {"workflow_runs": pg([{"head_sha": sha, "path": ".github/workflows/security.yml",
                                               "status": "completed", "conclusion": "success"}])}
        if method == "POST" and rest.startswith("statuses/"):
            fk.post_status(rest.split("/")[1], body["state"], body["description"], context=body["context"])
            return 201, {}
        if method == "POST" and re.fullmatch(r"issues/[0-9]+/comments", rest):
            fk.comment(int(rest.split("/")[1]), body["body"])
            return 201, {}
        if method == "POST" and re.fullmatch(r"issues/[0-9]+/labels", rest):
            return 200, []
        if method == "PUT" and rest.endswith("/update-branch"):
            ok = fk.update_branch(int(rest.split("/")[1]), body["expected_head_sha"])
            self.sync_refs()
            return (202, {}) if ok else (422, {})
        if method == "PUT" and rest.endswith("/merge"):
            sha = fk.merge(int(rest.split("/")[1]), body["sha"])
            return 200, {"merged": bool(sha), "sha": sha}
        if method == "POST" and rest == "pulls":
            n = fk.create_carrier(body["head"])
            fk.carriers[n]["head"]["repo"] = {"full_name": "o/r"}
            return 201, {"number": n}
        if method == "PATCH" and re.fullmatch(r"pulls/[0-9]+", rest):
            fk.close_carrier(int(rest.split("/")[1]))
            return 200, {}
        return 404, {}


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
    # A fixture origin is cloned repeatedly while it is still being committed to. Git's detached auto-gc
    # (triggered by those commits) packs and prunes loose objects in the background, so a concurrent
    # `git clone` copying them fails with "failed to copy file ... No such file or directory". Never gc.
    g("config", "gc.auto", "0")
    g("config", "maintenance.auto", "false")
    w("services/s1.txt", "s1\n")
    w("count.txt", "1\n")
    w(RULES_FILE, SYN_RULES)
    w(SYN_GENERATOR, SYN_GENERATOR_SRC)
    w(REGEN_INVENTORY, SYN_INVENTORY)
    w(SYN_BASELINE, "base\n")
    w(SYN_MAIN_GATE_CODE, "main\n")
    w("ci.py", "import os,sys\nfail=[]\n"
               "if int(open('count.txt').read()) != len(os.listdir('services')): fail.append('gates (data)')\n"
               "if os.path.exists('bad.txt'): fail.append('Validate manifests')\n"
               "want='\\n'.join(sorted(os.listdir('services')))+'\\n'\n"
               "if not os.path.exists('derived/index.txt') or open('derived/index.txt').read()!=want: fail.append('derived')\n"
               "print(','.join(fail)); sys.exit(1 if fail else 0)\n")
    # Like payments/gen-vop-opa-bundle.sh: heredoc python run from the script's own directory.
    w(SYN_HEREDOC_SCRIPT, 'cd "$(dirname "$0")"\npython3 - <<\'PY\'\ntry:\n    import yaml  # noqa\nexcept ImportError:\n    pass\nPY\n')
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
    pr("pW", {".github/workflows/w.yml": "on: push\n"})
    pr("pW2", {".github/scripts/two.py": "print(2)\n"})
    pr("pS", {".github/scripts/check-s.py": "print(1)\n"}, link=("docs-s-link", "/etc/passwd"))
    # #13400: gate code (a check script) rides a train; next to a member that needs
    # regeneration it must not share one (the regeneration is proven by main's gates only).
    pr("pK", {SYN_GATE_CODE: "print('k')\n"})
    pr("pM", {SYN_MAIN_GATE_CODE: "member\n"})   # modifies a check script main already has
    pr("pX", {"docs/adr/0001-x.md": "x\n"})
    pr("pF", {"svc/fast.txt": "f\n"})
    pr("pT", {SYN_BASELINE: "loosened\n"})
    # Review 6: a generator whose NAME carries a newline, and a module shadowing `yaml` next to the
    # heredoc script. Each leaves a marker if it is ever executed.
    pr("pN", {SYN_NEWLINE_GENERATOR: f"touch {os.path.join(os.path.dirname(root), 'newline-gen-ran')}\n", "docs/n.md": "n\n"})
    pr("pQ", {"docs/q\nr.md": "q\n"})
    # Review 7: rename a gate script to a harmless-looking path; with rename detection git reports
    # only the NEW name, hiding that the generator runner's inventory was deleted.
    g("checkout", "-q", "-b", "pV", "main")
    os.makedirs(os.path.join(root, "docs"), exist_ok=True)
    g("mv", REGEN_INVENTORY, "docs/x.md")
    g("commit", "-q", "-m", "pV")
    g("checkout", "-q", "main")
    pr("pY", {SYN_SHADOW_MODULE: f"open({os.path.join(os.path.dirname(root), 'shadow-ran')!r}, 'w').write('x')\n",
              "docs/y.md": "y\n"})
    # A bundle generator deeper than any fixed-depth glob reaches; it would leave a marker if run.
    pr("pD", {SYN_DEEP_GENERATOR: f"touch {os.path.join(os.path.dirname(root), 'deep-generator-ran')}\n",
              "docs/d.md": "d\n"})


def self_test() -> int:
    fails: list[str] = []
    last_reservation: dict = {}   # ct / now / activity of the latest reservation_at, for a failing case's message
    ran: list[str] = []

    def check(label: str, cond: bool, detail: str = "") -> None:
        ran.append(label)
        if not cond:
            fails.append(f"{label}: {detail} [clock: time.time={time.time():.0f} last_reservation={last_reservation}]")

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
    for f in (".github/scripts/regen-derived.sh", ".github/workflows/ci.yml", "docs/adr/gen-index.sh", "docs/adr/lib-frontmatter.sh",
              "openbank-infra/gitops/components/x/gen-x-opa-bundle.sh",
              "openbank-infra/gitops/components/a/b/c/d/gen-x-opa-bundle.sh"):
        ok, why = qualifies({**base_pr, "files": [f]}, req, green, None, U)
        check(f"qualifies: member touching {f} is refused (manual lane)", not ok and "manual lane" in why, why)
    # Anything under .github/ that can change what EXECUTES stays in the manual lane: code of any
    # extension, workflows/actions of any extension, gates.yaml (its run commands), pip requirement
    # pins (they choose what is installed), and agent prompts (they instruct an agent holding tools).
    for f in (".github/scripts/x.py", ".github/scripts/x.js", ".github/scripts/x.cjs", ".github/scripts/x.init.gradle",
              ".github/scripts/lib/probe.sh", ".github/workflows/y.yml", ".github/workflows/notes.md",
              ".github/workflows/requirements/schemathesis.txt", ".github/actions/a/action.yml",
              ".github/actions/a/README.md", ".github/scripts/pyyaml-requirements.txt",
              ".github/agent-prompts/pr-steward.md", ".github/CODEOWNERS", ".github/labels.yml",
              ".github/scripts/noext", "docs/adr/new.sh",
              # #13400: the train's own code and everything that judges a carrier or the merge
              # machinery stays strict, as does any .github path the allow-list does not name.
              ".github/scripts/merge-train.py", ".github/scripts/merge_train_carrier.py",
              ".github/workflows/merge-train.yml", ".github/workflows/security.yml",
              ".github/scripts/check-security-checklist.py", ".github/scripts/check-api-contract.py",
              ".github/scripts/check-codeql-compile-coverage.py", ".github/scripts/check-adr-registry.sh",
              ".github/scripts/check-eu-ai-act.sh", ".github/scripts/check-x.js", ".github/scripts/check-x",
              ".github/scripts/lib/check-x.sh", ".github/scripts/sub/check-x.py", ".github/gates/other.yaml",
              ".github/gates/gates.yml", ".github/dependabot.yml", ".github/unknown.json", ".github/x/y.cfg"):
        ok, why = qualifies({**base_pr, "files": [f]}, req, green, None, U)
        check(f"qualifies: member touching {f} is refused (manual lane)", not ok and "manual lane" in why, why)
    # Prose and gate-INPUT data under .github/ ride trains (ADR-0343 §Security: a baseline is read
    # by main's gate scripts in the train's CI exactly as in the PR's own CI).
    for f in (".github/CLAUDE.md", ".github/PULL_REQUEST_TEMPLATE.md",
              ".github/scripts/configproperty-kotlin-defaults-baseline.txt",
              ".github/gates/allow-all-pdp-exceptions.txt", ".github/scripts/gh-transient-patterns.txt"):
        ok, why = qualifies({**base_pr, "files": [f]}, req, green, None, U)
        check(f"qualifies: member touching only {f} is trainable", ok and not U(f), why)
    for f in ("openbank-libs/governance/rules.yaml", "docs/adr/0343-x.md", "openbank-infra/gitops/components/x/x.yaml"):
        check(f"untrainable: data file {f} is NOT untrainable", not U(f))
    # #13400: reviewed gate code rides a train. The carrier's CI runs the COMBINED head, so a gate
    # change is judged together with the code it judges exactly as in the strict lane, and bisect
    # isolates a culprit; what it may never do is share a train with a regeneration commit.
    for f in (".github/scripts/check-x.py", ".github/scripts/check-x.sh"):
        ok, why = qualifies({**base_pr, "files": [f]}, req, green, None, U)
        check(f"qualifies: a gate-code-only member ({f}) is a train CANDIDATE", ok and not U(f) and gate_code(f), why)
        sl = strict_lane_state({**base_pr, "files": [f]}, req, green, None, U, [], None, None)
        check(f"strict lane: a gate-code-only member ({f}) is NOT in the strict lane", sl[0] is None, str(sl))
    # #13837: gates.yaml DECLARES the privileged gates' run/selftest/mode/when/budget, so no
    # gates.yaml diff rides a train: each weakening shape is refused and lands in the strict lane.
    for shape in ("run of check-adr-registry.sh pointed at a no-op", "mode flipped enforced -> advisory",
                  "when narrowed to a path no PR touches", "privileged entry deleted", "budget_seconds changed"):
        f = ".github/gates/gates.yaml"
        ok, why = qualifies({**base_pr, "files": [f]}, req, green, None, U)
        check(f"qualifies: gates.yaml ({shape}) is refused (strict lane)",
              not ok and "manual lane" in why and U(f) and not gate_code(f), why)
        check(f"strict lane: gates.yaml ({shape}) IS in the strict lane",
              strict_lane_state({**base_pr, "files": [f]}, req, green, None, U, [], None, None)[0] == "green")
    # ...while an unrelated (non-privileged) gate's CODE change still rides a train.
    ok, why = qualifies({**base_pr, "files": [".github/scripts/check-unrelated.py"]}, req, green, None, U)
    check("qualifies: an unrelated gate's check script still rides a train (#13837 control)", ok, why)
    for mix in ([".github/scripts/check-x.py", ".github/workflows/ci.yml"],
                [".github/gates/gates.yaml", ".github/scripts/merge-train.py"],
                [".github/scripts/check-x.py", ".github/unknown.json"]):
        ok, why = qualifies({**base_pr, "files": mix}, req, green, None, U)
        check(f"qualifies: a member mixing gate code with {mix[1]} is refused (strict lane)",
              not ok and "manual lane" in why, why)
        check(f"strict lane: a member mixing gate code with {mix[1]} IS in the strict lane",
              strict_lane_state({**base_pr, "files": mix}, req, green, None, U, [], None, None)[0] == "green")
    # Review: gate code never rides a train next to a symlink/gitlink (the strict lane refuses
    # those outright); one such member must not drag a whole carrier into bisect. A DATA-only
    # member with a symlink still reaches publish, which halves it (pL), as before.
    ok, why = qualifies({**base_pr, "files": [".github/scripts/check-x.py", "docs/l"], "special_paths": ["docs/l"]},
                        req, green, None, U)
    check("qualifies: gate code next to a symlink/gitlink is refused on the train path", not ok and "symlink" in why, why)
    check("qualifies: a data-only member with a symlink still qualifies (publish halves it)",
          qualifies({**base_pr, "files": ["docs/l"], "special_paths": ["docs/l"]}, req, green, None, U)[0])
    # Review: files that SUPPRESS security findings stay strict, wherever they live.
    for f in (".gitleaksignore", ".gitleaks.toml", ".trivyignore", ".semgrep/rules.yml", ".semgrep.yml",
              ".github/scripts/check-ruleset-bypass-actors.py", ".github/scripts/check-branch-protection.py"):
        ok, why = qualifies({**base_pr, "files": [f]}, req, green, None, U)
        check(f"qualifies: member touching {f} is refused (strict lane)", not ok and "manual lane" in why and U(f), why)
    # Drift guard on the REAL tree: every check script the train's own workflows or the pinned
    # extra gate run, every one that judges a carrier (imports merge_train_carrier) or the merge
    # machinery, and every one that proves a DERIVED_OUTPUTS entry must be in PRIVILEGED_GATE_CODE.
    scripts = pathlib.Path(".github/scripts")
    check("privileged gate code: the drift guard runs from the repository root (it never skips silently)",
          scripts.is_dir() and pathlib.Path(".github/workflows").is_dir(), str(pathlib.Path.cwd()))
    must: set[str] = set(FRESHNESS_GATE_CODE)
    # Every workflow that mentions the train or its carrier library, plus each pinned extra gate's
    # workflow: the check scripts they run decide a train's own verdict.
    extra_wfs = {pathlib.Path(w) for w in (parse_extra_gates(pathlib.Path(RULES_FILE).read_text()) or {}).values()} \
        if pathlib.Path(RULES_FILE).exists() else set()
    for wf in sorted(pathlib.Path(".github/workflows").glob("*.y*ml")) if scripts.is_dir() else []:
        text = wf.read_text(errors="replace")
        if wf in extra_wfs or re.search(r"merge_train_carrier|merge-train", text):
            must |= set(re.findall(r"\.github/scripts/check-[A-Za-z0-9_.-]+", text))
    for c in sorted(scripts.glob("check-*")) if scripts.is_dir() else []:
        if re.search(r"merge_train_carrier|merge-train|bypass_actors|branches/[^/\s]*/protection|/rulesets",
                     c.read_text(errors="replace")):
            must.add(c.as_posix())
    missing = sorted(m for m in must if gate_code(m))
    check("privileged gate code: every check script the train, its extra gate or a carrier/ruleset verdict depends on stays strict",
          scripts.is_dir() and not missing, str(missing))
    for f in ("docs/a\nb.md", "svc/tab\there.txt", "svc/esc\x1b.txt", "svc/del\x7f.txt"):
        ok, why = qualifies({**base_pr, "files": [f]}, req, green, None, U)
        check(f"qualifies: a DATA path with a control character {f!r} is refused", not ok and "control character" in why, why)
    check("untrainable: an unparsable runner fails closed (any gen-* at any depth)",
          Untrainable("")("openbank-infra/gitops/a/b/c/d/gen-x-opa-bundle.sh") and Untrainable("")("x/y/z/gen-q.py"))
    # ---- #13400: a gate red only for its budget / NOT EVALUATED is inconclusive ---------------
    def recs(*kinds):
        return [{"id": f"g{i}", "status": "failed" if k else "ok", "failure_kind": k} for i, k in enumerate(kinds)]
    for label, jobs, records, want in [
        ("budget only", ["gates (lint-x)", "Validate manifests"], {"lint-x": recs("budget", None)}, True),
        ("not evaluated + budget", ["gates (a)", "gates (b)"], {"a": recs("not_evaluated"), "b": recs("budget")}, True),
        ("content next to budget", ["gates (a)"], {"a": recs("budget", "content")}, False),
        ("unfalsified", ["gates (a)"], {"a": [{"id": "x", "status": "unfalsified", "failure_kind": "unfalsified"}]}, False),
        ("pre-#13400 record (no failure_kind)", ["gates (a)"], {"a": [{"id": "x", "status": "failed"}]}, False),
        ("artifact unreadable", ["gates (a)"], {"a": None}, False),
        ("artifact missing", ["gates (a)"], {}, False),
        ("a non-gate job failed too", ["gates (a)", "derived"], {"a": recs("budget")}, False),
        ("aggregate alone", ["Validate manifests"], {}, False),
        ("shard red with no failed record", ["gates (a)"], {"a": recs(None)}, False),
    ]:
        got, why = gate_failures_inconclusive(jobs, records)
        check(f"gate verdict: {label} -> {'inconclusive' if want else 'conclusive'}", got is want, why)

    # A GitHub rerun keeps attempt-1 artifacts under the same workflow run id. The failing
    # attempt-2 job may be a content failure even when attempt 1 wrote a budget-only verdict.
    original_api, original_paginate, original_artifact_json = api, paginate, GitHub.artifact_json
    try:
        run = 123
        attempt = 2
        artifacts = [{"id": 10, "name": "gate-results-data-a1", "expired": False}]
        jobs = [{"name": "gates (data)", "conclusion": "failure", "run_attempt": attempt}]
        def fixture_api(method, path, body=None):
            return (200, {"run_attempt": attempt}) if path.endswith(f"/actions/runs/{run}") else (404, {})
        def fixture_paginate(path, key=None):
            if path.endswith("/check-runs"):
                return [{"name": "gates (data)", "id": 20,
                         "details_url": f"https://github.com/x/y/actions/runs/{run}/job/20"}]
            if path.endswith("/jobs?filter=latest"):
                return jobs
            if path.endswith("/artifacts"):
                return artifacts
            raise AssertionError(path)
        globals()["api"], globals()["paginate"] = fixture_api, fixture_paginate
        GitHub.artifact_json = lambda self, artifact_id: [
            {"id": "g", "status": "failed", "failure_kind": "budget"}]
        gh_attempt = GitHub("x/y", "main", "")
        got, why = gh_attempt.failures_inconclusive("head", ["gates (data)"])
        check("attempt evidence: old budget artifact cannot excuse a new red shard", not got and "unreadable" in why, why)
        artifacts.append({"id": 11, "name": "gate-results-data-a2", "expired": False})
        got, why = gh_attempt.failures_inconclusive("head", ["gates (data)"])
        check("attempt evidence: exact current attempt is accepted", got and "budget" in why, why)
        jobs[0]["run_attempt"] = 1
        got, why = gh_attempt.failures_inconclusive("head", ["gates (data)"])
        check("attempt evidence: stale failing job is rejected", not got and "not from attempt" in why, why)
    finally:
        globals()["api"], globals()["paginate"] = original_api, original_paginate
        GitHub.artifact_json = original_artifact_json

    # ---- inconclusive is never red; legacy culprit marks are re-evaluated -----------------
    rq = ["gates (data)", "derived", "sec"]
    f, pnd, inc = classify_carrier({"gates (data)": "success", "derived": "success", "sec": "cancelled"}, rq)
    check("classify: a cancelled context is inconclusive, not failing", f == [] and inc == ["sec"] and pnd == [], f"{f} {inc}")
    f, pnd, inc = classify_carrier({"gates (data)": "failure", "derived": "success", "sec": "cancelled"}, rq)
    check("classify: failure + cancelled -> only the real failure is failing", f == ["gates (data)"] and inc == ["sec"], f"{f} {inc}")
    f, pnd, inc = classify_carrier({"gates (data)": "skipped", "derived": "success", "sec": "cancelled"}, rq)
    check("classify: skipped next to a cancellation is inconclusive", f == [] and set(inc) == {"gates (data)", "sec"}, f"{f} {inc}")
    f, pnd, inc = classify_carrier({"gates (data)": "skipped", "derived": "success", "sec": "success"}, rq)
    check("classify: skipped with no cancellation keeps its old meaning (failing)", f == ["gates (data)"] and inc == [], f"{f} {inc}")
    f, pnd, inc = classify_carrier({"gates (data)": "stale", "derived": None, "sec": "success"}, rq)
    check("classify: stale is inconclusive, a pending context is pending", f == [] and inc == ["gates (data)"] and pnd == ["derived"])
    check("culprit description carries the prefix and the failing contexts",
          culprit_description(["gates (data)"], "a" * 40) == "culprit: gates (data) red on a train over main aaaaaaaaaaaa")
    legacy = {STATUS_CONTEXT: ("failure", "isolated as the cause of a red train over main 0123456789ab")}
    check("qualifies: a LEGACY culprit mark (pre-inconclusive rule) is re-evaluated, not final",
          qualifies({**base_pr, "files": ["svc/a"]}, req, green, legacy)[0])
    check("qualifies: a new-format culprit mark is final until a push",
          not qualifies({**base_pr, "files": ["svc/a"]}, req, green,
                        {STATUS_CONTEXT: ("failure", culprit_description(["x"], "b" * 40))})[0])
    check("qualifies: a manual-lane failure stays final",
          not qualifies({**base_pr, "files": ["svc/a"]}, req, green,
                        {STATUS_CONTEXT: ("failure", "not verifiable by the merge train: manual lane")})[0])
    check("qualifies: an App failure (culprit) on the head waits for a push",
          not qualifies(base_pr, req, green, {STATUS_CONTEXT: ("failure", "x")})[0])
    for desc in (f"{CONFLICT_PREFIX}{'b' * 12}: merge main into the branch", f"{REFUSED_PREFIX}{'b' * 12}: merge main into the branch"):
        check(f"qualifies: a notice ({desc[:20]}...) is information, not a verdict: the head still qualifies",
              qualifies(base_pr, req, green, {STATUS_CONTEXT: ("failure", desc)})[0])
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
    check("batch: a bisect size on an applied member caps it", batch_cap(mk(1, 2, 3, 4), {1: 2}, 8) == 2)
    # NEGATIVE first (2026-10-25, #13801..#13817): halving posted `bisect size 2` on the carrier's
    # members, but the oldest candidate (#13697, dropped by every build) carried none.
    check("batch: a bisect size on a NON-oldest applied member still caps (oldest never rides)",
          batch_cap(mk(2, 3, 4), {2: 2, 3: 2}, 8) == 2)
    check("batch: the smallest bisect size among applied members wins", batch_cap(mk(2, 3), {2: 2, 3: 1}, 8) == 1)
    # ...and a DROPPED candidate's size never counts (review of #13840): #1 carries 1 and never applies.
    check("batch: a dropped candidate's bisect size does not pin the train", batch_cap(mk(2, 3), {1: 1}, 8) == 8)
    check("batch: the window is the default size; the cap bounds APPLIED members, not candidates",
          [m.number for m in select_batch(mk(1, 2, 3, 4), {1: 2}, 3)] == [1, 2, 3])
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
    check("comments: fixed texts carry no placeholders", "{" not in DROP_MESSAGE + CULPRIT_MESSAGE + MANUAL_LANE_MESSAGE
          + EXTRA_GATE_MESSAGE)

    # ---- main history API budget: use the real checks + pagination over canned GETs ---------
    history = [f"{i:040x}" for i in range(STALE_RED_WALK)]
    budget_reads: list[str] = []
    history_results: dict[str, dict] = {}
    budget_required = [f"required-{i}" for i in range(8)]
    budget_rules = "merge_train:\n  extra_gates:\n    - {workflow: .github/workflows/security.yml, check: Trivy}\n"

    def budget_git(*args, **kwargs):
        return subprocess.CompletedProcess(args, 0, "\n".join(history) if args[0] == "rev-list" else budget_rules, "")

    def budget_get(path):
        budget_reads.append(path)
        sha = path.split("/commits/", 1)[1].split("/", 1)[0]
        if "/check-runs?" in path:
            # A full page forces real paginate() to fetch the second page. Relevant contexts
            # deliberately live there, so a page-one-only optimization cannot pass this test.
            if path.endswith("page=1"):
                return {"check_runs": [{"id": i, "name": f"unrelated-{i}", "status": "completed",
                                        "conclusion": "success"} for i in range(100)]}
            return {"check_runs": [{"id": 100 + i, "name": name, "status": "completed",
                                    "conclusion": state} for i, (name, state) in
                                   enumerate(history_results.get(sha, {}).items())]}
        return {"statuses": []}

    saved_get = globals()["get"]
    try:
        globals()["get"] = budget_get
        budget_gh = GitHub("o/r", "main", "")
        got = main_red_contexts(budget_gh, budget_git, budget_required, history[0])
        check("main history: nine absent contexts fetch each commit once, including every check page",
              got == {} and len(budget_reads) == 3 * STALE_RED_WALK
              and len(set(budget_reads)) == len(budget_reads), str(len(budget_reads)))
        # Pending/cancelled results do not hide a nearest completed failure. A later historical
        # failure must not override a nearer success, and the extra gate participates too.
        history_results.update({history[0]: {"required-0": None, "Trivy": "cancelled"},
                                history[3]: {"required-0": "failure", "Trivy": "failure"},
                                history[4]: {"required-1": "success"},
                                history[6]: {"required-1": "failure"}})
        budget_reads.clear()
        supplied_cache: dict = {}
        got = main_red_contexts(budget_gh, budget_git, budget_required, history[0], supplied_cache)
        check("main history: shared reads preserve nearest completed failures and extra gates",
              got == {"required-0": history[3], "Trivy": history[3]}
              and len(budget_reads) == 3 * STALE_RED_WALK, str(got))
        check("main history: caller's initially empty cache is populated and reused",
              len(supplied_cache) == STALE_RED_WALK
              and main_red_contexts(budget_gh, budget_git, budget_required, history[0], supplied_cache) == got
              and len(budget_reads) == 3 * STALE_RED_WALK)
        history_results[history[0]] = {"required-0": "success", "Trivy": "success"}
        budget_reads.clear()
        check("main history: a new evaluation rereads mutable checks, never reuses a prior verdict",
              main_red_contexts(budget_gh, budget_git, budget_required, history[0]) == {}
              and len(budget_reads) == 3 * STALE_RED_WALK)

        def denied_get(path):
            raise SystemExit("GET denied: API rate limit exceeded")

        globals()["get"] = denied_get
        refused = False
        try:
            main_red_contexts(budget_gh, budget_git, budget_required, history[0])
        except SystemExit:
            refused = True
        check("main history: unreadable checks still abort, never become a clean verdict", refused)
    finally:
        globals()["get"] = saved_get

    # ---- extra gates (merge_train.extra_gates) -------------------------------------------------
    decl = ("a: 1\nmerge_train:\n  # why\n  extra_gates:\n    - {workflow: .github/workflows/security.yml, check: Trivy}\n"
            "    - {workflow: .github/workflows/security.yml, check: \"CodeQL\"}  # c\nnext: 2\n")
    want = {"Trivy": ".github/workflows/security.yml", "CodeQL": ".github/workflows/security.yml"}
    check("extra gates: the rules.yaml block list is parsed (name -> pinned workflow)", parse_extra_gates(decl) == want,
          str(parse_extra_gates(decl)))
    for label, text in [("absent", "a: 1\n"), ("empty", "merge_train:\n  extra_gates: []\n"),
                        ("other key only", "merge_train:\n  batch: 8\n"),
                        ("an unpinned item", "merge_train:\n  extra_gates:\n    - Trivy\n"),
                        ("one malformed item among good ones", "merge_train:\n  extra_gates:\n"
                         "    - {workflow: .github/workflows/security.yml, check: Trivy}\n    - {check: X}\n")]:
        check(f"extra gates: {label} declaration parses as None", parse_extra_gates(text) is None, str(parse_extra_gates(text)))
    xg = {"Trivy": ".github/workflows/security.yml"}
    # The REAL GitHub.extra_checks over canned API pages: a spoofed green from another app and a later
    # green from another workflow must neither mask the Security scan red nor count on their own.
    def _runs(*specs):
        return [{"id": i, "name": "Trivy", "status": "completed", "conclusion": c, "app": {"slug": slug},
                 "details_url": f"https://github.com/o/r/actions/runs/{run}/job/{i}"} for i, (c, slug, run) in enumerate(specs)]
    paths = {1: ".github/workflows/security.yml", 2: ".github/workflows/evil.yml"}
    saved_api = (globals()["paginate"], globals()["get"])
    try:
        for label, page, want in [
                ("pinned red + later spoof green (other app)", _runs(("failure", "github-actions", 1), ("success", "evil-app", 1)), "failure"),
                ("pinned red + later green (other workflow)", _runs(("failure", "github-actions", 1), ("success", "github-actions", 2)), "failure"),
                ("only a spoofed green: absent, not green", _runs(("success", "evil-app", 1), ("success", "github-actions", 2)), "absent"),
                ("pinned green", _runs(("success", "github-actions", 1)), "success")]:
            globals()["paginate"] = lambda path, key=None, _p=page: _p if key == "check_runs" else []
            globals()["get"] = lambda path: {"path": paths[int(path.rsplit("/", 1)[1])] + "@refs/heads/x"}
            got = GitHub("o/r", "main", "").extra_checks("a" * 40, {"Trivy": ".github/workflows/security.yml"})
            check(f"extra gates (GitHub.extra_checks): {label}", got.get("Trivy", "absent") == want, str(got))
        # No pinned check run: n/a ONLY when the pinned workflow has a COMPLETED run for this head.
        def _wf(*specs):
            return [{"id": 10 + i, "path": f"{p}@refs/heads/x", "status": st, "head_sha": "a" * 40,
                     "conclusion": (c[0] if c else ("success" if st == "completed" else None))}
                    for i, (p, st, *c) in enumerate(specs)]
        sec, evil = ".github/workflows/security.yml", ".github/workflows/evil.yml"
        for label, wruns, want in [
                ("absent, no workflow run at all: absent (pending)", _wf(), "absent"),
                ("absent, pinned workflow still running: absent (pending)", _wf((sec, "in_progress")), "absent"),
                ("absent, only ANOTHER workflow completed: absent (pending)", _wf((evil, "completed")), "absent"),
                ("absent, pinned run completed + a rerun queued: absent (pending)", _wf((sec, "completed"), (sec, "queued")), "absent"),
                ("absent, pinned workflow COMPLETED without the job: skipped (n/a)", _wf((sec, "completed")), "skipped"),
                # #13400 fail closed: absent is n/a ONLY over pinned runs that all concluded SUCCESS.
                ("absent, pinned run CANCELLED: pending, never n/a", _wf((sec, "completed", "cancelled")), None),
                ("absent, pinned run TIMED OUT: pending", _wf((sec, "completed", "timed_out")), None),
                ("absent, pinned run STARTUP_FAILURE: pending", _wf((sec, "completed", "startup_failure")), None),
                ("absent, pinned run ACTION_REQUIRED: pending", _wf((sec, "completed", "action_required")), None),
                ("absent, pinned run FAILED: red", _wf((sec, "completed", "failure")), "failure"),
                ("absent, one success + one failed pinned run: red", _wf((sec, "completed"), (sec, "completed", "failure")), "failure"),
                ("absent, one success + one cancelled pinned run: pending", _wf((sec, "completed"), (sec, "completed", "cancelled")), None)]:
            globals()["paginate"] = lambda path, key=None, _w=wruns: _w if key == "workflow_runs" else []
            got = GitHub("o/r", "main", "").extra_checks("a" * 40, {"Trivy": sec})
            check(f"extra gates (GitHub.extra_checks): {label}", got.get("Trivy", "absent") == want, str(got))
            if want == "skipped":
                check("extra gates: absent but the pinned run completed without it is served",
                      qualifies(base_pr, req, {**green, **got}, extra=xg)[0])
                own = {**base_pr, "files": [sec]}
                check("extra gates: absent + the PR changes the pinned workflow is RED even over a successful run",
                      qualifies(own, req, {**green, **got}, untrainable=lambda _f: False, extra=xg)
                      == (False, "extra gate red: Trivy (the PR changes the extra gate's own workflow)"),
                      str(qualifies(own, req, {**green, **got}, untrainable=lambda _f: False, extra=xg)))
                check("extra gates: strict lane refuses an absent gate whose own workflow the PR changes",
                      strict_lane_state(own, req, {**green, **got}, None, U, [], None, xg)[0] is None)
                check("extra gates: a PRESENT green gate is served though the PR changes its workflow (strict lane)",
                      strict_lane_state(own, req, {**green, "Trivy": "success"}, None, U, [], None, xg)[0] == "green")
                cks = own_workflow_checks({**green, **got}, xg, [sec, "x.kt"])
                check("extra gates: a carrier whose member changes the pinned workflow is failing on an absent gate",
                      "Trivy" in classify_carrier(cks, carrier_contexts(req, xg, cks))[0], str(cks))
                cks = own_workflow_checks({**green, **got}, xg, None)
                check("extra gates: a carrier whose diff cannot be read holds an absent gate as pending",
                      "Trivy" in classify_carrier(cks, carrier_contexts(req, xg, cks))[1], str(cks))
                cks = own_workflow_checks({**green, **got}, xg, ["x.kt"])
                check("extra gates: a carrier not touching the pinned workflow keeps the n/a",
                      "Trivy" not in sum(classify_carrier(cks, carrier_contexts(req, xg, cks)), []), str(cks))
            elif want is None:
                check(f"extra gates: {label} is not served", qualifies(base_pr, req, {**green, **got}, extra=xg)
                      == (False, "extra gate pending: Trivy"), str(qualifies(base_pr, req, {**green, **got}, extra=xg)))
            elif want == "failure":
                check(f"extra gates: {label} refuses", qualifies(base_pr, req, {**green, **got}, extra=xg)
                      == (False, "extra gate red: Trivy"))
    finally:
        globals()["paginate"], globals()["get"] = saved_api
    check("extra gates: one red pinned run beats a later green one", extra_verdict(["failure", "success"]) == "failure")
    check("extra gates: pinned running waits, skipped alone is n/a",
          extra_verdict(["success", None]) is None and extra_verdict(["skipped"]) == "skipped")
    check("extra gates: red Trivy refuses (any lane reads qualifies)",
          qualifies(base_pr, req, {**green, "Trivy": "failure"}, extra=xg) == (False, "extra gate red: Trivy"))
    check("extra gates: without the declaration a red Trivy is not judged (the negative control)",
          qualifies(base_pr, req, {**green, "Trivy": "failure"})[0])
    check("extra gates: running Trivy waits", qualifies(base_pr, req, {**green, "Trivy": None}, extra=xg)
          == (False, "extra gate pending: Trivy"))
    # Fail closed (#13400): absent on the head is PENDING — Security scan has no path filter, so "absent"
    # almost always means "not reported yet". Only GitHub.extra_checks may turn absence into n/a.
    check("extra gates: Trivy absent with no completed pinned run is PENDING, not served",
          qualifies(base_pr, req, green, extra=xg) == (False, "extra gate never reported: Trivy"))
    check("extra gates: strict lane reads an absent Trivy as pending",
          strict_lane_state({**base_pr, "files": [".github/workflows/x.yml"]}, req, green, None, U, [], None, xg)[0] == "pending")
    check("extra gates: a carrier with an absent Trivy is not judged green (pending)",
          "Trivy" in classify_carrier(green, carrier_contexts(req, xg, green))[1])
    check("extra gates: a carrier with a red Trivy is failing",
          "Trivy" in classify_carrier({**green, "Trivy": "failure"}, carrier_contexts(req, xg, {**green, "Trivy": "failure"}))[0])
    check("extra gates: skipped Trivy is not applicable", qualifies(base_pr, req, {**green, "Trivy": "skipped"}, extra=xg)[0])
    check("extra gates: green Trivy is served", qualifies(base_pr, req, {**green, "Trivy": "success"}, extra=xg)[0])
    check("extra gates: a required failure is still reported as such",
          qualifies(base_pr, req, {**green, "Gitleaks": "failure", "Trivy": "failure"}, extra=xg)[1]
          == "required context failure: Gitleaks")
    wf = {**base_pr, "files": [".github/workflows/x.yml"]}
    check("extra gates: strict lane refuses a red Trivy",
          strict_lane_state(wf, req, {**green, "Trivy": "failure"}, None, U, [], None, xg)[0] is None)
    check("extra gates: strict lane WAITS when Trivy is red on main too",
          strict_lane_state(wf, req, {**green, "Trivy": "failure"}, None, U, [], {"Trivy": "c" * 40}, xg)[0] == "waiting")
    check("extra gates: strict lane reads a running Trivy as pending",
          strict_lane_state(wf, req, {**green, "Trivy": None}, None, U, [], None, xg)[0] == "pending")

    # ---- real git, real ticks ----------------------------------------------------------------
    saved = {k: os.environ.get(k) for k in ("GH_TOKEN", "GITHUB_TOKEN", "RUNNER_TEMP")}
    os.environ.update({"GH_TOKEN": "ghs_SELFTEST_MUST_NOT_LEAK", "GITHUB_TOKEN": "ghs_2", "RUNNER_TEMP": "/runner/temp"})
    try:
        env = generator_env()
        check("generator env: no *TOKEN*, no RUNNER_TEMP, isolated git config",
              not any("TOKEN" in k for k in env) and "RUNNER_TEMP" not in env and env["GIT_CONFIG_GLOBAL"] == os.devnull)
        with _synthetic_clock(), tempfile.TemporaryDirectory() as root:
            origin = os.path.join(root, "origin")
            _synthetic_origin(origin)
            gh = FakeGitHub(origin, root)
            base0 = gh.main_sha()
            numbers = {"pA": 11, "pB": 12, "p1": 1, "p2": 2, "p3": 3, "p4": 4, "pR": 21, "pL": 31, "pG": 32, "pW": 35, "pD": 41,
                       "pX": 33, "pF": 34, "pW2": 36, "pS": 37, "pN": 51, "pY": 52, "pQ": 53, "pV": 61, "pT": 62, "pK": 38, "pM": 39}
            probe = os.path.join(root, "probe.json")
            counter = [0]

            def clone(tag):
                counter[0] += 1
                d = os.path.join(root, f"{tag}-{counter[0]}")
                r = sh(["git", "clone", "-q", "--branch", "main", origin, d], env=base_env(), check=False)
                if r.returncode != 0:
                    raise RuntimeError(f"self-test fixture: git clone failed: {r.stderr.strip()[-400:]}")
                return d

            def tick(regen=None, batch_size=8):
                """settle -> build -> publish, each in its own fresh clone, as on three runners."""
                s = mode_settle(gh, Git(clone("settle")), "o/r", False)
                if s["in_flight"]:
                    return s, "in-flight"
                counter[0] += 1
                bdir = clone("build")
                b = TrainBuilder(bdir, regen_commands=regen or (f"{sys.executable} -I {SYN_GENERATOR}",),
                                 overlay_roots=(".github/scripts",))
                b.env["PROBE_OUT"] = probe
                out = os.path.join(root, f"bundle-{counter[0]}")
                mode_build(gh, Git(bdir), b, "o/r", out, batch_size)
                mode_notice_drops(gh, Git(clone("publish")), out)   # publish runs it first, as in main()
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

            # Owner-approved codex paths use the real train/strict machinery, never a shortcut.
            gh.g("branch", "codex/owner-feature", "p1")
            gh.g("branch", "codex/owner-workflow", "pW")
            gh.add_pr(70, "codex/owner-feature", "2026-01-01")
            _, st = tick()
            check("owner: unapproved codex head creates no train", st == "idle" and not gh.merged)
            h = gh.prs[70]["head"]["sha"]
            gh.post_status(h, "success", "owner-approved PR #70", OWNER_APPROVAL_CONTEXT)
            tick()  # carrier produced while approved
            gh.post_status(h, "failure", "owner-revoked PR #70", OWNER_APPROVAL_CONTEXT)
            tick()
            check("owner: revoked codex carrier cannot merge", not gh.merged, str(gh.merged))
            reset()
            gh.add_pr(70, "codex/owner-feature", "2026-01-01")
            gh.post_status(h, "success", "owner-approved PR #70", OWNER_APPROVAL_CONTEXT)
            drain()
            check("owner: approved codex feature uses verified train", gh.merged == [70], str(gh.merged))
            reset()
            gh.add_pr(71, "codex/owner-workflow", "2026-01-01")
            h = gh.prs[71]["head"]["sha"]
            gh.post_status(h, "success", "owner-approved PR #71", OWNER_APPROVAL_CONTEXT)
            tick()
            check("owner: approved codex workflow uses strict lane", gh.merged == [71]
                  and gh.app_statuses(h).get(STATUS_CONTEXT, ("", ""))[1].startswith("strict lane:"), str(gh.merged))
            reset()

            # 4-PR batch, #3 bad: bisect across ticks isolates #3; 1, 2, 4 merge.
            open_only("p1", "p2", "p3", "p4")
            drain()
            check("ticks: 4-PR batch with #3 bad merges 1, 2 and 4", sorted(gh.merged) == [1, 2, 4], str(gh.merged))
            check("ticks: #3 is the culprit (App failure + fixed comment)",
                  (3, CULPRIT_MESSAGE) in gh.comments
                  and gh.app_statuses(gh.prs[3]["head"]["sha"]).get(STATUS_CONTEXT, ("",))[0] == "failure", str(gh.comments))
            check("ticks: every carrier was closed", all(c["state"] == "closed" for c in gh.carriers.values()))
            reset()

            def settle_once():
                return mode_settle(gh, Git(clone("settle")), "o/r", False)

            def mt_status(n):
                return gh.app_statuses(gh.prs[n]["head"]["sha"]).get(STATUS_CONTEXT, ("", ""))

            def open_carriers():
                return [c for c in gh.carriers.values() if c["state"] == "open"]

            # Fully cancelled carrier (#13452 shape): no culprit; ONE rerun, then in flight.
            open_only("p1")
            tick()
            gh.carrier_override = {"gates (data)": "cancelled", "derived": "skipped"}
            s = settle_once()
            check("cancelled: a fully cancelled carrier blames nobody, re-runs once and is in flight",
                  s["culprits"] == [] and s["bisected"] == [] and len(gh.reruns) == 1 and s["in_flight"]
                  and len(open_carriers()) == 1 and mt_status(1)[0] != "failure" and not gh.comments,
                  f"{s} reruns={gh.reruns} status={mt_status(1)}")
            check("cancelled: the rerun asked for exactly the inconclusive contexts",
                  bool(gh.reruns) and sorted(gh.reruns[0][1]) == ["derived", "gates (data)"], str(gh.reruns))
            s = settle_once()
            check("cancelled: still inconclusive after the one rerun -> closed with no verdict, no second rerun",
                  s["culprits"] == [] and s["inconclusive"] and len(gh.reruns) == 1 and not open_carriers()
                  and mt_status(1)[0] != "failure", f"{s} reruns={gh.reruns}")
            gh.carrier_override = {}
            drain()
            check("cancelled: once CI is terminal and green, the member merges", gh.merged == [1], str(gh.merged))
            gh.reruns.clear()
            reset()

            # Rerun succeeds: the next settle sees a terminal green result and merges.
            open_only("p1")
            tick()
            gh.carrier_override = {"gates (data)": "cancelled"}
            gh.on_rerun = lambda: gh.carrier_override.clear()
            settle_once()
            s = settle_once()
            gh.on_rerun = None
            check("cancelled: a rerun that finishes green merges the member, nobody blamed",
                  gh.merged == [1] and s["culprits"] == [] and len(gh.reruns) == 1, f"{s} merged={gh.merged}")
            gh.reruns.clear()
            reset()

            # Failure + cancelled mix: only the real failure blames, and the status names only it.
            open_only("p3")
            tick()
            gh.carrier_override = {"issue-hygiene": "cancelled"}
            real = [c for c in SYN_REQUIRED[:3] if gh.checks(open_carriers()[0]["head"]["sha"]).get(c) == "failure"]
            gh.carrier_override = {"derived": "cancelled"} if "derived" not in real else {"gates (data)": "cancelled"}
            s = settle_once()
            st, desc = mt_status(3)
            check("mix: a real failure next to a cancelled job still names the culprit",
                  s["culprits"] == [3] and st == "failure" and desc.startswith(CULPRIT_PREFIX) and gh.reruns == [],
                  f"{s} {st} {desc!r}")
            check("mix: the culprit status names only the really failing context(s), never the cancelled one",
                  all(c in desc for c in real) and not any(c in desc for c in gh.carrier_override), f"{desc!r} real={real}")
            gh.carrier_override = {}
            reset()

            # #13400 (carrier #13501 blamed #13137): gates that PASSED on content but overran their budget,
            # or were NOT EVALUATED, are inconclusive exactly like a cancelled job — never a culprit.
            for kind in ("budget", "not_evaluated"):
                open_only("p1")
                tick()
                gh.carrier_override = {"gates (data)": "failure", "Validate manifests": "failure"}
                gh.gate_records = {"data": [{"id": "security-checklist-money-path", "status": "failed", "failure_kind": kind},
                                            {"id": "other", "status": "ok", "failure_kind": None}]}
                s = settle_once()
                check(f"{kind}: a carrier red only for {kind} gates blames nobody, re-runs once and is in flight",
                      s["culprits"] == [] and s["bisected"] == [] and len(gh.reruns) == 1 and s["in_flight"]
                      and mt_status(1)[0] != "failure" and not gh.comments, f"{s} reruns={gh.reruns} status={mt_status(1)}")
                s = settle_once()
                check(f"{kind}: still red the same way after the one rerun -> closed with no verdict",
                      s["culprits"] == [] and s["inconclusive"] and len(gh.reruns) == 1 and not open_carriers()
                      and mt_status(1)[0] != "failure", f"{s} reruns={gh.reruns}")
                gh.carrier_override, gh.gate_records = {}, {}
                gh.reruns.clear()
                reset()
            # Control: the same red with a CONTENT failure in the shard still names the culprit.
            open_only("p1")
            tick()
            gh.carrier_override = {"gates (data)": "failure", "Validate manifests": "failure"}
            gh.gate_records = {"data": [{"id": "a", "status": "failed", "failure_kind": "budget"},
                                        {"id": "b", "status": "failed", "failure_kind": "content"}]}
            s = settle_once()
            check("budget control: a content failure next to a budget overrun is still a culprit",
                  s["culprits"] == [1] and gh.reruns == [] and mt_status(1)[0] == "failure", f"{s} {mt_status(1)}")
            gh.carrier_override, gh.gate_records = {}, {}
            reset()
            # Control: no machine-readable verdict (artifact missing / pre-#13400 runner) stays conclusive.
            open_only("p1")
            tick()
            gh.carrier_override = {"gates (data)": "failure"}
            s = settle_once()
            check("budget control: a red shard with no gate-results artifact is still a culprit",
                  s["culprits"] == [1] and gh.reruns == [], str(s))
            gh.carrier_override = {}
            reset()

            # #13400 livelock (2026-10-24): a gate whose time grows with the train overran its budget on
            # four carriers of the SAME five members. A multi-member carrier closed with no verdict for a
            # GATE reason halves the next batch — bisect statuses, no culprit, no failure on anyone.
            def bisect_of(n):
                return bisect_size(gh.app_statuses(gh.prs[n]["head"]["sha"]))

            open_only("p1", "p2", "p4")
            tick()
            gh.carrier_override = {"gates (data)": "failure", "Validate manifests": "failure"}
            gh.gate_records = {"data": [{"id": "security-checklist-money-path", "status": "failed", "failure_kind": "budget"}]}
            settle_once()                       # the one rerun
            s = settle_once()                   # still budget-red: closed with no verdict
            check("budget livelock: a multi-member carrier closed for a gate budget halves the next batch",
                  s["inconclusive"] and s["culprits"] == [] and s["bisected"] == [[1, 2, 4]]
                  and [bisect_of(n) for n in (1, 2, 4)] == [1, 1, 1] and not open_carriers()
                  and not gh.comments and all(mt_status(n)[0] != "failure" for n in (1, 2, 4)),
                  f"{s} sizes={[bisect_of(n) for n in (1, 2, 4)]} comments={gh.comments}")
            gh.carrier_override, gh.gate_records = {}, {}
            _, st = tick()
            newest = max(gh.carriers.values(), key=lambda c: c["number"]) if gh.carriers else None
            check("budget livelock: the rebuilt carrier carries the halved batch, not the same five again",
                  st == "published" and newest is not None and newest["state"] == "open"
                  and len((parse_own_chain(gh.g, newest["head"]["sha"]) or ("", []))[1]) == 1,
                  f"st={st} newest={newest}")
            drain()
            check("budget livelock: once green, every member still merges through verified carriers",
                  sorted(gh.merged) == [1, 2, 4], str(gh.merged))
            gh.reruns.clear()
            reset()

            # #13400 follow-up, the REAL 2026-10-25 sequence (#13801..#13817): the OLDEST candidate is
            # dropped by every build (its squash is a no-op on main — #13697), so it never rides a carrier
            # and never receives the halving status. The cap used to be read off it alone: the halved
            # members were rebuilt into a full-size carrier again, six times in five hours.
            gh.g("checkout", "-q", "-f", "main")
            gh.g("merge", "-q", "--squash", "p1")
            subprocess.run([sys.executable, SYN_GENERATOR], cwd=origin, check=True, env=base_env())
            gh.g("add", "-A")
            gh.g("commit", "-q", "-m", "main already carries p1's change: p1's squash is a no-op")
            open_only("p1", "p2", "p4")
            tick()
            first = max(gh.carriers.values(), key=lambda c: c["number"]) if gh.carriers else None
            first_members = [n for n, _ in (parse_own_chain(gh.g, first["head"]["sha"]) or ("", []))[1]] if first else []
            gh.carrier_override = {"gates (data)": "failure", "Validate manifests": "failure"}
            gh.gate_records = {"data": [{"id": "security-checklist-money-path", "status": "failed", "failure_kind": "budget"}]}
            settle_once()
            s = settle_once()
            gh.carrier_override, gh.gate_records = {}, {}
            _, st = tick()
            newest = max(gh.carriers.values(), key=lambda c: c["number"]) if gh.carriers else None
            rebuilt = [n for n, _ in (parse_own_chain(gh.g, newest["head"]["sha"]) or ("", []))[1]] if newest else []
            check("budget livelock (oldest never rides): halving converges although the oldest candidate "
                  "carries no bisect status",
                  first_members == [2, 4] and s["bisected"] == [[2, 4]] and bisect_of(1) is None
                  and st == "published" and newest is not first and rebuilt == [2],
                  f"first={first_members} s={s} st={st} rebuilt={rebuilt} sizes={[bisect_of(n) for n in (1, 2, 4)]}")
            drain()
            check("budget livelock (oldest never rides): both halved members still merge",
                  sorted(gh.merged) == [2, 4], str(gh.merged))
            gh.reruns.clear()
            reset()

            # Review of #13840: a DROPPED candidate carrying `bisect size 1` must not pin the train at one.
            gh.g("checkout", "-q", "-f", "main")
            gh.g("merge", "-q", "--squash", "p1")
            subprocess.run([sys.executable, SYN_GENERATOR], cwd=origin, check=True, env=base_env())
            gh.g("add", "-A")
            gh.g("commit", "-q", "-m", "main already carries p1's change: p1's squash is a no-op")
            open_only("p1", "p2", "p4")
            gh.post_status(gh.prs[1]["head"]["sha"], "pending", "bisect size 1")
            _, st = tick()
            newest = max(gh.carriers.values(), key=lambda c: c["number"]) if gh.carriers else None
            got = [n for n, _ in (parse_own_chain(gh.g, newest["head"]["sha"]) or ("", []))[1]] if newest else []
            check("dropped candidate's bisect size: a no-op oldest PR at size 1 does not pin the train",
                  st == "published" and got == [2, 4] and bisect_of(1) == 1, f"st={st} members={got}")
            drain()
            check("dropped candidate's bisect size: both members still merge", sorted(gh.merged) == [2, 4], str(gh.merged))
            gh.reruns.clear()
            reset()

            # EARLY close (#13400): the inconclusive gate verdict is known minutes into CI; the rest of CI
            # (~1 h on 2026-10-24) cannot change it. Closed now, halved, nobody blamed, no rerun.
            open_only("p1", "p2", "p4")
            tick()
            gh.carrier_override = {"gates (data)": "failure", "Validate manifests": "failure", "derived": None}
            gh.gate_records = {"data": [{"id": "security-checklist-money-path", "status": "failed", "failure_kind": "budget"}]}
            s = settle_once()
            check("early close: an inconclusive gate shard with other CI still running closes now and halves",
                  s["inconclusive"] and s["bisected"] == [[1, 2, 4]] and s["culprits"] == [] and gh.reruns == []
                  and not open_carriers() and [bisect_of(n) for n in (1, 2, 4)] == [1, 1, 1]
                  and all(mt_status(n)[0] != "failure" for n in (1, 2, 4)) and not gh.comments,
                  f"{s} reruns={gh.reruns} sizes={[bisect_of(n) for n in (1, 2, 4)]}")
            gh.carrier_override, gh.gate_records = {}, {}
            reset()

            # A gate shard still RUNNING (pending) says nothing yet: the carrier stays in flight, untouched.
            open_only("p1", "p2", "p4")
            tick()
            gh.carrier_override = {"gates (data)": None, "Validate manifests": None, "derived": None}
            gh.gate_records = {"data": [{"id": "security-checklist-money-path", "status": "failed", "failure_kind": "budget"}]}
            s = settle_once()
            check("early close: a pending gate shard does nothing - in flight, no close, no halving",
                  s["in_flight"] and len(open_carriers()) == 1 and not s["inconclusive"] and s["bisected"] == []
                  and [bisect_of(n) for n in (1, 2, 4)] == [None] * 3, f"{s}")
            gh.carrier_override, gh.gate_records = {}, {}
            reset()

            # A REAL (content) gate FAIL with other CI running is NOT closed as no-verdict: it takes the
            # culprit/bisect path, which already acts on the first red required context (unchanged here).
            open_only("p1", "p2", "p4")
            tick()
            gh.carrier_override = {"gates (data)": "failure", "Validate manifests": "failure", "derived": None}
            gh.gate_records = {"data": [{"id": "x", "status": "failed", "failure_kind": "content"}]}
            s = settle_once()
            check("early close: a real gate FAIL with CI running is never an inconclusive close",
                  not s["inconclusive"] and s["culprits"] == [] and gh.reruns == []
                  and s["bisected"] == [[1, 2, 4]], f"{s}")
            gh.carrier_override, gh.gate_records = {}, {}
            reset()

            # Single member: early close cannot change the batch, so the wait-then-rerun-once path stays.
            open_only("p1")
            tick()
            gh.carrier_override = {"gates (data)": "failure", "Validate manifests": "failure", "derived": None}
            gh.gate_records = {"data": [{"id": "security-checklist-money-path", "status": "failed", "failure_kind": "budget"}]}
            s = settle_once()
            check("early close: a single-member carrier keeps waiting (in flight, open)",
                  s["in_flight"] and len(open_carriers()) == 1 and not s["inconclusive"], f"{s}")
            gh.carrier_override, gh.gate_records = {}, {}
            gh.reruns.clear()
            reset()

            # Control: a carrier closed with no verdict only for CANCELLED jobs (transient) is not halved.
            open_only("p1", "p2", "p4")
            tick()
            gh.carrier_override = {"gates (data)": "cancelled"}
            settle_once()
            s = settle_once()
            check("budget livelock control: a cancelled-only no-verdict close leaves the batch size alone",
                  s["inconclusive"] and s["bisected"] == [] and [bisect_of(n) for n in (1, 2, 4)] == [None] * 3,
                  f"{s} sizes={[bisect_of(n) for n in (1, 2, 4)]}")
            gh.carrier_override = {}
            gh.reruns.clear()
            reset()

            # A legacy (pre-fix) culprit mark is re-evaluated: the member rides again and merges when green.
            open_only("p1")
            gh.post_status(gh.prs[1]["head"]["sha"], "failure", "isolated as the cause of a red train over main 0123456789ab")
            drain()
            check("legacy mark: a pre-fix culprit status is re-evaluated and overwritten by the next train",
                  gh.merged == [1] and mt_status(1)[0] == "success", f"{gh.merged} {mt_status(1)}")
            reset()

            # Rerun refused by the API: no verdict, nobody blamed.
            open_only("p1")
            tick()
            gh.carrier_override, gh.rerun_ok = {"gates (data)": "cancelled"}, False
            s = settle_once()
            check("cancelled: a refused rerun closes the carrier with no verdict, nobody blamed",
                  s["culprits"] == [] and s["inconclusive"] and not open_carriers() and mt_status(1)[0] != "failure", str(s))
            gh.carrier_override, gh.rerun_ok = {}, True
            gh.reruns.clear()
            reset()

            # Semantic pair green alone, red together: one merges, the other is the culprit.
            # The fake squashes the PR head, not the carrier, so main would lack the regenerated derived
            # file and read red on `derived` — and no train is built over a red main. Not under test here.
            open_only("pA", "pB")
            gh.carrier_override = gh.main_override = {"derived": "success"}
            drain()
            gh.carrier_override, gh.main_override = {}, {}
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

            # A baseline edit under a generator overlay root: the overlay puts main's GENERATORS
            # back, never main's copy of a trainable data file over the member's (which the regen
            # commit would then carry as a revert, and publish would reject as a non-derived path).
            open_only("pT")
            drain()
            check("baseline: a member loosening a baseline under .github/scripts rides a train and merges intact",
                  gh.merged == [62] and pathlib.Path(origin, SYN_BASELINE).read_text() == "loosened\n",
                  f"{gh.merged} {gh.comments}")
            reset()

            # A candidate turning green BETWEEN build and publish reshapes the live batch. Publish
            # takes the build's declared members (each re-checked live) instead of re-planning, so
            # a legitimate bundle is not rejected for a race (run 39139647341: #13209 went green).
            open_only("p2")
            counter[0] += 1
            bdir = clone("build")
            b = TrainBuilder(bdir, regen_commands=(f"{sys.executable} -I {SYN_GENERATOR}",), overlay_roots=(".github/scripts",))
            out = os.path.join(root, f"bundle-{counter[0]}")
            mode_build(gh, Git(bdir), b, "o/r", out, 8)
            open_only("p1")      # older, so it sorts FIRST in the live batch
            st = mode_publish(gh, Git(clone("publish")), "o/r", out, str(counter[0]), "1", 8)
            check("race: a candidate going green after build does not reject the built train",
                  st == "published", st)
            reset()
            # NEGATIVE: a declared member that is no longer a live candidate at that head.
            open_only("p1", "p2")
            counter[0] += 1
            bdir = clone("build")
            b = TrainBuilder(bdir, regen_commands=(f"{sys.executable} -I {SYN_GENERATOR}",), overlay_roots=(".github/scripts",))
            out = os.path.join(root, f"bundle-{counter[0]}")
            mode_build(gh, Git(bdir), b, "o/r", out, 8)
            gh.prs[2]["auto_merge"] = None
            st = mode_publish(gh, Git(clone("publish")), "o/r", out, str(counter[0]), "1", 8)
            check("race: a declared member that was disarmed after build is refused",
                  st == "rejected" and not any(c["state"] == "open" for c in gh.carriers.values()), st)
            gh.prs[2]["auto_merge"] = {"x": 1}
            # NEGATIVE: a manifest naming a PR that was never a candidate (forged by the build job).
            pathlib.Path(out, MANIFEST_FILE).write_text(f"1 {gh.prs[1]['head']['sha']}\n2 {gh.prs[2]['head']['sha']}\n35 {gh.g('rev-parse', 'pW').stdout.strip()}\n")
            st = mode_publish(gh, Git(clone("publish")), "o/r", out, str(counter[0]), "1", 8)
            check("manifest: a member that is not a live candidate is refused", st == "rejected", st)
            pathlib.Path(out, MANIFEST_FILE).write_text(f"1 {'0' * 40}\n")
            st = mode_publish(gh, Git(clone("publish")), "o/r", out, str(counter[0]), "1", 8)
            check("manifest: a member at a head other than its live head is refused", st == "rejected", st)
            pathlib.Path(out, MANIFEST_FILE).write_text("1 HEAD; rm -rf /\n")
            st = mode_publish(gh, Git(clone("publish")), "o/r", out, str(counter[0]), "1", 8)
            check("manifest: a malformed line is refused", st == "rejected", st)
            pathlib.Path(out, MANIFEST_FILE).write_text(f"1 {gh.prs[1]['head']['sha']}\n2 {gh.prs[2]['head']['sha']}\n")
            st = mode_publish(gh, Git(clone("publish")), "o/r", out, str(counter[0]), "1", 1)
            check("manifest: a declared batch over the size cap is refused", st == "rejected", st)
            os.unlink(os.path.join(out, MANIFEST_FILE))
            st = mode_publish(gh, Git(clone("publish")), "o/r", out, str(counter[0]), "1", 8)
            check("manifest: a bundle with no member manifest is refused", st == "rejected", st)
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
            carriers_before = len(gh.carriers)
            drain()
            # They may merge, but only through the strict lane (no train, nothing executed).
            check("gate-file members: never ride a train, merge only via the strict lane, nothing leaks",
                  len(gh.carriers) == carriers_before and all(gh.app_statuses(gh.g("rev-parse", b).stdout.strip()).get(STATUS_CONTEXT, ("", ""))[1]
                                          .startswith("strict lane: ") for b, n in (("pG", 32), ("pW", 35)) if n in gh.merged)
                  and not os.path.exists(os.path.join(origin, "leaked.txt")) and not gh.comments, f"{gh.merged} {gh.comments}")
            reset()

            # #13400: a gate-code-only member rides a train, and never one carrying a regeneration
            # commit. pR (older) needs regeneration, pK (check script) does not: build trains pR
            # first and defers pK WITHOUT a drop notice; pK then rides its own train.
            open_only("pR", "pK")
            # The fake merges member HEADS, not the carrier tree, so after pR main lacks the regenerated
            # file that real main would carry from the carrier: hold it green, then commit the
            # regeneration on main as the real merge would have, and let pK ride.
            gh.main_override = {"derived": "success"}
            drain()
            check("gate code: deferred while main itself would need regeneration (never mixed, never dropped)",
                  gh.merged == [21] and gh.prs[38]["state"] == "open" and not gh.comments, f"{gh.merged} {gh.comments}")
            gh.g("checkout", "-q", "-f", "main")
            subprocess.run([sys.executable, SYN_GENERATOR], cwd=origin, check=True, env=base_env())
            gh.g("add", "derived")
            gh.g("commit", "-q", "-m", "regenerated (as the carrier carried it)")
            gh.main_override = {}
            drain()
            pk_head = gh.prs[38]["head"]["sha"]
            check("gate code: a check-script PR merges through a TRAIN (not the strict lane), with no notice",
                  sorted(gh.merged) == [21, 38] and not gh.updates
                  and not gh.app_statuses(pk_head).get(STATUS_CONTEXT, ("", ""))[1].startswith("strict lane:")
                  and not gh.comments, f"{gh.merged} updates={gh.updates} {gh.comments}")
            regen_with_gate = []
            for t in gh.carriers.values():
                chain = parse_own_chain(gh.g, t["head"]["sha"])
                if chain and 38 in [n for n, _h in chain[1]]:
                    msgs = gh.g("log", "--format=%B", f"{chain[0]}..{t['head']['sha']}").stdout
                    regen_with_gate.append(OWN_REGEN_MSG.strip() in msgs)
            check("gate code: no carrier holding the gate-code member carries a regeneration commit",
                  regen_with_gate and not any(regen_with_gate), str(regen_with_gate))
            reset()
            # The publish half (the security boundary): a build that IGNORES the separation and
            # emits pK + pR + a regeneration commit is rejected; nothing merges.
            open_only("pR", "pK")
            bdir = clone("build")
            b = TrainBuilder(bdir, regen_commands=(f"{sys.executable} -I {SYN_GENERATOR}",), overlay_roots=(".github/scripts",),
                             untrainable=Untrainable(SYN_INVENTORY))
            b.separate_gate_code = False
            b.declared_inputs = regen_inventory_fragments(SYN_INVENTORY)
            out = os.path.join(root, "bundle-gate-regen")
            mode_build(gh, Git(bdir), b, "o/r", out, 8)
            man = pathlib.Path(out, MANIFEST_FILE).read_text() if os.path.exists(os.path.join(out, MANIFEST_FILE)) else ""
            status = mode_publish(gh, Git(clone("publish")), "o/r", out, "998", "1", 8)
            check("gate code: CONTROL — with separation off, build emits both members and a regeneration commit",
                  "21 " in man and "38 " in man, man)
            check("gate code: publish rejects a train mixing gate code with a regeneration commit; nothing merges",
                  status == "rejected" and gh.merged == [], f"publish={status}")
            reset()

            # Review: a gate-code member adding a symlink never rides (qualifies refuses it), and a
            # build that forces it past qualifies is rejected by publish as the member's fault, so
            # the batch halves (bisect) instead of merging.
            open_only("pS", "p1")
            cands, _apps = live_candidates(gh, Git(clone("settle")), "o/r", gh.required(), base0, Untrainable(SYN_INVENTORY))
            check("gate code + symlink: never a train candidate", [m.number for m in cands] == [1], str([m.number for m in cands]))
            bdir = clone("build")
            b = TrainBuilder(bdir, regen_commands=(), overlay_roots=(".github/scripts",), untrainable=Untrainable(SYN_INVENTORY))
            ms = []
            for br in ("p1", "pS"):
                b.git("fetch", "-q", "origin", br)
                h = b.git("rev-parse", "FETCH_HEAD").stdout.strip()
                ms.append(Member(numbers[br], br, "", h, br, "t", ref=h))
            tip, _a, _d = b.build(base0, ms)
            pg = Git(clone("publish"))
            pg("fetch", "-q", bdir, tip)
            try:
                verify_bundle_train(pg, base0, ms, tip, ["derived/*"])
                rej = None
            except Reject as exc:
                rej = exc
            check("gate code + symlink: forced past qualifies, publish rejects it as the member's fault (bisect)",
                  rej is not None and rej.member_fault and "symlink" in str(rej), str(rej))
            reset()

            # Review: during regeneration the generators see MAIN's copy of gate code (as of
            # generators), and the member's copy is back in the tree before anything is committed.
            gate_probe = os.path.join(root, "gate-probe.txt")
            bdir = clone("build")
            b = TrainBuilder(bdir, regen_commands=(f"{sys.executable} -c \"import shutil; shutil.copy('{SYN_MAIN_GATE_CODE}', {gate_probe!r})\"",),
                             overlay_roots=(".github/scripts",), untrainable=Untrainable(SYN_INVENTORY))
            b.git("fetch", "-q", "origin", "pM")
            hm = b.git("rev-parse", "FETCH_HEAD").stdout.strip()
            tip, applied, _d = b.build(base0, [Member(39, "pM", "", hm, "pM", "t", ref=hm)])
            seen = pathlib.Path(gate_probe).read_text() if os.path.exists(gate_probe) else ""
            check("gate code: generators read main's copy during regeneration; the train keeps the member's, no regen commit",
                  applied == [39] and seen == "main\n"
                  and b.git("show", f"{tip}:{SYN_MAIN_GATE_CODE}").stdout == "member\n"
                  and b.git("log", "-1", "--format=%s", tip).stdout.strip() != REGEN_COMMIT_SUBJECT,
                  f"applied={applied} seen={seen!r}")
            reset()

            # NEGATIVE (review 5): a bundle generator deeper than any fixed glob.
            marker = os.path.join(root, "deep-generator-ran")
            open_only("pD")
            carriers_before = len(gh.carriers)
            drain()
            check("deep generator: the PR never rides a train, merges only via the strict lane, its generator never ran",
                  len(gh.carriers) == carriers_before and not os.path.exists(marker)
                  and (gh.merged == [] or gh.app_statuses(gh.g("rev-parse", "pD").stdout.strip())
                       .get(STATUS_CONTEXT, ("", ""))[1].startswith("strict lane: ")), f"{gh.merged} ran={os.path.exists(marker)}")
            bdir = clone("build")
            b = TrainBuilder(bdir, regen_commands=(f"{sys.executable} {SYN_GENERATOR}",), overlay_roots=(".github/scripts",),
                             untrainable=Untrainable(SYN_INVENTORY))
            b.git("fetch", "-q", "origin", "pD")
            head = b.git("rev-parse", "FETCH_HEAD").stdout.strip()
            _tip, applied, dropped = b.build(base0, [Member(41, "pD", "", head, "pD", "t", ref=head)])
            check("deep generator: forced past qualifies, build drops it and it still never runs",
                  applied == [] and dropped == [41] and not os.path.exists(marker), f"{applied} {dropped}")
            b.git("checkout", "-q", "-f", "--detach", head)       # the file present in the working tree
            b.declared_inputs = regen_inventory_fragments(SYN_INVENTORY)   # as mode_build sets it from main
            b.quarantine_new_executables(base0)
            b.regenerate()
            check("deep generator: quarantine removes a not-on-main executable before any generator runs",
                  b.quarantined == [SYN_DEEP_GENERATOR] and not os.path.exists(marker)
                  and os.path.exists(os.path.join(bdir, SYN_DEEP_GENERATOR)), f"{b.quarantined}")
            reset()

            # NEGATIVE (review 6a): a newline in a generator's NAME. git quotes such a path in its
            # default output, so string checks miss it while `find`/fnmatch still match it.
            nl_marker, sh_marker = os.path.join(root, "newline-gen-ran"), os.path.join(root, "shadow-ran")
            open_only("pN")
            sg = Git(clone("settle"))
            cands, _apps = live_candidates(gh, sg, "o/r", gh.required(), base0, Untrainable(SYN_INVENTORY))
            check("newline path: the PR never qualifies (control character, fail closed)", all(m.number != 51 for m in cands))
            open_only("pQ")
            cands, _apps = live_candidates(gh, Git(clone("settle")), "o/r", gh.required(), base0, Untrainable(SYN_INVENTORY))
            check("newline path: a DATA file with a newline is refused live too (read with -z, not split on lines)",
                  all(m.number != 53 for m in cands), str([m.number for m in cands]))
            gh.prs[53]["state"] = "closed"
            drain()
            check("newline path: nothing merges and the generator never ran", gh.merged == [] and not os.path.exists(nl_marker))
            bdir = clone("build")
            b = TrainBuilder(bdir, regen_commands=(f"{sys.executable} -I {SYN_GENERATOR}",), overlay_roots=(".github/scripts",),
                             untrainable=Untrainable(SYN_INVENTORY))
            b.git("fetch", "-q", "origin", "pN")
            head = b.git("rev-parse", "FETCH_HEAD").stdout.strip()
            _tip, applied, dropped = b.build(base0, [Member(51, "pN", "", head, "pN", "t", ref=head)])
            check("newline path: forced past qualifies, build drops it and it still never runs",
                  applied == [] and dropped == [51] and not os.path.exists(nl_marker), f"{applied} {dropped}")
            reset()

            # NEGATIVE (review 7): renaming regen-derived.sh to docs/x.md.
            # stale-ref-ok: docs/x.md exists only inside the synthetic repository this test builds.
            sg = Git(clone("settle"))
            sg("fetch", "-q", "origin", "pV")
            rn = changed_paths(sg, f"{base0}...FETCH_HEAD")
            check("rename: changed_paths reports BOTH the deleted gate script and the new name",
                  REGEN_INVENTORY in rn and "docs/x.md" in rn, str(rn))
            open_only("pV")
            cands, _apps = live_candidates(gh, Git(clone("settle")), "o/r", gh.required(), base0, Untrainable(SYN_INVENTORY))
            check("rename: a PR renaming regen-derived.sh to docs/x.md never qualifies", all(m.number != 61 for m in cands))
            bdir = clone("build")
            b = TrainBuilder(bdir, regen_commands=(), overlay_roots=(".github/scripts",), untrainable=Untrainable(SYN_INVENTORY))
            b.git("fetch", "-q", "origin", "pV")
            hv = b.git("rev-parse", "FETCH_HEAD").stdout.strip()
            _tip, applied, dropped = b.build(base0, [Member(61, "pV", "", hv, "pV", "t", ref=hv)])
            check("rename: forced past qualifies, build's staged check drops it", applied == [] and dropped == [61], f"{applied} {dropped}")
            reset()

            # NEGATIVE (review 6b): a module shadowing `yaml` next to main's heredoc bundle script.
            def shadow_run(quarantine: bool, safepath: bool) -> bool:
                if os.path.exists(sh_marker):
                    os.unlink(sh_marker)
                d = clone("build")
                bb = TrainBuilder(d, regen_commands=(f"{sys.executable} -I {SYN_GENERATOR}",), overlay_roots=(".github/scripts",),
                                  untrainable=Untrainable(SYN_INVENTORY))
                bb.declared_inputs = regen_inventory_fragments(SYN_INVENTORY)
                if not safepath:
                    bb.env.pop("PYTHONSAFEPATH", None)
                if not quarantine:
                    bb.quarantine_new_executables = lambda base: None
                bb.git("fetch", "-q", "origin", "pY")
                h = bb.git("rev-parse", "FETCH_HEAD").stdout.strip()
                bb.build(base0, [Member(52, "pY", "", h, "pY", "t", ref=h)])
                return os.path.exists(sh_marker)
            check("shadow module: CONTROL — with neither layer the shadow yaml.py IS imported", shadow_run(False, False))
            check("shadow module: PYTHONSAFEPATH alone keeps it inert", not shadow_run(False, True))
            check("shadow module: quarantine alone keeps it inert", not shadow_run(True, False))
            open_only("pY")
            drain()
            check("shadow module: through real ticks it is inert (the PR is data; the module never runs)",
                  not os.path.exists(sh_marker), str(gh.merged))
            reset()

            # Contrast: quarantining EVERY added file (not only non-inputs) hides a PR's new input
            # from the generators, so the train is wrong and main's freshness gate goes red.
            def regen_ok(keep: bool) -> bool:
                d = clone("build")
                bb = TrainBuilder(d, regen_commands=(f"{sys.executable} -I {SYN_GENERATOR}",), overlay_roots=(".github/scripts",),
                                  untrainable=Untrainable(SYN_INVENTORY))
                bb.declared_inputs, bb.keep_declared_inputs = regen_inventory_fragments(SYN_INVENTORY), keep
                bb.git("fetch", "-q", "origin", "pR")
                h = bb.git("rev-parse", "FETCH_HEAD").stdout.strip()
                bb.build(base0, [Member(21, "pR", "", h, "pR", "t", ref=h)])
                return subprocess.run([sys.executable, "ci.py"], cwd=d, capture_output=True).returncode == 0
            check("quarantine: declared inputs stay visible, so a PR adding one regenerates correctly", regen_ok(True))
            check("quarantine: CONTRAST — hiding every added file makes the same train red", not regen_ok(False))

            # THE BOUNDARY: a fully compromised build emits an arbitrary bundle. Publish and settle
            # must refuse to merge every variant; the only reachable outcome is a red or wasted train.
            open_only("p1")
            bdir = clone("build")
            b = TrainBuilder(bdir, regen_commands=(), overlay_roots=(".github/scripts",))
            b.git("fetch", "-q", "origin", "p1")
            h1 = b.git("rev-parse", "FETCH_HEAD").stdout.strip()
            honest, _a, _d = b.build(base0, [Member(1, "p1", "", h1, "p1", "t", ref=h1)])

            def commit_on(parent, edits, msg, link=None, parents=None):
                b.git("checkout", "-q", "-f", "--detach", parent)
                for rel, text in edits.items():
                    pth = pathlib.Path(bdir, rel)
                    pth.parent.mkdir(parents=True, exist_ok=True)
                    pth.write_text(text)
                if link:
                    os.symlink(link[1], os.path.join(bdir, link[0]))
                b.git("add", "-A")
                if parents:
                    tree = b.git("write-tree").stdout.strip()
                    return b.git("commit-tree", tree, *[x for q in parents for x in ("-p", q)], "-m", msg).stdout.strip()
                b.git("commit", "-q", "--allow-empty", "-m", msg)
                return b.git("rev-parse", "HEAD").stdout.strip()

            derived_now = pathlib.Path(origin, "derived/index.txt").read_text()
            variants = [
                ("a regeneration commit rewriting a non-derived file (ci.py)",
                 commit_on(honest, {"ci.py": "print('')\n"}, REGEN_COMMIT_SUBJECT), "rejected"),
                ("swapped PR content under the member's own commit",
                 commit_on(base0, {"docs/one.md": "not what the PR says\n"}, "p1"), "rejected"),
                ("an extra commit beyond the regeneration commit",
                 commit_on(commit_on(honest, {"derived/index.txt": derived_now}, REGEN_COMMIT_SUBJECT), {"docs/x.md": "x\n"}, "x"), "rejected"),
                ("a regeneration commit adding a symlink",
                 commit_on(honest, {}, REGEN_COMMIT_SUBJECT, link=("derived/link", "/etc/passwd")), "rejected"),
                ("a merge commit smuggling a second parent",
                 commit_on(honest, {}, REGEN_COMMIT_SUBJECT, parents=[honest, h1]), "rejected"),
                ("a regeneration commit with WRONG content inside the inventory",
                 commit_on(honest, {"derived/index.txt": "garbage\n"}, REGEN_COMMIT_SUBJECT), "published"),
            ]
            for i, (label, rev, want) in enumerate(variants):
                gh.statuses.clear()
                gh.comments.clear()
                for c in gh.carriers.values():
                    c["state"] = "closed"
                b.git("update-ref", BUNDLE_REF, rev)
                d = os.path.join(root, f"compromised-{i}")
                os.makedirs(d)
                b.git("bundle", "create", os.path.join(d, BUNDLE_FILE), BUNDLE_REF, f"^{base0}")
                write_manifest(os.path.join(d, MANIFEST_FILE), [Member(1, "p1", "", h1, "p1", "t")])
                status = mode_publish(gh, Git(clone("publish")), "o/r", d, str(900 + i), "1", 8)
                mode_settle(gh, Git(clone("settle")), "o/r", False)
                check(f"compromised build: {label} -> publish {want}, nothing merges",
                      status == want and gh.merged == [], f"publish={status} merged={gh.merged}")
            # ... and a train over an older main, after main moved.
            gh.statuses.clear()
            gh.g("checkout", "-q", "-f", "main")
            pathlib.Path(origin, "svc").mkdir(exist_ok=True)
            pathlib.Path(origin, "svc/later.txt").write_text("x\n")
            gh.g("add", "-A")
            gh.g("commit", "-q", "-m", "main moves")
            b.git("update-ref", BUNDLE_REF, honest)
            d = os.path.join(root, "compromised-stale")
            os.makedirs(d)
            b.git("bundle", "create", os.path.join(d, BUNDLE_FILE), BUNDLE_REF, f"^{base0}")
            write_manifest(os.path.join(d, MANIFEST_FILE), [Member(1, "p1", "", h1, "p1", "t")])
            status = mode_publish(gh, Git(clone("publish")), "o/r", d, "999", "1", 8)
            check("compromised build: a train over an older main -> publish rejected, nothing merges",
                  status == "rejected" and gh.merged == [], f"publish={status}")
            reset()

            # NEGATIVE (review 5): the files API is capped at 3000; a long PR hides a path there.
            open_only("pW")
            api_calls = []
            gh.pr_files = lambda n: api_calls.append(n) or [f"svc/f{i}.txt" for i in range(3000)]
            cands, _apps = live_candidates(gh, Git(clone("settle")), "o/r", gh.required(), base0, Untrainable(SYN_INVENTORY))
            check("files: a 3000-entry API list hiding .github/workflows/w.yml does not admit the PR",
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
            _f, batch, _sizes = plan_batch(gh, pub, "o/r", base, 8)
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

            # Phase 1 runtime guard (ADR-0343): shadow=false alone must not merge. Each negative case
            # changes ONE thing from the all-satisfied ruleset, so it cannot pass for another reason.
            def guarded(label, want_merged, **rs):
                open_only("p1")
                tick()
                gh.rs_checks, gh.rs_strict, gh.app_id = ([{"context": STATUS_CONTEXT, "integration_id": 4242}], False, "4242")
                for k, v in rs.items():
                    setattr(gh, k, v)
                mode_settle(gh, Git(clone("settle")), "o/r", False)
                st = gh.app_statuses(gh.prs[1]["head"]["sha"]).get(STATUS_CONTEXT, ("",))[0]
                check(f"phase-1 guard: {label}", (gh.merged == [1]) == want_merged and (want_merged or st == "success"),
                      f"merged={gh.merged} status={st}")
                gh.rs_checks, gh.rs_strict, gh.app_id = ([{"context": STATUS_CONTEXT, "integration_id": 4242}], False, "4242")
                reset()

            guarded("ruleset without merge-train -> no merge, status still posted", False, rs_checks=[{"context": "other", "integration_id": 4242}])
            guarded("merge-train unpinned -> no merge", False, rs_checks=[{"context": STATUS_CONTEXT}])
            guarded("merge-train pinned to another integration -> no merge", False, rs_checks=[{"context": STATUS_CONTEXT, "integration_id": 99}])
            guarded("strict still on -> no merge", False, rs_strict=True)
            guarded("GITOPS_APP_ID unset -> no merge", False, app_id="")
            guarded("pinned to the App, strict off -> merges", True)

            # A foreign merge landing during settle stops the merges after the first.
            open_only("p1", "p2")
            tick()
            gh.foreign_after_merge = "svc/foreign.txt"
            mode_settle(gh, Git(clone("settle")), "o/r", False)
            check("foreign merge mid-settle: merging stops, the rest waits for the next tick", gh.merged == [1], str(gh.merged))
            reset()

            # ---- strict lane (manual-lane PRs: executable .github/, generators, gates, inventory) ----
            def settle_now():
                return mode_settle(gh, Git(clone("settle")), "o/r", False)

            def strict_desc(n):
                return gh.app_statuses(gh.prs[n]["head"]["sha"]).get(STATUS_CONTEXT, ("", ""))

            # green + up to date with live main: the App posts the strict-lane status and merges it.
            open_only("pW")
            settle_now()
            st, desc = strict_desc(35)
            check("strict lane: manual-lane PR green on an up-to-date head merges with the strict-lane status",
                  gh.merged == [35] and st == "success" and desc.startswith("strict lane: green on an up-to-date head over main "),
                  f"merged={gh.merged} status={st} {desc!r}")
            reset()

            # green but BEHIND: update-branch, no merge, and only ONE manual-lane PR (the oldest) per tick.
            open_only("pW", "pW2", "p1")
            gh.prs[1]["auto_merge"] = None
            gh.merge(1, gh.prs[1]["head"]["sha"])          # main advances: pW and pW2 are now behind
            gh.merged.clear()
            s = settle_now()
            check("strict lane: green but behind gets update-branch, is not merged, one PR per tick (oldest)",
                  gh.merged == [] and gh.updates == [35] and not strict_desc(35)[0] and not strict_desc(36)[0]
                  and s.get("main_red", []) == [] and s.get("waiting", []) == [],
                  f"merged={gh.merged} updates={gh.updates} {s}")
            # The updated head runs its CI; while it does, settle reserves main (no train builds).
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {"gates (data)": None}
            s = settle_now()
            check("strict lane: an updated head whose CI is running reserves main and is not merged",
                  s["in_flight"] and gh.merged == [] and gh.updates == [35], f"{s} merged={gh.merged} updates={gh.updates}")
            gh.head_checks.clear()
            settle_now()
            check("strict lane: once green on the updated head a later tick merges it", gh.merged == [35], str(gh.merged))
            gh.updates.clear()
            reset()

            # red manual-lane PR: nothing at all.
            open_only("pW")
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {"Validate manifests": "failure"}
            settle_now()
            check("strict lane: a red manual-lane PR gets no status, no update, no merge",
                  gh.merged == [] and gh.updates == [] and not strict_desc(35)[0], f"{gh.merged} {gh.updates} {strict_desc(35)}")
            gh.head_checks.clear()
            reset()

            # red ordinary PR: no strict lane (it is the train's, and red).
            open_only("p1")
            gh.head_checks[gh.prs[1]["head"]["sha"]] = {"Validate manifests": "failure"}
            settle_now()
            check("strict lane: a red non-manual-lane PR never takes the strict lane",
                  gh.merged == [] and gh.updates == [] and not strict_desc(1)[0], f"{gh.merged} {strict_desc(1)}")
            gh.head_checks.clear()
            reset()

            # agent-pr-guard refusal and a symlink: never the strict lane, even green and up to date.
            open_only("pW")
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {"gates / agent-pr-guard": "failure"}
            settle_now()
            check("strict lane: a PR refused by agent-pr-guard never takes the strict lane",
                  gh.merged == [] and gh.updates == [] and not strict_desc(35)[0], f"{gh.merged} {strict_desc(35)}")
            gh.head_checks.clear()
            reset()
            open_only("pS")
            settle_now()
            check("strict lane: a manual-lane PR adding a symlink never takes the strict lane",
                  gh.merged == [] and gh.updates == [] and not strict_desc(37)[0], f"{gh.merged} {strict_desc(37)}")
            reset()

            # A train in flight: the strict lane does not merge (the carrier's base must stay main).
            open_only("p1")
            tick()
            gh.pending_shas.update(c["head"]["sha"] for c in gh.carriers.values() if c["state"] == "open")
            open_only("pW")
            s = settle_now()
            check("strict lane: no strict-lane merge while a train is in flight",
                  s["in_flight"] and gh.merged == [] and not strict_desc(35)[0], f"{s} merged={gh.merged}")
            gh.pending_shas.clear()
            reset()

            # main moves between reading it and merging: re-checked, no merge.
            open_only("pW")
            real_main = gh.main_sha
            gh.main_sha = lambda: "f" * 40
            settle_now()
            gh.main_sha = real_main
            check("strict lane: main re-checked right before the merge; a moved main means no merge",
                  gh.merged == [], f"merged={gh.merged}")
            reset()

            # STARVATION (#13456): a strict-lane PR is updated, and while its CI runs a fast-lane PR
            # merges (GitHub auto-merge acts on the success status settle posts). The updated head is
            # behind again the moment its CI goes green, so it is re-updated — forever. The
            # reservation must hold the fast lane and train merges, not only other strict updates.
            def automerge_fast(s):
                for n in s["fast"]:
                    if gh.prs[n]["state"] == "open":
                        gh.merge(n, gh.prs[n]["head"]["sha"])

            gh.updates.clear()
            open_only("pW", "p1")
            gh.prs[1]["auto_merge"] = None
            gh.merge(1, gh.prs[1]["head"]["sha"])          # main advances: pW is behind
            gh.merged.clear()
            settle_now()                                   # tick 1: pW updated
            open_only("pF")
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {"gates (data)": None}
            s = settle_now()                               # tick 2: pW's CI runs; pF is fast-eligible
            automerge_fast(s)
            check("reservation: while the reserved head's CI runs the fast lane is held, main does not move",
                  s["in_flight"] and s["fast"] == [] and 34 not in gh.merged
                  and not gh.app_statuses(gh.prs[34]["head"]["sha"]).get(STATUS_CONTEXT),
                  f"{s} merged={gh.merged}")
            gh.head_checks.clear()
            s = settle_now()                               # tick 3: pW green on its updated head
            automerge_fast(s)
            check("reservation: the reserved PR merges after its CI, updated exactly once (no starvation)",
                  35 in gh.merged and gh.updates == [35], f"merged={gh.merged} updates={gh.updates}")
            s = settle_now()                               # tick 4: the held fast-lane PR goes
            automerge_fast(s)
            check("reservation: the held fast-lane candidate waits one tick, then goes",
                  34 in gh.merged, f"{s} merged={gh.merged}")
            gh.updates.clear()
            reset()

            # Two strict-lane PRs may both be current: the older one's pending CI reserves main, so
            # the younger green one must not merge and invalidate that CI. Regression for #13500.
            open_only("pW", "pW2")
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {"gates (data)": None}
            s = settle_now()
            check("reservation: a green strict PR cannot merge past another strict PR's pending reservation",
                  s["in_flight"] and gh.merged == [] and not strict_desc(36)[0],
                  f"{s} merged={gh.merged} #36={strict_desc(36)}")
            gh.head_checks.clear()
            s = settle_now()
            check("reservation: the strict reservation holder gets served before the younger green PR",
                  gh.merged == [35], f"{s} merged={gh.merged}")
            reset()

            # GREEN CARRIER FIRST (#13400): a train whose CI went green while a strict-lane reservation
            # is active merges at once — before strict_lane runs — instead of being held and then voided
            # by the strict merge (carrier #13853 held for #13806, rebuilt as #13857; #13790 by #13679).
            # Before this rule both cases below merged the strict PR and rejected the carrier.
            open_only("p1")
            tick()
            gh.pending_shas.update(c["head"]["sha"] for c in gh.carriers.values() if c["state"] == "open")
            open_only("pW")
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {"gates (data)": None}
            gh.pending_shas.clear()                        # the train finishes green; pW still running
            s = settle_now()
            check("green carrier first: a verified green carrier merges past a running strict reservation",
                  gh.merged == [1] and not s["rejected"] and not any(c["state"] == "open" for c in gh.carriers.values()),
                  f"{s} merged={gh.merged}")
            gh.head_checks.clear()
            gh.updates.clear()
            s = settle_now()
            check("green carrier first: the pre-empted strict PR, now behind, is update-branched next tick",
                  gh.merged == [1] and gh.updates == [35], f"{s} merged={gh.merged} updates={gh.updates}")
            gh.updates.clear()
            reset()

            # BOUNDED STRICT DEFERRAL (#13400): a strict reservation whose CI is still running yields
            # ONCE per strict head to a carrier built from green candidates DURING that CI. The deferral
            # is recorded as DEFER_CONTEXT on the strict head when the carrier is published, and the
            # update-branch that follows the carrier's merge inherits it through its first parent.
            def deferral(sha):
                return gh.app_statuses(sha).get(DEFER_CONTEXT, ("", ""))[0]

            def running_reservation_and_green_candidate():
                open_only("pW")
                gh.head_checks[gh.prs[35]["head"]["sha"]] = {"gates (data)": None}   # pW up to date, CI running
                open_only("p1")                                                    # a green train candidate

            # (1) no deferral yet: a carrier is built and published, and the deferral recorded at publish.
            running_reservation_and_green_candidate()
            pw_a = gh.prs[35]["head"]["sha"]
            s, st = tick()
            check("deferral: running reservation + green candidate + no deferral -> a carrier is built",
                  st == "published" and any(c["state"] == "open" for c in gh.carriers.values())
                  and deferral(pw_a) == "success" and gh.merged == [], f"{s} st={st} defer={deferral(pw_a)!r}")
            gh.head_checks.clear()
            reset()

            # (2) a deferral already recorded for this strict head: held, no carrier.
            running_reservation_and_green_candidate()
            gh.post_status(gh.prs[35]["head"]["sha"], "success", "yielded once", context=DEFER_CONTEXT)
            s, st = tick()
            check("deferral: a strict head that already yielded once is held (no carrier built)",
                  st == "in-flight" and s["in_flight"] and not any(c["state"] == "open" for c in gh.carriers.values()),
                  f"{s} st={st}")
            gh.head_checks.clear()
            reset()

            # (3) GREEN CARRIER FIRST (#13400), rebuilt from #13853/#13806 on 2026-10-25: the strict
            # head and the carrier built under its CI are BOTH green in one tick. The carrier merges
            # first; no `merge-train` success is ever posted on the strict head in that tick (GitHub
            # auto-merge would act on it); the strict PR is update-branched on the next tick and
            # merges after its CI — no second carrier, since its update inherits the deferral record.
            gh.updates.clear()
            running_reservation_and_green_candidate()
            tick()
            pw_head = gh.prs[35]["head"]["sha"]
            gh.post_status(pw_head, "success", "strict lane: left by a refused merge")   # a stale success
            gh.head_checks.clear()                     # pW goes green; the carrier is green as well
            s = settle_now()
            check("green carrier first: green carrier + green strict holder -> the carrier merges, the strict PR waits",
                  gh.merged == [1] and 35 not in gh.merged and not s["rejected"], f"{s} merged={gh.merged}")
            check("green carrier first: no merge-train success stands on the strict head while the carrier merges",
                  gh.app_statuses(pw_head).get(STATUS_CONTEXT, ("", ""))[0] != "success",
                  f"{gh.app_statuses(pw_head).get(STATUS_CONTEXT)}")
            b_head = gh.prs[35]["head"]["sha"]        # updated right after the carrier merged (same tick or next)
            if b_head == pw_head:
                settle_now()
                b_head = gh.prs[35]["head"]["sha"]
            gh.head_checks[b_head] = {"gates (data)": None}
            open_only("p2")
            _s2, st2 = tick()                          # its CI runs: no second carrier (deferral inherited)
            gh.head_checks.clear()
            s3 = settle_now()
            check("green carrier first: strict PR is served next - updated once, merges after one more CI cycle",
                  gh.updates == [35] and st2 == "in-flight" and 35 in s3["merged"] and 2 not in gh.merged,
                  f"st2={st2} s3={s3} merged={gh.merged} updates={gh.updates}")
            gh.updates.clear()
            reset()

            # (3b) UNVERIFIED carrier vs a green strict PR past its deferral: the strict PR waits for
            # the running carrier while it is younger than CARRIER_PRIORITY_S (it would void ~1 h of
            # CI), and is served once the carrier is older — the strict wait is bounded.
            def running_carrier_and_green_strict(age_s):
                open_only("p1")
                tick()
                for c in gh.carriers.values():
                    if c["state"] == "open":
                        gh.pending_shas.add(c["head"]["sha"])
                        c["created_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - age_s))
                open_only("pW")
                gh.post_status(gh.prs[35]["head"]["sha"], "success", "yielded once", context=DEFER_CONTEXT)
                return settle_now()

            s = running_carrier_and_green_strict(10 * 60)
            check("carrier priority: a strict PR past its deferral waits for a young running carrier",
                  gh.merged == [] and any(c["state"] == "open" for c in gh.carriers.values()),
                  f"{s} merged={gh.merged}")
            gh.pending_shas.clear()
            reset()
            s = running_carrier_and_green_strict(CARRIER_PRIORITY_S + 60)
            check("carrier priority: bounded - a carrier older than CARRIER_PRIORITY_S no longer holds the strict PR",
                  gh.merged == [35], f"{s} merged={gh.merged}")
            gh.pending_shas.clear()
            reset()

            # (4) bounded: the carrier merges past the running head A (the one deferral); A is
            # update-branched to B, B inherits the record via its first parent, no further carrier is
            # built while B's CI runs, and the strict PR merges within 2 of its CI cycles.
            gh.updates.clear()
            running_reservation_and_green_candidate()
            tick()                                     # cycle 1: A's CI runs, carrier published
            s1 = settle_now()                          # the carrier is green: it pre-empts A once
            if gh.updates != [35]:
                # Only when A was not re-updated in the carrier's own tick: wait for A's CI, then update.
                gh.head_checks.clear()                 # A's CI finishes green, now behind main
                settle_now()                           # update-branch -> B
            # Pre-empted (#13400, 2026-10-26): A yielded and fell behind main, so it is re-updated in the
            # carrier's merge tick while its now-useless CI still runs. Either way, A -> B exactly once.
            b_head = gh.prs[35]["head"]["sha"]
            gh.head_checks[b_head] = {"gates (data)": None}   # cycle 2: B's CI runs
            open_only("p2")                            # another green candidate arrives
            s2, st2 = tick()
            gh.head_checks.clear()                     # B's CI finishes green
            s3 = settle_now()
            check("deferral: one yield only - the strict PR merges within 2 CI cycles, no second carrier",
                  1 in s1["merged"] and gh.updates == [35] and st2 == "in-flight" and not deferral(b_head)
                  and 35 in s3["merged"] and 2 not in gh.merged,
                  f"s1={s1} st2={st2} s3={s3} merged={gh.merged} updates={gh.updates}")
            gh.head_checks.clear()
            gh.updates.clear()
            reset()

            # OVERLAP FROM THE UPDATE TICK (#13400), rebuilt from 2026-10-26 16:06-17:14Z: carrier #13939
            # merged 5 at 16:06, and strict #13869 / #13903 / #13922 / #13934 were update-branched one
            # per tick, each tick reporting in_flight and building nothing; carrier #13946 opened only at
            # 17:14. Each case fails on the code before this change (the update tick built no carrier).
            real_update = gh.update_branch

            def update_then_run_ci(n, head):
                ok = real_update(n, head)
                if ok:
                    gh.head_checks[gh.prs[n]["head"]["sha"]] = {"gates (data)": None}   # its CI starts
                return ok

            gh.update_branch = update_then_run_ci
            try:
                gh.updates.clear()
                gh.g("checkout", "-q", "-f", "main")
                pathlib.Path(origin, "docs").mkdir(parents=True, exist_ok=True)
                pathlib.Path(origin, "docs/main-moved.md").write_text("carrier #13939 merged\n")
                gh.g("add", "-A")
                gh.g("commit", "-q", "-m", "main moves: the strict PR is now behind it")
                open_only("pW", "p1")                    # a green strict PR behind main + a green candidate
                pw_old = gh.prs[35]["head"]["sha"]
                s, st = tick()
                pw_b = gh.prs[35]["head"]["sha"]
                carrier = next((c for c in gh.carriers.values() if c["state"] == "open"), None)
                over_same_main = carrier is not None and gh.g(
                    "merge-base", "--is-ancestor", gh.main_sha(), carrier["head"]["sha"], check=False).returncode == 0
                check("overlap: the update-branch tick builds a carrier over the SAME main while the strict CI runs",
                      gh.updates == [35] and pw_b != pw_old and st == "published" and over_same_main
                      and deferral(pw_b) == "success" and gh.merged == [],
                      f"{s} st={st} updates={gh.updates} defer={deferral(pw_b)!r} merged={gh.merged}")
                # The carrier goes green while the strict head's CI still runs: green carrier first.
                s = settle_now()
                check("overlap: green carrier first - it merges past the running strict head",
                      gh.merged == [1] and 35 not in gh.merged, f"{s} merged={gh.merged}")
                # The strict PR, now behind, is re-updated (one more CI cycle) and yields no second time.
                s = settle_now()
                pw_c = gh.prs[35]["head"]["sha"]
                open_only("p2")                          # inflow continues
                s2, st2 = tick()
                check("overlap: bounded yield - re-updated once, and no second carrier is built under it",
                      gh.updates == [35, 35] and pw_c != pw_b and st2 == "in-flight"
                      and not any(c["state"] == "open" for c in gh.carriers.values()),
                      f"s={s} st2={st2} updates={gh.updates}")
                gh.head_checks.clear()                   # its second CI cycle finishes green
                s3 = settle_now()
                check("overlap: ... so the strict PR merges after 2 CI cycles, before the next carrier",
                      35 in s3["merged"] and 2 not in gh.merged, f"s3={s3} merged={gh.merged}")
                gh.head_checks.clear()
                gh.updates.clear()
                reset()

                # A strict PR that CONFLICTS with main (#13739, refused every tick for ~11 h on 2026-10-26)
                # is never update-branched: decided locally, told once with a "conflict" notice, holds no
                # reservation, and the next strict PR is served in the same tick.
                gh.g("checkout", "-q", "-f", "main")
                pathlib.Path(origin, ".github/workflows").mkdir(parents=True, exist_ok=True)
                pathlib.Path(origin, ".github/workflows/w.yml").write_text("on: pull_request\n")
                gh.g("add", "-A")
                gh.g("commit", "-q", "-m", "main edits the workflow pW edits: pW conflicts with main")
                open_only("pW", "pW2")
                asked: list[int] = []

                def update_or_refuse(n, head):
                    asked.append(n)
                    probe_m = gh.g("merge-tree", "--write-tree", "main", head, check=False)
                    return False if probe_m.returncode == 1 else update_then_run_ci(n, head)

                gh.update_branch = update_or_refuse
                head35 = gh.prs[35]["head"]["sha"]
                s = settle_now()
                s2 = settle_now()
                told = [t for n, t in gh.comments if n == 35 and t.startswith(STRICT_CONFLICT_COMMENT)]
                st35 = gh.app_statuses(head35)
                check("conflict: a conflicting strict PR is never update-branched; the next one is, same tick",
                      35 not in asked and gh.updates == [36] and 35 in s.get("conflicts", []),
                      f"asked={asked} updates={gh.updates} s={s}")
                check("conflict: it holds no reservation - main is reserved for the next strict PR instead",
                      s2["in_flight"] and 35 not in gh.merged and gh.updates == [36], f"s2={s2} updates={gh.updates}")
                check("conflict: told ONCE per head - 'conflict' notice naming the path, marker set",
                      len(told) == 1 and "`.github/workflows/w.yml`" in told[0]
                      and st35.get(STATUS_CONTEXT, ("", ""))[1].startswith(CONFLICT_PREFIX)
                      and NOTICE_CONTEXT in st35, f"{gh.comments} {st35}")
            finally:
                del gh.update_branch                     # back to FakeGitHub.update_branch
            gh.head_checks.clear()
            gh.updates.clear()
            reset()

            # LANE FAIRNESS (ADR-0343, 2026-10-21): the strict queue never empties, so before the fix
            # every strict merge was followed at once by the next strict update-branch — main stayed
            # reserved and no train ever merged (11 carriers, 0 train merges in 12.5 h; carrier
            # #13529 was held for strict #13526 and then voided by its merge). Both cases below fail
            # on that code: (a) an open carrier gets its turn, (b) after a strict merge the train goes
            # next, bounded by TRAIN_TURN_S.
            global TRAIN_TURN_S
            open_only("pW", "pW2", "p1")
            s, st = tick()                                 # pW merges (strict); a train over it is published
            gh.pending_shas.update(c["head"]["sha"] for c in gh.carriers.values() if c["state"] == "open")
            gh.updates.clear()
            s = settle_now()                               # carrier CI running; pW2 green but behind
            check("fairness: no strict-lane update-branch while a train carrier is open",
                  gh.merged == [35] and st == "published" and gh.updates == [] and s["in_flight"],
                  f"{s} st={st} merged={gh.merged} updates={gh.updates}")
            gh.pending_shas.clear()
            s, _st = tick()                                # the carrier is green: the train merges
            check("fairness: the train carrier merges instead of being held and voided by a strict merge",
                  gh.merged == [35, 1] and not s["rejected"], f"{s} merged={gh.merged}")
            check("fairness: once the train merged, the strict lane is served again in that same tick",
                  gh.updates == [36], f"{s} updates={gh.updates}")
            gh.updates.clear()
            reset()

            open_only("pW", "pW2")
            settle_now()                                   # pW merges; pW2 is behind
            open_only("p1")                                # a train candidate is waiting, no carrier
            gh.updates.clear()
            s = settle_now()
            check("fairness: right after a strict merge a waiting train goes next (no update-branch, main free)",
                  gh.merged == [35] and gh.updates == [] and not s["in_flight"],
                  f"{s} merged={gh.merged} updates={gh.updates}")
            saved_turn = TRAIN_TURN_S
            TRAIN_TURN_S = -1
            try:
                s = settle_now()
                # The update tick also opens pW2's one deferral, since p1 waits (overlap, 2026-10-26).
                check("fairness: the train's turn is bounded — expired, the strict lane updates again",
                      gh.updates == [36] and (s["in_flight"] or s.get("deferral_window") == 36),
                      f"{s} updates={gh.updates}")
            finally:
                TRAIN_TURN_S = saved_turn
            gh.updates.clear()
            reset()

            # PROGRESS-BASED RESERVATION (#13400): the reservation ends when the holder's CI STALLS or
            # passes the ceiling, never because it is slow. #13204 lapsed at 64 min on three heads
            # whose Services CI was green at 67.8 min, and was update-branched again each time.
            real_time = time.time

            def reservation_at(offset_s, last_activity_ago_s=None, checks=None):
                """Settle with the clock `offset_s` past the reserved head's commit; its newest check-run
                activity `last_activity_ago_s` before that (None: no check run ever started). `checks`
                overrides the head's required contexts (default: one still pending)."""
                open_only("pW", "pF")
                head = gh.prs[35]["head"]["sha"]
                gh.head_checks[head] = {"gates (data)": None} if checks is None else checks
                ct = int(gh.g("show", "-s", "--format=%ct", head).stdout.strip())
                if last_activity_ago_s is not None:
                    gh.activity[head] = ct + offset_s - last_activity_ago_s
                last_reservation.update(ct=ct, now=ct + offset_s, activity=gh.activity.get(head))
                time.time = lambda: ct + offset_s
                try:
                    return settle_now()
                finally:
                    time.time = real_time
                    gh.head_checks.clear()
                    gh.activity.clear()

            # (a) slow but live: 90 min in, a job finished 2 min ago — main stays reserved.
            s = reservation_at(90 * 60, 2 * 60)
            check("reservation: slow but progressing CI (90 min, last job 2 min ago) keeps main reserved",
                  s["in_flight"] and s["fast"] == [] and not s.get("timed_out"), str(s))
            reset()
            # (b) stalled: 20 min in, nothing started or finished since the update-branch.
            s = reservation_at(RESERVATION_STALL_S + 5 * 60)
            check("reservation: stalled CI (nothing started or completed in the stall window) times out",
                  34 in s["fast"] and s.get("timed_out") == [35], str(s))
            reset()
            # (b') stalled after progress: jobs ran, then nothing for longer than the window.
            s = reservation_at(40 * 60, RESERVATION_STALL_S + 60)
            check("reservation: CI that progressed and then stalled times out",
                  34 in s["fast"] and s.get("timed_out") == [35], str(s))
            reset()
            # (c) the hard ceiling bounds a CI that keeps trickling forever.
            s = reservation_at(RESERVATION_CEILING_S + 60, 60)
            check("reservation: past the hard ceiling the reservation times out even while CI progresses",
                  34 in s["fast"] and s.get("timed_out") == [35], str(s))
            reset()
            # (d) a future-dated head still holds nothing, even with live CI activity (#13485).
            s = reservation_at(-3600, -3600 - 60)
            check("reservation: a head committed in the future times out even with live CI activity",
                  34 in s["fast"] and s.get("timed_out") == [35], str(s))
            reset()
            # The pin is live: a fresh fixture head carries exactly the pinned instant, so the default
            # clock sees it as age 0 whatever the suite's real duration. If git ignored the pinned date
            # (or a read bypassed it) the head would be stale or future-dated and every holder would
            # time out. `open_only` cuts the heads, so measure one.
            open_only("pW", "pF")
            ct_pin = int(gh.g("show", "-s", "--format=%ct", gh.prs[35]["head"]["sha"]).stdout.strip())
            check("clock pin: a fixture head is stamped with the pinned instant (age 0 on the default clock)",
                  ct_pin == int(time.time()), f"head ct={ct_pin} time.time={time.time():.0f}")
            reset()

            # GREEN IS DONE, NOT STALLED: #13608 went green ~05:50Z, nothing ran after it, and the next tick
            # read the silence as a stall and released a mergeable holder to the back of the queue.
            # (e) green, last check activity 20 min ago: merged this tick, not released.
            gh.merged.clear()
            s = reservation_at(30 * 60, RESERVATION_STALL_S + 5 * 60, checks={})
            check("reservation: a GREEN holder idle past the stall window is merged, not released (#13608)",
                  gh.merged == [35] and not s.get("timed_out"), f"{s} merged={gh.merged}")
            gh.merged.clear()
            reset()
            # (f) pending, no activity for 20 min: still a stall.
            s = reservation_at(30 * 60, RESERVATION_STALL_S + 5 * 60)
            check("reservation: a PENDING holder idle past the stall window is released (stall)",
                  s.get("timed_out") == [35] and 35 not in gh.merged, f"{s} merged={gh.merged}")
            reset()
            # (g) pending with recent activity: kept.
            s = reservation_at(30 * 60, 60)
            check("reservation: a PENDING holder with recent activity keeps main reserved",
                  s["in_flight"] and not s.get("timed_out") and 35 not in gh.merged, str(s))
            reset()
            # (h) red: the failure path, never reported as a stall and never holding main.
            s = reservation_at(30 * 60, RESERVATION_STALL_S + 5 * 60, checks={"gates (data)": "failure"})
            check("reservation: a RED holder takes the failure path, not the stall path",
                  not s.get("timed_out") and 35 not in gh.merged and 34 in s["fast"], f"{s} merged={gh.merged}")
            reset()
            # #13485: what #13500 left open; both cases fail on the code before it.
            # (a) right after update-branch nothing has REPORTED yet; that head must still hold main.
            open_only("pW", "pF")
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {c: "absent" for c in SYN_REQUIRED[:4]}
            s = settle_now()
            check("reservation: a head whose CI has not reported yet still holds main (fast lane held)",
                  s["in_flight"] and s["fast"] == [], str(s))
            gh.head_checks.clear()
            reset()

            # (b) the committer time is the author's: a head dated in the future holds nothing.
            real_time = time.time
            time.time = lambda: real_time() - 3600
            try:
                open_only("pW", "pF")
                gh.head_checks[gh.prs[35]["head"]["sha"]] = {"gates (data)": None}
                s = settle_now()
            finally:
                time.time = real_time
                gh.head_checks.clear()
            check("reservation timeout: a head committed in the future holds no reservation",
                  34 in s["fast"] and s.get("timed_out") == [35], str(s))
            reset()

            # ---- main-red awareness: the strict lane never deadlocks behind a PR red only because main is ----
            MR = "Validate manifests"

            def main_breaks(base_red=False):
                """Advance main by one commit whose own CI is red on MR (and base0 too, if base_red), so
                every fixture PR is behind a red main tip."""
                if base_red:
                    gh.head_checks[base0] = {MR: "failure"}
                pathlib.Path(origin, "svc", "break.txt").parent.mkdir(parents=True, exist_ok=True)
                pathlib.Path(origin, "svc", "break.txt").write_text("break\n")
                gh.g("add", "-A")
                gh.g("commit", "-q", "-m", "break main")
                gh.head_checks[gh.main_sha()] = {MR: "failure"}

            def main_red_done():
                gh.head_checks.clear()
                gh.updates.clear()
                reset()

            # The production deadlock: an older manual-lane PR red only on the main-red context (one
            # context still running), a newer green PR cut from the red main that fixes it. The fix
            # merges first; the old PR is skipped and reserves nothing.
            # A fix behind main is never merged untested against main (#13517 merged it without update):
            # it is update-branched first, holds main while CI runs on the COMBINED head, and merges only
            # once that head verifies — fix set green, every other context green or red on main too.
            def fix_behind(combined=None, main_also_red=(), fix="pW2", fix_head=None, tamper=None):
                """pW (#35, red on MR) + the fix `fix` (default pW2 #36, green over base0 red on MR) behind
                a red main. Settles once (update-branch), gives the combined head `combined` checks (None =
                still running), settles again. Returns (first settle, second settle). `fix_head`: checks
                on the fix's own head before the update; `tamper(pr)`: edits the fix PR before settling."""
                fn = numbers[fix]
                open_only("pW", fix)
                if tamper:
                    tamper(gh.prs[fn])
                main_breaks(base_red=True)
                for c in main_also_red:
                    gh.head_checks[gh.main_sha()][c] = "failure"
                gh.head_checks[gh.prs[35]["head"]["sha"]] = {MR: "failure", "gates (data)": None}
                if fix_head is not None:
                    gh.head_checks[gh.prs[fn]["head"]["sha"]] = fix_head
                first = settle_now()
                if gh.updates != [fn]:
                    return first, None
                if combined is None:
                    gh.pending_shas.add(gh.prs[fn]["head"]["sha"])
                else:
                    gh.head_checks[gh.prs[fn]["head"]["sha"]] = combined
                return first, settle_now()

            s1, s2 = fix_behind(None)
            check("main red: a fix behind main is update-branched, not merged, and holds main while its combined CI runs",
                  gh.merged == [] and gh.updates == [36] and s1["in_flight"] and s1.get("waiting", []) == [35]
                  and s1.get("main_red", []) == [MR] and s2 is not None and s2["in_flight"] and gh.merged == [],
                  f"merged={gh.merged} updates={gh.updates} {s1} {s2}")
            gh.pending_shas.clear()
            main_red_done()

            s1, s2 = fix_behind({MR: "failure"})
            check("main red: a combined fix head red on the fix context is not merged",
                  gh.merged == [] and s2 is not None and not s2["in_flight"], f"merged={gh.merged} {s2}")
            main_red_done()

            s1, s2 = fix_behind({MR: "success", "gates (data)": "failure"}, main_also_red=("gates (data)",))
            check("main red: a combined fix head red only on a context main is red on too merges, pinned to that head",
                  gh.merged == [36] and strict_desc(36)[1].startswith("strict lane: fix for Validate manifests")
                  and "verified on its up-to-date head" in strict_desc(36)[1],
                  f"merged={gh.merged} {s2} status={strict_desc(36)}")
            main_red_done()

            s1, s2 = fix_behind({MR: "success", "derived": "failure"})
            check("main red: a combined fix head with a NEW red context is not merged",
                  gh.merged == [] and s2 is not None and not s2["in_flight"], f"merged={gh.merged} {s2}")
            main_red_done()

            s1, s2 = fix_behind({})
            check("main red: a combined fix head green everywhere merges",
                  gh.merged == [36] and s1.get("waiting", []) == [35], f"merged={gh.merged} {s1} {s2}")
            main_red_done()

            # The fix path takes manual-lane paths exactly as the strict lane does (pW2 touches
            # .github/scripts — the merge above), and refuses everything strict_lane_state refuses.
            for label, kw in [
                    ("adds a symlink", {"fix": "pS"}),
                    ("is a draft", {"tamper": lambda p: p.update(draft=True)}),
                    ("is from a fork", {"tamper": lambda p: p["head"].update(repo={"full_name": "fork/r"})}),
                    ("is refused by agent-pr-guard", {"fix_head": {"gates / agent-pr-guard": "failure"}})]:
                s1, s2 = fix_behind({}, **kw)
                fn = numbers[kw.get("fix", "pW2")]
                check(f"main red: a fix that {label} is neither updated nor merged",
                      gh.merged == [] and fn not in gh.updates and not strict_desc(fn)[0],
                      f"merged={gh.merged} updates={gh.updates} {s1} {s2} status={strict_desc(fn)}")
                main_red_done()
            def no_git(*_a, **_k):
                raise AssertionError("fix_head_state reached git for an unsafe path")
            try:
                ctl = fix_head_state(gh, no_git, "b", {"files": ["a\nb"], "head_sha": "h"}, [MR], {}, None, [],
                                     {MR: "m"})
            except AssertionError as e:
                ctl = (e,)
            check("fix path: a control-character path is refused before any git call", ctl[0] is None, str(ctl))

            # Main red: a green PR whose CI ran over a GREEN base is not a fix — no update-branch onto
            # the red tip (it would only manufacture main-caused red), and no merge.
            open_only("pW", "pW2")
            main_breaks()
            s = settle_now()
            check("main red: no update-branch and no merge for a green PR that is not the fix",
                  gh.updates == [] and gh.merged == [] and s["updated"] == [] and s.get("main_red", []) == [MR],
                  f"merged={gh.merged} updates={gh.updates} {s}")
            main_red_done()

            # A PR red on its OWN context (not red on main) is judged as before: no lane, no wait.
            open_only("pW")
            main_breaks()
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {"gates (data)": "failure", MR: "failure"}
            s = settle_now()
            check("main red: a PR also red on a context main is green on is not 'waiting' and gets nothing",
                  gh.updates == [] and gh.merged == [] and s.get("waiting", []) == [] and not strict_desc(35)[0],
                  f"merged={gh.merged} updates={gh.updates} {s}")
            main_red_done()
            open_only("pW")
            main_breaks()
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {"gates (data)": None}
            gh.g("checkout", "-q", "-f", "-B", "pW-cur", "pW")
            gh.g("merge", "-q", "--no-edit", "main")
            gh.g("checkout", "-q", "-f", "main")
            gh.prs[35]["head"].update(ref="pW-cur", sha=gh.g("rev-parse", "pW-cur").stdout.strip())
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {"gates (data)": None}
            s = settle_now()
            check("main red: an up-to-date PR with CI still running and no failure still reserves main",
                  s["in_flight"] and gh.merged == [] and s.get("waiting", []) == [], f"{s} merged={gh.merged}")
            main_red_done()

            # ---- main red x reservation ----
            def reservation_now():
                sg = Git(clone("settle"))
                base, _rt, strict, _red, _c, _a, mr, mc = settle_view(gh, sg, "o/r", gh.required())
                return strict_reservation(sg, base, strict, fixes=strict_fixes(gh, sg, base, strict, mr, mc))

            # A main-red "waiting" PR, even up to date with CI still running, never holds main: the fast
            # lane is not held for a PR that cannot go green until main is fixed.
            open_only("pW", "pF")
            main_breaks(base_red=True)
            gh.g("checkout", "-q", "-f", "-B", "pW-cur", "pW")
            gh.g("merge", "-q", "--no-edit", "main")
            gh.g("checkout", "-q", "-f", "main")
            gh.prs[35]["head"].update(ref="pW-cur", sha=gh.g("rev-parse", "pW-cur").stdout.strip())
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {MR: "failure", "gates (data)": None}
            holder, _to = reservation_now()
            s = settle_now()
            # pF's own CI ran green over base0, which is red here: by fix_contexts it is a FIX, so it is served
            # first in its own right (every lane, ADR-0343): behind main, it is update-branched (update then
            # verify) — what matters is that the waiting PR holds nothing.
            check("main red x reservation: a waiting PR is never the holder; the fast lane is not held for it",
                  holder != 35 and s.get("waiting", []) == [35]
                  and (34 in s["fast"] or 34 in gh.merged or s["updated"] == [34]) and s["updated"] in ([], [34]),
                  f"holder={holder} {s}")
            main_red_done()

            # The FIX behind a red main holds nothing until updated; once its combined head contains main
            # it is the holder: no fast-lane status and no train merge may move main under it.
            open_only("pW", "pW2", "pF")
            main_breaks(base_red=True)
            gh.head_checks[gh.prs[35]["head"]["sha"]] = {MR: "failure", "gates (data)": None}
            before, _to = reservation_now()
            s1 = settle_now()
            gh.pending_shas.add(gh.prs[36]["head"]["sha"])
            holder, _to = reservation_now()
            s = settle_now()
            check("main red x reservation: the updated fix is the holder while its combined CI runs; the fast lane waits",
                  before is None and gh.updates == [36] and holder == 36 and gh.merged == [] and s1["fast"] == []
                  and s["fast"] == [] and s["in_flight"], f"before={before} holder={holder} merged={gh.merged} {s1} {s}")
            gh.pending_shas.clear()
            main_red_done()

            # ---- main red x EVERY lane: a fix is served first even when it is not manual-lane ----
            def main_breaks_on_content():
                """main gains bad.txt, so its own CI (ci.py) is red on MR; returns a NON-manual-lane fix PR
                (#70) cut from that red tip that deletes it — green over a base red on MR."""
                pathlib.Path(origin, "bad.txt").write_text("x\n")
                gh.g("add", "-A")
                gh.g("commit", "-q", "-m", "break main (content)")
                gh.g("checkout", "-q", "-f", "-B", "pZ", "main")
                gh.g("rm", "-q", "bad.txt")
                gh.g("commit", "-q", "-m", "pZ: fix main")
                gh.g("checkout", "-q", "-f", "main")
                gh.add_pr(70, "pZ", "2026-01-70")

            open_only("p1")
            main_breaks_on_content()
            s = settle_now()
            # pZ is cut from the red tip itself, so its merge-base IS live main: its own CI already tested
            # exactly what merges, and no update is needed (the one case a fix merges as it stands).
            check("main red, any lane: a non-manual-lane fix already up to date merges first, alone, verified",
                  gh.merged == [70] and gh.updates == [] and 1 not in gh.merged and 70 in s["strict"]
                  and "verified on its up-to-date head" in strict_desc(70)[1],
                  f"merged={gh.merged} updates={gh.updates} {s} status={strict_desc(70)}")
            main_red_done()

            open_only("p1")
            main_breaks_on_content()
            _f, train, _z = plan_train(gh, Git(clone("plan")), "o/r", gh.main_sha())
            check("main red, any lane: no train is planned over a red main (it could only come back red)",
                  train == [], str([m.number for m in train]))
            main_red_done()

            # A train over a red main that is red ONLY on the main-red context blames nobody. The train is
            # built while main still reads green (main CI not reported yet), then main's red verdict lands.
            open_only("p1")
            pathlib.Path(origin, "bad.txt").write_text("x\n")
            gh.g("add", "-A")
            gh.g("commit", "-q", "-m", "break main (content)")
            gh.head_checks[gh.main_sha()] = {MR: "success"}
            tick()
            gh.head_checks.clear()
            s = settle_now()
            check("main red: a train red only on a main-red context names no culprit and bisects nothing",
                  s["culprits"] == [] and s["bisected"] == [] and s["inconclusive"] and mt_status(1)[0] != "failure"
                  and not gh.comments and not open_carriers(), f"{s} status={mt_status(1)} comments={gh.comments}")
            main_red_done()

            # ... but a context main is GREEN on still blames, naming only that context.
            open_only("p3", "p1")
            pathlib.Path(origin, "services", "z.txt").write_text("z\n")   # count.txt now wrong: gates (data) red
            gh.g("add", "-A")
            gh.g("commit", "-q", "-m", "break main (data)")
            gh.head_checks[gh.main_sha()] = {"gates (data)": "success", "derived": "success"}
            gh.prs[3]["created_at"] = "2026-01-00"
            tick(batch_size=1)
            gh.head_checks.clear()
            s = settle_now()
            check("main red: a context main is green on still names the culprit, and only that context",
                  s["culprits"] == [3] and mt_status(3)[1].startswith(CULPRIT_PREFIX + "Validate manifests red")
                  and "gates (data)" not in mt_status(3)[1], f"{s} status={mt_status(3)}")
            main_red_done()

            # Every open PR leaves a decision line, armed or not (a disarmed fix was invisible in the log).
            open_only("p1", "p2")
            gh.prs[1]["auto_merge"] = None
            gh.prs[2]["draft"] = True
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                settle_now()
            check("settle log: a disarmed and a draft PR each get a decision line",
                  "#     1 skip      auto-merge not armed" in buf.getvalue() and "#     2 skip      draft" in buf.getvalue(),
                  buf.getvalue()[-400:])
            reset()

            # ---- stale-red refresh: PRs left red by a red MAIN get one update-branch once main is green ----
            VM = "Validate manifests"

            def stale_red(*branches, base_red=True, tip_red=False, behind=True):
                """Open `branches` red on VM; main at their base red on VM (or not); main then gets a
                fix commit (unless not behind) whose own CI is green on VM (or still red)."""
                gh.updates.clear()
                gh.head_checks.clear()
                open_only(*branches)
                if base_red:
                    gh.head_checks[base0] = {VM: "failure"}
                if behind:
                    pathlib.Path(origin, "svc", "fix.txt").parent.mkdir(parents=True, exist_ok=True)
                    pathlib.Path(origin, "svc", "fix.txt").write_text("fix\n")
                    gh.g("add", "-A")
                    gh.g("commit", "-q", "-m", "fix main")
                    if tip_red:
                        gh.head_checks[gh.main_sha()] = {VM: "failure"}
                for br in branches:
                    gh.head_checks[gh.prs[numbers[br]]["head"]["sha"]] = {VM: "failure"}

            def stale_done():
                gh.head_checks.clear()
                gh.main_sequence.clear()
                gh.updates.clear()
                reset()

            stale_red("p1")
            s = settle_now()
            check("stale-red: red on a context main was red on at its base, green on the tip -> refreshed",
                  gh.updates == [1] and s["refreshed"] == [1] and gh.merged == [], f"updates={gh.updates} {s}")
            # After the refresh its base contains the fixed main; still red -> never refreshed again.
            gh.head_checks[gh.prs[1]["head"]["sha"]] = {VM: "failure"}
            gh.updates.clear()
            s = settle_now()
            check("stale-red: a refreshed PR still red is not refreshed again (no loop)",
                  gh.updates == [] and s["refreshed"] == [], f"updates={gh.updates} {s}")
            stale_done()

            stale_red("p1", base_red=False)
            s = settle_now()
            check("stale-red: red on a context that was green on main at its base -> NOT refreshed (own defect)",
                  gh.updates == [] and s["refreshed"] == [], f"updates={gh.updates} {s}")
            stale_done()

            # Not behind: main's CI for the tip finishes mid-tick (a re-run of the failed job going
            # green), so one read says red and the next green. Only the behind test stops it.
            stale_red("p1", behind=False)
            gh.main_sequence[base0] = [{VM: "failure"}, {VM: "success"}]
            s = settle_now()
            check("stale-red: red but not behind main -> NOT refreshed",
                  gh.updates == [] and s["refreshed"] == [], f"updates={gh.updates} {s}")
            stale_done()

            stale_red("p4", "p2", "p1")
            s = settle_now()
            check("stale-red: three eligible PRs -> only 2 refreshed, oldest first",
                  gh.updates == [1, 2] and s["refreshed"] == [1, 2], f"updates={gh.updates} {s}")
            stale_done()

            # The green candidate cap must not hide a later red PR from the independent stale-red
            # scan. Reuse one verified tree for these synthetic PRs; the status result is sequenced
            # per API read so the first 64 qualify and the 65th is stale-red.
            saved_prs, old_checks = gh.prs, gh.checks
            gh.prs = {}
            shared_head = gh.g("rev-parse", "p1").stdout.strip()
            for i in range(65):
                n = 1000 + i
                gh.prs[n] = {"number": n, "state": "open", "draft": False, "auto_merge": {"x": 1},
                             "title": f"p{n}", "body": "", "created_at": f"{i:03d}",
                             "user": {"login": "someone"},
                             "head": {"ref": "p1", "sha": shared_head, "repo": {"full_name": "o/r"}}}
            reads = [0]

            def sequenced_checks(sha):
                reads[0] += 1
                result = {c: "success" for c in SYN_REQUIRED[:4]}
                if reads[0] == 65:
                    result["Validate manifests"] = "failure"
                return result

            gh.checks = sequenced_checks
            red = []
            cands, _apps = live_candidates(gh, Git(clone("settle")), "o/r", gh.required(), base0,
                                           Untrainable(SYN_INVENTORY), red=red)
            check("stale-red: a later red PR is inspected after the 64-green candidate cap",
                  len(cands) == 64 and reads[0] == 65 and len(red) == 1 and red[0][0]["number"] == 1064,
                  f"candidates={len(cands)} status_reads={reads[0]} red={[x[0]['number'] for x in red]}")
            gh.prs, gh.checks = saved_prs, old_checks

            stale_red("p1", tip_red=True)
            s = settle_now()
            check("stale-red: main tip still red on that context -> NOT refreshed",
                  gh.updates == [] and s["refreshed"] == [], f"updates={gh.updates} {s}")
            stale_done()

            stale_red("p1")
            gh.prs[1]["auto_merge"] = None
            s = settle_now()
            check("stale-red: a disarmed PR is NOT refreshed", gh.updates == [] and s["refreshed"] == [], f"updates={gh.updates} {s}")
            stale_done()

            stale_red("p1")
            gh.prs[1]["draft"] = True
            s = settle_now()
            check("stale-red: a draft PR is NOT refreshed", gh.updates == [] and s["refreshed"] == [], f"updates={gh.updates} {s}")
            stale_done()

            stale_red("p1")
            gh.head_checks[gh.prs[1]["head"]["sha"]]["gates / agent-pr-guard"] = "failure"
            s = settle_now()
            check("stale-red: a PR agent-pr-guard refused is NOT refreshed",
                  gh.updates == [] and s["refreshed"] == [], f"updates={gh.updates} {s}")
            stale_done()

            stale_red("p1")
            gh.prs[1]["head"]["repo"] = {"full_name": "fork/r"}
            s = settle_now()
            check("stale-red: a fork PR is NOT refreshed", gh.updates == [] and s["refreshed"] == [], f"updates={gh.updates} {s}")
            stale_done()

            stale_red("p1")
            s = mode_settle(gh, Git(clone("settle")), "o/r", True)
            check("stale-red: shadow settle refreshes nothing", gh.updates == [] and s["refreshed"] == [], f"updates={gh.updates} {s}")
            stale_done()

            # ---- extra gates, end to end: the declaration lives on main, every lane reads it ----
            def declare_extra_gates():
                p = pathlib.Path(origin, RULES_FILE)
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(SYN_RULES)
                gh.g("add", "-A")
                gh.g("commit", "-q", "-m", "declare extra gates", check=False)  # already declared by the fixture

            def extra_done():
                gh.head_checks.clear()
                gh.carrier_override = {}
                gh.updates.clear()
                reset()

            def trivy(n, state):
                gh.head_checks.setdefault(gh.prs[n]["head"]["sha"], {})["Trivy"] = state

            # Red Trivy: never merged, in any lane (train/fast: p1, p2; strict: pW); told once per head.
            declare_extra_gates()
            carriers_before = len(gh.carriers)
            open_only("p1", "p2", "pW")
            for n in (1, 2, 35):
                trivy(n, "failure")
            drain(max_ticks=4)
            s = settle_once()
            told = [n for n, t in gh.comments if t == EXTRA_GATE_MESSAGE]
            check("extra gate red: an armed PR is never merged in any lane",
                  gh.merged == [] and gh.updates == [] and s["fast"] == [] and len(gh.carriers) == carriers_before,
                  f"merged={gh.merged} updates={gh.updates} carriers={len(gh.carriers) - carriers_before} {s}")
            check("extra gate red: the author is told once per head, the PR stays armed",
                  sorted(told) == [1, 2, 35] and all(gh.prs[n]["auto_merge"] for n in (1, 2, 35)), str(gh.comments))
            # A new head that turns the scan green is served without anyone re-arming it.
            trivy(1, "success")
            drain(max_ticks=4)
            check("extra gate red -> green on the same armed PR: served", 1 in gh.merged and 2 not in gh.merged,
                  str(gh.merged))
            extra_done()

            declare_extra_gates()
            open_only("p1")
            trivy(1, None)
            s = settle_once()
            _f, train, _z = plan_train(gh, Git(clone("plan")), "o/r", gh.main_sha())
            check("extra gate pending: waits (no fast lane, no train, no comment)",
                  gh.merged == [] and s["fast"] == [] and train == [] and not gh.comments, f"{s} {gh.comments}")
            extra_done()

            declare_extra_gates()
            open_only("p1")
            drain(max_ticks=4)
            check("extra gate not triggered on the head: served", gh.merged == [1] and not gh.comments,
                  f"{gh.merged} {gh.comments}")
            extra_done()

            # Carrier judged on the extra gate: red on the carrier with main green is a culprit (control) ...
            declare_extra_gates()
            open_only("p1", "p3")
            tick()
            gh.carrier_override = {"Trivy": "failure"}
            gh.head_checks[gh.main_sha()] = {"Trivy": "success"}   # main green: the override is the carrier's alone
            s = settle_once()
            check("extra gate on a carrier: red with main green is a verdict (bisect / culprit)",
                  gh.merged == [] and (s["bisected"] or s["culprits"]), str(s))
            extra_done()
            # ... and red on main too blames nobody: no verdict, no culprit, nothing merged.
            declare_extra_gates()
            open_only("p1")
            tick()
            gh.carrier_override = {"Trivy": "failure"}
            gh.head_checks[gh.main_sha()] = {"Trivy": "failure"}
            s = settle_once()
            check("extra gate red on main: the carrier blames nobody and nothing merges",
                  gh.merged == [] and not s["culprits"] and not s["bisected"] and s["inconclusive"]
                  and CULPRIT_MESSAGE not in [t for _n, t in gh.comments]
                  and mt_status(1)[0] != "failure", f"{s} {gh.comments} {mt_status(1)}")
            check("extra gate red on main: it reads as main red", "Trivy" in s["main_red"], str(s))
            extra_done()

            # Spoofing: a same-name GREEN Trivy from another app/workflow next to a pinned RED one.
            open_only("p1", "pW")
            for n in (1, 35):
                sha = gh.prs[n]["head"]["sha"]
                gh.head_checks[sha] = {"Trivy": "success"}            # the latest same-name run: spoofed green
                gh.pinned[sha] = {"Trivy": ["failure", "success"]}    # Security scan's own runs: one red
            drain(max_ticks=4)
            check("extra gate spoof: a green same-name run from another source cannot mask the pinned red",
                  gh.merged == [] and gh.updates == [], f"merged={gh.merged} updates={gh.updates}")
            gh.pinned.clear()
            extra_done()

            # Fail closed: declaration absent or unparseable on main -> nothing is served, nothing judged.
            for label, text in [("absent", None), ("unparseable", "merge_train:\n  extra_gates:\n    - Trivy\n")]:
                p = pathlib.Path(origin, RULES_FILE)
                if text is None:
                    gh.g("rm", "-q", RULES_FILE)
                else:
                    p.write_text(text)
                    gh.g("add", "-A")
                gh.g("commit", "-q", "-m", f"declaration {label}")
                open_only("p1", "pW")
                drain(max_ticks=3)
                _f, train, _z = plan_train(gh, Git(clone("plan")), "o/r", gh.main_sha())
                check(f"extra gate declaration {label}: no PR is served in any lane",
                      gh.merged == [] and gh.updates == [] and train == [] and _f == [],
                      f"merged={gh.merged} updates={gh.updates} train={[m.number for m in train]}")
                extra_done()
            # ---- notices (#13400): conflict-with-main drops and refused update-branch, once per head ----
            reset()
            gh.updates.clear()
            gh.g("checkout", "-q", "-f", "main")
            for rel, text in (("services/a.txt", "main-a\n"), ("count.txt", "2\n")):
                pathlib.Path(origin, rel).write_text(text)
            subprocess.run([sys.executable, SYN_GENERATOR], cwd=origin, check=True, env=base_env())
            gh.g("add", "-A")
            gh.g("commit", "-q", "-m", "main adds services/a.txt: pA now conflicts with main itself")
            open_only("pA")
            old_head = gh.prs[11]["head"]["sha"]
            for _ in range(3):
                tick()
            told = [t for n, t in gh.comments if n == 11 and t.startswith(CONFLICT_COMMENT)]
            st = mt_status(11)
            mt_posts = [x for x in gh.statuses.get(old_head, []) if x[0] == STATUS_CONTEXT]
            check("notice: a candidate conflicting with main gets ONE merge-train failure naming main",
                  st[0] == "failure" and st[1] == f"{CONFLICT_PREFIX}{gh.main_sha()[:12]}: merge main into the branch"
                  and len(mt_posts) == 1, f"{st} {mt_posts}")
            check("notice: ... and ONE comment listing the conflicting path, nothing repeated over 3 ticks",
                  len(told) == 1 and "`services/a.txt`" in told[0] and "`count.txt`" not in told[0]
                  and NOTICE_CONTEXT in gh.app_statuses(old_head), f"{gh.comments}")
            check("notice: no merge decision changed (nothing merged, no carrier for it)", gh.merged == [], str(gh.merged))
            # A new head (main merged in, conflict resolved) starts clean and is served.
            gh.g("checkout", "-q", "-b", "pA-fixed", "pA")
            pathlib.Path(origin, "services/a.txt").write_text("main-a\n")
            pathlib.Path(origin, "a-resolved.md").write_text("still has a change of its own\n")
            gh.g("add", "-A")
            gh.g("commit", "-q", "-m", "resolve")
            gh.g("checkout", "-q", "-f", "main")
            gh.prs[11]["head"].update(ref="pA-fixed", sha=gh.g("rev-parse", "pA-fixed").stdout.strip())
            check("notice: a new head carries none of it", not gh.app_statuses(gh.prs[11]["head"]["sha"]))
            drain(max_ticks=4)
            check("notice: a new head clears it — served, not told again",
                  gh.merged == [11] and len([1 for n, t in gh.comments if n == 11 and t.startswith(CONFLICT_COMMENT)]) == 1,
                  f"merged={gh.merged} {gh.comments}")
            gh.g("branch", "-q", "-D", "pA-fixed")
            reset()

            # Strict lane: a green manual-lane PR behind main whose update-branch GitHub refuses.
            gh.g("checkout", "-q", "-f", "main")
            pathlib.Path(origin, "docs/moved.md").parent.mkdir(parents=True, exist_ok=True)
            pathlib.Path(origin, "docs/moved.md").write_text("m\n")
            gh.g("add", "-A")
            gh.g("commit", "-q", "-m", "main moves")
            open_only("pW")
            refused: list[int] = []
            gh.update_branch = lambda n, head: refused.append(n) and False
            try:
                for _ in range(3):
                    settle_once()
            finally:
                del gh.update_branch
            head35 = gh.prs[35]["head"]["sha"]
            told = [t for n, t in gh.comments if n == 35 and t.startswith(REFUSED_COMMENT)]
            # Quota (#13400): refused for THIS head over THIS main is not asked again until either moves.
            check("notice: a refused update-branch is asked once per (head, main), not every tick",
                  refused == [35] and gh.merged == [], f"{refused} {gh.merged}")
            check("notice: a refused update-branch is told ONCE per head (status + comment + marker)",
                  len(told) == 1 and mt_status(35)[1].startswith(REFUSED_PREFIX)
                  and len([x for x in gh.statuses.get(head35, []) if x[0] == STATUS_CONTEXT]) == 1
                  and NOTICE_CONTEXT in gh.app_statuses(head35), f"{gh.comments} {gh.statuses.get(head35)}")
            gh.updates.clear()
            reset()
            # ---- GitHub API (#13400): transient failures never kill a tick's carrier; quota is not burnt ----
            # The REAL GitHub class and api() over HttpFixture (urllib.request.urlopen patched). Rebuilt
            # from 2026-10-26: publish died on a check-runs GET 503 at 16:33Z, build on the installation's
            # primary quota (403) at 16:39Z — each a lost carrier and a red run, no member at fault.
            reset()
            gh.updates.clear()
            saved_http = (urllib.request.urlopen, globals()["_api_sleep"], os.environ.get("GITOPS_APP_ID"))
            slept: list[float] = []
            os.environ["GITOPS_APP_ID"] = "4242"
            globals()["_api_sleep"] = slept.append

            def with_http(faults=None):
                fx = HttpFixture(gh, faults)
                fx.sync_refs()
                urllib.request.urlopen = fx
                _GET_MEMO.clear()
                _api_slept[0] = 0.0
                slept.clear()
                real = GitHub("o/r", "main", "http://run")
                real.remote = origin
                return fx, real

            def writes(fx):
                return [c for c in fx.calls if c[0] != "GET"]

            def check_runs_get(method, path):
                return method == "GET" and "/check-runs" in path

            def real_tick(real, fx):
                """build -> publish with the real GitHub class, each guarded as main() guards it."""
                counter[0] += 1
                bdir = clone("build")
                out = os.path.join(root, f"bundle-http-{counter[0]}")
                b = TrainBuilder(bdir, regen_commands=(f"{sys.executable} -I {SYN_GENERATOR}",),
                                 overlay_roots=(".github/scripts",))
                rc_b = guard_api("build", lambda: (mode_build(real, Git(bdir), b, "o/r", out, 8), 0)[1])
                fx.sync_refs()
                got = []
                rc_p = guard_api("publish", lambda: got.append(
                    mode_publish(real, Git(clone("publish")), "o/r", out, str(counter[0]), "1", 8)) or 0)
                return rc_b, rc_p, (got[0] if got else None)

            try:
                # (a) a 503 on a check-runs GET, then success: retried with backoff, the carrier is published.
                open_only("p1")
                try:
                    fx, real = with_http([[check_runs_get, (503, {}), 1]])
                    rc_b, rc_p, st = real_tick(real, fx)
                    ok = rc_b == 0 and rc_p == 0 and st == "published" and slept == [API_BACKOFF_BASE_S]
                    detail = f"rc={rc_b},{rc_p} st={st} slept={slept}"
                except BaseException as exc:          # the code before this change: SystemExit on the 503
                    ok, detail = False, f"{type(exc).__name__}: {exc}"
                check("api: a 503 then a success is retried with backoff and the carrier is published", ok, detail)
                reset()

                # (b) 503 on every attempt: the tick aborts cleanly, nobody blamed, nothing posted.
                open_only("p1")
                try:
                    fx, real = with_http([[check_runs_get, (503, {}), None]])
                    rc_b, rc_p, st = real_tick(real, fx)
                    tries = len([c for c in fx.calls if check_runs_get(*c)])
                    ok = (rc_b == 0 and rc_p == 0 and st is None and not writes(fx) and not gh.statuses
                          and not gh.comments and not any(c["state"] == "open" for c in gh.carriers.values())
                          and 2 <= tries <= 2 * API_RETRY_ATTEMPTS and sum(slept) <= API_RETRY_BUDGET_S)
                    detail = f"rc={rc_b},{rc_p} st={st} tries={tries} writes={writes(fx)} statuses={gh.statuses}"
                except BaseException as exc:
                    ok, detail = False, f"{type(exc).__name__}: {exc}"
                check("api: 503 on every attempt aborts the tick with no member fault (no status, comment, carrier)",
                      ok, detail)
                reset()

                # (c) primary quota exhausted: ONE request, no retry, no bisect or halving, the reset time logged.
                open_only("p1")
                try:
                    fx, real = with_http([[lambda m, p: m == "GET", (403, {"x-ratelimit-remaining": "0",
                                                                           "x-ratelimit-reset": str(int(time.time()) + 3600)}), None]])
                    err = None
                    try:
                        api("GET", "/repos/o/r/pulls?state=open&base=main")
                    except ApiAbort as exc:
                        err = exc
                    n_after_one = len(fx.calls)
                    rc_b, rc_p, st = real_tick(real, fx)
                    ok = (err is not None and err.kind == "quota" and "01:00:00Z" in err.detail and n_after_one == 1
                          and not slept and rc_b == 0 and rc_p == 0 and not writes(fx) and not gh.statuses)
                    detail = f"err={err!r} calls={len(fx.calls)} slept={slept} rc={rc_b},{rc_p} statuses={gh.statuses}"
                except BaseException as exc:
                    ok, detail = False, f"{type(exc).__name__}: {exc}"
                check("api: primary quota exhaustion aborts at once - no retry, no bisect, reset time logged", ok, detail)
                reset()

                # (d) auth errors are never retried and stay fail-closed: 401, and 403 without a rate-limit signal.
                for code, hdrs in ((401, {}), (403, {}), (422, {})):
                    try:
                        fx, real = with_http([[lambda m, p: True, (code, hdrs), None]])
                        got = api("GET", "/repos/o/r/pulls/1")
                        try:
                            get("/repos/o/r/pulls/1")
                            closed = False
                        except SystemExit:
                            closed = True
                        ok = got[0] == code and closed and len(fx.calls) == 2 and not slept
                        detail = f"got={got[0]} closed={closed} calls={len(fx.calls)} slept={slept}"
                    except BaseException as exc:
                        ok, detail = False, f"{type(exc).__name__}: {exc}"
                    check(f"api: a {code} is never retried and fails closed", ok, detail)

                # (e) secondary rate limit with Retry-After and a connection reset are retried (GET only);
                # a transient failure on a WRITE is never retried — it aborts the tick.
                try:
                    fx, real = with_http([[lambda m, p: True, (403, {"retry-after": "7"}), 1],
                                          [lambda m, p: True, "reset", 1]])
                    got = api("GET", "/repos/o/r/git/ref/heads/main")
                    ok = got[0] == 200 and slept == [7.0, API_BACKOFF_BASE_S * 2] and len(fx.calls) == 3
                    fx, real = with_http([[lambda m, p: m == "POST", (503, {}), None]])
                    try:
                        api("POST", f"/repos/o/r/statuses/{'a' * 40}", {"state": "success", "context": "x", "description": ""})
                        ok = False
                    except ApiAbort:
                        ok = ok and len(fx.calls) == 1
                    detail = f"slept={slept} calls={fx.calls}"
                except BaseException as exc:
                    ok, detail = False, f"{type(exc).__name__}: {exc}"
                check("api: Retry-After and a reset are retried on a GET; a write is never retried", ok, detail)

                # (f) quota: a steady-state settle tick asks nothing twice (#13739 shape: a strict PR whose
                # update-branch is refused, next to an idle candidate). Counted on the fixture.
                open_only("pW", "p1")
                fx, real = with_http()
                refuse = lambda n, head: False      # noqa: E731 — GitHub refuses, no conflict merge-tree sees
                gh.update_branch = refuse
                try:
                    gh.g("checkout", "-q", "-f", "main")
                    pathlib.Path(origin, "docs").mkdir(parents=True, exist_ok=True)
                    pathlib.Path(origin, "docs/quota.md").write_text("main moves\n")
                    gh.g("add", "-A")
                    gh.g("commit", "-q", "-m", "main moves under the strict PR")
                    per_tick = []
                    for _ in range(3):
                        before = len(fx.calls)
                        _GET_MEMO.clear()
                        mode_settle(real, Git(clone("settle")), "o/r", False)
                        per_tick.append(fx.calls[before:])
                finally:
                    del gh.update_branch
                steady = per_tick[-1]
                dup = sorted({c for c in steady if c[0] == "GET" and steady.count(c) > 1})
                upd = [c for c in steady if c[1].endswith("/update-branch")]
                log(f"api: settle calls per tick on the fixture: {[len(t) for t in per_tick]}")
                # The planning reads (a sha's check-runs/statuses/workflow runs) are read once per tick;
                # the ruleset and the open-PR list are re-read live outside planning (cache safety).
                check("api: a steady-state tick re-asks no refused update-branch and repeats no planning read",
                      not upd and not [c for c in dup if "/commits/" in c[1] or "/actions/runs" in c[1]],
                      f"per_tick={[len(t) for t in per_tick]} update={upd} dup={dup[:6]}")
                reset()

                # CACHE SAFETY (#13988 review): planning reads may be memoised, a write's verdict never.
                # Each case drifts state AFTER the tick's planning read and BEFORE its write.
                def drift_after_first(fx, pred, action):
                    fx.after.append([pred, action, 1])

                def head_runs(sha):
                    return lambda m, p: m == "GET" and f"/commits/{sha}/check-runs" in p

                # (g) a strict head green when planned turns red before its merge: no merge.
                open_only("pW")
                fx, real = with_http()
                hw = gh.prs[35]["head"]["sha"]
                drift_after_first(fx, head_runs(hw), lambda: gh.head_checks.__setitem__(hw, {"gates (data)": "failure"}))
                try:
                    mode_settle(real, Git(clone("settle")), "o/r", False)
                    ok, detail = 35 not in gh.merged, f"merged={gh.merged}"
                except BaseException as exc:
                    ok, detail = False, f"{type(exc).__name__}: {exc}"
                check("cache safety: a check-run green at planning and red at merge time means no merge", ok, detail)
                gh.head_checks.clear()
                reset()

                # (h) a fast-lane candidate whose required context is revoked between plan and the
                # status post: no `merge-train` success is posted on it.
                open_only("pF")
                fx, real = with_http()
                hf = gh.prs[34]["head"]["sha"]
                drift_after_first(fx, head_runs(hf), lambda: gh.head_checks.__setitem__(hf, {"Validate manifests": "failure"}))
                try:
                    mode_settle(real, Git(clone("settle")), "o/r", False)
                    st = gh.app_statuses(hf).get(STATUS_CONTEXT, ("", ""))
                    ok, detail = st[0] != "success", f"{st}"
                except BaseException as exc:
                    ok, detail = False, f"{type(exc).__name__}: {exc}"
                check("cache safety: a status revoked between plan and status-post means no success status", ok, detail)
                gh.head_checks.clear()
                reset()

                # (i) the ruleset turns strict up-to-date back on between the guard's read and the merge.
                open_only("pW")
                fx, real = with_http()
                drift_after_first(fx, lambda m, p: m == "GET" and "/rules/branches/" in p,
                                  lambda: setattr(gh, "rs_strict", True))
                try:
                    mode_settle(real, Git(clone("settle")), "o/r", False)
                    ok, detail = 35 not in gh.merged, f"merged={gh.merged}"
                except BaseException as exc:
                    ok, detail = False, f"{type(exc).__name__}: {exc}"
                finally:
                    gh.rs_strict = False
                check("cache safety: a ruleset change between plan and merge means the guard refuses", ok, detail)
                reset()
            finally:
                urllib.request.urlopen, globals()["_api_sleep"] = saved_http[0], saved_http[1]
                if saved_http[2] is None:
                    os.environ.pop("GITOPS_APP_ID", None)
                else:
                    os.environ["GITOPS_APP_ID"] = saved_http[2]
                _GET_MEMO.clear()
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
    ap.add_argument("mode", nargs="?", choices=["build", "publish", "settle", "owner-approval", "owner-status"])
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
    ap.add_argument("--pr", type=int, default=0, help="owner-approval: exact PR number")
    ap.add_argument("--head", default="", help="owner-approval: full current head SHA")
    ap.add_argument("--decision", choices=["approve", "revoke"], default="approve")
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
    return guard_api(args.mode, lambda: run_mode(gh, args, ap))


def guard_api(mode: str, run) -> int:
    """Run one mode; a GitHub API outage (ApiAbort) ends the tick cleanly instead of failing the job."""
    try:
        return run()
    except ApiAbort as exc:
        return api_abort_exit(mode, exc)


def api_abort_exit(mode: str, exc: ApiAbort) -> int:
    """A tick the API could not serve (#13400): a clean exit, never a verdict. Build reports no train
    (publish is skipped, the batch stays armed), settle reports in flight (nothing is built over a
    state it could not read), and no status, comment, bisect or halving is posted for any member."""
    log(f"::warning::merge-train {mode}: GitHub API unavailable ({exc}) — tick aborted, no member fault, "
        f"the batch is kept for the next tick")
    if mode == "owner-approval":
        return 1                              # an owner decision that was not recorded must fail visibly
    if mode == "build":
        write_output("has_train", "false")
        write_output("has_drops", "false")
    elif mode == "settle":
        write_output("in_flight", "true")
    return 0


def run_mode(gh, args, ap) -> int:
    if args.mode == "owner-status":
        line = owner_status_line(paginate(f"/repos/{gh.repo}/commits/{args.head}/statuses"))
        print(line or "no App status found")
        return 0 if line else 1
    if args.mode == "owner-approval":
        mode_owner_approval(gh, args.pr, args.head, args.decision)
        return 0
    workdir = args.workdir or os.getcwd()
    if args.mode == "build":
        if not args.out:
            ap.error("build needs --out")
        applied = mode_build(gh, Git(workdir), TrainBuilder(workdir), args.repo, args.out, args.batch_size)
        write_output("has_train", "true" if applied else "false")
        write_output("has_drops", "true" if os.path.isfile(os.path.join(args.out, DROPS_FILE)) else "false")
        return 0
    git = Git(workdir, token=os.environ["GH_TOKEN"])
    if args.mode == "publish":
        if not args.bundle or not args.run_id or not args.attempt:
            ap.error("publish needs --bundle, --run-id and --attempt")
        log(f"publish: notices {mode_notice_drops(gh, git, args.bundle)}")
        log(f"publish: {mode_publish(gh, git, args.repo, args.bundle, args.run_id, args.attempt, args.batch_size)}")
        return 0
    s = mode_settle(gh, git, args.repo, args.shadow)
    log(f"settle: {s}")
    write_output("in_flight", "true" if s["in_flight"] else "false")
    return 0


if __name__ == "__main__":
    sys.exit(main())
