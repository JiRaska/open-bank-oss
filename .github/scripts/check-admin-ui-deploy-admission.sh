#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Re-evaluate source freshness and duplicate deploy PR state at a privileged boundary.
set -euo pipefail

SOURCE_SHA="${1:?source SHA is required}"
EVENT_NAME="${2:?event name is required}"
OUTPUT_FILE="${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

main_sha="$(git ls-remote origin refs/heads/main | awk '{print $1}')"
if [[ ! "$main_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "::error::could not resolve origin/main; refusing a privileged build from an unverified source"
  exit 1
fi

if ! git cat-file -e "${main_sha}^{commit}" 2>/dev/null; then
  # A source can wait in the serialized build queue while main advances. Fetch enough
  # ancestry to prove image-pin-only advances; a longer or incomplete window fails closed.
  git fetch --no-tags --depth=64 origin "$main_sha"
fi

deploy_branch="chore/admin-ui-deploy-${SOURCE_SHA}"
open_deploy_pr_exists=false
if git ls-remote --exit-code --heads origin "refs/heads/${deploy_branch}" >/dev/null 2>&1; then
  # A leftover branch alone does not block recovery. Only an open PR proves an automatic
  # deploy for this source is already in progress. API failures fail closed under set -e.
  open_deploy_pr_exists="$(gh pr list --repo "${GITHUB_REPOSITORY:?}" --state open \
    --head "$deploy_branch" --json number --jq 'length > 0')"
else
  ls_remote_status=$?
  if [[ "$ls_remote_status" -ne 2 ]]; then
    echo "::error::could not check for an existing deploy branch (git ls-remote exited ${ls_remote_status}); refusing admission"
    exit "$ls_remote_status"
  fi
fi

proceed="$(bash .github/scripts/authorize-admin-ui-deploy-source.sh \
  "$SOURCE_SHA" "$main_sha" "$EVENT_NAME" "$open_deploy_pr_exists")"
printf 'proceed=%s\n' "$proceed" >> "$OUTPUT_FILE"
printf 'Deploy source admission at boundary: %s\n' "$proceed"
