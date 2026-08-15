#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""A Gradle job that can land on a self-hosted runner must set its own GRADLE_USER_HOME.

WHY THIS EXISTS
The self-hosted Mac runners are also developer workstations. A job that leaves
GRADLE_USER_HOME unset resolves it to `~/.gradle` — the human's cache — and then
`gradle/actions/setup-gradle` does two things to it:

  * writes its init scripts into `~/.gradle/init.d`, where they apply to EVERY
    interactive build on that machine; and
  * runs `cache-cleanup: on-success` — the action's DEFAULT — as a post-job step:
    "remove any stale/unused entries from the Gradle User Home". On a shared home
    that deletes entries the CI build did not use, and truncates files a concurrent
    local build is reading.

Measured 2026-08-01 on the openbank-app runner, which had five such jobs: local builds
in THIS repo failed `Dependency verification failed` for artifacts whose cached bytes
matched `verification-metadata.xml` and Maven Central exactly, and `modules-2/metadata-*`
lost its binary caches mid-build. The failure is indistinguishable from cache corruption
and cost two sessions of misdiagnosis, because the damaging job is in a different repo.

`_service-ci.yml` already resolves a per-service home for the self-hosted lanes; this
guard is what keeps the next Gradle job from forgetting.

WHAT COUNTS AS SATISFIED — any of:
  * `GRADLE_USER_HOME` in the workflow-level `env:`
  * `GRADLE_USER_HOME` in the job-level `env:`
  * a step that writes `GRADLE_USER_HOME=... >> $GITHUB_ENV` BEFORE the first step that
    uses Gradle (the `_service-ci.yml` shape — a reusable workflow cannot reference the
    `env` context in a job-level `env:` block, so it must be resolved in a step).

WHAT IS FLAGGED
A job that uses Gradle (`gradle/actions/setup-gradle`, or `gradlew` in a `run:` body) AND
whose `runs-on` is not exclusively GitHub-hosted labels. An expression-valued `runs-on` is
resolved to the string literals inside it, so the `_service-ci.yml` shape
(`... && 'openbank-build' || 'ubuntu-latest'`) is treated as possibly self-hosted.

