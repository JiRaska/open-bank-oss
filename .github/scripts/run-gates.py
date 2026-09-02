#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# The gate runner: executes the gates declared in .github/gates/gates.yaml.
#
# WHY THIS EXISTS
#   The gates used to be 79 sequential steps in ci.yml's `validate` job — one runner, one
#   checkout, run end to end. The job grew 35 -> 97 steps in four weeks and its median wall
#   time 0.7 -> 2.4 min, linearly, on a REQUIRED check that every PR pays. This runner is
#   the second half of the fix (the first is the matrix shard in ci.yml): within a shard it
#   runs the gates CONCURRENTLY, so a shard costs max(gate) rather than sum(gates), and it
#   loads rules.yaml once instead of once per checker process.
#
# WHAT IT GUARANTEES — and the ordering that makes the guarantee real
#   For every gate that declares one, the SELF-TEST runs FIRST, and the gate does not run
#   at all if it did not get the expected verdict. This repo has been burnt three separate
#   times by a gate whose failure path had never once executed (#2165 printed a TypeError
#   instead of a finding, #2154 exited 1 while printing nothing, #2177 reported 455 findings
#   against a true 920), so a green gate is evidence only if its red is reachable.
#
#   Two shapes exist here and they have OPPOSITE exit conventions — conflating them is how
#   this runner's first draft declared all 11 existing self-tests "unfalsified" on a repo
#   where all 11 were fine:
#
#     selftest_expect: pass  (the DEFAULT, and what every checker in this repo uses today)
#       The command is the checker's own `--self-test` harness. It builds a fixture the gate
#       must flag, runs the gate against it, and asserts the gate went red — so the HARNESS
#       exits 0 when the gate is falsifiable. Exit 0 is the good news.
#
#     selftest_expect: fail
#       The command IS the known-positive: it invokes the gate directly against input the
#       gate must reject, so a zero exit means the gate did not reject it. Use this when a
#       checker has no --self-test mode but a one-line broken fixture exists.
#
#   Declare it, don't infer it. There is no signal in the command text that distinguishes
#   the two, and guessing wrong is silent in the safe-looking direction.
#
#   The runner applies the same standard to itself: `--self-test` runs a synthetic manifest
#   whose gates and self-tests are rigged to fail in each distinct way, and asserts the
#   runner reports each one. It is wired into CI as its own gate (see gates.yaml:
#   `gate-runner-self-test`), so this file cannot silently stop failing either.
#
# WHY NOT `continue-on-error` FOR ADVISORY GATES
#   Because 11 of the 12 advisory gates here are advisory INSIDE the script — they print
#   ::warning and exit 0 unless passed --enforce — so `continue-on-error` describes only 1
#   of them and a sweep for it reports the other 11 as enforced (#2392). `mode:` in the
#   manifest is the single declared answer, and check-advisory-gate-registration.py reads
#   it directly rather than inferring from a step name.
#
# Usage:
#   python3 .github/scripts/run-gates.py --group kotlin
#   python3 .github/scripts/run-gates.py --all
#   python3 .github/scripts/run-gates.py --only domain-purity-gate-hexagonal-invariant
#   python3 .github/scripts/run-gates.py --list
#   python3 .github/scripts/run-gates.py --self-test

import argparse
import concurrent.futures
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import time

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

MANIFEST = ".github/gates/gates.yaml"
# Must match gatelib.CACHE_DIR_ENV. Not imported from it: run-gates.py has to load and validate
# the manifest even where a checker's dependencies are missing, and the string is the contract.
PARSE_CACHE_ENV = "GATE_PARSE_CACHE"
SUBJECTS_PREFIX = "SUBJECTS="  # must match gatelib.SUBJECTS_PREFIX
SUBJECTS_UNRESOLVED = "UNRESOLVED"  # must match gatelib.SUBJECTS_UNRESOLVED
VALID_MODES = {"enforced", "advisory"}
VALID_WHEN = {"always", "pull_request"}
VALID_EXPECT = {"pass", "fail"}


