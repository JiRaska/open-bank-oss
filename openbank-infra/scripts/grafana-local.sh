#!/usr/bin/env bash
# Open the internal Grafana (observability single pane) through a
# port-forward. Grafana has NO public ingress by decision (ADR-0056) —
# login is Keycloak SSO (realm openbank) with a localhost redirect.
set -euo pipefail
echo "Grafana -> http://localhost:3000  (Ctrl+C ukončí port-forward)"
( sleep 2; open "http://localhost:3000" 2>/dev/null || xdg-open "http://localhost:3000" 2>/dev/null || true ) &
exec kubectl -n observability port-forward svc/kube-prometheus-stack-grafana 3000:80
