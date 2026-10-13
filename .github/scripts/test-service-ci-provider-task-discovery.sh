#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
service="openbank-ci-harness-$$"
checkout="$tmp/historical-provider-checkout"
staged="$tmp/staged-trusted-helpers"
proof="$staged/provider-pact-task-${service}-local-1.proof"
trap 'rm -rf "$tmp"; rm -f "$proof"' EXIT

mkdir -p "$checkout/$service/src/test/kotlin" "$staged"
if [ -e "$checkout/.github/scripts/service-ci-provider-task-list.sh" ] || [ -e "$checkout/.github/scripts/service-ci-discover-provider-pact-task.sh" ]; then
  echo 'provider task discovery self-test: historical checkout unexpectedly contains trusted helpers' >&2
  exit 1
fi
cp "$repo_root/.github/scripts/service-ci-provider-task-list.sh" "$staged/"
cp "$repo_root/.github/scripts/service-ci-discover-provider-pact-task.sh" "$staged/"
touch "$checkout/$service/src/test/kotlin/SampleProviderVerificationTest.kt"
cat > "$checkout/gradlew" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$HARNESS_CALLS"
case "${HARNESS_MODE:-task}" in
  timeout) exit 124 ;;
  failure) echo 'Gradle failed' >&2; exit 1 ;;
  absent) printf "Tasks runnable from project ':%s'\nhelp\nBUILD SUCCESSFUL in 1s\n" "$HARNESS_SERVICE" ;;
  task) printf "Tasks runnable from project ':%s'\nproviderPactTest\nBUILD SUCCESSFUL in 1s\n" "$HARNESS_SERVICE" ;;
esac
EOF
chmod +x "$checkout/gradlew"
cd "$checkout"
export GITHUB_OUTPUT="$tmp/output"
export HARNESS_CALLS="$tmp/calls"
export HARNESS_SERVICE="$service"
export RUNNER_TEMP="$staged"

# A successful warm-up proof avoids a second Gradle invocation.
printf '%s' "$service" > "$proof"
: > "$HARNESS_CALLS"
bash "$RUNNER_TEMP/service-ci-discover-provider-pact-task.sh" "$service" version false
[ "$(cat "$GITHUB_OUTPUT")" = 'available=true' ]
[ ! -s "$HARNESS_CALLS" ]
rm -f "$GITHUB_OUTPUT" "$proof"

# Any missing proof (including timeout, failure, incomplete output, or absent task)
# takes the full discovery path. Discovery never runs tests or provider verification.
for mode in timeout failure absent; do
  export HARNESS_MODE="$mode"
  : > "$HARNESS_CALLS"
  if bash "$RUNNER_TEMP/service-ci-discover-provider-pact-task.sh" "$service" version false >"$tmp/$mode.out" 2>&1; then
    echo "provider task discovery self-test: $mode fallback unexpectedly passed with no task" >&2
    exit 1
  fi
  [ "$(wc -l < "$HARNESS_CALLS" | tr -d ' ')" = 1 ]
  grep -q ":${service}:tasks" "$HARNESS_CALLS"
  if grep -Eq '(^|[[:space:]])(test|providerPactTest)([[:space:]]|$)' "$HARNESS_CALLS"; then
    echo 'provider task discovery self-test: discovery executed tests or provider verification' >&2
    exit 1
  fi
done

# An incomplete warm-up listing is rejected by the shared proof parser; with no proof,
# normal full discovery runs and can confirm the task independently.
export HARNESS_MODE=task
: > "$HARNESS_CALLS"
bash "$RUNNER_TEMP/service-ci-discover-provider-pact-task.sh" "$service" version false > "$tmp/incomplete-fallback.out"
[ "$(wc -l < "$HARNESS_CALLS" | tr -d ' ')" = 1 ]

# A provider test without the convention task remains a hard failure after full discovery.
export HARNESS_MODE=absent
: > "$HARNESS_CALLS"
if bash "$RUNNER_TEMP/service-ci-discover-provider-pact-task.sh" "$service" version false >"$tmp/missing-task.out" 2>&1; then
  echo 'provider task discovery self-test: provider test without task was accepted' >&2
  exit 1
fi
grep -q 'does not expose providerPactTest' "$tmp/missing-task.out"

# Consumer-only modules still skip when provider verification is not required.
rm -f "$service/src/test/kotlin/SampleProviderVerificationTest.kt"
export HARNESS_MODE=absent
printf 'available=false\n' > "$GITHUB_OUTPUT"
bash "$RUNNER_TEMP/service-ci-discover-provider-pact-task.sh" "$service" version false > "$tmp/consumer.out"
grep -q 'owns no provider Pact tests' "$tmp/consumer.out"
[ "$(cat "$GITHUB_OUTPUT")" = $'available=false\navailable=false' ]

# A targeted run without a provider task still fails closed.
if bash "$RUNNER_TEMP/service-ci-discover-provider-pact-task.sh" "$service" version true >"$tmp/required.out" 2>&1; then
  echo 'provider task discovery self-test: required consumer-only run was accepted' >&2
  exit 1
fi
grep -q 'has no providerPactTest' "$tmp/required.out"
echo 'service-ci provider task discovery self-test passed'
