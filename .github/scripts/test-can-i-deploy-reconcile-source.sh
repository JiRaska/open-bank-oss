#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# A reconcile source miss must stop before Pact CLI setup/can-i-deploy. This fixture drives the
# real gate with a stubbed Broker that knows the pacticipant but has no exact version and no
# usable main-tagged source. No network, CLI, GitHub API, or GitOps mutation is possible here.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
real_python="$(command -v python3)"
export REAL_PYTHON="$real_python"
cat > "$tmp/bin/python3" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" = ".github/scripts/verify-successful-service-ci.py" ]; then
  echo "$*" >> "$SERVICE_CI_LOG"
  if [ -z "${GH_TOKEN:-}" ]; then
    echo "::error::GitHub token is unavailable" >&2
    exit 1
  fi
  if [ "${SERVICE_CI_MODE:-success}" != "success" ]; then
    echo "::error::exact-main Services CI evidence is unavailable" >&2
    exit 1
  fi
  if [ -n "${SERVICE_CI_EXPECTED_SOURCE:-}" ] && [ "${3:-}" != "$SERVICE_CI_EXPECTED_SOURCE" ]; then
    echo "::error::Services CI was not checked for the expected source SHA" >&2
    exit 1
  fi
  echo "verified exact-main service build and contract"
  exit 0
fi
exec "$REAL_PYTHON" "$@"
STUB
chmod +x "$tmp/bin/python3"
cat > "$tmp/bin/curl" <<'STUB'
#!/usr/bin/env bash
url="${@: -1}"
is_status=false
for arg in "$@"; do [ "$arg" = "-w" ] && is_status=true; done
if [ "$is_status" = true ]; then
  case "$url" in
    */versions/*)
      if [ "${BROKER_MODE:-known}" = "push-fails" ]; then echo 200; else echo 404; fi
      ;;
    */pacticipants/openbank-demo-service)
      case "${BROKER_MODE:-known}" in
        absent) echo 404 ;;
        appears)
          calls=0
          [ ! -f "$BROKER_STATE_FILE" ] || calls="$(cat "$BROKER_STATE_FILE")"
          calls=$((calls + 1)); echo "$calls" > "$BROKER_STATE_FILE"
          if [ "$calls" -eq 1 ]; then echo 404; else echo 200; fi
          ;;
        *) echo 200 ;;
      esac
      ;;
    *) echo 000 ;;
  esac
else
  case "$url" in
    */environments) echo '{"_embedded":{"environments":[{"name":"sandbox"}]}}' ;;
    */latest-version/main) echo "{\"number\":\"${OLDER_SHA:-}\"}" ;;
    *) echo '{}' ;;
  esac
fi
STUB
chmod +x "$tmp/bin/curl"
cat > "$tmp/bin/pact-broker" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$PACT_CLI_LOG"
if [ "$1" = "can-i-deploy" ]; then
  echo "pact verification failed"
  [ "${PACT_CLI_MODE:-failure}" = "success" ] && exit 0
  exit 1
fi
exit 0
STUB
chmod +x "$tmp/bin/pact-broker"

sha="0123456789abcdef0123456789abcdef01234567"
export PATH="$tmp/bin:$PATH"
export RECONCILE_SOURCE_REQUIRED=true
export EVENT_NAME=schedule GITHUB_EVENT_NAME=schedule
export GITHUB_SHA="$sha" GITHUB_REPOSITORY=JiRaska/open-bank-oss
export SERVICES='["openbank-demo-service"]'
export IMAGE_TAGS='{"openbank-demo-service":"sandbox-01234567"}'
export IMAGE_DIGESTS='{"openbank-demo-service":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
export BUILD_IMAGE_TAG=sandbox-01234567
export GITHUB_REF=refs/heads/main
export INPUT_CODEPLOY=false INPUT_CODEPLOY_PACT_VERSIONS='{}'
export PACT_BROKER_URL=http://broker.invalid PACT_BROKER_USERNAME=fixture PACT_BROKER_PASSWORD=fixture
export GITHUB_OUTPUT="$tmp/output" GITHUB_STEP_SUMMARY="$tmp/summary"
export SERVICE_CI_LOG="$tmp/service-ci-log" SERVICE_CI_MODE=success

if bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/log" 2>&1; then
  echo "FAIL: source-less reconcile gate unexpectedly succeeded" >&2
  cat "$tmp/log" >&2
  exit 1
fi
if ! grep -q '^gate_ran=true$' "$GITHUB_OUTPUT" || ! grep -q '^deployable=\[\]$' "$GITHUB_OUTPUT"; then
  echo "FAIL: reconcile source refusal did not emit the fail-closed outputs" >&2
  cat "$GITHUB_OUTPUT" >&2
  exit 1
