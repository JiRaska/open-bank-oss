#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Install openbank-admin-ui's node_modules for a gate that needs them — ONCE, under a lock.
#
# WHY THIS EXISTS
#   Two gates in the `lint` shard need modules from openbank-admin-ui: governance-manifest (yaml,
#   zod) and mermaid-parses (mermaid, jsdom). run-gates.py runs a shard's gates CONCURRENTLY, so
#   both called `npm ci` in the same directory at the same time and destroyed each other's tree:
#
#     npm error ENOTEMPTY: directory not empty, rmdir '.../node_modules/@apm-js-collab'
#     npm error ENOTDIR: not a directory, mkdir '.../node_modules/@csstools'
#
#   Both gates then reported UNFALSIFIED — neither self-test could run — which is the correct
#   verdict and the only reason this surfaced: a gate that cannot prove its own red is not green.
#   Note what the race did NOT do: fail on its own terms. Each npm error sat inside a gate's
#   dependency step, so without the self-test machinery it would have read as a flaky gate.
#
# WHY THE INSTALL IS FULL, NOT --omit=dev
#   jsdom is a devDependency and mermaid.parse() cannot run without a DOM. A dev-omitting install
#   satisfies governance-manifest but not mermaid-parses, so the two would keep reinstalling over
#   each other in turn — a slower version of the same bug. One superset install serves both.
#
# THE LOCK
#   mkdir is atomic on every filesystem this runs on and, unlike flock, exists on macOS too
#   (developers run run-gates.py locally). The waiter re-checks the modules after each sleep:
#   by then the holder has usually finished and there is nothing left to do.
#
# Usage:  source "$(dirname "$0")/ensure-admin-ui-deps.sh"; ensure_admin_ui_deps "<label>"

ensure_admin_ui_deps() {
  local label="${1:-deps}" script_dir repo_root ui_dir lock marker waited
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  repo_root="$(cd "$script_dir/../.." && pwd)"
  ui_dir="$repo_root/openbank-admin-ui"
  lock="$ui_dir/.node_modules.gate-lock"
  # A COMPLETION marker, not a resolve check. npm writes node_modules progressively, so a waiter
  # that probes with require.resolve can see a half-installed tree as ready and then fail on a
  # package npm has not unpacked yet — which is how the first version of this helper still let the
  # two gates trip over each other, just later and less obviously (the gate reported UNFALSIFIED,
  # not a clean failure). The marker is written only after `npm ci` returns 0.
  marker="$ui_dir/node_modules/.openbank-gate-deps-ok"

  if [ -f "$marker" ]; then return 0; fi

  waited=0
  # 900s: a cold `npm ci` here is ~50s, so this only trips when the holder died mid-install.
  while ! mkdir "$lock" 2>/dev/null; do
    if [ "$waited" -ge 900 ]; then
      echo "[$label] stale lock at $lock after ${waited}s — removing and retrying" >&2
      rm -rf "$lock"
      continue
    fi
    sleep 2
    waited=$((waited + 2))
    if [ -f "$marker" ]; then return 0; fi
  done
  trap 'rm -rf "$lock"' EXIT

  # Re-check under the lock: the holder we queued behind has usually just finished.
  if [ -f "$marker" ]; then
    rm -rf "$lock"
    trap - EXIT
    return 0
  fi

  echo "[$label] installing openbank-admin-ui deps (npm ci, full — jsdom is a devDependency)"
  # npm ci verifies the committed lockfile's integrity hashes; never a loose `npm install`.
  # A half-written tree left by a killed peer makes npm ci itself fail, so clear it first.
  rm -rf "$ui_dir/node_modules"
  ( cd "$ui_dir" && npm ci --ignore-scripts --no-audit --no-fund >/dev/null )
  touch "$marker"

  rm -rf "$lock"
  trap - EXIT
}
