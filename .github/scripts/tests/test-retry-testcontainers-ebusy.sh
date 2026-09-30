#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
runner="${here}/retry-testcontainers-ebusy.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat >"$tmp/fake-build" <<'SH'
#!/usr/bin/env bash
count=0
[ ! -f "$COUNT_FILE" ] || count="$(cat "$COUNT_FILE")"
count=$((count + 1))
printf '%s\n' "$count" >"$COUNT_FILE"
if { [ "$MODE" = ebusy ] && [ "$count" -eq 1 ]; } || [ "$MODE" = persistent ]; then
  echo 'Container exited with code 126'
  echo 'Log output from the failed container:'
  echo '2026-09-30T17:57:56Z sh: /tmp/testcontainers_start.sh: Text file busy'
  exit 1
fi
if [ "$MODE" = normal ]; then
  echo 'AssertionError: expected 1, got 2'
  exit 1
fi
if [ "$MODE" = prose ]; then
  echo 'Test name mentions sh: /tmp/testcontainers_start.sh: Text file busy but this failed elsewhere'
  exit 1
fi
exit 0
SH
chmod +x "$tmp/fake-build"

export COUNT_FILE="$tmp/count"
export MODE=ebusy
bash "$runner" -- "$tmp/fake-build" >"$tmp/out"
[ "$(cat "$COUNT_FILE")" = 2 ] || { echo 'ETXTBSY was not retried exactly once' >&2; exit 1; }
grep -q 'retrying this service build once' "$tmp/out"

rm "$COUNT_FILE"
export MODE=normal
if bash "$runner" -- "$tmp/fake-build" >"$tmp/out"; then
  echo 'ordinary test failure passed' >&2; exit 1
fi
[ "$(cat "$COUNT_FILE")" = 1 ] || { echo 'ordinary test failure was retried' >&2; exit 1; }

rm "$COUNT_FILE"
export MODE=prose
if bash "$runner" -- "$tmp/fake-build" >"$tmp/out"; then
  echo 'prose-only failure passed' >&2; exit 1
fi
[ "$(cat "$COUNT_FILE")" = 1 ] || { echo 'prose-only failure was retried' >&2; exit 1; }

rm "$COUNT_FILE"
export MODE=persistent
if bash "$runner" -- "$tmp/fake-build" >"$tmp/out"; then
  echo 'persistent infrastructure failure passed' >&2; exit 1
fi
[ "$(cat "$COUNT_FILE")" = 2 ] || { echo 'persistent failure was retried more than once' >&2; exit 1; }

echo 'retry-testcontainers-ebusy: 4 controls passed'
