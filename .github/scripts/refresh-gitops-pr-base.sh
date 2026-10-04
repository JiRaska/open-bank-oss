#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Refresh the branch that create-pull-request uses as its base before rewriting a manifest.
set -euo pipefail

git fetch origin main
git reset --hard origin/main
# reset alone leaves Actions' detached HEAD (and the local main ref) at the old
# workflow commit. create-pull-request then replays all intervening main changes
# into the bot PR. Move the actual local base ref before editing any manifest.
git switch -C main origin/main
