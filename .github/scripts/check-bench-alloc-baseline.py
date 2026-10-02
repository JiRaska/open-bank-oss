#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Allocation regression gate for the shared-libs JMH benchmarks.

WHY ALLOCATION AND NOT TIME
    `gc.alloc.rate.norm` (bytes allocated per operation) is a property of the code: it is
    read from the JVM's per-thread allocation counter, so it reproduces on a laptop and on a
    shared CI runner alike. Wall-clock (ns/op) is a property of the machine and of whatever
    else that machine is doing; gating on it turns a busy runner into a red PR. ns/op is
    therefore uploaded as an artifact by the workflow and never read here.

WHAT IT CHECKS
    Three sets, and every disagreement between them is a finding:

      S  the benchmarks that EXIST   — `@Benchmark` methods under the module's src/jmh
      B  the benchmarks BASELINED    — keys of alloc-baseline.json
      R  the benchmarks that RAN     — entries of the JMH JSON (`--results`)

    * S - B  a benchmark with no baseline entry (it would never be compared)
    * B - S  a stale baseline entry (its benchmark was removed or renamed)
    * S - R  a benchmark missing from the results (it did not run, so nothing was compared)
    * R - B  a result with no baseline entry
    * for each benchmark in R and B: B/op above baseline by more than max(5 %, 16 B)

    The scope is DERIVED from the sources, never listed by hand: a gate whose coverage is a
    hand-kept list reads as passing when the list is short.

    Without `--results` only S-vs-B is checked. That half needs no JVM, so it is the part
    declared in .github/gates/gates.yaml and run on every PR; the comparison itself needs a
    JMH run and is the last step of `.github/workflows/libs-bench.yml`.

    An improvement beyond the tolerance is not a failure: it prints a ratchet-down notice, and
    `--write-baseline` rewrites the file from the results so the number is never typed by hand.

MODES
    Advisory by default (findings are ::warning, exit 0); `--enforce` exits 1 on any finding.
    rules.yaml: libs_bench_alloc_baseline carries the graduation date.

Usage:
    check-bench-alloc-baseline.py                              # static: sources vs baseline
    check-bench-alloc-baseline.py --results <jmh.json>         # + compare B/op
    check-bench-alloc-baseline.py --results <jmh.json> --write-baseline
    check-bench-alloc-baseline.py --self-test
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import pathlib
import re
import sys
import tempfile

MODULE = "openbank-libs-benchmarks"
BASELINE = f"{MODULE}/alloc-baseline.json"
SOURCES = f"{MODULE}/src/jmh/kotlin"
METRIC = "gc.alloc.rate.norm"

REL_TOLERANCE = 0.05
ABS_TOLERANCE_BYTES = 16.0

# Benchmarks that RUN and are REPORTED but are not compared, because their B/op is not a
# property of the code under this harness: measured bimodal across forks, i.e. the JIT decides
# the number, and a comparator over them would fire on correct code. A hand-kept list of
# measured FACTS, not of scope: an entry naming no @Benchmark is a finding, and one that ALSO
# has a baseline entry is a finding, so it can only shrink by being noticed. Re-measure with
# `-f 5` before adding to it; a sharp before/after difference is NOT bimodality.
UNSTABLE: dict[str, str] = {
    "com.openbank.libs.benchmarks.MoneyBench.allocate":
        "2026-09-30: 1592 / 1680 / 1680 / 1680 B/op over four -f 1 runs, then 1680 / 1560 / 1680 with "
        "a 5-iteration warmup (7.7 % spread, no code change) — C2 escape analysis on the BigInteger "
        "largest-remainder loop settles differently per fork",
    "com.openbank.libs.benchmarks.MoneyBench.split":
        "2026-09-30: 1536 / 2056 / 1536 / 1536 B/op over four -f 1 runs (33.9 % spread, no code change); "
        "same path as allocate (split delegates to it)",
}

PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)", re.M)
CLASS_RE = re.compile(r"^(?:open\s+|abstract\s+)*class\s+([A-Za-z0-9_]+)", re.M)
# `@Benchmark` then, possibly after other annotations/modifiers, `fun name(`.
BENCHMARK_RE = re.compile(r"@Benchmark\b(?:\s|@[\w.]+(?:\([^)]*\))?|open|override)*fun\s+`?([A-Za-z0-9_]+)`?\s*\(")


def strip_comments(text: str) -> str:
    """Remove // and (nesting) /* */ comments so a KDoc that mentions @Benchmark is not a benchmark."""
    out, i, depth, n = [], 0, 0, len(text)
    in_string = False
    while i < n:
        two = text[i:i + 2]
        if depth == 0 and not in_string and text[i] == '"':
            in_string = True
            out.append(text[i]); i += 1
        elif in_string:
            if text[i] == "\\" and i + 1 < n:
                out.append(text[i:i + 2]); i += 2
                continue
            if text[i] in '"\n':
                in_string = False
            out.append(text[i]); i += 1
        elif two == "/*":
            depth += 1; i += 2
        elif two == "*/" and depth:
            depth -= 1; i += 2
        elif depth:
            i += 1
        elif two == "//":
            while i < n and text[i] != "\n":
                i += 1
        else:
            out.append(text[i]); i += 1
    return "".join(out)


def declared_benchmarks(root: pathlib.Path) -> set[str]:
    """S: fully-qualified `<package>.<Class>.<method>` for every @Benchmark in the module."""
    found: set[str] = set()
    src = root / SOURCES
    for path in sorted(src.rglob("*.kt")) if src.is_dir() else []:
        text = strip_comments(path.read_text(encoding="utf-8"))
        package = PACKAGE_RE.search(text)
        classes = [(m.start(), m.group(1)) for m in CLASS_RE.finditer(text)]
        for m in BENCHMARK_RE.finditer(text):
            owner = [name for pos, name in classes if pos < m.start()]
            if not owner or not package:
                raise ValueError(f"{path}: @Benchmark {m.group(1)} has no enclosing top-level class/package")
            found.add(f"{package.group(1)}.{owner[-1]}.{m.group(1)}")
    return found


def load_baseline(path: pathlib.Path) -> dict[str, float]:
    doc = json.loads(path.read_text(encoding="utf-8"))
    bench = doc.get("benchmarks")
    if not isinstance(bench, dict):
        raise ValueError(f"{path}: no 'benchmarks' object")
    return {str(k): float(v) for k, v in bench.items()}


def load_results(path: pathlib.Path) -> dict[str, float]:
    """R: benchmark -> B/op. A result without the gc profiler's metric is an error, not a zero."""
    out: dict[str, float] = {}
    for entry in json.loads(path.read_text(encoding="utf-8")):
        name = entry["benchmark"]
        params = entry.get("params") or {}
        if params:
            name += "[" + ",".join(f"{k}={params[k]}" for k in sorted(params)) + "]"
        secondary = entry.get("secondaryMetrics") or {}
        if METRIC not in secondary:
            raise ValueError(f"{path}: {name} has no {METRIC} — was JMH run with -prof gc?")
        out[name] = float(secondary[METRIC]["score"])
    return out


def allowed(baseline: float) -> float:
    return baseline + max(baseline * REL_TOLERANCE, ABS_TOLERANCE_BYTES)


