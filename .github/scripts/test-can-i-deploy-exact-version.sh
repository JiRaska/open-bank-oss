#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
# Regression for #12228: exercise the real gate with a known Pact participant whose image
# SHA has no broker version. A failing exact query must not enter either ADR-0092 exception.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/curl" <<'STUB'
#!/usr/bin/env bash
url="${*: -1}"
case "$url" in
  */versions/*)
    if [ "${STUB_CLI_MODE:-}" = exact-success ]; then echo 200; else echo 404; fi ;;
  */pacticipants/*/latest-version/main) echo '{}' ;;
  */pacticipants/*) echo 200 ;;
  */environments) echo '{"_embedded":{"environments":[{"name":"sandbox"}]}}' ;;
  *) exit 1 ;;
esac
STUB
cat > "$tmp/pact-broker" <<'STUB'
#!/usr/bin/env bash
case "${STUB_CLI_MODE:-}" in
  exact-success) echo 'All required verification results are published and successful'; exit 0 ;;
  no-main) echo 'No version with tag main exists for openbank-demo-service' ;;
  no-counterpart)
    echo 'There is no verified pact between these versions'
    echo 'no version is currently recorded as deployed in sandbox' ;;
  *) exit 2 ;;
esac
exit 1
STUB
chmod +x "$tmp/curl" "$tmp/pact-broker"

for mode in no-main no-counterpart exact-success; do
  : > "$tmp/output"
  : > "$tmp/summary"
  ( cd "$root" && PATH="$tmp:$PATH" PACT_CLI_BIN="$tmp/pact-broker" \
      STUB_CLI_MODE="$mode" SERVICES='["openbank-demo-service"]' \
      EVENT_NAME=schedule GITHUB_EVENT_NAME=schedule \
      GITHUB_SHA=0e32873f8c52d37e1b6530e5bd5e94275e5cefae \
      GITHUB_OUTPUT="$tmp/output" GITHUB_STEP_SUMMARY="$tmp/summary" \
      PACT_BROKER_URL=http://stub PACT_BROKER_USERNAME=u PACT_BROKER_PASSWORD=p \
      PACT_WAIT_BUDGET_SECONDS=0 \
      bash .github/scripts/can-i-deploy-gate.sh > "$tmp/log" 2>&1 ) || true
  expected='deployable=[]'
  if [ "$mode" = exact-success ]; then
    expected='deployable=["openbank-demo-service"]'
  fi
  if ! grep -Fqx "$expected" "$tmp/output"; then
    echo "FAIL $mode: unexpected deployable set" >&2
    cat "$tmp/output" >&2
    tail -20 "$tmp/log" >&2
    exit 1
  fi
  if ! grep -q -- '--version 0e32873f8c52d37e1b6530e5bd5e94275e5cefae' "$tmp/log"; then
    echo "FAIL $mode: gate did not ask about the image SHA" >&2
    exit 1
  fi
  echo "ok $mode: exact version controls admission"
done
