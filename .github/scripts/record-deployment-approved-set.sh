#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Record a GitOps PR's Pact versions only after their complete exact-version set is
# compatible with the environment. A newer byte-equivalent published version may
# have no provider verification yet; source equivalence alone is not a matrix verdict.
set -eo pipefail

plan="${1:?usage: record-deployment-approved-set.sh PLAN CLI}"
cli="${2:?usage: record-deployment-approved-set.sh PLAN CLI}"
: "${PACT_BROKER_URL:?}" "${PACT_BROKER_USERNAME:?}" "${PACT_BROKER_PASSWORD:?}"
[[ -r "$plan" && -x "$cli" ]] || { echo 'unreadable plan or CLI' >&2; exit 1; }

declare -a selectors=() services=() versions=()
while IFS=$'\t' read -r svc version extra || [[ -n "${svc:-}" ]]; do
  [[ -z "${extra:-}" && "$svc" =~ ^openbank-[a-z0-9-]+$ && "$version" =~ ^[0-9a-f]{40}$ ]] || {
    echo 'invalid Pact deployment plan' >&2; exit 1;
  }
  for existing in "${services[@]}"; do
    [[ "$existing" != "$svc" ]] || { echo 'duplicate Pact participant in deployment plan' >&2; exit 1; }
  done
  services+=("$svc")
  versions+=("$version")
  selectors+=(--pacticipant "$svc" --version "$version")
done <"$plan"
[[ ${#services[@]} -gt 0 ]] || { echo 'empty Pact deployment plan' >&2; exit 1; }

# Pact CLI accepts repeated --pacticipant/--version pairs with --to-environment.
# Check the WHOLE replacement set, including mutually compatible co-deploys, before
# the first record write. Exit 0 alone is approval; unknown and failed both refuse.
"$cli" can-i-deploy "${selectors[@]}" --to-environment sandbox \
  --broker-base-url "$PACT_BROKER_URL" \
  --broker-username "$PACT_BROKER_USERNAME" \
  --broker-password "$PACT_BROKER_PASSWORD" || {
    echo 'exact Pact deployment set has no approved matrix verdict; recording nothing' >&2
    exit 1
  }

failed=0
for i in "${!services[@]}"; do
  ok=0
  for attempt in 1 2 3; do
    if "$cli" record-deployment --pacticipant "${services[$i]}" \
      --version "${versions[$i]}" --environment sandbox \
      --broker-base-url "$PACT_BROKER_URL" \
      --broker-username "$PACT_BROKER_USERNAME" \
      --broker-password "$PACT_BROKER_PASSWORD"; then
      ok=1
      break
    fi
    echo "record-deployment retry ${attempt}/3 for ${services[$i]}" >&2
    sleep 5
  done
  if [[ "$ok" -ne 1 ]]; then
    echo "record-deployment failed for ${services[$i]} after 3 attempts" >&2
    failed=1
  fi
done
exit "$failed"
