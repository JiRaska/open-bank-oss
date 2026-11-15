#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every scheduled watch names its outcome (CLEAN/FOUND/SKIPPED/UNREADABLE). Gate `watch-outcome-declared`.

A watch whose only signal is its job conclusion cannot say "I could not read my subject"
apart from "I found a problem": on 2026-11-09..14 three watches were red for days on read
failures alone, and the reds were ignored (see watch_outcome.py). This gate makes the outcome
vocabulary mandatory for every NEW scheduled watch.

Subject: a workflow with a `schedule:` trigger whose file name contains watch / sentinel /
reconcile / deadman. It conforms when the workflow, or a `.github/scripts/*` file it invokes,
uses watch_outcome (import, CLI call, or the literal `title=watch-outcome::` annotation).
BASELINE lists today's non-conforming watches. It is a ratchet, and a baseline entry that has
started conforming (or no longer exists) fails too, so the list can only shrink.
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

NAME = re.compile(r"watch|sentinel|reconcile|deadman")
MARK = re.compile(r"watch_outcome|title=watch-outcome::")
SCRIPT_REF = re.compile(r"\.github/scripts/([\w./-]+\.(?:py|sh|mjs|js))")
BASELINE = {
    "date-driven-expiry-watch.yml",
    "deploy-code-lag-watch.yml",
    "deploy-drift-watch.yml",
    "external-feed-watch.yml",
    "governance-horizon-watch.yml",
    "main-red-watch.yml",
    "merged-past-red-check-watch.yml",
    "pact-verification-reconcile.yml",
    "upstream-unblock-watch.yml",
}


def conforms(root: Path, wf: Path) -> bool:
    text = wf.read_text()
    if MARK.search(text):
        return True
    for ref in SCRIPT_REF.findall(text):
        p = root / ".github/scripts" / ref
        if p.is_file() and MARK.search(p.read_text(errors="replace")):
            return True
    return False


def check(root: Path, baseline: set[str]) -> tuple[int, list[str]]:
    problems, subjects = [], 0
    names = set()  # subjects only: a baselined file that is no longer a scheduled watch is stale
    for wf in sorted((root / ".github/workflows").glob("*.y*ml")):
        if not NAME.search(wf.name) or not re.search(r"^\s+schedule:", wf.read_text(), re.M):
            continue
        subjects += 1
        names.add(wf.name)
        ok = conforms(root, wf)
        if not ok and wf.name not in baseline:
            problems.append(f"{wf.name}: scheduled watch emits no watch_outcome "
                            "(CLEAN/FOUND/SKIPPED/UNREADABLE) — see .github/scripts/watch_outcome.py")
        if ok and wf.name in baseline:
            problems.append(f"{wf.name}: now conforms — remove it from BASELINE in {Path(__file__).name}")
    for gone in sorted(baseline - names):
        problems.append(f"{gone}: in BASELINE but no such scheduled watch — remove the stale entry")
    return subjects, problems


def self_test() -> int:
    bad = []
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        (root / ".github/workflows").mkdir(parents=True)
        (root / ".github/scripts").mkdir(parents=True)
        w = root / ".github/workflows"
        sched = "on:\n  schedule:\n    - cron: '1 * * * *'\n"
        (w / "new-watch.yml").write_text(sched + "run: echo hi\n")
        (w / "ok-watch.yml").write_text(sched + "run: python3 .github/scripts/x.py\n")
        (root / ".github/scripts/x.py").write_text("from watch_outcome import emit\n")
        (w / "build.yml").write_text(sched)
        (w / "push-watch.yml").write_text("on:\n  push:\n")
        _, p = check(root, set())
        if not (len(p) == 1 and p[0].startswith("new-watch.yml")):
            bad.append(f"known-positive: a new silent watch must fail, exactly once: {p}")
        _, p = check(root, {"new-watch.yml"})
        if p:
            bad.append(f"baselined watch must pass: {p}")
        _, p = check(root, {"new-watch.yml", "ok-watch.yml"})
        if not any("now conforms" in x for x in p):
            bad.append("a conforming baseline entry must fail (ratchet)")
        _, p = check(root, {"new-watch.yml", "gone-watch.yml", "push-watch.yml"})
        if not (any("gone-watch.yml" in x for x in p) and any("push-watch.yml" in x for x in p)):
            bad.append("a stale baseline entry must fail")
    n, p = check(Path("."), BASELINE)
    if p or n < 5:
        bad.append(f"real tree: subjects={n} problems={p}")
    for b in bad:
        print("BAD " + b)
    print(f"SUBJECTS={n}")
    print("self-test: " + ("all cases behaved as required" if not bad else "FAILED"))
    return 1 if bad else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    n, problems = check(Path("."), BASELINE)
    print(f"SUBJECTS={n}")
    for p in problems:
        print("::error::" + p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
