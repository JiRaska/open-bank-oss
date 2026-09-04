#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Contract tests must be adversarial: a changed Pact/contract test file needs a negative case.

WHY (ADR-0279 WS1 #3). Measured 2026-09-03: **104 of 108** `*Pact*Test.kt` files assert only
the happy path — what the provider returns when the caller is authenticated and well-formed.
Nothing asserts the shape that actually hurts when it drifts: the 401/403/404 a wrong or
missing identity must produce. A contract that only covers success is an integration test
wearing a contract's name; the provider can stop enforcing authz entirely and every Pact
stays green.

SCOPE — deliberately the PR diff, not the fleet. The 104-file debt is tracked as a fleet
sweep (see --fleet-report); this gate enforces the convention only on files a PR touches,
so the ratchet moves with work instead of blocking it. A file is "adversarial" when its
source mentions a negative status or an unauthorized marker anywhere — one rejected case
is enough to pin the boundary's existence into the contract.

Usage:  check-adversarial-contract-tests.py [--since origin/main]
        check-adversarial-contract-tests.py --fleet-report   # the debt, as a number
        check-adversarial-contract-tests.py --self-test
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

CONTRACT_FILE = re.compile(r"(Pact|/contract/)[^/]*Test\.kt$")
# A negative-auth signal anywhere in the file: an expected 401/403/404 status, or an
# unauthorized marker in a provider-state/test name. 404 counts: for an id the caller may
# not see, "not found" IS the correct negative contract (enumeration resistance).
NEGATIVE = re.compile(
    r"\b(401|403)\b|UNAUTHORIZED|FORBIDDEN|Unauthorized|unauthorized|"
    r"rejectsUnauthenticated|missingToken|expiredToken"
)

SELFTEST_OK = '''class FooPactConsumerTest {
    fun `rejects when token is missing`() { /* expects 401 */ }
}'''
SELFTEST_BAD = '''class FooPactConsumerTest {
    fun `returns the list`() { /* expects 200 */ }
}'''


def changed_contract_files(since: str) -> list[Path]:
    res = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=AM", f"{since}...HEAD"],
        capture_output=True, text=True, check=True,
    )
    return [Path(f) for f in res.stdout.splitlines()
            if CONTRACT_FILE.search(f) and Path(f).is_file()]


def is_adversarial(path: Path) -> bool:
    return bool(NEGATIVE.search(path.read_text(encoding="utf-8", errors="replace")))


def fleet_report() -> int:
    res = subprocess.run(
        ["git", "ls-files", "*Pact*Test.kt", "*/contract/*Test.kt"],
        capture_output=True, text=True, check=True,
    )
    files = sorted(set(res.stdout.splitlines()))
    debt = [f for f in files if not is_adversarial(Path(f))]
    print(f"adversarial-contract: {len(files)} contract test file(s), "
          f"{len(files) - len(debt)} adversarial, {len(debt)} happy-path-only (fleet debt, #8590 #3)")
    for f in debt:
        print(f"  debt: {f}")
    return 0


def _self_test() -> int:
    bad = 0
    if not NEGATIVE.search(SELFTEST_OK):
        print("self-test FAIL: negative case not detected"); bad += 1
    if NEGATIVE.search(SELFTEST_BAD):
        print("self-test FAIL: happy path misread as adversarial"); bad += 1
    for ok, name in [(True, "x/contract/FooPactConsumerTest.kt"), (True, "x/FooPactTest.kt"),
                     (False, "x/FooTest.kt"), (False, "x/contract/Foo.kt")]:
        if bool(CONTRACT_FILE.search(name)) != ok:
            print(f"self-test FAIL: file match {name}"); bad += 1
    print("adversarial-contract self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default="origin/main")
    ap.add_argument("--fleet-report", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return _self_test()
    if args.fleet_report:
        return fleet_report()

    findings = 0
    for path in changed_contract_files(args.since):
        if is_adversarial(path):
            print(f"  ok: {path}")
        else:
            print(f"::error::{path}: a changed contract test carries no negative case — "
                  f"add the 401/403/404 expectation for a wrong or missing identity "
                  f"(ADR-0279 #3). A contract that only covers success stays green when the "
                  f"provider stops enforcing authz.")
            findings += 1
    print(f"check-adversarial-contract-tests: {findings} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
