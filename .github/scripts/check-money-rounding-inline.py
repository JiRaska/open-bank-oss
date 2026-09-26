#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""money-rounding-inline-ratchet (ADR-0318, advisory).

Counts inline `RoundingMode.` / `setScale(` tokens in each money-path service's
`src/main/**/*.kt` (rules.yaml: money_path_services), skipping comment lines. Rounding
belongs in `com.openbank.libs.domain.money.RoundingPolicy`; the counts measured when the
registry landed are the baseline and may only shrink.

Exit 1 when a service exceeds its baseline (a NEW inline site) or has dropped below it
(the baseline is stale — lower it so the ratchet keeps the gain).
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402  (path shim above must run first)

TOKEN = re.compile(r"RoundingMode\.|setScale\(")

# Measured on origin/main 2026-09-26. Services absent here have a baseline of 0.
BASELINE = {
    "openbank-delegation-service": 5,
    "openbank-domestic-payment": 2,
    "openbank-fx-service": 7,
    "openbank-interest-service": 14,
    "openbank-ledger-service": 8,
    "openbank-lending-service": 1,
    "openbank-sca-service": 2,
    "openbank-sdd-service": 2,
    "openbank-transaction-service": 11,
    "openbank-treasury-service": 4,
}


def count(service_dir: pathlib.Path) -> int:
    src = service_dir / "src" / "main"
    if not src.is_dir():
        return 0
    n = 0
    for path in sorted(src.rglob("*.kt")):
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith(("//", "*", "/*")):
                continue
            n += len(TOKEN.findall(line))
    return n


def check(root: pathlib.Path, baseline: dict[str, int]) -> tuple[list[str], int]:
    rules = yaml.safe_load((root / "openbank-libs/governance/rules.yaml").read_text(encoding="utf-8"))
    services = rules["money_path_services"]
    findings: list[str] = []
    for svc in services:
        got, want = count(root / svc), baseline.get(svc, 0)
        if got > want:
            findings.append(f"{svc}: {got} inline RoundingMode./setScale( tokens > baseline {want} — use RoundingPolicy (ADR-0318)")
        elif got < want:
            findings.append(f"{svc}: {got} tokens < baseline {want} — stale baseline, lower it in {pathlib.Path(__file__).name}")
    for svc in baseline:
        if svc not in services:
            findings.append(f"{svc}: baselined but not in money_path_services — remove the entry")
    return findings, len(services)


def self_test() -> int:
    ok = True
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        (root / "openbank-libs/governance").mkdir(parents=True)
        (root / "openbank-libs/governance/rules.yaml").write_text("money_path_services: [openbank-a]\n")
        kt = root / "openbank-a/src/main/kotlin/A.kt"
        kt.parent.mkdir(parents=True)
        kt.write_text("// setScale(2, RoundingMode.HALF_UP) in a comment\nval x = y.setScale(2, RoundingMode.HALF_UP)\n")
        cases = [
            ("at baseline is clean", {"openbank-a": 2}, False),
            ("a new site is flagged", {"openbank-a": 1}, True),
            ("a stale baseline is flagged", {"openbank-a": 3}, True),
            ("an unknown baselined service is flagged", {"openbank-a": 2, "openbank-b": 1}, True),
        ]
        for name, base, expect in cases:
            findings, _ = check(root, base)
            if bool(findings) != expect:
                print(f"SELF-TEST FAIL: {name}: {findings}")
                ok = False
    print("self-test: " + ("pass" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings, subjects = check(pathlib.Path(args.root), BASELINE)
    gatelib.subjects(subjects, "money-path service(s)")
    for f in findings:
        print(f"  {f}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
