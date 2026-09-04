#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Money-path PRs must actually TICK the security checklist, not just carry it.

WHY (ADR-0279 WS4 #26). The PR template's "## Security checklist" is the fleet's
per-PR security control — secrets/PII sweep, no unjustified suppressions, dependency
review, review-required labels. Measured behaviour says a checkbox nobody has to tick
gets read never: the template renders, the boxes stay empty, the review proceeds.
The champions/pitfall programme (AGENTS.md engineering notes) only works if the
money-path PR pauses on the lines written for exactly it.

WHAT IT CHECKS — only when the PR diff touches a `money_path_services` directory
(read from rules.yaml, the one list, so the two-lists-drift bug class cannot apply):
  * the PR body keeps a "## Security checklist" section (deleting the section is a
    finding, same as leaving it blank — otherwise the fix is "delete the question");
  * every checkbox inside that section is ticked (`[x]`/`[X]`). An unticked box is
    listed verbatim in the error, so the author ticks it or states why in the PR.

Deliberately NOT checked: the checkboxes' truthfulness (CI cannot know whether the
author really ran a secrets sweep — the control is the pause, not the proof) and
non-money-path PRs (the pause is priced; spend it where a mistake moves money).

Usage:  check-security-checklist.py --body-file <file> [--base origin/main]
        check-security-checklist.py --self-test
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

RULES = Path("openbank-libs/governance/rules.yaml")
SECTION = re.compile(r"^##\s+Security checklist\s*$", re.M | re.I)
NEXT_HEADING = re.compile(r"^##\s+", re.M)
BOX = re.compile(r"^\s*-\s*\[(?P<mark>[ xX])\]\s*(?P<text>.*)$", re.M)


def money_path_dirs(root: Path) -> list[str]:
    """The one list from rules.yaml; a bare `- openbank-foo-service` under
    `money_path_services:` — no YAML dependency for a 20-line block."""
    names: list[str] = []
    in_block = False
    for line in (root / RULES).read_text().splitlines():
        if line.startswith("money_path_services:"):
            in_block = True
            continue
        if in_block:
            m = re.match(r"\s+-\s+([a-z0-9-]+)", line)
            if m:
                names.append(m.group(1))
            elif line and not line.startswith(" ") and not line.startswith("#"):
                break
    if not names:
        print("::error::money_path_services not parseable from rules.yaml — fail closed")
        sys.exit(1)
    return names


def changed_files(base: str) -> list[str]:
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD"],
        capture_output=True, text=True, check=True,
    )
    return [ln for ln in out.stdout.splitlines() if ln]


def unticked(body: str) -> tuple[str, list[str]] | None:
    """None = section absent; otherwise (section, [unticked box texts])."""
    m = SECTION.search(body)
    if not m:
        return None
    rest = body[m.end():]
    nxt = NEXT_HEADING.search(rest)
    section = rest[: nxt.start()] if nxt else rest
    missing = [b.group("text").strip() for b in BOX.finditer(section) if b.group("mark") == " "]
    return section, missing


def run(root: Path, body: str, base: str, enforce: bool) -> int:
    dirs = money_path_dirs(root)
    files = changed_files(base)
    touched = [f for f in files if any(f == d or f.startswith(d + "/") for d in dirs)]
    if not touched:
        print(f"security-checklist: PR touches no money-path directory ({len(files)} files) — not applicable")
        return 0
    print(f"security-checklist: {len(touched)} money-path file(s) touched "
          f"({', '.join(sorted({t.split('/')[0] for t in touched}))})")

    res = unticked(body)
    if res is None:
        print("::error::PR touches money-path code but the body has no '## Security checklist' "
              "section — restore it from the template and tick every box (or state why in the PR).")
        return 1 if enforce else 0
    _, missing = res
    if missing:
        print(f"::error::PR touches money-path code and the Security checklist has "
              f"{len(missing)} unticked box(es):")
        for t in missing:
            print(f"  - [ ] {t}")
        print("Tick each box once true, or state the exception in the PR body.")
        return 1 if enforce else 0
    print("security-checklist: every Security checklist box ticked")
    return 0


def self_test() -> int:
    bad = 0
    tpl = (Path(__file__).resolve().parents[2] / ".github/PULL_REQUEST_TEMPLATE.md").read_text()
    # The template itself ships unticked boxes — it must be flagged.
    r = unticked(tpl)
    if r is None or not r[1]:
        print("self-test FAIL: pristine template not detected as unticked"); bad += 1
    ticked = re.sub(r"-\s*\[ \]", "- [x]", tpl)
    r2 = unticked(ticked)
    if r2 is None or r2[1]:
        print("self-test FAIL: fully ticked template still flagged"); bad += 1
    if unticked("## Summary\nno checklist here\n") is not None:
        print("self-test FAIL: missing section not detected"); bad += 1
    # A box OUTSIDE the section must not count.
    other = "## Security checklist\n- [x] done\n\n## Compliance impact\n- [ ] GDPR\n"
    r3 = unticked(other)
    if r3 is None or r3[1]:
        print("self-test FAIL: box in the next section leaked into the check"); bad += 1
    print("security-checklist self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--body-file", required=False)
    ap.add_argument("--body", default=None, help="PR body inline (the runner passes $PR_BODY)")
    ap.add_argument("--base", default="origin/main")
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    body = Path(args.body_file).read_text() if args.body_file else args.body
    if body is None:
        ap.error("--body-file or --body is required outside --self-test")
    return run(Path(args.root), body, args.base, args.enforce)


if __name__ == "__main__":
    sys.exit(main())
