#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Which main-push lanes may coalesce a burst of commits, and which must stay per commit.

Several commits routinely land on main within minutes (a verified batch, bot pin bumps). Each
push starts every unfiltered workflow, and hosted concurrency is a fixed, shared budget, so a
burst of N commits used to hold N slots for the same fleet-wide job. Measured 2026-11-11 over 30
commits in 10 batches: the fleet lint and the java-kotlin CodeQL leg were ~25 and ~21 hosted
minutes per commit, and neither result is consumed per commit.

A lane with a FIXED group and `cancel-in-progress: false` keeps one running and one pending run:
a newer push replaces only the pending one. That costs two runs per burst instead of N, and it is
not the shape issue #3390 removed (a ref-keyed group WITH cancel-in-progress, where every new
commit killed the running run and almost none finished).

It is only safe where no consumer needs that workflow's result for an exact commit. So this
check holds two lists and both directions:

  COALESCED   must NOT key its main-push lane by commit, and must never cancel a started
              main-push run. Losing either silently restores the N-fold cost, or #3390.
  PER_COMMIT  must keep `github.sha` in its main-push group. Each entry names the consumer
              that reads an exact-commit result; coalescing one of these would leave commits
              with no verdict, no image, no dependency graph or no published contract.

Usage:
  check-main-push-concurrency.py [--root DIR]
  check-main-push-concurrency.py --self-test
