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


def self_test() -> int:
    """Falsify the egress classifier and the critical-route matcher.

    What this gate protects: a `severity=critical` alert whose receiver cannot LEAVE the
    cluster is an alert nobody is paged by. The failure is perfectly quiet — Alertmanager
    fires, the webhook resolves to a `.svc` name, the delivery succeeds, and the only person
    who would notice is the one who was supposed to be woken up.

    Both halves are string-shaped and both can be wrong while looking right: a receiver is
    "egress capable" by integration key or by a literal external URL, and a route is
    "critical" in two matcher dialects.
    """
    fails: list[str] = []

    def case(label, fn, arg, want):
        got = fn(arg)
        if got != want:
            fails.append(f"{label}: expected {want}, got {got}")

    # --- receiver egress ---------------------------------------------------------------
    # A named integration is egress by definition — that is the whole point of the key list.
    case("a slack receiver is egress capable", receiver_is_egress_capable,
         {"slack_configs": [{"channel": "#ops"}]}, True)
    case("a pagerduty receiver is egress capable", receiver_is_egress_capable,
         {"pagerduty_configs": [{"routing_key": "x"}]}, True)

    # THE DEFECT this gate exists for: a receiver that only posts INSIDE the cluster. The
    # delivery succeeds and nobody is paged.
    case("a .svc webhook is NOT egress", receiver_is_egress_capable,
         {"webhook_configs": [{"url": "http://alerta.monitoring.svc:8080/api"}]}, False)
    case("a cluster.local webhook is NOT egress", receiver_is_egress_capable,
         {"webhook_configs": [{"url": "http://x.monitoring.svc.cluster.local/api"}]}, False)
    case("a localhost webhook is NOT egress", receiver_is_egress_capable,
         {"webhook_configs": [{"url": "http://localhost:9090/hook"}]}, False)
    # ...and the shape that DOES leave.
    case("an external webhook is egress", receiver_is_egress_capable,
         {"webhook_configs": [{"url": "https://hooks.example.com/services/T/B/X"}]}, True)

    # An EMPTY receiver must not read as egress-capable. This is the direction that fails
    # silently: a receiver stub with nothing in it looks configured.
    case("an empty receiver is not egress", receiver_is_egress_capable, {}, False)
    case("a receiver with an empty key list is not egress", receiver_is_egress_capable,
         {"slack_configs": []}, False)
    # A webhook with no URL, or a blank one, proves nothing.
    case("a webhook with no url is not egress", receiver_is_egress_capable,
         {"webhook_configs": [{}]}, False)
    case("a webhook with a blank url is not egress", receiver_is_egress_capable,
         {"webhook_configs": [{"url": "   "}]}, False)
    # A templated URL is not a literal one: it may resolve anywhere, so it cannot be counted
    # as proof of egress. Pinned so the behaviour stays deliberate.
    case("a host containing .svc anywhere is internal", _webhook_url_is_external,
         {"url": "http://prom.svc.internal/x"}, False)

    # --- critical route matching --------------------------------------------------------
    # Both dialects in use here. Missing one silently halves the gate's coverage.
    case("the matchers list form selects critical", _matchers_select_critical,
         {"matchers": ['severity = "critical"']}, True)
    case("the legacy match map form selects critical", _matchers_select_critical,
         {"match": {"severity": "critical"}}, True)
    case("the regex-equals form selects critical", _matchers_select_critical,
         {"matchers": ['severity =~ "critical"']}, True)
    # Not critical, and must not be treated as such — a gate that thinks every route is
    # critical reports on routes nobody asked about.
    case("a warning route is not critical", _matchers_select_critical,
         {"matchers": ['severity = "warning"']}, False)
    case("a route with no matchers is not critical", _matchers_select_critical, {}, False)

    # --- route walking ------------------------------------------------------------------
    # Critical routes nest. A walker that only reads the top level misses the common shape,
    # where severity routing sits two levels down under a team split.
    hits: list[str] = []
    _walk_routes({"routes": [
        {"matchers": ['team = "payments"'], "routes": [
            {"matchers": ['severity = "critical"'], "receiver": "deep-pager"},
        ]},
        {"matchers": ['severity = "critical"'], "receiver": "top-pager"},
    ]}, hits)
    if sorted(hits) != ["deep-pager", "top-pager"]:
        fails.append(f"nested critical routes not both found: {hits}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: critical-alert-egress is falsifiable (17 cases)")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

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
