#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""A workflow job that calls `gh` without a checkout must be told which repo it means.

WHY THIS EXISTS
`gh` infers the target repository from the git remote of the working directory. A job with
no `actions/checkout` has no working copy, so every subcommand dies with:

    failed to determine base repo: failed to run git: fatal: not a git repository

That is a one-line failure with an enormous blast radius, because the jobs shaped like this
are almost always ESCALATION paths — the code that runs only when something has already gone
wrong. They rarely execute, so they rarely fail, so nobody learns they are broken.

Measured 2026-07-31: four such jobs existed. Three had NEVER executed
(`api-contract-post-merge.yml:raise-issue`, `dependabot-auto-merge.yml:auto-merge`,
`dependency-submission.yml:raise-issue`), and the fourth — `auto-retry-cancelled.yml` — had
executed repeatedly and failed every single time since the day it was written, so the
spot-kill auto-retry from #2330 had never once re-run anything (#2898). The sharpest is the
#1449 alarm itself: the mechanism this repo points at as the answer to "a red push-triggered
workflow is addressed to nobody" could not have raised its issue.

(A fifth, `auto-deploy.yml:deploy-signal`, was flagged by the first draft of this guard and
is NOT broken — it only quotes `gh workflow run` inside a notice body. See strip_noncode.)

WHAT COUNTS AS SATISFIED
Either is fine, and both are in use here:
  * `GH_REPO` in the workflow env, the job env, or the step env  (record-deployment-on-merge)
  * `-R` / `--repo` on every flagged invocation                  (auto-retry-cancelled)

WHAT IS FLAGGED
`gh <subcommand>` for subcommands that resolve a repository: issue, pr, run, release,
workflow, label, repo, api. `gh api` is flagged ONLY when the path uses gh's `{owner}` /
`{repo}` placeholders — `gh api repos/OWNER/NAME/...` with a literal path needs no resolution
and is deliberately not flagged, because that form is common and correct here.

