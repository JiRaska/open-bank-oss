#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A GitOps image pin may only move FORWARD along main's history — never back.

WHY THIS EXISTS
---------------
`auto-deploy.yml` rewrites every selected service's pin to `sandbox-<source sha>` of the commit
whose build just finished (its line ~730: `TAG="sandbox-${SHA}"`), against whatever `main` holds
at that moment. Builds do not finish in commit order. So a deploy PR for an OLDER source commit,
opened after a NEWER commit's deploy PR already merged, rewrites main's newer pins to its older
ones — and nothing refuses it: git sees an ordinary edit (the PR's base already held the newer
pin, so there is no textual conflict), every job is green, and `supersede-deploy-prs.sh` compares
a new deploy PR only against OTHER OPEN deploy PRs, never against the pins main already carries.
Measured 2026-11-03: deploy PR #14579 pinned 34 services to one source sha; for 22 of them main's
pin was already a DESCENDANT of that sha — merging it would have rolled 22 services back while
carrying 12 forward, and every check on it was green. The owner's rule is "no downgrades, ever";
an explicit revert after a failure is allowed, as an explicit revert.

THE RULE
--------
For every `image: ...:sandbox-<sha>` line changed by the PR under `openbank-infra/gitops/`,
the NEW sha must be a descendant of (or equal to) the sha LIVE MAIN holds for that image NOW —
not the one at the PR's merge-base. That distinction is the whole defect: measured on #14579,
every one of its 34 moves is forward against its merge-base and 22 are backward against live
main, because newer deploys landed after the PR was cut. (Today only a textual conflict on the
same line stops such a PR, and an update-branch or a rebase that keeps the PR's side removes
even that.) Live main is read with `git show <live ref>:<path>` when the ref is present, else a
depth-1 fetch of the base branch, else the contents API; if none answers, the pin is UNKNOWN.
Ancestry is decided by history —
`git merge-base --is-ancestor` when both commits are present locally, the GitHub compare API
otherwise. The three other answers all fail the gate:

  behind    the new pin is an ANCESTOR of the old one: a downgrade.
  diverged  neither contains the other: not a deploy of main at all.
  repinned  same source sha, different digest or run suffix (see below).
  unparsed  a changed sandbox `image:` line the gate cannot read.
  unknown   a sha that cannot be resolved, or an API error: fail CLOSED. A pin that cannot be
            placed in history cannot be shown not to be a rollback, and the cost of being
            wrong is a silent rewind of a running service.

The FULL pin is compared, not only its source sha: the same sha with a different `-run<n>` suffix
or `@sha256:` digest is another image under an unchanged name, which history cannot order, so it
fails as `repinned`. A changed `image:` line that is a sandbox pin (it says `sandbox-`, or that
image was a sandbox pin before) but that the pin regex cannot read fails as `unparsed` — a shape
the gate cannot judge is never skipped. Third-party images are out of scope.

