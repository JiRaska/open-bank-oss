#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Shared source-of-truth for "which openbank_* metrics does this repo actually emit?".

WHY THIS EXISTS
---------------
Two gates ask the same question of the same corpus -- check-alert-metric-emitted.py (does an
ALERT depend on a metric nothing emits?) and check-dashboard-metric-emitted.py (does a PANEL?).
Answering it correctly is not one line: it needs all three of this repo's metric-naming idioms
canonicalised, KDoc prose excluded, and Micrometer's generated suffixes stripped from both sides.

Duplicating that across two files would reproduce the exact defect class both gates exist to
catch: two artifacts that must agree, with nothing checking that they do. The first divergence
would be silent and would look like a gate finding fewer problems, which is indistinguishable
from a gate finding no problems.

THE THREE IDIOMS
----------------
    "openbank.payments.submitted"                                DomainMetrics, dotted
    registry.counter("openbank_statement_close_failures_total")  CloseMetricsAdapter, exported name
    "openbank.ledger.accounting_day.stuck_cutoff_days"           AccountingDayScheduler, mixed

All three name real, live series. A checker that understands only the first reports the other two
as missing -- measured: it flagged six working alerts before [canonical] existed.
"""

from __future__ import annotations

import pathlib
import re

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]

COMMENT_RE = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)
LITERAL_RE = re.compile(r'"(openbank[._][A-Za-z0-9._]*)"')
METRIC_RE = re.compile(r"\b(openbank[_:][a-zA-Z0-9_:]*)\b")

# Suffixes the Prometheus registry appends; stripped from BOTH sides before comparing.
SUFFIXES = ("_total", "_bucket", "_count", "_sum", "_max", "_seconds")

# PromQL functions/keywords and common label names, so they are not mistaken for metric names.
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


def canonical(name: str) -> str:
    """One spelling for a metric, whichever idiom it was written in.

    Separators collapse to `_` and the registry's generated suffixes come off, so
    `openbank.payments.submitted`, `openbank_payments_submitted_total` and
    `openbank_payments_submitted_seconds_count` all reduce to the same key.
    """
    n = name.strip().lower().replace(".", "_").replace(":", "_")
    changed = True
    while changed:
        changed = False
        for suf in SUFFIXES:
            if n.endswith(suf) and len(n) > len(suf):
                n = n[: -len(suf)]
                changed = True
    return n


def metrics_in(expr: str) -> set[str]:
    """openbank_* / openbank:* metric names an expression depends on."""
    return {m for m in METRIC_RE.findall(expr) if m not in PROMQL_KW}


def emitted_names() -> set[str]:
    """Canonical metric names instrumented by service source.

    Comments are stripped BEFORE the literal scan. Metric names are quoted in KDoc all over this
    tree -- `FxFixingFreshnessPort` names three it does not emit -- and counting prose as
    instrumentation is how a checker certifies a metric that exists only in a sentence (the same
    trap as grepping `src/test` for the word "contract").

    Scoped per service-module rather than one rglob over REPO: the repo root also holds dozens of
    transient git worktrees under .claude/worktrees, and walking those costs minutes while adding
    nothing (they are copies of the same sources).
    """
    out: set[str] = set()
    for svc in sorted(REPO.glob("openbank-*")):
        src = svc / "src" / "main"
        if not src.is_dir():
            continue
        for path in gatelib.rglob(src, "*.kt"):
            try:
                body = COMMENT_RE.sub(" ", gatelib.read_text(path))
            except (UnicodeDecodeError, OSError):
                continue
            out.update(canonical(m) for m in LITERAL_RE.findall(body))
    return out


def self_check() -> list[str]:
    """Failures of this module's own guarantees, empty when sound.

    Shared by both gates' selftests: if the canonicalisation regresses, BOTH must go red, and a
    module that only its callers test is a module nothing tests directly.
    """
    problems = []
    same = [
        ("openbank.payments.submitted", "openbank_payments_submitted_total"),
        ("openbank.payments.submitted", "openbank_payments_submitted_seconds_count"),
        ("openbank_statement_close_failures_total", "openbank.statement.close.failures"),
        ("openbank.ledger.accounting_day.stuck_cutoff_days",
         "openbank_ledger_accounting_day_stuck_cutoff_days"),
    ]
    for a, b in same:
        if canonical(a) != canonical(b):
            problems.append(f"canonical({a!r}) != canonical({b!r})")
    if canonical("openbank_payments_submitted") == canonical("openbank_payments_completed"):
        problems.append("canonical() collapses two DIFFERENT metrics to one key")
    if metrics_in("sum(rate(kube_pod_status_ready[5m]))"):
        problems.append("metrics_in() claimed a non-openbank metric")
    if "openbank_llm_requests" not in {canonical(m) for m in
                                       metrics_in("sum(rate(openbank_llm_requests_total[5m]))")}:
        problems.append("metrics_in() missed a plain openbank_* counter")
    if not emitted_names():
        problems.append("emitted_names() found nothing -- the source scan is broken")
    return problems
