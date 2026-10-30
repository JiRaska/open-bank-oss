#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set +e
source "$RUNNER_TEMP/service-ci-provider-task-list.sh"
provider_task_proof="$RUNNER_TEMP/provider-pact-task-${SERVICE}-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}.proof"
rm -f "$provider_task_proof"
run_warmup() {
  timeout --kill-after=10s 120s ./gradlew --no-daemon --configure-on-demand \
    ":${SERVICE}:dependencies" ":${SERVICE}:tasks" --all --console=plain
}
# This advisory command is allowed to time out.  It must not leave a Gradle
# daemon writing test/Kover files while the mandatory build begins (#6614).
# `quarkusDependenciesBuild` writes dependent projects' build/libs JARs. A timeout
# can leave one truncated, which Gradle then treats as up-to-date in the mandatory
# build. `dependencies` resolves the same cache without producing project outputs.
# Keep the contract warm-up scoped to this module as in the build job.
# The mandatory Gradle task below still uses full configuration.
run_warmup > /tmp/gradle-warmup.log 2>&1
warmup_status=$?
service_ci_record_provider_task_proof "$warmup_status" /tmp/gradle-warmup.log "${SERVICE}" "$provider_task_proof"
if [ $warmup_status -eq 124 ]; then
  echo "::warning::dependency warm-up timed out after 120s; skipping advisory cache healing and continuing to mandatory step"
  cat /tmp/gradle-warmup.log
elif [ $warmup_status -ne 0 ]; then
  cat /tmp/gradle-warmup.log
  if bash "$SERVICE_CI_HELPERS/heal-gradle-verification-failure.sh" /tmp/gradle-warmup.log; then
    echo "retrying dependency warm-up after healing corrupted cache entries"
    run_warmup > /tmp/gradle-warmup.log 2>&1
    retry_status=$?
    service_ci_record_provider_task_proof "$retry_status" /tmp/gradle-warmup.log "${SERVICE}" "$provider_task_proof"
    cat /tmp/gradle-warmup.log
    if [ $retry_status -eq 124 ]; then
      echo "::warning::dependency warm-up retry timed out after 120s; continuing"
    fi
  else
    echo "::warning::dependency warm-up failed for a reason other than cache corruption — leaving it to the real step to report"
  fi
fi
