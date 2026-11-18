#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Trace-contract heap guard: a module whose test suite boots a *TraceContractIT must declare an
# explicit test JVM heap in its build.gradle.kts (2026-11-18, the loyalty/copilot/referral OOMs).
#
# THE MECHANISM
#   A *TraceContractIT runs under TraceContractProfile (openbank-libs-testing), which exists to
#   give the contract its own Quarkus application instance — its own RecordingSpanExporter, and a
#   dispatcher-free boot. That means the module's test fork carries a SECOND augmentation and app
#   boot where the rest of the suite carries one. Gradle's default test heap is 512 MiB; measured
#   on 2026-11-18 (CI runs 44293166069, 44293183518, 44302517242 and local full suites) the
#   second boot exhausted it: OutOfMemoryError in JPA bootstrap, in the instrumentation agent
#   ("can't create byte array"), and — worst — referral's suite HUNG 45 minutes when the OOMed
#   Kafka testcontainer never came up and the job had to be killed. Every one of these read as an
#   infra flake; none was. The single-class runs were green; only the full suite reproduced it.
#
#   The fix is one line — `tasks.test { maxHeapSize = "2g" }` (the statement-service convention) —
#   which is exactly why it keeps being forgotten: nothing in the module tells an author adding
#   the twentieth trace contract that the nineteenth is what pushed the fork over. This gate is
#   that missing invariant.
#
# WHY IT IS A RATCHET, NOT A FLAT FAIL
#   Six modules on main carry a trace contract but not yet the heap declaration (see BASELINE).
#   That is issue #16805's sweep, not a reason to redden main: baseline entries are tolerated,
#   new occurrences fail, and a baseline entry that GAINS the declaration (or loses its trace
#   contract) is reported as stale so the list shrinks in exactly one direction.
#
# EXIT CODES
#   0  every trace-contract module declares a heap; no stale baseline entries
#   1  a trace-contract module without maxHeapSize that is not in BASELINE, or a stale entry
#   2  the check could not run (tree not found) or BASELINE is malformed (entry naming a path
#      that is not an openbank-* module). Never conflated with 0.
#
# Run:  python3 .github/scripts/check-trace-contract-heap.py [--root .] [--self-test]

import argparse
import pathlib
import re
import sys

import gatelib

# Modules measured on origin/main without a maxHeapSize declaration at the gate's introduction.
# Each entry is one module — a wildcard would make the ratchet blind to a module added later
# (the #3928 lesson from check-kafka-dotted-keys.py). Remove an entry in the same PR that adds
# the declaration; the gate fails until you do.
BASELINE = {
    "openbank-anacredit-service": "#16805",
    "openbank-finrep-service": "#16805",
    "openbank-incentive-service": "#16805",
    "openbank-onboarding-service": "#16805",
    "openbank-security-scanner": "#16805",
    "openbank-vop-service": "#16805",
}

QUARKUS_SERVICE = 'id("openbank.quarkus-service")'


def service_modules(root: pathlib.Path) -> list[pathlib.Path]:
    """Modules using the shared Quarkus service plugin — the ones whose test task this is about."""
    modules = []
    for build in sorted(root.glob("openbank-*/build.gradle.kts")):
        if QUARKUS_SERVICE in build.read_text():
            modules.append(build.parent)
    return modules


def declares_heap(build_kts: pathlib.Path) -> bool:
    return "maxHeapSize" in build_kts.read_text()


def has_trace_contract(module: pathlib.Path) -> bool:
    return any(module.glob("src/test/**/*TraceContractIT*"))


def scan(root: pathlib.Path):
    """Return (findings, stale_baseline_entries, subjects).

    findings                 modules with a trace contract and no heap declaration, not baselined
    stale_baseline_entries   baselined modules that now declare a heap or no longer carry a
                             trace contract — the entry must be removed in the same PR
    """
    findings = []
    stale = []
    subjects = 0
    for module in service_modules(root):
        subjects += 1
        name = module.name
        if not has_trace_contract(module):
            if name in BASELINE:
                stale.append(f"{name}: baseline entry covered — no trace contract IT anymore; remove it")
            continue
        if declares_heap(module / "build.gradle.kts"):
            if name in BASELINE:
                stale.append(f"{name}: baseline entry covered — maxHeapSize now declared; remove it")
            continue
        if name not in BASELINE:
            findings.append(
                f"{name}: *TraceContractIT present but build.gradle.kts declares no maxHeapSize — "
                "the second profile boot exhausts the default 512 MiB test heap (OOM measured "
                "2026-11-18); add tasks.test { maxHeapSize = \"2g\" } and a docs note"
            )
    return findings, stale, subjects


