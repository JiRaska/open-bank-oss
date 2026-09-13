#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Inventory of long-lived credentials, with deadlines (ADR-0279 WS4 #18).

WHY. The OpenBao migration (ADR-0099) made DB passwords dynamic (`database/creds/…`,
24h TTL) — but every OIDC client secret and API key projected by an ExternalSecret from
a static `secret/data/…` kv path is a long-lived credential with no expiry anywhere in
the system that serves it. Measured 2026-09-04: 113 gitops files touch ExternalSecrets;
the fleet had no single list of which of them hold a static credential and by when it
must be rotated or migrated. This script is that list, plus the ratchet that stops the
list from growing silently.

CONVENTION INTRODUCED (and enforced on NEW manifests only):
  * an ExternalSecret whose remoteRef points at a STATIC kv path (not `database/creds/…`
    or another dynamic mount) must carry metadata annotation
    `openbank.io/rotation-deadline: YYYY-MM-DD` — the date by which the credential is
    rotated or the reference migrates to a dynamic mount;
  * dynamic references may annotate `openbank.io/rotation: dynamic` for the reader's
    benefit, but the path prefix already proves it, so it is not required.

MODES:
  --inventory           full scan, markdown table + counts (the weekly standing issue)
  --enforce-new BASE    diff-scoped gate: a NEW file declaring a static ExternalSecret
                        without the deadline annotation fails
  --self-test           offline fixtures

Existing undeclared statics are DEBT rendered by --inventory, not PR-blocking findings —
same staged shape as every ratchet in this repo.
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
import subprocess
import sys
from pathlib import Path

GITOPS = Path("openbank-infra/gitops")
DYNAMIC_KEY = re.compile(r"database/creds/|(^|/)creds/[a-z0-9-]+-db-vault-role")
DEADLINE = "openbank.io/rotation-deadline"
DYNAMIC_ANN = "openbank.io/rotation"
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

DOC_SPLIT = re.compile(r"^---\s*$", re.M)


class Cred:
    def __init__(self, doc: str, file: Path):
        self.file = file
        self.name = _field(doc, r"name:\s*([a-z0-9.-]+)") or "?"
        self.namespace = _field(doc, r"namespace:\s*([a-z0-9.-]+)") or "?"
        self.keys = re.findall(r"key:\s*([A-Za-z0-9_./-]+)", doc)
        self.dynamic = any(DYNAMIC_KEY.search(k) for k in self.keys)
        ann_block = re.search(r"annotations:\n((?:\s{4,}[^\n]*\n?)*)", doc)
        self.annotations = ann_block.group(1) if ann_block else ""
        self.deadline = _field(self.annotations, re.escape(DEADLINE) + r':\s*"?([^"\n]+)"?')

    @property
    def static(self) -> bool:
        return bool(self.keys) and not self.dynamic


def _field(text: str, pattern: str) -> str | None:
    m = re.search(pattern, text)
    return m.group(1).strip() if m else None


def scan(root: Path) -> list[Cred]:
    out: list[Cred] = []
    for f in sorted(root.rglob("*.yaml")):
        if GITOPS.parts[0] not in f.parts and str(GITOPS) not in str(f):
            continue
        text = f.read_text()
        for doc in DOC_SPLIT.split(text):
            if re.search(r"^kind:\s*ExternalSecret\s*$", doc, re.M):
                out.append(Cred(doc, f))
    return out


def validate_deadline(c: Cred, today: dt.date) -> str | None:
    """None when OK, else the violation text."""
    if c.deadline is None:
        return f"missing annotation `{DEADLINE}`"
    if not DATE_RE.match(c.deadline):
        return f"`{DEADLINE}: {c.deadline}` is not YYYY-MM-DD"
    if dt.date.fromisoformat(c.deadline) < today:
        return f"`{DEADLINE}: {c.deadline}` is in the past — rotate or re-date with a reason"
    return None