fi
if ! grep -q 'no exact or tree-equivalent Pact source (no)' "$tmp/log"; then
  echo "FAIL: missing source was not identified in the gate output" >&2
  cat "$tmp/log" >&2
  exit 1
fi
if grep -q 'can-i-deploy (' "$tmp/log"; then
  echo "FAIL: Pact can-i-deploy was called without source evidence" >&2
  cat "$tmp/log" >&2
  exit 1
fi
echo "PASS: missing reconcile source emits deployable=[] and exits before Pact can-i-deploy"

# Reconcile is intentionally main-only. Even if an off-main dispatch has a valid-looking
# tag/digest map, it must not request Pact or emit deployable services.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
export GITHUB_REF=refs/heads/codex/feature
if bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/off-main-log" 2>&1; then
  echo "FAIL: off-main reconcile was accepted" >&2
  exit 1
fi
if ! grep -q 'only for a workflow run on refs/heads/main' "$tmp/off-main-log" || \
   ! grep -q '^deployable=\[\]$' "$GITHUB_OUTPUT" || \
   grep -q 'can-i-deploy (' "$tmp/off-main-log"; then
  echo "FAIL: off-main reconcile was not refused before a Pact verdict" >&2
  cat "$GITHUB_OUTPUT" "$tmp/off-main-log" >&2
  exit 1
fi
export GITHUB_REF=refs/heads/main
echo "PASS: off-main reconcile fails closed before Pact can-i-deploy"

# Duplicate JSON keys would otherwise be silently collapsed by jq while validating coverage.
# The strict image-source verifier must reject that proof before it probes Pact or can deploy.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
export IMAGE_TAGS='{"openbank-demo-service":"sandbox-01234567","openbank-demo-service":"sandbox-01234567"}'
if bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/bad-image-map-log" 2>&1; then
  echo "FAIL: duplicate build-push image-map keys were accepted" >&2
  cat "$tmp/bad-image-map-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\[\]$' "$GITHUB_OUTPUT" || \
   ! grep -q 'duplicate JSON object key' "$tmp/bad-image-map-log" || \
   grep -q 'can-i-deploy (' "$tmp/bad-image-map-log"; then
  echo "FAIL: malformed image-source proof did not fail closed before can-i-deploy" >&2
  cat "$GITHUB_OUTPUT" "$tmp/bad-image-map-log" >&2
  exit 1
fi
echo "PASS: duplicate image-map keys fail closed before Pact can-i-deploy"
export IMAGE_TAGS='{"openbank-demo-service":"sandbox-01234567"}'

# A 404 for both the exact version and participant means there is no Pact graph, but it does not
# waive exact-main build/contract CI provenance. The gate must require that proof for the SHA of
# the successful build-push output, then preserve no-contract behavior only while the second
# participant probe still returns 404. Exercise missing and failing CI before the passing case.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
export BROKER_MODE=absent PACT_CLI="$tmp/bin/pact-broker" PACT_CLI_LOG="$tmp/cli-log"
unset GH_TOKEN
if bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/absent-no-token-log" 2>&1; then
  echo "FAIL: absent Pacticipant bypassed missing Services CI token" >&2
  cat "$tmp/absent-no-token-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\[\]$' "$GITHUB_OUTPUT" || \
   ! grep -q 'verify-successful-service-ci.py openbank-demo-service' "$SERVICE_CI_LOG" || \
   grep -q 'can-i-deploy (' "$tmp/absent-no-token-log"; then
  echo "FAIL: missing Services CI token did not fail closed for absent Pacticipant" >&2
  cat "$GITHUB_OUTPUT" "$SERVICE_CI_LOG" "$tmp/absent-no-token-log" >&2
  exit 1
fi
echo "PASS: absent Pacticipant requires Services CI provenance and fails closed without token"

: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
: > "$SERVICE_CI_LOG"
export GH_TOKEN=fixture-token SERVICE_CI_MODE=failure
if bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/absent-failed-ci-log" 2>&1; then
  echo "FAIL: absent Pacticipant bypassed failed Services CI evidence" >&2
  cat "$tmp/absent-failed-ci-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\[\]$' "$GITHUB_OUTPUT" || \
   ! grep -q 'verify-successful-service-ci.py openbank-demo-service' "$SERVICE_CI_LOG" || \
   grep -q 'can-i-deploy (' "$tmp/absent-failed-ci-log"; then
  echo "FAIL: failed Services CI evidence did not fail closed for absent Pacticipant" >&2
  cat "$GITHUB_OUTPUT" "$SERVICE_CI_LOG" "$tmp/absent-failed-ci-log" >&2
  exit 1
fi
echo "PASS: absent Pacticipant fails closed when exact-main Services CI evidence fails"

