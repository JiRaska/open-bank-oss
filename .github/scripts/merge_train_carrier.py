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
import os
import re
import subprocess
import time

BOT_LOGIN = "openbank-gitops-bot[bot]"
CARRIER_LABEL = "merge-train"
TRAIN_BRANCH_RE = re.compile(r"^train/[0-9]+-[0-9]+$")
OWN_MEMBER_RE = re.compile(r"^merge-train: member #([0-9]+)\n\nPR-Number: #\1\nPR-Head: ([0-9a-f]{40})\n$")
OWN_REGEN_MSG = "merge-train: regenerate derived files\n"
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class CarrierError(Exception):
    """The PR is a verified carrier but its member set cannot be derived — the caller fails closed."""


class CarrierUnavailable(CarrierError):
    """The member set could not be READ (a timed-out fetch, an unreachable API) — not a finding about
    the carrier. Gates report NOT EVALUATED for this, never PASS and never a content failure."""


# One deadline for the whole carrier resolution of one job (#13400). Carrier mode used to deepen with
# `git fetch --deepen=128 origin` — every one of the remote's ~665 branch refs, ~29 s measured — once per
# gate, so the first carrier judged in carrier mode (run 39526539131) spent 33.7 s and 44.9 s in two
# gates whose own work is <1 s. A resolution that cannot finish inside this is UNAVAILABLE.
RESOLVE_DEADLINE_S = 15.0
NOT_EVALUATED_LINE = "GATE_VERDICT=NOT_EVALUATED"   # must match run-gates.py NOT_EVALUATED_LINE


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


def _chain_complete(base: str, head: str, cwd: str | None) -> bool:
    revs = _git("rev-list", "--reverse", "--no-merges", f"{base}..{head}", cwd=cwd, check=False)
    commits = revs.stdout.split() if revs.returncode == 0 else []
    if not commits:
        return False
    parent = _git("rev-parse", "--verify", "--quiet", f"{commits[0]}^", cwd=cwd, check=False).stdout.strip()
    return bool(parent) and _git("merge-base", "--is-ancestor", parent, base, cwd=cwd, check=False).returncode == 0


def deepen_for_chain(base: str, head: str = "HEAD", cwd: str | None = None, timeout: float = RESOLVE_DEADLINE_S,
                     runner=subprocess.run) -> None:
    """Make base..head walkable in a shallow checkout, fetching ONLY the history behind `head`.

    The fetch names the head commit, so the default refspec (all of origin's branches) is not deepened:
    measured 2026-10-21 against this remote from a depth-2 clone, `git fetch --deepen=128 origin` took
    29.4 s and `git fetch --no-tags --deepen=128 origin <sha>` took 1.7 s for the same walkable chain.
    No network at all when the chain is already complete. Raises CarrierUnavailable on a timeout or a
    failed fetch — the chain was not read, which is not the same as a malformed chain."""
    if _git("rev-parse", "--is-shallow-repository", cwd=cwd).stdout.strip() != "true" or _chain_complete(base, head, cwd):
        return
    sha = _git("rev-parse", "--verify", "--quiet", f"{head}^{{commit}}", cwd=cwd, check=False).stdout.strip()
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise CarrierUnavailable(f"cannot resolve {head} to a commit")
    try:
        r = runner(["git", "fetch", "-q", "--no-tags", "--deepen=128", "origin", sha],
                   capture_output=True, text=True, cwd=cwd, timeout=max(1.0, timeout))
    except subprocess.TimeoutExpired:
        raise CarrierUnavailable(f"deepening the carrier chain timed out after {timeout:.0f}s") from None
    if r.returncode != 0:
        raise CarrierUnavailable(f"deepening the carrier chain failed: {(r.stderr or '').strip()[:160]}")


def carrier_members(base: str, head: str = "HEAD", cwd: str | None = None,
                    deepen_timeout: float = RESOLVE_DEADLINE_S) -> list[tuple[int, str]]:
    """[(member PR number, the train's own commit for it)] in train order. Raises CarrierError
    (CarrierUnavailable when the history could not be fetched in time)."""
    deepen_for_chain(base, head, cwd, timeout=deepen_timeout)
    revs = _git("rev-list", "--reverse", "--no-merges", f"{base}..{head}", cwd=cwd, check=False)
    if revs.returncode != 0:
        raise CarrierError(f"cannot list {base}..{head}: {revs.stderr.strip()[:200]}")
    commits = revs.stdout.split()
    if not commits:
        raise CarrierError("carrier range holds no commits")
    first_parent = _git("rev-parse", "--verify", "--quiet", f"{commits[0]}^", cwd=cwd, check=False).stdout.strip()
    if not first_parent or _git("merge-base", "--is-ancestor", first_parent, base, cwd=cwd, check=False).returncode != 0:
        raise CarrierUnavailable("carrier chain is incomplete in this checkout (shallow history) — cannot derive members")
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


