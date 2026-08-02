#!/usr/bin/env bash
# BREAK-GLASS access to the internal Grafana via port-forward.
#
# The normal way in is https://admin.open-bank.tech/tools/grafana — a sub-path of
# the console, behind the identity-aware edge gate (ADR-0234). Grafana still has
# NO Ingress of its own; nginx runs an auth_request against the admin-UI session
# before it proxies anything, so ADR-0056's "no pre-auth surface on the internet"
# still holds.
#
# This script stays for the case that path cannot serve you: the gate fails
# CLOSED, so if the admin-UI pod is down, Grafana is unreachable from the browser
# and this port-forward is how you reach the dashboards that would tell you why.
#
# CAVEAT: Keycloak SSO does NOT complete over this port-forward any more. Grafana
# has exactly one root_url and it now carries the public sub-path, so the OIDC
# redirect leaves localhost. Sign in with a local Grafana admin account instead.
set -euo pipefail
echo "Grafana (break-glass) -> http://localhost:3000  (Ctrl+C ukončí port-forward)"
echo "Běžný přístup: https://admin.open-bank.tech/tools/grafana"
echo "SSO přes tento port-forward NEFUNGUJE — použij lokální Grafana admin účet."
( sleep 2; open "http://localhost:3000" 2>/dev/null || xdg-open "http://localhost:3000" 2>/dev/null || true ) &
exec kubectl -n observability port-forward svc/kube-prometheus-stack-grafana 3000:80
