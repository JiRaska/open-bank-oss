#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# A SELF-TEST MUST DECLARE EVERY REPO FILE IT READS.
#
# run-gates.py skips a gate's self-test on a pull request unless one of the gate's declared
# `selftest_inputs` changed (`selftest_is_needed`, ~193 gate-hours saved per 23 days). That
# optimisation is only sound while the declaration is COMPLETE. A self-test that depends on a
# file nobody declared is skipped on exactly the pull request that changes that file, and the
# breakage surfaces on main instead, where every self-test runs. 2026-11-09: a mermaid bump
# changed openbank-admin-ui/package-lock.json; `mermaid-parses` runs admin-ui's own node
# modules, its declaration named only its script, the self-test was skipped on the PR, and
# main was UNFALSIFIED for 85 minutes. That instance was fixed by hand; this makes the class
# mechanical.
#
# MECHANISM — observe, do not infer
#   When OB_SELFTEST_TRACE is set, run-gates.py runs each self-test under
#   `strace -f --seccomp-bpf -y -e trace=open,openat,openat2`. `-y` makes strace print the
#   ABSOLUTE path behind every returned file descriptor, so a successful open is recorded as
#   the file actually opened — relative paths, chdir and symlinks are already resolved by the
#   kernel, and nothing here has to model a shell, Python's import system or Node's resolver.
#   Not --seccomp-bpf, though it would be cheaper: it sets no_new_privs, which makes sudo
#   inside a self-test fail, and a correct verdict outranks tracer overhead.
#
#   A read becomes a FINDING when, after attribution:
#     * it is a git-TRACKED file of this checkout (an untracked fixture the self-test wrote,
#       anything under /tmp, .git internals and system paths can never be a PR's change);
#     * no declared `selftest_inputs` entry covers it (the same prefix rule run-gates uses);
#     * it is not a UNIVERSAL_SELFTEST_INPUT (those re-run every self-test already);
#     * it is not covered by the baseline below.
#   Attribution maps two kinds of untracked read onto the tracked file that determines them,
#   because that is how the incident happened — nothing ever OPENED the lockfile:
#     * <dir>/node_modules/**           -> <dir>/package-lock.json (else package.json)
#     * <dir>/__pycache__/<m>.*.pyc     -> <dir>/<m>.py  (a warm bytecode cache never opens
#                                          the source, so the import would otherwise vanish)
#
#   What this does NOT see, stated so a green is not over-read: a stat() or existence probe of
#   a repo file (only opens are traced), a read through a network service, and anything a
#   self-test does not reach on the path it actually ran.
#
# RATCHET
#   .github/gates/selftest-undeclared-inputs.baseline holds today's undeclared reads, one
#   `<gate-id> <path>` per line; a path ending in `/` covers everything under it. A NEW
#   undeclared read fails the gate's self-test; a baseline entry the traced self-test no longer
#   reads is reported as stale (a notice — reads can differ by environment, so absence on one
#   runner is not proof). `--check` (the `selftest-inputs-traced` gate) fails a baseline entry
#   that a declaration now covers, or that names a gate which no longer exists, so the list can
#   only shrink by being noticed.
#
# Usage:
#   selftest_input_trace.py --check               baseline + wiring consistency (the gate)
#   selftest_input_trace.py --self-test           falsify the tracer and the attribution
#   selftest_input_trace.py --baseline-from J...  print baseline lines from run-gates --json files

import argparse
import fnmatch
import functools
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from gatelib import hermetic_git_env  # noqa: E402

TRACE_ENV = "OB_SELFTEST_TRACE"          # "1" = trace when possible, "require" = fail if not
BASELINE = ".github/gates/selftest-undeclared-inputs.baseline"
MANIFEST = ".github/gates/gates.yaml"
CI_WORKFLOW = ".github/workflows/ci.yml"
# Mirrors run-gates.py UNIVERSAL_SELFTEST_INPUTS (kept equal by this module's --check).
UNIVERSAL = (
    ".github/gates/gates.yaml",
    ".github/scripts/run-gates.py",
    ".github/scripts/gatelib.py",
    ".github/scripts/selftest_input_trace.py",
    BASELINE,
)

# `1234  openat(AT_FDCWD, "x", O_RDONLY) = 3</abs/x>` — only successful opens carry `<path>`.
_RET_PATH = re.compile(r"\)\s+=\s+\d+<(.*)>\s*$")
_PYC = re.compile(r"^(.*?)(?:^|/)__pycache__/([^/]+?)\.[^/]*\.pyc$")


