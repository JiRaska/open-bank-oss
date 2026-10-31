#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Deception drill (ADR-0357): fire every tripwire once, on purpose, and measure how long each
# takes to reach its alerting state. A detection control is only as real as its last
# known-positive; this script is that known-positive, and its output is the MTTD evidence.
#
# What it does, in order — every step is harmless by construction:
#   1. GET a targeted edge decoy            -> Envoy answers 401, nothing reaches a backend
#   2. sts:GetCallerIdentity with the canary key (deny-all user; read from the SSM lure)
#   3. one `auth can-i` with the honey SA token (zero RBAC; read from its Secret)
# then polls each signal: the EventBridge rule's MatchedEvents metric, the CloudWatch alarm
# state, and Loki for the decoy route (only if a Loki URL is given).
#
# Expect TWO owner emails per run (canary key, honey SA): that is the drill working. Every
# request carries the user agent `openbank-deception-drill/<run-id>` so the email and the
# audit log say it was a drill.
#
# Usage:
#   AWS_PROFILE=openbank openbank-infra/scripts/deception-drill.sh [--loki http://localhost:3100]
# Needs: aws, kubectl (context on the sandbox), curl, python3. macOS date(1) syntax; works on
# the stock bash 3.2 (no associative arrays, on purpose).
set -euo pipefail

REGION=eu-north-1
LOKI=""
[[ "${1:-}" == "--loki" ]] && LOKI="${2:?--loki needs a URL}"
RUN="drill-$(date -u +%Y%m%dT%H%M%SZ)"
UA="openbank-deception-drill/${RUN}"
TIMEOUT=600
# shellcheck disable=SC2034  # read indirectly via ${!fired_var} in the summary
FIRED_decoy=0 FIRED_canary=0 FIRED_honeysa=0
SEEN_decoy='' SEEN_canary='' SEEN_honeysa=''

now() { date -u +%s; }
say() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }

# --- 1. edge decoy -----------------------------------------------------------------------
FIRED_decoy=$(now)
code=$(curl -s -o /dev/null -w '%{http_code}' -A "$UA" https://admin.open-bank.tech/api/v1/admin/backup)
say "decoy: HTTP ${code} (expect 401 from Envoy)"
[[ "$code" == "401" ]] || { say "FAIL: decoy route is not serving"; exit 1; }

# --- 2. canary key -----------------------------------------------------------------------
creds=$(aws ssm get-parameter --region "$REGION" --with-decryption \
  --name /openbank/sandbox/backup/replication-credentials --query Parameter.Value --output text)
AKID=$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["aws_access_key_id"])' "$creds")
SECRET=$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["aws_secret_access_key"])' "$creds")
unset creds
FIRED_canary=$(now)
env -u AWS_PROFILE AWS_ACCESS_KEY_ID="$AKID" AWS_SECRET_ACCESS_KEY="$SECRET" AWS_REGION="$REGION" \
  AWS_EXECUTION_ENV="$UA" aws sts get-caller-identity --region "$REGION" --query Arn --output text \
  | sed 's/^/canary: called as /'
unset SECRET

# --- 3. honey ServiceAccount token -------------------------------------------------------
TOKEN=$(kubectl -n arc-runners get secret deploy-prod-token -o jsonpath='{.data.token}' | base64 -d)
# shellcheck disable=SC2034  # read indirectly in the summary
FIRED_honeysa=$(now)
kubectl --token="$TOKEN" --user="" auth can-i list secrets -n arc-runners 2>/dev/null \
  | sed 's/^/honey SA: can-i list secrets = /' || true
unset TOKEN

# --- poll ---------------------------------------------------------------------------------
matched() {  # EventBridge rule matched events since the drill started
  aws cloudwatch get-metric-statistics --region "$REGION" --namespace AWS/Events \
    --metric-name MatchedEvents --dimensions Name=RuleName,Value=openbank-honeytoken-canary-key-used \
    --start-time "$(date -u -r "${FIRED_canary}" +%Y-%m-%dT%H:%M:%SZ)" --end-time "$(date -u -v+5M +%Y-%m-%dT%H:%M:%SZ)" \
    --period 60 --statistics Sum --query 'sum(Datapoints[].Sum)' --output text 2>/dev/null
}
alarm() {
  aws cloudwatch describe-alarms --region "$REGION" --alarm-names openbank-honeytoken-sa-used \
    --query 'MetricAlarms[0].StateValue' --output text
}
loki_hit() {
  [[ -n "$LOKI" ]] || return 1
  curl -sG "$LOKI/loki/api/v1/query_range" \
    --data-urlencode 'query={namespace="envoy-gateway-system", container="envoy"} | json | route_name=~"httproute/observability/edge-decoys-targeted/.*" |= "'"$RUN"'"' \
    --data-urlencode "start=${FIRED_decoy}000000000" --data-urlencode "limit=1" \
  | python3 -c 'import sys,json;sys.exit(0 if json.load(sys.stdin)["data"]["result"] else 1)'
}

deadline=$(( $(now) + TIMEOUT ))
while (( $(now) < deadline )); do
  [[ -z "${SEEN_canary:-}" ]] && v=$(matched) && [[ "$v" != "None" && "${v%.*}" -ge 1 ]] 2>/dev/null && SEEN_canary=$(now)
  [[ -z "${SEEN_honeysa:-}" && "$(alarm)" == "ALARM" ]] && SEEN_honeysa=$(now)
  [[ -z "${SEEN_decoy:-}" ]] && loki_hit && SEEN_decoy=$(now)
  [[ -n "${SEEN_canary:-}" && -n "${SEEN_honeysa:-}" && ( -z "$LOKI" || -n "${SEEN_decoy:-}" ) ]] && break
  sleep 15
done

echo
echo "run=${RUN}"
printf '%-10s %-10s %s\n' tripwire result "seconds-to-signal"
rc=0
for k in decoy canary honeysa; do
  seen_var="SEEN_$k"; fired_var="FIRED_$k"; seen="${!seen_var}"; fired="${!fired_var}"
  if [[ -n "$seen" ]]; then printf '%-10s %-10s %s\n' "$k" PASS "$(( seen - fired ))"
  elif [[ "$k" == decoy && -z "$LOKI" ]]; then printf '%-10s %-10s %s\n' "$k" SKIPPED "(no --loki)"
  else printf '%-10s %-10s %s\n' "$k" FAIL ">${TIMEOUT}"; rc=1; fi
done
echo "Seconds measure signal-in-AWS/Loki; email delivery adds seconds to a minute. Check both emails arrived."
exit $rc
