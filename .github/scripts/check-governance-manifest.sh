#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# ADR-0071: the governance manifest is CODE-DERIVED from per-service governance.yaml.
# Regenerate it and fail on any module that is missing one, or whose declaration the tree
# contradicts. Phase 4 flipped this from advisory (::warning::) to enforce (exit 1) — the
# fleet completed the governance.yaml sweep in phases 1-3.
#
# WHAT IT CHECKS, IN TWO LAYERS (ADR-0196), NEITHER OF WHICH LIVES HERE
#   The generator (openbank-admin-ui/scripts/generate-governance.mjs) validates every
#   governance.yaml against the Zod schema in scripts/governance-schema.mjs
#   (openbank-libs/governance/governance.schema.json is DERIVED from it), so a shape
#   violation — wrong type, unknown key, malformed lineage node — is just another entry in
#   `gaps` and needs no separate rule here. It also cross-checks every declaration the tree
#   can settle: Flyway migrations, datasource URLs, Redis config. So a wrong claim, not only
#   a malformed one, fails too. This script is the GATE around that generator: it runs it,
#   reads `gaps`, and decides the exit code.
#
# WHY IT IS A GATE SCRIPT AND NOT A ci.yml STEP  (#3629 / #4083)
#   It used to be an inline `run:` step in ci.yml's `ui-build` job, whose
#   `if: needs.changes-ui.outputs.changed == 'true'` fires only for `openbank-admin-ui/`,
#   `*/governance.yaml`, or the governance schema. A PR that adds a whole new service
#   directory and omits governance.yaml touches NONE of those — so the job was not skipped,
#   it was ABSENT: nothing reported, the aggregate check was green, and no signal anywhere
#   said a gate had not been consulted. openbank-engagement-service shipped that way and the
#   fleet only found out when a later, unrelated PR turned the job on again and inherited the
#   red (#4083). Third sighting of one structural defect (#2236, #3629, #4083); the remedy is
#   always the same — declare it in .github/gates/gates.yaml, which runs unconditionally by
#   construction.
#
# WHY IT READS `gaps` THE WAY IT DOES
#   `gaps` is a top-level string array on governance.json. Issue #2165: the old reporter read
#   a `.modules` field that has never existed, so it threw a TypeError on EVERY failure and
#   never once named a module — a gate whose failure path had never run. There is deliberately
#   no `?? []` fallback: a missing array means the generator is broken, and that must be loud
#   rather than silently reported as "no gaps".
#
# THE `unverifiedDatabaseNames` LINE
#   ADR-0196: a declared databaseName that no datasource URL in the tree could confirm is not
#   a gap, but it is not a fact either. Printed every clean run so the number stays visible
#   instead of quietly growing.
#
# DEPENDENCIES
#   The generator imports `yaml` and `zod` from openbank-admin-ui/node_modules. The gates
#   shard does not install npm packages, so this script does it — via `npm ci`, which
#   verifies the committed lockfile's integrity hashes, and never a loose `npm install`.
#   Skipped when the modules already resolve, which is the local-developer case.
#
# Usage:  check-governance-manifest.sh [--self-test]
# Env:    GOVERNANCE_REPO   repo root to scan (default: this repo). Set ONLY by the self-test,
#                           which is the sole reason it is a variable.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
UI_DIR="$REPO_ROOT/openbank-admin-ui"

ensure_deps() {
  if node -e "require.resolve('yaml',{paths:['$UI_DIR']});require.resolve('zod',{paths:['$UI_DIR']})" \
      >/dev/null 2>&1; then
    return 0
  fi
  echo "[governance-manifest] installing openbank-admin-ui production deps (npm ci)"
  ( cd "$UI_DIR" && npm ci --omit=dev --ignore-scripts --no-audit --no-fund >/dev/null )
}