def strace_argv(trace_file: str) -> list:
    # No --seccomp-bpf: it sets no_new_privs, and sudo inside a self-test then refuses to run
    # (measured: promql-alert-unit-tests' promtool install went UNFALSIFIED under it).
    return ["strace", "-f", "-qq", "-y", "-e", "trace=open,openat,openat2",
            "-e", "signal=none", "-o", trace_file]


@functools.lru_cache(maxsize=1)
def strace_usable():
    """(ok, why). Proven by tracing a real open, not by `command -v` — ptrace can be denied."""
    if not shutil.which("strace"):
        return False, "strace is not installed"
    with tempfile.TemporaryDirectory() as d:
        probe = os.path.join(d, "probe.txt")
        pathlib.Path(probe).write_text("x")
        out = os.path.join(d, "trace")
        try:
            p = subprocess.run(strace_argv(out) + ["cat", probe], capture_output=True,
                               text=True, timeout=60)
        except (OSError, subprocess.SubprocessError) as e:
            return False, f"strace could not run: {e}"
        if p.returncode != 0:
            return False, f"strace exited {p.returncode}: {p.stderr.strip()[:300]}"
        if os.path.realpath(probe) not in opened_paths(pathlib.Path(out).read_text("utf-8", "replace")):
            return False, "strace ran but did not record a known open (known-positive missing)"
    return True, ""


def _unescape(s: str) -> str:
    # strace escapes non-printables as \xNN and `\`/`"` with a backslash; repo paths are ASCII.
    return re.sub(r"\\x([0-9a-fA-F]{2})", lambda m: chr(int(m.group(1), 16)), s).replace("\\\\", "\\")


def opened_paths(trace_text: str) -> set:
    found = set()
    for line in trace_text.splitlines():
        m = _RET_PATH.search(line)
        if m:
            found.add(_unescape(m.group(1)))
    return found


@functools.lru_cache(maxsize=4)
def tracked_files(root: str) -> frozenset:
    # hermetic_git_env drops GIT_INDEX_FILE/GIT_DIR: run-gates hands each gate a private index,
    # which must never redirect this listing to a different tree.
    p = subprocess.run(["git", "ls-files", "-z"], cwd=root, capture_output=True, timeout=120,
                       env=hermetic_git_env())
    if p.returncode != 0:
        raise RuntimeError(f"git ls-files failed in {root}: {p.stderr.decode()[:200]}")
    return frozenset(x for x in p.stdout.decode("utf-8", "replace").split("\0") if x)


def attribute(abs_paths, root: str, tracked) -> set:
    """Repo-relative TRACKED files these opens depend on (see header: attribution)."""
    real_root = os.path.realpath(root)
    out = set()
    for a in abs_paths:
        rp = os.path.realpath(a)
        if not (rp == real_root or rp.startswith(real_root + os.sep)):
            continue
        rel = os.path.relpath(rp, real_root).replace(os.sep, "/")
        if rel == "." or rel == ".git" or rel.startswith(".git/"):
            continue
        if rel in tracked:
            out.add(rel)
            continue
        parts = rel.split("/")
        if "node_modules" in parts:
            pkg = "/".join(parts[:parts.index("node_modules")])
            for name in ("package-lock.json", "package.json"):
                cand = f"{pkg}/{name}" if pkg else name
                if cand in tracked:
                    out.add(cand)
                    break
            continue
        m = _PYC.match(rel)
        if m:
            d, mod = m.group(1), m.group(2)
            cand = f"{d}/{mod}.py" if d else f"{mod}.py"
            if cand in tracked:
                out.add(cand)
    return out


def covered(path: str, decls) -> bool:
    for d in decls:
        d0 = d.rstrip("/")
        if path == d0 or path.startswith(d0 + "/"):
            return True
    return False


def baseline_covers(path: str, entries) -> bool:
    """A baseline entry is a path, or a prefix ending in `/`, whose segments may be fnmatch
    globs (`openbank-*/`). Globs exist for the corpus-scanning self-tests: a self-test that
    walks every service reads every new service's files too, and a per-file list would turn
    each new file into a red self-test on main."""
    segs = path.split("/")
    for e in entries:
        prefix = e.endswith("/")
        es = e.rstrip("/").split("/")
        if len(segs) < len(es) or (not prefix and len(segs) != len(es)):
            continue
        if all(fnmatch.fnmatchcase(s, p) for s, p in zip(segs, es)):
            return True
    return False


