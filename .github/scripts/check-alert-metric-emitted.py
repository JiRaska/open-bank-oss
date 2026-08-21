#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""An alert rule must depend only on domain metrics that some code actually emits.

WHY THIS EXISTS
---------------
Measured against the live sandbox on 2026-08-19: **18 alert rules fire on `openbank_*`
metrics that have no series in Prometheus at all**, and four of those name a metric that
does not exist anywhere in the repository -- no Kotlin emits it, no recording rule
produces it, nothing ever will:

    TransactionSagaStuck              (severity: critical)  openbank_transaction_sagas_stuck_total
    ClearingSettlementWindowMissed    (severity: warning)   openbank_clearing_settlements_completed_total
    LendingRepaymentProcessingStalled (severity: warning)   openbank_lending_repayments_processed_total
    SwiftMtMessageProcessingStalled   (severity: warning)   openbank_swift_messages_processed_total

All four were structurally incapable of firing, and all four are now resolved on main (see
KNOWN_DEAD below for which route each took) -- this gate exists so the estate cannot
reacquire a fifth. `TransactionSagaStuck` was a critical money-path alert whose whole job is
to page someone when a saga wedges, and it could not. Nothing anywhere disagreed: the rule
loads cleanly,
`promtool check rules` passes (the expression is valid PromQL over a metric that simply
has no data), Alertmanager shows a healthy route, and the Prometheus UI lists the rule
as `inactive` -- which is exactly what a correctly-quiet alert also looks like.

This is the same shape this repo has documented repeatedly -- a detector that exists in
the prose and not in the ruleset (FinOps D2), the OPA generator covering 4 of 25, the
`openapi.yaml` fiction, the 16 dashboard metrics of #5049. The dashboard half of it was
found by an empty-panel audit; the ALERT half is worse, because a blank panel is at
least visible to anyone who opens the dashboard, while a rule that never fires produces
no artifact at all.

WHAT IT CHECKS
--------------
For every alert rule in every `PrometheusRule` manifest under `openbank-infra/gitops`,
extract the `openbank_*` / `openbank:*` metric names its expression depends on, and
require each to be either

  1. emitted by Kotlin source -- the Micrometer name (`openbank.foo.bar`) appears in some
     `openbank-*/src/main` file. Prometheus name -> Micrometer name is the registry's own
     mapping, applied in reverse: strip one `_total` / `_bucket` / `_count` / `_sum` /
     `_max` suffix, then `_` -> `.`; or
  2. produced by a recording rule in the same manifest corpus (`record:` key); or
  3. listed in EXTERNALLY_PROVIDED below, with a reason.

WHAT IT DELIBERATELY DOES NOT CHECK
------------------------------------
Non-`openbank_` metrics. `kube_*`, `node_*`, `container_*`, `up`, `http_server_requests_*`
and friends come from exporters and framework instrumentation this repo does not own, so
"no Kotlin emits it" says nothing about whether they exist -- including them would produce
noise that buries the signal. The rule is: this gate covers exactly the metrics whose
source of truth is this repository.

It also does not check that an emitted metric has ever actually FIRED at runtime. That is
a different property (and a legitimate state -- a counter for an event that has not
happened yet is correctly absent), and it is not decidable from the repo. See
check-alert-absent-blind.py for the complementary runtime-semantics half.

Usage:  check-alert-metric-emitted.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

import gatelib
import metricsrc

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"

# Domain metrics that legitimately come from outside this repo's Kotlin. Each needs a
# reason. Checked BOTH ways -- a stale entry is itself reported -- so this can only shrink.
EXTERNALLY_PROVIDED: dict[str, str] = {}

# Dead alert rules declared rather than hidden, keyed by "<alert>#<metric>" so fixing one alert
# does not silently excuse another on the same metric. Checked BOTH ways below: an entry that
# stops reproducing is itself reported, so the list can only shrink.
#
# EMPTY, and that is the point. All four entries this gate was written against are resolved on
# main, by two different and both-correct routes:
#   - TransactionSagaStuck (critical, money-path) -> #5787 added StuckSagaGauge, so the metric
#     now exists and the alert can finally fire.
#   - ClearingSettlementWindowMissed / SwiftMtMessageProcessingStalled /
#     LendingRepaymentProcessingStalled -> removed in #5733. Instrumenting them would not have
#     made them meaningful: all three watched a THROUGHPUT FLOOR on services with no cadence to
#     miss (every entry point REST-driven, no @Scheduled sweep, no @Incoming consumer), so "no
#     traffic in the last hour" is the normal resting state, not a fault. Where a cadence is
#     added later the fleet mechanism applies -- registerWorkflowLiveness (ADR-0160) plus the
#     existing WorkflowLivenessStale -- not a bespoke per-service throughput rule.
#
# A NEW dead alert matches nothing here and fails immediately, which is the whole purpose: the
# estate reached zero and cannot quietly leave it.
KNOWN_DEAD: dict[str, str] = {}

