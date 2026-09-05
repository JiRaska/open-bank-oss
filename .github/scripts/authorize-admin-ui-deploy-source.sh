#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Prints exactly `true` when an Admin UI deploy source may enter the privileged build job.
# A source that is no longer the main tip is normally stale. The sole exception is when every
# newer commit is this workflow's own GitOps image bump: those commits change no build input, and
# rejecting the waiting source would leave its Admin UI changes permanently undeployed.
set -euo pipefail

SOURCE_SHA="${1:?source SHA is required}"
MAIN_SHA="${2:?main SHA is required}"
ADMIN_UI_MANIFEST="openbank-infra/gitops/components/admin-ui/admin-ui.yaml"
DEPLOY_SUBJECT_PREFIX="chore(admin-ui): deploy "

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

if [ "$SOURCE_SHA" = "$MAIN_SHA" ]; then
  echo true
  exit 0
fi

git merge-base --is-ancestor "$SOURCE_SHA" "$MAIN_SHA" 2>/dev/null \
  || reject "Skipping stale source ${SOURCE_SHA}; it is not an ancestor of main ${MAIN_SHA}."

while IFS= read -r commit_sha; do
  subject="$(git log -1 --format=%s "$commit_sha")"
  if [[ "$subject" != "${DEPLOY_SUBJECT_PREFIX}"* ]]; then
    reject "Skipping stale source ${SOURCE_SHA}; newer build-relevant main commit ${commit_sha} owns deployment."
  fi

  changed_paths="$(git diff-tree --first-parent --no-commit-id --name-only -r "$commit_sha")"
  if [ "$changed_paths" != "$ADMIN_UI_MANIFEST" ]; then
    reject "Skipping stale source ${SOURCE_SHA}; deploy-looking commit ${commit_sha} changed more than the Admin UI image pin."
  fi
done < <(git rev-list --reverse "${SOURCE_SHA}..${MAIN_SHA}")

echo "Allowing ${SOURCE_SHA}; main advanced only through self-generated Admin UI image bumps." >&2
echo true
