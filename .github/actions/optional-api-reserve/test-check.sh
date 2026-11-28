#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
check="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/gh" <<'GH'
#!/usr/bin/env bash
set -eu
[[ "${GH_TOKEN:-}" == fixture-token && "$#" == 4 && "$1 $2 $3" == 'api rate_limit --jq' ]]
[[ "$FIXTURE" != unavailable ]] || exit 1
jq -r "$4" <<< "$FIXTURE"
GH
chmod +x "$tmp/gh"
export PATH="$tmp:$PATH" GITHUB_OUTPUT="$tmp/output"

expect() {
  local expected=$1 fixture=$2 token=${3-fixture-token}
  : > "$GITHUB_OUTPUT"
  GH_TOKEN="$token" FIXTURE="$fixture" bash "$check" > "$tmp/log"
  [[ $(cat "$GITHUB_OUTPUT") == "proceed=$expected" ]]
  if [[ "$expected" == false ]]; then
    grep -Fq 'Optional GitHub work deferred' "$tmp/log"
  fi
}
expect true '{"resources":{"core":{"limit":5000,"remaining":1001},"graphql":{"limit":5000,"remaining":0}}}'
expect false '{"resources":{"core":{"limit":5000,"remaining":1000},"graphql":{"limit":5000,"remaining":5000}}}'
expect false '{"resources":{"core":{"limit":1000,"remaining":0}}}'
expect false '{"resources":{"core":{"limit":5000,"remaining":5001}}}'
expect false '{"resources":{"core":{"limit":5000,"remaining":2500.5}}}'
expect false '{"resources":{"core":{"limit":"5000","remaining":2500}}}'
expect false '{"resources":{"graphql":{"limit":5000,"remaining":4000}}}'
expect false unavailable
expect false '{}' ''
printf 'Optional API reserve: 9 cases passed\n'
