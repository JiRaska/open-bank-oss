#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Only a push to main may write the remote Gradle build cache (ADR-0363).

WHY THIS EXISTS
    The remote build cache serves every later build. An entry written from pull-request
    code would be served to main. ADR-0363 makes writing a property of `main` alone: the
    write credential is an environment secret of `gradle-cache-writer`, whose branch policy
    admits only main, and the node rejects writes from the read-only account.

    Those two controls live outside this repository (environment policy, node config). This
    gate keeps the workflow side from drifting into a shape that would rely on them alone.

WHAT IT CHECKS, in every .github/workflows/*.yml job:
    1. WRITE-PASSWORD-SCOPE: a job that references `GRADLE_CACHE_WRITE_PASSWORD` must declare
       `environment:` naming `gradle-cache-writer` behind a push-to-main condition.
    2. PUSH-SCOPE: `GRADLE_REMOTE_CACHE_PUSH` is the literal `false`, or an expression that
       requires `github.event_name == 'push'` and `refs/heads/main`.
    3. PASSED-WRITE-SECRET: no caller passes `GRADLE_CACHE_WRITE_PASSWORD` to a reusable
       workflow (`jobs.<id>.secrets`); it must only ever come from the environment.

EXIT CODES
    0 clean, 1 could not answer (no workflows read), 2 findings.
"""
from __future__ import annotations

import argparse
import pathlib
import sys
import tempfile

import yaml

ENV_NAME = "gradle-cache-writer"
WRITE_SECRET = "GRADLE_CACHE_WRITE_PASSWORD"
PUSH_VAR = "GRADLE_REMOTE_CACHE_PUSH"


def main_push_guarded(expr: str) -> bool:
    e = " ".join(str(expr).split())
    return "github.event_name == 'push'" in e and "refs/heads/main" in e


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
                findings.append(f"PUSH-SCOPE {where}: {PUSH_VAR}={push!r} is not gated on a push to main")
            if WRITE_SECRET in (job.get("secrets") or {}):
                findings.append(f"PASSED-WRITE-SECRET {where}: passes {WRITE_SECRET} to a reusable workflow")
    return len(files), findings


def self_test() -> int:
    guarded = "${{ (github.event_name == 'push' && github.ref == 'refs/heads/main') && 'x' || '' }}"
    cases = [
        ("clean guarded job", {"environment": guarded.replace("'x'", f"'{ENV_NAME}'"),
                               "env": {PUSH_VAR: guarded, "P": f"${{{{ secrets.{WRITE_SECRET} }}}}"}}, None),
        ("push always true", {"env": {PUSH_VAR: "true"}}, "PUSH-SCOPE"),
        ("write secret without environment", {"env": {"P": f"${{{{ secrets.{WRITE_SECRET} }}}}"}},
         "WRITE-PASSWORD-SCOPE"),
        ("write secret passed to callee", {"uses": "./x.yml", "secrets": {WRITE_SECRET: "${{ secrets.Y }}"}},
         "PASSED-WRITE-SECRET"),
        ("literal false", {"env": {PUSH_VAR: "false"}}, None),
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
