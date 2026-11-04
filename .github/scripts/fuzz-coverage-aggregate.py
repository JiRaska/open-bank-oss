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
  * tested    — in-scope services where some pass exercised handler logic (exercised > 0)
  * auth-blocked — every operation answered only 401/403 in every pass: NOT RUN
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
    # Both passes are read. authz ON is the auth-enforcement property (nearly every operation
    # answers 401/403 there BY DESIGN); authz OFF is the pass that reaches handler logic.
    # Counting ON alone made a service whose 52 operations all answered 401 read "tested"
    # with exercised=0 (delegation, sanctions, fraud in run 41631915107).
    lanes: dict = {}
    for ops in sorted(artifacts.rglob("*-ops*.json")):
        try:
            rec = json.loads(ops.read_text())
        except json.JSONDecodeError:
            continue
        svc = rec.get("service")
        lane = rec.get("lane", "")
        if not svc or lane not in ("authz ON", "authz OFF"):
            continue
        lanes.setdefault(svc, {})[lane] = rec
    services = []
    for svc, by_lane in lanes.items():
        def num(lane: str, key: str, by_lane=by_lane) -> int:
            return int((by_lane.get(lane) or {}).get(key) or 0)
        selected = max(num("authz ON", "selected"), num("authz OFF", "selected"))
        exercised_on = num("authz ON", "exercised")
        exercised_off = num("authz OFF", "exercised")
        exercised = max(exercised_on, exercised_off)
        # Operations no pass ever got past 401/403 ran NO handler logic: they are NOT RUN,
        # never passed. With an OFF record that is its residual auth_blocked; without one,
        # every ON-blocked operation is unexercised.
        if "authz OFF" in by_lane:
            not_run = num("authz OFF", "auth_blocked")
        else:
            not_run = num("authz ON", "auth_blocked")
        if selected == 0:
            status = "no-evidence"
        elif exercised == 0:
            status = "auth-blocked"
        else:
            status = "tested"
        services.append({
            "service": svc,
            "status": status,
            "selected": selected,
            "exercised": exercised,
            "exercisedAuthOn": exercised_on,
            "exercisedAuthOff": exercised_off,
            "authBlocked": not_run,
            "notRun": not_run,
        })
    seen = {s["service"] for s in services}
    for svc in scope.get("keep", []):
        if svc not in seen:
            # In scope for this run but left no ops record — boot failure, harness error,
            # or a leg killed before the fuzz pass. NOT evidence about the HTTP surface.
            services.append({"service": svc, "status": "no-evidence", "selected": 0,
                             "exercised": 0, "exercisedAuthOn": 0, "exercisedAuthOff": 0,
                             "authBlocked": 0, "notRun": 0})
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
        # Operations reported NOT RUN (auth-blocked in every pass) — a reader must show these
        # as untested, never fold them into a green.
        "totalNotRun": sum(s["notRun"] for s in services),
        "authBlockedServices": sum(1 for s in services if s["status"] == "auth-blocked"),
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
        # both passes merge into ONE row per service; OFF is what reaches the handlers
        led = [s for s in cov["services"] if s["service"] == "openbank-ledger-service"]
        if len(led) != 1 or led[0]["exercised"] != 40 or led[0]["notRun"] != 0:
            print(f"self-test FAIL: ledger must be one row, exercised 40, notRun 0: {led}"); bad += 1
        # known-positives: all-401 ON pass with no OFF record (the delegation shape of run
        # 41631915107), blocked in BOTH passes (psd2), and a residual not-run count (party)
        (root / "b").mkdir()
        recs = [
            ("openbank-delegation-service", "", "authz ON", 52, 52, 0),
            ("openbank-psd2-service", "", "authz ON", 17, 17, 0),
            ("openbank-psd2-service", "-authz-off", "authz OFF", 17, 17, 0),
            ("openbank-party-service", "", "authz ON", 32, 32, 0),
            ("openbank-party-service", "-authz-off", "authz OFF", 32, 4, 28),
        ]
        for svc, sfx, lane, sel, blk, exe in recs:
            (root / "b" / f"{svc}-ops{sfx}.json").write_text(json.dumps({
                "service": svc, "lane": lane, "selected": sel,
                "auth_blocked": blk, "exercised": exe}))
        (root / "b.json").write_text(json.dumps({"keep": sorted({r[0] for r in recs})}))
        ab = {s["service"]: s for s in aggregate(root / "b", root / "b.json", "x")["services"]}
        dele = ab["openbank-delegation-service"]
        if dele["status"] != "auth-blocked" or dele["notRun"] != 52:
            print(f"self-test FAIL: all-401 service must be auth-blocked, notRun=52: {dele}"); bad += 1
        if ab["openbank-psd2-service"]["status"] != "auth-blocked":
            print("self-test FAIL: blocked in BOTH passes must be auth-blocked"); bad += 1
        party = ab["openbank-party-service"]
        if party["status"] != "tested" or party["notRun"] != 4:
            print(f"self-test FAIL: party residual not-run must be 4: {party}"); bad += 1
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