def inventory(root: Path, today: dt.date) -> tuple[str, int]:
    creds = scan(root)
    statics = [c for c in creds if c.static]
    declared = [c for c in statics if c.deadline and not validate_deadline(c, today)]
    overdue = [c for c in statics if c.deadline and validate_deadline(c, today) and "past" in (validate_deadline(c, today) or "")]
    undeclared = [c for c in statics if not c.deadline]
    lines = [
        "# Long-lived credential inventory", "",
        f"ExternalSecrets: **{len(creds)}** total — {len(statics)} static (long-lived), "
        f"{len(creds) - len(statics)} dynamic (`database/creds/…`, ADR-0099).", "",
        f"Static credentials with a valid rotation deadline: **{len(declared)}**; "
        f"overdue: **{len(overdue)}**; undeclared (debt): **{len(undeclared)}**.", "",
        "## Static credentials", "",
        "| credential | namespace | backend key(s) | rotation deadline |", "|---|---|---|---|",
    ]
    for c in sorted(statics, key=lambda c: (c.namespace, c.name)):
        dl = c.deadline or "— **UNDECLARED**"
        lines.append(f"| `{c.name}` | {c.namespace} | {', '.join(f'`{k}`' for k in c.keys)} | {dl} |")
    lines += ["", "_Maintained by credential-inventory.yml (ADR-0279 #18). Do not edit by hand. "
              "New static ExternalSecrets must carry `openbank.io/rotation-deadline` — enforced "
              "by the `credential-deadline-ratchet` gate._"]
    print(f"credential-inventory: {len(creds)} ExternalSecrets, {len(statics)} static, "
          f"{len(declared)} with deadline, {len(overdue)} overdue, {len(undeclared)} undeclared")
    return "\n".join(lines) + "\n", 1 if overdue else 0


def _diff_added(base: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", "diff", "--name-only", "--diff-filter=A", f"{base}...HEAD"],
                          capture_output=True, text=True, check=False)


def _can_diff(base: str) -> bool:
    """Can the gate's ACTUAL query run — not merely: does the object exist.

    This distinction is the whole bug, twice over. `base...HEAD` is a three-dot diff, so it
    needs a MERGE BASE, not just the base commit. Fetching the sha by name (`--depth=1`) makes
    `git cat-file -e` succeed while the histories stay disconnected, so the diff still exits
    128. The first version of this fix checked exactly that — object presence — declared the
    base reached, and failed in precisely the same place it was written to fix.

    Probing with the real command is the only criterion that cannot be satisfied by an
    almost-correct repair.
    """
    return _diff_added(base).returncode == 0


def reach_base(base: str) -> bool:
    """Make `base` reachable in a shallow checkout, reporting honestly if it cannot be.

    The gates shard checks out at `fetch-depth: 1` (ci.yml says so in its own comment), so a
    PR whose base commit has since scrolled out of the shallow window simply does not have it.
    `git diff base...HEAD` then exits 128 — and because the caller passed `check=True`, the
    gate died with a CalledProcessError traceback and was reported as
    `FAIL new static ExternalSecret must declare a rotation deadline`. It had found no such
    thing. It had not looked.

    That is the failure mode this repo keeps re-learning: a check that cannot distinguish
    "nothing to report" from "could not run" is not a check. Here it happened to fail closed,
    which is the right direction and the wrong sentence — it accused the PR of a credential
    violation it did not commit, and sent the author looking for an ExternalSecret that was
    never there. Measured on #9095, whose diff touches no manifest at all.

    Same deepen-in-place idiom as check-flyway-version-commit-order.py, for the same reason:
    60 other gates do not need full history and should not pay for it.
    """
    if _can_diff(base):
        return True
    # Deepen, cheapest first, re-probing with the real query after each attempt. Fetching the
    # sha by name alone is deliberately NOT enough here — see _can_diff.
    for args in (["--depth=1", "origin", base], ["--unshallow", "origin", "main"],
                 ["--depth=2147483647", "origin", "main"]):
        subprocess.run(["git", "fetch", "--quiet", *args],
                       capture_output=True, text=True, check=False)
        if _can_diff(base):
            return True
    return False