def compare(declared: set[str], baseline: dict[str, float], results: dict[str, float] | None):
    """Return (findings, notices)."""
    findings: list[str] = []
    notices: list[str] = []
    base = set(baseline)
    for name in sorted(set(UNSTABLE) - declared):
        findings.append(f"{name}: UNSTABLE entry names no @Benchmark in {SOURCES} — remove it")
    for name in sorted(set(UNSTABLE) & base):
        findings.append(f"{name}: is UNSTABLE (not compared) yet has a baseline entry — --write-baseline again")
    declared = declared - set(UNSTABLE)
    for name in sorted(declared - base):
        findings.append(f"{name}: benchmark has no baseline entry — run it and --write-baseline")
    for name in sorted(base - declared):
        findings.append(f"{name}: stale baseline entry — no such @Benchmark in {SOURCES}")
    if results is None:
        return findings, notices
    ran = set(results) - set(UNSTABLE)
    for name in sorted(set(UNSTABLE) & set(results)):
        notices.append(f"{name}: {results[name]:.1f} B/op reported, NOT compared (UNSTABLE: {UNSTABLE[name]})")
    for name in sorted(declared - ran):
        findings.append(f"{name}: missing from the results — the benchmark did not run, so nothing was compared")
    for name in sorted(ran - base):
        findings.append(f"{name}: result has no baseline entry ({results[name]:.1f} B/op)")
    for name in sorted(ran & base):
        got, want = results[name], baseline[name]
        if got > allowed(want):
            pct = (got - want) / want * 100 if want else float("inf")
            findings.append(
                f"{name}: {got:.1f} B/op exceeds baseline {want:.1f} B/op by {got - want:+.1f} B "
                f"({pct:+.1f} %); allowed up to {allowed(want):.1f}"
            )
        elif got < want - max(want * REL_TOLERANCE, ABS_TOLERANCE_BYTES):
            notices.append(
                f"{name}: {got:.1f} B/op is below baseline {want:.1f} B/op "
                f"({(got - want) / want * 100:+.1f} %) — ratchet the baseline down (--write-baseline)"
            )
    return findings, notices


def write_baseline(path: pathlib.Path, results: dict[str, float]) -> None:
    doc = {
        "metric": f"{METRIC} (B/op)",
        "tolerance": f"max({REL_TOLERANCE:.0%}, {ABS_TOLERANCE_BYTES:.0f} B) above baseline",
        "regenerate": (
            "./gradlew :openbank-libs-benchmarks:jmh && python3 .github/scripts/check-bench-alloc-baseline.py "
            "--results openbank-libs-benchmarks/build/reports/jmh/results.json --write-baseline"
        ),
        "benchmarks": {name: round(results[name], 1) for name in sorted(results)},
    }
    path.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")


def run(root: pathlib.Path, results_path: pathlib.Path | None, enforce: bool, write: bool) -> int:
    declared = declared_benchmarks(root)
    if not declared:
        print(f"::error::no @Benchmark found under {SOURCES} — refusing to report a pass")
        return 1
    results = load_results(results_path) if results_path else None
    baseline_path = root / BASELINE
    if write:
        if results is None:
            print("::error::--write-baseline needs --results")
            return 2
        missing = sorted(declared - set(results))
        if missing:
            print(f"::error::refusing to write a baseline from a partial run; missing: {', '.join(missing)}")
            return 1
        compared = {k: v for k, v in results.items() if k in declared and k not in UNSTABLE}
        write_baseline(baseline_path, compared)
        print(f"wrote {BASELINE} ({len(compared)} benchmarks; {len(declared) - len(compared)} UNSTABLE, reported only)")
        return 0
    if not baseline_path.is_file():
        print(f"::error::{BASELINE} is missing — every benchmark is uncompared; run the module and --write-baseline")
        return 1
    baseline = load_baseline(baseline_path)
    findings, notices = compare(declared, baseline, results)
    print(f"SUBJECTS={len(declared)}")
    if results is not None:
        width = max(len(n) for n in results)
        for name in sorted(results):
            want = baseline.get(name)
            tag = "  (UNSTABLE, not compared)" if name in UNSTABLE else ""
            print(f"  {name:<{width}}  {results[name]:>12.1f} B/op  baseline {want if want is not None else '—':>10}{tag}")
    for line in notices:
        print(f"::notice::{line}")
    for line in findings:
        print(f"::{'error' if enforce else 'warning'}::{line}")
    what = "sources vs baseline" + (" vs results" if results is not None else "")
    if findings:
        print(f"bench-alloc-baseline: {len(findings)} finding(s) ({what})"
              + ("" if enforce else " — ADVISORY mode, not failing the build"))
        return 1 if enforce else 0
    print(f"bench-alloc-baseline: OK — {len(declared)} benchmarks, {what}")
    return 0


