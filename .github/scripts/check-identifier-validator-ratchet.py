#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Ratchet on AD-HOC identifier validation outside openbank-libs-domain (#16748).
#
# The shared identifier validators (RodneCislo, Iban, CzechAccountNumber) are held to the
# official specification by committed spec vectors and a differential test against
# python-stdnum. A hand-rolled copy in a service is held to nothing: it is written from the
# same mental model the shared code was, it never sees the vectors, and when the shared one is
# fixed the copy keeps the old bug. This gate does not force today's copies to migrate; it
# forbids NEW ones. Three shapes are counted per file in `*/src/main/**/*.kt`:
#
#   mod97           an ISO 7064 MOD 97-10 computation (`.mod(97…)`, `% 97`, `97.toBigInteger`,
#                   `BigInteger.valueOf(97)`) — the IBAN / LEI / RF-reference check.
#   iban-regex      a regex opening with `[A-Z]{2}` + two digits — an IBAN shape check.
#   currency-regex  `[A-Z]{3}` — a currency "validation" that accepts every three-letter
#                   string, ISO 4217 or not (libs-domain `CurrencyCode` knows the list).
#   isin-regex      `[A-Z]{2}[A-Z0-9]{9}` — an ISIN shape check, which accepts every wrong check
#                   digit and every non-ISO-3166 prefix.
#   rc-structure-only  `RodneCislo.parse(x)` / `isValid(x)` with ONE argument: structure only, it accepts
#                   a birth number for a date that has not happened. Callers that accept a person use
#                   the overload with `today`; the baseline holds the legitimate structure-only callers.
#   currency-jdk    `java.util.Currency.getInstance` as a check — it knows WITHDRAWN codes
#                   (DEM, FRF, CSK), so it accepts currencies that no longer exist.
#
# The baseline (.github/gates/identifier-validator-baseline.json) maps category -> file ->
# count. A count above its baseline, or a file not in it, FAILS. A count BELOW its baseline also
# fails, asking for the baseline to be lowered in the same PR, so a removed copy cannot be
# silently re-added later under the old allowance.
#
# EXIT CODES
#   0  every count equals its baseline
#   1  a new ad-hoc validator, or a stale (too generous) baseline entry
#   2  the check could not run, or the self-test failed
#
# Run:  python3 .github/scripts/check-identifier-validator-ratchet.py [--root .]
#                [--write-baseline] [--self-test]

import argparse
import json
import pathlib
import re
import sys
import tempfile

PATTERNS = {
    "mod97": re.compile(
        r"(?<![0-9])97\s*\.toBigInteger|BigInteger\.valueOf\(\s*97\s*\)|%\s*97(?![0-9])|\.mod\(\s*97(?![0-9])"
        # a NAMED constant is the same computation: `% MOD_97`, `% MODULUS_97`, `Mod97`, `mod97`
        r"|%\s*\(?[A-Za-z_]*97\b|\b(?:MOD|MODULUS)_?97\b|\b[Mm]od97\b"
    ),
    "iban-regex": re.compile(r"\[A-Z\]\{2\}(?:\[0-9\]|\\\\d)\{2\}"),
    "currency-regex": re.compile(r"\[A-Z\]\{3\}"),
    "isin-regex": re.compile(r"\[A-Z\]\{2\}\[A-Z0-9\]\{9\}"),
    "currency-jdk": re.compile(r"Currency\.getInstance|getAvailableCurrencies"),
    # RodneCislo.parse/isValid with ONE argument checks structure only and does not reject a birthdate
    # in the future; a caller that accepts a person passes `today` (the clock stays with the caller)
    "rc-structure-only": re.compile(r"RodneCislo\.(?:parse|isValid)\(\s*[^,()\n]+(?:\([^()\n]*\))?[^,()\n]*\)"),
}
OWNER = "openbank-libs-domain/"
BASELINE = pathlib.Path(".github/gates/identifier-validator-baseline.json")


def scan(root: pathlib.Path):
    counts = {k: {} for k in PATTERNS}
    scanned = 0
    for p in sorted(root.glob("*/src/main/**/*.kt")):
        rel = p.relative_to(root).as_posix()
        if rel.startswith(OWNER) or "/build/" in rel:
            continue
        scanned += 1
        text = p.read_text(encoding="utf-8", errors="replace")
        for cat, rx in PATTERNS.items():
            n = len(rx.findall(text))
            if n:
                counts[cat][rel] = n
    return counts, scanned


