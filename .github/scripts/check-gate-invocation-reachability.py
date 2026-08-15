#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A gate is only as reachable as the JOB it sits in.

WHY THIS EXISTS
---------------
`check-gate-script-registration.py` answers "is anything wired to this script at all" and says
in its own header that it deliberately does NOT answer "can that invocation run". This is the
other half, for the one workflow where the question is not a judgement call: `ci.yml`.

`ci.yml` carries the required contexts. A gate written as an inline `run:` step there inherits
its host job's conditions instead of the manifest's, and a conditional host silently narrows
the gate to something nobody declared. That is #3629 exactly:
`check-dockerfile-no-build-stage.py` — the gate that owns per-service Dockerfile shape — lived
in `ui-build`, whose `if: needs.changes-ui.outputs.changed == 'true'` fires only for
`openbank-admin-ui/`, `*/governance.yaml` or the governance schema. It could not run on a
Dockerfile-only PR, the exact change it exists to catch. Not skipped-and-reported: the job is
absent, the aggregate check is green, and nothing anywhere says a gate was not consulted.

Found again on 2026-08-09 in the same job: `check-manifest-types-only.sh` (#4339). That one is
reachable by coincidence — the file it guards lives under `openbank-admin-ui/`, so the filter
happens to fire when its subject changes — and "reachable by coincidence" is not a property
anyone can check. Both are now declared in `.github/gates/gates.yaml`, which runs
unconditionally by construction.

THE RULE
--------
No `check-*` script may be invoked from a step in `ci.yml` whose job carries an `if:`, or whose
step carries one. Not "should" — there is nowhere in `ci.yml` a gate belongs that
`gates.yaml` does not cover, and the manifest is the thing this repo can enumerate, shard and
falsify. An unconditional job in `ci.yml` is allowed but pointless, so it is reported as a
notice rather than an error: the fix is the same and the harm is not.

SCOPE, STATED SO IT CANNOT BE MISREAD
-------------------------------------
`ci.yml` only. `_service-ci.yml` and `services-ci.yml` are path-scoped by design — that is what
"path-scoped CI only builds changed services" means — and a rule here would be an argument
against the architecture rather than a defect check. Scheduled watchers (`fleet-attestation`,
`upstream-unblock-watch`) are conditional on purpose too. If those ever need covering it will
need a per-script declaration of the expected lane, which is a different and larger gate.

Usage:  check-gate-invocation-reachability.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

WORKFLOW = ".github/workflows/ci.yml"
CHECK_RE = re.compile(r"(?:^|[\s/])(check-[\w.-]+\.(?:py|sh))")

# A step that CALLS ITSELF a gate, for the gates that are not a `check-*` script. The
# ADR-0071 governance-manifest gate was `node scripts/generate-governance.mjs` inline in
# `ui-build` — no check-* script anywhere in it, so the rule above could not see it, and it
# sat in the conditional job for the whole time this checker was green about that job
# (#4083). A probe that cannot express the failure reports clean; this is the second
# detector, keyed on the one thing such a step always has: a name asserting it is a gate.
GATE_NAME_RE = re.compile(r"\b(gate|enforced|advisory)\b", re.IGNORECASE)

# `always()` does not NARROW anything — it makes a job run in cases it otherwise would not,
# which is the opposite defect from the one this checker is about. `validate` carries it, and
# treating it as a condition would flag a step that is strictly more reachable than an
# unconditional one. Any other expression is narrowing until someone proves otherwise.
NON_NARROWING = {"always()", "${{ always() }}"}


def is_narrowing(cond) -> bool:
    return cond is not None and str(cond).strip() not in NON_NARROWING


def strip_comments(text: str) -> str:
    """Drop whole-line `#` comments.

    A comment that NAMES a gate script is how this repo explains why a gate moved — the very
    note left behind in `ci.yml` by #4339 says `check-manifest-types-only.sh is now declared
    in gates.yaml`. Matching that would make this checker flag the explanation of its own
    finding, the shape that made check-advisory-gate-registration.py flag itself (#2450).
    """
    return "\n".join(l for l in text.split("\n") if not l.lstrip().startswith("#"))


def findings(root: pathlib.Path = pathlib.Path(".")):
    """Return (errors, notices, steps_examined)."""
    path = root / WORKFLOW
    if not path.is_file():
        raise FileNotFoundError(f"{WORKFLOW} not found — refusing to report a pass")
    doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    jobs = doc.get("jobs") or {}
    if not jobs:
        raise ValueError(f"{WORKFLOW} declares no jobs — refusing to report a pass")

    errors, notices, examined = [], [], 0
    for job_name, job in jobs.items():
        job_if = job.get("if")
        for step in job.get("steps") or []:
            run = strip_comments(str(step.get("run") or ""))
            if not run.strip():
                continue
            examined += 1
            label = step.get("name") or run.strip().split("\n")[0][:60]
            scripts = sorted(set(CHECK_RE.findall(run)))
            if not scripts and GATE_NAME_RE.search(step.get("name") or ""):
                if is_narrowing(job_if):
                    errors.append(
                        f"step '{label}' in job '{job_name}' names itself a gate but runs no "
                        f"check-* script, and the job is conditional (if: {job_if}). The gate "
                        f"cannot run when that condition is false, and an absent job reports "
                        f"nothing — declare it in .github/gates/gates.yaml instead (#4083)."
                    )
                elif is_narrowing(step.get("if")):
                    errors.append(
                        f"step '{label}' in job '{job_name}' names itself a gate but runs no "
                        f"check-* script, and carries a step-level condition "
                        f"(if: {step['if']}). Same problem, one level down — declare it in "
                        f".github/gates/gates.yaml instead."
                    )
            for script in scripts:
                if is_narrowing(job_if):
                    errors.append(
                        f"{script} is invoked by step '{label}' in job '{job_name}', which is "
                        f"conditional (if: {job_if}). The gate cannot run when that condition "
                        f"is false, and an absent job reports nothing — declare it in "
                        f".github/gates/gates.yaml instead (#3629)."
                    )
                elif is_narrowing(step.get("if")):
                    errors.append(
                        f"{script} is invoked by step '{label}' in job '{job_name}' under a "
                        f"step-level condition (if: {step['if']}). Same problem, one level "
                        f"down — declare it in .github/gates/gates.yaml instead."
                    )
                else:
                    notices.append(
                        f"{script} runs as an inline step in job '{job_name}'. It is reachable "
                        f"today, but a condition added to that job later would narrow it "
                        f"silently — gates belong in .github/gates/gates.yaml."
                    )
    return errors, notices, examined


def self_test() -> int:
    import tempfile

    fails = []

    def case(label, workflow_yaml, want_errors, want_notices):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / ".github" / "workflows").mkdir(parents=True)
            (root / WORKFLOW).write_text(workflow_yaml)
            errs, notes, _ = findings(root)
        if (len(errs), len(notes)) != (want_errors, want_notices):
            fails.append(
                f"{label}: expected {want_errors} error(s)/{want_notices} notice(s), "
                f"got {len(errs)}/{len(notes)}"
            )

    # The #3629 shape itself.
    case("a gate in a conditional job is an error", """
jobs:
  ui-build:
    if: needs.changes-ui.outputs.changed == 'true'
    steps:
      - name: a gate
        run: bash .github/scripts/check-manifest-types-only.sh
""", 1, 0)

    case("a step-level condition is the same defect", """
jobs:
  validate:
    steps:
      - name: a gate
        if: github.event_name == 'push'
        run: python3 .github/scripts/check-something.py
""", 1, 0)

    # The negative cases. Without these a checker that flagged everything would pass.
    case("an unconditional inline gate is a notice, not an error", """
jobs:
  validate:
    steps:
      - run: bash .github/scripts/check-something.sh
""", 0, 1)

    case("a conditional job with no gate in it is clean", """
jobs:
  ui-build:
    if: needs.changes-ui.outputs.changed == 'true'
    steps:
      - run: npm run build
""", 0, 0)

    # Code-about-code: the note explaining that a gate MOVED must not be a finding.
    case("a comment naming a script does not count as an invocation", """
jobs:
  ui-build:
    if: needs.changes-ui.outputs.changed == 'true'
    steps:
      - name: something else
        run: |
          # check-manifest-types-only.sh is declared in gates.yaml, not here
          npm run build
""", 0, 0)

    # #4083: the governance-manifest gate ran `node scripts/generate-governance.mjs`, so the
    # check-* rule above was structurally blind to it. These four pin the second detector,
    # including both directions of the always() carve-out.
    case("a gate-NAMED step with no check-* script in a conditional job is an error", """
jobs:
  ui-build:
    if: needs.changes-ui.outputs.changed == 'true'
    steps:
      - name: Governance manifest gate (ADR-0071, enforced)
        run: node scripts/generate-governance.mjs
""", 1, 0)

    case("a gate-NAMED step under a step-level condition is an error", """
jobs:
  validate:
    steps:
      - name: some gate
        if: github.event_name == 'push'
        run: node scripts/generate-governance.mjs
""", 1, 0)

    case("always() is not a narrowing condition", """
jobs:
  validate:
    if: always()
    steps:
      - name: Verify no gate shard failed
        run: echo ok
""", 0, 0)

    case("an ordinary step in a conditional job is not a gate", """
jobs:
  ui-build:
    if: needs.changes-ui.outputs.changed == 'true'
    steps:
      - name: Build
        run: npm run build
""", 0, 0)

    # A workflow that cannot be read must never report clean.
    for bad, exc in ((None, FileNotFoundError), ("jobs: {}\n", ValueError)):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            if bad is not None:
                (root / ".github" / "workflows").mkdir(parents=True)
                (root / WORKFLOW).write_text(bad)
            try:
                findings(root)
                fails.append(f"a {exc.__name__} input did not raise — would report a false clean")
            except exc:
                pass

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: gate-invocation-reachability is falsifiable (11 cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="a gate must not inherit a job's conditions")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    try:
        errors, notices, examined = findings(pathlib.Path(args.root))
    except (FileNotFoundError, ValueError) as exc:
        sys.stderr.write(f"::error::{exc}\n")
        return 1

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import gatelib

    gatelib.subjects(examined, "ci.yml run: steps")
    for n in notices:
        print(f"::notice::{n}")
    for e in errors:
        print(f"::{'error' if args.enforce else 'warning'}::{e}", file=sys.stderr)
    print(
        f"gate-invocation-reachability: {examined} run: step(s) in {WORKFLOW}; "
        f"{len(errors)} gate(s) behind a condition, {len(notices)} inline but unconditional."
    )
    return 1 if (errors and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