def enforce_new(base: str, today: dt.date) -> int:
    if not reach_base(base):
        print(f"::error::credential-deadline-ratchet could not reach the diff base {base}, so it "
              f"scanned NOTHING. This is an UNKNOWN result, not a clean one, and not a finding "
              f"against this pull request. Usual cause: the base commit has scrolled out of the "
              f"shard's shallow window (ci.yml checks out at fetch-depth: 1) — rebasing the branch "
              f"onto current main resolves it.")
        return 1
    out = _diff_added(base)
    if out.returncode != 0:
        print(f"::error::credential-deadline-ratchet could not diff {base}...HEAD "
              f"(git exit {out.returncode}), so it scanned NOTHING. UNKNOWN, not clean, and not a "
              f"finding against this pull request. git said: {out.stderr.strip()[:300]}")
        return 1
    bad = 0
    for name in out.stdout.splitlines():
        f = Path(name)
        if not f.exists() or f.suffix != ".yaml":
            continue
        for doc in DOC_SPLIT.split(f.read_text()):
            if not re.search(r"^kind:\s*ExternalSecret\s*$", doc, re.M):
                continue
            c = Cred(doc, f)
            if not c.static:
                continue
            violation = validate_deadline(c, today)
            if violation:
                print(f"::error::{name}: new static ExternalSecret '{c.name}' {violation}. "
                      f"Rotate-by dates are the only thing between a long-lived credential and "
                      f"an immortal one (ADR-0279 #18).")
                bad += 1
    print(f"credential-deadline-ratchet: {bad} finding(s)")
    return 1 if bad else 0


