#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Classify the pinned k6 legacy summary without weakening the status contract."""

import argparse
import json
import math
import sys
from pathlib import Path


def classify(summary: object, k6_exit: int) -> str:
    if not isinstance(summary, dict) or not isinstance(summary.get("metrics"), dict):
        raise ValueError("missing or malformed k6 metrics")
    metrics = summary["metrics"]
    contract = metrics.get("security_contract_breaches")
    if not isinstance(contract, dict):
        raise ValueError("security contract metric is missing or malformed")
    # --summary-export in k6 0.54 exports metric.values as the metric itself.
    count = contract.get("count")
    if type(count) not in (int, float) or (
        isinstance(count, float) and (not math.isfinite(count) or not count.is_integer())
    ) or count < 0:
        raise ValueError("security contract count must be a finite nonnegative integer")
    if count > 0:
        raise ValueError("security contract breached, regardless of k6 exit status")

    for name in ("security_probes_total", "security_enumeration_probes_total", "http_reqs"):
        metric = metrics.get(name)
        count = metric.get("count") if isinstance(metric, dict) else None
        if type(count) not in (int, float) or (
            isinstance(count, float) and (not math.isfinite(count) or not count.is_integer())
        ) or count <= 0:
            raise ValueError(f"{name} must prove that HTTP probes actually ran")
    failed = []
    for name, metric in metrics.items():
        if not isinstance(metric, dict):
            raise ValueError("malformed k6 metric")
        thresholds = metric.get("thresholds", {})
        if not isinstance(thresholds, dict) or any(type(v) is not bool for v in thresholds.values()):
            raise ValueError("malformed legacy k6 threshold results")
        # Legacy summary booleans mean failure, unlike handleSummary's {ok: ...}.
        failed.extend(name for breached in thresholds.values() if breached)
    if k6_exit == 0:
        if failed:
            raise ValueError("failed threshold contradicts successful k6 exit")
        return "pass"
    if k6_exit == 99 and failed and all(name == "enumeration_ms" for name in failed):
        return "advisory-latency"
    raise ValueError("k6 failed outside the advisory enumeration latency threshold")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--k6-exit", type=int, required=True)
    args = parser.parse_args()
    try:
        result = classify(json.loads(args.summary.read_text()), args.k6_exit)
    except (OSError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    if result == "advisory-latency":
        print("::warning::Only the advisory enumeration latency threshold breached; security contract count is zero")
    print(json.dumps({"result": result, "security_contract_breaches": 0, "k6_exit": args.k6_exit}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
