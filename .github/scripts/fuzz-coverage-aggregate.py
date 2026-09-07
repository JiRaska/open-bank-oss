#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Aggregate one api-fuzz run's per-service artifacts into fuzz-coverage.json.

WHY (ADR-0279 #2). A fuzz/DAST job list showing a service is NOT evidence the service
was tested (#6492, the 2026-08-18 run where 6 of 7 "failures" never sent a request).
The durable evidence is the per-service `*-ops*.json` the harness writes
(fuzz-services.sh: selected / auth_blocked / exercised). This job merges those records
plus the prepare step's scope file into ONE machine-readable verdict:

  * inScope   — services the derivation selected for this run
  * excluded  — services skipped OUT LOUD, with the reason (never silently dropped)
  * tested    — in-scope services with a real exercised-surface record (selected > 0)
  * per-service exercised/authBlocked so the console can say what a green is worth

A matrix leg that failed before writing an ops record shows up as `no-evidence` —
that is a harness/infra finding, not an HTTP-surface finding, and it must not count
as tested.

Usage:  fuzz-coverage-aggregate.py --artifacts <dir> --scope <fuzz-scope.json> \
            --run-url <url> --out <fuzz-coverage.json>
        fuzz-coverage-aggregate.py --self-test
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path


def aggregate(artifacts: Path, scope_file: Path, run_url: str) -> dict:
    scope = json.loads(scope_file.read_text()) if scope_file.exists() else {"keep": [], "skipped": []}
    services = []
    for ops in sorted(artifacts.rglob("*-ops*.json")):
        try:
            rec = json.loads(ops.read_text())
        except json.JSONDecodeError:
            continue
        svc = rec.get("service")
        lane = rec.get("lane", "")
        if lane != "authz ON":  # the ON pass is the production-shaped one; OFF is the control
            continue
        selected = int(rec.get("selected") or 0)
        services.append({
            "service": svc,
            "status": "tested" if selected > 0 else "no-evidence",
            "exercised": int(rec.get("exercised") or 0),
            "authBlocked": int(rec.get("auth_blocked") or 0),
        })
    seen = {s["service"] for s in services}
    for svc in scope.get("keep", []):
        if svc not in seen:
            # In scope for this run but left no ops record — boot failure, harness error,
            # or a leg killed before the fuzz pass. NOT evidence about the HTTP surface.
            services.append({"service": svc, "status": "no-evidence",
                             "exercised": 0, "authBlocked": 0})
    tested = sum(1 for s in services if s["status"] == "tested")
    return {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "run": run_url,
        "inScope": len(scope.get("keep", [])),
        "excluded": scope.get("skipped", []),
        # A dispatch-narrowed run is NOT a fleet coverage measurement — the security-kpis
        # collector skips artifacts flagged override (and an old artifact without the flag
        # is treated as NOT overridden, i.e. measured, because every scheduled run derives
        # the full set; the flag exists from the workflow change that introduced it).
        "override": bool(scope.get("override", False)),
        "overrideRequested": scope.get("overrideRequested", []),
        "tested": tested,
        "coveragePct": round(100 * tested / len(services)) if services else 0,
        "totalExercised": sum(s["exercised"] for s in services),
        "services": sorted(services, key=lambda s: s["service"] or ""),
    }


def self_test() -> int:
    import tempfile
    bad = 0
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        (root / "scope.json").write_text(json.dumps({
            "keep": ["openbank-ledger-service", "openbank-balance-service", "openbank-fx-service"],
            "skipped": [["openbank-vop-service", "no postgresql:// URL"]],
        }))
        (root / "a").mkdir()
        (root / "a" / "openbank-ledger-service-ops.json").write_text(json.dumps({
            "service": "openbank-ledger-service", "lane": "authz ON",
            "selected": 40, "auth_blocked": 12, "exercised": 28}))
        (root / "a" / "openbank-ledger-service-ops-authz-off.json").write_text(json.dumps({
            "service": "openbank-ledger-service", "lane": "authz OFF",
            "selected": 40, "auth_blocked": 0, "exercised": 40}))
        (root / "a" / "openbank-balance-service-ops.json").write_text(json.dumps({
            "service": "openbank-balance-service", "lane": "authz ON",
            "selected": 0, "auth_blocked": 0, "exercised": 0}))
        cov = aggregate(root, root / "scope.json", "https://example.test/run/1")
        if cov["inScope"] != 3 or cov["tested"] != 1:
            print(f"self-test FAIL: inScope/tested = {cov['inScope']}/{cov['tested']}, want 3/1")
            bad += 1
        # fx never wrote an ops record -> must surface as no-evidence, not silently vanish
        fx = [s for s in cov["services"] if s["service"] == "openbank-fx-service"]
        if not fx or fx[0]["status"] != "no-evidence":
            print("self-test FAIL: missing ops record must surface as no-evidence"); bad += 1
        # the authz-OFF control pass must not double-count the service
        led = [s for s in cov["services"] if s["service"] == "openbank-ledger-service"]
        if len(led) != 1 or led[0]["exercised"] != 28:
            print("self-test FAIL: authz-OFF pass leaked into the coverage record"); bad += 1
        if cov["excluded"] != [["openbank-vop-service", "no postgresql:// URL"]]:
            print("self-test FAIL: excluded services must carry their reason"); bad += 1
        if cov["override"] is not False or cov["overrideRequested"] != []:
            print("self-test FAIL: a derived (non-override) scope must record override=false"); bad += 1
        # an override scope must propagate the flag — the KPI collector keys off it
        (root / "ovr.json").write_text(json.dumps({
            "keep": ["openbank-ledger-service"], "skipped": [],
            "override": True, "overrideRequested": ["openbank-ledger-service"]}))
        ovr = aggregate(root, root / "ovr.json", "https://example.test/run/2")
        if ovr["override"] is not True or ovr["overrideRequested"] != ["openbank-ledger-service"]:
            print("self-test FAIL: override scope must surface override=true"); bad += 1
    print("fuzz-coverage-aggregate self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--artifacts", type=Path)
    ap.add_argument("--scope", type=Path)
    ap.add_argument("--run-url", default="")
    ap.add_argument("--out", type=Path, default=Path("fuzz-coverage.json"))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    cov = aggregate(args.artifacts, args.scope, args.run_url)
    args.out.write_text(json.dumps(cov, indent=2) + "\n")
    print(f"fuzz-coverage: {cov['tested']}/{cov['inScope']} services tested "
          f"({cov['coveragePct']}%), {cov['totalExercised']} operations exercised, "
          f"{len(cov['excluded'])} excluded -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
