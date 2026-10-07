#!/usr/bin/env bash
# Diagnose the broker selector POST used by pact-jvm 4.7.3 provider verification (#7376).
# Emit only bounded response metadata. The endpoint, credentials, request and response bodies
# remain in process memory or private temporary files, never in public CI output.
set -euo pipefail

service="${1:?service required}"
branch="${2:?provider branch required}"
case "$service" in openbank-*) ;; *) echo "::error::Invalid provider name" >&2; exit 1 ;; esac
[[ "$service" =~ ^[a-z0-9-]+$ ]] || { echo "::error::Invalid provider name" >&2; exit 1; }
[[ -n "${PACT_BROKER_URL:-}" ]] || { echo "::error::Pact Broker URL missing" >&2; exit 1; }

tmp_dir=$(mktemp -d)
chmod 700 "$tmp_dir"
trap 'rm -rf "$tmp_dir"' EXIT
body_file="$tmp_dir/request.json"
response_file="$tmp_dir/response.json"
headers_file="$tmp_dir/headers"
status_file="$tmp_dir/status"

# PactBrokerLoader builds an empty consumerVersionSelectors list from the repository's default
# @PactBroker annotation (no tags/selectors/method), and the workflow passes enablePending=true
# and providerBranch=BRANCH. PactBrokerClient.fetchPactsUsingNewEndpoint adds the remaining fields.
# Keep this in step with the JVM arguments immediately below the preflight workflow step.
jq -nc --arg branch "$branch" '{
  consumerVersionSelectors: [],
  includePendingStatus: true,
  providerVersionTags: [],
  providerVersionBranch: $branch
}' > "$body_file"

endpoint="${PACT_BROKER_URL%/}/pacts/provider/${service}/for-verification"
post_helper="${PACT_POST_HELPER:-${RUNNER_TEMP:?}/pact-broker-post.sh}"
post_rc=0
http_code=$(bash "$post_helper" "$endpoint" "$body_file" "$response_file" 'application/hal+json, application/json' "$headers_file" "$status_file") || post_rc=$?
status="${http_code:-$(cat "$status_file" 2>/dev/null || printf 000)}"
[[ "$status" =~ ^[0-9]{3}$ ]] || status=000

# Header values are untrusted response data. Show a MIME token only when it is short, printable
# and syntactically safe; otherwise report "invalid". Do not log arbitrary headers.
content_type=$(awk '
  BEGIN { IGNORECASE=1; value="missing" }
  tolower($0) ~ /^content-type:[[:space:]]*/ {
    sub(/^[^:]*:[[:space:]]*/, "", $0); sub(/\r$/, "", $0); value=$0
  }
  END { print value }
' "$headers_file" 2>/dev/null || printf missing)
mime="${content_type%%;*}"
mime=$(printf '%s' "$mime" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
if [[ "$content_type" == missing ]]; then
  mime=missing
elif [[ ! "$mime" =~ ^[a-z0-9.+-]+/[a-z0-9.+-]+$ || ${#mime} -gt 64 ]]; then
  mime=invalid
fi
case "$mime" in
  application/json|application/hal+json|text/html|text/plain|missing|invalid) ;;
  *) mime=other ;;
esac

# Only report presence of the one fixed relation pact-jvm reads. Other relation keys are
# broker-controlled response-body data and could themselves carry private identifiers.
relations=none
if [[ -f "$response_file" ]] && jq -e 'type == "object"' "$response_file" >/dev/null 2>&1; then
  if jq -e '._embedded | type == "object" and has("pacts")' "$response_file" >/dev/null 2>&1; then
    relations=pacts
  fi
fi
echo "Pact selector preflight for ${service}: HTTP ${status}; Content-Type ${mime}; HAL relations ${relations}."

if (( post_rc != 0 )); then
  echo "::error::Pact selector POST failed before provider verification; broker status above is diagnostic, not a contract mismatch." >&2
  exit 1
fi
if [[ "$mime" != application/json && "$mime" != application/hal+json ]]; then
  echo "::error::Pact selector response has no usable JSON Content-Type; pact-jvm cannot parse it safely." >&2
  exit 1
fi
if ! jq -e 'type == "object" and (._embedded.pacts | type) == "array"' "$response_file" >/dev/null 2>&1; then
  echo "::error::Pact selector response lacks the required HAL pacts array; provider verification cannot proceed." >&2
  exit 1
fi
