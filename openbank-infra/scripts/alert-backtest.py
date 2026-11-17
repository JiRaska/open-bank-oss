#!/usr/bin/env python3
"""Backtest a Prometheus alert rule against recorded history: how often would it FIRE?

Replays the rule's `expr` with query_range at a 1-minute step over the last N days, then
emulates Prometheus' own state machine per series -- `for:` (pending -> firing only after the
condition held continuously that long) and `keep_firing_for:` (stay firing until the condition
has been false that long) -- and reports, per alert, the number of FIRING ONSETS (what reaches
Alertmanager) and firing minutes.

Why onsets and not `changes(ALERTS_FOR_STATE[7d])`: that series' value is the activeAt
timestamp, so every pending flicker that never fires counts as a change. Measured 2026-11-14:
TrafficDropAnomaly read 11,754 "changes" over 7d against 16 real firing onsets. A noise budget
must count what a human receives.

Calibrate before trusting a number: `--calibrate` runs the rule AS DEPLOYED and compares the
emulated onsets with the onsets recorded in ALERTS{alertstate="firing"}. A backtest that cannot
reproduce the alert's real history cannot say anything about a candidate.

Usage (read-only; Prometheus is reached by a port-forward, it has no public ingress):
  kubectl -n observability port-forward svc/kube-prometheus-stack-prometheus 19141:9090 &
  python3 openbank-infra/scripts/alert-backtest.py --prom http://localhost:19141 \
      --rules openbank-infra/gitops/components/observability/prometheus-rules-forecast.yaml \
      --alert TrafficDropAnomaly --calibrate
  # candidate expression / timing, without editing the file:
  python3 ... --alert TrafficDropAnomaly --for 30m --keep-firing-for 15m

  python3 openbank-infra/scripts/alert-backtest.py --self-test   # state machine, no network
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.parse
import urllib.request

STEP = 60


def parse_duration(s: str | None) -> int:
    if not s:
        return 0
    total = 0
    for n, u in re.findall(r"(\d+)([smhdw])", s):
        total += int(n) * {"s": 1, "m": 60, "h": 3600, "d": 86400, "w": 604800}[u]
    return total


def emulate(points: list[int], for_s: int, keep_s: int, step: int = STEP) -> tuple[int, int]:
    """points: sorted unix timestamps at which the condition held. Returns (onsets, firing_steps).

    Mirrors rules/alerting.go: a series absent from the result is 'false'. Pending starts at the
    first true step; firing when (t - activeAt) >= for. Once firing, a false step resolves only
    after the condition has been false for >= keep_firing_for.
    """
    true_set = set(points)
    if not points:
        return 0, 0
    start, end = points[0], points[-1] + keep_s + step
    onsets = firing_steps = 0
    active_at = None
    firing = False
    last_true = None
    t = start
    while t <= end:
        if t in true_set:
            last_true = t
            if active_at is None:
                active_at = t
            if not firing and t - active_at >= for_s:
                firing = True
                onsets += 1
        else:
            if firing and keep_s and last_true is not None and t - last_true < keep_s:
                pass  # held by keep_firing_for
            else:
                firing = False
                active_at = None
        if firing:
            firing_steps += 1
        t += step
    return onsets, firing_steps


def prom_range(prom: str, expr: str, start: int, end: int) -> list[dict]:
    q = urllib.parse.urlencode({"query": expr, "start": start, "end": end, "step": STEP})
    with urllib.request.urlopen(f"{prom}/api/v1/query_range?{q}", timeout=300) as r:
        body = json.load(r)
    if body.get("status") != "success":
        raise SystemExit(f"query failed: {body}")
    return body["data"]["result"]


def collect(prom: str, expr: str, days: float, pad_s: int) -> dict[str, list[int]]:
    end = int(time.time()) // STEP * STEP
    start = end - int(days * 86400) - pad_s
    series: dict[str, set[int]] = {}
    chunk = 6 * 3600  # keep each query small; anomaly subqueries are expensive
    s = start
    while s < end:
        e = min(s + chunk - STEP, end)
        for r in prom_range(prom, expr, s, e):
            key = json.dumps({k: v for k, v in r["metric"].items() if k != "__name__"}, sort_keys=True)
            series.setdefault(key, set()).update(int(float(t)) for t, _ in r["values"])
        s = e + STEP
    return {k: sorted(v) for k, v in series.items()}


def recorded_onsets(prom: str, alert: str, days: float) -> int:
    expr = (f'sum(count_over_time((ALERTS{{alertname="{alert}",alertstate="firing"}} unless '
            f'ALERTS{{alertname="{alert}",alertstate="firing"}} offset 1m)[{int(days * 24)}h:1m]))')
    q = urllib.parse.urlencode({"query": expr})
    with urllib.request.urlopen(f"{prom}/api/v1/query?{q}", timeout=300) as r:
        res = json.load(r)["data"]["result"]
    return int(float(res[0]["value"][1])) if res else 0


def load_rule(path: str, alert: str) -> dict:
    import yaml  # PyYAML; only needed when reading a rule file

    with open(path) as f:
        for doc in yaml.safe_load_all(f):
            for g in ((doc or {}).get("spec") or doc or {}).get("groups", []):
                for r in g.get("rules", []):
                    if r.get("alert") == alert:
                        return r
    raise SystemExit(f"{alert} not found in {path}")


def self_test() -> int:
    m = 60
    cases = [
        # (true minutes, for, keep, expected onsets)
        ("flicker shorter than for never fires", [0, 1, 2], 5 * m, 0, 0),
        ("held exactly for fires once", list(range(0, 6)), 5 * m, 0, 1),
        ("two episodes split by a gap fire twice", list(range(0, 10)) + list(range(20, 30)), 5 * m, 0, 2),
        ("keep_firing_for bridges the gap", list(range(0, 10)) + list(range(13, 30)), 5 * m, 5 * m, 1),
        ("keep_firing_for shorter than gap does not", list(range(0, 10)) + list(range(20, 30)), 5 * m, 5 * m, 2),
        ("for 0 fires on first point", [7], 0, 0, 1),
        ("empty is quiet", [], 5 * m, 0, 0),
    ]
    bad = 0
    for name, mins, f, k, want in cases:
        got, _ = emulate([x * m for x in mins], f, k)
        ok = got == want
        bad += not ok
        print(f"{'ok  ' if ok else 'FAIL'} {name}: onsets={got} want={want}")
    # Known-negative: a broken emulator that ignores `for:` must be caught by case 1.
    if emulate([0, m, 2 * m], 0, 0)[0] != 1:
        print("FAIL control: for=0 must fire")
        bad += 1
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--prom")
    ap.add_argument("--rules")
    ap.add_argument("--alert")
    ap.add_argument("--expr", help="candidate expression (overrides the file)")
    ap.add_argument("--for", dest="for_", help="candidate for: (overrides the file)")
    ap.add_argument("--keep-firing-for", help="candidate keep_firing_for: (overrides the file)")
    ap.add_argument("--days", type=float, default=7)
    ap.add_argument("--calibrate", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not (a.prom and a.alert and (a.rules or a.expr)):
        ap.error("--prom, --alert and --rules or --expr are required")
    rule = load_rule(a.rules, a.alert) if a.rules else {}
    expr = a.expr or rule["expr"]
    for_s = parse_duration(a.for_ if a.for_ is not None else rule.get("for"))
    keep_s = parse_duration(a.keep_firing_for if a.keep_firing_for is not None else rule.get("keep_firing_for"))
    series = collect(a.prom.rstrip("/"), expr, a.days, for_s)
    onsets = steps = 0
    per = []
    for k, pts in series.items():
        o, s = emulate(pts, for_s, keep_s)
        onsets += o
        steps += s
        if o:
            per.append((o, s, k))
    print(json.dumps({"alert": a.alert, "days": a.days, "for_s": for_s, "keep_firing_for_s": keep_s,
                      "series_true_at_least_once": len(series), "firing_onsets": onsets,
                      "firing_minutes": steps}, indent=1))
    for o, s, k in sorted(per, reverse=True)[:10]:
        print(f"  {o:4d} onsets {s:6d} min  {k}")
    if a.calibrate:
        rec = recorded_onsets(a.prom.rstrip("/"), a.alert, a.days)
        print(f"calibration: recorded firing onsets={rec}, emulated={onsets}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
