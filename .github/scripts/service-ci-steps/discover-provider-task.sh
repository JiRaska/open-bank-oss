#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
task_list="$(./gradlew ":${SERVICE}:tasks" --all --console=plain)"
provider_test="$(find "${SERVICE}/src/test" -type f -name '*ProviderVerificationTest.kt' -print -quit 2>/dev/null || true)"
has_task=false
if grep -Eq '^[[:space:]]*providerPactTest([[:space:]]|$)' <<< "$task_list"; then has_task=true; fi
# The TEST FILE decides, not the task. Since #4414 the openbank.quarkus-service convention
# plugin registers `providerPactTest` for EVERY service, including the ~40 that own no
# provider verification at all, so "the task exists" became true fleet-wide and the
# consumer-only branch below was unreachable. Every one of those services then ran the
# broker preflight, and a service the broker has never heard of got HTTP 404 and failed its
# contract job with a message blaming the broker (communication-service 7/7 on main,
# #4195). `*ProviderVerificationTest.kt` under src/test is exactly the include filter the
# convention's own source set uses, so it is the same predicate the task itself runs on.
if [ -n "$provider_test" ] && [ "$has_task" = "true" ]; then
  echo "available=true" >> "$GITHUB_OUTPUT"
  echo "${SERVICE} has ${provider_test} and exposes providerPactTest — provider verification will run."
elif [ -n "$provider_test" ]; then
  echo "::error::${SERVICE} has ${provider_test} but does not expose providerPactTest. Apply the service convention or register an equivalent provider task."
  exit 1
elif [ "${REQUIRE_PROVIDER_PACT_TEST}" = "true" ]; then
  echo "::error::${SERVICE} at ${PACT_PROVIDER_VERSION} has no providerPactTest. No Pact provider verification was published; do not treat this targeted run as deployment evidence."
  exit 1
else
  echo "available=false" >> "$GITHUB_OUTPUT"
  echo "${SERVICE} owns no provider Pact tests — consumer Pact publication is complete; provider verification is not applicable."
fi
