#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Self-test for deploy-lag-blockers.py: every case is one the script MUST distinguish."""

from __future__ import annotations

import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "deploy-lag-blockers.py"
spec = importlib.util.spec_from_file_location("blockers", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

RULES = {
    "money_path_services": ["openbank-ledger-service", "openbank-account-service"],
    "deploy_holds": {"services": {
        "openbank-ledger-service": {"all_of": [
            {"issue": "#1", "reason": "owner-controlled first image"},
            {"issue": "#2", "reason": "staged wave"}]},
        "openbank-aml-service": {"issue": "#3", "reason": "single-entry form"},
    }},
}
LOG = (
    "##[warning][openbank-billing-service] is blocked on openbank-ledger-service, which is NOT part of this run\n"
    "##[warning][openbank-treasury-service] is blocked on openbank-ledger-service, which is NOT part of this run\n"
    "##[warning][openbank-treasury-service] is blocked on openbank-ledger-service, which is NOT part of this run\n"
    "##[warning][openbank-card-service] is blocked on openbank-fx-service, which is NOT part of this run\n"
)


def entry(svc, hours):
    return {"service": svc, "oldest_src_age_hours": hours, "deployed_tag": "t",
            "pinned_commit": "p", "oldest_src_commit": "c"}


failures = 0


def check(name, cond):
    global failures
    print(("ok   " if cond else "FAIL ") + name)
    failures += 0 if cond else 1


report = {"threshold_hours": 6, "entries": [
    entry("openbank-ledger-service", 570), entry("openbank-account-service", 80),
    entry("openbank-account-service-x", 80), entry("openbank-aml-service", 10)]}
# account-service-x is a near-name: it must NOT inherit account-service's money-path flag.
out = {e["service"]: e for e in mod.enrich(report, RULES, LOG, 3)["entries"]}

led = out["openbank-ledger-service"]
check("held money-path service names both holds", [h["issue"] for h in led["holds"]] == ["#1", "#2"])
check("held service is not escalated as unexplained", led["blocker"] == "hold" and not led["escalate"])
check("REGRESSION consumers attributed to provider, deduplicated",
      led["blocked_consumers"] == ["openbank-billing-service", "openbank-treasury-service"])
acc = out["openbank-account-service"]
check("unheld money-path lag past N days escalates", acc["escalate"] and acc["blocker"] == "unexplained")
check("near-name service is not money-path", not out["openbank-account-service-x"]["money_path"])
check("single-entry hold form is read", out["openbank-aml-service"]["holds"][0]["issue"] == "#3")
check("provider blocking a consumer but absent from lag report is appended",
      out.get("openbank-fx-service", {}).get("kind") == "regression_block"
      and out["openbank-fx-service"]["blocked_consumers"] == ["openbank-card-service"])

young = {e["service"]: e for e in mod.enrich(
    {"entries": [entry("openbank-account-service", 71)]}, RULES, "", 3)["entries"]}
check("money-path lag under N days does not escalate", not young["openbank-account-service"]["escalate"])
check("no log means no consumers invented", young["openbank-account-service"]["blocked_consumers"] == [])

# Unreadable input must fail loudly (exit 2), never print an empty clean report.
with tempfile.TemporaryDirectory() as d:
    rules_p = pathlib.Path(d, "r.yaml")
    rules_p.write_text("money_path_services: []\n")
    bad = pathlib.Path(d, "bad.json")
    bad.write_text("{not json")
    r = subprocess.run([sys.executable, str(SCRIPT), "--report", str(bad), "--rules", str(rules_p)],
                       capture_output=True, text=True)
    check("malformed report exits 2", r.returncode == 2 and r.stdout == "")
    r = subprocess.run([sys.executable, str(SCRIPT), "--report", str(pathlib.Path(d, "missing")),
                        "--rules", str(rules_p)], capture_output=True, text=True)
    check("missing report exits 2", r.returncode == 2)
    good = pathlib.Path(d, "good.json")
    good.write_text(json.dumps({"entries": []}))
    r = subprocess.run([sys.executable, str(SCRIPT), "--report", str(good), "--rules", str(rules_p)],
                       capture_output=True, text=True)
    check("empty report round-trips", r.returncode == 0 and json.loads(r.stdout)["entries"] == [])

print(f"{failures} failure(s)")
sys.exit(1 if failures else 0)
