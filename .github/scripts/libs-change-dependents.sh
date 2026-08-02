#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Which services must be rebuilt because a shared library changed (issue #2983).
#
# THE DEFECT THIS CLOSES
# `Auto deploy` derived its build set from changed SERVICE directories, plus a hardcoded
# "shared paths" regex that named only `openbank-libs`, `openbank-libs-domain` and
# `openbank-libs-runtime`. The ADR-0122 split created two more siblings, and a change under
# `openbank-libs-temporal/src/main` therefore matched nothing at all:
#
#     Detect changed services: success
#     Build + push: skipped
#
# #2949 fixed the Temporal payload converter and rebuilt no consumer. campaign-service only got it
# because someone re-dispatched by hand; fx-service and statement-service were still running images
# built before the fix. Their pods were green, which says nothing about the change — they were not
# running it.
#
# The second-order cost is worse than a delayed fix: a REGRESSION in a shared library then reaches
# consumers one at a time, weeks apart, each carried in by an unrelated PR — and by the time it
# surfaces, the correlation with the library change is gone and the innocent PR gets the blame.
#
# WHY DERIVED, NOT LISTED
# The dependency is already declared in each consumer's build.gradle.kts. A hand-kept libs→services
# mapping would rot exactly like the ESO cert-reader allow-list did (#2851). This greps the
# declarations, so the mapping cannot drift from the build files it describes.
#
# It is also narrower than "rebuild everything on any libs change": libs-temporal has 16 consumers,
# not 53. Measured today — domain 45, runtime 43, temporal 16.
#
# openbank-libs-testing is DELIBERATELY not a trigger: it is declared `testImplementation` by all
# five of its consumers, so it is absent from the runtime image and rebuilding for it would burn
# fleet CI to ship a byte-identical artifact.
#
# TRULY GLOBAL paths (build-logic, gradle wrapper, settings.gradle.kts, openbank-libs itself) still
# mean everything: they change how every module is built, and no per-module declaration expresses
# that.
#
# Usage:
#   libs-change-dependents.sh <all-services>            # changed files on stdin, one per line
#   libs-change-dependents.sh --selftest
# Prints the services to rebuild, one per line (possibly none). Prints ALL_SERVICES for a global
# path. Never prints a service outside the ALL_SERVICES it was given.
set -euo pipefail

# Runtime-affecting shared modules → their declaration token in a consumer's build file.
LIBS_MODULES=(openbank-libs-domain openbank-libs-runtime openbank-libs-temporal)
# Paths that change how EVERYTHING is built; no declaration can express these.
GLOBAL_RE='^(build-logic/|gradle/|gradlew|settings\.gradle\.kts|build\.gradle\.kts|openbank-libs/(src/main|build\.gradle\.kts|gradle/))'
# A libs module matters when its compiled sources or its own build file move. Its docs do not
# (issue #375: a bare prefix match rebuilt 37 services on a doc-only change).
libs_re() { printf '^%s/(src/main|build\\.gradle\\.kts)' "$1"; }

dependents_of() {
  local module="$1"
  # `project(":<module>")` under implementation/api — testImplementation consumers are excluded by
  # LIBS_MODULES not containing openbank-libs-testing, and are additionally skipped here so a
  # future testImplementation of a runtime module does not trigger a pointless rebuild.
  command grep -lE "^[[:space:]]*(implementation|api)\(project\(\":${module}\"\)\)" \
    openbank-*/build.gradle.kts 2>/dev/null | cut -d/ -f1 || true
}

