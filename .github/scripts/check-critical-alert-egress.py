#!/usr/bin/env python3
"""Critical-alert egress gate (rules.yaml: alerting).

Enforces the invariant: at least one Alertmanager route matching
`severity = "critical"` must terminate at a receiver that can actually reach a
human OUTSIDE the cluster.

WHY THIS EXISTS: this is the "severity inversion" — the failure it guards against
is invisible by inspection, because the config looks *more* careful for critical
than for warning. Critical alerts were routed to GoAlert (paging, escalation,
ack, dedup) and to HolmesGPT for RCA enrichment, while warning got a plain Slack
webhook. But GoAlert, the ntfy it fans out to, and the Holmes relay are all
cluster-internal Services with no Ingress (ADR-0056), so every critical delivery
path dead-ended inside the perimeter: reaching one required a kubectl
port-forward by someone who already suspected a problem. Warning escaped to
Slack. Net effect: the more severe the alert, the less likely a human saw it —
and a money-path service stayed down for days with nobody paged.

The subtlety this encodes: "has a paging receiver configured" is NOT the same
property as "a human is reachable". Only the second one matters at 03:00, and
only the second one is checked here.

A receiver counts as egress-capable if it configures a known external
integration (slack/pagerduty/opsgenie/email/...). A `webhook_configs` receiver
counts ONLY if its URL is demonstrably external: an in-cluster URL
(*.svc, *.cluster.local, localhost) does not count, and a `url_file` does not
count either — the URL is a mounted secret this script cannot resolve, so it
cannot be proven to leave the cluster (this is exactly the GoAlert case).
That is deliberately conservative: an unprovable path is treated as no path.

This gate does NOT argue against in-perimeter paging (ADR-0088's intent stands).
It only requires that it is not the ONLY leg.

Requires pyyaml (installed by the ci.yml step that precedes it). ENFORCED.
Usage: check-critical-alert-egress.py [kube-prometheus-stack.yaml path]
"""

from __future__ import annotations

import pathlib
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = REPO / "openbank-infra" / "gitops" / "apps" / "kube-prometheus-stack.yaml"

# Receiver config keys that, by construction, deliver to an external system.
EXTERNAL_INTEGRATION_KEYS = frozenset(
    {
        "slack_configs",
        "pagerduty_configs",
        "opsgenie_configs",
        "email_configs",
        "victorops_configs",
        "pushover_configs",
        "telegram_configs",
        "webex_configs",
        "discord_configs",
        "msteams_configs",
        "msteamsv2_configs",
        "sns_configs",
        "rocketchat_configs",
        "jira_configs",
    }
)

# Hostname suffixes/values that are unambiguously inside the cluster.
INTERNAL_HOST_MARKERS = (".svc", ".cluster.local", "localhost", "127.0.0.1")


def _webhook_url_is_external(webhook: dict) -> bool:
    """A webhook proves egress only if it has a literal, non-cluster-internal URL."""
    url = webhook.get("url")
    if not isinstance(url, str) or not url.strip():
        # url_file (or nothing): a secret-mounted URL we cannot resolve -> unprovable.
        return False
    host = url.split("://", 1)[-1].split("/", 1)[0].split(":", 1)[0].lower()
    return not any(marker in host for marker in INTERNAL_HOST_MARKERS)


def receiver_is_egress_capable(receiver: dict) -> bool:
    if any(receiver.get(key) for key in EXTERNAL_INTEGRATION_KEYS):
        return True
    return any(
        isinstance(w, dict) and _webhook_url_is_external(w)
        for w in receiver.get("webhook_configs") or []
    )


def _matchers_select_critical(route: dict) -> bool:
    """True if this route's matchers select severity=critical.

    Handles the `matchers: ['severity = "critical"']` form used by this repo as
    well as the legacy `match: {severity: critical}` map form.
    """
    match_map = route.get("match") or {}
    if isinstance(match_map, dict) and match_map.get("severity") == "critical":
        return True
    for matcher in route.get("matchers") or []:
        if not isinstance(matcher, str):
            continue
        normalized = matcher.replace('"', "").replace("'", "").replace(" ", "")
        if normalized in ("severity=critical", "severity=~critical"):
            return True
    return False


def _walk_routes(route: dict, receivers_hit: list[str]) -> None:
    for child in route.get("routes") or []:
        if not isinstance(child, dict):
            continue
        if _matchers_select_critical(child) and child.get("receiver"):
            receivers_hit.append(child["receiver"])
        _walk_routes(child, receivers_hit)


def main() -> int:
    manifest = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_MANIFEST
    if not manifest.is_file():
        print(f"::error::check-critical-alert-egress: {manifest} not found")
        return 1

    doc = yaml.safe_load(manifest.read_text())
    values = (((doc or {}).get("spec") or {}).get("source") or {}).get("helm") or {}
    alertmanager = (values.get("valuesObject") or {}).get("alertmanager") or {}

    if not alertmanager.get("enabled", False):
        print("check-critical-alert-egress: alertmanager disabled — nothing to check.")
        return 0

    config = alertmanager.get("config") or {}
    root = config.get("route") or {}
    receivers = {
        r["name"]: r for r in (config.get("receivers") or []) if isinstance(r, dict) and r.get("name")
    }

    critical_receivers: list[str] = []
    if _matchers_select_critical(root) and root.get("receiver"):
        critical_receivers.append(root["receiver"])
    _walk_routes(root, critical_receivers)

    if not critical_receivers:
        print(
            "::error::check-critical-alert-egress: no Alertmanager route matches "
            'severity = "critical" — critical alerts fall through to the default '
            "receiver and page nobody. Add a critical route to an egress-capable receiver."
        )
        return 1

    egressing = [n for n in critical_receivers if receiver_is_egress_capable(receivers.get(n, {}))]

    if not egressing:
        dead_ends = ", ".join(sorted(set(critical_receivers)))
        print(
            "::error::check-critical-alert-egress: SEVERITY INVERSION — every "
            f"severity=critical route dead-ends inside the cluster (receivers: {dead_ends}). "
            "None configures an external integration or a webhook with a literal "
            "non-cluster URL, so a critical alert cannot reach a human who is not already "
            "port-forwarding into the cluster. Route critical to a receiver that egresses "
            "(in-perimeter paging may stay alongside it, but not as the only leg). "
            "See rules.yaml: alerting.critical_alerts_must_egress."
        )
        return 1

    print(
        "check-critical-alert-egress: OK — severity=critical reaches egress-capable "
        f"receiver(s): {', '.join(sorted(set(egressing)))}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
