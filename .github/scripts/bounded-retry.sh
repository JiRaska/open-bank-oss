#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Run a setup command with a per-attempt deadline, retrying a bounded number of times.
#
#   bounded-retry.sh <seconds-per-attempt> <attempts> <command> [args...]
#
# For runner SETUP only (installing a browser, say), never for the check a job exists to
# perform: a retried check is a loosened check. Exhausting the attempts exits non-zero and
# names the step, so the job reports a failed setup instead of being cancelled by its own
# timeout-minutes, which reads as though somebody stopped the run.
set -uo pipefail

run() {
  local seconds="$1" attempts="$2" attempt rc
  shift 2
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    # -k: a command ignoring TERM (apt behind sudo) must not outlive its attempt.
    timeout -k 10 "$seconds" "$@"
    rc=$?
    [ "$rc" -eq 0 ] && return 0
    if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
      echo "bounded-retry: attempt $attempt/$attempts exceeded ${seconds}s: $*" >&2
    else
      echo "bounded-retry: attempt $attempt/$attempts exited $rc: $*" >&2
    fi
  done
  echo "bounded-retry: giving up after $attempts attempts: $*" >&2
  return 1
}

self_test() {
  local dir marker started elapsed
  dir="$(mktemp -d)"
  marker="$dir/attempted"

  run 5 2 true 2>/dev/null || { echo "FAIL: a passing command must pass"; return 1; }

  if run 5 2 false 2>/dev/null; then echo "FAIL: exhausted attempts must exit non-zero"; return 1; fi

  # shellcheck disable=SC2016  # expanded by the inner shell
  run 5 2 bash -c '[ -e "$1" ] || { touch "$1"; exit 1; }' _ "$marker" 2>/dev/null \
    || { echo "FAIL: a failure must be retried"; return 1; }

  started=$SECONDS
  if run 1 2 sleep 30 2>/dev/null; then echo "FAIL: a hung command must not pass"; return 1; fi
  elapsed=$((SECONDS - started))
  [ "$elapsed" -le 5 ] || { echo "FAIL: a hung command took ${elapsed}s, not bounded"; return 1; }

  rm -rf "$dir"
  echo "bounded-retry self-test passed"
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi
if [ "$#" -lt 3 ]; then
  echo "usage: bounded-retry.sh <seconds-per-attempt> <attempts> <command> [args...]" >&2
  exit 2
fi
run "$@"
