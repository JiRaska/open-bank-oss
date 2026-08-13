#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""check-accounting-clock.py — ADR-0207 D1 gate.

The accounting date must come from ``com.openbank.libs.domain.calendar.AccountingClock``, not
from a wall clock constructed in application code.

WHY THIS EXISTS SEPARATELY FROM check-clock-injection.sh
--------------------------------------------------------
``check-clock-injection.sh`` (ADR-0100 Layer 1) bans ``Instant.now()`` / ``LocalDate.now()`` in the
domain and application layers of money-path services. It did NOT catch the defect ADR-0207 fixes,
and could not have: ``Clock.system(ZoneId.of("Europe/Prague"))`` is *injected-clock-shaped*. It
looks exactly like the thing that gate wants you to do. So ``openbank-ledger-service`` ran two
clock regimes at once — a UTC ``ClockProducer`` bean and a Prague clock built inside
``LedgerService`` / ``YearCloseService`` — and stayed green. The two disagreed about what day it
was for two hours a day, half the year, and nothing detected it because both answers are
individually plausible and no third party knew which was right.

WHAT IS BANNED (domain/ and application/ layers of money-path services)
-----------------------------------------------------------------------
- ``Clock.system(zone)``, ``Clock.systemDefaultZone()`` — binding a clock to a zone chosen here.
- ``ZoneId.of(...)``, ``ZoneId.systemDefault()`` — declaring an accounting time zone locally.
  The bank zone is declared once, as ``AccountingClock.BANK_ZONE``.

The infrastructure layer is deliberately out of scope: ``ClockProducer`` is where a clock is
legitimately constructed, and 45 services have one. This gate is about *deriving an accounting
date from a wall clock*, not about wall-clock time, which stays correct and untouched.

SCOPE IS DERIVED, NOT HAND-KEPT
-------------------------------
The money-path service list is read from ``openbank-libs/governance/rules.yaml``
(``money_path_services``) rather than copied here. A gate whose coverage set is maintained
separately from the artifacts it covers reads as *passing* when the list is short, never as
*unchecked* — ``check-clock-injection.sh`` lists 8 of the 20 money-path services, so 12 are
silently out of its scope today.