Shell comments are stripped before matching, so prose mentioning `./gradlew` in a comment
does not make a job "a Gradle job" (#2450, the code-about-code rule).

Usage:
    python3 .github/scripts/check-gradle-user-home-isolation.py
    python3 .github/scripts/check-gradle-user-home-isolation.py --self-test
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("PyYAML is required", file=sys.stderr)
    raise SystemExit(2) from None

WORKFLOWS = Path(".github/workflows")

VAR = "GRADLE_USER_HOME"

# GitHub-hosted runner images. Anything else — a bare custom label, or a label produced by
# an expression — is treated as possibly self-hosted.
HOSTED = re.compile(r"^(ubuntu|macos|windows)-[0-9a-z.\-]+$")

GRADLE_ACTION = "gradle/actions/setup-gradle"
GRADLEW = re.compile(r"(?<![\w./-])\.?/?gradlew\b")
GITHUB_ENV_WRITE = re.compile(rf"{VAR}=.*>>\s*\"?\$?\{{?GITHUB_ENV", re.MULTILINE)


def strip_shell_comments(text: str) -> str:
    """Drop `#` comments so prose about Gradle is not read as a Gradle invocation."""
    out = []
    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("#"):
            continue
        out.append(re.sub(r"(?<!\S)#(?!\{).*$", "", line))
    return "\n".join(out)


def runner_may_be_self_hosted(runs_on) -> bool:
    if runs_on is None:
        return False
    labels: list[str] = []
    if isinstance(runs_on, str):
        if "${{" in runs_on:
            # Resolve an expression to the literals it can evaluate to.
            labels = re.findall(r"'([^']+)'", runs_on) or ["<expression>"]
        else:
            labels = [runs_on]
    elif isinstance(runs_on, list):
        labels = [str(x) for x in runs_on]
    elif isinstance(runs_on, dict):  # `group:` / `labels:` form
        raw = runs_on.get("labels") or runs_on.get("group") or ""
        labels = raw if isinstance(raw, list) else [str(raw)]
    return any(not HOSTED.match(label.strip()) for label in labels if label)


def step_uses_gradle(step: dict) -> bool:
    if GRADLE_ACTION in str(step.get("uses") or ""):
        return True
    return bool(GRADLEW.search(strip_shell_comments(str(step.get("run") or ""))))


def step_sets_home(step: dict) -> bool:
    if VAR in (step.get("env") or {}):
        return True
    return bool(GITHUB_ENV_WRITE.search(strip_shell_comments(str(step.get("run") or ""))))


def check_workflow(path: Path, text: str) -> list[str]:
    try:
        doc = yaml.safe_load(text) or {}
    except yaml.YAMLError as exc:
        return [f"{path}: unparseable YAML: {exc}"]

    workflow_env = doc.get("env") or {}
    findings = []
    for name, job in (doc.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        steps = [s for s in (job.get("steps") or []) if isinstance(s, dict)]
        gradle_at = next((i for i, s in enumerate(steps) if step_uses_gradle(s)), None)
        if gradle_at is None:
            continue
        if not runner_may_be_self_hosted(job.get("runs-on")):
            continue
        if VAR in workflow_env or VAR in (job.get("env") or {}):
            continue
        # A step may resolve the home into $GITHUB_ENV — but only if it runs FIRST.
        if any(step_sets_home(s) for s in steps[:gradle_at]):
            continue
        late = any(step_sets_home(s) for s in steps[gradle_at:])
        why = (
            f"sets {VAR} only AFTER the first Gradle step — setup-gradle has already "
            f"written to the shared home by then"
            if late
            else f"no {VAR}"
        )
        findings.append(f"{path}:{name} — {why}")
    return findings


_MUST_FLAG = {
    "self-hosted gradle job with no GRADLE_USER_HOME": """
jobs:
  build:
    runs-on: [self-hosted, openbank-build]
    steps:
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew build
""",
    "custom label via an expression (the _service-ci shape)": """
jobs:
  build:
    runs-on: ${{ github.ref == 'refs/heads/main' && 'openbank-build' || 'ubuntu-latest' }}
    steps:
      - run: ./gradlew build
""",
    "home exported only after setup-gradle ran": """
jobs:
  gate:
    runs-on: [self-hosted, openbank-build]
    steps:
      - uses: gradle/actions/setup-gradle@v6
      - run: |
          echo "GRADLE_USER_HOME=$HOME/.gradle-ci" >> "$GITHUB_ENV"
          ./gradlew test
""",
}

_MUST_NOT_FLAG = {
    "GitHub-hosted runner needs no isolation": """
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - run: ./gradlew build
""",
    "job-level env (the app-build shape)": """
jobs:
  build:
    runs-on: [self-hosted, openbank-build]
    env:
      GRADLE_USER_HOME: ${{ github.workspace }}/../_gradle-ci
    steps:
      - run: ./gradlew build
""",
    "resolved into $GITHUB_ENV before the first Gradle step (_service-ci shape)": """
jobs:
  build:
    runs-on: ${{ github.ref == 'refs/heads/main' && 'openbank-build' || 'ubuntu-latest' }}
    steps:
      - name: Resolve GRADLE_USER_HOME
        run: echo "GRADLE_USER_HOME=${{ github.workspace }}/../.gradle-svc/svc" >> "$GITHUB_ENV"
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew build
""",
    "workflow-level env": """
env:
  GRADLE_USER_HOME: /tmp/gradle-ci
jobs:
  build:
    runs-on: [self-hosted, openbank-build]
    steps:
      - run: ./gradlew build
""",
    "a comment mentioning gradlew is not a Gradle job": """
jobs:
  notes:
    runs-on: [self-hosted, openbank-build]
    steps:
      - run: |
          # ./gradlew build runs in the sibling job
          echo hello
""",
    "no Gradle at all": """
jobs:
  j:
    runs-on: [self-hosted, openbank-build]
    steps:
      - run: echo hello
""",
}


def self_test() -> int:
    failures = 0
    for label, text in _MUST_FLAG.items():
        if not check_workflow(Path("<test>"), text):
            print(f"SELF-TEST FAIL: should have flagged — {label}")
            failures += 1
    for label, text in _MUST_NOT_FLAG.items():
        found = check_workflow(Path("<test>"), text)
        if found:
            print(f"SELF-TEST FAIL: false positive — {label}\n  {found[0]}")
            failures += 1
    total = len(_MUST_FLAG) + len(_MUST_NOT_FLAG)
    if failures:
        print(f"self-test: {failures} of {total} cases failed")
        return 1
    print(f"self-test: {total}/{total} cases pass "
          f"({len(_MUST_FLAG)} must-flag, {len(_MUST_NOT_FLAG)} must-not-flag)")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    if not WORKFLOWS.is_dir():
        print(f"{WORKFLOWS} not found — run from the repository root", file=sys.stderr)
        return 2

    findings = []
    for path in sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml")):
        findings.extend(check_workflow(path, path.read_text()))

    if findings:
        print("Gradle jobs that can run self-hosted without an isolated GRADLE_USER_HOME:")
        for f in findings:
            print(f"  - {f}")
        print(
            "\nSet a per-job home so CI cannot prune the workstation's ~/.gradle:\n"
            "    env:\n"
            "      GRADLE_USER_HOME: ${{ github.workspace }}/../.gradle-svc/<name>\n"
            "or resolve it into $GITHUB_ENV in a step BEFORE the first Gradle step\n"
            "(see .github/workflows/_service-ci.yml)."
        )
        return 1

    print("OK: every self-hosted Gradle job isolates GRADLE_USER_HOME.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
