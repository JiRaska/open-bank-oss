#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""outbox-claim-sql-ratchet (ADR-0327 D11, advisory in Phase 1, enforced in Phase 4).

A hand-rolled `FOR UPDATE SKIP LOCKED` in any `openbank-*/src/main/**/*.kt` outside
`openbank-libs-runtime` is a finding unless baselined. The baseline is keyed by FILE with the
number of occurrences measured on the day the gate landed, and it may only shrink: a new file,
or a higher count in a baselined file, fails; a baselined file that has healed (lower count, or
gone) is a STALE entry and also fails, so the debt cannot quietly stop being tracked.

Comments and KDoc are stripped before counting. Measured 2026-10-01 on origin/main: a raw grep
matches 70 files, of which 32 are dispatchers, an entity and OutboxStatus.kt whose PROSE
explains the claim — the text-matching-guard lesson in .github/CLAUDE.md. Kotlin block
comments nest, and the stripper mirrors that.

detekt cannot see SQL inside a string template, so this is a script gate, not a detekt rule.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402  (path shim above must run first)

TOKEN = re.compile(r"FOR\s+UPDATE\s+SKIP\s+LOCKED", re.IGNORECASE)
EXEMPT_MODULES = {"openbank-libs-runtime"}

# Measured 2026-10-01 on origin/main by this script (comments stripped): occurrences per file.
BASELINE: dict[str, int] = {
    "openbank-account-service/src/main/kotlin/com/openbank/account/infrastructure/persistence/repository/AccountOutboxRepositoryImpl.kt": 1,
    "openbank-agent-service/src/main/kotlin/com/openbank/agent/infrastructure/audit/JdbcAgentAuditOutbox.kt": 1,
    "openbank-balance-service/src/main/kotlin/com/openbank/balance/infrastructure/persistence/repository/BalanceOutboxRepositoryImpl.kt": 1,
    "openbank-billing-service/src/main/kotlin/com/openbank/billing/infrastructure/outbox/BillingOutboxRepositoryImpl.kt": 1,
    "openbank-card-issuance-service/src/main/kotlin/com/openbank/cardissuance/infrastructure/persistence/repository/CardOutboxRepositoryImpl.kt": 1,
    "openbank-consent-service/src/main/kotlin/com/openbank/consent/infrastructure/persistence/repository/ConsentOutboxRepositoryImpl.kt": 1,
    "openbank-delegation-service/src/main/kotlin/com/openbank/delegation/infrastructure/persistence/repository/DelegationOutboxRepositoryImpl.kt": 1,
    "openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/infrastructure/persistence/repository/DelegatedSpendBindingRepositoryImpl.kt": 1,
    "openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/infrastructure/persistence/repository/DomesticPaymentOutboxRepositoryImpl.kt": 1,
    "openbank-fraud-service/src/main/kotlin/com/openbank/fraud/infrastructure/persistence/FraudOutboxRepositoryImpl.kt": 1,
    "openbank-fx-service/src/main/kotlin/com/openbank/fx/infrastructure/persistence/repository/FxOutboxRepositoryImpl.kt": 1,
    "openbank-incentive-service/src/main/kotlin/com/openbank/incentive/infrastructure/persistence/IncentivePersistence.kt": 1,
    "openbank-interest-service/src/main/kotlin/com/openbank/interest/infrastructure/persistence/repository/InterestOutboxRepositoryImpl.kt": 1,
    "openbank-ledger-service/src/main/kotlin/com/openbank/ledger/infrastructure/persistence/repository/LedgerOutboxRepositoryImpl.kt": 1,
    "openbank-lending-service/src/main/kotlin/com/openbank/lending/infrastructure/persistence/repository/LendingOutboxRepositoryImpl.kt": 1,
    "openbank-sanctions-service/src/main/kotlin/com/openbank/sanctions/infrastructure/persistence/repository/SanctionsOutboxRepositoryImpl.kt": 1,
    "openbank-sca-service/src/main/kotlin/com/openbank/sca/infrastructure/persistence/repository/ScaOutboxRepositoryImpl.kt": 1,
    "openbank-sdd-service/src/main/kotlin/com/openbank/sdd/infrastructure/persistence/repository/SddOutboxRepositoryImpl.kt": 1,
    "openbank-security-scanner/src/main/kotlin/com/openbank/security/infrastructure/persistence/repository/IctIncidentOutboxRepositoryImpl.kt": 1,
    "openbank-sepa-payment/src/main/kotlin/com/openbank/sepa/infrastructure/persistence/repository/SepaPaymentOutboxRepositoryImpl.kt": 1,
    "openbank-standing-order-service/src/main/kotlin/com/openbank/standingorder/infrastructure/persistence/repository/StandingOrderOutboxRepositoryImpl.kt": 1,
    "openbank-swift-service/src/main/kotlin/com/openbank/swift/infrastructure/persistence/repository/SwiftOutboxRepositoryImpl.kt": 1,
    "openbank-transaction-service/src/main/kotlin/com/openbank/transaction/infrastructure/persistence/repository/TransactionOutboxRepositoryImpl.kt": 1,
    "openbank-treasury-service/src/main/kotlin/com/openbank/treasury/infrastructure/persistence/repository/TreasuryOutboxRepositoryImpl.kt": 1,
}


