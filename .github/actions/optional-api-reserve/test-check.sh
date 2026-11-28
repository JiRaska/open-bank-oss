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

# Check the action boundary as well as check.sh: a misspelled env key would
# otherwise make the deployed workflow defer even when the quota is healthy.
python3 - <<'PY'
from pathlib import Path
import yaml

for filename, job, api_step in (
    ("deploy-code-lag-watch", "watch", "Name the blocker per lagging service"),
    ("merge-train-stall-watch", "watch", "Evaluate and keep the stall issue"),
    ("test-evidence-reconcile", "reconcile", "Resolve provable evidence per running image"),
):
    path = Path(".github/workflows") / (filename + ".yml")
    steps = yaml.safe_load(path.read_text())["jobs"][job]["steps"]
    reserve, = (step for step in steps if step.get("id") == "api-reserve")
    caller, = (step for step in steps if step.get("name") == api_step)
    assert reserve["uses"] == "./.github/actions/optional-api-reserve", filename
    assert reserve["env"]["GH_TOKEN"] == caller["env"]["GH_TOKEN"], filename
    assert steps.index(reserve) < steps.index(caller), filename
    assert caller["if"] == "steps.api-reserve.outputs.proceed == 'true'", filename
print("Optional API reserve: 3 workflow token bindings passed")
PY
