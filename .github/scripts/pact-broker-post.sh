#!/usr/bin/env bash
# POST an idempotent Pact publication or read-only provider selector with bounded 5xx retries.
# A 4xx is an answer, not broker downtime. Never echo credentials, request or response bodies.

set -euo pipefail

pact_broker_post() {
  local endpoint="$1" body_file="$2" response_file="$3" accept="$4"
  local -a delays=(0 20 40 80 120 120 120)
  local attempt=0 delay code rc

  for delay in "${delays[@]}"; do
    attempt=$((attempt + 1))
    if (( delay > 0 )); then sleep "$delay"; fi
    if code=$(curl -sS --connect-timeout 5 --max-time 30 \
      -o "$response_file" -w '%{http_code}' -X POST \
      -u "${PACT_BROKER_USERNAME}:${PACT_BROKER_PASSWORD}" \
      -H 'Content-Type: application/json' -H "Accept: ${accept}" \
      "$endpoint" --data-binary "@${body_file}" 2>/dev/null); then
      rc=0
    else
      rc=$?
    fi

    if (( rc != 0 )); then
      echo "::error::Pact Broker transport failed (curl exit ${rc}); no HTTP verdict." >&2
      return 1
    fi
    if [[ "$code" =~ ^2[0-9][0-9]$ ]]; then
      printf '%s\n' "$code"
      return 0
    fi
    if [[ ! "$code" =~ ^5[0-9][0-9]$ ]]; then
      echo "::error::Pact Broker returned HTTP ${code}; not retrying a non-5xx response." >&2
      return 1
    fi
    if (( attempt == ${#delays[@]} )); then
      echo "::error::Pact Broker remained unavailable (HTTP ${code}) after ${attempt} attempts." >&2
      return 1
    fi
    echo "::warning::Pact Broker HTTP ${code} on attempt ${attempt}/${#delays[@]}; retrying after a bounded delay." >&2
  done
}

self_test() {
  local dir state result
  dir=$(mktemp -d)
  trap 'rm -rf "$dir"' RETURN
  export PACT_BROKER_USERNAME=test PACT_BROKER_PASSWORD=test
  printf '{}' > "$dir/body"
  state="$dir/state"
  curl() {
    local n=0
    [[ -f "$state" ]] && n=$(cat "$state")
    n=$((n + 1))
    echo "$n" > "$state"
    if [[ "${TEST_TRANSPORT:-}" == yes ]]; then return 7; fi
    if (( n <= ${TEST_FAILS:-0} )); then printf '%s' "${TEST_CODE:-500}"; return 0; fi
    printf '200'
  }
  sleep() { echo "$1" >> "$dir/sleeps"; }

  TEST_FAILS=6 TEST_CODE=503 result=$(pact_broker_post x "$dir/body" "$dir/response" application/json 2>/dev/null)
  [[ "$result" == 200 && $(cat "$state") == 7 ]] || return 1
  [[ $(paste -sd, "$dir/sleeps") == '20,40,80,120,120,120' ]] || return 1

  rm -f "$state" "$dir/sleeps"
  TEST_FAILS=0 result=$(pact_broker_post x "$dir/body" "$dir/response" 'application/hal+json, application/json')
  [[ "$result" == 200 && $(cat "$state") == 1 && ! -e "$dir/sleeps" ]] || return 1

  for TEST_CODE in 400 404 429 302; do
    rm -f "$state" "$dir/sleeps"
    TEST_FAILS=7 pact_broker_post x "$dir/body" "$dir/response" application/json >/dev/null 2>&1 && return 1
    [[ $(cat "$state") == 1 && ! -e "$dir/sleeps" ]] || return 1
  done

  rm -f "$state" "$dir/sleeps"
  TEST_TRANSPORT=yes pact_broker_post x "$dir/body" "$dir/response" application/json >/dev/null 2>&1 && return 1
  [[ $(cat "$state") == 1 && ! -e "$dir/sleeps" ]] || return 1

  rm -f "$state" "$dir/sleeps"
  TEST_FAILS=7 TEST_CODE=500 pact_broker_post x "$dir/body" "$dir/response" application/json >/dev/null 2>&1 && return 1
  [[ $(cat "$state") == 7 ]] || return 1
  echo 'pact-broker-post self-test: 8 cases passed'
}

if [[ "${1:-}" == --self-test ]]; then
  self_test
else
  pact_broker_post "$@"
fi
