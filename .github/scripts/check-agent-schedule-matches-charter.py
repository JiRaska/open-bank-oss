#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""An agent's deployed *_CRON must equal the schedule its charter (agents.yaml) declares.

`openbank-libs/governance/agents.yaml` is the governed record behind the EU AI Act inventory
(`docs/compliance/eu-ai-act.md`) and the admin-ui AI-governance snapshot. The sweep time a pod
actually runs at is a Quarkus cron in `openbank-infra/gitops/components/<agent>/<agent>.yaml`
(`*_CRON`). Measured 2026-10-17: five of nine scheduled agents disagreed (devops 04:00 vs 03:30,
control-liveness-sentinel 03:00 vs 03:15, docs-truth Sunday 06:00-with-no-day vs Sun 05:30,
authz-policy-auditor 07:00 vs Sun 06:00, flaky-test-hunter 08:00 vs Sun 06:30) — the crons were
deliberate 15-minute offsets between agents sharing one LiteLLM gateway, the charter was never
updated, and nothing could notice: both files are individually valid.

Charter format: `daily: "HH:MM UTC"` or `weekly: "<Weekday> HH:MM UTC"` (the weekday is required;
a weekly charter with no day names no time at all). Cron format: Quarkus six fields
`sec min hour dom month dow`. Only a cron that fires at one fixed time (numeric sec/min/hour,
`*`/`?` day fields, dow `*`/`?` or a three-letter weekday) is comparable; anything else is
reported as unparseable, which fails — an unreadable schedule is not a matching one.

Agents whose deployment declares no `*_CRON` (reactive-only) are skipped. An agent with a cron
and no charter schedule fails.

Usage: check-agent-schedule-matches-charter.py [--root .] [--enforce | --selftest]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

DAYS = {"mon": "Monday", "tue": "Tuesday", "wed": "Wednesday", "thu": "Thursday",
        "fri": "Friday", "sat": "Saturday", "sun": "Sunday"}
CRON_ENV = re.compile(r"_CRON$")


def parse_cron(expr: str) -> tuple[str | None, str] | None:
    """('Sunday'|None, 'HH:MM') for a fixed-time Quarkus cron, else None."""
    parts = expr.split()
    if len(parts) != 6:
        return None
    sec, minute, hour, dom, month, dow = parts
    if not (sec.isdigit() and minute.isdigit() and hour.isdigit() and sec == "0"):
        return None
    if dom not in ("*", "?") or month != "*":
        return None
    day = None
    if dow not in ("*", "?"):
        day = DAYS.get(dow[:3].lower()) if re.fullmatch(r"[A-Za-z]{3}", dow) else None
        if day is None:
            return None
    return day, f"{int(hour):02d}:{int(minute):02d}"


def parse_charter(schedule: dict | None) -> tuple[str | None, str] | None:
    if not schedule:
        return None
    for kind in ("daily", "weekly"):
        val = schedule.get(kind)
        if not val:
            continue
        m = re.fullmatch(r"(?:(?P<day>[A-Za-z]+)\s+)?(?P<t>\d{2}:\d{2})\s+UTC", str(val).strip())
        if not m:
            return None
        day = m.group("day")
        if kind == "weekly" and not day:
            return None
        if kind == "daily" and day:
            return None
        if day and day[:3].lower() not in DAYS:
            return None
        return (DAYS[day[:3].lower()] if day else None), m.group("t")
    return None


def deployed_crons(root: pathlib.Path, agent: str) -> list[str]:
    path = root / "openbank-infra" / "gitops" / "components" / agent / f"{agent}.yaml"
    if not path.exists():
        return []
    out: list[str] = []
    for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")):
        stack = [doc]
        while stack:
            node = stack.pop()
            if isinstance(node, dict):
                if CRON_ENV.search(str(node.get("name", ""))) and "value" in node:
                    out.append(str(node["value"]))
                stack.extend(node.values())
            elif isinstance(node, list):
                stack.extend(node)
    return [c for c in out if c.strip().lower() != "off"]


def check(root: pathlib.Path) -> tuple[list[str], int]:
    agents = (yaml.safe_load((root / "openbank-libs/governance/agents.yaml").read_text(encoding="utf-8")) or {}).get("agents", [])
    problems: list[str] = []
    compared = 0
    for a in agents:
        crons = deployed_crons(root, a["id"])
        if not crons:
            continue
        charter = parse_charter(a.get("schedule"))
        if charter is None:
            problems.append(f"{a['id']}: deployed cron {crons} but the charter schedule {a.get('schedule')!r} is missing or "
                            f"unparseable (daily: \"HH:MM UTC\" or weekly: \"<Weekday> HH:MM UTC\")")
            continue
        for c in crons:
            got = parse_cron(c)
            compared += 1
            if got != charter:
                problems.append(f"{a['id']}: deployed cron {c!r} fires at {got} but the charter says {charter}")
    return problems, compared


def selftest() -> int:
    fails = []

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: got {got!r}, want {want!r}")

    case("daily cron", parse_cron("0 30 3 * * ?"), (None, "03:30"))
    case("weekly cron", parse_cron("0 30 5 ? * SUN"), ("Sunday", "05:30"))
    case("a range is not a fixed time", parse_cron("0 0 3-5 * * ?"), None)
    case("every-n minutes is not a fixed time", parse_cron("0 */5 * * * ?"), None)
    case("five-field crontab is refused", parse_cron("30 3 * * *"), None)
    case("charter daily", parse_charter({"daily": "03:15 UTC"}), (None, "03:15"))
    case("charter weekly with day", parse_charter({"weekly": "Sunday 05:30 UTC"}), ("Sunday", "05:30"))
    case("charter weekly WITHOUT a day is refused", parse_charter({"weekly": "06:00 UTC"}), None)
    case("charter absent", parse_charter(None), None)
    case("charter reactive-only", parse_charter({"reactive": "x"}), None)
    # Known-negative against the pre-fix main values: the gate must flag the real drift shape.
    case("devops 04:00 charter vs 03:30 cron differ", parse_charter({"daily": "04:00 UTC"}) == parse_cron("0 30 3 * * ?"), False)
    case("a matching pair compares equal", parse_charter({"weekly": "Sunday 06:00 UTC"}) == parse_cron("0 0 6 ? * SUN"), True)
    live, n = check(pathlib.Path(__file__).resolve().parents[2])
    case("the live tree compares at least 5 schedules (the scan is not vacuous)", n >= 5, True)
    if fails:
        sys.stderr.write("selftest FAILED:\n  " + "\n  ".join(fails) + "\n")
        return 1
    print("selftest ok: 13 cases + a live read")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    problems, compared = check(pathlib.Path(args.root))
    if compared == 0:
        print("::error::agent-schedule-charter: compared NO schedules — the scan is broken, not clean.")
        return 1
    level = "error" if args.enforce else "warning"
    for p in problems:
        print(f"::{level}::agent-schedule-charter: {p}")
    print(f"agent-schedule-charter: {compared} deployed cron(s) compared, {len(problems)} mismatch(es).")
    return 1 if problems and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
