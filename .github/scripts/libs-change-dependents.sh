#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Runtime consumers of changed shared code, including transitive/umbrella dependencies.
#
# CI and deployment share library_dependency_graph.py. CI includes test edges; deployment
# excludes recognized test-only edges. Unknown syntax falls back to the supplied fleet.
# A global build input still rebuilds every deployable service. Neither a docs-only change
# nor a library's src/test change seeds a deployment.
#
# Usage: libs-change-dependents.sh '<all services>' < changed-files
#        libs-change-dependents.sh --selftest
set -euo pipefail

GLOBAL_RE='^(build-logic/|gradle/|gradlew|settings\.gradle\.kts|build\.gradle\.kts|openbank-libs/(src/main|build\.gradle\.kts|gradle/))'

service_lines() { printf '%s\n' "$1" | tr '[:space:]' '\n' | sed '/^$/d' | sort -u; }

selftest() {
  local me="${BASH_SOURCE[0]}" result
  local svcs="openbank-customer-edge openbank-product-catalog openbank-unrelated"
  python3 .github/scripts/test_library_dependency_graph.py
  result="$(printf '%s\n' 'openbank-libs-domain/src/main/kotlin/X.kt' | bash "$me" "$svcs")"
  [ "$result" = "$(printf '%s\n' openbank-customer-edge openbank-product-catalog)" ] || {
    echo 'selftest FAIL: transitive umbrella consumers missing or unrelated service included' >&2; return 1;
  }
  for path in openbank-admin-ui/src/x.tsx openbank-libs-domain/docs/README.md openbank-libs-domain/src/test/Test.kt; do
    result="$(printf '%s\n' "$path" | bash "$me" "$svcs")"
    [ -z "$result" ] || { echo "selftest FAIL: $path seeded a deployment" >&2; return 1; }
  done
  for path in build-logic/src/main/kotlin/X.kt openbank-libs-unknown/src/main/kotlin/X.kt; do
    result="$(printf '%s\n' "$path" | bash "$me" "$svcs")"
    [ "$result" = "$(service_lines "$svcs")" ] || {
      echo 'selftest FAIL: global/unknown input did not select the entire supplied fleet' >&2; return 1;
    }
  done
  result="$(printf '%s\n' 'openbank-libs-domain/src/main/kotlin/X.kt' | bash "$me" 'openbank-unrelated')"
  [ -z "$result" ] || { echo 'selftest FAIL: result escaped supplied fleet' >&2; return 1; }
  echo 'selftest OK: transitive fan-out, conservative fallback, exclusions and successful empty results'
}

if [ "${1:-}" = --selftest ]; then
  selftest
  exit 0
fi
ALL_SERVICES="${1:?usage: libs-change-dependents.sh '<all services>' < changed-files}"
CHANGED="$(cat)"
if grep -qE "$GLOBAL_RE" <<< "$CHANGED"; then
  echo 'global build input changed → every service' >&2
  service_lines "$ALL_SERVICES"
else
  python3 .github/scripts/service-ci-library-dependents.py --runtime "$ALL_SERVICES" <<< "$CHANGED"
fi
