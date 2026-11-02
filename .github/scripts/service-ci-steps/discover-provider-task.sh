#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
"$RUNNER_TEMP/service-ci-discover-provider-pact-task.sh" \
  "$SERVICE" "$PACT_PROVIDER_VERSION" "$REQUIRE_PROVIDER_PACT_TEST"