"""

from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

import yaml

WORKFLOWS = Path(".github/workflows")
MAIN_PUSH_GUARD = "!(github.event_name == 'push' && github.ref == 'refs/heads/main')"
# Expressions that are false for every push event, whatever the ref.
PUSH_SAFE = (MAIN_PUSH_GUARD, "github.event_name != 'push'", "github.event_name == 'pull_request'")

# workflow file -> job id (None = the workflow-level group) -> why it may coalesce.
COALESCED: dict[str, dict[str | None, str]] = {
    "fleet-lint.yml": {
        None: "fleet-wide compile/lint verdict; not required, not a deploy input, no per-commit reader",
    },
    "security.yml": {
        "codeql": "code-scanning upload for the branch; the newest analysis supersedes older ones",
    },
}

# workflow file -> the consumer that needs a result for the exact commit.
PER_COMMIT: dict[str, str] = {
    "ci.yml": "required contexts on main (walked per commit for main's own verdict) and the admin-ui deploy source",
    "services-ci.yml": "required `all-green`; deploys build from and verify an exact-commit successful run",
    "security.yml": "Trivy is a per-commit gate input read from each main commit's check runs",
    "secret-scan.yml": "required `Gitleaks`; a leaked secret must be attributed to its commit",
    "opa-policy.yml": "required `OPA policy gate`",
    "dependency-submission.yml": "dependency review needs the submitted graph of a PR's exact merge-base",
    "pact-drift-check.yml": "publishes contract versions keyed by the commit sha",
    "api-contract-post-merge.yml": "classifies the spec change of the exact merged commit",
}


def _concurrency(doc: dict, job: str | None):
    if job is None:
        return doc.get("concurrency")
    jobs = doc.get("jobs")
    spec = jobs.get(job) if isinstance(jobs, dict) else None
    return spec.get("concurrency") if isinstance(spec, dict) else None


def _never_cancels_main_push(value, accepted: tuple[str, ...] = (MAIN_PUSH_GUARD,)) -> bool:
    """True when the expression is false for every push to main. Decided against a closed list of
    known expressions rather than evaluated: an unrecognised expression is refused, not guessed."""
    if value is False or value is None:
        return True
    if not isinstance(value, str):
        return False
    body = value.replace('"', "'").strip()
    if body.startswith("${{") and body.endswith("}}"):
        body = body[3:-2].strip()
    return body in accepted


def check(root: Path) -> tuple[list[str], int]:
    errors: list[str] = []
    subjects = 0
    docs: dict[str, dict] = {}
    for name in sorted(set(COALESCED) | set(PER_COMMIT)):
        path = root / WORKFLOWS / name
        if not path.is_file():
            errors.append(f"{name}: declared here but the workflow does not exist — remove the entry or restore the file")
            continue
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            errors.append(f"{name}: unparseable ({exc})")
            continue
        if not isinstance(doc, dict):
            errors.append(f"{name}: not a mapping")
            continue
        docs[name] = doc

    for name, lanes in COALESCED.items():
        doc = docs.get(name)
        if doc is None:
            continue
        for job, _why in lanes.items():
            subjects += 1
            where = f"{name}{'::' + job if job else ''}"
            conc = _concurrency(doc, job)
            if not isinstance(conc, dict) or not isinstance(conc.get("group"), str):
                errors.append(f"{where}: no concurrency group — every main push starts its own run again (N runs per burst)")
                continue
            group = conc["group"]
            if "github.sha" in group:
                errors.append(f"{where}: main-push lane is keyed by github.sha, so a burst of N commits costs N runs again")
            if "'main-push'" not in group:
                errors.append(f"{where}: group has no fixed 'main-push' lane for pushes to main")
            strategy = doc["jobs"][job].get("strategy") if job is not None else None
            if isinstance(strategy, dict) and "matrix" in strategy and "matrix." not in group:
                errors.append(f"{where}: a matrix job's lane must include its matrix key, or legs replace each other")
            if not _never_cancels_main_push(conc.get("cancel-in-progress")):
                errors.append(
                    f"{where}: cancel-in-progress can be true for a main push — a shared lane that cancels the RUNNING "
                    "run is issue #3390 (no main run ever finishes during a burst)"
                )

    for name, why in PER_COMMIT.items():
        doc = docs.get(name)
        if doc is None:
            continue
        subjects += 1
        conc = doc.get("concurrency")
        group = conc.get("group") if isinstance(conc, dict) else None
        if not isinstance(group, str) or "github.sha" not in group:
            errors.append(f"{name}: main-push group must stay keyed by github.sha — consumer: {why}")
        elif not _never_cancels_main_push(conc.get("cancel-in-progress"), PUSH_SAFE):
            errors.append(f"{name}: cancel-in-progress can be true for a main push — consumer: {why}")
    return errors, subjects


# ---------------------------------------------------------------------------------------------
# Self-test: every rule is held to a fixture that must pass and one that must fail.
# ---------------------------------------------------------------------------------------------
_GUARD = "${{ !(github.event_name == 'push' && github.ref == 'refs/heads/main') }}"
_SHA = "x-${{ github.event_name == 'push' && github.ref == 'refs/heads/main' && github.sha || 'ci' }}"
_LANE = "x-${{ github.event_name == 'push' && github.ref == 'refs/heads/main' && 'main-push' || 'ci' }}"
_JOB_LANE = "codeql-${{ matrix.language }}-${{ github.event_name == 'push' && github.ref == 'refs/heads/main' && 'main-push' || github.run_id }}"


def _fixture(root: Path, **override) -> None:
    wf = root / WORKFLOWS
    wf.mkdir(parents=True, exist_ok=True)
    base: dict[str, dict] = {name: {"concurrency": {"group": _SHA, "cancel-in-progress": _GUARD}, "jobs": {}}
                             for name in PER_COMMIT}
    base["fleet-lint.yml"] = {"concurrency": {"group": _LANE, "cancel-in-progress": _GUARD}, "jobs": {}}
    base["security.yml"]["jobs"] = {"codeql": {
        "strategy": {"matrix": {"include": "x"}},
        "concurrency": {"group": _JOB_LANE, "cancel-in-progress": False}}}
    for name, mutate in override.items():
        mutate(base[name.replace("__", ".").replace("_", "-")])
    for name, doc in base.items():
        (wf / name).write_text(yaml.safe_dump(doc), encoding="utf-8")


def self_test() -> int:
    failures: list[str] = []

    def case(label: str, want_error: str | None, **override) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _fixture(root, **override)
            errors, subjects = check(root)
        if want_error is None:
            ok = not errors and subjects == len(PER_COMMIT) + sum(len(v) for v in COALESCED.values())
        else:
            ok = any(want_error in e for e in errors)
        print(f"  {'ok  ' if ok else 'FAIL'} {label}" + ("" if ok else f" -> {errors}"))
        if not ok:
            failures.append(label)

    def conc(doc):
        return doc["concurrency"]

    case("the shipped shape passes", None)
    case("fleet lint keyed by sha again is refused", "keyed by github.sha",
         fleet_lint__yml=lambda d: conc(d).update(group=_SHA))
    case("fleet lint with no fixed main lane is refused", "no fixed 'main-push' lane",
         fleet_lint__yml=lambda d: conc(d).update(group="x-${{ github.ref }}"))
    case("fleet lint cancelling a running main run is refused (#3390)", "issue #3390",
         fleet_lint__yml=lambda d: conc(d).update({"cancel-in-progress": True}))
    case("fleet lint with no concurrency is refused", "no concurrency group",
         fleet_lint__yml=lambda d: d.pop("concurrency"))
    case("codeql leg without a lane is refused", "security.yml::codeql: no concurrency group",
         security__yml=lambda d: d["jobs"]["codeql"].pop("concurrency"))
    case("codeql lane without the matrix key is refused", "must include its matrix key",
         security__yml=lambda d: d["jobs"]["codeql"]["concurrency"].update(group=_LANE))
    case("codeql leg cancelling a running analysis is refused", "issue #3390",
         security__yml=lambda d: d["jobs"]["codeql"]["concurrency"].update({"cancel-in-progress": True}))
    for name in PER_COMMIT:
        key = name.replace(".", "__").replace("-", "_")
        case(f"{name} losing its per-commit lane is refused", f"{name}: main-push group must stay keyed",
             **{key: lambda d: conc(d).update(group=_LANE)})
    case("a per-commit workflow cancelling main runs is refused", "ci.yml: cancel-in-progress can be true",
         ci__yml=lambda d: conc(d).update({"cancel-in-progress": True}))

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        _fixture(root)
        (root / WORKFLOWS / "fleet-lint.yml").unlink()
        errors, _ = check(root)
        ok = any("does not exist" in e for e in errors)
        print(f"  {'ok  ' if ok else 'FAIL'} a declared workflow that is gone is refused, not skipped")
        if not ok:
            failures.append("missing workflow")

    if failures:
        print(f"SELF-TEST FAILED: {len(failures)} case(s)")
        return 1
    print("self-test OK")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    errors, subjects = check(Path(args.root))
    print(f"SUBJECTS={subjects}")
    for e in errors:
        print(f"::error title=main-push concurrency::{e}")
    if errors:
        return 1
    print(f"OK: {sum(len(v) for v in COALESCED.values())} coalesced lane(s), {len(PER_COMMIT)} per-commit workflow(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