selftest() {
  local fail=0
  # The declaration matcher must see a real consumer and must not see a test-only one.
  if ! dependents_of openbank-libs-temporal | command grep -qx openbank-fx-service; then
    echo "selftest FAIL: fx-service is not detected as a libs-temporal consumer" >&2
    fail=1
  fi
  if dependents_of openbank-libs-temporal | command grep -qx openbank-libs-temporal; then
    echo "selftest FAIL: a module reported as its own consumer" >&2
    fail=1
  fi
  # testImplementation must not count as a runtime dependency.
  if dependents_of openbank-libs-testing | command grep -q .; then
    echo "selftest FAIL: testImplementation consumers of libs-testing were counted as runtime" >&2
    fail=1
  fi
  # The path matchers, both directions — a doc-only change must NOT trigger a module.
  local re; re="$(libs_re openbank-libs-temporal)"
  command grep -qE "$re" <<< "openbank-libs-temporal/src/main/kotlin/X.kt" || {
    echo "selftest FAIL: a real source change did not match its module" >&2; fail=1; }
  if command grep -qE "$re" <<< "openbank-libs-temporal/docs/README.md"; then
    echo "selftest FAIL: a docs-only change matched its module (issue #375)" >&2
    fail=1
  fi
  command grep -qE "$GLOBAL_RE" <<< "build-logic/src/main/kotlin/X.kt" || {
    echo "selftest FAIL: build-logic did not match the global paths" >&2; fail=1; }
  if command grep -qE "$GLOBAL_RE" <<< "openbank-libs/governance/rules.yaml"; then
    echo "selftest FAIL: a governance catalog matched the global paths — that is data, not a compile input" >&2
    fail=1
  fi
  # Exit status, asserted in both directions. The caller reads this script inside a command
  # substitution under `set -e`, so a non-zero status is fatal REGARDLESS of the output — and the
  # matcher assertions above cannot see that, which is how a script that could never succeed on a
  # push kept a green self-test.
  local me="${BASH_SOURCE[0]}"
  local svcs="openbank-fx-service openbank-account-service"
  if ! printf 'openbank-admin-ui/src/x.tsx\n' | bash "$me" "$svcs" >/dev/null 2>&1; then
    echo "selftest FAIL: a change touching no libs module must exit 0, not report failure" >&2
    fail=1
  fi
  if ! printf 'openbank-libs-domain/src/main/kotlin/X.kt\n' | bash "$me" "$svcs" >/dev/null 2>&1; then
    echo "selftest FAIL: a real libs change must exit 0 — it printed consumers and still failed" >&2
    fail=1
  fi

  [ "$fail" -eq 0 ] && echo "selftest OK: matchers verified in both directions, and the exit status on both an empty and a non-empty result."
  return "$fail"
}

if [ "${1:-}" = "--selftest" ]; then
  selftest
  exit $?
fi

ALL_SERVICES="${1:?usage: libs-change-dependents.sh <all-services>   # changed files on stdin}"
CHANGED="$(cat)"

if command grep -qE "$GLOBAL_RE" <<< "$CHANGED"; then
  echo "global build input changed → every service" >&2
  printf '%s\n' $ALL_SERVICES
  exit 0
fi

DEPENDENTS=""
for module in "${LIBS_MODULES[@]}"; do
  if command grep -qE "$(libs_re "$module")" <<< "$CHANGED"; then
    found="$(dependents_of "$module")"
    count="$(printf '%s' "$found" | command grep -c . || true)"
    echo "${module} changed → ${count} consumer(s)" >&2
    DEPENDENTS="${DEPENDENTS}
${found}"
  fi
done

# Intersect with ALL_SERVICES: a consumer that this pipeline cannot build (a different pipeline
# owns it, or it has no gitops manifest) must never enter the build set.
#
# The `|| true` is load-bearing, not defensive noise. An empty result is the COMMON case — most
# pushes touch no libs module — and `grep` reports "nothing matched" as exit 1. Under the
# `set -euo pipefail` at the top, and again in the caller's `SERVICES_JSON="$(...)"`, that status
# killed the whole auto-deploy detect step with no message at all, so every push-triggered run
# after this script landed detected nothing and deployed nothing. It failed even on the path that
# WORKS: a libs change printed the right consumers and still exited 1, because a `while` loop's
# status is the last thing it ran — here a non-matching `[` test.
printf '%s\n' $DEPENDENTS \
  | sort -u \
  | command grep -Fxf <(printf '%s\n' $ALL_SERVICES) || true