The one escape is explicit: the PR labelled `deploy-revert` may move pins backward (reported as
a notice, never silently). A merge-train carrier carries no labels of its own, so for a VERIFIED
carrier the label is read from every member. The branch name and the `PR-Number:` trailers are
both author-controlled, so verification is the train's own (as check-security-checklist's
run_carrier): merge_train_carrier.is_verified_carrier on the live PR, carrier_members over
base..head (only the train's member/regen commits), and the App's `merge-train/record` status on
exactly that head naming exactly its tree. A `train/*` head that fails any of these is judged on
its OWN labels like any PR. A label that cannot be read counts as absent. Reading labels needs
`gh` + GH_TOKEN; when that is unavailable the PR is treated as unlabelled, the failing direction.

On a push to `main` there is nothing left to block; the gate counts its subjects and exits 0.

WHAT IT DOES NOT DO
-------------------
It does not decide WHICH services a deploy PR should pin (that is `auto-deploy.yml`'s selection
and `supersede-deploy-prs.sh`'s coverage check), nor whether a hold applies (`check-deploy-holds.py`).
It only guarantees that whatever a PR pins, it never pins backward unannounced.

Usage:
  check-deploy-pin-monotonic.py --root . --base "$PR_DIFF_BASE"     # gate
  check-deploy-pin-monotonic.py --self-test                          # falsify every verdict, no network
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import subprocess
import sys
import tempfile
from typing import Callable

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import merge_train_carrier as carrier_lib  # sibling module; scripts run as files

PIN_RE = re.compile(r"^\s*(?:-\s*)?image:\s*['\"]?(?P<image>[^\s'\"@]+?):sandbox-(?P<sha>[0-9a-f]{7,40})"
                    r"(?P<rest>(?:-run[0-9]+)?(?:@sha256:[0-9a-f]{64})?)['\"]?\s*$")
# A sandbox TAG anywhere on a line (any key syntax): `sandbox-` + hex, not part of a longer word,
# so prose like an `openbank-sandbox-db-backups` bucket name is not one. Case-insensitive on purpose:
# an upper-case sha the pin regex refuses must still be caught here, not skipped.
SANDBOX_TAG_RE = re.compile(r"(?<![A-Za-z0-9_.-])sandbox-[0-9a-fA-F]{7,40}(?![0-9a-zA-Z])")
IMAGE_LINE_RE = re.compile(r"^\s*(?:-\s*)?image:\s*['\"]?(?P<ref>[^\s'\"]*)")
PIN_ROOT = "openbank-infra/gitops"
REVERT_LABEL = "deploy-revert"

OK, DOWNGRADE, DIVERGED, UNKNOWN, REPINNED, UNPARSED = "ok", "downgrade", "diverged", "unknown", "repinned", "unparsed"


def image_name(ref: str) -> str:
    """Last path segment of an image reference, without tag or digest."""
    return ref.split("@", 1)[0].rsplit("/", 1)[-1].split(":", 1)[0]


def git(root: str, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", root, *args], text=True, capture_output=True, check=check)


DUPLICATE = ("", "", "<duplicate>")   # one manifest pins the same image name to two different values


def full_pins_in_text(text: str) -> dict[str, tuple[str, str, str]]:
    """image name -> (source sha, rest of the pin: `-run<n>` + `@sha256:` digest, full repository path).
    An image name pinned to two DIFFERENT values in one manifest maps to DUPLICATE, never to whichever
    line came last — a dict that kept the last writer would let one line hide another."""
    out: dict[str, tuple[str, str, str]] = {}
    for line in text.splitlines():
        m = PIN_RE.match(line)
        if m:
            name = m.group("image").rsplit("/", 1)[-1]
            val = (m.group("sha"), m.group("rest"), m.group("image"))
            out[name] = val if out.get(name, val) == val else DUPLICATE
    return out


def pins_in_text(text: str) -> dict[str, str]:
    """image name -> source sha for every sandbox pin line in one manifest."""
    return {k: v[0] for k, v in full_pins_in_text(text).items()}


def added_lines(root: str, base: str, path: str) -> list[str]:
    """Every line the PR added to `path` — the gate judges each of these, not a per-name summary."""
    diff = git(root, "diff", "-U0", f"{base}...HEAD", "--", path, check=False).stdout
    return [ln[1:] for ln in diff.splitlines() if ln.startswith("+") and not ln.startswith("+++")]


def full_pins_at(root: str, rev: str, path: str) -> dict[str, tuple[str, str, str]]:
    r = git(root, "show", f"{rev}:{path}", check=False)
    return full_pins_in_text(r.stdout) if r.returncode == 0 else {}


def pins_at(root: str, rev: str, path: str) -> dict[str, str]:
    r = git(root, "show", f"{rev}:{path}", check=False)
    return pins_in_text(r.stdout) if r.returncode == 0 else {}


def count_subjects(root: str) -> int:
    n = 0
    for p in pathlib.Path(root, PIN_ROOT).rglob("*.y*ml"):
        try:
            n += len(pins_in_text(p.read_text(errors="replace")))
        except OSError:
            pass
    return n


def live_ref(root: str, branch: str) -> str | None:
    """A ref naming the CURRENT tip of `branch`: origin/<branch> when present, else a depth-1 fetch."""
    for ref in (f"refs/remotes/origin/{branch}",):
        if git(root, "rev-parse", "--verify", "-q", ref, check=False).returncode == 0:
            return ref
    r = git(root, "fetch", "-q", "--depth=1", "origin", branch, check=False)
    if r.returncode == 0:
        return "FETCH_HEAD"
    return None


def live_pins_api(repo: str, branch: str, path: str) -> dict[str, tuple[str, str, str]] | None:
    try:
        r = subprocess.run(["gh", "api", "-H", "Accept: application/vnd.github.raw", f"repos/{repo}/contents/{path}?ref={branch}"],
                           text=True, capture_output=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return full_pins_in_text(r.stdout) if r.returncode == 0 else None


def tree_pins(root: str, rev: str) -> dict[str, set[tuple[str, str, str]]] | None:
    """image name -> every distinct pin for it anywhere under PIN_ROOT at `rev` (None if unreadable).
    A pin is judged against the whole tree, so moving it to a new or renamed manifest is no escape."""
    r = git(root, "grep", "-h", "-I", "-e", "sandbox-", rev, "--", PIN_ROOT, check=False)
    if r.returncode not in (0, 1):
        return None
    out: dict[str, set[tuple[str, str, str]]] = {}
    for line in r.stdout.splitlines():
        m = PIN_RE.match(line)
        if m:
            out.setdefault(m.group("image").rsplit("/", 1)[-1], set()).add((m.group("sha"), m.group("rest"), m.group("image")))
    return out


def changed_manifests(root: str, base: str) -> list[str]:
    r = git(root, "diff", "--no-renames", "--name-only", f"{base}...HEAD", "--", PIN_ROOT)
    return [p for p in r.stdout.split("\n") if p.strip()]


def ancestry_local(root: str, old: str, new: str) -> str | None:
    """OK/DOWNGRADE/DIVERGED from local history, or None when either commit is not present."""
    for sha in (old, new):
        if git(root, "cat-file", "-e", f"{sha}^{{commit}}", check=False).returncode != 0:
            return None
    if git(root, "merge-base", "--is-ancestor", old, new, check=False).returncode == 0:
        return OK
    if git(root, "merge-base", "--is-ancestor", new, old, check=False).returncode == 0:
        return DOWNGRADE
    return DIVERGED


def ancestry_api(repo: str, old: str, new: str) -> str:
    """Same question asked of GitHub, for the fetch-depth:1 checkout the gates run in."""
    try:
        r = subprocess.run(["gh", "api", f"repos/{repo}/compare/{old}...{new}", "--jq", ".status"],
                           text=True, capture_output=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return UNKNOWN
    if r.returncode != 0:
        return UNKNOWN
    status = r.stdout.strip()
    return {"ahead": OK, "identical": OK, "behind": DOWNGRADE, "diverged": DIVERGED}.get(status, UNKNOWN)


def pr_labels(repo: str, number: str) -> set[str] | None:
    try:
        r = subprocess.run(["gh", "pr", "view", number, "--repo", repo, "--json", "labels", "--jq", ".labels[].name"],
                           text=True, capture_output=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if r.returncode != 0:
        return None
    return {x.strip() for x in r.stdout.splitlines() if x.strip()}


def verified_carrier_members(root: str, base: str, repo: str, number: str,
                             fetch_pr: Callable[[str, str], dict | None],
                             fetch_record: Callable[[str, str], str | None]) -> tuple[list[str] | None, str]:
    """The member PR numbers of a VERIFIED merge-train carrier, or (None, why) when this head is not one.

    `GITHUB_HEAD_REF` and commit trailers are both author-controlled: anyone with push access can push a
    `train/…` branch carrying a forged `PR-Number:` trailer. So, as check-security-checklist's
    run_carrier does: the live PR must be the train's own (merge_train_carrier.is_verified_carrier —
    App bot by login, type and id, exact branch, label), its commits must be the train's own member/regen
    commits (carrier_members), and the App's `merge-train/record` on exactly that head must name exactly
    its tree — the one thing a human push cannot produce."""
    pr = fetch_pr(repo, number) if number else None
    if not carrier_lib.is_verified_carrier(pr, repo):
        return None, "head is train/… but the PR is not a verified merge-train carrier"
    head = str((pr.get("head") or {}).get("sha") or "")
    here = git(root, "rev-parse", "HEAD", check=False).stdout.strip()
    here2 = git(root, "rev-parse", "--verify", "-q", "HEAD^2", check=False).stdout.strip()
    if not re.fullmatch(r"[0-9a-f]{40}", head) or head not in (here, here2):
        return None, f"carrier head {head[:12] or '?'} is not the tree under test"
    try:
        members = carrier_lib.carrier_members(base, head, cwd=root)
    except carrier_lib.CarrierError as exc:
        return None, f"carrier members cannot be verified ({exc})"
    recorded = fetch_record(repo, head)
    tree = git(root, "rev-parse", f"{head}^{{tree}}", check=False).stdout.strip()
    if not recorded or recorded != tree:
        return None, f"carrier head {head[:12]} carries no App merge-train/record of its tree"
    return [str(n) for n, _ in members], "verified carrier"


def revert_allowed(root: str, base: str, repo: str, number: str, head_ref: str,
                   labels_of: Callable[[str, str], set[str] | None],
                   fetch_pr: Callable[[str, str], dict | None] = carrier_lib.gh_pr,
                   fetch_record: Callable[[str, str], str | None] = carrier_lib.gh_record_tree) -> tuple[bool, str]:
    """True only when every PR this head stands for carries the revert label. A `train/` head that
    is not a verified carrier is judged on its OWN labels, like any PR."""
    note = ""
    if head_ref.startswith("train/"):
        members, why = verified_carrier_members(root, base, repo, number, fetch_pr, fetch_record)
        if members is not None:
            for m in members:
                labels = labels_of(repo, m)
                if labels is None or REVERT_LABEL not in labels:
                    return False, f"member #{m} is not labelled {REVERT_LABEL}"
            return True, f"every member ({', '.join('#' + m for m in members)}) of the verified carrier is labelled {REVERT_LABEL}"
        note = f"{why}; judged on its own labels: "
        print(f"deploy-pin-monotonic: {why} — judged as an ordinary PR")
    allowed, reason = own_labels_allow(repo, number, labels_of)
    return allowed, note + reason


def own_labels_allow(repo: str, number: str, labels_of: Callable[[str, str], set[str] | None]) -> tuple[bool, str]:
    if not number:
        return False, "no PR number in the environment"
    labels = labels_of(repo, number)
    if labels is None:
        return False, f"labels of #{number} could not be read (treated as unlabelled)"
    if REVERT_LABEL in labels:
        return True, f"#{number} is labelled {REVERT_LABEL}"
    return False, f"#{number} is not labelled {REVERT_LABEL}"


def compare(old: tuple[str, str, str], new: tuple[str, str, str], ancestry: Callable[[str, str], str]) -> str:
    if old[2] != new[2]:
        return REPINNED   # another registry/repository
    if old[0] == new[0] or new[0].startswith(old[0]) or old[0].startswith(new[0]):
        # same source commit: identical pin is OK; another digest/run under it cannot be placed in history
        return OK if old[1] == new[1] else REPINNED
    return ancestry(old[0], new[0])


def judge(root: str, base: str, ancestry: Callable[[str, str], str],
          live_pins: Callable[[str], dict[str, tuple[str, str, str]] | None],
          live_tree: Callable[[], dict[str, set[tuple[str, str, str]]] | None]) -> list[tuple[str, str, str, str, str]]:
    """(path, image, live pin, new pin, verdict) for every pin line the PR added, judged against LIVE main.

    Each ADDED line is judged on its own, against live main — never against the merge-base, since a
    line restating an older pin rewinds a newer one just the same. It is compared with the pin in the
    same manifest on live main, or, where that manifest has none (a new, moved or renamed file), with
    every pin for that name anywhere under PIN_ROOT. A line carrying a sandbox tag the pin regex cannot
    read fails as UNPARSED (whatever its key syntax); the full repository path and digest are part of
    the pin; a name pinned twice differently in one manifest fails closed; and a pin the PR removes
    fails closed unless its manifest is deleted and the image is gone from the whole tree (a
    decommission)."""
    out = []
    tree = live_tree()
    head_tree = tree_pins(root, "HEAD") or {}
    for path in changed_manifests(root, base):
        at_base, after = full_pins_at(root, base, path), full_pins_at(root, "HEAD", path)
        live = live_pins(path)
        known = set(at_base) | set(after) | set(live or {})
        for body in added_lines(root, base, path):
            if body.lstrip().startswith("#"):
                continue   # a YAML comment sets nothing
            m = PIN_RE.match(body)
            if not m:
                im = IMAGE_LINE_RE.match(body)
                if SANDBOX_TAG_RE.search(body) or (im and image_name(im.group("ref")) in known):
                    out.append((path, body.strip(), "?", "?", UNPARSED))   # a pin the gate cannot read
                continue
            image = m.group("image").rsplit("/", 1)[-1]
            new = (m.group("sha"), m.group("rest"), m.group("image"))
            if DUPLICATE in (after.get(image), (live or {}).get(image)):
                out.append((path, image, "?", new[0], UNPARSED))   # two different pins for one name
                continue
            if live is not None and image in live:
                olds = {live[image]}
            elif tree is not None:
                olds = tree.get(image, set())   # empty: image new to main
            else:
                out.append((path, image, "?", new[0], UNKNOWN))   # live main unreadable: fail closed
                continue
            for old in sorted(olds):
                verdict = compare(old, new, ancestry)
                if verdict != OK:
                    shown = (old[2], new[2]) if verdict == REPINNED and old[2] != new[2] else (old[0] + old[1], new[0] + new[1])
                    out.append((path, image, shown[0], shown[1], verdict))
        still_there = git(root, "cat-file", "-e", f"HEAD:{path}", check=False).returncode == 0
        for image in sorted(set(at_base) - set(after)):
            if still_there or image in head_tree:
                out.append((path, image, at_base[image][0], "(removed)", UNPARSED))   # pin dropped or moved
    return out


def run_gate(root: str, base: str, repo: str, number: str, head_ref: str, event: str, branch: str = "main",
             ancestry: Callable[[str, str], str] | None = None,
             labels_of: Callable[[str, str], set[str] | None] = pr_labels,
             live_pins: Callable[[str], dict[str, tuple[str, str, str]] | None] | None = None,
             live_tree: Callable[[], dict[str, set[tuple[str, str, str]]] | None] | None = None,
             fetch_pr: Callable[[str, str], dict | None] = carrier_lib.gh_pr,
             fetch_record: Callable[[str, str], str | None] = carrier_lib.gh_record_tree) -> int:
    subjects = count_subjects(root)
    print(f"SUBJECTS={subjects}")
    if not base:
        if event == "push":
            print("push to main: nothing left to block; pins counted only.")
            return 0
        print("::error::--base / PR_DIFF_BASE is required on a pull request")
        return 2

    def decide(old: str, new: str) -> str:
        local = ancestry_local(root, old, new)
        return local if local is not None else ancestry_api(repo, old, new)

    ref: str | None = None
    if live_pins is None or live_tree is None:
        ref = live_ref(root, branch)
        print(f"live {branch}: {ref or 'not fetchable; falling back to the contents API'}")

    if live_pins is None:
        def live_pins(path: str) -> dict[str, tuple[str, str, str]] | None:
            if ref is not None:
                return full_pins_at(root, ref, path)
            return live_pins_api(repo, branch, path)
    if live_tree is None:
        def live_tree() -> dict[str, set[tuple[str, str, str]]] | None:
            return tree_pins(root, ref) if ref is not None else None   # API mode: no whole-tree view

    moved = judge(root, base, ancestry or decide, live_pins, live_tree)
    bad = [m for m in moved if m[4] != OK]
    for path, image, old, new, verdict in moved:
        print(f"{verdict:9s} {image}: live {old[:8]} -> {new[:8]}  ({path})")
    if not bad:
        print(f"{len(moved)} pin(s) moved, all forward.")
        return 0
    allowed, why = revert_allowed(root, base, repo, number, head_ref, labels_of, fetch_pr, fetch_record)
    if allowed:
        for _path, image, old, new, verdict in bad:
            print(f"::notice::explicit revert: {image} {old[:8]} -> {new[:8]} is {verdict} ({why})")
        return 0
    for path, image, old, new, verdict in bad:
        print(f"::error file={path}::{image}: {old[:8]} -> {new[:8]} is {verdict.upper()} — a deploy may never move a pin "
              f"backward from what main holds now. {why}. Re-dispatch auto-deploy for current main, or label the PR `{REVERT_LABEL}` "
              f"to make the revert explicit.")
    return 1


# ---------------------------------------------------------------------------------------------
# self-test: a real repository with c1 -> c2 -> c3 on main and a diverged branch commit, every
# verdict reached from a pin diff, and the API fallback + label escape driven through stubs.
# ---------------------------------------------------------------------------------------------
def self_test() -> int:
    fails: list[str] = []

    def check(label: str, cond: bool, detail: str = "") -> None:
        if not cond:
            fails.append(f"{label}: {detail}")

    env = {**os.environ, "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_AUTHOR_NAME": "t",
           "GIT_AUTHOR_EMAIL": "t@example.invalid", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid"}
    env.pop("GIT_INDEX_FILE", None)
    env.pop("GIT_DIR", None)

    with tempfile.TemporaryDirectory() as root:
        def g(*a: str) -> str:
            return subprocess.run(["git", "-C", root, "-c", "commit.gpgsign=false", *a], text=True,
                                  capture_output=True, check=True, env=env).stdout.strip()

        manifest = pathlib.Path(root, PIN_ROOT, "components/svc-a/svc-a.yaml")
        other = pathlib.Path(root, PIN_ROOT, "components/svc-b/svc-b.yaml")

        def write_pins(a: str, b: str = "cafe0001", digest: str = "0" * 64, raw_a: str = "") -> None:
            manifest.parent.mkdir(parents=True, exist_ok=True)
            other.parent.mkdir(parents=True, exist_ok=True)
            line = raw_a or f"      image: registry.example/openbank-svc-a:sandbox-{a}@sha256:{digest}\n"
            manifest.write_text("spec:\n  containers:\n    - name: svc-a\n" + line)
            other.write_text(f"spec:\n  containers:\n    - name: svc-b\n      image: registry.example/openbank-svc-b:sandbox-{b}\n")

        g("init", "-q", "-b", "main")
        pathlib.Path(root, "x.txt").write_text("1\n")
        g("add", "-A"); g("commit", "-q", "-m", "c1"); c1 = g("rev-parse", "HEAD")
        pathlib.Path(root, "x.txt").write_text("2\n")
        g("add", "-A"); g("commit", "-q", "-m", "c2"); c2 = g("rev-parse", "HEAD")
        pathlib.Path(root, "x.txt").write_text("3\n")
        g("add", "-A"); g("commit", "-q", "-m", "c3"); c3 = g("rev-parse", "HEAD")
        pathlib.Path(root, "x.txt").write_text("4\n")
        g("add", "-A"); g("commit", "-q", "-m", "c4"); c4 = g("rev-parse", "HEAD")
        g("checkout", "-q", "-b", "side", c1)
        pathlib.Path(root, "y.txt").write_text("side\n")
        g("add", "-A"); g("commit", "-q", "-m", "side"); side = g("rev-parse", "HEAD")
        g("checkout", "-q", "main")

        # Base state: pins point at c2 (as main would after c2's deploy).
        write_pins(c2[:8]); g("add", "-A"); g("commit", "-q", "-m", "pins at c2"); base = g("rev-parse", "HEAD")

        def pr(new_a: str, new_b: str = "cafe0001", msg: str = "deploy", **kw) -> None:
            g("checkout", "-q", "-B", "pr", base)
            write_pins(new_a, new_b, **kw); g("add", "-A"); g("commit", "-q", "-m", msg)

        no_labels: Callable[[str, str], set[str] | None] = lambda _r, _n: set()
        with_label: Callable[[str, str], set[str] | None] = lambda _r, _n: {REVERT_LABEL}
        unreadable: Callable[[str, str], set[str] | None] = lambda _r, _n: None

        def live_from(ref: str) -> Callable[[str], dict[str, tuple[str, str, str]] | None]:
            return lambda path: full_pins_at(root, ref, path)

        def gate(**kw) -> int:
            # Live main is the local `main` branch; the PR branch is cut from `base` (main's tip at cut time).
            args = dict(root=root, base=base, repo="o/r", number="7", head_ref="chore/x", event="pull_request",
                        labels_of=no_labels, live_pins=live_from("main"), live_tree=lambda: tree_pins(root, "main"),
                        fetch_pr=lambda _r, _n: None,
                        fetch_record=lambda _r, _s: "")
            args.update(kw)
            return run_gate(**args)

        pr(c3[:8]);   check("forward pin (c2 -> c3) passes", gate() == 0)
        # THE #14579 SHAPE: forward against the merge-base, backward against live main. Main moved to c4
        # after the PR was cut; the PR still pins c3. Against `base` that is forward; against main, a downgrade.
        g("checkout", "-q", "main"); write_pins(c4[:8]); g("add", "-A"); g("commit", "-q", "-m", "pins at c4 (newer deploy landed)")
        pr(c3[:8]);   check("forward vs merge-base but BEHIND live main (#14579 shape) fails", gate() == 1)
        check("...and the same diff judged against the merge-base alone would pass (the blind spot, kept measurable)",
              gate(live_pins=live_from(base)) == 0)
        pr(c4[:8]);   check("pin equal to live main passes", gate() == 0)
        pr(c2[:8], "cafe0002"); check("svc-a untouched (equals merge-base) is ignored even though main moved; svc-b unknown fails",
                                      gate(ancestry=lambda o, n: UNKNOWN) == 1)
        pr(c4[:8]);   check("live main unreadable -> UNKNOWN, fails closed", gate(live_pins=lambda _p: None, live_tree=lambda: None) == 1)
        g("checkout", "-q", "main"); g("reset", "-q", "--hard", base)   # main back at c2 pins for the remaining cases
        pr(c1[:8]);   check("DOWNGRADE (c2 -> c1) fails", gate() == 1)
        pr(side[:8]); check("DIVERGED (c2 -> side) fails", gate() == 1)
        pr(c2[:12]);  check("same commit, longer abbreviation, is not a move", gate() == 0)
        # BYPASS 2a: same sha, different digest — a different image under an unchanged source sha.
        pr(c2[:8], digest="1" * 64); check("digest-only change fails closed", gate() == 1)
        # BYPASS 2b: a changed sandbox image line the pin regex cannot parse is skipped, not judged.
        pr(c1[:8], raw_a="      image: registry.example/openbank-svc-a:sandbox-" + c1[:8] + "-evil\n")
        check("unparseable changed sandbox image line fails closed", gate() == 1)
        # Review findings on the first fix: other key syntaxes, registry swap, last-writer-wins, dropped pin.
        pr(c1[:8], raw_a=f"      image: registry.example/openbank-svc-a:sandbox-{c3[:8]}@sha256:{'0' * 64}\n    - name: svc-a-sidecar\n" + '      "image": "registry.example/openbank-svc-a:sandbox-' + c1[:8] + '"\n')
        check("sandbox pin in another key syntax (quoted key) fails closed", gate() == 1)
        pr(c1[:8], raw_a=f"      image: registry.example/openbank-svc-a:sandbox-{c3[:8]}@sha256:{'0' * 64}\n    - name: svc-a-sidecar\n" + "      newTag: sandbox-" + c1[:8] + "\n")
        check("sandbox pin under a kustomize newTag key fails closed", gate() == 1)
        pr(c2[:8], raw_a=f"      image: evil.example/openbank-svc-a:sandbox-{c2[:8]}@sha256:{'0' * 64}\n")
        check("same sha and digest from ANOTHER registry fails closed (repinned)", gate() == 1)
        pr(c2[:8], raw_a=(f"      image: registry.example/openbank-svc-a:sandbox-{c1[:8]}@sha256:{'0' * 64}\n"
                          f"    - name: svc-a2\n      image: registry.example/openbank-svc-a:sandbox-{c2[:8]}@sha256:{'0' * 64}\n"))
        check("a downgrade line hidden by a later line pinning the same name (last-writer-wins) fails", gate() == 1)
        pr(c2[:8], raw_a=(f"      image: registry.example/openbank-svc-a:sandbox-{c3[:8]}@sha256:{'0' * 64}\n"
                          f"    - name: svc-a2\n      image: registry.example/openbank-svc-a:sandbox-{c4[:8]}@sha256:{'0' * 64}\n"))
        check("one name pinned to two DIFFERENT values in one manifest fails closed, even if both are forward", gate() == 1)
        pr(c2[:8], raw_a="      name: svc-a-without-image\n")
        check("a sandbox pin dropped from a manifest that still exists fails closed", gate() == 1)
        pr(c3[:8], raw_a=f"      # was sandbox-{c1[:8]}\n      image: registry.example/openbank-svc-a:sandbox-{c3[:8]}@sha256:{'0' * 64}\n")
        check("a comment mentioning sandbox- next to a forward pin still passes", gate() == 0)
        pr(c2[:8], raw_a=f"      image: registry.example/openbank-svc-a:sandbox-{c2[:8]}@sha256:{'0' * 64}\n"
                          "      destinationPath: s3://openbank-sandbox-db-backups/x\n")
        check("a non-pin line containing `sandbox-` (bucket name) is not a pin", gate() == 0)
        # Review round 3: restating the merge-base pin rewinds a newer live pin; moved/new/deleted manifests.
        g("checkout", "-q", "main"); write_pins(c4[:8]); g("add", "-A"); g("commit", "-q", "-m", "main moves to c4")
        pr(c2[:8], raw_a=f"      image:   registry.example/openbank-svc-a:sandbox-{c2[:8]}@sha256:{'0' * 64}\n")
        check("an added line restating the MERGE-BASE pin while live main is newer fails", gate() == 1)
        g("checkout", "-q", "-B", "pr", base); manifest.unlink()
        moved = pathlib.Path(root, PIN_ROOT, "components/svc-a2/svc-a.yaml"); moved.parent.mkdir(parents=True, exist_ok=True)
        moved.write_text(f"      image: registry.example/openbank-svc-a:sandbox-{c3[:8]}@sha256:{'0' * 64}\n")
        g("add", "-A"); g("commit", "-q", "-m", "move")
        check("a pin MOVED to a new manifest is judged against live main tree-wide (c4 -> c3 fails)", gate() == 1)
        g("checkout", "-q", "-B", "pr", base); moved.parent.mkdir(parents=True, exist_ok=True)
        moved.write_text(f"      image: registry.example/openbank-svc-a:sandbox-{c3[:8]}@sha256:{'0' * 64}\n")
        g("add", "-A"); g("commit", "-q", "-m", "second manifest")
        check("a pin ADDED in a new manifest for an image live main pins elsewhere is judged tree-wide", gate() == 1)
        moved.write_text(f"      image: registry.example/openbank-svc-a:sandbox-{c4[:8]}@sha256:{'0' * 64}\n")
        g("add", "-A"); g("commit", "-q", "-m", "second manifest at live")
        check("...and the same new manifest at live main's pin passes", gate() == 0)
        g("checkout", "-q", "-B", "pr", base); manifest.unlink(); g("add", "-A"); g("commit", "-q", "-m", "decommission svc-a")
        check("deleting a manifest whose image is gone from the whole tree (decommission) passes", gate() == 0)
        g("checkout", "-q", "main"); g("reset", "-q", "--hard", base)
        pr(c2[:8], raw_a=f"      image: registry.example/openbank-svc-a:sandbox-{c2[:8]}@sha256:{'0' * 64}\n"
                          f"      tag: sandbox-{c1[:8].upper()}\n")
        check("an upper-case sandbox tag the pin regex refuses fails closed", gate() == 1)
        pr(c2[:8], "cafe0002"); check("svc-b moves to an unresolvable sha: UNKNOWN fails closed (no API)",
                                      gate(ancestry=lambda o, n: UNKNOWN) == 1)
        pr(c1[:8]);   check("downgrade with the revert label passes", gate(labels_of=with_label) == 0)
        pr(c1[:8]);   check("downgrade with unreadable labels fails (treated as unlabelled)", gate(labels_of=unreadable) == 1)
        pr(c1[:8]);   check("downgrade with no PR number fails", gate(number="") == 1)
        # Carrier: only a VERIFIED carrier (App bot PR, train's own commits, App record of the tree) is
        # judged by its members' labels; anything else on a train/ head is judged on its own labels.
        member_only: Callable[[str, str], set[str] | None] = lambda _r, n: {REVERT_LABEL} if n == "41" else set()
        own_only: Callable[[str, str], set[str] | None] = lambda _r, n: {REVERT_LABEL} if n == "500" else set()

        def carrier_pr(head: str) -> dict:
            return {"user": {"login": carrier_lib.BOT_LOGIN, "type": "Bot", "id": carrier_lib.BOT_ID},
                    "head": {"ref": "train/1-1", "sha": head, "repo": {"full_name": "o/r"}},
                    "labels": [{"name": carrier_lib.CARRIER_LABEL}]}

        def as_carrier(**kw) -> int:
            head = g("rev-parse", "HEAD")
            args = {"head_ref": "train/1-1", "number": "500", "fetch_pr": lambda _r, _n: carrier_pr(head),
                    "fetch_record": lambda _r, _s: g("rev-parse", "HEAD^{tree}")}
            args.update(kw)
            return gate(**args)

        pr(c1[:8], msg="merge-train: member #41\n\nPR-Number: #41\nPR-Head: " + "a" * 40)
        check("verified carrier downgrade, member labelled, passes", as_carrier(labels_of=member_only) == 0)
        check("verified carrier downgrade, member unlabelled, fails", as_carrier(labels_of=no_labels) == 1)
        # BYPASS 1 (forged carrier): a human PR on a train/ head forges a member trailer naming a labelled PR.
        # Neither the branch name nor a commit message proves a carrier; the PR's OWN labels must decide.
        check("FORGED carrier (unverified train/ head, trailer names a labelled PR) fails on its own labels",
              gate(head_ref="train/1-1", number="7", labels_of=member_only) == 1)
        check("bot-authored carrier whose head has NO App record of its tree fails on its own labels",
              as_carrier(labels_of=member_only, fetch_record=lambda _r, _s: "") == 1)
        check("carrier record naming ANOTHER tree fails on its own labels",
              as_carrier(labels_of=member_only, fetch_record=lambda _r, _s: "f" * 40) == 1)
        check("unverified train/ head that IS itself labelled passes on its own label",
              gate(head_ref="train/1-1", number="500", labels_of=own_only) == 0)
        pr(c1[:8], msg="no trailer")
        check("carrier with a foreign commit is not verified; member labels ignored",
              as_carrier(labels_of=member_only) == 1)
        # API fallback mapping, without network.
        for status, want in (("ahead", OK), ("identical", OK), ("behind", DOWNGRADE), ("diverged", DIVERGED), ("garbage", UNKNOWN)):
            fake = subprocess.CompletedProcess([], 0, stdout=status + "\n", stderr="")
            real_run = subprocess.run
            subprocess.run = lambda *a, _f=fake, **k: _f  # type: ignore[assignment]
            try:
                check(f"compare API status {status!r} -> {want}", ancestry_api("o/r", "a" * 8, "b" * 8) == want)
            finally:
                subprocess.run = real_run
        failed = subprocess.CompletedProcess([], 1, stdout="", stderr="HTTP 404")
        real_run = subprocess.run
        subprocess.run = lambda *a, **k: failed  # type: ignore[assignment]
        try:
            check("compare API error -> UNKNOWN", ancestry_api("o/r", "a" * 8, "b" * 8) == UNKNOWN)
        finally:
            subprocess.run = real_run
        # Push to main: no base, counts subjects, exits 0; PR without base: exit 2.
        pr(c3[:8]);   check("push with no base exits 0", gate(base="", event="push") == 0)
        check("pull_request with no base exits 2", gate(base="") == 2)
        check("subjects are counted from the tree", count_subjects(root) == 2)
        # Pin parser: the real line shapes.
        text = ("          image: 123456789012.dkr.ecr.eu-north-1.amazonaws.com/openbank-admin-ui:sandbox-2d089f8fe-run41159167054\n"
                "          image: 123456789012.dkr.ecr.eu-north-1.amazonaws.com/openbank-aml-service:sandbox-48a6da90@sha256:" + "e" * 64 + "\n"
                "          image: ghcr.io/x/y:1.2.3\n")
        check("parser reads -run and @sha256 shapes and ignores non-sandbox images",
              pins_in_text(text) == {"openbank-admin-ui": "2d089f8fe", "openbank-aml-service": "48a6da90"}, str(pins_in_text(text)))

    for f in fails:
        print(f"FAIL {f}")
    print(f"self-test: {len(fails)} failure(s)")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--base", default=os.environ.get("PR_DIFF_BASE", ""))
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "JiRaska/open-bank-oss"))
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    return run_gate(root=a.root, base=a.base, repo=a.repo, number=os.environ.get("PR_NUMBER", ""),
                    head_ref=os.environ.get("GITHUB_HEAD_REF", ""), event=os.environ.get("GITHUB_EVENT_NAME", ""))


if __name__ == "__main__":
    sys.exit(main())
