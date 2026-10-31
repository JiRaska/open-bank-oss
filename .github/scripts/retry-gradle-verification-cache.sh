#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# Retry a build only when dependency verification failed before Gradle ran a task.
# This preserves the single test execution and never retries an ordinary test failure.
set -uo pipefail

[ "${1:-}" = -- ] || { echo 'usage: retry-gradle-verification-cache.sh -- command [args...]' >&2; exit 2; }
shift
[ "$#" -gt 0 ] || { echo 'missing command' >&2; exit 2; }

log="$(mktemp)" || exit 2
trap 'rm -f "$log"' EXIT
"$@" 2>&1 | tee "$log"
status=("${PIPESTATUS[@]}")
if [ "${status[1]}" -ne 0 ]; then
  echo '::error::Could not retain build output; refusing an unverified retry.' >&2
  exit "${status[1]}"
fi
[ "${status[0]}" -ne 0 ] || exit 0

# A failure after any task might have written build outputs or run tests. Do not
# replay those side effects, even if the log also contains a verification error.
if grep -Eq '^> Task([[:space:]]|$)' "$log"; then
  exit "${status[0]}"
fi

if ! .github/scripts/heal-gradle-verification-failure.sh "$log"; then
  exit "${status[0]}"
fi

echo '::warning::Dependency verification failed before task execution; retrying build once after healing the named cache entry.'
"$@"