: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
: > "$SERVICE_CI_LOG"
export SERVICE_CI_MODE=success
if ! bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/absent-log" 2>&1; then
  echo "FAIL: a build-proven service with no Pacticipant was permanently blocked" >&2
  cat "$tmp/absent-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\["openbank-demo-service"\]$' "$GITHUB_OUTPUT"; then
  echo "FAIL: absent Pacticipant was not explicitly treated as deployable" >&2
  cat "$GITHUB_OUTPUT" >&2
  cat "$tmp/absent-log" >&2
  exit 1
fi
if grep -q 'can-i-deploy' "$PACT_CLI_LOG" 2>/dev/null; then
  echo "FAIL: absent Pacticipant incorrectly invoked Pact can-i-deploy" >&2
  cat "$PACT_CLI_LOG" >&2
  exit 1
fi
if ! grep -q 'verify-successful-service-ci.py openbank-demo-service' "$SERVICE_CI_LOG"; then
  echo "FAIL: absent Pacticipant skipped exact-main Services CI provenance" >&2
  cat "$SERVICE_CI_LOG" "$tmp/absent-log" >&2
  exit 1
fi
if ! grep -q 'has no Pacticipant; using only this run' "$tmp/absent-log"; then
  echo "FAIL: no-contract exception was not identified in the gate output" >&2
  cat "$tmp/absent-log" >&2
  exit 1
fi
echo "PASS: absent Pacticipant deploys only after exact-main Services CI proof, without a Pact verdict"

# The co-deploy path uses a different selector loop. Its explicit absent rule must keep the
# no-contract service out of the matrix, while still allowing the exact build-proven image.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
export EVENT_NAME=workflow_dispatch GITHUB_EVENT_NAME=workflow_dispatch INPUT_CODEPLOY=true
if ! bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/absent-codeploy-log" 2>&1; then
  echo "FAIL: co-deploy permanently blocked a build-proven service with no Pacticipant" >&2
  cat "$tmp/absent-codeploy-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\["openbank-demo-service"\]$' "$GITHUB_OUTPUT" || \
   grep -q 'can-i-deploy (' "$PACT_CLI_LOG" 2>/dev/null; then
  echo "FAIL: co-deploy did not keep the absent service deployable outside the Pact matrix" >&2
  cat "$GITHUB_OUTPUT" "$tmp/absent-codeploy-log" >&2
  exit 1
fi
echo "PASS: co-deploy excludes a confirmed absent Pacticipant and deploys its exact built image"

# If the participant appears (or the Broker becomes inconclusive) after the absent preflight,
# the whole batch must be emptied before GitOps can consume it.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
export BROKER_MODE=appears BROKER_STATE_FILE="$tmp/participant-probes" INPUT_CODEPLOY=false
if bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/appeared-log" 2>&1; then
  echo "FAIL: participant appearing after preflight did not fail closed" >&2
  cat "$tmp/appeared-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\[\]$' "$GITHUB_OUTPUT" || \
   ! grep -q 'absence changed or became inconclusive after preflight' "$tmp/appeared-log"; then
  echo "FAIL: changed participant state did not empty the deployment batch" >&2
  cat "$GITHUB_OUTPUT" "$tmp/appeared-log" >&2
  exit 1
fi
echo "PASS: participant appearing after preflight fails closed with deployable=[]"

# The co-deploy path must apply the same second 404 check before building its exact-version matrix.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
rm -f "$BROKER_STATE_FILE"
export EVENT_NAME=workflow_dispatch GITHUB_EVENT_NAME=workflow_dispatch INPUT_CODEPLOY=true
if bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/appeared-codeploy-log" 2>&1; then
  echo "FAIL: co-deploy accepted a participant that appeared after preflight" >&2
  cat "$tmp/appeared-codeploy-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\[\]$' "$GITHUB_OUTPUT" || \
   ! grep -q 'no-contract state changed or became inconclusive before co-deploy' "$tmp/appeared-codeploy-log"; then
  echo "FAIL: changed co-deploy participant state did not empty the batch" >&2
  cat "$GITHUB_OUTPUT" "$tmp/appeared-codeploy-log" >&2
  exit 1
fi
echo "PASS: co-deploy fails closed if an absent Pacticipant appears after preflight"

# Positive integration path: a registered Pacticipant has no version for the current
# workflow SHA, but does have an older main-tagged version. The real Git tree comparator
# proves that only irrelevant docs changed; exact-main Services CI is checked for that
# older SHA, and the real selector must pass that exact version to the Pact CLI.
positive="$tmp/positive-repo"
mkdir -p "$positive/.github/scripts" "$positive/.github/workflows" \
  "$positive/openbank-demo-service/src/main"