# --- falsification -----------------------------------------------------------------------
# Fed a tree it MUST flag and one it must not. The repo's hardest-won CI rule is that a gate
# which has only ever passed is unfalsified (#2165, #2154, #2177) — and this gate's whole
# history is exactly that, because for most of it the job it lived in never ran.
if [ "${1:-}" = "--self-test" ]; then
  ensure_deps
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  self="${BASH_SOURCE[0]}"
  fails=0

  mk_module() {  # $1 module name, $2 governance.yaml body ("" = do not create it)
    mkdir -p "$tmp/$1"
    echo "0.1.0" > "$tmp/$1/version.txt"
    if [ -n "$2" ]; then printf '%s\n' "$2" > "$tmp/$1/governance.yaml"; else rm -f "$tmp/$1/governance.yaml"; fi
  }

  CLEAN_YAML='dataDomain: platform
primaryDatastore: none
ownsNoDatabase: true
dataLineageRole: internal
dataClassification: internal
retentionPolicy: not applicable
evidenceExported: false'

  probe() {  # $1 label, $2 expected rc, $3 must-appear substring ("" = none)
    local label="$1" want="$2" needle="$3" rc=0 out
    out="$(GOVERNANCE_REPO="$tmp" bash "$self" 2>&1)" || rc=$?
    if [ "$rc" != "$want" ]; then
      echo "  BAD  $label: want rc=$want, got rc=$rc"; echo "$out" | sed 's/^/       | /'
      fails=$((fails + 1))
      return
    fi
    if [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then
      echo "  BAD  $label: rc=$want as expected, but the output never named '$needle'"
      echo "$out" | sed 's/^/       | /'
      fails=$((fails + 1))
      return
    fi
    echo "  ok   $label"
  }

  # A gate that goes red without NAMING the offending module is the #2165 failure verbatim,
  # so the red case asserts the module name is in the output, not merely the exit code.
  mk_module openbank-selftest-service "$CLEAN_YAML"
  probe "a module with a valid governance.yaml passes" 0 ""

  mk_module openbank-selftest-service ""
  probe "a module with NO governance.yaml is flagged, by name" 1 "openbank-selftest-service"

  mk_module openbank-selftest-service 'dataDomain: platform
primaryDatastore: PostgreSQL
databaseName: 12345'
  probe "an invalid governance.yaml is flagged, by name" 1 "openbank-selftest-service"

  if [ "$fails" -ne 0 ]; then
    echo "self-test FAILED ($fails case(s))" >&2
    exit 1
  fi
  echo "self-test ok: governance-manifest is falsifiable (3 cases)"
  exit 0
fi

ensure_deps

SCAN_REPO="${GOVERNANCE_REPO:-$REPO_ROOT}"
# node's require() dispatches on the EXTENSION — a bare mktemp name is parsed as CommonJS
# JS and dies on the first `"key":`, which is how the self-test caught this before CI did.
OUT_DIR="$(mktemp -d)"
OUT="$OUT_DIR/governance.json"
trap 'rm -rf "$OUT_DIR"' EXIT

node "$UI_DIR/scripts/generate-governance.mjs" --repo "$SCAN_REPO" --out "$OUT"

# The corpus this gate examined: released components (a directory with version.txt), which is
# exactly the set generate-governance.mjs enumerates. Printed for run-gates.py's min_subjects
# floor — a gate whose corpus silently went to zero would otherwise pass everything (#4339).
node -e "console.log('SUBJECTS=' + require(process.argv[1]).totals.modules)" "$OUT"

GAPS="$(node -e "console.log(require(process.argv[1]).totals.withGaps)" "$OUT")"
if [ "$GAPS" -gt 0 ]; then
  echo "::error::Governance drift — $GAPS module(s) missing or with an invalid governance.yaml (ADR-0071 Phase 4). Add a governance.yaml to each flagged service."
  node -e "const g=require(process.argv[1]); if(!Array.isArray(g.gaps)){console.error('::error::governance.json has no gaps array — generate-governance.mjs is broken');process.exit(2)} for(const x of g.gaps) console.log('  gap: '+x)" "$OUT"
  exit 1
fi

node -e "const t=require(process.argv[1]).totals; console.log(\`Governance manifest clean: \${t.modules} modules, 0 gaps, \${t.unverifiedDatabaseNames} unverified databaseName.\`)" "$OUT"