# PromQL functions/keywords and common label names, so they are not mistaken for metrics.
PROMQL_KW = {
    "sum", "rate", "irate", "increase", "avg", "min", "max", "count", "by", "without", "on",
    "ignoring", "group_left", "group_right", "histogram_quantile", "topk", "bottomk",
    "label_replace", "label_join", "absent", "absent_over_time", "vector", "scalar", "time",
    "clamp_max", "clamp_min", "clamp", "round", "delta", "idelta", "deriv", "predict_linear",
    "stddev", "stdvar", "quantile", "count_values", "offset", "bool", "and", "or", "unless",
    "avg_over_time", "sum_over_time", "max_over_time", "min_over_time", "last_over_time",
    "count_over_time", "stddev_over_time", "quantile_over_time", "present_over_time",
    "changes", "resets", "abs", "ceil", "floor", "exp", "ln", "log2", "log10", "sqrt", "sgn",
    "timestamp", "day_of_week", "hour", "minute", "month", "year", "days_in_month", "group",
    "sort", "sort_desc", "pi", "le",
}

METRIC_RE = re.compile(r"\b(openbank[_:][a-zA-Z0-9_:]*)\b")
# Micrometer appends these to the base name; strip at most one to recover the base.
SUFFIXES = ("_total", "_bucket", "_count", "_sum", "_max", "_seconds")


