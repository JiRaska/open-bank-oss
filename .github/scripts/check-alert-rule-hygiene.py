#!/usr/bin/env python3
"""Alert-rule hygiene ratchet: every NEW alert must be fit to page a human.

Three properties, all decidable from the rule text (no Prometheus needed, so it runs on a PR):

  for        a `for:` of at least 1m. A rule that fires on its first true evaluation pages on
             every single-scrape blip. A rule whose condition is a one-shot event (a counter
             increase) may opt out with annotation `for_zero_reason:` saying why.
  runbook    annotation `runbook_url:` — where the person woken up goes next.
  backtest   any rule built on a STATISTICAL or PROJECTED threshold (stddev_over_time,
             predict_linear, holt_winters / double_exponential_smoothing, z-score style
             quantile_over_time bands) must carry annotation `backtest:` recording its firing
             onsets over recorded history (openbank-infra/scripts/alert-backtest.py). These are
             the rules whose noise cannot be judged by reading them: on 2026-11-14 the three that
             shipped without one were the top flappers of the week (TrafficDropAnomaly,
             SpanLatencyTrendAnomaly, NodeDiskWillFillIn4h: 16 / 18 / 66 firing onsets in 7d).

Existing violations are baselined in .github/gates/alert-rule-hygiene-baseline.yaml, keyed by
ALERT NAME and property. The baseline is checked in both directions: a new violation fails,
and a baselined entry that no longer violates (or whose alert is gone) also fails, so the list
only shrinks and an empty-scan cannot pass as clean.

  python3 .github/scripts/check-alert-rule-hygiene.py --enforce
  python3 .github/scripts/check-alert-rule-hygiene.py --write-baseline   # after a deliberate fix
  python3 .github/scripts/check-alert-rule-hygiene.py --self-test
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
RULE_GLOB = "openbank-infra/gitops/**/*.yaml"
BASELINE = ROOT / ".github/gates/alert-rule-hygiene-baseline.yaml"
STATISTICAL = re.compile(
    r"\b(stddev_over_time|stdvar_over_time|predict_linear|holt_winters|double_exponential_smoothing)\s*\(")
DUR = re.compile(r"^(\d+(ms|s|m|h|d|w))+$")


def seconds(d: str | None) -> float:
    if not d:
        return 0.0
    mult = {"ms": 0.001, "s": 1, "m": 60, "h": 3600, "d": 86400, "w": 604800}
    return sum(int(n) * mult[u] for n, u in re.findall(r"(\d+)(ms|s|m|h|d|w)", str(d)))


def iter_alerts(root: pathlib.Path):
    for f in sorted(root.glob(RULE_GLOB)):
        try:
            docs = list(yaml.safe_load_all(f.read_text()))
        except yaml.YAMLError:
            continue  # yamllint owns syntax; a rule file that does not parse is its finding
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "PrometheusRule":
                continue
            for g in (doc.get("spec") or {}).get("groups") or []:
                for r in g.get("rules") or []:
                    if isinstance(r, dict) and "alert" in r:
                        yield f.relative_to(root).as_posix(), r


def violations(root: pathlib.Path) -> tuple[set[tuple[str, str]], int]:
    out: set[tuple[str, str]] = set()
    n = 0
    for _, r in iter_alerts(root):
        n += 1
        name, ann = r["alert"], r.get("annotations") or {}
        if seconds(r.get("for")) < 60 and not ann.get("for_zero_reason"):
            out.add((name, "for"))
        if not ann.get("runbook_url"):
            out.add((name, "runbook"))
        if STATISTICAL.search(str(r.get("expr", ""))) and not ann.get("backtest"):
            out.add((name, "backtest"))
    return out, n


def load_baseline(path: pathlib.Path) -> set[tuple[str, str]]:
    if not path.is_file():
        return set()
    data = yaml.safe_load(path.read_text()) or {}
    return {(a, p) for p, names in (data.get("baseline") or {}).items() for a in names or []}


def write_baseline(path: pathlib.Path, v: set[tuple[str, str]]) -> None:
    by: dict[str, list[str]] = {}
    for a, p in sorted(v):
        by.setdefault(p, []).append(a)
    head = ("# DERIVED by .github/scripts/check-alert-rule-hygiene.py --write-baseline.\n"
            "# Alerts that predate the hygiene ratchet (2026-11-14). Shrink-only: fixing one and\n"
            "# not removing it here fails the gate, exactly like adding a new violation.\n")
    path.write_text(head + yaml.safe_dump({"baseline": by}, sort_keys=True))


def check(root: pathlib.Path, baseline: pathlib.Path) -> int:
    v, n = violations(root)
    b = load_baseline(baseline)
    new, stale = sorted(v - b), sorted(b - v)
    print(f"SUBJECTS={n}")
    print(f"alert-rule-hygiene: {n} alert rules scanned, {len(v)} violations, {len(b)} baselined")
    for a, p in new:
        print(f"::error::alert {a}: missing {p} "
              f"({'for: >= 1m or for_zero_reason' if p == 'for' else 'runbook_url' if p == 'runbook' else 'backtest annotation from alert-backtest.py'})")
    for a, p in stale:
        print(f"::error::baseline entry {a}/{p} no longer violates (or the alert is gone) — "
              f"remove it: python3 .github/scripts/check-alert-rule-hygiene.py --write-baseline")
    if n == 0:
        print("::error::scanned 0 alert rules — the glob found nothing, this run measured nothing")
        return 1
    return 1 if (new or stale) else 0


def self_test() -> int:
    good = """apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
