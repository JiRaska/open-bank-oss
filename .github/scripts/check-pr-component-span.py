#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A releasing PR must stay reviewable: bounded released-component span and changed-line count.

WHY THIS EXISTS
---------------
A feature PR grew to 698 files across 15 released modules before anyone measured it: it could not
be reviewed, it collected 149 conflicts, and its release notes would have landed in fifteen
changelogs from one squash commit. The size was never a decision — it accreted, one slice at a
time, because nothing counts it. This gate counts it.

THE RULE (limits live in rules.yaml: pr_span_limits)
  * A PR whose title type releases (feat/fix/perf/security, or a `!` breaking marker) fails when
    its diff touches more than `max_version_dirs` directories holding a version.txt, or more than
    `max_changed_lines` changed lines (added + deleted, binary files excluded).
  * The `integration_label` label (`integration-branch`) exempts it — that is the declared shape
    for work that must land as one unit, and it is reviewed slice by slice on that branch.
  * A PR carrying that label fails if its base is `main`: an integration branch merges INTO a
    long-lived branch, never straight onto main, or the label is just a bypass.

A file belongs to the DEEPEST directory above it that holds a version.txt; files under no
released component (docs, .github) count toward the line total only.

Usage:
  check-pr-component-span.py --diff-base <sha> [--head HEAD] --pr <n>          # labels/base/title via gh
  check-pr-component-span.py --diff-base <sha> --title T --labels a,b --base-ref main
  check-pr-component-span.py --self-test
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys
import tempfile

import yaml

RULES = "openbank-libs/governance/rules.yaml"
TITLE_RE = re.compile(r"^(?P<type>[a-z]+)(\([^)]*\))?(?P<bang>!)?:")


def git(root: pathlib.Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True, text=True).stdout


def limits(root: pathlib.Path) -> dict:
    data = yaml.safe_load((root / RULES).read_text(encoding="utf-8"))
    lim = data.get("pr_span_limits")
    if not isinstance(lim, dict):
        raise SystemExit(f"{RULES}: pr_span_limits missing — refusing to run with invented limits")
    return lim


def releasing(title: str, types: list[str]) -> bool:
    m = TITLE_RE.match(title.strip())
    return bool(m) and (m.group("type") in types or bool(m.group("bang")))


def measure(root: pathlib.Path, diff_base: str, head: str) -> tuple[set[str], int, int]:
    """(component dirs touched, changed lines, changed files) for diff_base...head."""
    version_dirs = sorted(
        {str(pathlib.PurePosixPath(p).parent) for p in git(root, "ls-tree", "-r", "--name-only", head).splitlines()
         if p == "version.txt" or p.endswith("/version.txt")},
        key=len, reverse=True)
    touched: set[str] = set()
    lines = files = 0
    for row in git(root, "diff", "--numstat", "--no-renames", f"{diff_base}...{head}").splitlines():
        added, deleted, path = row.split("\t", 2)
        files += 1
        if added != "-":
            lines += int(added) + int(deleted)
        for d in version_dirs:
            if d == "." or path.startswith(d + "/"):
                touched.add(d)
                break
    return touched, lines, files


def evaluate(lim: dict, title: str, labels: list[str], base_ref: str,
             touched: set[str], lines: int) -> list[str]:
    label = lim["integration_label"]
    findings = []
    if label in labels:
        if base_ref == "main":
            findings.append(f"label `{label}` on a PR whose base is main — an integration branch merges into a "
                            f"long-lived branch, never onto main; retarget the PR or drop the label")
        return findings
    if not releasing(title, lim["releasing_types"]):
        return findings
    if len(touched) > lim["max_version_dirs"]:
        findings.append(f"releasing PR touches {len(touched)} released components (> {lim['max_version_dirs']}): "
                        f"{', '.join(sorted(touched))} — split it, or land it via an `{label}` branch")
    if lines > lim["max_changed_lines"]:
        findings.append(f"releasing PR changes {lines} lines (> {lim['max_changed_lines']}) — split it, "
                        f"or land it via an `{label}` branch")
    return findings


