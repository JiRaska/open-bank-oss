#!/usr/bin/env python3
"""Render and optionally publish the standing-critical Alertmanager digest.

The script is intentionally stdlib-only.  It fails closed when credentials or the
Alertmanager response are missing; an empty, unauthenticated response is never
reported as "zero criticals".  The P90 value is the age of currently standing
critical alerts (not MTTR, which requires resolved-alert history).
"""
from __future__ import annotations

import datetime as dt
import json
import os
import sys
import urllib.error
import urllib.request


def parse_time(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = (len(ordered) - 1) * p
    low, high = int(rank), min(int(rank) + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (rank - low)


def render(alerts: list[dict], now: dt.datetime) -> str:
    rows = []
    ages = []
    for alert in alerts:
        labels = alert.get("labels") or {}
        if labels.get("severity") != "critical":
            continue
        started = parse_time(alert.get("startsAt", ""))
        age = max(0.0, (now - started).total_seconds())
        ages.append(age)
        hours = age / 3600
        rows.append((hours, labels.get("alertname", "<unnamed>"), labels.get("service", labels.get("job", "-"))))
    rows.sort(reverse=True)
    p90 = percentile(ages, 0.90) / 3600
    lines = ["# Standing critical alerts", "", f"Open criticals: **{len(rows)}**", f"Standing-age P90: **{p90:.1f} h** (not MTTR)", ""]
    if rows:
        lines += ["| Age | Alert | Service |", "|---:|---|---|"]
        lines += [f"| {hours:.1f} h | `{name}` | `{service}` |" for hours, name, service in rows]
    else:
        lines.append("No standing critical alerts returned by Alertmanager.")
    return "\n".join(lines) + "\n"


def fetch(url: str, token: str) -> list[dict]:
    request = urllib.request.Request(
        url.rstrip("/") + "/api/v2/alerts?filter=severity%3D%22critical%22",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            if response.status != 200:
                raise RuntimeError(f"Alertmanager returned HTTP {response.status}")
            payload = json.load(response)
    except (OSError, ValueError, urllib.error.URLError) as exc:
        raise RuntimeError(f"Alertmanager query failed: {exc}") from exc
    if not isinstance(payload, list):
        raise RuntimeError("Alertmanager response was not an alert array")
    return payload


def self_test() -> None:
    now = dt.datetime(2026, 8, 18, 12, tzinfo=dt.timezone.utc)
    fixture = [{"labels": {"severity": "critical", "alertname": "A", "service": "ledger"}, "startsAt": "2026-08-18T08:00:00Z"}, {"labels": {"severity": "warning", "alertname": "ignored"}, "startsAt": "2026-08-18T00:00:00Z"}]
    output = render(fixture, now)
    assert "Open criticals: **1**" in output and "4.0 h" in output and "ledger" in output
    try:
        fetch("http://127.0.0.1:1", "token")
    except RuntimeError:
        pass
    else:
        raise AssertionError("network failures must fail closed")
    print("self-test ok: standing-critical-digest (render + fail-closed fetch)")


def main() -> int:
    if "--self-test" in sys.argv:
        self_test()
        return 0
    url, token = os.environ.get("ALERTMANAGER_URL"), os.environ.get("ALERTMANAGER_DIGEST_TOKEN")
    if not url or not token:
        print("ERROR: ALERTMANAGER_URL and ALERTMANAGER_DIGEST_TOKEN are required", file=sys.stderr)
        return 2
    now = dt.datetime.now(dt.timezone.utc)
    try:
        alerts = fetch(url, token)
        digest = render(alerts, now)
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    # Persist the raw active-alert array so alert-rca-ledger.py folds the SAME
    # observation the digest reports (ADR-0241 D3). Only written on the success
    # path: a fail-closed run must not hand the ledger an empty observation,
    # which observe() would read as "everything resolved".
    alerts_output = os.environ.get("DIGEST_ALERTS_OUTPUT")
    if alerts_output:
        with open(alerts_output, "w", encoding="utf-8") as stream:
            json.dump({"observedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"), "alerts": alerts}, stream)
    output = os.environ.get("DIGEST_OUTPUT", "standing-critical-digest.md")
    with open(output, "w", encoding="utf-8") as stream:
        stream.write(digest)
    print(digest, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
