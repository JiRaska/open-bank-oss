#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
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

MANIFEST="openbank-admin-ui/src/lib/governance/manifest.ts"

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

if [ "$FAIL" -eq 0 ]; then
  echo "OK: $MANIFEST is types-only — no hard-coded versions or inline data (ADR-0071 Phase 4)."
fi

exit "$FAIL"
