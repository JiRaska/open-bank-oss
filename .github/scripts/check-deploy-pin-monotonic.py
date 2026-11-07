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
  unknown   a sha that cannot be resolved, or an API error: fail CLOSED. A pin that cannot be
            placed in history cannot be shown not to be a rollback, and the cost of being
            wrong is a silent rewind of a running service.

The one escape is explicit: the PR labelled `deploy-revert` may move pins backward (reported as
a notice, never silently). A merge-train carrier carries no labels of its own, so for a head on
`train/*` the label is read from every member named in the carrier's `PR-Number: #n` trailers;
a member that cannot be read counts as unlabelled. Reading labels needs `gh` + GH_TOKEN; when
that is unavailable the PR is treated as unlabelled, which is the failing direction.

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

PIN_RE = re.compile(r"^\s*(?:-\s*)?image:\s*['\"]?(?P<image>[^\s'\"@]+?):sandbox-(?P<sha>[0-9a-f]{7,40})(?:-run[0-9]+)?(?:@sha256:[0-9a-f]{64})?['\"]?\s*$")
PIN_ROOT = "openbank-infra/gitops"
REVERT_LABEL = "deploy-revert"
TRAILER_RE = re.compile(r"^PR-Number:\s*#?([0-9]+)\s*$", re.MULTILINE)

OK, DOWNGRADE, DIVERGED, UNKNOWN = "ok", "downgrade", "diverged", "unknown"