def gh_pr(repo: str, number: int | str, timeout: float = 10) -> dict | None:
    """The LIVE PR object, or None when it cannot be read (callers fail closed or fall back)."""
    if not REPO_RE.fullmatch(repo or "") or not str(number).isdigit():
        return None
    try:
        r = subprocess.run(["gh", "api", f"repos/{repo}/pulls/{int(number)}"],
                           capture_output=True, text=True, check=True, timeout=max(1.0, timeout))
        data = json.loads(r.stdout)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError):
        return None
    return data if isinstance(data, dict) else None


def gh_member_bodies(repo: str, numbers: list[int], timeout: float = 10) -> dict[int, str] | None:
    """Every member's LIVE body in ONE GraphQL request, or None when it cannot be read in `timeout`.
    A member the query does not return is simply absent from the dict (the caller treats it as unread)."""
    if not REPO_RE.fullmatch(repo or "") or not numbers or not all(isinstance(n, int) and n > 0 for n in numbers):
        return None
    owner, name = repo.split("/", 1)
    fields = " ".join(f"m{n}: pullRequest(number: {n}) {{ body }}" for n in numbers)
    query = f"query($o: String!, $n: String!) {{ repository(owner: $o, name: $n) {{ {fields} }} }}"
    try:
        r = subprocess.run(["gh", "api", "graphql", "-f", f"query={query}", "-f", f"o={owner}", "-f", f"n={name}"],
                           capture_output=True, text=True, check=True, timeout=max(1.0, timeout))
        data = (json.loads(r.stdout).get("data") or {}).get("repository") or {}
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError, AttributeError):
        return None
    out: dict[int, str] = {}
    for n in numbers:
        node = data.get(f"m{n}")
        if isinstance(node, dict):
            out[n] = node.get("body") or ""
    return out


class Resolution:
    """What one job knows about whether its PR is a carrier, resolved ONCE and shared by every gate.

    kind: "none"        — not a PR run, or not a verified carrier (`pr` may still be the live object);
          "carrier"     — verified carrier; `members` = [(number, train commit, live body or None if unread)];
          "invalid"     — verified carrier whose commits are not the train's own: fail closed (a finding);
          "unavailable" — the facts could not be READ in time: gates report NOT EVALUATED, never PASS."""

    def __init__(self, kind: str, pr: dict | None = None, members: list | None = None, reason: str = ""):
        self.kind, self.pr, self.members, self.reason = kind, pr, members or [], reason

    def to_json(self) -> dict:
        return {"kind": self.kind, "pr": self.pr, "members": [list(m) for m in self.members], "reason": self.reason}

    @classmethod
    def from_json(cls, d: dict) -> "Resolution":
        return cls(d["kind"], d.get("pr"), [(int(n), c, b) for n, c, b in d.get("members") or []], d.get("reason", ""))


CACHE_DIR_ENV = "GATE_PARSE_CACHE"   # run-gates.py's per-invocation (= per-job) scratch dir; gone after the run
FETCHES = {"resolve": 0}             # counted for the self-test: one resolution per job, not per gate