def strip_comments(src: str) -> str:
    """Remove `//` line comments and (nested) `/* */` block comments; string literals are kept."""
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


def count(path: pathlib.Path) -> int:
    return len(TOKEN.findall(strip_comments(path.read_text(encoding="utf-8", errors="replace"))))


def scan(root: pathlib.Path) -> tuple[dict[str, int], int]:
    """Occurrences per file (relative path) and the number of modules examined."""
    found: dict[str, int] = {}
    modules = 0
    for module in sorted(root.glob("openbank-*")):
        src = module / "src" / "main"
        if not src.is_dir() or module.name in EXEMPT_MODULES:
            continue
        modules += 1
        for kt in sorted(src.rglob("*.kt")):
            n = count(kt)
            if n:
                found[kt.relative_to(root).as_posix()] = n
    return found, modules


def check(root: pathlib.Path, baseline: dict[str, int]) -> tuple[list[str], int]:
    found, modules = scan(root)
    findings: list[str] = []
    for path, n in sorted(found.items()):
        want = baseline.get(path, 0)
        if n > want:
            findings.append(f"{path}: {n} FOR UPDATE SKIP LOCKED site(s) > baseline {want} — collapse onto AbstractPanacheOutboxRepository (ADR-0327 D1)")
    for path, want in sorted(baseline.items()):
        n = found.get(path, 0)
        if n < want:
            findings.append(f"{path}: {n} site(s) < baseline {want} — stale baseline, remove or lower the entry in {pathlib.Path(__file__).name}")
    return findings, modules


def self_test() -> int:
    ok = True
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        kt = root / "openbank-a/src/main/kotlin/A.kt"
        kt.parent.mkdir(parents=True)
        kt.write_text(
            "/** prose: the claim is UPDATE ... FOR UPDATE SKIP LOCKED — must not count\n"
            " * /* nested */ still a comment: FOR UPDATE SKIP LOCKED */\n"
            "// FOR UPDATE SKIP LOCKED in a line comment\n"
            'val sql = """SELECT id FROM a_outbox ORDER BY created_at LIMIT :n FOR UPDATE SKIP LOCKED"""\n',
        )
        libs = root / "openbank-libs-runtime/src/main/kotlin/B.kt"
        libs.parent.mkdir(parents=True)
        libs.write_text('val sql = "FOR UPDATE SKIP LOCKED"\n')
        rel = "openbank-a/src/main/kotlin/A.kt"
        cases = [
            ("at baseline is clean (comments stripped, libs-runtime exempt)", {rel: 1}, False),
            ("a new site is flagged", {}, True),
            ("a second site in a baselined file is flagged", {rel: 0}, True),
            ("a stale baseline is flagged", {rel: 2}, True),
            ("a baselined file that is gone is flagged", {rel: 1, "openbank-a/src/main/kotlin/Gone.kt": 1}, True),
        ]
        for name, base, expect in cases:
            findings, subjects = check(root, base)
            if bool(findings) != expect or subjects != 1:
                print(f"SELF-TEST FAIL: {name}: findings={findings} subjects={subjects}")
                ok = False
        if strip_comments("a /* b /* c */ d */ e") != "a  e":
            print("SELF-TEST FAIL: nested block comments must strip as one")
            ok = False
    print("self-test: " + ("pass" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--measure", action="store_true", help="print today's per-file counts as a BASELINE block")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = pathlib.Path(args.root)
    if args.measure:
        found, _ = scan(root)
        for path, n in sorted(found.items()):
            print(f'    "{path}": {n},')
        return 0
    findings, subjects = check(root, BASELINE)
    gatelib.subjects(subjects, "module(s) with src/main scanned for FOR UPDATE SKIP LOCKED")
    for f in findings:
        print(f"  {f}")
    print(f"{len(findings)} finding(s); baseline {len(BASELINE)} file(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
