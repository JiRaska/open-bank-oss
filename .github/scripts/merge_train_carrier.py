# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Recognise a merge-train carrier PR and derive its members (ADR-0343), for gates that read a PR body.

WHY. A merge-train carrier is a bot-opened PR whose body is fixed boilerplate. A gate that reads the
live PR body (the money-path security checklist, the coverage-floor trailer) therefore judges the
carrier on text no human wrote, and fails every train that carries a member needing that text —
then the train blames the member (#12506 via carriers #13424/#13451, whose own body was fully
ticked). The attestation lives on the MEMBERS, so a body gate evaluates the members when its subject
is a carrier. It never copies member text onto the carrier: that would turn a human attestation
into bot text.

TRUST. A PR is a carrier only when ALL hold, judged from the LIVE PR object (never the event):
  * head branch matches `^train/[0-9]+-[0-9]+$` and lives in this repository (not a fork);
  * author login is exactly the gitops App bot, `openbank-gitops-bot[bot]`, with type `Bot`
    (a user cannot register a login containing `[bot]`, so a human cannot be that author);
  * it carries the `merge-train` label.
A human-opened PR named `train/…` is judged as an ordinary PR. Members come from the carrier's own
commits (`base..head`), never from body text: every non-merge commit must be either the train's own
member commit (`merge-train: member #N` + `PR-Number: #N` + `PR-Head: <sha>` trailers, the exact
message merge-train.py writes) or its single regeneration commit — anything else fails closed.
"""
from __future__ import annotations

import json
import re
import subprocess

BOT_LOGIN = "openbank-gitops-bot[bot]"
CARRIER_LABEL = "merge-train"
TRAIN_BRANCH_RE = re.compile(r"^train/[0-9]+-[0-9]+$")
OWN_MEMBER_RE = re.compile(r"^merge-train: member #([0-9]+)\n\nPR-Number: #\1\nPR-Head: ([0-9a-f]{40})\n$")
OWN_REGEN_MSG = "merge-train: regenerate derived files\n"
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class CarrierError(Exception):
    """The PR is a verified carrier but its member set cannot be derived — the caller fails closed."""


def is_verified_carrier(pr: dict | None, repo: str) -> bool:
    if not isinstance(pr, dict):
        return False
    user = pr.get("user") or {}
    head = pr.get("head") or {}
    labels = {(lb or {}).get("name") for lb in pr.get("labels") or []}
    return (user.get("login") == BOT_LOGIN and user.get("type") == "Bot"
            and TRAIN_BRANCH_RE.fullmatch(head.get("ref") or "") is not None
            and ((head.get("repo") or {}).get("full_name") == repo)
            and CARRIER_LABEL in labels)


def _git(*args: str, cwd: str | None = None, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=check, cwd=cwd)


def carrier_members(base: str, head: str = "HEAD", cwd: str | None = None) -> list[tuple[int, str]]:
    """[(member PR number, the train's own commit for it)] in train order. Raises CarrierError."""
    if _git("rev-parse", "--is-shallow-repository", cwd=cwd).stdout.strip() == "true":
        # CI checks out the merge ref at depth 2; the chain sits below it.
        _git("fetch", "-q", "--deepen=128", "origin", cwd=cwd, check=False)
    revs = _git("rev-list", "--reverse", "--no-merges", f"{base}..{head}", cwd=cwd, check=False)
    if revs.returncode != 0:
        raise CarrierError(f"cannot list {base}..{head}: {revs.stderr.strip()[:200]}")
    commits = revs.stdout.split()
    if not commits:
        raise CarrierError("carrier range holds no commits")
    first_parent = _git("rev-parse", "--verify", "--quiet", f"{commits[0]}^", cwd=cwd, check=False).stdout.strip()
    if not first_parent or _git("merge-base", "--is-ancestor", first_parent, base, cwd=cwd, check=False).returncode != 0:
        raise CarrierError("carrier chain is incomplete in this checkout (shallow history) — cannot derive members")
    members: list[tuple[int, str]] = []
    regen = 0
    for c in commits:
        msg = _git("log", "-1", "--format=%B", c, cwd=cwd).stdout.rstrip("\n") + "\n"
        m = OWN_MEMBER_RE.fullmatch(msg)
        if m:
            members.append((int(m.group(1)), c))
        elif msg == OWN_REGEN_MSG and regen == 0:
            regen += 1
        else:
            raise CarrierError(f"commit {c[:12]} is neither a train member commit nor its regeneration commit")
    if not members or len({n for n, _ in members}) != len(members):
        raise CarrierError("carrier has no members, or a member twice")
    return members


def regen_commits(base: str, head: str = "HEAD", cwd: str | None = None) -> list[str]:
    out = _git("rev-list", "--no-merges", f"{base}..{head}", cwd=cwd, check=False).stdout.split()
    return [c for c in out if _git("log", "-1", "--format=%B", c, cwd=cwd).stdout.rstrip("\n") + "\n" == OWN_REGEN_MSG]


def gh_pr(repo: str, number: int | str) -> dict | None:
    """The LIVE PR object, or None when it cannot be read (callers fail closed or fall back)."""
    if not REPO_RE.fullmatch(repo or "") or not str(number).isdigit():
        return None
    try:
        r = subprocess.run(["gh", "api", f"repos/{repo}/pulls/{int(number)}"],
                           capture_output=True, text=True, check=True, timeout=15)
        data = json.loads(r.stdout)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError):
        return None
    return data if isinstance(data, dict) else None
