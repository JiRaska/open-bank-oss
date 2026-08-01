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
import os
import pathlib
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
VALID_MODES = {"enforced", "advisory"}
VALID_WHEN = {"always", "pull_request"}
VALID_EXPECT = {"pass", "fail"}


# ---------------------------------------------------------------------------
# Manifest
# ---------------------------------------------------------------------------
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
        # A gate that reads $PR_DIFF_BASE without declaring needs_base would silently diff
        # against an empty string on push-to-main. Catch the mismatch at load time, in both
        # directions, so the declaration cannot drift from the command.
        reads_base = "PR_DIFF_BASE" in g["run"]
        if reads_base != bool(g.get("needs_base")):
            sys.stderr.write(
                f"::error::gate {g['id']}: needs_base={bool(g.get('needs_base'))} but the "
                f"command {'reads' if reads_base else 'does not read'} $PR_DIFF_BASE\n"
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
        got = (exc.stdout or "") + (exc.stderr or "")
        if isinstance(got, bytes):
            got = got.decode("utf-8", "replace")
        return 124, got + f"\n[run-gates] TIMEOUT after {timeout}s\n"
    finally:
        if scratch is not None:
            try:
                os.unlink(scratch.name)
            except OSError:
                pass


def execute(gate, root: pathlib.Path, is_pr: bool, timeout: int, index=None) -> Result:
    r = Result(gate)
    if gate["when"] == "pull_request" and not is_pr:
        r.status = "skipped"
        r.output = "skipped: pull_request-only gate, this is not a pull_request event\n"
        return r
    if gate.get("needs_base") and not os.environ.get("PR_DIFF_BASE"):
        # Fail loudly. Running the gate with an empty base would diff against nothing and
        # pass — a gate green about work it never did.
        r.status = "failed"
        r.output = "PR_DIFF_BASE is empty but this gate needs it — refusing to run vacuously\n"
        return r

    t0 = time.monotonic()
    buf = []

    selftest = gate.get("selftest")
    if selftest:
        want_pass = gate.get("selftest_expect", "pass") == "pass"
        rc, out = _run(selftest, root, gate.get("env"), timeout, index)
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
    r.output = "".join(buf)
    r.seconds = time.monotonic() - t0
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
    for r in warned:
        print(f"::warning title={r.gate['id']}::advisory gate failed: {r.gate['name']}")
    for r in failed:
        why = "self-test verdict wrong — gate is unfalsified" if r.status == "unfalsified" else "gate failed"
        print(f"::error title={r.gate['id']}::{why}: {r.gate['name']}")
    return 1 if failed else 0


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
    ap.add_argument("--self-test", action="store_true", help="falsify the runner itself")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    gates = load(root, args.manifest)

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

    index = git_index_path(root)
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as ex:
        futs = {ex.submit(execute, g, root, is_pr, args.timeout, index): g["id"] for g in sel}
        done = {futs[f]: f.result() for f in concurrent.futures.as_completed(futs)}
    results = [done[g["id"]] for g in sel]
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
  - id: slow
    name: "a gate that exceeds its timeout"
    group: t
    run: "sleep 30"
"""

EXPECTED = {
    "passing": "ok",
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
        results = [execute(g, tmp, is_pr=False, timeout=2) for g in gates]
        bad = []
        for r in results:
            want = EXPECTED[r.id]
            mark = "ok " if r.status == want else "BAD"
            print(f"  {mark} {r.id:18s} want={want:12s} got={r.status}")
            if r.status != want:
                bad.append(f"{r.id}: want {want}, got {r.status}")

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

        # A needs_base mismatch must abort in BOTH directions.
        for body, decl in (('run: "echo $PR_DIFF_BASE"', ""), ('run: "true"', "needs_base: true")):
            (tmp / ".github" / "gates" / "gates.yaml").write_text(
                f"gates:\n  - id: x\n    name: x\n    group: t\n    {decl}\n    {body}\n"
            )
            try:
                load(tmp)
                bad.append(f"load() accepted a needs_base mismatch ({decl or 'undeclared'})")
            except SystemExit:
                pass

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
