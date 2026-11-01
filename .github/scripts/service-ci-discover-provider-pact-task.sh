#!/usr/bin/env bash
set -euo pipefail

service="${1:?service required}"
provider_version="${2:?provider version required}"
require_provider_test="${3:?require_provider_pact_test required}"
proof="${RUNNER_TEMP:-/tmp}/provider-pact-task-${service}-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}.proof"
script_dir="$(cd "$(dirname "$0")" && pwd)"
# The helper is staged beside this trusted script before the checkout changes to P.
# shellcheck disable=SC1091
source "$script_dir/service-ci-provider-task-list.sh"

if service_ci_provider_task_reuse_is_proven "$proof" "$service"; then
  task_list='providerPactTest'
  echo 'Reusing providerPactTest confirmation from the successful dependency warm-up.'
else
  task_list="$(./gradlew ":${service}:tasks" --all --console=plain)"
fi

provider_test="$(find "${service}/src/test" -type f -name '*ProviderVerificationTest.kt' -print -quit 2>/dev/null || true)"
has_task=false
if grep -Eq '^[[:space:]]*providerPactTest([[:space:]]|$)' <<< "$task_list"; then has_task=true; fi
if [ -n "$provider_test" ] && [ "$has_task" = 'true' ]; then
  echo 'available=true' >> "$GITHUB_OUTPUT"
  echo "${service} has ${provider_test} and exposes providerPactTest — provider verification will run."
elif [ -n "$provider_test" ]; then
  echo "::error::${service} has ${provider_test} but does not expose providerPactTest. Apply the service convention or register an equivalent provider task."
  exit 1
elif [ "$require_provider_test" = 'true' ]; then
  echo "::error::${service} at ${provider_version} has no providerPactTest. No Pact provider verification was published; do not treat this targeted run as deployment evidence."
  exit 1
else
  echo 'available=false' >> "$GITHUB_OUTPUT"
  echo "${service} owns no provider Pact tests — consumer Pact publication is complete; provider verification is not applicable."
fi
