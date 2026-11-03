#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A git fixture under `.github/scripts` must bring its own identity and must not sign.

WHY THIS EXISTS
---------------
`test-check-deploy-source-freshness.py` built a repository with `git init` + `git commit` and set no
identity. On a Mac that works — git derives a name and address from the account — so every local
run was green; on `ubuntu-latest` the commit answers "Please tell me who you are" and the suite was
red the first time CI ran it (#14697). The reverse trap is the same shape: a developer whose global
config says `commit.gpgsign = true` (this repo's owner signs every commit) gets a pinentry prompt or
a failure from a fixture that works on the runner. Both are environment leaking into a fixture.

Measured 2026-11-03 on `origin/main`: 37 files under `.github/scripts` run `git init` and
`git commit`; 36 set an identity in the file and the 37th through a shared helper
(`merge-train.py`'s `Git`, whose `base_env()` sets it); 28 neutralise signing, 9 do not.

THE RULE
--------
Every file under `.github/scripts` that invokes `git init` must, in the same file:
  1. provide an identity: `GIT_AUTHOR_NAME`/`GIT_COMMITTER_NAME` in an env, `user.name` via
     `git config` or `-c`, or a recognised shared helper (`gatelib.hermetic_git_env`,
     `merge-train.py`'s `Git`/`base_env`), AND
  2. neutralise signing: `commit.gpgsign=false` (config or `-c`), or isolate the global and system
     config (`GIT_CONFIG_GLOBAL` / `GIT_CONFIG_NOSYSTEM` / `GIT_CONFIG_SYSTEM`), or the same helpers.

The gate is a text check and says so: it proves a fixture NAMES the settings, not that every
commit runs under them. That is the decidable half, and it is the half that was missing.
Prefer `gatelib.hermetic_git_env()` in new fixtures; migrating the 37 existing ones is not required
(a 37-file refactor for a class with one measured occurrence is not worth its diff).

FAIL DIRECTION
--------------
Closed: a fixture that sets its identity in a way this scanner does not recognise fails and is
fixed by naming one of the recognised forms — one line. The scanner never recognises more than the
listed forms, so a new indirection cannot read as covered by accident.

Usage:
  check-git-fixture-hermetic.py --root .       # gate
  check-git-fixture-hermetic.py --self-test
"""
from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
import tempfile

SCRIPTS = ".github/scripts"
EXTS = (".py", ".sh", ".bash", ".mjs", ".cjs", ".js")
INIT_RE = re.compile(r"""(?:\bgit\b[^\n]*?\binit\b|["']init["']\s*[,)])""")
IDENTITY_RE = re.compile(r"GIT_AUTHOR_NAME|GIT_COMMITTER_NAME|user\.name|hermetic_git_env|\bbase_env\(|\btrain\.Git\(")
NOSIGN_RE = re.compile(r"commit\.gpgsign[\"']?\s*[=,\s]\s*[\"']?false|gpgsign=false|GIT_CONFIG_GLOBAL|GIT_CONFIG_NOSYSTEM|GIT_CONFIG_SYSTEM|hermetic_git_env|\bbase_env\(|\btrain\.Git\(")


# name -> reason; checked in both directions (a declared file that becomes hermetic fails too).
KNOWN_NON_HERMETIC: dict[str, str] = {
    # Its fixture env is a fresh dict with no HOME, so no global config is read in practice, but it
    # names neither form the scanner accepts. #14685 and #14689 both edit this file; add
    # GIT_CONFIG_GLOBAL there once they land and delete this entry.
    "check-security-checklist.py": "env dict without HOME; left for #14685/#14689 to avoid a three-way conflict",
}


def strip_comments(text: str) -> str:
    out = []
    for line in text.splitlines():
        in_s = in_d = False
        cut = None
        for i, ch in enumerate(line):
            if ch == "'" and not in_d:
                in_s = not in_s
            elif ch == '"' and not in_s:
                in_d = not in_d
            elif ch == "#" and not in_s and not in_d:
                cut = i
                break
        out.append(line if cut is None else line[:cut])
    return "\n".join(out)


def fixture_files(root: pathlib.Path) -> list[pathlib.Path]:
    r = subprocess.run(["git", "-C", str(root), "ls-files", "--", SCRIPTS], text=True, capture_output=True, check=False)
    paths = [root / f for f in r.stdout.split("\n") if f.strip()] if r.returncode == 0 \
        else sorted(p for p in (root / SCRIPTS).rglob("*") if p.is_file())
    out = []
    for p in paths:
        if p.suffix not in EXTS or p.name == pathlib.Path(__file__).name:
            continue
        try:
            text = strip_comments(p.read_text(errors="replace"))
        except OSError:
            continue
        if INIT_RE.search(text):
            out.append(p)
    return out


def verdict(text: str) -> list[str]:
    text = strip_comments(text)
    missing = []
    if not IDENTITY_RE.search(text):
        missing.append("no identity (GIT_AUTHOR_NAME/GIT_COMMITTER_NAME, user.name, or gatelib.hermetic_git_env)")
    if not NOSIGN_RE.search(text):
        missing.append("signing not neutralised (commit.gpgsign=false, GIT_CONFIG_GLOBAL/NOSYSTEM isolation, or gatelib.hermetic_git_env)")
    return missing


def run(root: pathlib.Path) -> int:
    files = fixture_files(root)
    print(f"SUBJECTS={len(files)}")
    rc = 0
    for p in files:
        missing = verdict(p.read_text(errors="replace"))
        rel = p.relative_to(root).as_posix()
        if p.name in KNOWN_NON_HERMETIC:
            if missing:
                print(f"known-non-hermetic {rel}: {KNOWN_NON_HERMETIC[p.name]}")
            else:
                print(f"::error file={rel}::declared KNOWN_NON_HERMETIC but it is hermetic now — remove the stale entry.")
                rc = 1
            continue
        for m in missing:
            print(f"::error file={rel}::git fixture: {m}. Works on a Mac (auto-identity, no signing) and fails on the "
                  f"runner, or the reverse; use gatelib.hermetic_git_env() or name the setting in the file.")
            rc = 1
    for name in KNOWN_NON_HERMETIC:
        if not any(p.name == name for p in files):
            print(f"::error::KNOWN_NON_HERMETIC names {name}, which is not a git fixture — remove the stale entry.")
            rc = 1
    print(f"{len(files)} git fixtures, {sum(1 for p in files if verdict(p.read_text(errors='replace')))} non-hermetic")
    return rc


def self_test() -> int:
    fails = []

    def check(label, cond, detail=""):
        if not cond:
            fails.append(f"{label}: {detail}")

    cases = {
        "no identity, no gpgsign": ('subprocess.run(["git", "init", "-q", d]); subprocess.run(["git", "commit", "-m", "x"])', 2),
        "identity via env, gpgsign via -c": ('env={"GIT_AUTHOR_NAME": "t"}; run(["git", "-c", "commit.gpgsign=false", "init"])', 0),
        "identity via config, no gpgsign": ('g("init", "-q"); g("config", "user.name", "t"); g("commit", "-m", "x")', 1),
        "identity + GIT_CONFIG_GLOBAL isolation": ('git init -q; export GIT_CONFIG_GLOBAL=/dev/null; git -c user.name=t commit -qm x', 0),
        "shared helper": ('env = gatelib.hermetic_git_env(); run(["git", "init"], env=env)', 0),
        "merge-train Git helper": ('git = train.Git(root); git("init", "-q", "-b", "main")', 0),
        "settings only in a comment": ('# GIT_AUTHOR_NAME=t commit.gpgsign=false\nrun(["git", "init"])', 2),
        "gpgsign via git config args": ('g("init"); g("config", "user.name", "t"); g("config", "commit.gpgsign", "false")', 0),
        "gpgsign via shell git config": ('git init -q && git config user.name s && git config commit.gpgsign false', 0),
        "gpgsign=true is not neutralised": ('run(["git", "-c", "user.name=t", "-c", "commit.gpgsign=true", "init"])', 1),
    }
    for label, (text, want) in cases.items():
        got = len(verdict(text))
        check(label, got == want, f"{got} findings, wanted {want}: {verdict(text)}")
    real_known = dict(KNOWN_NON_HERMETIC)
    KNOWN_NON_HERMETIC.clear()   # the synthetic tree has none of the real declarations
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        (root / SCRIPTS).mkdir(parents=True)
        (root / SCRIPTS / "test-bad.py").write_text('subprocess.run(["git", "init", "-q", d])\n')
        (root / SCRIPTS / "test-good.sh").write_text('git init -q; GIT_AUTHOR_NAME=t GIT_COMMITTER_NAME=t git -c commit.gpgsign=false commit -qm x\n')
        (root / SCRIPTS / "check-no-fixture.py").write_text('print("reads git log only")\n')
        (root / SCRIPTS / "notes.md").write_text('git init\n')
        files = {p.name for p in fixture_files(root)}
        check("only files invoking git init are subjects", files == {"test-bad.py", "test-good.sh"}, str(files))
        check("a non-hermetic fixture fails the gate", run(root) == 1)
        (root / SCRIPTS / "test-bad.py").write_text('env = gatelib.hermetic_git_env()\nsubprocess.run(["git", "init", "-q", d], env=env)\n')
        check("fixed with the shared helper -> pass", run(root) == 0)
        KNOWN_NON_HERMETIC["test-bad.py"] = "stale on purpose"
        check("a declared file that is hermetic fails (stale entry)", run(root) == 1)
        KNOWN_NON_HERMETIC.pop("test-bad.py"); KNOWN_NON_HERMETIC["test-gone.py"] = "missing"
        check("a declaration naming a non-fixture fails", run(root) == 1)
        KNOWN_NON_HERMETIC.pop("test-gone.py")
        (root / SCRIPTS / "test-bad.py").write_text('subprocess.run(["git", "init", "-q", d])\n')
        KNOWN_NON_HERMETIC["test-bad.py"] = "declared"
        check("a declared non-hermetic fixture passes", run(root) == 0)
        KNOWN_NON_HERMETIC.pop("test-bad.py")
    KNOWN_NON_HERMETIC.update(real_known)
    for f in fails:
        print(f"FAIL {f}")
    print(f"self-test: {len(fails)} failure(s)")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    return self_test() if a.self_test else run(pathlib.Path(a.root).resolve())


if __name__ == "__main__":
    sys.exit(main())
