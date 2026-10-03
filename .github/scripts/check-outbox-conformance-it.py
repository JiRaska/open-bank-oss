#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""outbox-conformance-it-present (ADR-0327 D9, advisory in Phase 1, enforced in Phase 4).

Every module whose `src/main` extends `AbstractOutboxDispatcher` must carry, under `src/test`,
a class extending `OutboxDispatchConformanceIT` (openbank-libs-testing). Detection is by the
SUPERTYPE — `: OutboxDispatchConformanceIT()` on a non-comment line — never by file name, the
Pact "grep for the word contract" lesson: `*OutboxDispatchIT.kt` files exist in five services
that carry a local harness and inherit nothing from the kit.

The subject set is DERIVED from the dispatcher supertype, not hand-kept, so a new dispatcher
enters the check the day it lands. The baseline is the modules that were uncovered when the
gate landed, by name; it may only shrink, and fails in both directions: an uncovered module the
baseline does not name, and a baselined module that has since become covered (stale entry).
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402  (path shim above must run first)

DISPATCHER_SUPERTYPE = re.compile(r":\s*AbstractOutboxDispatcher\s*\(")
CONFORMANCE_SUPERTYPE = re.compile(r":\s*OutboxDispatchConformanceIT\s*\(\s*\)")

# Measured 2026-10-01 on origin/main: 38 dispatcher owners, 1 covered (ledger, ADR-0327 finding 8).
# Each entry leaves in that service's Phase 2/3 migration PR (#11652).
BASELINE_UNCOVERED: set[str] = {
    "openbank-account-service",
    "openbank-balance-service",
    "openbank-billing-service",
    "openbank-card-issuance-service",
    "openbank-consent-service",
    "openbank-delegation-service",
    "openbank-domestic-payment",
    "openbank-fraud-service",
    "openbank-fx-service",
    "openbank-incentive-service",
    "openbank-interest-service",
    "openbank-lending-service",
    "openbank-sanctions-service",
    "openbank-sca-service",
    "openbank-sdd-service",
    "openbank-security-scanner",
    "openbank-sepa-payment",
    "openbank-standing-order-service",
    "openbank-swift-service",
    "openbank-transaction-service",
    "openbank-treasury-service",
}


def strip_comments(src: str) -> str:
    out: list[str] = []
    i, n, depth = 0, len(src), 0
    while i < n:
        two = src[i:i + 2]
        if depth == 0 and two == "//":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if two == "/*":
            depth += 1
            i += 2
            continue
        if depth > 0 and two == "*/":
            depth -= 1
            i += 2
            continue
        if depth == 0:
            out.append(src[i])
        i += 1
    return "".join(out)


def matches(tree: pathlib.Path, pattern: re.Pattern[str]) -> bool:
    if not tree.is_dir():
        return False
    return any(pattern.search(strip_comments(kt.read_text(encoding="utf-8", errors="replace"))) for kt in tree.rglob("*.kt"))


def scan(root: pathlib.Path) -> tuple[set[str], set[str]]:
    """(dispatcher owners, owners with a conformance IT)."""
    owners: set[str] = set()
    covered: set[str] = set()
    for module in sorted(root.glob("openbank-*")):
        if matches(module / "src" / "main", DISPATCHER_SUPERTYPE):
            owners.add(module.name)
            if matches(module / "src" / "test", CONFORMANCE_SUPERTYPE):
                covered.add(module.name)
    return owners, covered


def check(root: pathlib.Path, baseline: set[str]) -> tuple[list[str], int]:
    owners, covered = scan(root)
    findings: list[str] = []
    for m in sorted(owners - covered - baseline):
        findings.append(f"{m}: extends AbstractOutboxDispatcher but no test extends OutboxDispatchConformanceIT (ADR-0327 D9)")
    for m in sorted(baseline & covered):
        findings.append(f"{m}: baselined as uncovered but now carries a conformance IT — remove the entry from {pathlib.Path(__file__).name}")
    for m in sorted(baseline - owners):
        findings.append(f"{m}: baselined but no longer extends AbstractOutboxDispatcher — remove the entry from {pathlib.Path(__file__).name}")
    return findings, len(owners)


def self_test() -> int:
    ok = True
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)

        def write(rel: str, text: str) -> None:
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)

        write("openbank-a/src/main/kotlin/A.kt", "class ADispatcher(m: DomainMetrics) : AbstractOutboxDispatcher(m)\n")
        write("openbank-a/src/test/kotlin/AIT.kt", "class AOutboxConformanceIT : OutboxDispatchConformanceIT() {}\n")
        write("openbank-b/src/main/kotlin/B.kt", "class BDispatcher(m: DomainMetrics) : AbstractOutboxDispatcher(m)\n")
        # Named like the kit, inherits nothing from it — and a comment naming the supertype.
        write("openbank-b/src/test/kotlin/BOutboxDispatchIT.kt", "// mirrors : OutboxDispatchConformanceIT() but local\nclass BOutboxDispatchIT {}\n")
        write("openbank-c/src/main/kotlin/C.kt", "// : AbstractOutboxDispatcher( in prose only\nclass C\n")
        cases = [
            ("covered a, baselined b is clean", {"openbank-b"}, False),
            ("uncovered b without a baseline is flagged (file name and comment do not count)", set(), True),
            ("a baselined module that became covered is stale", {"openbank-a", "openbank-b"}, True),
            ("a baselined module that is no dispatcher owner is stale", {"openbank-b", "openbank-c"}, True),
        ]
        for name, base, expect in cases:
            findings, subjects = check(root, base)
            if bool(findings) != expect or subjects != 2:
                print(f"SELF-TEST FAIL: {name}: findings={findings} subjects={subjects}")
                ok = False
    print("self-test: " + ("pass" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--measure", action="store_true", help="print today's uncovered owners")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = pathlib.Path(args.root)
    if args.measure:
        owners, covered = scan(root)
        print(f"owners={len(owners)} covered={sorted(covered)}")
        for m in sorted(owners - covered):
            print(f'    "{m}",')
        return 0
    findings, subjects = check(root, BASELINE_UNCOVERED)
    gatelib.subjects(subjects, "module(s) extending AbstractOutboxDispatcher")
    for f in findings:
        print(f"  {f}")
    print(f"{len(findings)} finding(s); baseline {len(BASELINE_UNCOVERED)} uncovered module(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
