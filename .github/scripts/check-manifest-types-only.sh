#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# ADR-0071 Phase 4: verify that manifest.ts contains ONLY type definitions —
# no hand-edited version strings, no inline data arrays, no hardcoded lineage.
#
# Fails (exit 1) if manifest.ts contains any of:
#   • a semver literal inside a string  e.g. flywayDeclaredVersion: '3.0.0'
#   • an exported data array            e.g. export const GOVERNANCE_MANIFEST
#   • an object literal with a serviceName value (inline data entry)
#
# These patterns indicate someone hand-edited data that must come from
# per-service governance.yaml files (ADR-0071).

set -euo pipefail

# The path is overridable so the self-test can drive the real checks against fixtures. It is
# the ONLY reason this is a variable: CI always uses the default.
MANIFEST="${MANIFEST_PATH:-openbank-admin-ui/src/lib/governance/manifest.ts}"

# --- falsification ---------------------------------------------------------------------
# Three rules, each fed a file it MUST flag and one it must not, plus the missing-file case.
# Written when the gate moved out of ci.yml's conditional ui-build job into gates.yaml
# (#3629/#4339): a gate nobody can see fail is not evidence, and this one had never failed.
if [ "${1:-}" = "--self-test" ]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  self="$0"
  fails=0
  probe() {  # $1 label, $2 expected rc, $3 file content ("" = do not create the file)
    local label="$1" want="$2" body="$3" rc=0
    if [ -n "$body" ]; then printf '%s\n' "$body" > "$tmp/manifest.ts"; else rm -f "$tmp/manifest.ts"; fi
    MANIFEST_PATH="$tmp/manifest.ts" bash "$self" >/dev/null 2>&1 || rc=$?
    if [ "$rc" != "$want" ]; then
      echo "  BAD  $label: want rc=$want, got rc=$rc"; fails=$((fails + 1))
    else
      echo "  ok   $label"
    fi
  }
  probe "a types-only manifest passes"        0 "export interface Foo { serviceName: string }"
  probe "a hard-coded semver is flagged"      1 "export interface Foo { v: string } // '1.2.3'"
  probe "an exported const is flagged"        1 "export const GOVERNANCE_MANIFEST = []"
  probe "inline serviceName data is flagged"  1 "const x = { serviceName: 'ledger-service' }"
  probe "a missing manifest is flagged"       1 ""
  if [ "$fails" -ne 0 ]; then
    echo "self-test FAILED ($fails case(s))" >&2
    exit 1
  fi
  echo "self-test ok: manifest-types-only is falsifiable (5 cases)"
  exit 0
fi

if [ ! -f "$MANIFEST" ]; then
  echo "ERROR: $MANIFEST not found — was the file moved?"
  exit 1
fi

FAIL=0

# 1. Semver string literal — catches '1.2.3' or "0.14.2" inside the file
#    Uses ERE: matches quote + digits.digits.digits + same-quote
if grep -qE "('[0-9]+\.[0-9]+\.[0-9]+'|\"[0-9]+\.[0-9]+\.[0-9]+\")" "$MANIFEST"; then
  echo "FAIL: $MANIFEST contains a hard-coded semver literal."
  echo "      Versions must come from per-service governance.yaml (ADR-0071)."
  grep -nE "('[0-9]+\.[0-9]+\.[0-9]+'|\"[0-9]+\.[0-9]+\.[0-9]+\")" "$MANIFEST" || true
  FAIL=1
fi

# 2. Exported const data array — catches re-introduction of GOVERNANCE_MANIFEST
#    export const FOO = or export const FOO: are both data, not types
if grep -qE "^[[:space:]]*export[[:space:]]+const[[:space:]]+" "$MANIFEST"; then
  echo "FAIL: $MANIFEST contains an exported const (inline data)."
  echo "      The manifest must be types-only; data comes from governance.yaml (ADR-0071)."
  grep -nE "^[[:space:]]*export[[:space:]]+const[[:space:]]+" "$MANIFEST" || true
  FAIL=1
fi

# 3. Inline service name value — a string assigned to serviceName property in data
#    e.g. serviceName: 'ledger-service'   or   serviceName: "party-service"
#    Interface/type definitions use `serviceName: string` (no quotes around value)
if grep -qE "serviceName[[:space:]]*:[[:space:]]*['\"]" "$MANIFEST"; then
  echo "FAIL: $MANIFEST contains inline serviceName data (not a type definition)."
  echo "      Lineage data must come from per-service governance.yaml (ADR-0071)."
  grep -nE "serviceName[[:space:]]*:[[:space:]]*['\"]" "$MANIFEST" || true
  FAIL=1
fi

# One file, and the gate is worthless if it is not there: run-gates.py fails the gate below
# its declared floor, so a moved or renamed manifest.ts is a red rather than a clean run over
# nothing (#4339).
echo "SUBJECTS=$([ -f "$MANIFEST" ] && echo 1 || echo 0)"

if [ "$FAIL" -eq 0 ]; then
  echo "OK: $MANIFEST is types-only — no hard-coded versions or inline data (ADR-0071 Phase 4)."
fi

exit "$FAIL"
