#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A module that asserts a trace contract must also EMIT its evidence, or its trace column is grey.

WHY THIS EXISTS
---------------
The Test Intelligence `trace` column is filled from exactly one wire: `TraceContract.verifiedAs(id)`
in `openbank-libs-testing` prints `OPENBANK_TRACE_CONTRACT_V1:<id>` to stdout, Gradle keeps that in
the JUnit XML `<system-out>`, and `collect-test-run-evidence.py` projects every marker it finds in
`build/test-results` into a `trace` evidence row. The 2026-10-17 Test Intelligence audit read "only
one Kotlin file emits the marker" as a gap; it is the design — that one file is the library — and
the scheduled Services CI run of the same day carried a `passed` trace row for every module checked
(balance, account, sanctions, interest, fx). What the audit was right to fear is the shape this gate
closes: a test that chains `.requiresSpan(...)`, `.hasNoErrorSpan()` and friends but never calls
`.verifiedAs(...)` asserts the trace and reports nothing, so the module reads as having no trace
evidence at all — green tests, grey column, and nothing in CI to say why.

THE RULE
--------
Every module (`openbank-*/`) whose `src/test/kotlin` uses the trace-contract assertion API must have
at least one `.verifiedAs("<id>")` in that tree, and every id must match the collector's regex
(`[a-z0-9][a-z0-9._-]{0,63}` — anything else is `require`d to fail at runtime, so a wrong id is a
test that cannot pass). `openbank-libs-testing` is exempt: its own unit test exercises the
rejection path on purpose.

Usage:  check-trace-contract-evidence.py
        check-trace-contract-evidence.py --self-test
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[2]
ASSERTION_RE = re.compile(r"\.(requiresSpan|requiresAttribute|requiresSameTrace|hasNoErrorSpan|spanCompletesWithin)\(")
VERIFIED_RE = re.compile(r"\.verifiedAs\(\s*\"([^\"]*)\"")
# Must stay identical to collect-test-run-evidence.py's marker regex and TraceContract's TRACE_CONTRACT_ID.
CONTRACT_ID_RE = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")
EXEMPT = {"openbank-libs-testing"}  # the library's own unit test deliberately exercises the invalid-id path
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")


def strip_comments(text: str) -> str:
    return LINE_COMMENT_RE.sub("", BLOCK_COMMENT_RE.sub("", text))


def evaluate(root: pathlib.Path) -> tuple[list[str], int]:
    """Findings and the number of modules that use the assertion API (the gate's subjects)."""
    findings: list[str] = []
    subjects = 0
    for module in sorted(p for p in root.glob("openbank-*") if p.is_dir()):
        if module.name in EXEMPT:
            continue
        tests = module / "src" / "test" / "kotlin"
        if not tests.is_dir():
            continue
        asserting: list[pathlib.Path] = []
        ids: list[tuple[pathlib.Path, str]] = []
        for kt in tests.rglob("*.kt"):
            code = strip_comments(kt.read_text(errors="ignore"))
            if ASSERTION_RE.search(code):
                asserting.append(kt)
            ids += [(kt, cid) for cid in VERIFIED_RE.findall(code)]
        if not asserting:
            continue
        subjects += 1
        rel = asserting[0].relative_to(root)
        if not ids:
            findings.append(
                f"::error file={rel}::{module.name}: {len(asserting)} test file(s) assert a TraceContract "
                f"but none calls .verifiedAs(\"<id>\"), so no OPENBANK_TRACE_CONTRACT_V1 marker is ever "
                f"printed and the module's Test Intelligence trace column stays grey. Add "
                f".verifiedAs(\"<module>-<operation>\") as the LAST call of the chain."
            )
        for kt, cid in ids:
            if not CONTRACT_ID_RE.fullmatch(cid):
                findings.append(
                    f"::error file={kt.relative_to(root)}::{module.name}: contract id {cid!r} does not match "
                    f"[a-z0-9][a-z0-9._-]{{0,63}}; TraceContract.verifiedAs rejects it at runtime and the "
                    f"collector would not read it."
                )
    return findings, subjects


def self_test() -> int:
    ok = True

    def fixture(files: dict[str, str]) -> pathlib.Path:
        root = pathlib.Path(tempfile.mkdtemp(prefix="trace-evidence-"))
        for rel, body in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body)
        return root

    chain = 'exporter.contract()\n    .requiresSpan("POST /x")\n    .hasNoErrorSpan()\n'
    good = chain + '    .verifiedAs("x-service-create")\n'

    def case(label: str, files: dict[str, str], expected: int, subjects: int) -> None:
        nonlocal ok
        got, seen = evaluate(fixture(files))
        good_case = len(got) == expected and seen == subjects
        ok = ok and good_case
        print(f"  [{'ok ' if good_case else 'FAIL'}] {label}: findings={len(got)} expected={expected} "
              f"subjects={seen} expected={subjects}")

    it = "openbank-x-service/src/test/kotlin/com/openbank/x/XTraceContractIT.kt"
    case("assertions + verifiedAs — must pass", {it: good}, 0, 1)
    case("assertions WITHOUT verifiedAs — MUST flag (grey column)", {it: chain}, 1, 1)
    case("verifiedAs only inside a comment — MUST flag",
         {it: chain + '    // .verifiedAs("x-service-create")\n'}, 1, 1)
    case("verifiedAs in a sibling file of the same module — must pass",
         {it: chain, "openbank-x-service/src/test/kotlin/com/openbank/x/Other.kt": good}, 0, 1)
    case("invalid contract id (uppercase, spaces) — MUST flag",
         {it: chain + '    .verifiedAs("Customer supplied / trace id")\n'}, 1, 1)
    case("module with no trace assertions — not a subject",
         {"openbank-y-service/src/test/kotlin/Y.kt": "class Y"}, 0, 0)
    case("libs-testing exempt even without verifiedAs",
         {"openbank-libs-testing/src/test/kotlin/T.kt": chain}, 0, 0)
    real, subjects = evaluate(REPO)
    live_ok = subjects >= 20 and not real
    ok = ok and live_ok
    print(f"  [{'ok ' if live_ok else 'FAIL'}] real tree: {subjects} modules assert a trace contract, {len(real)} finding(s)")
    print("self-test:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings, subjects = evaluate(REPO)
    print(f"SUBJECTS={subjects}  # modules whose tests assert a TraceContract")
    for line in findings:
        print(line)
    if findings:
        return 1
    print(f"trace-contract evidence: all {subjects} asserting modules emit a verifiedAs marker")
    return 0


if __name__ == "__main__":
    sys.exit(main())
