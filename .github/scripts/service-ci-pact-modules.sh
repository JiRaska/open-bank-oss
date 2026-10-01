#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Sourced by Services CI to map a Pact filename to its Gradle build modules.
# Admin UI is a consumer but not a Gradle module; its trusted Pact workflow
# publishes the contract, and the provider remains in the service matrix.

pact_build_modules() {
  local base prov cons
  base=$(basename "$1" .json)
  case "$base" in *-openbank-*) ;; *) return 1 ;; esac
  prov="openbank-${base##*-openbank-}"
  cons="${base%-openbank-*}"
  if [ "$cons" != "openbank-admin-ui" ]; then printf '%s\n' "$cons"; fi
  printf '%s\n' "$prov"
}

admin_ui_pact_changed() {
  grep -qE '^pacts/openbank-admin-ui-openbank-[a-z0-9-]+\.json$' <<< "$1"
}

pact_build_modules_self_test() {
  is_inert_service_path ".github/scripts/publish-admin-ui-pacts.py" \
    || { echo "selector self-test: Pact publisher must not full-fleet" >&2; return 1; }
  is_inert_service_path ".github/gates/workflow-write-permissions-baseline.txt" \
    || { echo "selector self-test: Pact permission baseline is service-inert" >&2; return 1; }
  [ "$(pact_build_modules pacts/openbank-admin-ui-openbank-context-service.json)" = "openbank-context-service" ] \
    || { echo "selector self-test: Admin UI Pact must select its provider only" >&2; return 1; }
  [ "$(pact_build_modules pacts/openbank-ledger-service-openbank-balance-service.json)" = $'openbank-ledger-service\nopenbank-balance-service' ] \
    || { echo "selector self-test: service Pact must select both sides" >&2; return 1; }
  if pact_build_modules pacts/invalid.json >/dev/null; then
    echo "selector self-test: malformed Pact filename must fail" >&2
    return 1
  fi
  admin_ui_pact_changed $'pacts/openbank-admin-ui-openbank-context-service.json\nopenbank-admin-ui/src/app/page.tsx' \
    || { echo "selector self-test: changed admin UI pact needs publication" >&2; return 1; }
  ! admin_ui_pact_changed 'openbank-admin-ui/src/app/page.tsx' \
    || { echo "selector self-test: UI source alone needs no publication wait" >&2; return 1; }
}
