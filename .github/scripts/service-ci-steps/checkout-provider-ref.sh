#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
# Publishing a verification says "this provider version satisfies the consumer contract",
# and can-i-deploy then lets a deploy through on it.  Resolve the requested commit from
# the full main history, then reject anything that is not an on-main ancestor before it
# replaces the trusted triggering checkout.
git fetch --quiet origin main
RESOLVED_REF="$(bash "$RUNNER_TEMP/resolve-historical-service-ref.sh" "$REF")"
if ! git merge-base --is-ancestor "$RESOLVED_REF" origin/main; then
  echo "::error::$REF is not an ancestor of origin/main. A verification may only be" \
       "published for a commit that is on main (#3306)."
  exit 1
fi
git checkout --detach --quiet "$RESOLVED_REF"
echo "$RESOLVED_REF is on main — publishing the verification against it."
