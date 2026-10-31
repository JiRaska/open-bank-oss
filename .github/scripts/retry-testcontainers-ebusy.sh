#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# Retry one service build only when Testcontainers could not execute its own
# start script (exit 126 / ETXTBSY). A normal test failure is never retried.
set -uo pipefail

if [ "${1:-}" = --self-test ]; then
  here="$(cd "$(dirname "$0")" && pwd)"
  bash "${here}/tests/test-retry-testcontainers-ebusy.sh"
  exit $?
fi
[ "${1:-}" = -- ] || { echo 'usage: retry-testcontainers-ebusy.sh -- command [args...]' >&2; exit 2; }
shift
[ "$#" -gt 0 ] || { echo 'missing command' >&2; exit 2; }

log="$(mktemp)" || exit 2
trap 'rm -f "$log"' EXIT
"$@" 2>&1 | tee "$log"
status=("${PIPESTATUS[@]}")
if [ "${status[1]}" -ne 0 ]; then
  echo '::error::Could not retain the build output; refusing an unverified retry.' >&2
  exit "${status[1]}"
fi
[ "${status[0]}" -ne 0 ] || exit 0

if ! grep -Fq 'Container exited with code 126' "$log" \
   || ! grep -Fq 'Log output from the failed container:' "$log" \
   || ! grep -Fxq 'sh: /tmp/testcontainers_start.sh: Text file busy' <(sed -E 's/^.*Z //' "$log"); then
  exit "${status[0]}"
fi

echo '::warning::Testcontainers start script hit ETXTBSY; retrying this service build once.'
sleep 3
"$@"
