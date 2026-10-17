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
  if [ "${2:-}" = "--resolve-reconcile-sources" ]; then
    echo "$*" >> "$SERVICE_CI_LOG"
    if [ -z "${GH_TOKEN:-}" ] || [ "${RESOLVER_MODE:-failure}" != "success" ] || \
       [ -z "${RESOLVER_SHA:-}" ]; then
      echo "::error::no successful historical Services CI source" >&2
      exit 1
    fi
    resolved='{}'
    for svc in $(jq -r '.[]' <<< "${3:?}"); do
      exact="$(EVENT_NAME=push PACT_WAIT_PER_SERVICE_SECONDS=0 \
        bash .github/scripts/probe-pact-version.sh "$svc" "$RESOLVER_SHA")"
      if [ "$exact" != "yes" ]; then
        echo "::error::resolver fixture has no exact Pact version" >&2
        exit 1
      fi
      resolved="$(jq -cn --argjson old "$resolved" --arg svc "$svc" \
        --arg sha "$RESOLVER_SHA" \
        '$old + {($svc): {sha: $sha, run_id: "fixture-run", attempt: 2}}')"
    done
    printf '%s\n' "$resolved"
    exit 0
  fi
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
      case "${BROKER_MODE:-known}" in
        push-fails|verified) echo 200 ;;
        source-ready)
          if [[ "$url" == */versions/${RESOLVER_SHA:-unset} ]]; then echo 200; else echo 404; fi
          ;;
        *) echo 404 ;;
      esac
      ;;
    */pacticipants/*)
      case "${BROKER_MODE:-known}" in
        absent) echo 404 ;;
        verified) echo 200 ;;
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
    */latest-version/main) echo "{\"number\":\"${LATEST_MAIN_SHA:-${OLDER_SHA:-}}\"}" ;;
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
if ! grep -q 'no verified historical Services CI source' "$tmp/log"; then
  echo "FAIL: missing historical source was not identified in the gate output" >&2
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

# A missing Pacticipant has no durable source proof. A new Pact can be published after
# this gate and before the separate GitOps job mutates image pins, so reconcile must stop
# at the first 404. Exact Services CI cannot stand in for a Pact verdict.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
: > "$SERVICE_CI_LOG"
export BROKER_MODE=absent PACT_CLI="$tmp/bin/pact-broker" PACT_CLI_LOG="$tmp/cli-log"
: > "$PACT_CLI_LOG"
unset GH_TOKEN
if bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/absent-log" 2>&1; then
  echo "FAIL: reconcile accepted an absent Pacticipant without a durable Pact source" >&2
  cat "$tmp/absent-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\[\]$' "$GITHUB_OUTPUT" || \
   ! grep -q 'Broker has no Pacticipant and no verified Pact source' "$tmp/absent-log" || \
   [ -s "$SERVICE_CI_LOG" ] || [ -s "$PACT_CLI_LOG" ] || \
   grep -q 'can-i-deploy (' "$tmp/absent-log"; then
  echo "FAIL: absent Pacticipant did not fail before downstream CI or Pact verdict" >&2
  cat "$GITHUB_OUTPUT" "$SERVICE_CI_LOG" "$PACT_CLI_LOG" "$tmp/absent-log" >&2
  exit 1
fi
echo "PASS: absent Pacticipant fails reconcile before CI, Pact verdict, or GitOps output"

# Even if a Pacticipant appears immediately after the first 404, the gate has already
# emitted an empty deployment set. It never carries the earlier absence to GitOps.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
rm -f "$tmp/participant-probes"
export BROKER_MODE=appears BROKER_STATE_FILE="$tmp/participant-probes" INPUT_CODEPLOY=false
if bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/late-registration-log" 2>&1; then
  echo "FAIL: late Pacticipant registration allowed a source-less reconcile" >&2
  cat "$tmp/late-registration-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\[\]$' "$GITHUB_OUTPUT" || \
   [ "$(cat "$BROKER_STATE_FILE")" != "1" ] || \
   grep -q 'can-i-deploy (' "$tmp/late-registration-log"; then
  echo "FAIL: absent-source refusal did not stop at the first Broker observation" >&2
  cat "$GITHUB_OUTPUT" "$BROKER_STATE_FILE" "$tmp/late-registration-log" >&2
  exit 1
fi
echo "PASS: late Pacticipant registration cannot make an initially absent source deployable"

