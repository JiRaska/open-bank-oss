#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Name the blocker behind each deployed-code-lag entry (deploy-code-lag-watch.yml).

WHY THIS EXISTS
  The code-lag watch opened a tracker for ledger-service on its first day of lag and
  refreshed it for 24 days, and nothing moved: the body said the reconcile "re-drives it
  (possibly behind a transient can-i-deploy block)", which was false. The real blocker was
  a ``deploy_holds`` entry in rules.yaml, which only the owner can release, and meanwhile
  two consumers (billing, treasury) were refused by can-i-deploy REGRESSION because the
  provider in sandbox was the held one. The tracker existed; it named neither cause, so
  it read as noise. This script turns a lag entry into a statement of WHO must act.

WHAT IT ADDS to each report entry
  holds              deploy_holds entries for the service: [{issue, reason}]
  money_path         service is in rules.yaml money_path_services
  blocked_consumers  consumers the auto-deploy can-i-deploy step blocked because THIS
                     service has to deploy first (parsed from that run's log; the line does
                     not carry the block class, so it may be REGRESSION or a transient wait)
  blocker            "hold" | "unexplained"
  escalate           money-path and older than --money-path-days with no hold recorded:
                     nothing in governance explains the lag, so it is a pipeline defect.

  The log is UNTRUSTED data: only warning-annotation lines are parsed, and only names in
  the --services allowlist (the repo's own service set) are accepted. Entries are only
  enriched, never added, so the issue lifecycle stays driven by the lag report alone.

Usage: deploy-lag-blockers.py --report lag.json --rules rules.yaml
                              [--deploy-log can-i-deploy.log] [--money-path-days 3]
                              [--services openbank-a,openbank-b]
Prints the enriched report as JSON on stdout. Exit 2 on unreadable input — a watch that
cannot read its input must not report a clean bill.
"""

from __future__ import annotations

import argparse
import json
import re
import sys

import yaml

BLOCKED_RE = re.compile(
    r"(?:^|\s)(?:##\[warning\]|::warning::)\[(openbank-[a-z0-9-]+)\] is blocked on (openbank-[a-z0-9-]+), which is NOT part of this run",
    re.MULTILINE,
)


def holds_for(rules: dict, service: str) -> list[dict]:
    entry = ((rules.get("deploy_holds") or {}).get("services") or {}).get(service)
    if not entry:
        return []
    items = entry.get("all_of") if isinstance(entry, dict) and "all_of" in entry else [entry]
    return [{"issue": str(i.get("issue", "")), "reason": str(i.get("reason", ""))} for i in items]


def blocked_map(log_text: str, known: set[str] | None) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for consumer, provider in BLOCKED_RE.findall(log_text):
        if known is not None and (consumer not in known or provider not in known):
            continue
        lst = out.setdefault(provider, [])
        if consumer not in lst:
            lst.append(consumer)
    return out


def enrich(report: dict, rules: dict, log_text: str, money_path_days: float,
           known: set[str] | None = None) -> dict:
    money = set(rules.get("money_path_services") or [])
    blocked = blocked_map(log_text, known)
    entries = list(report.get("entries") or [])
    for e in entries:
        svc = e["service"]
        e["holds"] = holds_for(rules, svc)
        e["money_path"] = svc in money
        e["blocked_consumers"] = blocked.get(svc, [])
        e["blocker"] = "hold" if e["holds"] else "unexplained"
        age = float(e.get("oldest_src_age_hours") or 0)
        e["escalate"] = bool(e["money_path"] and not e["holds"] and age >= money_path_days * 24)
    out = dict(report)
    out["entries"] = entries
    out["money_path_days"] = money_path_days
    return out


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", required=True)
    ap.add_argument("--rules", required=True)
    ap.add_argument("--deploy-log")
    ap.add_argument("--money-path-days", type=float, default=3.0)
    ap.add_argument("--services", help="comma-separated allowlist of known service names")
    a = ap.parse_args(argv)
    try:
        with open(a.report, encoding="utf-8") as f:
            report = json.load(f)
        with open(a.rules, encoding="utf-8") as f:
            rules = yaml.safe_load(f)
        log_text = ""
        if a.deploy_log:
            with open(a.deploy_log, encoding="utf-8", errors="replace") as f:
                log_text = f.read()
    except (OSError, ValueError, yaml.YAMLError) as exc:
        print(f"deploy-lag-blockers: cannot read input: {exc}", file=sys.stderr)
        return 2
    if not isinstance(report, dict) or not isinstance(rules, dict) or "entries" not in report:
        print("deploy-lag-blockers: malformed report or rules", file=sys.stderr)
        return 2
    json.dump(enrich(report, rules, log_text, a.money_path_days,
                     set(filter(None, a.services.split(","))) if a.services else None), sys.stdout, indent=2)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
