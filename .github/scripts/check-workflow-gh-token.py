#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every workflow step that INVOKES the gh CLI must have a token in scope (gate workflow-gh-token-declared).

WHY THIS EXISTS
---------------
`gh` without `GH_TOKEN`/`GITHUB_TOKEN` in the environment does not degrade — it exits non-zero
with `gh: To use GitHub CLI in a GitHub Actions workflow, set the GH_TOKEN environment variable`,
and under `set -e` that ends the step. Measured 2026-09-13: 31 steps across the workflows invoke
gh; exactly one had no token — admin-ui-deploy.yml's "Reject stale or self-generated deploy
source", where `gh pr list` sits inside an `if` branch taken only when a deploy branch for the
SHA already exists. That is the RECOVERY path (re-admit a stuck build), so the path that existed
to recover was the one that could not run, and no green run ever exercised it. Point-fixed in
the same PR; this gate is the systemic half, with an empty baseline.

WHAT COUNTS AS AN INVOCATION
----------------------------
Only `gh <subcommand>` in COMMAND POSITION — start of a line, or after `;`, `&&`, `||`, `|`,
`(`, `$(`, `then`, `do`, `else`. A mention inside a string is not a call: the first scan of this
tree produced a false positive on `gh workflow run auto-deploy.yml -f services=<svc>` inside an
`echo`ed remediation message for a human (auto-deploy.yml). A gate that cries wolf about correct
code is worth less than nothing (`\\bpact\\b` vs `compact`, the entity-column-names lesson), so
the match is positional and comment lines are stripped first. The known-negative for exactly that
echoed remediation is in --self-test.

A token is "in scope" when `GH_TOKEN` or `GITHUB_TOKEN` is set in the step's `env:`, the job's,
or the workflow's. `gh` also honours `GH_ENTERPRISE_TOKEN`, not used here; add it if it ever is.
"""

from __future__ import annotations

import argparse
import glob
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402

TOKEN_KEYS = {"GH_TOKEN", "GITHUB_TOKEN"}
SUBCOMMANDS = (
    "pr|issue|api|run|workflow|release|label|repo|search|variable|secret|cache|auth|gist|"
    "project|ruleset|codespace|extension|status|browse"
)
CMD = re.compile(
    r"(?:^|[;&|(!]|\$\(|\b(?:then|do|else|if|while|until|elif)\b)\s*(?:env\s+(?:\S+=\S*\s+)+)?gh\s+(?:" + SUBCOMMANDS + r")\b",
    re.M,
)


def _code_only(run: str) -> str:
    return "\n".join(ln for ln in run.splitlines() if not ln.lstrip().startswith("#"))


def invokes_gh(run: str) -> bool:
    return bool(CMD.search(_code_only(run or "")))


def scan_workflow(doc: dict, path: str) -> tuple[int, list[str]]:
    """Returns (steps invoking gh, problems)."""
    n = 0
    problems: list[str] = []
    wenv = doc.get("env") or {}
    for jn, job in (doc.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        jenv = job.get("env") or {}
        for i, step in enumerate(job.get("steps") or []):
            if not isinstance(step, dict):
                continue
            run = step.get("run")
            if not isinstance(run, str) or not invokes_gh(run):
                continue
            n += 1
            env = {**(wenv if isinstance(wenv, dict) else {}),
                   **(jenv if isinstance(jenv, dict) else {}),
                   **((step.get("env") or {}) if isinstance(step.get("env"), dict) else {})}
            if not (TOKEN_KEYS & set(env)):
                label = step.get("name") or step.get("id") or f"step[{i}]"
                problems.append(
                    f"{path}: job `{jn}` step `{label}` invokes gh with no GH_TOKEN/GITHUB_TOKEN "
                    f"in step, job or workflow env — under `set -e` the step dies on the first call"
                )
    return n, problems


def scan(root: Path) -> tuple[int, list[str]]:
    total = 0
    problems: list[str] = []
    for f in sorted(glob.glob(str(root / ".github/workflows/*.yml"))):
        doc = gatelib.load_yaml(f)
        if not isinstance(doc, dict):
            continue
        n, p = scan_workflow(doc, str(Path(f).relative_to(root)))
        total += n
        problems += p
    return total, problems


def self_test() -> int:
    failures: list[str] = []

    def check(label: str, cond: bool) -> None:
        print(("  ok   " if cond else "  FAIL ") + label)
        if not cond:
            failures.append(label)

    def wf(run: str, env: dict | None = None, jenv: dict | None = None, wenv: dict | None = None) -> dict:
        step = {"name": "s", "run": run}
        if env is not None:
            step["env"] = env
        job = {"steps": [step]}
        if jenv is not None:
            job["env"] = jenv
        doc = {"jobs": {"j": job}}
        if wenv is not None:
            doc["env"] = wenv
        return doc

    tok = {"GH_TOKEN": "${{ github.token }}"}
    # Known-positive: the live defect's shape.
    live = 'if git ls-remote --exit-code --heads origin x >/dev/null; then\n  open="$(gh pr list --repo "$R" --state open --json number --jq \'length > 0\')"\nfi\n'
    n, p = scan_workflow(wf(live), "w.yml")
    check("known-positive: `$(gh pr list …)` inside an if-branch with no token is red", n == 1 and len(p) == 1)
    check("known-positive: a step-level token clears it", scan_workflow(wf(live, env=tok), "w.yml")[1] == [])
    check("known-positive: a job-level token clears it", scan_workflow(wf(live, jenv=tok), "w.yml")[1] == [])
    check("known-positive: a workflow-level token clears it", scan_workflow(wf(live, wenv=tok), "w.yml")[1] == [])
    check("GITHUB_TOKEN is accepted too",
          scan_workflow(wf(live, env={"GITHUB_TOKEN": "x"}), "w.yml")[1] == [])
    # Known-negatives: mentions that are not invocations.
    echoed = 'echo "re-dispatch to recover (\\`gh workflow run auto-deploy.yml -f services=<svc>\\`), or"\n'
    check("known-negative: `gh workflow run` inside an echoed message is not a call",
          scan_workflow(wf(echoed), "w.yml") == (0, []))
    check("known-negative: a comment line is not a call",
          scan_workflow(wf("# gh api repos/x\necho hi\n"), "w.yml") == (0, []))
    check("known-negative: `ghcr.io` / `ghost` do not match",
          scan_workflow(wf("docker pull ghcr.io/x\nghost pr list\n"), "w.yml") == (0, []))
    check("known-negative: a uses: step with no run: is skipped",
          scan_workflow({"jobs": {"j": {"steps": [{"uses": "actions/checkout@x"}]}}}, "w.yml") == (0, []))
    # Positional forms that ARE calls.
    for form in ("gh api x", "foo && gh api x", "foo | gh issue list", "x=$(gh run list)", "if gh pr view 1; then :; fi",
                 "  env GH_PAGER= gh api x"):
        check(f"positional form is a call: {form!r}", invokes_gh(form))

    # The real tree: the baseline is EMPTY. A new tokenless call is red immediately.
    total, problems = scan(Path("."))
    check(f"real tree: {total} steps invoke gh, all with a token in scope", total >= 20 and problems == [])
    for p in problems:
        print("         " + p)

    if failures:
        print(f"self-test: {len(failures)} case(s) FAILED")
        return 1
    print("self-test: all cases behaved as required")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--root", default=".")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    total, problems = scan(Path(a.root))
    gatelib.subjects(total, "workflow steps invoking the gh CLI")
    for p in problems:
        print("::error::" + p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
