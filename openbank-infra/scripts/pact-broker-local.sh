#!/usr/bin/env bash
# Open the Pact Broker UI (contract matrix, ADR-0092) through a port-forward — a
# local alternative to the basic-auth ingress at https://pact.open-bank.tech
# (the broker is also exposed there for the mixed CI runner pool, ADR-0092).
# Basic-auth: the read-only creds are in OpenBao (openbank/pact-broker,
# read-only-username/read-only-password); fetch them with
#   kubectl -n pact-broker get secret pact-broker-basic-auth \
#     -o jsonpath='{.data.read-only-username}' | base64 -d
set -euo pipefail
echo "Pact Broker -> http://localhost:9292  (Ctrl+C ukončí port-forward)"
( sleep 2; open "http://localhost:9292" 2>/dev/null || xdg-open "http://localhost:9292" 2>/dev/null || true ) &
exec kubectl -n pact-broker port-forward svc/pact-broker 9292:9292