def git(root: str, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", root, *args], text=True, capture_output=True, check=check)


def pins_in_text(text: str) -> dict[str, str]:
    """image name -> source sha for every sandbox pin line in one manifest."""
    out: dict[str, str] = {}
    for line in text.splitlines():
        m = PIN_RE.match(line)
        if m:
            out[m.group("image").rsplit("/", 1)[-1]] = m.group("sha")
    return out


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


def live_pins_api(repo: str, branch: str, path: str) -> dict[str, str] | None:
    try:
        r = subprocess.run(["gh", "api", "-H", "Accept: application/vnd.github.raw", f"repos/{repo}/contents/{path}?ref={branch}"],
                           text=True, capture_output=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return pins_in_text(r.stdout) if r.returncode == 0 else None


def changed_manifests(root: str, base: str) -> list[str]:
    r = git(root, "diff", "--name-only", f"{base}...HEAD", "--", PIN_ROOT)
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


def revert_allowed(root: str, base: str, repo: str, number: str, head_ref: str,
                   labels_of: Callable[[str, str], set[str] | None]) -> tuple[bool, str]:
    """True only when every PR this head stands for carries the revert label."""
    if head_ref.startswith("train/"):
        log = git(root, "log", "--format=%B", f"{base}..HEAD", check=False).stdout
        members = sorted(set(TRAILER_RE.findall(log)))
        if not members:
            return False, "carrier head with no PR-Number trailers"
        for m in members:
            labels = labels_of(repo, m)
            if labels is None or REVERT_LABEL not in labels:
                return False, f"member #{m} is not labelled {REVERT_LABEL}"
        return True, f"every member ({', '.join('#' + m for m in members)}) is labelled {REVERT_LABEL}"
    if not number:
        return False, "no PR number in the environment"
    labels = labels_of(repo, number)
    if labels is None:
        return False, f"labels of #{number} could not be read (treated as unlabelled)"
    if REVERT_LABEL in labels:
        return True, f"#{number} is labelled {REVERT_LABEL}"
    return False, f"#{number} is not labelled {REVERT_LABEL}"


def judge(root: str, base: str, ancestry: Callable[[str, str], str],
          live_pins: Callable[[str], dict[str, str] | None]) -> list[tuple[str, str, str, str, str]]:
    """(path, image, live sha, new sha, verdict) for every pin the PR moved, judged against LIVE main."""
    out = []
    for path in changed_manifests(root, base):
        at_base, after = pins_at(root, base, path), pins_at(root, "HEAD", path)
        live = live_pins(path)
        for image, new in sorted(after.items()):
            if at_base.get(image) == new:
                continue   # the PR did not move this pin; a squash keeps main's value for it
            if live is None:
                out.append((path, image, "?", new, UNKNOWN))   # live main unreadable: fail closed
                continue
            old = live.get(image)
            if old is None or old == new or new.startswith(old) or old.startswith(new):
                continue   # image new to main, or already at this commit (another abbreviation)
            out.append((path, image, old, new, ancestry(old, new)))
    return out


def run_gate(root: str, base: str, repo: str, number: str, head_ref: str, event: str, branch: str = "main",
             ancestry: Callable[[str, str], str] | None = None,
             labels_of: Callable[[str, str], set[str] | None] = pr_labels,
             live_pins: Callable[[str], dict[str, str] | None] | None = None) -> int:
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

    if live_pins is None:
        ref = live_ref(root, branch)
        print(f"live {branch}: {ref or 'not fetchable; falling back to the contents API'}")

        def live_pins(path: str) -> dict[str, str] | None:   # noqa: E306
            if ref is not None:
                r = git(root, "show", f"{ref}:{path}", check=False)
                return pins_in_text(r.stdout) if r.returncode == 0 else {}
            return live_pins_api(repo, branch, path)

    moved = judge(root, base, ancestry or decide, live_pins)
    bad = [m for m in moved if m[4] != OK]
    for path, image, old, new, verdict in moved:
        print(f"{verdict:9s} {image}: live {old[:8]} -> {new[:8]}  ({path})")
    if not bad:
        print(f"{len(moved)} pin(s) moved, all forward.")
        return 0
    allowed, why = revert_allowed(root, base, repo, number, head_ref, labels_of)
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

        def write_pins(a: str, b: str = "cafe0001") -> None:
            manifest.parent.mkdir(parents=True, exist_ok=True)
            other.parent.mkdir(parents=True, exist_ok=True)
            manifest.write_text(f"spec:\n  containers:\n    - name: svc-a\n      image: registry.example/openbank-svc-a:sandbox-{a}@sha256:{'0' * 64}\n")
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

        def pr(new_a: str, new_b: str = "cafe0001", msg: str = "deploy") -> None:
            g("checkout", "-q", "-B", "pr", base)
            write_pins(new_a, new_b); g("add", "-A"); g("commit", "-q", "-m", msg)

        no_labels: Callable[[str, str], set[str] | None] = lambda _r, _n: set()
        with_label: Callable[[str, str], set[str] | None] = lambda _r, _n: {REVERT_LABEL}
        unreadable: Callable[[str, str], set[str] | None] = lambda _r, _n: None

        def live_from(ref: str) -> Callable[[str], dict[str, str] | None]:
            return lambda path: pins_at(root, ref, path)

        def gate(**kw) -> int:
            # Live main is the local `main` branch; the PR branch is cut from `base` (main's tip at cut time).
            args = dict(root=root, base=base, repo="o/r", number="7", head_ref="chore/x", event="pull_request",
                        labels_of=no_labels, live_pins=live_from("main"))
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
        pr(c4[:8]);   check("live main unreadable -> UNKNOWN, fails closed", gate(live_pins=lambda _p: None) == 1)
        g("checkout", "-q", "main"); g("reset", "-q", "--hard", base)   # main back at c2 pins for the remaining cases
        pr(c1[:8]);   check("DOWNGRADE (c2 -> c1) fails", gate() == 1)
        pr(side[:8]); check("DIVERGED (c2 -> side) fails", gate() == 1)
        pr(c2[:12]);  check("same commit, longer abbreviation, is not a move", gate() == 0)
        pr(c2[:8], "cafe0002"); check("svc-b moves to an unresolvable sha: UNKNOWN fails closed (no API)",
                                      gate(ancestry=lambda o, n: UNKNOWN) == 1)
        pr(c1[:8]);   check("downgrade with the revert label passes", gate(labels_of=with_label) == 0)
        pr(c1[:8]);   check("downgrade with unreadable labels fails (treated as unlabelled)", gate(labels_of=unreadable) == 1)
        pr(c1[:8]);   check("downgrade with no PR number fails", gate(number="") == 1)
        # Carrier: label must be on every member named by PR-Number trailers.
        pr(c1[:8], msg="merge-train: member #41\n\nPR-Number: #41\nPR-Head: " + "a" * 40)
        check("carrier downgrade, member labelled, passes", gate(head_ref="train/1-1", number="", labels_of=with_label) == 0)
        check("carrier downgrade, member unlabelled, fails", gate(head_ref="train/1-1", number="", labels_of=no_labels) == 1)
        pr(c1[:8], msg="no trailer")
        check("carrier downgrade with no trailers fails even with labels", gate(head_ref="train/1-1", number="", labels_of=with_label) == 1)
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