def compare(counts, baseline):
    problems = []
    for cat in PATTERNS:
        have, allowed = counts.get(cat, {}), baseline.get(cat, {})
        for f, n in sorted(have.items()):
            if n > allowed.get(f, 0):
                hint = ("pass `today`: RodneCislo.parse(raw, LocalDate.now(clock)); the one-argument form is structure "
                        "only and accepts a birthdate in the future" if cat == "rc-structure-only" else
                        "validate with openbank-libs-domain (Iban / CzechAccountNumber / RodneCislo / CurrencyCode) "
                        "instead of a hand-rolled copy")
                problems.append(f"NEW {cat}: {f} has {n} (baseline {allowed.get(f, 0)}) — {hint}")
        for f, n in sorted(allowed.items()):
            if have.get(f, 0) < n:
                problems.append(
                    f"STALE {cat}: {f} has {have.get(f, 0)} (baseline {n}) — lower the baseline "
                    "with --write-baseline in this PR"
                )
    return problems


def run(root: pathlib.Path, baseline_path: pathlib.Path) -> int:
    if not baseline_path.is_file():
        print(f"::error::baseline missing: {baseline_path}")
        return 2
    counts, scanned = scan(root)
    if scanned == 0:
        print("::error::scanned zero Kotlin sources — wrong --root?")
        return 2
    problems = compare(counts, json.loads(baseline_path.read_text()))
    for p in problems:
        print(f"::error::{p}")
    total = sum(sum(v.values()) for v in counts.values())
    print(f"identifier-validator ratchet: {total} baselined occurrence(s), {len(problems)} problem(s)")
    print(f"SUBJECTS={scanned}  # Kotlin main sources scanned outside {OWNER}")
    return 1 if problems else 0


def self_test() -> int:
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        src = root / "openbank-x/src/main/kotlin/X.kt"
        src.parent.mkdir(parents=True)
        owner = root / "openbank-libs-domain/src/main/kotlin/Iban.kt"
        owner.parent.mkdir(parents=True)
        owner.write_text("val r = n.mod(97.toBigInteger()); val c = Regex(\"[A-Z]{3}\")\n")
        src.write_text("val CURRENCY = Regex(\"[A-Z]{3}\")\n")
        base = root / "baseline.json"
        base.write_text(json.dumps({"currency-regex": {"openbank-x/src/main/kotlin/X.kt": 1}}))
        cases = [
            ("baselined copy only", None, 0),
            ("new mod-97", "val r = BigInteger(s).mod(BigInteger.valueOf(97))\n", 1),
            ("new percent 97", "var r = (r * 10 + d) % 97\n", 1),
            ("new IBAN regex", 'val IBAN = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")\n', 1),
            ("second currency regex", 'val C2 = Regex("^[A-Z]{3}$")\n', 1),
            ("named-constant mod-97", "for (d in s) r = (r * 10 + d) % MOD_97\n", 1),
            ("named-constant modulus 97", "val x = n % MODULUS_97\n", 1),
            ("new one-arg RodneCislo.parse", "val rc = RodneCislo.parse(raw)\n", 1),
            ("new one-arg RodneCislo.isValid", "if (RodneCislo.isValid(n)) x()\n", 1),
            ("two-arg RodneCislo.parse with a call is fine", "val rc = RodneCislo.parse(raw, LocalDate.now(clock))\n", 0),
            ("not a mod-97: 1997", "val year = 1997\n", 0),
            ("not a mod-97: MOD_970", "val k = MOD_970\n", 0),
        ]
        failed = False
        for name, extra, want in cases:
            src.write_text('val CURRENCY = Regex("[A-Z]{3}")\n' + (extra or ""))
            got = run(root, base)
            ok = got == want
            failed |= not ok
            print(f"self-test {'ok  ' if ok else 'FAIL'} {name}: exit {got}, want {want}")
        src.write_text("val nothing = 1\n")
        got = run(root, base)
        failed |= got != 1
        print(f"self-test {'ok  ' if got == 1 else 'FAIL'} stale baseline: exit {got}, want 1")
    return 2 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--write-baseline", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    root = pathlib.Path(a.root)
    if a.write_baseline:
        counts, _ = scan(root)
        (root / BASELINE).write_text(json.dumps(counts, indent=2, sort_keys=True) + "\n")
        return 0
    return run(root, root / BASELINE)


if __name__ == "__main__":
    sys.exit(main())
