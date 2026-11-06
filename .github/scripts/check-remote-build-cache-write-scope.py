#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Only main's own code may write the remote Gradle build cache (ADR-0363).

WHY THIS EXISTS
    The remote build cache serves every later build. An entry written from pull-request
    code would be served to main. ADR-0363 makes writing a property of `main` alone: the
    write credential is an environment secret of `gradle-cache-writer`, whose branch policy
    admits only main, and the node rejects writes from the read-only account.

    Those two controls live outside this repository (environment policy, node config). This
    gate keeps the workflow side from drifting into a shape that would rely on them alone.

WHAT IT CHECKS, in every .github/workflows/*.yml job:
    1. WRITE-PASSWORD-SCOPE: a job that references `GRADLE_CACHE_WRITE_PASSWORD` must declare
       `environment:` naming `gradle-cache-writer` behind a main-only condition.
    2. PUSH-SCOPE: `GRADLE_REMOTE_CACHE_PUSH` is the literal `false`, or a main-only expression.
    A main-only condition names `refs/heads/main`, compares `github.event_name` only with `==`
    and only against `push` / `workflow_dispatch` (a dispatched full-fleet run is how the cache
    is seeded), and, when it admits `workflow_dispatch`, also requires `inputs.ref == ''` so a
    dispatch rebuilding a historical ref stays read-only.
    5. SCRIPT-SCOPE: `.github/scripts/service-ci-steps/remote-build-cache.sh` switches to the
       write account only under the same three conditions (event, ref, empty H=inputs.ref).
    3. PASSED-WRITE-SECRET: no caller passes `GRADLE_CACHE_WRITE_PASSWORD` to a reusable
       workflow (`jobs.<id>.secrets`); it must only ever come from the environment.
    4. PR-INHERIT: no caller of `_service-ci-pr.yml` (the pull_request lane) uses
       `secrets: inherit`. The main lane inherits so the environment secret resolves
       (actions/runner issue 4453); PR code must keep receiving only its explicit map.

EXIT CODES
    0 clean, 1 could not answer (no workflows read), 2 findings.
"""
from __future__ import annotations

import argparse
import re
import pathlib
import sys
import tempfile

import yaml

ENV_NAME = "gradle-cache-writer"
WRITE_SECRET = "GRADLE_CACHE_WRITE_PASSWORD"
PUSH_VAR = "GRADLE_REMOTE_CACHE_PUSH"


WRITE_EVENTS = {"push", "workflow_dispatch"}
SCRIPT = ".github/scripts/service-ci-steps/remote-build-cache.sh"


def main_push_guarded(expr: str) -> bool:
    e = " ".join(str(expr).split())
    events = set(re.findall(r"github\.event_name\s*==\s*'([^']*)'", e))
    if not events or not events <= WRITE_EVENTS or re.search(r"github\.event_name\s*!=", e):
        return False
    if "refs/heads/main" not in e:
        return False
    return "workflow_dispatch" not in events or "inputs.ref == ''" in e


def script_findings(root: pathlib.Path) -> list[str]:
    f = root / SCRIPT
    if not f.exists():
        return []
    text = f.read_text()
    case = re.search(r'case "\$\{GITHUB_EVENT_NAME:-\}" in ([^)]*)\) e=1 ;; \*\) e= ;; esac', text)
    cond = re.search(r"^if (.*); then\n\s*u=ci-write", text, re.M)
    ok = (case is not None and set(case.group(1).split("|")) <= WRITE_EVENTS and cond is not None
          and '[ -n "$e" ]' in cond.group(1)
          and '[ "${GITHUB_REF:-}" = refs/heads/main ]' in cond.group(1)
          and '[ -z "${H:-}" ]' in cond.group(1))
    return [] if ok else [f"SCRIPT-SCOPE {SCRIPT}: write switch is not limited to push/workflow_dispatch "
                          "on refs/heads/main with an empty inputs.ref"]


def scan(root: pathlib.Path) -> tuple[int, list[str]]:
    files = sorted((root / ".github/workflows").glob("*.yml"))
    findings: list[str] = []
    for f in files:
        doc = yaml.safe_load(f.read_text()) or {}
        for jid, job in (doc.get("jobs") or {}).items():
            if not isinstance(job, dict):
                continue
            where = f"{f.name}::{jid}"
            text = yaml.safe_dump(job)
            env = job.get("environment")
            env_text = yaml.safe_dump(env) if env is not None else ""
            uses_write = WRITE_SECRET in text.replace(f"{WRITE_SECRET}:", "") or f"secrets.{WRITE_SECRET}" in text
            if uses_write and not (ENV_NAME in env_text and main_push_guarded(env_text)):
                findings.append(f"WRITE-PASSWORD-SCOPE {where}: references {WRITE_SECRET} without a "
                                f"push-to-main `environment: {ENV_NAME}`")
            push = (job.get("env") or {}).get(PUSH_VAR)
            if push is not None and str(push).strip().lower() != "false" and not main_push_guarded(str(push)):
                findings.append(f"PUSH-SCOPE {where}: {PUSH_VAR}={push!r} is not gated on main-only events")
            if str(job.get("uses", "")).endswith("_service-ci-pr.yml") and job.get("secrets") == "inherit":
                findings.append(f"PR-INHERIT {where}: pull_request-lane caller uses `secrets: inherit`")
            if isinstance(job.get("secrets"), dict) and WRITE_SECRET in job["secrets"]:
                findings.append(f"PASSED-WRITE-SECRET {where}: passes {WRITE_SECRET} to a reusable workflow")
    findings += script_findings(root)
    return len(files), findings


def self_test() -> int:
    guarded = "${{ (github.event_name == 'push' && github.ref == 'refs/heads/main') && 'x' || '' }}"
    dispatch = ("${{ (github.event_name == 'push' || github.event_name == 'workflow_dispatch') && "
                "github.ref == 'refs/heads/main' && inputs.ref == '' && 'x' || '' }}")
    good_sh = (pathlib.Path(__file__).resolve().parents[2] / SCRIPT).read_text()
    scripts = [
        ("script: real", good_sh, None),
        ("script: any event", good_sh.replace("push|workflow_dispatch) e=1 ;; *) e= ;;", "*) e=1 ;; *) e= ;;"), "SCRIPT-SCOPE"),
        ("script: pull_request", good_sh.replace("push|workflow_dispatch)", "push|pull_request)"), "SCRIPT-SCOPE"),
        ("script: any ref", good_sh.replace(' && [ "${GITHUB_REF:-}" = refs/heads/main ]', ""), "SCRIPT-SCOPE"),
        ("script: historical ref", good_sh.replace(' && [ -z "${H:-}" ]', ""), "SCRIPT-SCOPE"),
    ]
    cases = [
        ("clean guarded job", {"environment": guarded.replace("'x'", f"'{ENV_NAME}'"),
                               "env": {PUSH_VAR: guarded, "P": f"${{{{ secrets.{WRITE_SECRET} }}}}"}}, None),
        ("push always true", {"env": {PUSH_VAR: "true"}}, "PUSH-SCOPE"),
        ("write secret without environment", {"env": {"P": f"${{{{ secrets.{WRITE_SECRET} }}}}"}},
         "WRITE-PASSWORD-SCOPE"),
        ("write secret passed to callee", {"uses": "./x.yml", "secrets": {WRITE_SECRET: "${{ secrets.Y }}"}},
         "PASSED-WRITE-SECRET"),
        ("literal false", {"env": {PUSH_VAR: "false"}}, None),
        ("PR lane inherits secrets", {"uses": "./.github/workflows/_service-ci-pr.yml", "secrets": "inherit"},
         "PR-INHERIT"),
        ("dispatch on main seeds", {"environment": dispatch.replace("'x'", f"'{ENV_NAME}'"),
                                    "env": {PUSH_VAR: dispatch, "P": f"${{{{ secrets.{WRITE_SECRET} }}}}"}}, None),
        ("pull_request writes", {"env": {PUSH_VAR: guarded.replace("'push'", "'pull_request'")}}, "PUSH-SCOPE"),
        ("push or pull_request writes", {"env": {PUSH_VAR: dispatch.replace("'workflow_dispatch'", "'pull_request'")}},
         "PUSH-SCOPE"),
        ("not-PR writes", {"env": {PUSH_VAR: "${{ github.event_name != 'pull_request' && github.ref == 'refs/heads/main' }}"}},
         "PUSH-SCOPE"),
        ("non-main ref writes", {"env": {PUSH_VAR: guarded.replace("refs/heads/main", "refs/heads/dev")}}, "PUSH-SCOPE"),
        ("dispatch with inputs.ref writes", {"env": {PUSH_VAR: dispatch.replace(" && inputs.ref == ''", "")}},
         "PUSH-SCOPE"),
        ("env dispatch with inputs.ref", {"environment": dispatch.replace(" && inputs.ref == ''", "").replace("'x'", f"'{ENV_NAME}'"),
                                          "env": {"P": f"${{{{ secrets.{WRITE_SECRET} }}}}"}}, "WRITE-PASSWORD-SCOPE"),
        ("main lane inherits secrets", {"uses": "./.github/workflows/_service-ci.yml", "secrets": "inherit"}, None),
    ]
    bad = 0
    for name, job, want in cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / ".github/workflows").mkdir(parents=True)
            (root / ".github/workflows/w.yml").write_text(yaml.safe_dump({"on": "push", "jobs": {"j": job}}))
            _, found = scan(root)
            got = found[0].split()[0] if found else None
            ok = got == want
            bad += not ok
            print(f"  {'ok ' if ok else 'BAD'} {name}: want {want}, got {got}")
    for name, body, want in scripts:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / ".github/workflows").mkdir(parents=True)
            (root / SCRIPT).parent.mkdir(parents=True)
            (root / SCRIPT).write_text(body)
            _, found = scan(root)
            got = found[0].split()[0] if found else None
            ok = got == want
            bad += not ok
            print(f"  {'ok ' if ok else 'BAD'} {name}: want {want}, got {got}")
    print("self-test: " + ("every finding class is reachable, controls are clean" if not bad else f"{bad} case(s) wrong"))
    return 1 if bad else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        sys.exit(self_test())
    n, found = scan(pathlib.Path("."))
    if n == 0:
        print("remote-build-cache-write-scope: no workflows read", file=sys.stderr)
        sys.exit(1)
    print(f"SUBJECTS={n}  # workflow files examined")
    for line in found:
        print(f"::error::{line}")
    print(f"remote-build-cache-write-scope: {len(found)} finding(s)")
    sys.exit(2 if found else 0)
