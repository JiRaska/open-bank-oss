#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
PROVIDER_VERSION="$(git rev-parse HEAD)"
if [ -n "$REQUESTED_PROVIDER_VERSION" ]; then
  PROOF_RUNNER="$RUNNER_TEMP/prove-pact-provider-version.sh"
  [ -x "$PROOF_RUNNER" ] || { echo "::error::trusted provider proof runner is absent"; exit 1; }
  PROVIDER_VERSION="$(bash "$PROOF_RUNNER" --resolve "$REQUESTED_PROVIDER_VERSION")"
  FIXTURE_REF="${REQUESTED_FIXTURE_REF:-$GITHUB_SHA}"
  # Fetch full main history once, then resolve F from objects already present. An
  # abbreviated SHA is not a valid fetch refspec on every Git server.
  git fetch --quiet origin main
  FIXTURE_VERSION="$(bash "$RUNNER_TEMP/resolve-historical-service-ref.sh" "$FIXTURE_REF")"
  # The script proves P/F/main ancestry and requires this checkout to be P. If P and F
  # are byte-identical in every production build input of SERVICE, it replaces
  # SERVICE/src/test (only) with F's; otherwise it refuses — except Ledger and Balance,
  # whose single approved provider fixture from F is kept as the fallback (#11597).
  bash "$PROOF_RUNNER" --prepare-overlay \
    "$SERVICE" "$PROVIDER_VERSION" "$FIXTURE_VERSION" origin/main
else
  # No override: retain the original one-SHA verification contract.
  PROVIDER_VERSION="$(git rev-parse HEAD)"
fi
echo "PACT_PROVIDER_VERSION=$PROVIDER_VERSION" >> "$GITHUB_ENV"
