#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A service that disables the scheduler in tests must exercise it in at least one test.

Why this exists (issue #2204, #2187)
------------------------------------
`quarkus.scheduler.enabled: false` under `%test` is a reasonable default — it stops crons
firing during unrelated tests. Its cost is that the scheduler class becomes structurally
invisible: what remains is direct method calls, and a direct call SUPPLIES THE VERY VERT.X
CONTEXT THE REAL SCHEDULER DOES NOT. A test that calls `scheduler.sweep()` therefore passes
against code that can never work as a cron.

That is not hypothetical. #2187 found five `@Scheduled` methods — three money-path — that had
**never executed**, in services whose tests were green throughout: a plain (non-`suspend`)
`@Scheduled` body of `runBlocking { … }` throws `HR000068` on the first reactive Panache call
and aborts, silently. `check-no-runblocking-in-scheduled.py` now catches that ONE defect shape.
It cannot catch the next one. Only running the real cron can.

So the rule is about the test, not the code: if a service disables the scheduler under `%test`
AND has an `@Scheduled` method, some test must turn it back on. The fixes for #2187 did exactly
that — `LedgerSchedulerVertxContextIT`, `BillingCycleSweepVertxContextIT`,
`StandingOrderExecutionSweepIT` set `quarkus.scheduler.enabled=true` in a `@TestProfile` and
shrink the cron expression.

Detection is deliberately narrow
--------------------------------
A test counts only if it sets `quarkus.scheduler.enabled` to `true`. An earlier, looser sweep
for the string `scheduler.enabled` scored `openbank-card-issuance-service` as covered on
`scheduler(enabled = false).enforceRetention()` — a direct method call with an unrelated
feature flag, i.e. precisely the shape this check exists to reject. Comments are stripped for
the same reason: prose describing the setting is not the setting.

Usage:  check-scheduler-exercised.py [--enforce] [--selftest]
Advisory by default (repo convention). BASELINE holds the services that predate the rule; it
can only shrink, and a stale entry — a service that now IS covered — is itself reported.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]

# The scheduler turned back ON, in any of the spellings a @TestProfile uses.
REENABLES = re.compile(
    r"""["']quarkus\.scheduler\.enabled["']\s*(?:to|=|:)\s*["']true["']"""
    r"""|%test\.quarkus\.scheduler\.enabled\s*[:=]\s*true""",
)
DISABLED_NESTED = re.compile(r"scheduler\s*:\s*\n\s+enabled\s*:\s*false")
DISABLED_FLAT = re.compile(r"%test\.quarkus\.scheduler\.enabled\s*[:=]\s*false")
TEST_BLOCK = re.compile(r'^"?%test"?\s*:\s*\n(.*?)(?=^\S|\Z)', re.M | re.S)

# Services with a @Scheduled method that disable the scheduler in tests and do not re-enable it
# anywhere. Measured 2026-07-26 (#2204). This list may only SHRINK.
BASELINE = {
    "openbank-aml-service",
    "openbank-card-issuance-service",
    "openbank-interest-service",
    "openbank-onboarding-service",
    "openbank-sanctions-service",
    "openbank-sdd-service",
}


def strip_comments(text: str) -> str:
    return "\n".join(re.sub(r"#.*", "", line) for line in text.splitlines())


def strip_kt_comments(text: str) -> str:
    text = re.sub(r"//.*", "", text)
    # Kotlin block comments NEST; a non-greedy /* ... */ closes early on a KDoc containing `/*`.
    out, depth, i = [], 0, 0
    while i < len(text):
        if text.startswith("/*", i):
            depth += 1
            i += 2
        elif text.startswith("*/", i) and depth:
            depth -= 1
            i += 2
        else:
            if not depth:
                out.append(text[i])
            i += 1
    return "".join(out)