def load_baseline(root) -> dict:
    """{gate_id: [entry, ...]} — raises ValueError on a malformed line."""
    p = pathlib.Path(root) / BASELINE
    base = {}
    if not p.is_file():
        return base
    for n, raw in enumerate(p.read_text().splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 2:
            raise ValueError(f"{BASELINE}:{n}: want `<gate-id> <path>`, got {raw!r}")
        base.setdefault(parts[0], []).append(parts[1])
    return base


def undeclared(gate: dict, reads, baseline_entries=()):
    """(new_findings, baselined, stale_baseline) for one traced self-test."""
    decls = list(gate.get("selftest_inputs") or [])
    raw = sorted(r for r in reads if r not in UNIVERSAL and not covered(r, decls))
    new = [r for r in raw if not baseline_covers(r, baseline_entries)]
    based = [r for r in raw if baseline_covers(r, baseline_entries)]
    stale = [b for b in baseline_entries if not any(baseline_covers(r, [b]) for r in raw)]
    return new, based, stale


def analyse(trace_file: str, root: str, gate: dict, baseline_entries=()):
    text = pathlib.Path(trace_file).read_text("utf-8", "replace")
    reads = attribute(opened_paths(text), root, tracked_files(str(root)))
    return undeclared(gate, reads, baseline_entries)


# ---------------------------------------------------------------------------------------------
def check(root: pathlib.Path) -> int:
    import yaml
    gates = yaml.safe_load((root / MANIFEST).read_text())["gates"]
    by_id = {g["id"]: g for g in gates}
    errors = []
    try:
        base = load_baseline(root)
    except ValueError as e:
        errors.append(str(e))
        base = {}
    entries = 0
    for gid, paths in sorted(base.items()):
        g = by_id.get(gid)
        if g is None:
            errors.append(f"{BASELINE}: `{gid}` is not a gate in {MANIFEST} — drop its lines")
            continue
        if not (g.get("selftest") and g.get("selftest_inputs")):
            errors.append(f"{BASELINE}: `{gid}` declares no selftest/selftest_inputs, so its "
                          f"self-test always runs and a baseline entry means nothing — drop it")
        for p in paths:
            entries += 1
            if covered(p, g.get("selftest_inputs") or []) or p in UNIVERSAL:
                errors.append(f"{BASELINE}: `{gid} {p}` is now covered by a declaration — "
                              f"remove the stale baseline line")
            elif not any(c in p for c in "*?[") and not (root / p).exists():
                errors.append(f"{BASELINE}: `{gid} {p}` names a path that does not exist")
    # The control is only as real as the job that switches it on: a shard that stops setting
    # OB_SELFTEST_TRACE=require would turn every trace into a silent no-op.
    wf = (root / CI_WORKFLOW).read_text()
    if not re.search(rf"^\s*{TRACE_ENV}:\s*[\"']?require[\"']?\s*$", wf, re.M):
        errors.append(f"{CI_WORKFLOW}: the gate shards no longer set `{TRACE_ENV}: require`, so "
                      f"no self-test is traced and undeclared inputs go unseen")
    rg = root / ".github/scripts/run-gates.py"
    if rg.is_file():
        m = re.search(r"^UNIVERSAL_SELFTEST_INPUTS = \((.*?)^\)", rg.read_text(), re.M | re.S)
        theirs = set(re.findall(r'^\s*"([^"]+)"', m.group(1), re.M)) if m else set()
        if theirs != set(UNIVERSAL):
            errors.append("run-gates.py UNIVERSAL_SELFTEST_INPUTS and this module's UNIVERSAL "
                          f"disagree: {sorted(theirs ^ set(UNIVERSAL))}")
    subjects = sum(1 for g in gates if g.get("selftest") and g.get("selftest_inputs"))
    for e in errors:
        print(f"::error::{e}")
    print(f"{subjects} gate(s) declare selftest_inputs; {entries} baselined undeclared read(s) "
          f"across {len(base)} gate(s); {len(errors)} problem(s)")
    print(f"SUBJECTS={subjects}")
    return 1 if errors else 0


def baseline_from(json_files) -> int:
    lines = set()
    for f in json_files:
        for rec in json.loads(pathlib.Path(f).read_text()):
            for p in rec.get("selftest_undeclared_reads") or []:
                lines.add(f"{rec['id']} {p}")
    for line in sorted(lines):
        print(line)
    return 0


def self_test() -> int:
    ok, why = strace_usable()
    if not ok:
        print(f"FAIL: cannot falsify the tracer here — {why}")
        return 1
    bad = []
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d) / "repo"
        (root / "gate").mkdir(parents=True)
        (root / "ui/node_modules/lib").mkdir(parents=True)
        (root / "lib/__pycache__").mkdir(parents=True)
        files = {
            "gate/check.sh": "cat secret.txt ui/node_modules/lib/index.js lib/__pycache__/h.cpython-312.pyc\n",
            "secret.txt": "undeclared\n",
            "ui/package-lock.json": "{}\n",
            "ui/package.json": "{}\n",
            "lib/h.py": "\n",
            ".github/gates/gates.yaml": "gates: []\n",
        }
        for rel, body in files.items():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_text(body)
        (root / "ui/node_modules/lib/index.js").write_text("//\n")
        (root / "lib/__pycache__/h.cpython-312.pyc").write_text("pyc\n")
        (root / "scratch.txt").write_text("untracked\n")
        genv = hermetic_git_env()
        subprocess.run(["git", "init", "-q"], cwd=root, check=True, env=genv)
        subprocess.run(["git", "add", *files], cwd=root, check=True, env=genv)
        tracked_files.cache_clear()
        trace = str(pathlib.Path(d) / "trace")
        # cd into a subdirectory and read back up: the path must still resolve absolutely.
        cmd = ("cd gate && bash check.sh 2>/dev/null; cd .. && cat ../repo/gate/check.sh "
               ".github/gates/gates.yaml scratch.txt /etc/hostname >/dev/null 2>&1 || true")
        cmd = cmd.replace("bash check.sh", "(cd .. && bash gate/check.sh >/dev/null)")
        p = subprocess.run(strace_argv(trace) + ["bash", "-c", cmd], cwd=root)
        if p.returncode != 0:
            bad.append(f"traced fixture exited {p.returncode}")
        gate = {"id": "fixture", "selftest": "x", "selftest_inputs": ["gate/check.sh"]}
        new, _, _ = analyse(trace, str(root), gate)
        want = ["lib/h.py", "secret.txt", "ui/package-lock.json"]
        if new != want:
            bad.append(f"undeclared reads: want {want}, got {new}")
        # Full declaration -> clean. Directory prefix covers below it.
        g2 = dict(gate, selftest_inputs=["gate/check.sh", "secret.txt", "ui/", "lib/h.py"])
        if analyse(trace, str(root), g2)[0]:
            bad.append("a complete declaration still produced findings")
        # Baseline: an entry suppresses, an unread one is stale, a prefix entry covers.
        new, based, stale = analyse(trace, str(root), gate, ["secret.txt", "ui/", "never/read"])
        if new != ["lib/h.py"] or based != ["secret.txt", "ui/package-lock.json"] or stale != ["never/read"]:
            bad.append(f"baseline handling wrong: new={new} based={based} stale={stale}")
        # Lookalike prefix must NOT cover (run-gates' own `ab` vs `abc/` rule).
        if covered("secrets.txt", ["secret"]) or not covered("ui/a/b", ["ui"]):
            bad.append("prefix coverage disagrees with run-gates' rule")
        glob_cases = [("openbank-new-service/src/A.kt", ["openbank-*/"], True),
                      ("docs/x.md", ["openbank-*/"], False),
                      ("a/b/c.txt", ["a/b"], False),          # exact entry, not a prefix
                      ("a/b", ["a/b"], True),
                      ("a/bc/d", ["a/b/"], False)]
        for path, entries, want in glob_cases:
            if baseline_covers(path, entries) != want:
                bad.append(f"baseline_covers({path!r}, {entries}) != {want}")
    # --check must flag a baseline line that a declaration already covers.
    with tempfile.TemporaryDirectory() as d:
        r = pathlib.Path(d)
        (r / ".github/gates").mkdir(parents=True)
        (r / ".github/workflows").mkdir(parents=True)
        (r / "a.py").write_text("")
        (r / MANIFEST).write_text("gates:\n  - {id: g, selftest: x, selftest_inputs: [a.py]}\n")
        (r / CI_WORKFLOW).write_text(f"env:\n  {TRACE_ENV}: require\n")
        (r / BASELINE).write_text("g a.py\n")
        if check(r) == 0:
            bad.append("--check accepted a baseline entry already covered by a declaration")
        (r / BASELINE).write_text("")
        if check(r) != 0:
            bad.append("--check rejected a clean baseline")
        (r / CI_WORKFLOW).write_text("env: {}\n")
        if check(r) == 0:
            bad.append("--check accepted a workflow that never switches tracing on")
    for b in bad:
        print(f"FAIL: {b}")
    print("self-test " + ("FAILED" if bad else "passed"))
    return 1 if bad else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--baseline-from", nargs="+", metavar="JSON")
    a = ap.parse_args(argv)
    if a.self_test:
        return self_test()
    if a.baseline_from:
        return baseline_from(a.baseline_from)
    return check(pathlib.Path(a.root).resolve())


if __name__ == "__main__":
    sys.exit(main())
