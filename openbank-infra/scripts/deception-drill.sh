#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Deception drill (ADR-0361): fire every tripwire once, on purpose, and measure how long each
# takes to reach its alerting state. A detection control is only as real as its last
# known-positive; this script is that known-positive, and its output is the MTTD evidence.
#
# What it does, in order — every step is harmless by construction:
#   1. GET a targeted edge decoy            -> Envoy answers 401, nothing reaches a backend
#   2. sts:GetCallerIdentity with the watched key (deny-all user; read from its SSM parameter)
#   3. one `auth can-i` with the watched ServiceAccount token (zero RBAC; read from its Secret)
# then polls each signal: the EventBridge rule's MatchedEvents metric, the CloudWatch alarm
# state, and Loki for the decoy route (only if a Loki URL is given).
#
# Expect TWO owner emails per run (key, ServiceAccount token; ONE with --scope aws): that is the
# drill working. Requests carry the user agent `openbank-deception-drill/<run-id>` for the audit
# log, but a user agent is attacker-controlled and never proves a drill: only a deception-drill
# run on main that started minutes before the email does.
#
# Usage:
#   export DRILL_DECOY_URL=...        # a targeted edge decoy URL (see the edge-decoys route)
#   export DRILL_SSM_PARAM=...        # SSM parameter holding the watched key (tofu state, deception.tf)
#   export DRILL_SA_NAMESPACE=...     # namespace and Secret of the watched token (gitops manifest)
#   export DRILL_SA_TOKEN_SECRET=...
#   AWS_PROFILE=openbank openbank-infra/scripts/deception-drill.sh [--loki http://localhost:3100]
#   openbank-infra/scripts/deception-drill.sh --scope aws      # scheduled run (deception-drill.yml)
# The identifiers are inputs, not constants, so this file does not restate where the tripwires sit.
# --scope aws fires only what an identity without Kubernetes or Loki access can: the decoy (its
# 401 only) and the watched key, and additionally asserts the alert SNS topic DELIVERED a
# notification (email). The ServiceAccount and Loki rows print SKIPPED, never PASS, and the
# DRILL_SA_* inputs are not required.
# Needs: aws, curl, python3 (+ kubectl for the full scope). Portable: GNU and BSD userland, and
# the stock macOS bash 3.2 (no associative arrays, on purpose). TIMEOUT and POLL override the
# window and the poll interval (the self-test uses them).
set -euo pipefail

REGION=eu-north-1
# Physical names of the signals, as declared in aws/envs/sandbox-substrate/deception.tf.
KEY_RULE=openbank-honeytoken-canary-key-used
SA_ALARM=openbank-honeytoken-sa-used
TOPIC=openbank-honeytoken-alerts
LOKI=""
SCOPE=full
while [[ $# -gt 0 ]]; do
  case "$1" in
    --loki) LOKI="${2:?--loki needs a URL}"; shift 2 ;;
    --scope) SCOPE="${2:?--scope needs full|aws}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ "$SCOPE" == full || "$SCOPE" == aws ]] || { echo "--scope must be full or aws" >&2; exit 2; }
: "${DRILL_DECOY_URL:?set DRILL_DECOY_URL}" "${DRILL_SSM_PARAM:?set DRILL_SSM_PARAM}"
if [[ "$SCOPE" == full ]]; then
  : "${DRILL_SA_NAMESPACE:?set DRILL_SA_NAMESPACE}" "${DRILL_SA_TOKEN_SECRET:?set DRILL_SA_TOKEN_SECRET}"
fi
RUN="drill-$(date -u +%Y%m%dT%H%M%SZ)"
UA="openbank-deception-drill/${RUN}"
TIMEOUT="${TIMEOUT:-600}"
POLL="${POLL:-15}"
# shellcheck disable=SC2034  # read indirectly via ${!fired_var} in the summary
FIRED_decoy=0 FIRED_key=0 FIRED_sa=0 FIRED_delivery=0
SEEN_decoy='' SEEN_key='' SEEN_sa='' SEEN_delivery=''

now() { date -u +%s; }
iso() { python3 -c 'import sys,datetime;print(datetime.datetime.fromtimestamp(int(sys.argv[1]),datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$1"; }
say() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }

# --- 1. edge decoy -----------------------------------------------------------------------
FIRED_decoy=$(now)
code=$(curl -s -o /dev/null -w '%{http_code}' -A "$UA" "$DRILL_DECOY_URL")
say "decoy: HTTP ${code} (expect 401 from Envoy)"
[[ "$code" == "401" ]] || { say "FAIL: decoy route is not serving"; exit 1; }

# --- 2. watched key -----------------------------------------------------------------------
creds=$(aws ssm get-parameter --region "$REGION" --with-decryption \
  --name "$DRILL_SSM_PARAM" --query Parameter.Value --output text)
AKID=$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["aws_access_key_id"])' "$creds")
SECRET=$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["aws_secret_access_key"])' "$creds")
unset creds
FIRED_key=$(now)
FIRED_delivery=$FIRED_key
env -u AWS_PROFILE AWS_ACCESS_KEY_ID="$AKID" AWS_SECRET_ACCESS_KEY="$SECRET" AWS_REGION="$REGION" \
  AWS_EXECUTION_ENV="$UA" aws sts get-caller-identity --region "$REGION" --query Arn --output text >/dev/null
# The identity is not printed: this output is posted to a public issue on failure.
say "watched key: called"
unset SECRET

