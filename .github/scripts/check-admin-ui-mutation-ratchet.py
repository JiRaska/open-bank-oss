#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ADR-0370 U5: the Admin UI mutation-testing floor and scope only go up.

`openbank-admin-ui/stryker.config.mjs` declares `thresholds.break` (the mutation score below
which Stryker fails) and the decision modules it mutates (`src/lib/{a,b,...}/**/*.ts`). Stryker
enforces the score; nothing stops a PR from lowering `break` or dropping a module from the brace
list in the same change. This gate compares both with the PR's merge base. A lowered floor or a
dropped module fails unless the PR carries `mutation-floor-lowered: #<issue>`.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import subprocess
import sys

CONFIG = "openbank-admin-ui/stryker.config.mjs"
TRAILER = "mutation-floor-lowered:"
ISSUE_RE = re.compile(r"(?:#\d+|https://github\.com/[\w.-]+/[\w.-]+/issues/\d+)")
BREAK_RE = re.compile(r"\bbreak:\s*([^,}\s]+)")
SCOPE_RE = re.compile(r"'src/lib/\{([^}]*)\}/\*\*/\*\.ts'")


class Refused(ValueError):
    pass


def parse(text: str) -> tuple[int, set[str]]:
    breaks = BREAK_RE.findall(text)
    if len(breaks) != 1 or not breaks[0].isdigit():
        raise Refused(f"expected one integer `break:` literal, found {breaks!r}")
    scopes = SCOPE_RE.findall(text)
    if len(scopes) != 1:
        raise Refused("expected one `'src/lib/{...}/**/*.ts'` mutate glob")
    modules = {m.strip() for m in scopes[0].split(",") if m.strip()}
    return int(breaks[0]), modules


def compare(base: tuple[int, set[str]], head: tuple[int, set[str]]) -> list[str]:
    out = []
    if head[0] < base[0]:
        out.append(f"thresholds.break lowered {base[0]} -> {head[0]}")
    for module in sorted(base[1] - head[1]):
        out.append(f"module src/lib/{module} dropped from the mutate scope")
    return out


def justified(text: str) -> bool:
    for line in text.splitlines():
        s = line.strip()
        if s.lower().startswith(TRAILER) and ISSUE_RE.search(s[len(TRAILER):]):
            return True
    return False


def main_check(base_arg: str, body: str) -> int:
    base = (base_arg or os.environ.get("PR_DIFF_BASE", "")).strip()
    if not base:
        print("::error::PR_DIFF_BASE is empty but this gate requires it — refusing to run vacuously")
        return 2
    try:
        head = parse(pathlib.Path(CONFIG).read_text(encoding="utf-8"))
    except (OSError, Refused) as exc:
        print(f"::error file={CONFIG}::{exc}")
        return 1
    print(f"SUBJECTS={len(head[1]) + 1}")
    shown = subprocess.run(["git", "show", f"{base}:{CONFIG}"], capture_output=True, text=True)
    if shown.returncode != 0:
        print(f"admin-ui-mutation-ratchet: {CONFIG} is new against {base}; break={head[0]}, {len(head[1])} modules")
        return 0
    try:
        old = parse(shown.stdout)
    except Refused as exc:
        print(f"::error::base {CONFIG} unreadable: {exc}")
        return 1
    findings = compare(old, head)
    if not findings:
        print(f"admin-ui-mutation-ratchet: break={head[0]}, {len(head[1])} modules, nothing lowered")
        return 0
    log = subprocess.run(["git", "log", "--format=%B", f"{base}..HEAD"], capture_output=True, text=True).stdout
    if justified(f"{body}\n{log}"):
        print(f"admin-ui-mutation-ratchet: {len(findings)} lowering(s) under a `{TRAILER}` trailer")
        return 0
    for f in findings:
        print(f"::error file={CONFIG}::{f} (ADR-0370 U5). To lower deliberately, add `{TRAILER} #<issue>`.")
    return 1


def self_test() -> None:
    cfg = "mutate: ['src/lib/{a,b,c}/**/*.ts'],\n  thresholds: { high: 90, low: 75, break: 55 },"
    base = parse(cfg)
    assert base == (55, {"a", "b", "c"}), base
    assert compare(base, parse(cfg.replace("break: 55", "break: 60"))) == []
    assert compare(base, parse(cfg.replace("{a,b,c}", "{a,b,c,d}"))) == []
    assert compare(base, parse(cfg.replace("break: 55", "break: 40"))) == ["thresholds.break lowered 55 -> 40"]
    assert compare(base, parse(cfg.replace("{a,b,c}", "{a,c}"))) == ["module src/lib/b dropped from the mutate scope"]
    for bad in (cfg.replace("break: 55", "break: FLOOR"), cfg.replace("'src/lib/{a,b,c}/**/*.ts'", "'src/lib/**'")):
        try:
            parse(bad)
        except Refused:
            continue
        raise AssertionError(f"must refuse: {bad}")
    assert justified("mutation-floor-lowered: #12")
    assert not justified("mutation-floor-lowered: later")
    print("self-test: ok")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="")
    parser.add_argument("--body", default="")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    sys.exit(main_check(args.base, args.body))


if __name__ == "__main__":
    main()