def self_test() -> int:
    bad = 0
    today = dt.date(2026, 9, 4)
    doc_static = (
        "kind: ExternalSecret\nmetadata:\n  name: foo-oidc\n  namespace: foo\n"
        "spec:\n  target:\n    name: foo-oidc\n  data:\n  - remoteRef:\n      key: account-service\n")
    c = Cred(doc_static, Path("x.yaml"))
    if not c.static or c.dynamic:
        print("self-test FAIL: static classification"); bad += 1
    if validate_deadline(c, today) is None:
        print("self-test FAIL: missing deadline must be a violation"); bad += 1
    doc_dyn = doc_static.replace("key: account-service", "key: database/creds/foo-db-vault-role")
    if Cred(doc_dyn, Path("x.yaml")).static:
        print("self-test FAIL: dynamic path classified as static"); bad += 1
    doc_ok = doc_static.replace("metadata:\n",
                                f'metadata:\n  annotations:\n    {DEADLINE}: "2027-01-01"\n')
    if validate_deadline(Cred(doc_ok, Path("x.yaml")), today) is not None:
        print("self-test FAIL: valid future deadline flagged"); bad += 1
    doc_past = doc_ok.replace("2027-01-01", "2026-01-01")
    v = validate_deadline(Cred(doc_past, Path("x.yaml")), today)
    if v is None or "past" not in v:
        print("self-test FAIL: past deadline not caught"); bad += 1
    doc_bad = doc_ok.replace('"2027-01-01"', '"next quarter"')
    if validate_deadline(Cred(doc_bad, Path("x.yaml")), today) is None:
        print("self-test FAIL: non-date deadline accepted"); bad += 1
    # The gate must be unable to pass off "could not look" as "looked and found nothing" —
    # and must not dress it up as a credential finding either. A sha of the right shape that
    # cannot exist stands in for the scrolled-out base seen in CI.
    #
    # Run it inside a throwaway repo with NO remote, not against the checkout. Two reasons, both
    # learned the hard way: against the real repo `reach_base` reaches the network and tries
    # `--unshallow`, which took 12.7s against this gate's 5s budget and failed the shard; and a
    # self-test that deepens the checkout it is running in has changed the thing it was meant to
    # observe. With no `origin` configured every fetch fails immediately and offline, so the
    # unreachable-base path is exercised at full speed.
    import contextlib
    import io
    import os
    import tempfile
    unreachable = "0" * 40
    buf = io.StringIO()
    cwd = os.getcwd()
    with tempfile.TemporaryDirectory() as tmp:
        try:
            os.chdir(tmp)
            subprocess.run(["git", "init", "--quiet"], check=False,
                           capture_output=True, text=True)
            with contextlib.redirect_stdout(buf):
                rc = enforce_new(unreachable, today)
        finally:
            os.chdir(cwd)
    said = buf.getvalue()
    if rc == 0:
        print("self-test FAIL: unreachable base returned success — a gate that did not run "
              "must never report clean"); bad += 1
    if "UNKNOWN" not in said:
        print("self-test FAIL: unreachable base did not say UNKNOWN"); bad += 1
    if "rotation deadline" in said or "ADR-0279" in said:
        print("self-test FAIL: unreachable base reported as a credential violation"); bad += 1

    # The three checks above pin the OUTCOME contract (never clean, always UNKNOWN, never an
    # accusation) — but they pass whether or not `reach_base` actually recovers anything, because
    # an unrecoverable base and a failed diff both land on an honest UNKNOWN. That left the half
    # that fixes the real CI symptom unproven, so it gets its own case: a base that is genuinely
    # absent from a shallow clone and genuinely recoverable from its origin. `file://` keeps it
    # offline and fast; the deepening path is the same one CI takes.
    # Strip the inherited git environment for the WHOLE fixture, process-wide and restored
    # after. Passing a clean env to the setup commands is not enough: the recovery case calls
    # enforce_new, whose own subprocesses inherit os.environ, and GIT_DIR/GIT_INDEX_FILE
    # override the working directory — so `os.chdir(clone)` is silently ignored and git keeps
    # answering about the main checkout. That is what CI was doing, and it is invisible from
    # inside the fixture: every command succeeds, against the wrong repository.
    inherited_git = {k: v for k, v in os.environ.items() if k.startswith("GIT_")}
    for k in inherited_git:
        del os.environ[k]
    try:
        with tempfile.TemporaryDirectory() as tmp:
            origin, clone = Path(tmp) / "origin", Path(tmp) / "clone"
            git_o = ["git", "-C", str(origin)]
            # A fixture must not depend on the ambient git configuration. CI built no commits at
            # all here — `rev-parse HEAD~2` came back `fatal: ambiguous argument` — while the same
            # code committed fine on a developer machine, which is the signature of a system or
            # global gitconfig doing something the fixture never asked for (a commit template, a
            # hooks path, signing, an identity policy). Rather than chase which one, the whole
            # ambient layer is switched off: GIT_CONFIG_* to /dev/null, HOME inside the fixture,
            # and the identity supplied through the environment instead of inherited.
            # Strip EVERY inherited GIT_* variable, not just the config ones. CI exports the
            # checkout's own git environment, so the fixture's `git commit` was writing through
            # the MAIN repository's index and died on a blob it had no reason to know about:
            #
            #   error: invalid object 100644 74c3fada… for '.clusterfuzzlite/Dockerfile'
            #
            # A file from this repo, in a commit meant to contain one line of text. Inheriting the
            # ambient git environment is what made a fixture that passes locally impossible in CI.
            env = {
                **{k: v for k, v in os.environ.items() if not k.startswith("GIT_")},
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_CONFIG_SYSTEM": os.devnull,
                "GIT_CONFIG_NOSYSTEM": "1",
                "HOME": str(Path(tmp) / "home"),
                "GIT_AUTHOR_NAME": "fixture", "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
                "GIT_COMMITTER_NAME": "fixture", "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
            }
            (Path(tmp) / "home").mkdir(exist_ok=True)
            steps: list[subprocess.CompletedProcess[str]] = []

            def git(*args: str) -> subprocess.CompletedProcess[str]:
                done = subprocess.run(["git", *args], check=False, capture_output=True,
                                      text=True, env=env)
                steps.append(done)
                return done

            # Build defensively and stop at the first failure. A fixture that raises instead of
            # reporting is the same defect once more: the exception escapes as a crash and the
            # gate is recorded as unfalsified, with no sentence anywhere saying the fixture is
            # what broke.
            origin.mkdir(parents=True, exist_ok=True)
            try:
                if git("init", "--quiet", "-b", "main", str(origin)).returncode == 0:
                    for n in range(3):
                        (origin / f"f{n}.txt").write_text(str(n))
                        if git(*git_o[1:], "add", f"f{n}.txt").returncode != 0:
                            break
                        if git(*git_o[1:], "-c", "commit.gpgsign=false",
                               "commit", "--quiet", "-m", f"c{n}").returncode != 0:
                            break
            except OSError as exc:
                steps.append(subprocess.CompletedProcess(["<fixture build>"], 1, "", str(exc)))
            rev = git(*git_o[1:], "rev-parse", "HEAD~2")
            old_sha = rev.stdout.strip()
            cloned = git("clone", "--quiet", "--depth=1", f"file://{origin}", str(clone))
            # A fixture that failed to build must say so as a FIXTURE fault. Reporting it as
            # "the gate is broken" would be this gate's own bug one storey up: a setup failure
            # dressed as a finding. `git rev-parse` echoes its argument back when it cannot
            # resolve it, so an unbuilt fixture hands the gate the literal string "HEAD~2" and
            # the failure reads exactly like a real one.
            setup_error = None
            if not re.fullmatch(r"[0-9a-f]{40}", old_sha):
                # Name the FIRST step that failed, not just the last symptom: the earlier
                # attempt reported rev-parse's complaint, which is downstream of whichever
                # setup command actually broke.
                first_bad = next((d for d in steps if d.returncode != 0), None)
                blame = (f"first failing setup step: {' '.join(first_bad.args)!r} "
                         f"rc={first_bad.returncode} stderr={first_bad.stderr.strip()[:200]}"
                         if first_bad else "every setup step exited 0, which should be impossible")
                setup_error = (f"rev-parse gave {old_sha!r} (rc={rev.returncode}); {blame}")
            elif cloned.returncode != 0:
                setup_error = f"clone rc={cloned.returncode}; stderr={cloned.stderr.strip()[:200]}"
            present_before, rc2, buf2 = False, 0, io.StringIO()
            if setup_error is None:
                cwd2 = os.getcwd()
                try:
                    os.chdir(clone)
                    # Prove git is answering about the CLONE before trusting anything it says.
                    # GIT_DIR/GIT_INDEX_FILE override the working directory, so a leaked one
                    # makes every command below succeed against the wrong repository — and the
                    # failure then reads as "reach_base is broken", which is the exact
                    # misattribution this gate exists to stop.
                    # Ask which REPOSITORY git is using, not which directory it is standing
                    # in. `--show-toplevel` reports the cwd whatever GIT_DIR says, so it agrees
                    # with the fixture even while every command goes to another repository —
                    # a probe that cannot observe the fault it was added for. `--git-dir` is
                    # the one that moves. An empty answer is a fault too: `Path("").resolve()`
                    # is the cwd, so blank would otherwise read as agreement.
                    shown = subprocess.run(["git", "rev-parse", "--absolute-git-dir"],
                                           check=False, capture_output=True, text=True)
                    gitdir = shown.stdout.strip()
                    if shown.returncode != 0 or not gitdir:
                        setup_error = (f"`git rev-parse --absolute-git-dir` gave no answer in "
                                       f"the fixture clone (rc={shown.returncode}, "
                                       f"stderr={shown.stderr.strip()[:120]})")
                    elif Path(gitdir).resolve() != (clone / ".git").resolve():
                        setup_error = (f"git is using repository {gitdir!r}, not the fixture "
                                       f"clone's {str(clone / '.git')!r} — a GIT_* variable is "
                                       f"leaking in, so every answer below is about the wrong "
                                       f"repository")
                    present_before = _can_diff(old_sha)
                    with contextlib.redirect_stdout(buf2):
                        rc2 = enforce_new(old_sha, today)
                finally:
                    os.chdir(cwd2)
    finally:
        os.environ.update(inherited_git)

    if setup_error is not None:
        print(f"::error::self-test FAIL: the recovery FIXTURE did not build, so the recovery path was "
              f"never exercised — this is a fixture fault, not a gate finding: {setup_error}")
        bad += 1
    elif present_before:
        print("self-test FAIL: shallow clone already had the old base — the fixture proves "
              "nothing, so the recovery path was never exercised"); bad += 1
    elif rc2 != 0 or "UNKNOWN" in buf2.getvalue():
        print(f"self-test FAIL: a recoverable base was not recovered (rc={rc2}) — reach_base "
              f"is not doing its job: {buf2.getvalue().strip()[:200]}"); bad += 1
    print("credential-inventory self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--inventory", action="store_true")
    ap.add_argument("--md", help="write the inventory markdown here")
    ap.add_argument("--enforce-new", metavar="BASE")
    ap.add_argument("--today", default=dt.date.today().isoformat(),
                    help="YYYY-MM-DD override (deterministic tests; no bare clock reads in CI)")
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    today = dt.date.fromisoformat(args.today)
    if args.enforce_new:
        return enforce_new(args.enforce_new, today)
    if args.inventory:
        md, rc = inventory(Path(args.root), today)
        if args.md:
            Path(args.md).write_text(md)
        else:
            print(md)
        return rc
    ap.error("one of --inventory / --enforce-new / --self-test is required")


if __name__ == "__main__":
    sys.exit(main())