def alert_rules() -> list[dict]:
    """Every alerting rule in every PrometheusRule manifest, with its source file."""
    out = []
    for path in gatelib.rglob(GITOPS, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "PrometheusRule":
                continue
            for group in ((doc.get("spec") or {}).get("groups") or []):
                if not isinstance(group, dict):
                    continue
                for rule in group.get("rules") or []:
                    if not isinstance(rule, dict) or not rule.get("alert"):
                        continue
                    out.append({
                        "file": str(path.relative_to(REPO)),
                        "group": group.get("name", ""),
                        "alert": rule["alert"],
                        "expr": str(rule.get("expr", "")),
                        "severity": (rule.get("labels") or {}).get("severity", ""),
                    })
    return out


def recorded_names() -> set[str]:
    """Metric names produced by recording rules -- those exist without any Kotlin."""
    out = set()
    for path in gatelib.rglob(GITOPS, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "PrometheusRule":
                continue
            for group in ((doc.get("spec") or {}).get("groups") or []):
                if not isinstance(group, dict):
                    continue
                for rule in group.get("rules") or []:
                    if isinstance(rule, dict) and rule.get("record"):
                        out.add(str(rule["record"]))
    return out




def evaluate(rules: list[dict], emitted: set[str], recorded: set[str],
             known_dead: dict[str, str] | None = None) -> tuple[list[dict], set[str], set[str]]:
    """Returns (violations, used_exemptions, used_baseline)."""
    known_dead = KNOWN_DEAD if known_dead is None else known_dead
    violations, used, used_dead = [], set(), set()
    for r in rules:
        for metric in sorted(metricsrc.metrics_in(r["expr"])):
            if metric in recorded or metricsrc.canonical(metric) in {metricsrc.canonical(r) for r in recorded}:
                continue
            if metric in EXTERNALLY_PROVIDED:
                used.add(metric)
                continue
            if metricsrc.canonical(metric) in emitted:
                continue
            key = f"{r['alert']}#{metric}"
            if key in known_dead:
                used_dead.add(key)
                continue
            violations.append({**r, "metric": metric})
    return violations, used, used_dead


def selftest() -> int:
    for problem in metricsrc.self_check():
        print(f"selftest FAIL (metricsrc): {problem}")
        return 1
    emitted = {metricsrc.canonical("openbank.payments.submitted"),
               metricsrc.canonical("openbank_statement_close_failures_total"),
               metricsrc.canonical("openbank.ledger.accounting_day.stuck_cutoff_days")}
    recorded = {"openbank:llm_cost_usd_24h:total"}
    cases = [
        # (expr, expect_violation, why)
        ("increase(openbank_payments_submitted_total[1h]) == 0", False, "counter with code"),
        ("openbank_transaction_sagas_stuck_total > 0", True,
         "the shape #5758 fixed: an alert over a metric nothing emits"),
        ("openbank:llm_cost_usd_24h:total > 5", False, "produced by a recording rule"),
        ("sum(rate(kube_pod_status_ready[5m])) == 0", False, "non-openbank metric is out of scope"),
        ("histogram_quantile(0.99, openbank_payments_submitted_seconds_bucket) > 1", False,
         "histogram suffixes strip back to a real base name"),
        ("increase(openbank_statement_close_failures_total[1h]) > 0", False,
         "underscore idiom: registry.counter(\"openbank_statement_close_failures_total\")"),
        ("openbank_ledger_accounting_day_stuck_cutoff_days > 3", False,
         "mixed dot/underscore idiom"),
    ]
    for expr, want, why in cases:
        v, _, _ = evaluate([{"file": "t", "group": "g", "alert": "A", "expr": expr, "severity": ""}],
                           emitted, recorded, known_dead={})
        if bool(v) != want:
            print(f"selftest FAIL ({why}): expr={expr!r} expected violation={want}, got {len(v)}")
            return 1
    # A baselined alert is spared; the SAME metric on a different alert is not -- otherwise
    # one debt entry would quietly cover every future rule that reuses the metric.
    # Baseline mechanics are exercised against a SYNTHETIC entry, never against whatever happens
    # to be in KNOWN_DEAD: that dict is empty today, and a fixture reading from it would quietly
    # stop testing anything the moment the estate is clean -- which is exactly now.
    fake_baseline = {"SomeDeadAlert#openbank_never_emitted_total": "selftest fixture"}
    dead_rule = [{"file": "t", "group": "g", "alert": "SomeDeadAlert",
                  "expr": "increase(openbank_never_emitted_total[1h]) == 0", "severity": "warning"}]
    v, _, used_dead = evaluate(dead_rule, emitted, recorded, known_dead=fake_baseline)
    if v or not used_dead:
        print(f"selftest FAIL: KNOWN_DEAD entry not honoured (findings={len(v)}, used={used_dead})")
        return 1
    copycat = [{"file": "t", "group": "g", "alert": "SomeNewAlert",
                "expr": "increase(openbank_never_emitted_total[1h]) == 0", "severity": "warning"}]
    v2, _, _ = evaluate(copycat, emitted, recorded, known_dead=fake_baseline)
    if len(v2) != 1:
        print("selftest FAIL: baseline wrongly excused a DIFFERENT alert on the same metric.")
        return 1

    # The corpus scan itself must not be silently empty.
    if not alert_rules():
        print("selftest FAIL: found no PrometheusRule alert rules -- the scan is broken.")
        return 1
    print(f"selftest OK: {len(cases)} expression cases both directions, plus baseline honoured "
          f"and not over-applied (flags the dead critical alert, spares recording rules, "
          f"non-openbank metrics, histogram suffixes and all three naming idioms).")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    rules = alert_rules()
    emitted = metricsrc.emitted_names()
    recorded = recorded_names()
    violations, used, used_dead = evaluate(rules, emitted, recorded)

    findings = []
    for v in violations:
        findings.append(
            f"::error file={v['file']}::alert {v['alert']} (severity={v['severity'] or 'unset'}) "
            f"depends on {v['metric']}, which no code in this repo emits and no recording rule "
            f"produces. The rule loads cleanly and reads as `inactive` in Prometheus -- "
            f"indistinguishable from a correctly-quiet alert -- but it can never fire. Either "
            f"instrument the metric, correct the name, or delete the rule.",
        )
    for stale in sorted(set(KNOWN_DEAD) - used_dead):
        findings.append(
            f"::error::stale KNOWN_DEAD entry {stale} -- that alert no longer depends on a "
            f"metric nothing emits (instrumented, renamed or deleted). Remove it, so the "
            f"baseline can only shrink.",
        )
    for stale in sorted(set(EXTERNALLY_PROVIDED) - used):
        findings.append(
            f"::error::stale EXTERNALLY_PROVIDED entry {stale} -- no alert references it any "
            f"more. Remove it, so the list can only shrink.",
        )

    for line in findings:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    gatelib.subjects(len(rules), "alert rules scanned")
    verdict = "clean." if not findings else f"{len(findings)} finding(s) above."
    print(f"check-alert-metric-emitted: {len(rules)} alert rule(s), {len(emitted)} emitted "
          f"metric name(s), {len(recorded)} recording rule(s), {len(KNOWN_DEAD)} baselined "
          f"dead alert(s) (#5733) — {verdict}")
    return 1 if findings and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