def validate_baseline(root: pathlib.Path) -> list[str]:
    """Every baseline entry must name an existing openbank-* module directory."""
    errors = []
    for name in BASELINE:
        candidate = root / name
        if not candidate.is_dir() or not (candidate / "build.gradle.kts").exists():
            errors.append(f"baseline entry '{name}' does not name a module with build.gradle.kts")
    return errors


def _build_self_test_tree(root: pathlib.Path) -> None:
    """Four modules: heap+trace (allow), trace-no-heap (flag), heap-only (allow), plain (allow).

    Plus a baselined trace-no-heap module (allow while baselined) and a baselined module whose
    trace contract was removed (stale-entry report) — the two BASELINE branches the real scan
    decides on.
    """

    def module(name: str, *, quarkus: bool, heap: bool, trace: bool) -> None:
        mod = root / name
        (mod / "src/test/kotlin/x").mkdir(parents=True)
        plugins = 'id("openbank.quarkus-service")' if quarkus else 'id("java")'
        body = f'plugins {{\n    {plugins}\n}}\n'
        if heap:
            body += '\ntasks.test {\n    maxHeapSize = "2g"\n}\n'
        (mod / "build.gradle.kts").write_text(body)
        if trace:
            (mod / "src/test/kotlin/x/XTraceContractIT.kt").write_text("// fixture\n")

    module("openbank-a-service", quarkus=True, heap=True, trace=True)
    module("openbank-b-service", quarkus=True, heap=False, trace=True)
    module("openbank-c-service", quarkus=True, heap=True, trace=False)
    module("openbank-d-service", quarkus=True, heap=False, trace=False)
    module("openbank-e-service", quarkus=True, heap=False, trace=True)  # in BASELINE below
    module("openbank-f-service", quarkus=True, heap=True, trace=False)  # in BASELINE, trace gone


def self_test() -> int:
    """Drive the REAL scan() over a synthetic tree, not a retyped copy of its classifier."""
    saved_baseline = dict(BASELINE)
    failures = 0
    try:
        BASELINE.clear()
        BASELINE.update({"openbank-e-service": "#0000", "openbank-f-service": "#0000"})
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _build_self_test_tree(root)
            findings, stale, subjects = scan(root)
    finally:
        BASELINE.clear()
        BASELINE.update(saved_baseline)

    checks = [
        ("openbank-b-service flagged (trace, no heap)", any("openbank-b-service" in f for f in findings)),
        ("openbank-a-service allowed (trace + heap)", not any("openbank-a-service" in f for f in findings)),
        ("openbank-c-service allowed (heap, no trace)", not any("openbank-c-service" in f for f in findings)),
        ("openbank-d-service allowed (no trace, no heap)", not any("openbank-d-service" in f for f in findings)),
        ("openbank-e-service allowed while baselined", not any("openbank-e-service" in f for f in findings)),
        (
            "openbank-f-service stale (baseline, trace gone)",
            any("openbank-f-service" in s for s in stale),
        ),
        ("exactly one finding", len(findings) == 1),
        ("exactly one stale entry", len(stale) == 1),
    ]
    for label, ok in checks:
        print(f"{'pass' if ok else 'FAIL'}  {label}")
        failures += 0 if ok else 1
    print(f"subjects: {subjects} (expect 6)")

    print(f"\nself-test: {len(checks) - failures} passed, {failures} failed")
    return 0 if failures == 0 else 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    root = pathlib.Path(args.root)
    if not root.is_dir():
        print(f"::error::tree not found: {root} — the check could not run. This is NOT a pass.")
        return 2

    if args.self_test:
        return self_test()

    shape_errors = validate_baseline(root)
    if shape_errors:
        for e in shape_errors:
            print(f"::error::{e}")
        return 2

    findings, stale, subjects = scan(root)
    gatelib.subjects(subjects, "openbank quarkus-service modules scanned")

    for f in findings:
        print(f"::error::{f}")
    for s in stale:
        print(f"::error::{s}")

    covered = len(BASELINE) - sum(1 for s in stale if "no trace contract" in s)
    print(
        f"trace-contract-heap: {subjects} modules scanned, "
        f"{len(findings)} new finding(s), {len(stale)} stale baseline entry(ies), "
        f"{covered} baseline entry(ies) still open"
    )
    return 1 if (findings or stale) else 0


if __name__ == "__main__":
    sys.exit(main())
