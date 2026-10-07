#!/usr/bin/env bash
# POST an idempotent Pact publication or read-only provider selector with bounded 5xx retries.
# A 4xx is an answer, not broker downtime. Never echo credentials, request or response bodies.

set -euo pipefail

pact_broker_post() {
  local endpoint="$1" body_file="$2" response_file="$3" accept="$4"
  local headers_file="${5:-}" status_file="${6:-}"
  local -a delays=(0 20 40 80 120 120 120)
  local -a curl_headers=(-H 'Content-Type: application/json')
  local -a capture_headers=(-D /dev/null)
  local attempt=0 delay code rc

  if [[ -n "$accept" ]]; then curl_headers+=(-H "Accept: ${accept}"); fi
  if [[ -n "$headers_file" ]]; then capture_headers=(-D "$headers_file"); fi

  for delay in "${delays[@]}"; do
    attempt=$((attempt + 1))
    if (( delay > 0 )); then sleep "$delay"; fi
    code=000
    if code=$(curl -sS --connect-timeout 5 --max-time 30 \
      -o "$response_file" -w '%{http_code}' -X POST \
      -u "${PACT_BROKER_USERNAME}:${PACT_BROKER_PASSWORD}" \
      "${curl_headers[@]}" "${capture_headers[@]}" \
      "$endpoint" --data-binary "@${body_file}" 2>/dev/null); then
      rc=0
    else
      rc=$?
    fi

    if [[ -n "$status_file" ]]; then printf '%s\n' "${code:-000}" > "$status_file"; fi

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
    local n=0 arg previous='' header_file=''
    [[ -f "$state" ]] && n=$(cat "$state")
    n=$((n + 1))
    echo "$n" > "$state"
    if [[ "${TEST_CAPTURE:-}" == yes ]]; then
      for arg in "$@"; do
        if [[ "$previous" == -D ]]; then header_file="$arg"; fi
        if [[ "$previous" == -H && "$arg" == 'Accept: application/hal+json, application/json' ]]; then
          printf 'accept-present\n' > "$dir/accept"
        fi
        previous="$arg"
      done
      [[ -n "$header_file" ]] && printf 'HTTP/1.1 200 OK\r\nContent-Type: application/hal+json\r\n' > "$header_file"
    fi
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

  rm -f "$state" "$dir/sleeps"
  TEST_CAPTURE=yes TEST_FAILS=0 result=$(pact_broker_post x "$dir/body" "$dir/response" 'application/hal+json, application/json' "$dir/headers" "$dir/status")
  [[ "$result" == 200 && $(cat "$dir/status") == 200 && $(cat "$dir/accept") == accept-present ]] || return 1
  grep -q '^Content-Type: application/hal+json' "$dir/headers" || return 1

  rm -f "$state" "$dir/sleeps" "$dir/status"
  PACT_BROKER_PASSWORD=private-password TEST_FAILS=7 TEST_CODE=400 pact_broker_post https://private-host.invalid/path "$dir/body" "$dir/response" application/json "$dir/headers" "$dir/status" >"$dir/out" 2>&1 && return 1
  [[ $(cat "$dir/status") == 400 && $(cat "$state") == 1 ]] || return 1
  ! grep -Eq 'private-host|private-password|private-response' "$dir/out" || return 1

  rm -f "$state" "$dir/sleeps" "$dir/status"
  PACT_BROKER_PASSWORD=private-password TEST_TRANSPORT=yes pact_broker_post https://private-host.invalid/path "$dir/body" "$dir/response" application/json "$dir/headers" "$dir/status" >"$dir/out" 2>&1 && return 1
  [[ $(cat "$dir/status") == 000 && $(cat "$state") == 1 ]] || return 1
  ! grep -Eq 'private-host|private-password|private-response' "$dir/out" || return 1
  echo 'pact-broker-post self-test: 11 cases passed'
}

if [[ "${1:-}" == --self-test ]]; then
  self_test
else
  pact_broker_post "$@"
fi
