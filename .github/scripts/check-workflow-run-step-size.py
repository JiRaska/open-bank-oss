#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""
Fail when a workflow step's `run:` script grows past what GitHub will accept (issue #3082).

WHAT THIS CATCHES, AND WHY NOTHING ELSE DID
GitHub rejects a workflow whose single `run:` script is too large. It does not report a size
error: the workflow becomes UNPARSEABLE, and every push produces a run with ZERO jobs titled
after the file path, with the generic "This run likely failed because of a workflow file
issue". `name:` is not even read.

That is indistinguishable from an ordinary red run, and it takes the whole workflow down — when
it happened to auto-deploy.yml on 2026-08-01 it blocked every contributor's deploy, not just the
author's, until the commit was reverted (#3135 -> #3139).

Nothing in this repo's gate stack sees it:
  * PyYAML parses the file fine, and a strict duplicate-key loader is clean
  * actionlint reports the identical finding set as before the change
  * yamllint is clean
  * the change had been unit-tested by extracting and running the step body
All of those inspect what the file MEANS. The limit is about how BIG one piece of it is.

THE MEASUREMENT (auto-deploy.yml's can-i-deploy step, bisected against GitHub itself by
pushing variants to a throwaway branch and observing whether a run was created at all):

    17414 chars  accepted      (the pre-change baseline)
    19889 chars  accepted
    20054 chars  accepted
    20654 chars  REJECTED      (the change that broke main)

so the true ceiling sits between 20054 and 20654 — most likely 20 KiB (20480). It is NOT a
whole-file limit: a control file padded to 95295 bytes, larger than the rejected one, parsed
fine as long as no single step crossed the line. That control is what makes "per step" a
measurement rather than a guess.

MAX_RUN_CHARS is set below the measured floor, not at it. A gate that trips exactly where the
platform does leaves no room to land a fix, and the number is inferred rather than documented,
so it may move.

Usage:
    check-workflow-run-step-size.py              # gate (exit 1 when a step is too large)
    check-workflow-run-step-size.py --self-test  # prove the gate can fail
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

REPO = Path(__file__).resolve().parents[2]
WORKFLOWS = REPO / ".github" / "workflows"

# Measured ceiling is between 20054 (ok) and 20654 (rejected). Leave real headroom: a step that
# is already at the edge cannot be fixed without first making it smaller, and the fix is usually
# a comment explaining what went wrong. 19000 leaves ~1600 chars above the largest step in the
# tree today (auto-deploy's can-i-deploy, 17414) and ~1000 below the lowest known rejection.
MAX_RUN_CHARS = 19000


def oversized_steps(workflow_dir: Path, limit: int = MAX_RUN_CHARS):
    """-> [(file, job, step, length)] for every `run:` above the limit, largest first."""
    out = []
    for f in sorted(workflow_dir.glob("*.yml")) + sorted(workflow_dir.glob("*.yaml")):
        try:
            doc = yaml.safe_load(f.read_text(encoding="utf-8", errors="ignore"))
        except yaml.YAMLError:
            continue  # a file that does not parse is yamllint's problem, not this gate's
        if not isinstance(doc, dict):
            continue
        for job_name, job in (doc.get("jobs") or {}).items():
            if not isinstance(job, dict):
                continue
            for i, step in enumerate(job.get("steps") or []):
                if not isinstance(step, dict):
                    continue
                script = step.get("run")
                if not isinstance(script, str) or len(script) <= limit:
                    continue
                label = step.get("name") or step.get("id") or f"step #{i + 1}"
                # relative_to only when the file really is under the repo — the self-test
                # drives this function over a temp dir, and an exception there would mean the
                # gate could only ever be exercised by the thing it is meant to guard.
                try:
                    shown = f.relative_to(REPO)
                except ValueError:
                    shown = f
                out.append((shown, job_name, label, len(script)))
    return sorted(out, key=lambda r: -r[3])


def run_gate() -> int:
    findings = oversized_steps(WORKFLOWS)
    largest = 0
    for f in sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml")):
        try:
            doc = yaml.safe_load(f.read_text(encoding="utf-8", errors="ignore")) or {}
        except yaml.YAMLError:
            continue
        if isinstance(doc, dict):
            for job in (doc.get("jobs") or {}).values():
                if isinstance(job, dict):
                    for s in job.get("steps") or []:
                        if isinstance(s, dict) and isinstance(s.get("run"), str):
                            largest = max(largest, len(s["run"]))
    print(f"check-workflow-run-step-size: largest run script is {largest} chars "
          f"(limit {MAX_RUN_CHARS}; GitHub rejects the whole workflow somewhere above 20054)")
    if not findings:
        return 0
    for path, job, step, size in findings:
        print(f"::error file={path}::job '{job}', step '{step}' has a {size}-character `run:` "
              f"script (limit {MAX_RUN_CHARS}). GitHub rejects an oversized run script by making "
              f"the WHOLE workflow unparseable — zero jobs, no error message, every contributor's "
              f"runs of this file dead until it is reverted. Move the logic into "
              f".github/scripts/ and call it; prose belongs in the script's header, not in the "
              f"workflow.")
    return 1


def self_test() -> int:
    """A gate that has only ever passed is unfalsified. Drive both sides."""
    import tempfile

    failures = []
    with tempfile.TemporaryDirectory() as td:
        d = Path(td)
        small = {"jobs": {"j": {"steps": [{"name": "small", "run": "echo hi\n"}]}}}
        (d / "small.yml").write_text(yaml.safe_dump(small))
        got = oversized_steps(d)
        print(f"  {'ok  ' if not got else 'FAIL'} a small step is not flagged")
        if got:
            failures.append("small step flagged")

        big = {"jobs": {"j": {"steps": [{"name": "huge", "run": "x" * (MAX_RUN_CHARS + 1)}]}}}
        (d / "big.yml").write_text(yaml.safe_dump(big))
        got = oversized_steps(d)
        hit = [g for g in got if g[2] == "huge"]
        print(f"  {'ok  ' if hit else 'FAIL'} a step one char over the limit IS flagged")
        if not hit:
            failures.append("oversized step not flagged")

        # Exactly at the limit must pass: the check is "above", and an off-by-one here would
        # fail a workflow that GitHub accepts.
        edge = {"jobs": {"j": {"steps": [{"name": "edge", "run": "x" * MAX_RUN_CHARS}]}}}
        (d / "edge.yml").write_text(yaml.safe_dump(edge))
        got = [g for g in oversized_steps(d) if g[2] == "edge"]
        print(f"  {'ok  ' if not got else 'FAIL'} a step exactly at the limit is not flagged")
        if got:
            failures.append("edge case flagged")

        # A step with no `run:` (uses:) must not blow up the walker.
        uses = {"jobs": {"j": {"steps": [{"uses": "actions/checkout@v4"}]}}}
        (d / "uses.yml").write_text(yaml.safe_dump(uses))
        try:
            oversized_steps(d)
            print("  ok   a `uses:` step is skipped without error")
        except Exception as ex:  # noqa: BLE001 - the point is that nothing escapes
            print(f"  FAIL `uses:` step raised {ex}")
            failures.append("uses step raised")

    if failures:
        print(f"\n::error::self-test failed: {', '.join(failures)}")
        return 1
    print("\nself-test passed: the gate flags an oversized step and clears the others.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="prove the gate can fail")
    args = ap.parse_args()
    return self_test() if args.self_test else run_gate()


if __name__ == "__main__":
    sys.exit(main())