# ---------------------------------------------------------------------------------------------
# Self-test: the NEGATIVE cases first. A comparator that has only ever seen an in-tolerance
# result is unfalsified.
# ---------------------------------------------------------------------------------------------

def _jmh(scores: dict[str, float]) -> str:
    return json.dumps([
        {"benchmark": name, "primaryMetric": {"score": 1.0},
         "secondaryMetrics": {METRIC: {"score": score, "scoreUnit": "B/op"}}}
        for name, score in scores.items()
    ])


def _fixture(root: pathlib.Path, methods: list[str], baseline: dict[str, float]) -> None:
    src = root / SOURCES / "com/example"
    src.mkdir(parents=True, exist_ok=True)
    body = "\n".join(f"    @Benchmark\n    fun {m}(): Int = 1\n" for m in methods)
    (src / "DemoBench.kt").write_text(
        "package com.example\n\n/** Mentions @Benchmark fun ghost() in prose only. */\n"
        f"@State(Scope.Benchmark)\nclass DemoBench {{\n{body}}}\n", encoding="utf-8")
    (root / BASELINE).write_text(json.dumps({"benchmarks": baseline}), encoding="utf-8")


def self_test() -> int:
    # The fixtures know nothing of the real UNSTABLE table (whose entries would, correctly, be
    # reported as naming no @Benchmark in a fixture tree), so it is emptied for the run.
    saved = dict(UNSTABLE)
    UNSTABLE.clear()
    try:
        return _self_test()
    finally:
        UNSTABLE.clear()
        UNSTABLE.update(saved)