COMMENTS ARE STRIPPED
---------------------
Prose about the bug names the banned constructs — this file does, ``AccountingClock``'s KDoc does,
and ``LedgerService`` now carries a comment saying what used to be there. A guard over source text
needs an explicit rule for code-about-code or it flags the very explanation it exists to produce.
Kotlin block comments NEST, so the stripper below tracks depth; a naive stripper closes early on a
KDoc containing ``/*`` and then scans real code as if it were prose.

Usage: python3 .github/scripts/check-accounting-clock.py [--enforce]
Exit:  0 clean (or advisory), 1 violations found with --enforce
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
RULES = REPO / "openbank-libs" / "governance" / "rules.yaml"

TARGET_LAYERS = ("domain", "application")

# Deliberately NOT banned: `Clock.systemUTC()`. ADR-0207 says so explicitly — "The 45 identical
# ClockProducer beans are not the problem and are not in scope to delete. Wall-clock UTC is the
# right answer for timestamps." A guard that also flagged those would report ~15 findings of which
# only a few are the defect, and a gate with that signal-to-noise gets ignored, which is worse than
# not having it. What IS banned is deriving an accounting date from a zone chosen locally.
BANNED = [
    (re.compile(r"\bClock\s*\.\s*system\s*\("), "Clock.system(zone)"),
    (re.compile(r"\bClock\s*\.\s*systemDefaultZone\s*\("), "Clock.systemDefaultZone()"),
    (re.compile(r"\bZoneId\s*\.\s*of\s*\("), "ZoneId.of(...)"),
    (re.compile(r"\bZoneId\s*\.\s*systemDefault\s*\("), "ZoneId.systemDefault()"),
]

# Files allowed to bind a clock to a locally-chosen zone inside domain/application.
#
# This gate is ENFORCED, and these three pre-existing sites are the reason it can be: naming them
# individually, with a reason each, is what makes enforcement honest. The alternative considered was
# shipping the whole gate advisory, which registers as "someone should look" forever and is exactly
# the failure mode `check-advisory-gate-registration.py` exists to prevent.
#
# The check FAILS on a stale entry — a path that no longer exists or no longer violates — so an
# exemption and its fix move together and this debt cannot quietly become permanent. Tracked in
# issue #2963; see it for why each is not a one-line change.
ALLOWLIST: dict[str, str] = {
    "openbank-fx-service/src/main/kotlin/com/openbank/fx/application/usecase/"
    "CnbRateIngestionService.kt": (
        "#2963 — DECIDED (not deferred): this is the ČNB PUBLICATION calendar, not the accounting "
        "day. A fixing is declared by the ČNB for a named business day and published at 14:30 "
        "Prague; the day boundaries that bound validFrom/validTo are the publisher's, not the "
        "bank's books. Same value as AccountingClock.BANK_ZONE today only because the ČNB is a "
        "Prague institution and this entity keeps its books in Prague — binding it to the "
        "accounting zone would encode a coincidence as a dependency. The file states this in place."
    ),
}


def money_path_services() -> list[str]:
    """Read money_path_services from rules.yaml without requiring PyYAML."""
    try:
        import yaml  # type: ignore

        data = yaml.safe_load(RULES.read_text(encoding="utf-8"))
        services = data.get("money_path_services") or []
        return [str(s) for s in services]
    except ImportError:
        # Minimal fallback parser for the flat `key:\n  - item` shape rules.yaml uses.
        services, in_block = [], False
        for line in RULES.read_text(encoding="utf-8").splitlines():
            if line.startswith("money_path_services:"):
                in_block = True
                continue
            if in_block:
                stripped = line.strip()
                if stripped.startswith("- "):
                    services.append(stripped[2:].strip().strip("\"'"))
                elif stripped and not line.startswith((" ", "\t")):
                    break
        return services


def strip_comments(source: str) -> str:
    """Blank out // line comments and /* nested block comments */, preserving line numbers.

    Kotlin block comments nest: `/* a /* b */ c */` closes at the LAST `*/`, not the first.
    Mirroring that is the difference between scanning code and scanning prose about code.
    """
    out = []
    i, n, depth = 0, len(source), 0
    while i < n:
        two = source[i : i + 2]
        if depth == 0 and two == "//":
            while i < n and source[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if two == "/*":
            depth += 1
            out.append("  ")
            i += 2
            continue
        if two == "*/" and depth > 0:
            depth -= 1
            out.append("  ")
            i += 2
            continue
        out.append(source[i] if (depth == 0 or source[i] == "\n") else " ")
        i += 1
    return "".join(out)


def scan(services: list[str], repo: pathlib.Path = None) -> tuple[list[str], set[str]]:
    repo = repo or REPO
    findings: list[str] = []
    hit_paths: set[str] = set()
    for svc in services:
        root = repo / svc / "src" / "main" / "kotlin"
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.kt")):
            parts = path.relative_to(repo).parts
            if not any(layer in parts for layer in TARGET_LAYERS):
                continue
            rel = str(path.relative_to(repo))
            code = strip_comments(path.read_text(encoding="utf-8"))
            for lineno, line in enumerate(code.splitlines(), start=1):
                for pattern, label in BANNED:
                    if pattern.search(line):
                        hit_paths.add(rel)
                        if rel in ALLOWLIST:
                            continue
                        findings.append(f"{rel}:{lineno}: {label} — derive the accounting date from AccountingClock")
    return findings, hit_paths


def self_test() -> int:
    """Falsify the banned-construct scanner.

    The accounting day is a BANK calendar fact, not a wall-clock one: a posting made at 23:30
    Prague on the last business day belongs to that day's books regardless of where the pod
    runs. `Clock.systemDefaultZone()` or a hand-written `ZoneId.of(...)` in domain or
    application code silently substitutes the container's zone, and the resulting misdated
    posting is arithmetically correct — it reconciles, it just lands on the wrong day.

    Nothing downstream catches that: the ledger balances either way.
    """
    import tempfile

    fails: list[str] = []

    def run(files: dict[str, str]) -> tuple[list[str], set[str]]:
        td = tempfile.mkdtemp()
        repo = pathlib.Path(td)
        for rel, body in files.items():
            f = repo / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(body)
        return scan(["openbank-x"], repo)

    def case(label, findings, want_hit):
        got = bool(findings)
        if got != want_hit:
            fails.append(f"{label}: expected finding={want_hit}, got {findings}")

    K = "openbank-x/src/main/kotlin/com/openbank/x"

    # Every banned construct, because missing ONE leaves that spelling free fleet-wide while
    # the gate keeps reporting clean about the others.
    for frag, label in (("Clock.systemDefaultZone()", "systemDefaultZone"),
                        ("Clock.system(ZoneId.of(\"UTC\"))", "Clock.system"),
                        ("ZoneId.systemDefault()", "systemDefault"),
                        ("ZoneId.of(\"Europe/Prague\")", "ZoneId.of")):
        f, _h = run({f"{K}/domain/A.kt": f"val c = {frag}\n"})
        case(f"{label} in domain is FLAGGED", f, True)

    # Spacing variants: `Clock . systemDefaultZone (` is the same call, and a pattern without
    # \s* would miss a reformatted file.
    f, _h = run({f"{K}/domain/A.kt": "val c = Clock . systemDefaultZone ()\n"})
    case("spacing around the call still matches", f, True)

    # The SANCTIONED form must be clean, or the gate blocks the fix it demands.
    f, _h = run({f"{K}/domain/A.kt": "val d = AccountingClock.today()\n"})
    case("AccountingClock is clean", f, False)

    # SCOPE by layer: infrastructure is where a real zone legitimately enters the system.
    f, _h = run({f"{K}/infrastructure/A.kt": "val c = Clock.systemDefaultZone()\n"})
    case("infrastructure is out of scope", f, False)

    # PROSE: the fix comment names the very call it removed.
    f, _h = run({f"{K}/domain/A.kt": "// never call Clock.systemDefaultZone() here\nval d = AccountingClock.today()\n"})
    case("the call named in a comment is not a hit", f, False)

    # The allowlist suppresses a FINDING but must still record the path as a hit — otherwise a
    # stale entry can never be detected, and the list becomes permanent by being invisible.
    only = sorted(ALLOWLIST)[0] if ALLOWLIST else None
    if only:
        svc = only.split("/", 1)[0]
        td = tempfile.mkdtemp(); repo = pathlib.Path(td)
        f = repo / only; f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text("val c = ZoneId.of(\"Europe/Prague\")\n")
        findings, hits = scan([svc], repo)
        if findings:
            fails.append(f"an allowlisted file produced a finding: {findings}")
        if only not in hits:
            fails.append("an allowlisted file was not recorded as a HIT — a stale allowlist "
                         "entry could then never be detected and the list becomes permanent")

    # A live read: the fixtures cannot tell that the money-path list and the layout still
    # resolve in this repo.
    live_services = money_path_services()
    if not live_services:
        fails.append("reading the real rules.yaml produced NO money-path services — this gate "
                     "would report clean about nobody")

    if fails:
        for f_ in fails:
            sys.stderr.write(f"::error::self-test: {f_}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(f"self-test ok: accounting-clock gate is falsifiable "
          f"(10 cases + a live read of {len(live_services)} money-path services)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enforce", action="store_true", help="exit 1 on violations")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    services = money_path_services()
    if not services:
        print("::error::check-accounting-clock: money_path_services is empty in rules.yaml")
        return 1

    findings, hit_paths = scan(services)

    stale = sorted(set(ALLOWLIST) - hit_paths)
    if stale:
        for entry in stale:
            print(f"::error::check-accounting-clock: stale ALLOWLIST entry '{entry}' — no longer violates, remove it")
        return 1

    if findings:
        for finding in findings:
            print(f"::{'error' if args.enforce else 'warning'}::{finding}")
        print(
            f"\n{len(findings)} accounting-clock violation(s) across {len(services)} money-path services.\n"
            "The accounting date is a domain value with its own calendar and cutoff (ADR-0207 D1) — "
            "inject com.openbank.libs.domain.calendar.AccountingClock instead of constructing a "
            "Clock or naming a ZoneId in the domain/application layer.",
        )
        return 1 if args.enforce else 0

    print(f"OK: no accounting-date-from-wall-clock derivation in {len(services)} money-path services.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