# --- 3. watched ServiceAccount token -------------------------------------------------------
if [[ "$SCOPE" == full ]]; then
  TOKEN=$(kubectl -n "$DRILL_SA_NAMESPACE" get secret "$DRILL_SA_TOKEN_SECRET" -o jsonpath='{.data.token}' | base64 -d)
  # shellcheck disable=SC2034  # read indirectly in the summary
  FIRED_sa=$(now)
  kubectl --token="$TOKEN" --user="" auth can-i list secrets -n "$DRILL_SA_NAMESPACE" 2>/dev/null \
    | sed 's/^/watched SA: can-i list secrets = /' || true
  unset TOKEN
fi

# --- poll ---------------------------------------------------------------------------------
matched() {  # EventBridge rule matched events since the drill started
  aws cloudwatch get-metric-statistics --region "$REGION" --namespace AWS/Events \
    --metric-name MatchedEvents --dimensions Name=RuleName,Value="$KEY_RULE" \
    --start-time "$(iso $(( FIRED_key / 60 * 60 )))" --end-time "$(iso $(( $(now) + 300 )))" \
    --period 60 --statistics Sum --query 'sum(Datapoints[].Sum)' --output text 2>/dev/null
}
delivered() {  # notifications the alert topic delivered to its subscribers since the drill
  aws cloudwatch get-metric-statistics --region "$REGION" --namespace AWS/SNS \
    --metric-name NumberOfNotificationsDelivered --dimensions Name=TopicName,Value="$TOPIC" \
    --start-time "$(iso $(( FIRED_delivery / 60 * 60 )))" --end-time "$(iso $(( $(now) + 300 )))" \
    --period 60 --statistics Sum --query 'sum(Datapoints[].Sum)' --output text 2>/dev/null
}
# Windows start at the MINUTE the drill fired, never earlier: a 60 s lead once reached into the
# previous one-minute bucket and counted a notification sent before the drill (a subscription
# confirmation), so delivery read PASS while every subscription was still pending (2026-10-31).
ge1() { [[ -n "$1" && "$1" != "None" ]] && python3 -c 'import sys;sys.exit(0 if float(sys.argv[1])>=1 else 1)' "$1" 2>/dev/null; }
alarm() {
  aws cloudwatch describe-alarms --region "$REGION" --alarm-names "$SA_ALARM" \
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
  [[ -z "${SEEN_key:-}" ]] && ge1 "$(matched || true)" && SEEN_key=$(now)
  [[ -z "${SEEN_delivery:-}" ]] && ge1 "$(delivered || true)" && SEEN_delivery=$(now)
  [[ "$SCOPE" == full && -z "${SEEN_sa:-}" && "$(alarm)" == "ALARM" ]] && SEEN_sa=$(now)
  [[ -z "${SEEN_decoy:-}" ]] && loki_hit && SEEN_decoy=$(now)
  [[ -n "${SEEN_key:-}" && -n "${SEEN_delivery:-}" && ( "$SCOPE" == aws || -n "${SEEN_sa:-}" ) && ( -z "$LOKI" || -n "${SEEN_decoy:-}" ) ]] && break
  sleep "$POLL"
done

# Exactly one matched event per run. A second one in the window is someone else using the key,
# and must not be absorbed into the drill's PASS. Count only after SETTLE seconds, so a late
# CloudTrail delivery is inside the count, and treat an unreadable count as a failure.
COUNT=""
if [[ -n "${SEEN_key:-}" ]]; then
  sleep "${SETTLE:-180}"
  COUNT=$(matched || true)
fi

# The verdict alone is also written to DRILL_VERDICT (the workflow posts that file, and only that
# file, to a public issue), so nothing printed above it can reach the issue.
VERDICT="${DRILL_VERDICT:-/dev/null}"
: > "$VERDICT"
# shellcheck disable=SC2059  # every caller passes a literal format as $1
out() { printf "$@" | tee -a "$VERDICT"; }
echo
out '%s\n' "run=${RUN}"
out '%s\n' "fired_at=$(iso "$FIRED_key")  # the key alert is this drill only if its <time> is this minute AND matched=1"
out '%-10s %-10s %s\n' tripwire result "seconds-to-signal"
rc=0
for k in decoy key delivery sa; do
  seen_var="SEEN_$k"; fired_var="FIRED_$k"; seen="${!seen_var}"; fired="${!fired_var}"
  if [[ -n "$seen" ]]; then out '%-10s %-10s %s\n' "$k" PASS "$(( seen - fired ))"
  elif [[ "$k" == decoy && -z "$LOKI" ]]; then out '%-10s %-10s %s\n' "$k" SKIPPED "(no --loki; 401 asserted above)"
  elif [[ "$k" == sa && "$SCOPE" == aws ]]; then out '%-10s %-10s %s\n' "$k" SKIPPED "(--scope aws: monthly manual drill)"
  else out '%-10s %-10s %s\n' "$k" FAIL ">${TIMEOUT}"; rc=1; fi
done
if [[ -n "${SEEN_key:-}" ]]; then
  if [[ -z "$COUNT" || "$COUNT" == "None" ]] || ! python3 -c 'import sys;float(sys.argv[1])' "$COUNT" 2>/dev/null; then
    out '%-10s %-10s %s\n' count FAIL "matched=unreadable after settle: cannot rule out a second event"
    rc=1
  elif python3 -c 'import sys;sys.exit(0 if float(sys.argv[1])>1 else 1)' "$COUNT"; then
    out '%-10s %-10s %s\n' count FAIL "matched=${COUNT} (expected 1): treat the other event as a compromise"
    rc=1
  else
    out '%-10s %-10s %s\n' count PASS "matched=${COUNT}"
  fi
fi
echo "Seconds measure signal-in-AWS/Loki. delivery = the SNS topic handed the key alert to its subscribers (email)."
exit $rc
