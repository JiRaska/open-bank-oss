#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

# Measure the same token and API host used by the optional caller. /rate_limit
# does not spend primary REST quota. The snapshot is advisory across concurrent jobs.
proceed=false
reason="REST core quota unavailable"
if [[ -n ${GH_TOKEN:-} ]] && quota=$(gh api rate_limit --jq '
  .resources.core
  | select((.limit | type) == "number" and (.remaining | type) == "number")
  | select(.limit > 0 and .remaining >= 0 and .remaining <= .limit)
  | select(.limit == (.limit | floor) and .remaining == (.remaining | floor))
  | if .remaining * 5 > .limit then "sufficient" else "low" end
' 2>/dev/null); then
  case "$quota" in
    sufficient) proceed=true ;;
    low) reason="REST core reserve at or below 20%" ;;
  esac
fi

printf 'proceed=%s\n' "$proceed" >> "$GITHUB_OUTPUT"
if [[ "$proceed" == false ]]; then
  printf '::notice title=Optional GitHub work deferred::%s. A later trigger will reassess quota.\n' "$reason"
fi
