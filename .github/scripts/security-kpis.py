#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Aggregate the three ADR-0279 security KPIs into openbank-admin-ui/security-kpis.json.

WHY. The excellence console (runbook 0016) reads read-only endpoints; the three signals
landed by #8619/#8666/#8695/#8700 live in CI logs and standing issues. This collector is
the bridge: it recomputes the numbers FROM THE SAME SCRIPTS the gates run (never a
reimplementation that could drift), and writes one snapshot the admin-ui image bakes at
build time — the `security-report.json` pattern documented in /api/security/route.ts.

Sources:
  * netpol coverage  — check-netpol-coverage.py --root . --enforce (rc + stdout parse)
  * dependency freshness — deps-freshness.py --json (Maven Central; degrade to null
    on outage — a missing number renders as 'unavailable', never as 0)
  * credential inventory — credential-inventory.py --inventory (counts parsed from stdout)

Usage:  security-kpis.py [--out openbank-admin-ui/security-kpis.json] [--skip-freshness]
        security-kpis.py --self-test
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
from pathlib import Path

OUT_DEFAULT = Path("openbank-admin-ui/security-kpis.json")


def run_json(cmd: list[str]) -> tuple[int, str]:
    res = subprocess.run(cmd, capture_output=True, text=True)
    return res.returncode, res.stdout


def collect_netpol() -> dict:
    rc, out = run_json([sys.executable, ".github/scripts/check-netpol-coverage.py",
                        "--root", ".", "--enforce"])
    m = re.search(r"netpol-coverage: (\d+)/(\d+) components.*\((\d+)%\)", out)
    if not m:
        return {"available": False, "reason": "collector output unparsable"}
    return {"available": True, "covered": int(m.group(1)), "total": int(m.group(2)),
            "coveragePct": int(m.group(3)), "gateGreen": rc == 0}


def collect_freshness(tmp: Path) -> dict:
    rc, out = run_json([sys.executable, ".github/scripts/deps-freshness.py",
                        "--json", str(tmp / "fresh.json")])
    f = tmp / "fresh.json"
    if rc != 0 or not f.exists():
        return {"available": False, "reason": "collector failed"}
    data = json.loads(f.read_text())
    if data.get("fleet") is None:
        return {"available": False, "reason": "no scored modules (Maven Central outage?)"}
    return {"available": True, "fleetScore": data["fleet"],
            "scoredModules": data["scored_modules"], "unknownModules": data["unknown_modules"]}


def collect_credentials() -> dict:
    rc, out = run_json([sys.executable, ".github/scripts/credential-inventory.py",
                        "--inventory", "--today", dt.date.today().isoformat()])
    m = re.search(r"credential-inventory: (\d+) ExternalSecrets, (\d+) static, "
                  r"(\d+) with deadline, (\d+) overdue, (\d+) undeclared", out)
    if not m:
        return {"available": False, "reason": "collector output unparsable"}
    total, static, declared, overdue, undeclared = map(int, m.groups())
    return {"available": True, "totalSecrets": total, "staticSecrets": static,
            "withDeadline": declared, "overdue": overdue, "undeclared": undeclared,
            "overdueFound": rc != 0}


def self_test() -> int:
    bad = 0
    # The parse regexes must survive the real scripts' output shape — pin them on the
    # exact strings the scripts print today (a drift here means the console goes blank,
    # which is exactly what this test exists to catch first).
    netpol_line = "netpol-coverage: 59/73 components carry the generated ingress policy (81%); 14 uncovered"
    if not re.search(r"netpol-coverage: (\d+)/(\d+) components.*\((\d+)%\)", netpol_line):
        print("self-test FAIL: netpol regex"); bad += 1
    cred_line = ("credential-inventory: 165 ExternalSecrets, 158 static, 0 with deadline, "
                 "0 overdue, 158 undeclared")
    m = re.search(r"credential-inventory: (\d+) ExternalSecrets, (\d+) static, "
                  r"(\d+) with deadline, (\d+) overdue, (\d+) undeclared", cred_line)
    if not m or m.group(2) != "158":
        print("self-test FAIL: credential regex"); bad += 1
    print("security-kpis self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(OUT_DEFAULT))
    ap.add_argument("--skip-freshness", action="store_true",
                    help="offline smoke: skip the Maven Central collector")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    import tempfile
    with tempfile.TemporaryDirectory() as td:
        freshness = ({"available": False, "reason": "skipped"} if args.skip_freshness
                     else collect_freshness(Path(td)))
        snapshot = {
            "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "netpol": collect_netpol(),
            "freshness": freshness,
            "credentials": collect_credentials(),
        }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(snapshot, indent=2) + "\n")
    avail = sum(1 for k in ("netpol", "freshness", "credentials") if snapshot[k]["available"])
    print(f"security-kpis: {avail}/3 collectors available -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