for script in can-i-deploy-gate.sh probe-pact-version.sh pact-version-tree-equivalent.sh \
  resolve-can-i-deploy-selector.sh classify-can-i-deploy-block.sh verify-reconcile-image-map.py; do
  cp "$root/.github/scripts/$script" "$positive/.github/scripts/$script"
done
printf 'env:\n  ANALYTICS_SINK_TYPE: clickhouse\n  AGENT_SVID_NONCE_STORE: redis\n' \
  > "$positive/.github/workflows/auto-deploy.yml"
printf 'x\n' > "$positive/.github/workflows/Dockerfile.deploy"
printf 'plugins {}\n' > "$positive/openbank-demo-service/build.gradle.kts"
printf 'class Example\n' > "$positive/openbank-demo-service/src/main/Example.kt"
(
  cd "$positive"
  # run-gates.py exports a private checkout index to each gate. This throwaway repo must use
  # its own index so an earlier fixture cannot leave blobs from another object database here.
  unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_COMMON_DIR
  git init -q
  git config user.email fixture@example.test
  git config user.name fixture
  git config commit.gpgsign false
  git add .github openbank-demo-service
  git commit -qm 'base build with published pact'
  OLDER_SHA="$(git rev-parse HEAD)"
  mkdir -p docs
  printf 'operational note\n' > docs/note.md
  git add docs/note.md
  git commit -qm 'unrelated documentation change'
  CURRENT_SHA="$(git rev-parse HEAD)"

  : > "$GITHUB_OUTPUT"
  : > "$GITHUB_STEP_SUMMARY"
  : > "$PACT_CLI_LOG"
  : > "$SERVICE_CI_LOG"
  export OLDER_SHA CURRENT_SHA
  export GITHUB_SHA="$CURRENT_SHA" GITHUB_REF=refs/heads/main
  export EVENT_NAME=schedule GITHUB_EVENT_NAME=schedule
  export BROKER_MODE=known SERVICE_CI_MODE=success SERVICE_CI_EXPECTED_SOURCE="$OLDER_SHA"
  export PACT_CLI_MODE=success
  export IMAGE_TAGS="{\"openbank-demo-service\":\"sandbox-${CURRENT_SHA:0:8}\"}"
  export IMAGE_DIGESTS='{"openbank-demo-service":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
  export BUILD_IMAGE_TAG="sandbox-${CURRENT_SHA:0:8}"
  if ! bash .github/scripts/can-i-deploy-gate.sh >"$tmp/positive-log" 2>&1; then
    echo "FAIL: equivalent older-Pact source did not complete the positive gate" >&2
    cat "$tmp/positive-log" "$PACT_CLI_LOG" "$SERVICE_CI_LOG" >&2
    exit 1
  fi
  if ! grep -F -- "--pacticipant openbank-demo-service --version $OLDER_SHA" "$PACT_CLI_LOG" >/dev/null || \
     ! grep -F "verify-successful-service-ci.py openbank-demo-service $OLDER_SHA" "$SERVICE_CI_LOG" >/dev/null || \
     ! grep -q '^deployable=\["openbank-demo-service"\]$' "$GITHUB_OUTPUT"; then
    echo "FAIL: positive gate did not carry the exact equivalent Pact/CI SHA through" >&2
    cat "$GITHUB_OUTPUT" "$PACT_CLI_LOG" "$SERVICE_CI_LOG" "$tmp/positive-log" >&2
    exit 1
  fi
)
echo "PASS: registered Pacticipant + equivalent older SHA + exact-main CI selects the exact Pact version"

# The push lane shares the block-classification/reporting path. Exercise it on Bash 3.2 too:
# the JSON-backed class/reason maps must preserve the REGRESSION label without associative arrays.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
export RECONCILE_SOURCE_REQUIRED=false EVENT_NAME=push GITHUB_EVENT_NAME=push INPUT_CODEPLOY=false BROKER_MODE=push-fails
if bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/push-log" 2>&1; then
  echo "FAIL: intentionally failed push-lane Pact verdict returned success" >&2
  cat "$tmp/push-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\[\]$' "$GITHUB_OUTPUT" || \
   ! grep -q 'openbank-demo-service.*REGRESSION' "$GITHUB_STEP_SUMMARY" || \
   ! grep -q 'pact FAILED verification' "$GITHUB_STEP_SUMMARY"; then
  echo "FAIL: push-lane JSON block maps lost the failure class or reason" >&2
  cat "$GITHUB_OUTPUT" "$GITHUB_STEP_SUMMARY" "$tmp/push-log" >&2
  exit 1
fi
echo "PASS: push-lane JSON block maps retain REGRESSION class and reason under Bash 3.2"
