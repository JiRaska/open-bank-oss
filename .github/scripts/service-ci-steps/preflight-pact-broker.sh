#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
endpoint="${PACT_BROKER_URL%/}/pacts/provider/${SERVICE}/for-verification"
body='{"consumerVersionSelectors":[{"mainBranch":true}],"includePendingStatus":false}'
body_file=$(mktemp)
trap 'rm -f "$body_file"' EXIT
printf '%s' "$body" > "$body_file"
http_code="$(bash "$RUNNER_TEMP/pact-broker-post.sh" \
  "$endpoint" "$body_file" /tmp/pact-preflight.json \
  'application/hal+json, application/json')"
if ! jq empty /tmp/pact-preflight.json 2>/dev/null; then
  byte_count="$(wc -c < /tmp/pact-preflight.json | tr -d ' ')"
  echo "::error::Pact Broker preflight for ${SERVICE}: HTTP $http_code but the body ($byte_count bytes) is not valid JSON. pact-jvm's HalClient cannot parse this either — expect a downstream NullPointerException in fetchConsumersWithSelectorsV2 if this preflight is bypassed. This is a broker-side malformed-response problem, not a ${SERVICE} contract regression."
  exit 1
fi
if ! jq -e 'has("_embedded") or has("_links")' /tmp/pact-preflight.json > /dev/null 2>&1; then
  echo "::error::Pact Broker preflight for ${SERVICE}: HTTP $http_code, valid JSON, but neither '_embedded' nor '_links' is present — not a HAL document. pact-jvm's HalClient expects HAL structure here and will NPE walking it. This is a broker-side response-shape problem, not a ${SERVICE} contract regression."
  exit 1
fi
echo "Pact Broker preflight for ${SERVICE}: HTTP $http_code, valid HAL/JSON — proceeding to provider verification."
