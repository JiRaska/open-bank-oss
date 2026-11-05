#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Moved verbatim out of _service-ci.yml, which is expanded once per Services CI caller
# under GitHub's expanded-workflow size cap. pipefail keeps the inline-step semantics.
set -o pipefail
python3 .github/scripts/service-build-artifact.py pack \
  --service openbank-context-service --sha "$GITHUB_SHA" \
  --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" \
  --out "$RUNNER_TEMP/deploy-build"
