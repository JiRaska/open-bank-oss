#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Prints exactly `true` when an Admin UI deploy source may enter the privileged build job.
# A source that is no longer the main tip is normally stale. The sole exception is when every
# newer commit changes only this workflow's own GitOps image pin. Keep this decision identical
# to deploy-window.py's deferred image freshness check.
set -euo pipefail

SOURCE_SHA="${1:?source SHA is required}"
MAIN_SHA="${2:?main SHA is required}"
EVENT_NAME="${3:-unknown}"
OPEN_DEPLOY_PR_EXISTS="${4:-false}"
DEPLOY_SUBJECT_PREFIX="chore(admin-ui): deploy "
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

reject() {
  echo "$1" >&2
  echo false
  exit 0
}

git cat-file -e "${SOURCE_SHA}^{commit}" 2>/dev/null \
  || reject "Skipping unknown deploy source ${SOURCE_SHA}."
git cat-file -e "${MAIN_SHA}^{commit}" 2>/dev/null \
  || reject "Skipping deploy because current main ${MAIN_SHA} is unavailable locally."

source_subject="$(git log -1 --format=%s "$SOURCE_SHA")"
if [[ "$source_subject" == "${DEPLOY_SUBJECT_PREFIX}"* ]]; then
  reject "Skipping self-generated GitOps commit; its image is already built, signed and attested."
fi

# Automatic sources for one SHA are serialized by the workflow concurrency group. Once the
# first admitted run opens its deterministic deploy branch, a later automatic source would only
# rebuild the same commit under a different immutable tag and invalidate exact-head review. A
# workflow_dispatch is an explicit operator refresh and intentionally remains an override.
if [ "$EVENT_NAME" != "workflow_dispatch" ] && [ "$OPEN_DEPLOY_PR_EXISTS" = "true" ]; then
  reject "Skipping duplicate automatic deploy for ${SOURCE_SHA}; its deploy branch already exists."
fi

if [ "$SOURCE_SHA" = "$MAIN_SHA" ]; then
  echo true
  exit 0
fi

python3 "$SCRIPT_DIR/deploy-window.py" admin-ui-source-current \
  --root . --source "$SOURCE_SHA" --main "$MAIN_SHA" \
  || reject "Skipping stale source ${SOURCE_SHA}; main changed beyond this workflow's own image pins."

echo "Allowing ${SOURCE_SHA}; main advanced only through self-generated Admin UI image bumps." >&2
echo true
