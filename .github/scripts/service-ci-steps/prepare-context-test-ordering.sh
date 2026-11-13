#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Moved verbatim out of _service-ci.yml, which is expanded once per Services CI caller
# under GitHub's expanded-workflow size cap. pipefail keeps the inline-step semantics.
set -o pipefail
python3 .github/scripts/prepare-test-priority.py \
  --repo "$GITHUB_REPOSITORY" --component openbank-context-service \
  --out "$RUNNER_TEMP/context-test-priority.json"
echo "OPENBANK_TEST_PRIORITY_FILE=$RUNNER_TEMP/context-test-priority.json" >> "$GITHUB_ENV"