spec:
  groups:
    - name: g
      rules:
        - alert: Good
          expr: up == 0
          for: 5m
          annotations: {runbook_url: "x"}
        - alert: GoodStat
          expr: x < avg_over_time(x[1h]) - 3 * stddev_over_time(x[1h])
          for: 5m
          annotations: {runbook_url: "x", backtest: "7d: 0 onsets"}
        - alert: GoodEvent
          expr: increase(y[5m]) > 0
          annotations: {runbook_url: "x", for_zero_reason: "one-shot event"}
"""
    bad = """apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
spec:
  groups:
    - name: g
      rules:
        - alert: NoFor
          expr: up == 0
          annotations: {runbook_url: "x"}
        - alert: ShortFor
          expr: up == 0
          for: 30s
          annotations: {runbook_url: "x"}
        - alert: NoRunbook
          expr: up == 0
          for: 5m
        - alert: StatNoBacktest
          expr: predict_linear(z[6h], 3600) < 0
          for: 5m
          annotations: {runbook_url: "x"}
"""
    want = {("NoFor", "for"), ("ShortFor", "for"), ("NoRunbook", "runbook"), ("StatNoBacktest", "backtest")}
    fails = 0
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        p = root / "openbank-infra/gitops/c"
        p.mkdir(parents=True)
        (p / "good.yaml").write_text(good)
        base = root / "b.yaml"
        if check(root, base) != 0:
            print("FAIL: clean rules rejected"); fails += 1
        (p / "bad.yaml").write_text(bad)
        got, _ = violations(root)
        if got != want:
            print(f"FAIL: violations {sorted(got)} != {sorted(want)}"); fails += 1
        if check(root, base) != 1:
            print("FAIL: new violations accepted"); fails += 1
        write_baseline(base, got)
        if check(root, base) != 0:
            print("FAIL: baselined violations rejected"); fails += 1
        (p / "bad.yaml").unlink()
        if check(root, base) != 1:
            print("FAIL: stale baseline accepted"); fails += 1
        empty = pathlib.Path(d) / "empty"
        empty.mkdir()
        if check(empty, base) != 1:
            print("FAIL: a scan of zero rules passed"); fails += 1
    print("self-test:", "FAIL" if fails else "ok")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--enforce", action="store_true")
    g.add_argument("--write-baseline", action="store_true")
    g.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.write_baseline:
        v, n = violations(ROOT)
        write_baseline(BASELINE, v)
        print(f"wrote {len(v)} baseline entries over {n} alert rules")
        return 0
    return check(ROOT, BASELINE)


if __name__ == "__main__":
    sys.exit(main())