Shell comments are stripped before matching, so a line explaining `gh issue create` does not
trip the guard. That is the code-about-code rule this repo learned the hard way (#2450).

Usage:
    python3 .github/scripts/check-gh-repo-context.py            # check .github/workflows
    python3 .github/scripts/check-gh-repo-context.py --self-test
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

# Subcommands that need gh to know which repo it is talking about.
RESOLVING = ("issue", "pr", "run", "release", "workflow", "label", "repo")
_GH_SUB = re.compile(r"(?<![\w/-])gh\s+(" + "|".join(RESOLVING) + r")\b")
_GH_API_PLACEHOLDER = re.compile(r"(?<![\w/-])gh\s+api\b[^\n]*\{(?:owner|repo)\}")
_HAS_REPO_FLAG = re.compile(r"\s(?:-R|--repo)[\s=]")


_ESCAPED_SPAN = re.compile(r"\\`[^`]*\\`")


def strip_noncode(script: str) -> str:
    """Remove text that mentions gh without running it.

    Two forms, both real and both found while building this guard:

    * whole-line shell comments — only lines whose first non-space character is `#`, so a
      `#` inside a URL or a string is left alone (the code-about-code rule from #2450);
    * markdown code spans written with ESCAPED backticks, `\\`gh workflow run ...\\``. Inside a
      double-quoted shell string an escaped backtick is a literal character, never command
      substitution, so this is unambiguously prose. `auto-deploy.yml`'s `deploy-signal` job
      builds a notice body containing exactly that and has no gh call at all — the first draft
      of this guard flagged it, and the PR it shipped alongside briefly claimed that job was
      broken. Un-escaped backticks are NOT stripped: those ARE command substitution.
    """
    lines = []
    for line in script.split("\n"):
        if line.lstrip().startswith("#"):
            continue
        lines.append(_ESCAPED_SPAN.sub("", line))
    return "\n".join(lines)


def offending_lines(script: str) -> list[str]:
    """Lines invoking a repo-resolving gh subcommand without -R/--repo."""
    out = []
    for line in strip_noncode(script).split("\n"):
        if not (_GH_SUB.search(line) or _GH_API_PLACEHOLDER.search(line)):
            continue
        if _HAS_REPO_FLAG.search(line):
            continue
        out.append(line.strip())
    return out


def check_workflow(path: Path, text: str) -> list[str]:
    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError as exc:
        return [f"{path}: unparseable YAML ({exc.__class__.__name__})"]
    if not isinstance(doc, dict) or not isinstance(doc.get("jobs"), dict):
        return []

    wf_env = doc.get("env") or {}
    findings = []
    for job_name, job in doc["jobs"].items():
        if not isinstance(job, dict):
            continue
        steps = job.get("steps") or []
        if not isinstance(steps, list):
            continue
        if any(
            isinstance(s, dict) and "actions/checkout" in str(s.get("uses", ""))
            for s in steps
        ):
            continue  # has a working copy; gh can infer the repo

        job_env = job.get("env") or {}
        for step in steps:
            if not isinstance(step, dict):
                continue
            script = step.get("run")
            if not isinstance(script, str):
                continue
            bad = offending_lines(script)
            if not bad:
                continue
            step_env = step.get("env") or {}
            if "GH_REPO" in {**wf_env, **job_env, **step_env}:
                continue
            step_name = step.get("name", "<unnamed step>")
            findings.append(
                f"{path}: job '{job_name}', step '{step_name}' calls gh with no checkout, "
                f"no GH_REPO and no -R/--repo:\n      {bad[0]}"
            )
    return findings


# --------------------------------------------------------------------------------------
# Self-test. A guard that has only ever passed is unfalsified, so the negative cases below
# are the point: each is a shape that MUST NOT be flagged, and every one of them is real.
# --------------------------------------------------------------------------------------
_MUST_FLAG = {
    "bare gh issue, no checkout": """
jobs:
  raise-issue:
    steps:
      - name: Open issue
        run: gh issue create --title x --body y
""",
    "gh api with {owner}/{repo} placeholders": """
jobs:
  probe:
    steps:
      - run: gh api repos/{owner}/{repo}/actions/runs
""",
    "GH_REPO on a different job does not help": """
jobs:
  ok:
    env:
      GH_REPO: a/b
    steps:
      - run: gh pr merge 1
  broken:
    steps:
      - run: gh pr merge 2
""",
}

_MUST_NOT_FLAG = {
    "job has a checkout": """
jobs:
  build:
    steps:
      - uses: actions/checkout@v4
      - run: gh pr merge 1
""",
    "GH_REPO in job env (record-deployment-on-merge shape)": """
jobs:
  record:
    env:
      GH_REPO: ${{ github.repository }}
    steps:
      - run: gh pr diff 1
""",
    "GH_REPO in step env": """
jobs:
  j:
    steps:
      - env:
          GH_REPO: ${{ github.repository }}
        run: gh issue list
""",
    "-R on the call (auto-retry-cancelled shape)": """
jobs:
  retry:
    steps:
      - run: gh run rerun -R "${GITHUB_REPOSITORY}" "${RUN_ID}" --failed
""",
    "gh api with a literal repos/ path needs no resolution": """
jobs:
  j:
    steps:
      - run: gh api repos/JiRaska/open-bank-oss/actions/runs --jq '.total_count'
""",
    "a comment mentioning gh issue create is not code": """
jobs:
  j:
    steps:
      - run: |
          # gh issue create would need a repo here
          echo hello
""",
    "no gh at all": """
jobs:
  j:
    steps:
      - run: echo hello
""",
    "escaped-backtick prose in a heredoc (auto-deploy deploy-signal shape)": r"""
jobs:
  deploy-signal:
    steps:
      - run: |
          printf '%s\n' \
            "re-dispatch to recover (\`gh workflow run auto-deploy.yml -f services=<svc>\`), or" \
            "wait for the reconcile tick"
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
    files = sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml"))
    for path in files:
        findings.extend(check_workflow(path, path.read_text(encoding="utf-8")))

    if findings:
        print("gh invocations with no repo context:\n")
        for f in findings:
            print(f"  - {f}")
        print(
            "\nA job with no actions/checkout cannot infer the repo, so gh exits 1 with\n"
            "'failed to determine base repo'. Set GH_REPO: ${{ github.repository }} on the\n"
            "job, or pass -R to the call. See .github/scripts/check-gh-repo-context.py."
        )
        return 1

    print(f"gh repo context: OK ({len(files)} workflows checked)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