def _resolve_uncached(base: str, env, cwd, fetch_pr, fetch_bodies, deadline: float) -> Resolution:
    FETCHES["resolve"] += 1
    repo, number = env.get("GITHUB_REPOSITORY", ""), env.get("PR_NUMBER", "")
    if not (repo and number.isdigit()):
        return Resolution("none")
    left = lambda: deadline - time.monotonic()  # noqa: E731
    pr = fetch_pr(repo, number, timeout=max(1.0, left()))
    if pr is None:
        # Unreadable. An ordinary PR keeps its event body (today's fallback); a PR whose EVENT says it is a
        # train head cannot be told from a carrier without the live object, so nobody may judge it now.
        if TRAIN_BRANCH_RE.fullmatch(env.get("GITHUB_HEAD_REF", "")):
            return Resolution("unavailable", reason=f"PR #{number} (head {env.get('GITHUB_HEAD_REF')}) unreadable")
        return Resolution("none")
    if not is_verified_carrier(pr, repo):
        return Resolution("none", pr)
    try:
        members = carrier_members(base, "HEAD", cwd=cwd, deepen_timeout=max(1.0, left()))
    except CarrierUnavailable as exc:
        return Resolution("unavailable", pr, reason=str(exc))
    except CarrierError as exc:
        return Resolution("invalid", pr, reason=str(exc))
    if left() <= 0:
        return Resolution("unavailable", pr, reason=f"carrier resolution exceeded {RESOLVE_DEADLINE_S:.0f}s")
    bodies = fetch_bodies(repo, [n for n, _ in members], timeout=max(1.0, left()))
    # A body that could not be read stays None — NOT "" — so each gate decides whether it needed it:
    # the checklist only for a member with a human box to answer, the floor ratchet only for a lowering.
    got = bodies or {}
    unread = [n for n, _ in members if n not in got]
    return Resolution("carrier", pr, [(n, c, got.get(n)) for n, c in members],
                      reason=("member body unreadable: " + ", ".join(f"#{n}" for n in unread)) if unread else "")


def resolve(base: str, env=None, cwd: str | None = None, fetch_pr=None, fetch_bodies=None,
            deadline_s: float = RESOLVE_DEADLINE_S) -> Resolution:
    """The job's carrier facts, computed by the FIRST gate that asks and read from a cache file by every
    later one. Keyed by (PR number, HEAD sha, base): the subject of every gate in one job is that exact
    tree, so nothing a later gate reads can describe a different head. The cache lives in run-gates.py's
    per-invocation directory, so it never outlives the job; without it (a checker run by hand) the
    resolution simply happens in-process. Concurrent gates serialise on a lock instead of racing."""
    env = os.environ if env is None else env
    fetch_pr = fetch_pr or gh_pr                 # looked up at call time, so a test can substitute them
    fetch_bodies = fetch_bodies or gh_member_bodies
    cache_dir = env.get(CACHE_DIR_ENV, "")
    head = _git("rev-parse", "HEAD", cwd=cwd, check=False).stdout.strip()
    if not cache_dir or not os.path.isdir(cache_dir) or not head:
        return _resolve_uncached(base, env, cwd, fetch_pr, fetch_bodies, time.monotonic() + deadline_s)
    import fcntl
    import hashlib
    key = hashlib.sha256(f"{env.get('PR_NUMBER', '')}|{head}|{base}".encode()).hexdigest()[:24]
    path = os.path.join(cache_dir, f"merge-train-carrier-{key}.json")
    with open(path + ".lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            with open(path, encoding="utf-8") as fh:
                return Resolution.from_json(json.load(fh))
        except (OSError, ValueError, KeyError, TypeError):
            pass
        res = _resolve_uncached(base, env, cwd, fetch_pr, fetch_bodies, time.monotonic() + deadline_s)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(res.to_json(), fh)
        os.replace(tmp, path)
        return res


def not_evaluated(gate: str, reason: str) -> None:
    """Say, in the one line run-gates.py reads, that this gate could not reach a verdict. The caller
    exits non-zero: an unjudged carrier must never go green, and run-gates records the failure as
    `not_evaluated`, which the merge train treats as inconclusive — never as a culprit."""
    print(f"::warning::{gate}: NOT EVALUATED on this run — {reason}. Not a pass and not a finding: "
          f"the subject could not be read. Re-run the job.")
    print(f"{NOT_EVALUATED_LINE}  # {reason}")


def pr_body_with_members(body: str, base: str, env: dict | None = None, fetch_pr=None,
                         fetch_bodies=None) -> str:
    """`body`, plus — only for a VERIFIED carrier, read live from PR_NUMBER/GITHUB_REPOSITORY — each
    member's live body. For gates whose required text is per-member and self-identifying (a trailer
    naming an issue, a section naming a service), so the union is exactly what the members declared.
    Anything that is not a verified carrier, or whose members cannot be derived, gets `body` alone."""
    res = resolve(base, env, fetch_pr=fetch_pr, fetch_bodies=fetch_bodies)
    if res.kind != "carrier":
        if res.kind in ("invalid", "unavailable"):
            print(f"::warning::merge-train carrier members not derivable ({res.reason}) — carrier body only")
        return body
    if res.reason:
        print(f"::warning::merge-train carrier: {res.reason}")
    print(f"merge-train carrier — reading member bodies {', '.join(f'#{n}' for n, _, _ in res.members)}")
    return "\n\n".join([body] + [b or "" for _, _, b in res.members])