# ---------------------------------------------------------------------------
# Manifest
# ---------------------------------------------------------------------------
def unbuffer():
    """Line-buffer stdout/stderr, unconditionally.

    THE ISSUE THIS EXISTS FOR (#6068). Python block-buffers stdout when it is not a TTY,
    and this runner printed nothing until the very end. Measured 2026-08-21 on `--all`:
    the redirect file held **0 bytes for the whole 81-second run** and every byte appeared
    at exit. A `--group` shard finishes in seconds, so its window is invisible — which is
    exactly why all four reported observations were `--all` and none were per-shard.

    That silence is the failure. Anything that samples the output early, or kills the run
    (a harness timeout, a cancelled job, a closed session) and reports the WRAPPER's exit
    code, reads "exit 0, no output" — indistinguishable from "all gates passed". With line
    buffering a killed run leaves its partial verdicts behind, which is the difference
    between an unexplained silence and a truncated log naming the gate it died on.
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(line_buffering=True)
        except (AttributeError, OSError):
            pass  # not a reconfigurable stream (a StringIO under the self-test); harmless


TEXT_GATE_ID = re.compile(r"^  - id:\s*\S", re.M)


def gate_count_by_text(root: pathlib.Path, path: str = MANIFEST) -> int:
    """Count gate entries by TEXT SCAN — deliberately not the YAML parser.

    The cross-count (#6068 suggestion 2, the convention `check_ruler_wiring()` and
    `check-audit-money-path-subscription.py` adopted): a reach figure derived twice by the
    same method is one figure. If PyYAML ever silently drops entries — a duplicate mapping
    key keeps only the LAST, the trap CLAUDE.md documents for application.yaml — the parse
    shrinks and nothing disagrees with it. A regex over the raw bytes cannot make that
    mistake, and a mismatch between the two is a hard failure rather than a smaller run.
    """
    f = root / path
    try:
        return len(TEXT_GATE_ID.findall(f.read_text()))
    except OSError:
        return -1


def strip_comments(body: str) -> str:
    """Drop whole-line shell comments. See the ${{ }} check in load() for why."""
    return "\n".join(l for l in body.split("\n") if not l.lstrip().startswith("#"))

def load(root: pathlib.Path, path: str = MANIFEST):
    f = root / path
    if not f.is_file():
        sys.stderr.write(f"::error::{path} not found — cannot run gates\n")
        sys.exit(2)
    doc = yaml.safe_load(f.read_text()) or {}
    gates = doc.get("gates") or []
    if not gates:
        # An empty manifest must never read as "everything passed". This is the exact
        # failure shape of a gate whose SCOPE is a hand-kept list: short list, green run,
        # no work done (pact-drift-check.yml, issue #2284).
        sys.stderr.write(f"::error::{path} declares no gates — refusing to report success\n")
        sys.exit(2)
    seen = set()
    for g in gates:
        for k in ("id", "name", "run"):
            if k not in g:
                sys.stderr.write(f"::error::gate {g.get('id', '<no id>')} is missing `{k}`\n")
                sys.exit(2)
        # A `run:` that is present but says nothing executes `bash -c ""` and exits 0 — a gate
        # that reports PASS having done no work, which is the exact failure this runner exists
        # to make impossible. Presence was checked; emptiness was not, and one gate in the
        # manifest was shipping that shape deliberately. Comments are stripped first for the
        # same reason as the ${{ }} check below: a run: block that is only prose is equally a
        # no-op, and this is the one place that can tell.
        if not strip_comments(str(g["run"])).strip():
            sys.stderr.write(
                f"::error::gate {g['id']}: `run:` is empty (or only comments). `bash -c \"\"` "
                f"exits 0, so this gate would report PASS without executing anything. Give it "
                f"a command, or delete the gate.\n"
            )
            sys.exit(2)
        if g["id"] in seen:
            sys.stderr.write(f"::error::duplicate gate id `{g['id']}`\n")
            sys.exit(2)
        seen.add(g["id"])
        g.setdefault("mode", "enforced")
        g.setdefault("when", "always")
        g.setdefault("group", "ungrouped")
        if g["mode"] not in VALID_MODES:
            sys.stderr.write(f"::error::gate {g['id']}: mode `{g['mode']}` not in {VALID_MODES}\n")
            sys.exit(2)
        if g["when"] not in VALID_WHEN:
            sys.stderr.write(f"::error::gate {g['id']}: when `{g['when']}` not in {VALID_WHEN}\n")
            sys.exit(2)
        budget = g.get("budget_seconds")
        if budget is not None:
            if isinstance(budget, bool) or not isinstance(budget, (int, float)) or budget <= 0:
                sys.stderr.write(
                    f"::error::gate {g['id']}: budget_seconds `{budget}` must be a positive "
                    f"number\n"
                )
                sys.exit(2)
        # selftest_inputs. The one way this optimisation can HARM: a declared path that
        # matches nothing — a typo, or a file since renamed — makes the gate's self-test skip
        # on every pull request forever, and the skip is silent because "no declared input
        # changed" is the normal, expected message. So every declared path must exist in the
        # tree right now, and the check is here rather than at execution time because a
        # manifest that cannot be trusted must stop the run, not degrade it.
        inputs = g.get("selftest_inputs")
        if inputs is not None:
            if not g.get("selftest"):
                sys.stderr.write(
                    f"::error::gate {g['id']}: declares `selftest_inputs` but has no "
                    f"`selftest` to scope. Remove it, or add the falsification.\n"
                )
                sys.exit(2)
            if not isinstance(inputs, list) or not inputs or not all(
                isinstance(x, str) and x.strip() for x in inputs
            ):
                sys.stderr.write(
                    f"::error::gate {g['id']}: `selftest_inputs` must be a non-empty list of "
                    f"repo-relative paths.\n"
                )
                sys.exit(2)
            for decl in inputs:
                if decl in UNIVERSAL_SELFTEST_INPUTS:
                    sys.stderr.write(
                        f"::error::gate {g['id']}: `selftest_inputs` names `{decl}`, which is "
                        f"already universal — every gate's self-test re-runs when it changes. "
                        f"Listing it hides that fact from the next reader.\n"
                    )
                    sys.exit(2)
                if not (root / decl).exists():
                    sys.stderr.write(
                        f"::error::gate {g['id']}: `selftest_inputs` names `{decl}`, which does "
                        f"not exist. A path that matches nothing skips this gate's self-test on "
                        f"every pull request, silently and forever.\n"
                    )
                    sys.exit(2)

        floor = g.get("min_subjects")
        if floor is not None:
            if not isinstance(floor, int) or isinstance(floor, bool) or floor < 1:
                sys.stderr.write(
                    f"::error::gate {g['id']}: min_subjects `{floor}` must be a positive "
                    f"integer. A floor of 0 states nothing — every gate clears it, including "
                    f"one whose corpus has vanished, which is the case it exists for.\n"
                )
                sys.exit(2)
        exp = g.setdefault("selftest_expect", "pass")
        if exp not in VALID_EXPECT:
            sys.stderr.write(
                f"::error::gate {g['id']}: selftest_expect `{exp}` not in {VALID_EXPECT}\n"
            )
            sys.exit(2)
        if "selftest_expect" in g and not g.get("selftest") and exp != "pass":
            sys.stderr.write(
                f"::error::gate {g['id']}: selftest_expect is set but there is no selftest\n"
            )
            sys.exit(2)
        # A ${{ }} expression is a WORKFLOW construct. Nothing expands it here, so bash gets
        # the literal text and dies with "bad substitution" — noisy in CI but silent to a
        # reader, and the shape is easy to reintroduce by copying a step in from a workflow.
        # Export the value as an env var from the shard job instead.
        #
        # COMMENT LINES ARE STRIPPED FIRST, deliberately. On its first run this check flagged
        # the gate whose comment EXPLAINS why the expression was removed — the same shape
        # that made check-advisory-gate-registration.py flag itself (#2450). Code-about-code
        # has to be out of scope or a guard like this manufactures its own findings; the cost
        # is that a commented-out command carrying the bug is invisible, which is the right
        # trade for a file whose comments are most of its value.
        for key in ("run", "selftest"):
            if "${{" in strip_comments(str(g.get(key) or "")):
                sys.stderr.write(
                    f"::error::gate {g['id']}: `{key}` contains a ${{{{ }}}} workflow expression, "
                    f"which nothing expands outside a workflow. Export it as an env var from the "
                    f"shard job in ci.yml and read $NAME here.\n"
                )
                sys.exit(2)
        # $PR_DIFF_BASE handling. `needs_base` is DERIVED from the command and then checked
        # against the declaration, in both directions, so the two cannot drift.
        #
        #   required  the command reads $PR_DIFF_BASE bare. An empty base would diff against
        #             nothing and pass — a gate green about work it never did — so the runner
        #             fails instead of running it vacuously.
        #   optional  the command reads ${PR_DIFF_BASE:-}, i.e. it supplies its own default
        #             and is written to work without one.
        #   absent    the command does not read it at all.
        #
        # The distinction is not academic. `identifier-intent-guard` is `when: always` and
        # reads ${PR_DIFF_BASE:-}; conflating "reads it" with "requires it" made it fail every
        # push to main, where there is no PR and hence no base — the shard went red on main
        # within minutes of this landing. Hence also the `required` + `when: always`
        # contradiction check below: that combination can only ever fail on a push, so it is
        # rejected at load time rather than discovered on the default branch.
        run = g["run"]
        if "${PR_DIFF_BASE:-" in run:
            derived = "optional"
        elif "PR_DIFF_BASE" in run:
            derived = "required"
        else:
            derived = None
        declared = g.get("needs_base")
        if declared is True:
            declared = "required"
        elif declared is False:
            declared = None
        if declared not in (None, "required", "optional"):
            sys.stderr.write(
                f"::error::gate {g['id']}: needs_base `{g.get('needs_base')}` must be "
                f"`required`, `optional`, or absent\n"
            )
            sys.exit(2)
        if declared != derived:
            sys.stderr.write(
                f"::error::gate {g['id']}: needs_base declares `{declared}` but the command "
                f"is `{derived}` (bare $PR_DIFF_BASE = required, ${{PR_DIFF_BASE:-}} = "
                f"optional, absent = neither)\n"
            )
            sys.exit(2)
        g["needs_base"] = derived
        if derived == "required" and g["when"] != "pull_request":
            sys.stderr.write(
                f"::error::gate {g['id']}: needs_base `required` with when `{g['when']}` can "
                f"only fail on push to main, where there is no PR and no diff base. Either "
                f"set `when: pull_request`, or make the command tolerate an empty base with "
                f"${{PR_DIFF_BASE:-}} and declare `optional`.\n"
            )
            sys.exit(2)
    return gates


# ---------------------------------------------------------------------------
# Execution
# ---------------------------------------------------------------------------
class Result:
    def __init__(self, gate):
        self.gate = gate
        self.status = "pending"  # ok | failed | warned | skipped | unfalsified
        self.output = ""
        self.seconds = 0.0
        # Split out separately (ADR-0255 Gate Tax follow-up, external review 2026-08-10):
        # `seconds` used to be self-test + run: combined, which made "how much does the
        # self-test itself cost" unanswerable from the JSON without re-deriving it from the
        # text log — measured live at 894s of gate CPU in one CI run with no way to say how
        # much of that was falsification overhead vs the check doing its actual job.
        self.selftest_seconds = 0.0
        # True when the self-test was deliberately not run for this pull request because
        # none of the gate's declared inputs changed. Distinct from `selftest_declared`
        # being false: the falsification EXISTS and simply did not need re-proving here.
        self.selftest_skipped = False

    @property
    def id(self):
        return self.gate["id"]


def git_index_path(root: pathlib.Path):
    """Absolute path of this checkout's git index, or None outside a repo.

    Not `.git/index`: in a worktree `.git` is a FILE pointing elsewhere, and this runs in
    worktrees constantly. Ask git.
    """
    try:
        p = subprocess.run(
            ["git", "rev-parse", "--git-path", "index"],
            cwd=root, capture_output=True, text=True, timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if p.returncode != 0:
        return None
    idx = pathlib.Path(p.stdout.strip())
    if not idx.is_absolute():
        idx = root / idx
    return idx if idx.is_file() else None


def _run(cmd: str, cwd: pathlib.Path, extra_env: dict, timeout: int, index: pathlib.Path = None):
    env = dict(os.environ)
    for k, v in (extra_env or {}).items():
        env[k] = str(v)
    # Each gate gets a PRIVATE copy of the git index. Two of them (gen-network-policies,
    # service-runbook-drift) run `git add --intent-to-add` so that a generated file which
    # was never committed shows up as drift rather than staying invisible as untracked
    # (#2064). Serially that was harmless; concurrently they race for .git/index.lock, and
    # the loser fails with an error about locking that has nothing to do with the gate.
    # A private index also stops any gate leaking staged state into another's `git diff` —
    # coupling the old sequential job had and nobody had reason to notice.
    scratch = None
    if index is not None:
        scratch = tempfile.NamedTemporaryFile(prefix="gate-index-", delete=False)
        scratch.close()
        shutil.copyfile(index, scratch.name)
        env["GIT_INDEX_FILE"] = scratch.name
    try:
        p = subprocess.run(
            ["bash", "-euo", "pipefail", "-c", cmd],
            cwd=cwd,
            env=env,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return p.returncode, p.stdout + p.stderr
    except subprocess.TimeoutExpired as exc:
        # Decode each stream INDEPENDENTLY, before concatenating. Even with text=True,
        # TimeoutExpired can carry one stream as str and the other as bytes, so the obvious
        # `(exc.stdout or "") + (exc.stderr or "")` raises `TypeError: can't concat str to
        # bytes` — swallowing the real output and turning a timeout into a stack trace that
        # takes the whole shard down. Reached only on an actual timeout with output on both
        # streams, which is why the `slow` self-test case below now writes to both.
        def _text(stream):
            if not stream:
                return ""
            if isinstance(stream, (bytes, bytearray)):
                return stream.decode("utf-8", "replace")
            return stream

        got = _text(exc.stdout) + _text(exc.stderr)
        return 124, got + f"\n[run-gates] TIMEOUT after {timeout}s\n"
    finally:
        if scratch is not None:
            try:
                os.unlink(scratch.name)
            except OSError:
                pass


def last_subject_count(out: str):
    """The LAST `SUBJECTS=<n>` line in a gate's output, or None.

    Last, not first: a checker that reports per-corpus counts should end with the one that
    decides, and a self-test fixture's count must never be mistaken for the real run's.
    """
    found = None
    for line in out.splitlines():
        line = line.strip()
        if line.startswith(SUBJECTS_PREFIX):
            value = line[len(SUBJECTS_PREFIX):].split("#")[0].strip()
            if value.isdigit():
                found = int(value)
            elif value == SUBJECTS_UNRESOLVED:
                found = SUBJECTS_UNRESOLVED
    return found


# Files whose content can change a self-test's verdict for EVERY gate, so a change to any of
# them re-falsifies the whole estate regardless of what a gate declares. gates.yaml carries the
# selftest command and its expected verdict; run-gates.py decides what a verdict MEANS; gatelib
# is imported by most checkers. Anything else is per-gate and must be declared.
UNIVERSAL_SELFTEST_INPUTS = (
    ".github/gates/gates.yaml",
    ".github/scripts/run-gates.py",
    ".github/scripts/gatelib.py",
)


def changed_paths(root: pathlib.Path, base: str):
    """Repo-relative paths this PR changed against the already-resolved merge-base.

    Returns None when the set cannot be established — no base, not a repo, git failed. None is
    NOT an empty set: an empty set means "this PR changed nothing here" and would let every
    self-test be skipped, so the caller must treat None as "run everything". That distinction is
    the whole safety property of this function (CLAUDE.md: a probe's silence is not evidence).
    """
    if not base:
        return None
    try:
        out = subprocess.run(
            ["git", "diff", "--name-only", base],
            cwd=root, capture_output=True, text=True, timeout=60,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode != 0:
        return None
    return {line.strip() for line in out.stdout.splitlines() if line.strip()}


def selftest_is_needed(gate, changed):
    """(needed, reason). Whether this gate's self-test must run on THIS pull request.

    A self-test proves the gate's red path is reachable. That is a property of the gate's own
    code -- its checker, its fixture, its manifest entry -- and re-running it on a pull request
    that touched none of them re-proves an identical static fact. Measured 2026-09-02 over the
    ci_gate_runs warehouse: 266.1 of 889.1 gate-hours in 23 days were self-test, 193.4 h of it
    on the pull_request lane alone.

    What is NOT weakened, and why this is not a cache:
      * nothing is stored, trusted or replayed -- there is no recorded verdict anywhere, so
        there is nothing to poison or to go stale;
      * every push to main runs every self-test unconditionally (`changed` is None off a pull
        request), so a self-test broken by anything at all is caught on the merge commit that
        introduced it, not a day later;
      * any pull request touching a gate's declared inputs, the manifest, or the runner itself
        falsifies that gate before it is allowed to gate the change;
      * a gate that declares no `selftest_inputs` keeps today's behaviour exactly.
    """
    if not gate.get("selftest"):
        return False, ""
    inputs = gate.get("selftest_inputs")
    if not inputs:
        return True, ""
    if changed is None:
        return True, "the changed-file set could not be established"
    for path in changed:
        if path in UNIVERSAL_SELFTEST_INPUTS:
            return True, f"`{path}` changed (universal self-test input)"
        for decl in inputs:
            if path == decl or path.startswith(decl.rstrip("/") + "/"):
                return True, f"`{path}` changed (declared input `{decl}`)"
    return False, "no declared input changed on this pull request"


def execute(gate, root: pathlib.Path, is_pr: bool, timeout: int, index=None, changed=None) -> Result:
    r = Result(gate)
    if gate["when"] == "pull_request" and not is_pr:
        r.status = "skipped"
        r.output = "skipped: pull_request-only gate, this is not a pull_request event\n"
        return r
    if gate.get("needs_base") == "required" and not os.environ.get("PR_DIFF_BASE"):
        # Fail loudly. Running the gate with an empty base would diff against nothing and
        # pass — a gate green about work it never did. `optional` gates fall through: they
        # supply their own default and are written to work without a base.
        r.status = "failed"
        r.output = "PR_DIFF_BASE is empty but this gate requires it — refusing to run vacuously\n"
        return r

    t0 = time.monotonic()
    buf = []

    selftest = gate.get("selftest")
    needed, why = selftest_is_needed(gate, changed)
    if selftest and not needed:
        # Skipped, and SAID SO in the gate's own output — a falsification that silently did not
        # happen would be indistinguishable from one that passed, which is the failure mode this
        # whole runner exists to prevent.
        r.selftest_skipped = True
        buf.append(
            f"--- self-test SKIPPED on this pull request: {why} ---\n"
            "[run-gates] the gate below still runs. Its self-test runs unconditionally on every\n"
            "push to main, and on any pull request touching its declared `selftest_inputs`,\n"
            "gates.yaml, run-gates.py or gatelib.py.\n"
        )
        selftest = None
    if selftest:
        want_pass = gate.get("selftest_expect", "pass") == "pass"
        st0 = time.monotonic()
        rc, out = _run(selftest, root, gate.get("env"), timeout, index)
        r.selftest_seconds = time.monotonic() - st0
        buf.append(f"--- self-test (must {'PASS' if want_pass else 'FAIL'}) ---\n" + out)
        if (rc == 0) != want_pass:
            r.status = "unfalsified"
            buf.append(
                "\n[run-gates] the self-test "
                + (
                    "FAILED. It drives this gate against a fixture the gate must flag and "
                    "asserts the gate went red, so a failure means the gate no longer "
                    "catches what it was written to catch"
                    if want_pass
                    else "PASSED. It IS a known-positive — input this gate must reject — so "
                    "a zero exit means the gate did not reject it"
                )
                + ". Either way the gate's failure path is not reachable: it is unfalsified "
                "and its green means nothing.\n"
            )
            r.output = "".join(buf)
            r.seconds = time.monotonic() - t0
            return r

    rc, out = _run(gate["run"], root, gate.get("env"), timeout, index)
    buf.append("--- gate ---\n" + out)

    # The subject floor. A gate that examined nothing passes everything, and its output says
    # so out loud — `0 .kt files checked`, `0 @RolesAllowed site(s) checked` — while exiting 0.
    # Measured 2026-08-09 by deleting the corpus: nine kotlin gates and ten gitops gates stayed
    # green with their subject gone (#4339). Where the manifest declares `min_subjects:`, the
    # gate must print how many things it looked at, and clear the floor.
    floor = gate.get("min_subjects")
    if floor is not None and rc == 0:
        found = last_subject_count(out)
        if found == SUBJECTS_UNRESOLVED:
            # THIRD STATE. The gate says it could not read its corpus at all (a rate-limited
            # or unreachable API), so the floor has nothing to hold: 0 subjects here means
            # "not measured", not "corpus collapsed". Enforcing it would turn every transient
            # outage into a red PR — exactly what gatelib.subjects_unresolved exists to stop.
            buf.append(
                "\n[run-gates] this gate reported its corpus UNRESOLVED; min_subjects "
                f"({floor}) not applied to this run. Not a pass and not a failure.\n"
            )
        elif found is None:
            rc = 1
            buf.append(
                f"\n[run-gates] this gate declares min_subjects: {floor} but printed no "
                f"`{SUBJECTS_PREFIX}<n>` line. The floor cannot be checked, so the gate's green "
                f"means nothing — call gatelib.subjects(n) (python) or echo the line (shell).\n"
            )
        elif found < floor:
            rc = 1
            buf.append(
                f"\n[run-gates] this gate examined {found} subject(s), below its declared "
                f"floor of {floor}. Either its corpus moved (a renamed directory, a changed "
                f"glob, a moved source root) and the gate is now a no-op, or the fleet really "
                f"did shrink and the floor in gates.yaml needs a deliberate edit.\n"
            )

    r.seconds = time.monotonic() - t0

    # Wall-time budget. Nothing here ever went red for being slow, and that is how `ci.yml`'s
    # required check went from a 0.7 min median to 2.4 min in four weeks — each gate cheap,
    # the total unbounded, and no single change big enough to argue with.
    #
    # ENFORCED ONLY UNDER CI, deliberately. A developer laptop running eight gates across four
    # cores is not a measurement: this repo's own audit read check-adr-registry.sh at 60.4s
    # locally against a whole shard of 20s in CI. Off the runner the overrun is printed and
    # nothing fails.
    budget = gate.get("budget_seconds")
    if budget is not None and r.seconds > budget:
        on_ci = os.environ.get("CI") == "true"
        note = (
            f"\n[run-gates] this gate took {r.seconds:.1f}s against a budget of {budget}s. "
            f"Budgets are set well above the observed CI time, so this is a regression rather "
            f"than noise — profile it, or raise the number in gates.yaml deliberately.\n"
        )
        buf.append(note)
        if on_ci:
            rc = rc or 1
        else:
            buf.append("[run-gates] not on CI (CI != true), so the overrun does not fail.\n")

    r.output = "".join(buf)
    if rc == 0:
        r.status = "ok"
    elif gate["mode"] == "advisory":
        r.status = "warned"
    else:
        r.status = "failed"
    return r


ICON = {
    "ok": "PASS",
    "warned": "WARN",
    "failed": "FAIL",
    "skipped": "SKIP",
    "unfalsified": "UNFALSIFIED",
}


def report(results, jobs):
    # ZERO GATES IS NOT A PASS (#6068). Without this, report([]) printed "0 gates" and
    # returned 0 — the one line in this file that could answer "everything is fine" about
    # a run that evaluated nothing. Every other vacuity guard here (empty manifest, empty
    # run:, unknown group, missing subject floor) exists to prevent exactly that shape;
    # the function that computes the exit code did not have one.
    if not results:
        sys.stderr.write(
            "::error::run-gates: ZERO gates ran, so there is nothing to report success "
            "about. A run that evaluated nothing is not a pass — exiting 2. If a shard is "
            "genuinely meant to be empty, delete it from the matrix rather than letting it "
            "report green.\n"
        )
        return 2

    # Print each gate's output inside its own collapsible group, in manifest order — the
    # concurrent completion order is not reproducible and makes logs hard to diff.
    failed, warned = [], []
    for r in results:
        icon = ICON[r.status]
        print(f"::group::{icon} [{r.gate['group']}] {r.gate['name']}  ({r.seconds:.1f}s)")
        print(r.output.rstrip() or "(no output)")
        print("::endgroup::")
        if r.status in ("failed", "unfalsified"):
            failed.append(r)
        elif r.status == "warned":
            warned.append(r)

    print()
    print(f"{'=' * 78}")
    counts = {k: sum(1 for r in results if r.status == k) for k in ICON}
    total = sum(r.seconds for r in results)
    wall = max((r.seconds for r in results), default=0.0)
    print(
        f"{len(results)} gates  "
        + "  ".join(f"{ICON[k]}={counts[k]}" for k in ICON if counts[k])
        + f"   cpu={total:.1f}s  slowest={wall:.1f}s  jobs={jobs}"
    )
    # The per-gate roster, printed UNCONDITIONALLY — on the pass path as much as the fail
    # path (#6068). The ::group:: blocks above are collapsed by default in the CI UI and
    # absent entirely from a truncated log, so on a green run the only surviving evidence
    # used to be a single count line. A caller has to be able to see WHICH gates ran, not
    # just how many, without expanding anything.
    print(f"--- verdicts ({len(results)} gates) ---")
    for r in results:
        print(f"  {ICON[r.status]:12s} {r.gate['id']:{max(len(x.gate['id']) for x in results)}s}"
              f"  {r.seconds:6.1f}s  [{r.gate.get('group')}]")

    for r in warned:
        print(f"::warning title={r.gate['id']}::advisory gate failed: {r.gate['name']}")
    for r in failed:
        why = "self-test verdict wrong — gate is unfalsified" if r.status == "unfalsified" else "gate failed"
        print(f"::error title={r.gate['id']}::{why}: {r.gate['name']}")
    return 1 if failed else 0


def json_records(results) -> list[dict]:
    """One record per gate, for ADR-0255's Tier 1 snapshot.

    Deliberately NOT the same shape as the text report: a machine reader needs a stable,
    typed field per property (`subjects` as an int or null, never embedded in prose it would
    have to re-parse), which is the whole reason this exists instead of a collector re-reading
    the printed log — CLAUDE.md documents at length why parsing a job's own log back out is
    fragile (the log contains the step's own `run:` script text; several real false positives
    and negatives came from exactly that). This is the structured alternative: one artifact,
    typed, no parsing risk.
    """
    out = []
    for r in results:
        g = r.gate
        out.append({
            "id": g["id"],
            "group": g.get("group"),
            "mode": g.get("mode", "enforced"),
            "status": r.status,
            "seconds": round(r.seconds, 3),
            # Purely additive: `seconds` keeps meaning "total time this gate cost" (self-test +
            # run:, unchanged), so nothing already reading it — the admin-ui collector, the
            # ClickHouse schema, the Grafana dashboard — has its meaning silently redefined.
            # A consumer that wants "how much was the check itself, minus falsification
            # overhead" computes seconds - selftest_seconds; 0.0 when there is no selftest.
            "selftest_seconds": round(r.selftest_seconds, 3),
            "subjects": last_subject_count(r.output),
            "selftest_declared": bool(g.get("selftest")),
            # A gate whose selftest is declared but came back "unfalsified" did not reach its
            # own run: at all (see execute()) — status alone already encodes this, repeated
            # here as an explicit boolean so a consumer never has to know that convention.
            "selftest_passed": (
                None if not g.get("selftest") or r.selftest_skipped
                else r.status != "unfalsified"
            ),
            # None means "not re-proved on this pull request because no declared input
            # changed", NOT "passed" and NOT "absent". A consumer counting falsifications must
            # keep the three apart or the estate's coverage reads higher than it is.
            "selftest_skipped": r.selftest_skipped,
            "budget_seconds": g.get("budget_seconds"),
            "min_subjects": g.get("min_subjects"),
        })
    return out


def select(gates, args):
    sel = gates
    if args.group:
        sel = [g for g in sel if g.get("group") in args.group]
        known = {g.get("group") for g in gates}
        for want in args.group:
            if want not in known:
                sys.stderr.write(
                    f"::error::no gate declares group `{want}` — the shard would run "
                    f"nothing and report success. Known groups: {sorted(known)}\n"
                )
                sys.exit(2)
    if args.only:
        sel = [g for g in sel if g["id"] in args.only]
        got = {g["id"] for g in sel}
        for want in args.only:
            if want not in got:
                sys.stderr.write(f"::error::no gate with id `{want}`\n")
                sys.exit(2)
    return sel


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=".")
    ap.add_argument("--manifest", default=MANIFEST)
    ap.add_argument("--group", action="append", help="run only this shard (repeatable)")
    ap.add_argument("--only", action="append", help="run only this gate id (repeatable)")
    ap.add_argument("--all", action="store_true", help="run every gate")
    ap.add_argument("--list", action="store_true", help="print the manifest and exit")
    ap.add_argument("--jobs", type=int, default=0, help="concurrency (default: cpu count)")
    ap.add_argument("--timeout", type=int, default=420, help="per-gate seconds")
    ap.add_argument("--json", metavar="PATH", help="write a structured per-gate JSON summary "
                     "(ADR-0255) alongside the normal text report; does not change the exit "
                     "code or the text output")
    ap.add_argument("--self-test", action="store_true", help="falsify the runner itself")
    args = ap.parse_args(argv)
    unbuffer()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    gates = load(root, args.manifest)

    # Cross-count the manifest's reach by a SECOND method before running anything (#6068).
    # A YAML parse that silently lost entries yields a smaller, entirely green run, and no
    # figure derived from that same parse can disagree with it.
    by_text = gate_count_by_text(root, args.manifest)
    if by_text != len(gates):
        sys.stderr.write(
            f"::error::run-gates: gate count disagrees by method — YAML parse says "
            f"{len(gates)}, a text scan of {args.manifest} says {by_text}. One of them is "
            f"losing gates (a duplicate mapping key keeps only the last), so the run would "
            f"cover less than the manifest declares. Refusing to run.\n"
        )
        return 2

    if args.list:
        w = max(len(g["id"]) for g in gates)
        for g in gates:
            st = "selftest" if g.get("selftest") else "--------"
            print(f"{g.get('group','?'):12s} {g['id']:{w}s}  {g['mode']:8s} {g['when']:12s} {st}")
        print(f"\n{len(gates)} gates, {len(set(g.get('group') for g in gates))} groups, "
              f"{sum(1 for g in gates if g.get('selftest'))} with a self-test")
        return 0

    if not (args.group or args.only or args.all):
        ap.error("pick one of --group / --only / --all / --list")

    sel = select(gates, args)
    if not sel:
        sys.stderr.write("::error::selection matched no gates — refusing to report success\n")
        return 2

    is_pr = os.environ.get("GITHUB_EVENT_NAME", "pull_request") == "pull_request"
    jobs = args.jobs or min(8, (os.cpu_count() or 2))
    print(f"[run-gates] {len(sel)} gates, {jobs} concurrent, event="
          f"{os.environ.get('GITHUB_EVENT_NAME', '(local)')}")

    # Resolved ONCE for the whole invocation: 40-odd gates in a shard would otherwise each
    # shell out to git for the identical answer. `None` off a pull request (and whenever the
    # set cannot be established) means every self-test runs — see selftest_is_needed().
    changed = changed_paths(root, os.environ.get("PR_DIFF_BASE", "")) if is_pr else None
    if changed is not None:
        print(f"[run-gates] pull request changed {len(changed)} paths; self-tests whose declared "
              f"inputs are untouched will be skipped")

    index = git_index_path(root)
    # One YAML parse cache for the whole invocation. The gates are separate processes over one
    # corpus — the 20 single-script python gates in the `gitops` shard cost 231s apart and 8.7s
    # with the parse shared, same verdicts — so gatelib.py writes each parsed document here,
    # keyed by content sha, and every later gate reads it back instead of re-decoding. Scoped
    # to this directory and removed below: nothing survives into the next run, so a gate can
    # never read a parse from a tree that has since changed.
    cache = tempfile.mkdtemp(prefix="gate-parse-cache-", dir=os.environ.get("RUNNER_TEMP") or None)
    os.environ[PARSE_CACHE_ENV] = cache
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as ex:
            futs = {ex.submit(execute, g, root, is_pr, args.timeout, index, changed): g["id"] for g in sel}
            done = {}
            # Emit a line PER GATE as it finishes, rather than nothing until the end
            # (#6068). Combined with unbuffer() this is what makes a run that is killed
            # part-way leave evidence of how far it got, instead of an empty file.
            for f in concurrent.futures.as_completed(futs):
                r = f.result()
                done[futs[f]] = r
                print(f"[run-gates] {len(done):3d}/{len(sel)} {ICON[r.status]:12s} "
                      f"{r.gate['id']} ({r.seconds:.1f}s)")
    finally:
        os.environ.pop(PARSE_CACHE_ENV, None)
        shutil.rmtree(cache, ignore_errors=True)

    # Everything that was submitted must come back. A missing id here would silently
    # shrink `results` (and with it every count printed below) rather than fail.
    missing = [g["id"] for g in sel if g["id"] not in done]
    if missing or len(done) != len(sel):
        sys.stderr.write(
            f"::error::run-gates: {len(sel)} gates were submitted but {len(done)} came "
            f"back{' (missing: ' + ', '.join(missing) + ')' if missing else ''}. The run "
            f"is incomplete, so its verdict is not evidence. Refusing to report it.\n"
        )
        return 2
    results = [done[g["id"]] for g in sel]
    if args.json:
        # Written BEFORE report()'s exit code is returned, so a shard that goes on to fail
        # still leaves its JSON behind for the artifact-upload step — an observability
        # snapshot that only appears on a green run would be useless for the one case anyone
        # actually wants to look at it.
        try:
            pathlib.Path(args.json).write_text(json.dumps(json_records(results), indent=2))
        except OSError as exc:
            sys.stderr.write(f"::warning::run-gates: could not write --json {args.json}: {exc}\n")
    return report(results, jobs)


# ---------------------------------------------------------------------------
# The runner's own falsification. Every branch that is supposed to fail the shard is fed
# an input that must trigger it — and every branch that is supposed to PASS is fed one too,
# because a runner that fails everything would satisfy the negative cases alone.
# ---------------------------------------------------------------------------
SELF_TEST_MANIFEST = """
gates:
  - id: passing
    name: "a gate that passes"
    group: t
    run: "true"
  - id: failing
    name: "an enforced gate that fails"
    group: t
    run: "echo boom; exit 1"
  - id: advisory-failing
    name: "an advisory gate that fails"
    group: t
    mode: advisory
    run: "echo meh; exit 1"
  - id: harness-ok
    name: "a gate whose --self-test harness passes (the default convention)"
    group: t
    selftest: "true"
    run: "true"
  - id: harness-broken
    name: "a gate whose --self-test harness fails"
    group: t
    selftest: "exit 1"
    run: "true"
  - id: known-positive-rejected
    name: "a gate that correctly rejects its known-positive"
    group: t
    selftest: "exit 1"
    selftest_expect: fail
    run: "true"
  - id: known-positive-accepted
    name: "a gate that wrongly accepts its known-positive"
    group: t
    selftest: "true"
    selftest_expect: fail
    run: "true"
  - id: pr-only
    name: "a pull_request-only gate"
    group: t
    when: pull_request
    run: "exit 1"
  - id: floor-met
    name: "a gate that clears its subject floor"
    group: t
    min_subjects: 3
    run: "echo SUBJECTS=3"
  - id: floor-missed
    name: "a gate whose corpus vanished"
    group: t
    min_subjects: 3
    run: "echo checked nothing; echo SUBJECTS=0"
  - id: floor-unresolved
    name: "a gate whose corpus could not be READ is not held to its floor"
    group: t
    min_subjects: 3
    run: "echo 'SUBJECTS=UNRESOLVED  # rate limited'"
  - id: floor-unreported
    name: "a gate that declares a floor and never prints a count"
    group: t
    min_subjects: 1
    run: "echo all good"
  - id: floor-last-wins
    name: "the LAST count decides, so a self-test fixture cannot stand in for the real run"
    group: t
    min_subjects: 5
    run: "echo 'SUBJECTS=1  # a self-test fixture'; echo SUBJECTS=9"
  - id: over-budget
    name: "a gate slower than its declared budget"
    group: t
    budget_seconds: 0.2
    run: "sleep 0.5"
  - id: within-budget
    name: "a gate inside its budget"
    group: t
    budget_seconds: 30
    run: "true"
  - id: slow
    name: "a gate that exceeds its timeout"
    group: t
    # Writes to BOTH streams before hanging: a bare `sleep` produces no output, so the
    # timeout path's stream handling was never exercised and a TypeError there went
    # unnoticed until a real gate timed out on a loaded machine.
    run: "echo on-stdout; echo on-stderr >&2; sleep 30"
"""

EXPECTED = {
    "passing": "ok",
    "floor-met": "ok",
    "floor-missed": "failed",
    "floor-unresolved": "ok",
    "floor-unreported": "failed",
    "floor-last-wins": "ok",
    "over-budget": "failed",
    "within-budget": "ok",
    "failing": "failed",
    "advisory-failing": "warned",
    "harness-ok": "ok",
    "harness-broken": "unfalsified",
    "known-positive-rejected": "ok",
    "known-positive-accepted": "unfalsified",
    "pr-only": "skipped",
    "slow": "failed",
}


def self_test():
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="run-gates-selftest-"))
    try:
        (tmp / ".github" / "gates").mkdir(parents=True)
        (tmp / ".github" / "gates" / "gates.yaml").write_text(SELF_TEST_MANIFEST)
        gates = load(tmp)
        os.environ["GITHUB_EVENT_NAME"] = "push"  # so the pull_request-only gate skips
        os.environ["CI"] = "true"  # budgets are enforced on the runner only
        results = [execute(g, tmp, is_pr=False, timeout=2) for g in gates]
        bad = []
        for r in results:
            want = EXPECTED[r.id]
            mark = "ok " if r.status == want else "BAD"
            print(f"  {mark} {r.id:18s} want={want:12s} got={r.status}")
            if r.status != want:
                bad.append(f"{r.id}: want {want}, got {r.status}")
            # A timeout must SURFACE what the gate printed before it hung — that output is
            # usually the only clue to why. Asserting the status alone passes against a
            # timeout path that raises instead of reporting.
            if r.id == "over-budget" and "against a budget of" not in r.output:
                bad.append("over-budget: failed, but not for the budget reason")
            if r.id == "floor-missed" and "below its declared floor" not in r.output:
                bad.append("floor-missed: failed, but not for the floor reason")
            # The third state, both halves: an UNRESOLVED corpus must pass DESPITE the floor,
            # and must say out loud that the floor was not applied — a silent pass here is
            # indistinguishable from a gate that really checked 3 subjects.
            if r.id == "floor-unresolved" and "min_subjects (3) not applied" not in r.output:
                bad.append("floor-unresolved: passed without saying the floor was skipped")
            # ADR-0255's json_records() must round-trip through json.dumps (a Result carrying
            # something non-serialisable would crash the whole run at the very end, after
            # every gate had already finished) and preserve the SUBJECTS= count this record
            # exists to make machine-readable in the first place.
            if r.id == "floor-met":
                rec = json_records([r])[0]
                try:
                    json.dumps(rec)
                except TypeError as exc:
                    bad.append(f"floor-met: json_records() is not JSON-serialisable: {exc}")
                if rec["subjects"] != 3:
                    bad.append(f"floor-met: json_records() subjects want 3, got {rec['subjects']}")
                if rec["selftest_declared"] is not False:
                    bad.append("floor-met: selftest_declared should be False (no selftest: set)")
            if r.id == "harness-ok":
                rec = json_records([r])[0]
                if rec["selftest_declared"] is not True or rec["selftest_passed"] is not True:
                    bad.append(f"harness-ok: want selftest_declared=True/selftest_passed=True, "
                               f"got {rec['selftest_declared']}/{rec['selftest_passed']}")
                # A declared, PASSING selftest still costs real wall time (it ran "true", not
                # nothing) — selftest_seconds must be measured, not left at the zero-value
                # default that would make it indistinguishable from "no selftest at all".
                if rec["selftest_seconds"] <= 0:
                    bad.append(f"harness-ok: selftest_seconds should be > 0 (a selftest ran), "
                               f"got {rec['selftest_seconds']}")
                if rec["seconds"] < rec["selftest_seconds"]:
                    bad.append("harness-ok: total seconds must be >= selftest_seconds "
                               "(selftest runs before run:, never after)")
            if r.id == "harness-broken":
                rec = json_records([r])[0]
                if rec["selftest_passed"] is not False:
                    bad.append(f"harness-broken: want selftest_passed=False, "
                               f"got {rec['selftest_passed']}")
                if rec["selftest_seconds"] <= 0:
                    bad.append("harness-broken: selftest_seconds should be > 0 even when the "
                               "self-test FAILS — the harness still ran and cost real time")
            if r.id == "floor-met":
                # Cross-check the negative: a gate with NO selftest: must report exactly 0.0,
                # never a stale/leaked value from a previous gate's timer in the same process.
                rec0 = json_records([r])[0]
                if rec0["selftest_seconds"] != 0.0:
                    bad.append(f"floor-met: no selftest declared, selftest_seconds should be "
                               f"0.0, got {rec0['selftest_seconds']}")
            if r.id == "floor-unreported" and "printed no" not in r.output:
                bad.append("floor-unreported: failed, but not for the missing-count reason")
            if r.id == "slow":
                for stream in ("on-stdout", "on-stderr"):
                    if stream not in r.output:
                        bad.append(f"slow: timeout output dropped {stream}")
                if "TIMEOUT after" not in r.output:
                    bad.append("slow: timeout not reported in the output")

        # The report() contract too: a failed gate must make the runner exit non-zero, and
        # an advisory-only failure must NOT. Checking the statuses alone would miss a
        # report() that classified correctly and then returned 0 regardless.
        rc_all = report(results, 1)
        if rc_all == 0:
            bad.append("report() returned 0 despite an enforced failure")
        rc_advisory = report([r for r in results if r.id in ("passing", "advisory-failing")], 1)
        if rc_advisory != 0:
            bad.append("report() returned non-zero for an advisory-only failure")

        # An empty manifest must abort, not pass.
        (tmp / ".github" / "gates" / "gates.yaml").write_text("gates: []\n")
        try:
            load(tmp)
            bad.append("load() accepted an empty manifest instead of exiting")
        except SystemExit as exc:
            if exc.code != 2:
                bad.append(f"empty manifest exited {exc.code}, want 2")

        # An empty (or comment-only) run: must abort. `bash -c ""` exits 0, so without this the
        # gate reports PASS having run nothing — and the negative case matters just as much:
        # a run: that merely CONTAINS comments alongside a command is the normal shape here.
        for body, want_abort in (
            ('run: ""', True),
            ('run: "   "', True),
            ('run: "# only a comment"', True),
            ('run: "# a comment\\ntrue"', False),
        ):
            (tmp / ".github" / "gates" / "gates.yaml").write_text(
                f"gates:\n  - id: x\n    name: x\n    group: t\n    {body}\n"
            )
            try:
                load(tmp)
                if want_abort:
                    bad.append(f"load() accepted a no-op run: ({body})")
            except SystemExit:
                if not want_abort:
                    bad.append(f"load() rejected a run: that does have a command ({body})")

        # Off the runner, the same over-budget gate must PASS — the whole point of gating the
        # rule on CI is that a laptop's timings are not a measurement.
        os.environ["CI"] = "false"
        (tmp / ".github" / "gates" / "gates.yaml").write_text(
            "gates:\n  - id: x\n    name: x\n    group: t\n    budget_seconds: 0.2\n"
            '    run: "sleep 0.5"\n'
        )
        g = load(tmp)[0]
        r = execute(g, tmp, is_pr=False, timeout=5)
        if r.status != "ok":
            bad.append(f"off-CI over-budget gate: want ok, got {r.status}")
        if "does not fail" not in r.output:
            bad.append("off-CI over-budget gate: did not say why it was not failed")
        os.environ["CI"] = "true"

        # A budget must be a positive number.
        for body, want_abort in (
            ("budget_seconds: 0", True),
            ("budget_seconds: -3", True),
            ('budget_seconds: "30"', True),
            ("budget_seconds: 30", False),
            ("budget_seconds: 0.5", False),
        ):
            (tmp / ".github" / "gates" / "gates.yaml").write_text(
                f"gates:\n  - id: x\n    name: x\n    group: t\n    {body}\n"
                f'    run: "true"\n'
            )
            try:
                load(tmp)
                if want_abort:
                    bad.append(f"load() accepted an unusable budget_seconds ({body})")
            except SystemExit:
                if not want_abort:
                    bad.append(f"load() rejected a valid budget_seconds ({body})")

        # A floor must be a positive integer. `0` is the shape that matters: it reads like a
        # declaration and asserts nothing at all.
        for body, want_abort in (
            ("min_subjects: 0", True),
            ("min_subjects: -1", True),
            ('min_subjects: "5"', True),
            ("min_subjects: true", True),
            ("min_subjects: 1", False),
        ):
            (tmp / ".github" / "gates" / "gates.yaml").write_text(
                f"gates:\n  - id: x\n    name: x\n    group: t\n    {body}\n"
                f"    run: \"echo SUBJECTS=1\"\n"
            )
            try:
                load(tmp)
                if want_abort:
                    bad.append(f"load() accepted an unusable min_subjects ({body})")
            except SystemExit:
                if not want_abort:
                    bad.append(f"load() rejected a valid min_subjects ({body})")

        # The parse-cache contract is a STRING shared with gatelib.py, in two files that do not
        # import each other. A rename on one side would silently turn the cross-gate cache off
        # — everything still green, just slower, which is the kind of regression nothing here
        # would ever notice.
        try:
            sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
            import gatelib

            if gatelib.CACHE_DIR_ENV != PARSE_CACHE_ENV:
                bad.append(
                    f"parse-cache env var disagrees: run-gates has {PARSE_CACHE_ENV!r}, "
                    f"gatelib has {gatelib.CACHE_DIR_ENV!r}"
                )
        except ImportError as exc:
            bad.append(f"gatelib.py is not importable from the scripts directory: {exc}")

        # The ${{ }} guard: red on a real command, and — the half that actually needed
        # deciding — GREEN on a comment that merely mentions the syntax.
        for body, want_abort in (
            ('run: "echo ${{ github.sha }}"', True),
            ('run: "# never write ${{ github.sha }} here\\necho ok"', False),
        ):
            (tmp / ".github" / "gates" / "gates.yaml").write_text(
                f"gates:\n  - id: x\n    name: x\n    group: t\n    {body}\n"
            )
            try:
                load(tmp)
                if want_abort:
                    bad.append("load() accepted a ${{ }} expression in a command")
            except SystemExit:
                if not want_abort:
                    bad.append("load() rejected a ${{ }} mentioned only in a comment")

        # needs_base. Every combination that must abort, and the three that must not —
        # a load() that rejected everything would satisfy the negative cases on its own.
        # The `required` + `when: always` row is the one that matters: that gate shape ran
        # green on every PR and failed every push to main, so only a push found it.
        for decl, body, when, want_abort in (
            ('', 'run: "echo $PR_DIFF_BASE"', '', True),               # reads it, undeclared
            ('needs_base: required', 'run: "true"', 'when: pull_request', True),  # declared, never read
            ('needs_base: optional', 'run: "echo $PR_DIFF_BASE"', '', True),      # bare read is required
            ('needs_base: required', 'run: "echo ${PR_DIFF_BASE:-}"',
             'when: pull_request', True),                              # defaulted read is optional
            ('needs_base: required', 'run: "echo $PR_DIFF_BASE"', '', True),      # required + when:always
            ('needs_base: sometimes', 'run: "echo $PR_DIFF_BASE"',
             'when: pull_request', True),                              # not a valid value
            ('needs_base: required', 'run: "echo $PR_DIFF_BASE"',
             'when: pull_request', False),                             # the valid required shape
            ('needs_base: optional', 'run: "echo ${PR_DIFF_BASE:-}"', '', False),  # the valid optional shape
            ('', 'run: "true"', '', False),                            # neither
        ):
            (tmp / ".github" / "gates" / "gates.yaml").write_text(
                f"gates:\n  - id: x\n    name: x\n    group: t\n    {decl}\n    {when}\n    {body}\n"
            )
            label = f"{decl or 'undeclared'} / {when or 'when:always'} / {body}"
            try:
                load(tmp)
                if want_abort:
                    bad.append(f"load() accepted an invalid needs_base shape: {label}")
            except SystemExit:
                if not want_abort:
                    bad.append(f"load() rejected a VALID needs_base shape: {label}")

        # And at run time: `required` with an empty base is refused, `optional` runs anyway.
        #
        # Assert the MESSAGE, not just the status. Gates run under `bash -euo pipefail`, so a
        # bare `$PR_DIFF_BASE` on an unset variable already dies with "unbound variable" —
        # status alone cannot tell the guard from `-u`, and this assertion passed against a
        # deliberately disabled guard until it checked the text. The guard still earns its
        # place: "refusing to run vacuously" says what went wrong, `unbound variable` does not.
        for decl, body, when, want, want_text in (
            ('needs_base: required', 'run: "echo $PR_DIFF_BASE"', 'when: pull_request',
             "failed", "refusing to run vacuously"),
            ('needs_base: optional', 'run: "echo ${PR_DIFF_BASE:-}"', '', "ok", None),
        ):
            (tmp / ".github" / "gates" / "gates.yaml").write_text(
                f"gates:\n  - id: x\n    name: x\n    group: t\n    {decl}\n    {when}\n    {body}\n"
            )
            os.environ.pop("PR_DIFF_BASE", None)
            g = load(tmp)[0]
            # `required` gates are pull_request-only by construction, so drive it as a PR.
            r = execute(g, tmp, is_pr=True, timeout=5)
            if r.status != want:
                bad.append(f"empty PR_DIFF_BASE + {decl}: want {want}, got {r.status}")
            if want_text and want_text not in r.output:
                bad.append(
                    f"empty PR_DIFF_BASE + {decl}: failed for the wrong reason — expected "
                    f"the guard's message, got: {r.output.strip()[:120]}"
                )

        # --json end to end: a real file gets written and round-trips through json.load, and
        # an unwritable path warns instead of taking the whole run down (main()'s own
        # try/except, exercised here rather than only unit-testing json_records() in
        # isolation — the file-write path is where a real deploy would actually break).
        #
        # The manifest on disk was overwritten by every needs_base/${{ }} case run above —
        # restore SELF_TEST_MANIFEST before driving main() through it, or this exercises
        # whatever single-gate fixture happened to be written last.
        (tmp / ".github" / "gates" / "gates.yaml").write_text(SELF_TEST_MANIFEST)
        # --timeout 3, not the 420s default: group "t" includes the "slow" fixture, which
        # sleeps 30s specifically to exercise the timeout path — without capping it here,
        # every future run of THIS self-test would silently cost 30 extra seconds.
        out_path = tmp / "gate-results.json"
        argv_json = ["--root", str(tmp), "--group", "t", "--json", str(out_path), "--timeout", "3"]
        rc = main(argv_json)
        if rc == 0:
            bad.append("--json run: expected a non-zero exit (the manifest has failing gates)")
        if not out_path.is_file():
            bad.append("--json: no file was written")
        else:
            try:
                records = json.loads(out_path.read_text())
                if not any(r["id"] == "passing" and r["status"] == "ok" for r in records):
                    bad.append("--json: the written file does not contain the expected record")
            except json.JSONDecodeError as exc:
                bad.append(f"--json: written file is not valid JSON: {exc}")

        import io
        import contextlib

        sink = io.StringIO()
        with contextlib.redirect_stderr(sink):
            rc2 = main(["--root", str(tmp), "--group", "t", "--timeout", "3",
                        "--json", str(tmp / "no" / "such" / "dir" / "x.json")])
        if "could not write --json" not in sink.getvalue():
            bad.append("--json to an unwritable path did not warn")
        if rc2 == 0:
            bad.append("--json to an unwritable path unexpectedly reported success overall")

        # ------------------------------------------------------------------
        # #6068: the runner must not be able to report success having run nothing,
        # and must not be able to report ANYTHING silently.
        # ------------------------------------------------------------------

        # 1. Zero gates ran -> non-zero, and it must SAY that nothing ran. Status-only
        #    assertions would pass against a report() that returned 2 for a different
        #    reason, which is how a guard ends up green for the wrong cause.
        sink0 = io.StringIO()
        with contextlib.redirect_stderr(sink0):
            rc_empty = report([], 1)
        if rc_empty == 0:
            bad.append("report([]) returned 0 — a run that evaluated nothing read as a pass")
        if "ZERO gates ran" not in sink0.getvalue():
            bad.append("report([]) failed without naming the fact that nothing ran")

        # 2. The cross-count. Both directions: a manifest whose two counting methods
        #    disagree must abort, and the REAL manifest must agree — a check that always
        #    reported a mismatch would satisfy the negative case on its own.
        (tmp / ".github" / "gates" / "gates.yaml").write_text(SELF_TEST_MANIFEST)
        if gate_count_by_text(tmp) != len(load(tmp)):
            bad.append("gate_count_by_text disagrees with the YAML parse on a VALID manifest")
        repo_root = pathlib.Path(__file__).resolve().parent.parent.parent
        if (repo_root / MANIFEST).is_file():
            n_text, n_yaml = gate_count_by_text(repo_root), len(load(repo_root))
            if n_text != n_yaml:
                bad.append(f"the repo's own {MANIFEST}: text scan {n_text} != YAML {n_yaml}")
        # duplicate `id:` keys inside one entry: PyYAML keeps the last, the text scan sees
        # the entries — the shape the cross-count exists to catch.
        (tmp / ".github" / "gates" / "gates.yaml").write_text(
            'gates:\n  - id: a\n    name: a\n    group: t\n    run: "true"\n'
        )
        if gate_count_by_text(tmp) != 1:
            bad.append(f"text scan miscounted a 1-gate manifest: {gate_count_by_text(tmp)}")

        # 3. NO TTY -> still non-empty output, same verdict as an ordinary run. Driven as a
        #    real SUBPROCESS with pipes for stdout/stderr: an in-process call cannot observe
        #    buffering at all, which is the whole defect (#6068). stdin is closed too, so
        #    nothing about the invocation looks interactive.
        (tmp / ".github" / "gates" / "gates.yaml").write_text(SELF_TEST_MANIFEST)
        for label, argv_tty in (
            ("failing shard", ["--group", "t", "--timeout", "3"]),
            ("passing shard", ["--only", "passing"]),
        ):
            proc = subprocess.run(
                [sys.executable, str(pathlib.Path(__file__).resolve()),
                 "--root", str(tmp)] + argv_tty,
                capture_output=True, text=True, timeout=120, stdin=subprocess.DEVNULL,
                env={**os.environ, "GITHUB_EVENT_NAME": "push"},
            )
            if not proc.stdout.strip():
                bad.append(f"no-TTY {label}: EMPTY stdout — the #6068 shape, exit "
                           f"{proc.returncode}")
            if "--- verdicts" not in proc.stdout:
                bad.append(f"no-TTY {label}: no per-gate verdict roster in the output")
            want_rc = (label == "passing shard")
            if (proc.returncode == 0) != want_rc:
                bad.append(f"no-TTY {label}: exit {proc.returncode}, want "
                           f"{'0' if want_rc else 'non-zero'}")

        # 4. STREAMING, not just eventual. The three checks above all wait for the process
        #    to exit, and exit flushes — so every one of them passes against the block-
        #    buffered build that produced #6068. This is the assertion that can actually
        #    fail: with stdout redirected to a FILE (never a TTY), output must be readable
        #    while the run is still in flight. Measured on origin/main before the fix:
        #    0 bytes for the whole 81s of `--all`, everything appearing at exit.
        (tmp / ".github" / "gates" / "gates.yaml").write_text(
            'gates:\n  - id: slow-a\n    name: slow-a\n    group: t\n    run: "true"\n'
            '  - id: slow-b\n    name: slow-b\n    group: t\n    run: "sleep 6"\n'
        )
        stream_out = tmp / "streamed.log"
        with open(stream_out, "wb") as fh:
            proc = subprocess.Popen(
                [sys.executable, str(pathlib.Path(__file__).resolve()),
                 "--root", str(tmp), "--group", "t", "--timeout", "30"],
                stdout=fh, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                env={**os.environ, "GITHUB_EVENT_NAME": "push"},
            )
            seen = 0
            deadline = time.monotonic() + 4.0
            while time.monotonic() < deadline and proc.poll() is None:
                seen = stream_out.stat().st_size
                if seen:
                    break
                time.sleep(0.1)
            alive_when_seen = proc.poll() is None
            proc.wait(timeout=60)
        if not seen:
            bad.append(
                "stdout to a non-TTY file stayed EMPTY while the run was in flight — this "
                "is #6068 exactly: a killed or sampled run is indistinguishable from a "
                "silent pass. unbuffer() is not taking effect."
            )
        elif not alive_when_seen:
            bad.append("output only appeared after the process had already exited")

        # 9. SELFTEST INPUT SCOPING. Skipping a falsification is the one optimisation in
        #    this runner that can hollow it out, so every branch is driven here — including
        #    the two that must NOT skip, because a feature that skips everything would satisfy
        #    a test that only checked the skip.
        SKIP_CASES = [
            # (gate dict fragment, changed set, want_needed, label)
            ({"selftest": "true"}, {"any/file"}, True,
             "no selftest_inputs declared -> unchanged behaviour, always runs"),
            ({"selftest": "true", "selftest_inputs": ["a/b.py"]}, {"a/b.py"}, True,
             "a declared input changed -> runs"),
            ({"selftest": "true", "selftest_inputs": ["a"]}, {"a/deep/c.py"}, True,
             "a declared DIRECTORY prefix contains the change -> runs"),
            ({"selftest": "true", "selftest_inputs": ["a/b.py"]}, {"z/other.py"}, False,
             "nothing declared changed -> skipped"),
            ({"selftest": "true", "selftest_inputs": ["a/b.py"]},
             {".github/gates/gates.yaml"}, True,
             "the manifest changed -> every self-test runs"),
            ({"selftest": "true", "selftest_inputs": ["a/b.py"]},
             {".github/scripts/run-gates.py"}, True,
             "the runner changed -> every self-test runs"),
            ({"selftest": "true", "selftest_inputs": ["a/b.py"]},
             {".github/scripts/gatelib.py"}, True,
             "gatelib changed -> every self-test runs"),
            ({"selftest": "true", "selftest_inputs": ["a/b.py"]}, None, True,
             "changed set UNKNOWN -> runs (None is not an empty set)"),
            ({"selftest": "true", "selftest_inputs": ["a/b.py"]}, set(), False,
             "a pull request that changed nothing -> skipped"),
            ({"selftest": "true", "selftest_inputs": ["ab"]}, {"abc/d.py"}, False,
             "prefix must be a PATH boundary: `ab` must not match `abc/`"),
            ({"run": "true"}, {"a/b.py"}, False,
             "no selftest at all -> nothing to run"),
        ]
        for frag, changed_set, want, label in SKIP_CASES:
            got, _why = selftest_is_needed(dict(frag), changed_set)
            if got != want:
                bad.append(f"selftest scoping [{label}]: want needed={want}, got {got}")

        # And end to end through execute(), because the predicate agreeing is not the same as
        # the runner acting on it: a skipped self-test must still RUN THE GATE, must say in its
        # own output that the falsification did not happen, and must not report `selftest_passed`.
        scoped = {
            "id": "scoped", "name": "scoped", "group": "t", "when": "always",
            "mode": "enforced", "selftest": "exit 1", "selftest_expect": "pass",
            "selftest_inputs": ["a/b.py"], "run": "echo ran-anyway",
        }
        r = execute(dict(scoped), tmp, True, 60, None, {"z/unrelated.py"})
        if r.status != "ok":
            bad.append(f"scoped skip: a broken self-test with untouched inputs should not "
                       f"block the gate on a PR, got {r.status}")
        if "ran-anyway" not in r.output:
            bad.append("scoped skip: the GATE itself did not run")
        if "self-test SKIPPED" not in r.output:
            bad.append("scoped skip: output does not say the falsification was skipped")
        if not r.selftest_skipped:
            bad.append("scoped skip: selftest_skipped flag not set")
        if json_records([r])[0]["selftest_passed"] is not None:
            bad.append("scoped skip: selftest_passed must be null, never true, when skipped")
        if r.selftest_seconds != 0.0:
            bad.append("scoped skip: charged self-test time for a self-test that did not run")

        # The SAME gate, same broken self-test, with the changed set unknown — i.e. every push
        # to main. It must go UNFALSIFIED. This is the case that makes the whole design safe,
        # so it is asserted rather than assumed.
        r = execute(dict(scoped), tmp, False, 60, None, None)
        if r.status != "unfalsified":
            bad.append(f"off a pull request a broken self-test must be UNFALSIFIED, got {r.status}")

        # A declared input that does not exist would skip forever, silently. load() must refuse.
        (tmp / "real-input.py").write_text("# a path that really exists\n")
        man = tmp / ".github" / "gates" / "gates.yaml"
        for body, want_refused in (
            ('selftest_inputs: ["does/not/exist.py"]', True),
            ('selftest_inputs: [".github/gates/gates.yaml"]', True),   # already universal
            ('selftest_inputs: []', True),
            ('selftest_inputs: "a-string"', True),
            ('selftest_inputs: ["real-input.py"]', False),             # the valid shape
            # ...and the valid shape must still be refused without a selftest to scope.
            ('selftest_inputs: ["real-input.py"]\n    NO_SELFTEST: 1', True),
        ):
            decl = body.replace("\n    NO_SELFTEST: 1", "")
            has_selftest = "NO_SELFTEST" not in body
            man.write_text(
                "gates:\n  - id: x\n    name: x\n    group: t\n"
                + ('    selftest: "true"\n    selftest_expect: pass\n' if has_selftest else "")
                + f"    {decl}\n" + '    run: "true"\n'
            )
            rc = subprocess.run(
                [sys.executable, str(pathlib.Path(__file__).resolve()), "--list"],
                capture_output=True, text=True, cwd=str(tmp),
            ).returncode
            if want_refused and rc == 0:
                bad.append(f"load() accepted an unusable selftest_inputs ({body!r})")
            if not want_refused and rc != 0:
                bad.append(f"load() rejected a valid selftest_inputs ({body!r})")
        man.write_text(SELF_TEST_MANIFEST)   # restore for anything after this block

        if bad:
            print("\n::error::run-gates self-test FAILED:")
            for b in bad:
                print(f"  - {b}")
            return 1
        print("\nrun-gates self-test: all branches reachable and correctly classified.")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
