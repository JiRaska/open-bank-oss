#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""ADR-0342 D2: the day-close participant set is declared once and wired everywhere.

Three things must name the same set of participants, or the close is wrong in a way nothing
else notices:

  1. rules.yaml `close_participants` — the declaration (name -> service + principal);
  2. ledger-service application.yaml `openbank.ledger.close.participants` — what the ledger
     WAITS for, and which service-account may report for each;
  3. the services whose src/main constructs a `CloseParticipationReporter` — who actually REPORTS.

A name the ledger waits for that no service reports holds every day in CUTOFF once enforced; a
service that reports a name the ledger does not declare gets 404 and the close passes without it;
a principal mismatch gets 403 for the right service. All three read as green in their own module.

Usage: check-close-participants.py [--root DIR] [--enforce] | --self-test
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

LEDGER_YAML = "openbank-ledger-service/src/main/resources/application.yaml"
RULES_YAML = "openbank-libs/governance/rules.yaml"
REPORTER_RE = re.compile(r"\bCloseParticipationReporter\(")


def declared(root: Path) -> dict[str, dict]:
    rules = yaml.safe_load((root / RULES_YAML).read_text()) or {}
    block = rules.get("close_participants") or {}
    return dict(block.get("participants") or {})


def ledger_participants(root: Path) -> dict[str, str]:
    doc = yaml.safe_load((root / LEDGER_YAML).read_text()) or {}
    node = doc
    for key in ("openbank", "ledger", "close", "participants"):
        node = (node or {}).get(key) if isinstance(node, dict) else None
    return {name: (cfg or {}).get("principal", "") for name, cfg in (node or {}).items()}


def reporting_services(root: Path) -> set[str]:
    found: set[str] = set()
    for module in sorted(p for p in root.iterdir() if p.is_dir() and p.name.startswith("openbank-")):
        if module.name.startswith("openbank-libs"):
            continue
        src = module / "src" / "main"
        if not src.is_dir():
            continue
        for path in src.rglob("*.kt"):
            if REPORTER_RE.search(path.read_text(errors="replace")):
                found.add(module.name)
                break
    return found


def findings(root: Path) -> tuple[list[str], int]:
    out: list[str] = []
    decl = declared(root)
    ledger = ledger_participants(root)
    reporters = reporting_services(root)

    for name in sorted(set(decl) - set(ledger)):
        out.append(f"{name}: declared in rules.yaml but the ledger does not wait for it ({LEDGER_YAML})")
    for name in sorted(set(ledger) - set(decl)):
        out.append(f"{name}: the ledger waits for it but rules.yaml close_participants does not declare it")
    for name in sorted(set(decl) & set(ledger)):
        want = (decl[name] or {}).get("principal", "")
        if want != ledger[name]:
            out.append(f"{name}: principal differs — rules.yaml '{want}', ledger '{ledger[name]}'")
    declared_services = {(cfg or {}).get("service", ""): name for name, cfg in decl.items()}
    for service, name in sorted(declared_services.items()):
        if service and service not in reporters:
            out.append(f"{name}: {service} constructs no CloseParticipationReporter in src/main — nothing reports")
    for service in sorted(reporters - set(declared_services)):
        out.append(f"{service}: constructs a CloseParticipationReporter but is not in rules.yaml close_participants")
    return out, len(decl)


def run(root: Path, enforce: bool) -> int:
    out, subjects = findings(root)
    for line in out:
        print(f"::{'error' if enforce else 'warning'}::close-participants: {line}")
    print(f"SUBJECTS={subjects}")
    print(f"close-participants: {subjects} participant(s) declared, {len(out)} finding(s)")
    if subjects == 0:
        print("::error::close-participants: rules.yaml declares no participants — nothing was measured")
        return 1
    return 1 if (out and enforce) else 0


def _fixture(tmp: Path, ledger_principal: str, reporter: bool, extra_ledger: bool = False) -> Path:
    root = tmp / f"case-{len(list(tmp.iterdir()))}"
    (root / "openbank-libs/governance").mkdir(parents=True)
    (root / "openbank-libs/governance/rules.yaml").write_text(
        "close_participants:\n  participants:\n    interest:\n"
        "      service: openbank-interest-service\n      principal: service-account-openbank-interest\n"
    )
    led = root / "openbank-ledger-service/src/main/resources"
    led.mkdir(parents=True)
    extra = "        lending:\n          principal: service-account-openbank-lending\n" if extra_ledger else ""
    led.joinpath("application.yaml").write_text(
        "openbank:\n  ledger:\n    close:\n      participants:\n        interest:\n"
        f"          principal: {ledger_principal}\n{extra}"
    )
    src = root / "openbank-interest-service/src/main/kotlin"
    src.mkdir(parents=True)
    body = "val r = CloseParticipationReporter(\"interest\", sink, null)\n" if reporter else "val x = 1\n"
    src.joinpath("S.kt").write_text(body)
    return root


def self_test() -> int:
    with tempfile.TemporaryDirectory() as d:
        tmp = Path(d)
        cases = [
            ("aligned", _fixture(tmp, "service-account-openbank-interest", True), 0),
            ("principal drift", _fixture(tmp, "service-account-openbank-billing", True), 1),
            ("no reporter", _fixture(tmp, "service-account-openbank-interest", False), 1),
            ("ledger waits for undeclared", _fixture(tmp, "service-account-openbank-interest", True, True), 1),
        ]
        ok = True
        for label, root, expected in cases:
            got = len(findings(root)[0])
            if (got > 0) != (expected > 0):
                print(f"SELF-TEST FAIL: {label}: expected {'findings' if expected else 'clean'}, got {got}")
                ok = False
        print("self-test: " + ("pass" if ok else "FAIL"))
        return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.getcwd())
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run(Path(args.root), args.enforce)


if __name__ == "__main__":
    sys.exit(main())