def pr_context(pr: str) -> tuple[str, list[str], str]:
    out = subprocess.run(["gh", "pr", "view", pr, "--json", "title,labels,baseRefName"],
                         check=True, capture_output=True, text=True).stdout
    d = json.loads(out)
    return d["title"], [lab["name"] for lab in d["labels"]], d["baseRefName"]


def self_test() -> int:
    fails = 0
    lim = {"max_version_dirs": 3, "max_changed_lines": 15000, "integration_label": "integration-branch",
           "releasing_types": ["feat", "fix", "perf", "security"]}
    with tempfile.TemporaryDirectory() as t:
        root = pathlib.Path(t)
        g = lambda *a: subprocess.run(["git", "-C", t, *a], check=True, capture_output=True)  # noqa: E731
        g("init", "-q", "-b", "main")
        g("config", "user.email", "t@t")
        g("config", "user.name", "t")
        for svc in ("a", "b", "c", "d", "a/nested"):
            (root / svc).mkdir(parents=True, exist_ok=True)
            (root / svc / "version.txt").write_text("0.1.0\n")
        (root / "docs").mkdir()
        g("add", ".")
        g("commit", "-qm", "base")
        base = git(root, "rev-parse", "HEAD").strip()
        for svc in ("a", "b", "c", "d"):
            (root / svc / "x.kt").write_text("x\n")
        g("add", ".")
        g("commit", "-qm", "four components")
        four = git(root, "rev-parse", "HEAD").strip()
        touched, lines, _ = measure(root, base, four)
        cases = [
            # (name, title, labels, base_ref, touched, lines, must_fail)
            ("feat across 4 components", "feat(a): x", [], "main", touched, lines, True),
            ("fix over line limit", "fix(a): x", [], "main", {"a"}, 15001, True),
            ("breaking chore across 4", "chore(a)!: x", [], "main", touched, lines, True),
            ("integration label onto main", "chore: x", ["integration-branch"], "main", set(), 1, True),
            ("feat across 3", "feat(a): x", [], "main", {"a", "b", "c"}, 15000, False),
            ("chore across 4", "chore(a): x", [], "main", touched, 99999, False),
            ("labelled into integration branch", "feat(a): x", ["integration-branch"], "int/x", touched, 99999, False),
        ]
        if touched != {"a", "b", "c", "d"} or lines != 4:
            print(f"  [FAIL] measure() saw {sorted(touched)} / {lines} lines, expected a,b,c,d / 4")
            fails += 1
        # a file under a nested component belongs to the deeper one
        (root / "a/nested/y.kt").write_text("y\n")
        g("add", ".")
        g("commit", "-qm", "nested")
        nested, _, _ = measure(root, four, "HEAD")
        if nested != {"a/nested"}:
            print(f"  [FAIL] nested file attributed to {sorted(nested)}, expected a/nested")
            fails += 1
        for name, title, labels, base_ref, tch, ln, must_fail in cases:
            got = bool(evaluate(lim, title, labels, base_ref, tch, ln))
            ok = got == must_fail
            fails += 0 if ok else 1
            print(f"  [{'ok' if ok else 'FAIL'}] {name}: {'red' if got else 'green'}")
    if fails == 0:
        print("check-pr-component-span self-test: ok (4 known-positives red, 3 known-negatives green)")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--diff-base")
    ap.add_argument("--head", default="HEAD")
    ap.add_argument("--pr")
    ap.add_argument("--title")
    ap.add_argument("--labels", default="")
    ap.add_argument("--base-ref")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not a.diff_base:
        print("--diff-base is empty but this gate requires it — refusing to run vacuously")
        return 2
    root = pathlib.Path(a.root)
    lim = limits(root)
    if a.pr:
        title, labels, base_ref = pr_context(a.pr)
    else:
        title, labels, base_ref = a.title or "", [x for x in a.labels.split(",") if x], a.base_ref or "main"
    touched, lines, files = measure(root, a.diff_base, a.head)
    print(f"title: {title!r}  labels: {labels}  base: {base_ref}")
    print(f"released components touched: {len(touched)} ({', '.join(sorted(touched)) or '-'}); "
          f"changed lines: {lines}; files: {files}")
    print(f"SUBJECTS={files}")
    findings = evaluate(lim, title, labels, base_ref, touched, lines)
    for f in findings:
        print(f"::error::{f}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
