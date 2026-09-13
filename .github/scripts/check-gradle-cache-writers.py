#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Every Gradle-cache consumer in CI declares, here, whether it may WRITE the cache.

Background (#3299). The repo's GitHub Actions cache sits permanently over its 10 GB
ceiling — measured 2026-08-02 at 11.7 GB across 92 entries — so GitHub evicts
least-recently-used entries continuously. Nothing goes red: eviction shows up as a job
re-downloading dependencies, never as a failed check.

What actually fills it is the WRITE rate, not stale junk. Every entry in the pool had
been accessed within the preceding ~30 minutes, which means the pool is already nothing
but live entries and a janitor would have nothing to delete. Each cache-writing job-run
stores a fresh `gradle-build-cache-v1` entry (~200 MB) and, whenever the fleet's resolved
dependency set shifts by even one artifact, an entire new `gradle-dependencies-v1` entry
of ~700-850 MB — the entry is content-addressed over the whole `modules-2` tree, so two
jobs resolving almost-the-same set still store it twice. That is how 9 near-identical
dependency entries came to hold 6.1 GB, 60% of the pool.

So the lever is the number of jobs writing, and `setup-gradle`'s own documentation
prescribes the shape ("Select which jobs should write to the cache"): elect ONE writer
per OS+architecture and make every other job a consumer. `gradle-home-cache-strict-match`
defaults to false, so consumers still restore across job boundaries.

WHY A GATE AND NOT A COMMENT
    The budget is only stable while something notices a new entrant. A workflow that adds
    `setup-gradle` with no cache inputs writes by default, silently, and the only symptom
    is somebody else's job getting slower days later. #3131's nightly sweep was written
    without `cache: gradle` deliberately, and nothing in the tree recorded that intent.

    So this is a DRIFT check in both directions, not an allowlist. Every usage is declared
    below with the mode its YAML must produce. A new usage fails as undeclared; a declared
    usage whose YAML changes mode fails as drift; a declared usage that disappears fails as
    stale. None of the three can be resolved by editing this file alone without saying what
    changed.

    It does NOT check `runs-on`. Whether the runner is self-hosted is a property of the
    expression, not of the step, and the ARC lane's NAT-egress reason for disabling the
    cache entirely is documented at its own call site.

Usage:
    check-gradle-cache-writers.py             # warn
    check-gradle-cache-writers.py --enforce   # fail
    check-gradle-cache-writers.py --self-test # prove it can fail
    check-gradle-cache-writers.py --list      # print the derived modes (to update DECLARED)
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import tempfile

import yaml

WORKFLOW_DIR = pathlib.Path(".github/workflows")

SETUP_GRADLE = "gradle/actions/setup-gradle"
SETUP_JAVA = "actions/setup-java"

# ---------------------------------------------------------------------------
# THE DECLARED BUDGET
#
# key   "<workflow file>::<job id>"
# mode  what the step's cache inputs must resolve to. One of:
#         writes-on-main — no explicit input. NOT "always writes": `cache-read-only`
#                          defaults to `${{ github.ref_name != default_branch }}`, so
#                          silence already means "write on main, restore elsewhere".
#         writes-always  — `cache-read-only: false` LITERALLY, which overrides that
#                          default and writes on feature branches too.
#         read-only      — restores, never writes (`cache-read-only: true` literally)
#         disabled       — does not touch the Actions cache at all
#         conditional    — an expression decides; may or may not write
#         setup-java     — actions/setup-java `cache: gradle`
# why   the justification. Required, and required to be non-empty, for every entry —
#       this is the "any new workflow adding entries justifies them" half of #3299.
#
# The invariant this budget encodes: at most ONE `writer`/`conditional` entry per
# OS+architecture on Linux-X64, which today is fleet-lint. Everything else restores.
# ---------------------------------------------------------------------------
DECLARED: dict[str, tuple[str, str]] = {
    "fleet-lint.yml::fleet-lint": (
        "conditional",
        "THE designated Linux-X64 writer. Runs `testClasses detekt ktlintCheck` fleet-wide "
        "on every push to main, so its Gradle home is a near-superset of what every other "
        "fleet job needs. Writes on main only (`cache-read-only` is true off main).",
    ),
    "dependency-submission.yml::submit": (
        "read-only",
        "Demoted from writer in #3299. The only fleet-wide Gradle job nobody waits on: not "
        "a required check, push/schedule-triggered, 4.8-8.5 min against a 30 min timeout. "
        "Restores fleet-lint's home and re-downloads only the runtime-only artifacts "
        "`assemble` needs beyond it.",
    ),
    "evals-copilot-proposal.yml::copilot-proposal-pack": (
        "read-only",
        "Scoped to one service's eval test classes (pure-JVM, no infra); resolves a small subset "
        "of what fleet-lint already restores fleet-wide. Same reasoning as its sibling "
        "evals-fraud-review.yml below.",
    ),
    "evals-fraud-review.yml::fraud-review-pack": (
        "read-only",
        "Scoped to one service's test classes; resolves a small subset of what fleet-lint "
        "already restores fleet-wide, so it never needed a fresh entry of its own. Same "
        "reasoning as dependency-submission.yml's own demotion above.",
    ),
    "security.yml::codeql": (
        "writes-on-main",
        "Left as-is deliberately. Its java-kotlin leg documents a 25-30 min cold penalty and "
        "is the most preemption-exposed job in the fleet, so demoting it would trade a felt "
        "regression for headroom already obtained more cheaply elsewhere. Revisit only with "
        "a measurement showing fleet-lint's entry covers `testClasses` for it.",
    ),
    "auto-deploy.yml::build-push": (
        "writes-always",
        "Runs on ubuntu-24.04-arm — a Linux-ARM64 cache scope with NO other writer, so "
        "demoting it would leave the arm64 lane permanently cold rather than sharing an "
        "entry. Its entries are also the smallest in the pool (~7 MB across 3). NOTE the "
        "mode: its explicit `cache-read-only: false` writes on feature branches too, which "
        "the action's default would not. Left unchanged in #3299 because the arm64 pool is "
        "~0.06% of the total, but it is the first thing to revisit if arm64 entries grow.",
    ),
    "ghcr-publish.yml::publish": (
        "writes-on-main",
        "Same Linux-ARM64 scope and the same reasoning as auto-deploy's build-push.",
    ),
    "_service-ci.yml::build": (
        "read-only",
        "ADR-0250 Phase 2 split the old single self-hosted-or-GitHub-hosted job in two: "
        "`build` is now ALWAYS GitHub-hosted (never self-hosted), so its Gradle-cache "
        "inputs are literal (`cache-disabled: false`, `cache-read-only: true`) rather "
        "than the `runner.environment`-conditioned expressions the pre-split job used — "
        "there is no longer a self-hosted branch here to make this `conditional`. The "
        "self-hosted, cache-disabled leg (ARC NAT egress, ~$200/mo) moved to the new "
        "`contract` job below.",
    ),
    "_service-ci.yml::contract": (
        "disabled",
        "ADR-0250 Phase 2: the self-hosted half of the pre-split `build` job (ARC NAT "
        "egress FinOps, ~$200/mo — ties GitHub Actions cache off entirely and relies on "
        "the in-cluster remote Gradle build cache instead, GRADLE_REMOTE_CACHE_URL via "
        "arc-runners.tf, ADR-0043). Runs only on main push/dispatch to publish provider-"
        "pact verification, never on a PR, so it costs the writer budget nothing either "
        "way — declared for the same reason `build` is: so a change to `cache-disabled` "
        "here shows up.",
    ),
    "api-fuzz.yml::fuzz": ("read-only", "Consumer; restores fleet-lint's home."),
    "api-fuzz-authenticated.yml::fuzz-authenticated": (
        "read-only",
        "Consumer; restores fleet-lint's home.",
    ),
    "swift-boot-it-probe.yml::probe": (
        "disabled",
        "Boot probe; builds one service and needs no cross-run Gradle state.",
    ),
    "onnx-serving-smoke.yml::smoke": (
        "read-only",
        "Consumer; restores fleet-lint's home. Builds one service's quarkusBuild to smoke "
        "the ONNX serving path inside the shipped image (#3354), on a schedule rather than "
        "per-push, so it neither needs nor should store a per-run entry.",
    ),
    "pitest.yml::pitest": (
        "read-only",
        "Demoted from setup-java. Consumer; restores fleet-lint's home. The setup-java "
        "keying argument holds better here than for verification-metadata — this is a "
        "weekly scheduled sweep — but it is still a pure consumer, so reading the entry "
        "fleet-lint already maintains costs the pool nothing and cannot churn it.",
    ),
    "pitest.yml::pitest-authz": (
        "read-only",
        "Consumer; restores fleet-lint's home. Targeted authz mutation lane (ADR-0279 #7) "
        "riding the same weekly schedule and cache posture as the matrix job above — a "
        "pure consumer with no reason to store a per-run entry.",
    ),
    "pact-drift-check.yml::drift-check": (
        "read-only",
        "Demoted from setup-java. Consumer; regenerates consumer pacts and diffs them, and "
        "restores fleet-lint's home to do it. Runs on PRs, so unlike pitest it is exposed "
        "to the per-run churn this budget limits.",
    ),
    "services-ci.yml::verification-metadata": (
        "read-only",
        "Demoted from setup-java. The shared justification — setup-java keys on the build "
        "files, so it re-uses one entry rather than storing a new one per run — is FALSE "
        "here, and measurably so: this job runs only when a module `build.gradle.kts` "
        "changed, i.e. exactly when the key's own input changed, so an exact-key hit is "
        "impossible by construction and it stored ~1.1 GB per PR. Measured 2026-08-06: all "
        "three live `setup-java-Linux-x64-gradle` entries (3.29 GB, 29% of the ceiling) sat "
        "on PR refs, not one shared entry. Its check also passes `--refresh-dependencies` "
        "deliberately, so a warm Gradle home is what it is designed not to lean on.",
    ),
    "perf-gate.yml::perf": (
        "read-only",
        "Consumer; restores fleet-lint's home. Weekly advisory k6 gate (ADR-0243) — "
        "never a writer, so it costs the pool nothing.",
    ),
}


# Modes that can put a new entry into the pool on at least one ref.
WRITE_CAPABLE = ("writes-on-main", "writes-always", "conditional")

# A ratchet, not a law: it equals today's count, so ADDING a write-capable job fails
# until someone raises it on purpose. #3299's whole finding is that the pool is filled
# by the number of writers, and nothing previously counted them.
WRITER_BUDGET = 5


def write_capable(found: dict[str, str]) -> list[str]:
    return sorted(k for k, m in found.items() if m in WRITE_CAPABLE)


def _is_true(value: object) -> bool:
    """A LITERAL yes. A ${{ }} expression is never treated as a decided value."""
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() == "true"


def _is_expression(value: object) -> bool:
    return "${{" in str(value)


def classify(step: dict) -> str | None:
    """Derive the cache mode a single workflow step resolves to, or None if it has none."""
    uses = str(step.get("uses", ""))
    with_ = step.get("with") or {}
    if not isinstance(with_, dict):
        with_ = {}

    if uses.startswith(SETUP_JAVA):
        cache = with_.get("cache")
        return "setup-java" if str(cache).strip() == "gradle" else None

    if not uses.startswith(SETUP_GRADLE):
        return None

    disabled = with_.get("cache-disabled")
    read_only = with_.get("cache-read-only")

    # A decided "never touches the cache" beats everything else.
    if disabled is not None and _is_true(disabled):
        return "disabled"
    if read_only is not None and _is_true(read_only):
        return "read-only"
    # Anything expression-shaped resolves per ref or per runner. Do not guess which —
    # `_service-ci` is never a writer only because TWO complementary expressions cover
    # each other, which no per-step rule can see.
    if _is_expression(disabled) or _is_expression(read_only):
        return "conditional"
    # A LITERAL false overrides the action's safe default and writes on every ref,
    # feature branches included. That is strictly worse than saying nothing.
    if read_only is not None and str(read_only).strip().lower() == "false":
        return "writes-always"
    # Nothing said. `cache-read-only` DEFAULTS to
    # `${{ github.ref_name != default_branch }}`, so silence means "writes on main".
    return "writes-on-main"


def scan(workflow_dir: pathlib.Path) -> dict[str, str]:
    """Map "<file>::<job>" -> derived mode for every Gradle-cache usage found."""
    found: dict[str, str] = {}
    for path in sorted(workflow_dir.glob("*.yml")) + sorted(workflow_dir.glob("*.yaml")):
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError:
            continue
        if not isinstance(doc, dict):
            continue
        for job_id, job in (doc.get("jobs") or {}).items():
            if not isinstance(job, dict):
                continue
            for step in job.get("steps") or []:
                if not isinstance(step, dict):
                    continue
                mode = classify(step)
                if mode is None:
                    continue
                key = f"{path.name}::{job_id}"
                # setup-gradle is the meaningful signal when a job has both.
                if key not in found or found[key] == "setup-java":
                    found[key] = mode
    return found


def evaluate(found: dict[str, str], declared: dict[str, tuple[str, str]]) -> list[str]:
    """Return one problem line per disagreement between YAML and the declared budget."""
    problems: list[str] = []

    for key, mode in sorted(found.items()):
        if key not in declared:
            problems.append(
                f"UNDECLARED  {key} uses the Gradle cache (mode `{mode}`) but is not in "
                f"DECLARED. Add it with a reason — a new writer costs every other job "
                f"cache headroom (#3299)."
            )
            continue
        want, why = declared[key]
        if not why.strip():
            problems.append(f"NO-REASON   {key} is declared with an empty justification.")
        if mode != want:
            problems.append(
                f"DRIFT       {key} is declared `{want}` but its YAML now resolves to "
                f"`{mode}`."
            )

    for key in sorted(declared):
        if key not in found:
            problems.append(
                f"STALE       {key} is declared but no longer uses the Gradle cache. "
                f"Remove the declaration."
            )

    # The point of the budget: one writer per cache scope. Anything that can write on
    # Linux-X64 beyond the elected one is what put the pool over its ceiling.
    writers = write_capable(found)
    if len(writers) > WRITER_BUDGET:
        problems.append(
            f"BUDGET      {len(writers)} jobs can write the Gradle cache "
            f"({', '.join(writers)}), over the budget of {WRITER_BUDGET}. Raising the "
            f"budget is a deliberate act — say why in the PR (#3299)."
        )
    return problems


# ---------------------------------------------------------------------------
# Self-test: prove the RED is reachable, and prove the green is not vacuous.
# ---------------------------------------------------------------------------
_STEP = "      - uses: {uses}\n        with:\n{inputs}"


def _wf(uses: str, inputs: str) -> str:
    body = "".join(f"          {line}\n" for line in inputs.splitlines() if line)
    return "on: push\njobs:\n  j:\n    steps:\n" + _STEP.format(uses=uses, inputs=body)


def self_test() -> int:
    cases = [
        # (name, workflow yaml, declared budget, must_flag)
        (
            "undeclared writer (no cache inputs at all) is flagged",
            _wf(SETUP_GRADLE + "@v6", "dependency-graph: generate"),
            {},
            True,
        ),
        (
            "declared read-only is NOT flagged",
            _wf(SETUP_GRADLE + "@v6", "cache-read-only: true"),
            {"w.yml::j": ("read-only", "consumer")},
            False,
        ),
        (
            "declared read-only that silently became a writer is flagged",
            _wf(SETUP_GRADLE + "@v6", "dependency-graph: generate"),
            {"w.yml::j": ("read-only", "consumer")},
            True,
        ),
        (
            "an expression is `conditional`, never mistaken for read-only",
            _wf(SETUP_GRADLE + "@v6", "cache-read-only: ${{ github.ref != 'refs/heads/main' }}"),
            {"w.yml::j": ("read-only", "consumer")},
            True,
        ),
        (
            "a declaration with no reason is flagged",
            _wf(SETUP_GRADLE + "@v6", "cache-read-only: true"),
            {"w.yml::j": ("read-only", "   ")},
            True,
        ),
        (
            "a stale declaration (usage removed) is flagged",
            "on: push\njobs:\n  j:\n    steps:\n      - run: echo hi\n",
            {"w.yml::j": ("read-only", "consumer")},
            True,
        ),
        (
            "an explicit `cache-read-only: false` is `writes-always`, not `writes-on-main` "
            "(it overrides the action's write-on-main-only default)",
            _wf(SETUP_GRADLE + "@v6", "cache-read-only: false"),
            {"w.yml::j": ("writes-on-main", "writer")},
            True,
        ),
        (
            "silence is `writes-on-main`, not `writes-always`",
            _wf(SETUP_GRADLE + "@v6", "dependency-graph: generate"),
            {"w.yml::j": ("writes-always", "writer")},
            True,
        ),
        (
            "setup-java without `cache: gradle` is not a Gradle-cache usage",
            _wf(SETUP_JAVA + "@v5", "distribution: temurin"),
            {},
            False,
        ),
        (
            "setup-java WITH `cache: gradle` must be declared",
            _wf(SETUP_JAVA + "@v5", "cache: gradle"),
            {},
            True,
        ),
    ]

    failures = 0
    for name, wf_text, declared, must_flag in cases:
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            (d / "w.yml").write_text(wf_text, encoding="utf-8")
            problems = evaluate(scan(d), declared)
        flagged = bool(problems)
        ok = flagged == must_flag
        failures += 0 if ok else 1
        verdict = "ok" if ok else "WRONG"
        want = "flag" if must_flag else "pass"
        print(f"  [{verdict:5s}] must {want:4s}: {name}")
        if not ok:
            print(f"           got: {problems or 'no findings'}")

    if failures:
        print(
            f"\nself-test: {failures} case(s) wrong — the check does not measure what it claims."
        )
        return 1
    print("\nself-test: OK — flags a new writer, drift, a stale entry and a missing reason; "
          "leaves a correctly declared consumer alone.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    found = scan(WORKFLOW_DIR)

    if args.list:
        for key, mode in sorted(found.items()):
            print(f"{mode:12s} {key}")
        return 0

    problems = evaluate(found, DECLARED)
    if not problems:
        writers = write_capable(found)
        print(
            f"Gradle cache budget OK — {len(found)} declared usages, "
            f"{len(writers)}/{WRITER_BUDGET} write-capable: {', '.join(writers)}"
        )
        return 0

    for problem in problems:
        level = "error" if args.enforce else "warning"
        print(f"::{level}::{problem}")
    print(f"\n{len(problems)} problem(s). See #3299 and this script's header.")
    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
