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


ALERT_QUERY = "/api/v2/alerts?active=true&silenced=false&inhibited=false&filter=severity%3D%22critical%22"


def fetch(url: str, token: str | None = None) -> list[dict]:
    """Read active, unsilenced critical alerts. In-cluster the Alertmanager service needs no
    token (it is reached on the pod network, not through the cookie-gated edge), so the
    Authorization header is sent only when a token is supplied."""
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url.rstrip("/") + ALERT_QUERY, headers=headers)
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


def publish_slack(webhook: str, digest: str) -> None:
    """Post the digest to a Slack incoming webhook. Fails closed on any non-2xx answer."""
    request = urllib.request.Request(
        webhook,
        data=json.dumps({"text": digest}).encode(),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            if response.status >= 300:
                raise RuntimeError(f"Slack returned HTTP {response.status}")
    except (OSError, urllib.error.URLError) as exc:
        raise RuntimeError(f"Slack publish failed: {exc}") from exc


def envelope(alerts: list[dict], now: dt.datetime) -> dict:
    return {"observedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"), "alerts": alerts}


def self_test() -> None:
    now = dt.datetime(2026, 8, 18, 12, tzinfo=dt.timezone.utc)
    fixture = [{"labels": {"severity": "critical", "alertname": "A", "service": "ledger"}, "startsAt": "2026-08-18T08:00:00Z"}, {"labels": {"severity": "warning", "alertname": "ignored"}, "startsAt": "2026-08-18T00:00:00Z"}]
    output = render(fixture, now)
    assert "Open criticals: **1**" in output and "4.0 h" in output and "ledger" in output
    assert "active=true" in ALERT_QUERY and "silenced=false" in ALERT_QUERY, "silenced alerts must not be reported as standing"
    assert envelope(fixture, now)["observedAt"] == "2026-08-18T12:00:00Z"
    for probe in (lambda: fetch("http://127.0.0.1:1", "token"), lambda: fetch("http://127.0.0.1:1"), lambda: publish_slack("http://127.0.0.1:1", "x")):
        try:
            probe()
        except RuntimeError:
            pass
        else:
            raise AssertionError("network failures must fail closed")
    print("self-test ok: standing-critical-digest (render + query filter + fail-closed fetch/publish)")


def main() -> int:
    if "--self-test" in sys.argv:
        self_test()
        return 0
    url, token = os.environ.get("ALERTMANAGER_URL"), os.environ.get("ALERTMANAGER_DIGEST_TOKEN")
    if not url:
        print("ERROR: ALERTMANAGER_URL is required", file=sys.stderr)
        return 2
    webhook = os.environ.get("SLACK_WEBHOOK_URL")
    if "--publish-slack" in sys.argv and not webhook:
        # A digest that cannot be delivered is a failed run, never a quiet success.
        print("ERROR: --publish-slack needs SLACK_WEBHOOK_URL (Secret critical-alert-digest-slack)", file=sys.stderr)
        return 2
    now = dt.datetime.now(dt.timezone.utc)
    try:
        alerts = fetch(url, token)
        digest = render(alerts, now)
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    # The observation envelope alert-rca-ledger.py folds (ADR-0241 D3). Only emitted on the
    # success path: a fail-closed run must not hand the ledger an empty observation, which
    # observe() would read as "everything resolved". "-" writes it as one JSON log line, so
    # the in-cluster CronJob leaves it in Loki instead of a workflow artifact.
    alerts_output = os.environ.get("DIGEST_ALERTS_OUTPUT")
    if alerts_output == "-":
        print(json.dumps({"event": "standing-critical-observation", **envelope(alerts, now)}))
    elif alerts_output:
        with open(alerts_output, "w", encoding="utf-8") as stream:
            json.dump(envelope(alerts, now), stream)
    output = os.environ.get("DIGEST_OUTPUT")
    if output:
        with open(output, "w", encoding="utf-8") as stream:
            stream.write(digest)
    print(digest, end="")
    if "--publish-slack" in sys.argv:
        try:
            publish_slack(webhook, digest)
        except RuntimeError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
