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
  local label="${1:-deps}"
  local script_dir repo_root ui_dir lock waited
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  repo_root="$(cd "$script_dir/../.." && pwd)"
  ui_dir="$repo_root/openbank-admin-ui"
  lock="$ui_dir/.node_modules.gate-lock"

  # RESOLVABLE IS NOT IMPORTABLE, and the difference is what took the lint shard red on main
  # (2026-08-19). `require.resolve` finds mermaid's package entry and says nothing about whether
  # importing it works: mermaid.core.mjs imports `es-toolkit/compat`, and against a tree where that
  # dependency is missing its `exports` map the import dies with
  #   ERR_UNSUPPORTED_DIR_IMPORT: Directory import '.../es-toolkit/compat' is not supported
  # while every `require.resolve` above still succeeds. The gate then reported UNFALSIFIED — its
  # self-test could not run — which is the correct verdict and gave no clue that the cause was a
  # half-right node_modules rather than the checker.
  #
  # So the probe now asks the question the gates actually ask: can these modules be IMPORTED, from
  # the directory the gates import them from. A tree that fails this is reinstalled from the
  # lockfile below instead of being trusted because the paths exist.
  _deps_present() {
    ( cd "$ui_dir" && node --input-type=module -e "
        for (const m of ['yaml', 'zod', 'mermaid', 'jsdom']) { await import(m) }
      " ) >/dev/null 2>&1
  }

  # Printed on the failure path only, and printed rather than inferred: the last time this broke,
  # three plausible causes (node version, npm flags, a nested duplicate) were indistinguishable
  # from the log, and the next run should answer that instead of the next person guessing again.
  _deps_diagnose() {
    ( cd "$ui_dir" && node --input-type=module -e "
        import { createRequire } from 'node:module';
        const require = createRequire(process.cwd() + '/');
        const v = (m) => { try { return require(m + '/package.json').version } catch { return 'ABSENT' } };
        console.error('[deps] node=' + process.version + ' mermaid=' + v('mermaid') +
                      ' es-toolkit=' + v('es-toolkit') + ' jsdom=' + v('jsdom'));
        try { await import('mermaid') } catch (e) { console.error('[deps] mermaid import: ' + e.code + ' ' + e.message.split('\n')[0]) }
      " ) 2>&1 || true
  }

  if _deps_present; then return 0; fi

  waited=0
  # 600s: a cold `npm ci` here is ~50s, so this only trips when the holder died mid-install.
  while ! mkdir "$lock" 2>/dev/null; do
    if [ "$waited" -ge 600 ]; then
      echo "[$label] stale lock at $lock after ${waited}s — removing and retrying" >&2
      rm -rf "$lock"
      continue
    fi
    sleep 2
    waited=$((waited + 2))
    if _deps_present; then return 0; fi
  done
  trap 'rm -rf "$lock"' EXIT

  if _deps_present; then
    rm -rf "$lock"
    trap - EXIT
    return 0
  fi

  echo "[$label] openbank-admin-ui deps are not importable — reinstalling. Current state:"
  _deps_diagnose
  echo "[$label] installing openbank-admin-ui deps (npm ci, full — jsdom is a devDependency)"
  # npm ci verifies the committed lockfile's integrity hashes; never a loose `npm install`.
  # A half-written tree left by a killed peer makes npm ci itself fail, so clear it first.
  rm -rf "$ui_dir/node_modules"
  ( cd "$ui_dir" && npm ci --ignore-scripts --no-audit --no-fund >/dev/null )

  rm -rf "$lock"
  trap - EXIT

  # A reinstall that did not fix importability must say so HERE, next to the install that was
  # supposed to fix it — otherwise the failure surfaces two gates later as an unfalsified verdict
  # with no mention of dependencies at all, which is exactly how this cost an afternoon.
  if ! _deps_present; then
    echo "[$label] deps STILL not importable after a clean npm ci — this is not a stale tree:" >&2
    _deps_diagnose >&2
    return 1
  fi
}