def _self_test() -> int:
    a, b = "com.example.DemoBench.alpha", "com.example.DemoBench.beta"
    # (label, methods in source, baseline, results or None, exit under --enforce, text the output must carry)
    cases = [
        # --- must go RED, and for the stated reason ---
        ("20 % over baseline is red", ["alpha", "beta"], {a: 1000.0, b: 200.0}, {a: 1200.0, b: 200.0}, 1,
         f"{a}: 1200.0 B/op exceeds baseline 1000.0"),
        ("a benchmark that did not run is red", ["alpha", "beta"], {a: 1000.0, b: 200.0}, {a: 1000.0}, 1,
         f"{b}: missing from the results"),
        ("a removed benchmark leaves a stale baseline entry, which is red", ["alpha"], {a: 1000.0, b: 200.0},
         {a: 1000.0}, 1, f"{b}: stale baseline entry"),
        ("a stale baseline entry is red in static mode too", ["alpha"], {a: 1000.0, b: 200.0}, None, 1,
         f"{b}: stale baseline entry"),
        ("a benchmark with no baseline entry is red", ["alpha", "beta"], {a: 1000.0}, {a: 1000.0, b: 200.0}, 1,
         f"{b}: benchmark has no baseline entry"),
        ("17 B over a small baseline is red (absolute floor is 16 B)", ["alpha", "beta"], {a: 1000.0, b: 40.0},
         {a: 1000.0, b: 57.0}, 1, f"{b}: 57.0 B/op exceeds baseline 40.0"),
        # --- must stay GREEN ---
        ("an in-tolerance result (+4 %) is green", ["alpha", "beta"], {a: 1000.0, b: 200.0},
         {a: 1040.0, b: 200.0}, 0, "bench-alloc-baseline: OK"),
        ("+16 B on a small baseline is green", ["alpha", "beta"], {a: 1000.0, b: 40.0}, {a: 1000.0, b: 56.0}, 0,
         "bench-alloc-baseline: OK"),
        ("an improvement is green and prints a ratchet-down notice", ["alpha", "beta"], {a: 1000.0, b: 200.0},
         {a: 500.0, b: 200.0}, 0, f"::notice::{a}: 500.0 B/op is below baseline 1000.0"),
        ("static mode with matching scope is green", ["alpha", "beta"], {a: 1000.0, b: 200.0}, None, 0,
         "bench-alloc-baseline: OK"),
    ]

    ok = True
    for label, methods, baseline, results, want, text in cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / MODULE).mkdir()
            _fixture(root, methods, baseline)
            results_path = None
            if results is not None:
                results_path = root / "results.json"
                results_path.write_text(_jmh(results), encoding="utf-8")
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                got = run(root, results_path, enforce=True, write=False)
            good = got == want and text in buf.getvalue()
            ok &= good
            print(f"  [{'ok' if good else 'WRONG'}] {label}: exit {got} (want {want})")

    # UNSTABLE: reported, never compared; and the table itself is held to the sources
    u = "com.example.DemoBench.unstable"
    try:
        UNSTABLE[u] = "fixture: bimodal"
        unstable_cases = [
            ("an UNSTABLE benchmark 50 % over is not a finding", ["alpha", "unstable"], {a: 1000.0},
             {a: 1000.0, u: 3000.0}, 0, f"::notice::{u}: 3000.0 B/op reported, NOT compared"),
            ("an UNSTABLE entry naming no @Benchmark is red", ["alpha"], {a: 1000.0}, None, 1,
             f"{u}: UNSTABLE entry names no @Benchmark"),
            ("an UNSTABLE benchmark that also has a baseline entry is red", ["alpha", "unstable"],
             {a: 1000.0, u: 100.0}, None, 1, f"{u}: is UNSTABLE (not compared) yet has a baseline entry"),
        ]
        for label, methods, baseline, results, want, text in unstable_cases:
            with tempfile.TemporaryDirectory() as tmp:
                root = pathlib.Path(tmp)
                (root / MODULE).mkdir()
                _fixture(root, methods, baseline)
                results_path = None
                if results is not None:
                    results_path = root / "results.json"
                    results_path.write_text(_jmh(results), encoding="utf-8")
                buf = io.StringIO()
                with contextlib.redirect_stdout(buf):
                    got = run(root, results_path, enforce=True, write=False)
                good = got == want and text in buf.getvalue()
                ok &= good
                print(f"  [{'ok' if good else 'WRONG'}] {label}: exit {got} (want {want})")
    finally:
        UNSTABLE.clear()

    # advisory mode must not fail the build, and must still print the finding
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        (root / MODULE).mkdir()
        _fixture(root, ["alpha"], {a: 1000.0})
        rp = root / "results.json"
        rp.write_text(_jmh({a: 1200.0}), encoding="utf-8")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            got = run(root, rp, enforce=False, write=False)
        good = got == 0 and "::warning::" in buf.getvalue() and "exceeds baseline" in buf.getvalue()
        ok &= good
        print(f"  [{'ok' if good else 'WRONG'}] advisory mode prints the finding as a warning and exits 0")

        # a result without the gc metric is an error, never a silent zero
        rp.write_text(json.dumps([{"benchmark": a, "secondaryMetrics": {}}]), encoding="utf-8")
        try:
            load_results(rp)
            good = False
        except ValueError:
            good = True
        ok &= good
        print(f"  [{'ok' if good else 'WRONG'}] a result with no {METRIC} raises instead of reading as 0")

        # no baseline file at all is red, never a traceback and never a pass
        (root / BASELINE).unlink()
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            got = run(root, None, enforce=False, write=False)
        good = got == 1 and "is missing" in buf.getvalue()
        ok &= good
        print(f"  [{'ok' if good else 'WRONG'}] a missing baseline file is red even in advisory mode")
        _fixture(root, ["alpha"], {a: 1000.0})

        # the KDoc in the fixture names `@Benchmark fun ghost()` — prose must not be a benchmark
        good = declared_benchmarks(root) == {a}
        ok &= good
        print(f"  [{'ok' if good else 'WRONG'}] @Benchmark in a comment is not a benchmark")

    print("self-test " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--results", help="JMH JSON produced with -prof gc -rf json")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--write-baseline", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    results = pathlib.Path(args.results) if args.results else None
    if results is not None and not results.is_file():
        print(f"::error::{results} not found — the JMH run produced no results; refusing to report a pass")
        return 1
    return run(pathlib.Path(args.root), results, args.enforce, args.write_baseline)


if __name__ == "__main__":
    sys.exit(main())
