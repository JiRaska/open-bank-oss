#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
set -eu
umask 077

if [ "${MCP_HARNESS_APP_SIGN_IN:-}" != true ] || [ -z "${MCP_HARNESS_APP_COUNTER_FILE:-}" ]; then
  echo "scheduled journey requires app sign-in and its persistent counter file" >&2
  exit 2
fi
case "$MCP_HARNESS_APP_COUNTER_FILE" in
  /*) counter="$MCP_HARNESS_APP_COUNTER_FILE";;
  *) counter="$PWD/$MCP_HARNESS_APP_COUNTER_FILE";;
esac
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)

# Keep the lock through both assertions. Locking only the reservation would allow a later
# process to present its higher reserved counter before the current browser has authenticated.
exec flock --exclusive --nonblock --no-fork -- "$counter.lock" node "$script_dir/src/cli.ts" --journey "$@"
