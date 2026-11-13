#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Moved verbatim out of _service-ci.yml, which is expanded once per Services CI caller
# under GitHub's expanded-workflow size cap. pipefail keeps the inline-step semantics.
set -o pipefail
if python3 .github/scripts/check-test-intelligence-compatibility.py \
  "${SERVICE}/build/test-intelligence/run.json" \
  --component "${SERVICE}" \
  --commit "${GITHUB_SHA}" \
  --branch "${EXPECTED_BRANCH}" \
  --run-id "${GITHUB_RUN_ID}" \
  --attempt "${GITHUB_RUN_ATTEMPT}"; then
  echo "ready=true" >> "${GITHUB_OUTPUT}"
else
  echo "::warning::Skipping incomplete Test Intelligence compatibility artifact"
fi
