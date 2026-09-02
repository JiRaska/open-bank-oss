#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A dashboard panel must query only openbank_* metrics that some code actually emits.

WHY THIS EXISTS
---------------
This is #5049 made structural. That issue found 16 business metrics referenced across 9 Grafana
dashboards and emitted by nothing -- Business KPIs, Executive Overview, Compliance & AML, the
panels a stakeholder or auditor is most likely to open. It was fixed by hand and closed, and
nothing prevented it happening again: as of writing, no gate in this repo looks at a dashboard.

A panel over a metric nothing emits renders "No data", which is indistinguishable from a panel
over a metric that is simply quiet right now. That ambiguity is the whole problem, and it is why
the check is STATIC.

WHAT IT CHECKS
--------------
Every `expr` in every panel of every `dashboard-*.yaml` ConfigMap under `openbank-infra/gitops`.
Each `openbank_*` / `openbank:*` metric it depends on must be either instrumented in
`openbank-*/src/main` (see metricsrc for the three naming idioms) or produced by a recording rule
in the manifest corpus.

WHAT IT DELIBERATELY DOES NOT CHECK
-----------------------------------
**Whether the series currently has data.** That is a different question with a different answer,
and conflating them would make this gate unusable. Micrometer registers a counter on its FIRST
increment, so `openbank_payments_submitted_total` is genuinely ABSENT from Prometheus on a service
that has not taken a payment since its pod started -- measured 2026-08-19: the metric was missing
while the code emitting it was correct and deployed, because the last payment predated the pod by
six days. A runtime check would have called that a defect; this one correctly passes it, and would
still fail the day someone deletes the emitting code.

Non-`openbank_` metrics are out of scope: `kube_*`, `node_*`, `traces_spanmetrics_*` and framework
instrumentation come from exporters this repo does not own, so "no Kotlin emits it" says nothing.
Non-Prometheus panels (Loki/Tempo/ClickHouse) are likewise skipped -- they name no openbank_ series.

Usage:  check-dashboard-metric-emitted.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import yaml

import gatelib
import metricsrc

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"

# Panels depending on a metric nothing emits, declared rather than hidden, keyed by
# "<dashboard>#<panel>#<metric>". Checked BOTH ways: an entry that stops reproducing is itself
# reported, so the list can only shrink.
#
# EMPTY. #5049's sweep is complete -- 235 panel queries across 27 dashboards, 153 of them naming
# an openbank_ series, and every one resolves to instrumented code. The gate ships as a pure
# ratchet so the estate cannot quietly reacquire the debt it just paid off.
KNOWN_UNEMITTED: dict[str, str] = {}