def scan() -> tuple[set[str], set[str], int]:
    """(services that disable it and have @Scheduled, services whose tests re-enable it, walked)

    The walked count is the corpus. Both returned SETS are filtered subsets, and the existing
    zero-guard below only catches the total collapse — a scan that reached 3 of 61 services
    would still find a couple of disablers and read as a fleet in good order.
    """
    disables_with_scheduled, reenables = set(), set()
    walked = 0
    for svc_dir in sorted(REPO.glob("openbank-*/")):
        svc = svc_dir.name
        app = svc_dir / "src/main/resources/application.yaml"
        if not app.is_file():
            continue
        walked += 1
        try:
            conf = strip_comments(app.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError):
            continue
        block = TEST_BLOCK.search(conf)
        disabled = bool(DISABLED_FLAT.search(conf)) or bool(block and DISABLED_NESTED.search(block.group(1)))
        if not disabled:
            continue
        main_src = svc_dir / "src/main"
        if not any("@Scheduled" in p.read_text(encoding="utf-8", errors="ignore")
                   for p in main_src.rglob("*.kt")):
            continue  # nothing to exercise
        disables_with_scheduled.add(svc)
        test_src = svc_dir / "src/test"
        if any(REENABLES.search(strip_kt_comments(p.read_text(encoding="utf-8", errors="ignore")))
               for p in test_src.rglob("*.kt")):
            reenables.add(svc)
    return disables_with_scheduled, reenables, walked


def selftest() -> int:
    """The rule's own examples, both directions — a gate that only ever passes is unfalsified."""
    ok = True
    cases = [
        ('"quarkus.scheduler.enabled" to "true"', True, "the @TestProfile idiom the #2187 fixes use"),
        ("%test.quarkus.scheduler.enabled=true", True, "the flat spelling"),
        ("scheduler(enabled = false).enforceRetention()", False,
         "a direct call with an unrelated flag — the card-issuance false positive"),
        ('// sets "quarkus.scheduler.enabled" to "true" one day', False, "prose is not the setting"),
    ]
    for src, want, why in cases:
        got = bool(REENABLES.search(strip_kt_comments(src)))
        if got != want:
            print(f"selftest FAIL: {src!r} -> {got}, want {want} ({why})")
            ok = False
    disabled, _, _ = scan()
    for must in ("openbank-ledger-service", "openbank-billing-service"):
        if must not in disabled:
            print(f"selftest FAIL: {must} disables the scheduler per #2204 but the scan missed it")
            ok = False
    if not ok:
        return 1
    print(f"selftest ok: {len(cases)} pattern cases both directions + the two services #2204 names")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    disabled, reenabled, walked = scan()
    gatelib.subjects(walked, "services with an application.yaml walked")
    if not disabled:
        print("::error::check-scheduler-exercised: found no service disabling the scheduler — "
              "the scan is broken, not the fleet clean.")
        return 1

    uncovered = disabled - reenabled
    print(f"check-scheduler-exercised: {len(disabled)} service(s) disable the scheduler under %test "
          f"and have @Scheduled; {len(reenabled)} exercise it in a test.")

    fixed = BASELINE - uncovered
    if fixed:
        print("::warning::check-scheduler-exercised: BASELINE is stale — these now exercise the "
              f"scheduler and must be removed from it: {', '.join(sorted(fixed))}")

    new = uncovered - BASELINE
    for svc in sorted(uncovered):
        print(f"  {'NEW  ' if svc in new else 'known'}  {svc}")

    if not new:
        return 0
    detail = (
        "A service disabling the scheduler under %test with no test that re-enables it has a "
        "@Scheduled method NOTHING has ever run as a cron. A direct method call cannot substitute: "
        "it supplies the Vert.x context the scheduler does not, so it passes against code that can "
        "never work (#2187 found five such jobs, three money-path, that had never executed). Add a "
        "@TestProfile setting quarkus.scheduler.enabled=true with a shrunken cron, as "
        "LedgerSchedulerVertxContextIT does."
    )
    marker = "error" if args.enforce else "warning"
    print(f"::{marker}::check-scheduler-exercised: {len(new)} service(s) NOT in the baseline. {detail}")
    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