# The co-deploy path runs the same source preflight and must fail before asking the
# combined matrix or producing deployable services.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
rm -f "$BROKER_STATE_FILE"
export EVENT_NAME=workflow_dispatch GITHUB_EVENT_NAME=workflow_dispatch INPUT_CODEPLOY=true
if bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/absent-codeploy-log" 2>&1; then
  echo "FAIL: co-deploy accepted an absent Pacticipant without source evidence" >&2
  cat "$tmp/absent-codeploy-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\[\]$' "$GITHUB_OUTPUT" || \
   grep -q 'can-i-deploy (' "$tmp/absent-codeploy-log"; then
  echo "FAIL: co-deploy did not fail closed before Pact matrix evaluation" >&2
  cat "$GITHUB_OUTPUT" "$tmp/absent-codeploy-log" >&2
  exit 1
fi
echo "PASS: co-deploy rejects absent Pacticipant before building its Pact matrix"

# Positive integration path for #13070: the Broker's latest main-tagged source has changed
# service inputs and cannot describe this image, while an earlier successful Services CI
# SHA still has identical build inputs. Reconcile must discover that exact historical source,
# verify its exact Pact version, and pass that SHA (never latest/main) to can-i-deploy.
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
  printf 'changed source\n' > openbank-demo-service/src/main/Example.kt
  git add openbank-demo-service/src/main/Example.kt
  git commit -qm 'later source build without matching reconcile image'
  LATEST_MAIN_SHA="$(git rev-parse HEAD)"
  printf 'class Example\n' > openbank-demo-service/src/main/Example.kt
  git add openbank-demo-service/src/main/Example.kt
  git commit -qm 'source reverted to earlier successful build'
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
  # The positive scenario is isolated in this subshell; restore the push scenario below.
  # shellcheck disable=SC2030
  export EVENT_NAME=schedule GITHUB_EVENT_NAME=schedule
  # shellcheck disable=SC2030
  export GH_TOKEN=fixture-token BROKER_MODE=source-ready LATEST_MAIN_SHA
  export SERVICE_CI_MODE=success SERVICE_CI_EXPECTED_SOURCE="$OLDER_SHA"
  export RESOLVER_MODE=success RESOLVER_SHA="$OLDER_SHA"
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
     ! grep -F -- "--resolve-reconcile-sources [\"openbank-demo-service\"] $CURRENT_SHA" "$SERVICE_CI_LOG" >/dev/null || \
     ! grep -q '^deployable=\["openbank-demo-service"\]$' "$GITHUB_OUTPUT"; then
    echo "FAIL: positive gate did not carry the exact equivalent Pact/CI SHA through" >&2
    cat "$GITHUB_OUTPUT" "$PACT_CLI_LOG" "$SERVICE_CI_LOG" "$tmp/positive-log" >&2
    exit 1
  fi
)
echo "PASS: mid-history source recovery selects exact successful CI/Pact SHA over a mismatched latest-main version"

# The push lane shares the block-classification/reporting path. Exercise it on Bash 3.2 too:
# the JSON-backed class/reason maps must preserve the REGRESSION label without associative arrays.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
# shellcheck disable=SC2031
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

# A no-contract service remains deployable on ordinary pushes. The reconcile-only
# fail-closed rule above must not turn this documented first-deploy path into a deadlock.
: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
: > "$PACT_CLI_LOG"
export RECONCILE_SOURCE_REQUIRED=false EVENT_NAME=push GITHUB_EVENT_NAME=push BROKER_MODE=absent
if ! bash "$root/.github/scripts/can-i-deploy-gate.sh" >"$tmp/push-absent-log" 2>&1; then
  echo "FAIL: ordinary push blocked a service with no Pacticipant" >&2
  cat "$tmp/push-absent-log" >&2
  exit 1
fi
if ! grep -q '^deployable=\["openbank-demo-service"\]$' "$GITHUB_OUTPUT" || \
   ! grep -q 'has no contracts in the broker .* deployable' "$tmp/push-absent-log" || \
   grep -q 'can-i-deploy openbank-demo-service' "$PACT_CLI_LOG"; then
  echo "FAIL: ordinary push changed the established no-contract behavior" >&2
  cat "$GITHUB_OUTPUT" "$PACT_CLI_LOG" "$tmp/push-absent-log" >&2
  exit 1
fi
echo "PASS: ordinary push keeps the documented no-contract deployment behavior"