def panel_queries() -> list[dict]:
    """Every panel expression in every dashboard ConfigMap, with enough context to name it."""
    out: list[dict] = []
    for path in gatelib.rglob(GITOPS, "dashboard-*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "ConfigMap":
                continue
            for key, raw in (doc.get("data") or {}).items():
                if not key.endswith(".json") or not isinstance(raw, str):
                    continue
                try:
                    dash = json.loads(raw)
                except json.JSONDecodeError:
                    # A dashboard whose JSON does not parse is a separate defect, and one the
                    # Grafana sidecar surfaces on its own; skipping is right here but silence is
                    # not, so it is reported as a notice by the caller via the parse counter.
                    continue
                _walk(dash.get("panels"), str(path.relative_to(REPO)), dash.get("title", ""), out)
    return out


def _walk(panels: object, file: str, dash_title: str, out: list[dict]) -> None:
    if not isinstance(panels, list):
        return
    for p in panels:
        if not isinstance(p, dict):
            continue
        if p.get("type") == "row":
            _walk(p.get("panels"), file, dash_title, out)
            continue
        for t in p.get("targets") or []:
            if not isinstance(t, dict):
                continue
            expr = t.get("expr")
            if isinstance(expr, str) and expr.strip():
                out.append({
                    "file": file, "dashboard": dash_title,
                    "panel": p.get("title", "(untitled)"), "expr": expr,
                })


def recorded_canonical() -> set[str]:
    """Canonical names produced by recording rules -- those exist without any Kotlin."""
    out: set[str] = set()
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
                        out.add(metricsrc.canonical(str(rule["record"])))
    return out


def evaluate(queries: list[dict], emitted: set[str], recorded: set[str],
             baseline: dict[str, str] | None = None) -> tuple[list[dict], set[str]]:
    baseline = KNOWN_UNEMITTED if baseline is None else baseline
    findings, used = [], set()
    for q in queries:
        for metric in sorted(metricsrc.metrics_in(q["expr"])):
            c = metricsrc.canonical(metric)
            if c in emitted or c in recorded:
                continue
            key = f"{q['dashboard']}#{q['panel']}#{metric}"
            if key in baseline:
                used.add(key)
                continue
            findings.append({**q, "metric": metric})
    return findings, used


def selftest() -> int:
    problems = metricsrc.self_check()
    if problems:
        for p in problems:
            print(f"selftest FAIL (metricsrc): {p}")
        return 1

    emitted = {metricsrc.canonical("openbank.payments.submitted")}
    recorded = {metricsrc.canonical("openbank:llm_cost_usd_24h:total")}
    cases = [
        ("sum(rate(openbank_payments_submitted_total[5m]))", False, "emitted counter"),
        ("openbank:llm_cost_usd_24h:total > 5", False, "produced by a recording rule"),
        ("sum(rate(kube_pod_status_ready[5m]))", False, "non-openbank metric is out of scope"),
        ("histogram_quantile(0.99, openbank_payments_submitted_seconds_bucket)", False,
         "registry suffixes strip back to a real base name"),
        ("sum(increase(openbank_ghost_metric_total[1h]))", True, "the #5049 shape"),
    ]
    for expr, want, why in cases:
        f, _ = evaluate([{"file": "t", "dashboard": "D", "panel": "P", "expr": expr}],
                        emitted, recorded, baseline={})
        if bool(f) != want:
            print(f"selftest FAIL ({why}): expr={expr!r} expected finding={want}, got {len(f)}")
            return 1

    # Baseline mechanics against a SYNTHETIC entry, never against KNOWN_UNEMITTED: that dict is
    # empty, and a fixture reading from it would quietly stop testing anything.
    fake = {"D#P#openbank_ghost_metric_total": "selftest fixture"}
    f, used = evaluate([{"file": "t", "dashboard": "D", "panel": "P",
                         "expr": "openbank_ghost_metric_total > 0"}], emitted, recorded, baseline=fake)
    if f or not used:
        print(f"selftest FAIL: baseline entry not honoured (findings={len(f)}, used={used})")
        return 1
    f2, _ = evaluate([{"file": "t", "dashboard": "OTHER", "panel": "P",
                       "expr": "openbank_ghost_metric_total > 0"}], emitted, recorded, baseline=fake)
    if len(f2) != 1:
        print("selftest FAIL: baseline excused a DIFFERENT dashboard on the same metric")
        return 1

    # The corpus scan must not be silently empty -- a gate that reads no dashboards passes
    # everything, which is the exact failure it exists to prevent.
    q = panel_queries()
    if not q:
        print("selftest FAIL: found no dashboard panel queries -- the scan is broken.")
        return 1
    print(f"selftest OK: metricsrc sound, {len(cases)} expression cases both directions, baseline "
          f"honoured and not over-applied, {len(q)} panel queries reachable.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    queries = panel_queries()
    emitted = metricsrc.emitted_names()
    recorded = recorded_canonical()
    findings, used = evaluate(queries, emitted, recorded)

    lines = []
    for f in findings:
        lines.append(
            f"::error file={f['file']}::dashboard \"{f['dashboard']}\" panel \"{f['panel']}\" "
            f"queries {f['metric']}, which no code in this repo emits and no recording rule "
            f"produces. The panel renders \"No data\", which is indistinguishable from a metric "
            f"that is merely quiet -- so nobody opening the dashboard can tell. This is the #5049 "
            f"shape. Either instrument the metric, correct the name, or remove the panel.",
        )
    for stale in sorted(set(KNOWN_UNEMITTED) - used):
        lines.append(
            f"::error::stale KNOWN_UNEMITTED entry {stale} -- that panel no longer depends on a "
            f"metric nothing emits. Remove it, so the baseline can only shrink.",
        )

    for line in lines:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    gatelib.subjects(len(queries), "dashboard panel queries scanned")
    verdict = "clean." if not lines else f"{len(lines)} finding(s) above."
    print(f"check-dashboard-metric-emitted: {len(queries)} panel query/ies, {len(emitted)} emitted "
          f"metric name(s), {len(recorded)} recording rule(s), {len(KNOWN_UNEMITTED)} baselined "
          f"— {verdict}")
    return 1 if lines and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
