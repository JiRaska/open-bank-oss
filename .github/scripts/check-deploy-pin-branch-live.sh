#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Gate `deploy-pin-branch-contract` (ADR-0358): verify the tip of deploy/sandbox.
# The branch has no workflows of its own (a push there runs nothing), so the contract is
# checked twice: by publish-deploy-pin.py on the candidate before it is written, and here on
# every CI run against whatever the branch holds now, which is what catches a write that did
# not come through the publisher. Before the owner bootstraps the branch the corpus is
# UNRESOLVED, never a pass.
set -euo pipefail

branch="deploy/sandbox"
set +e
git ls-remote --exit-code --heads origin "refs/heads/${branch}" >/dev/null 2>&1
status=$?
set -e
if [ "$status" -eq 2 ]; then
  exec python3 .github/scripts/check-deploy-pin-branch.py check \
    --head "refs/remotes/origin/${branch}" --allow-absent
elif [ "$status" -ne 0 ]; then
  echo "::error::git ls-remote origin ${branch} exited ${status}; cannot verify the deploy branch"
  exit 1
fi

# Ancestry against main needs main's history; the gate shard checks out shallow. A blobless
# unshallow brings every commit and fetches only the blobs the pin's manifests need.
if [ "$(git rev-parse --is-shallow-repository)" = true ]; then
  git fetch --quiet --no-tags --filter=blob:none --unshallow origin \
    "+refs/heads/main:refs/remotes/origin/main"
else
  git fetch --quiet --no-tags origin "+refs/heads/main:refs/remotes/origin/main"
fi
git fetch --quiet --no-tags origin "+refs/heads/${branch}:refs/remotes/origin/${branch}"
exec python3 .github/scripts/check-deploy-pin-branch.py check --tip-only \
  --head "refs/remotes/origin/${branch}" --main refs/remotes/origin/main
