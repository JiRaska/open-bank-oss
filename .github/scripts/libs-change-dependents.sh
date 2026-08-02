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
  # END-TO-END exit status. Everything above tests the matchers in-process, which is why this
  # script could exit 1 on the commonest input in production while the selftest stayed green: the
  # caller consumes the script as a command substitution under `set -e`, so its EXIT STATUS is part
  # of the contract and has to be exercised by actually running it.
  local out rc
  # (a) No libs module changed — the overwhelmingly common push. Must be exit 0 and no output.
  out="$(printf 'openbank-fraud-service/src/main/kotlin/X.kt\n' \
    | bash "$0" "openbank-fraud-service" 2>/dev/null)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "selftest FAIL: a push touching no libs module exited $rc — 'no dependents' is a normal answer" >&2
    fail=1
  elif [ -n "$out" ]; then
    echo "selftest FAIL: a push touching no libs module printed '$out'" >&2
    fail=1
  fi
  # (b) A libs module changed, but NO consumer of it is in ALL_SERVICES — so every comparison in
  #     the intersection loop fails, including the last one. (An empty argument cannot be used
  #     here: `${1:?}` rejects it as a usage error, which is a legitimate non-zero.)
  printf 'openbank-libs-temporal/src/main/kotlin/X.kt\n' | bash "$0" "openbank-not-a-real-service" >/dev/null 2>&1 \
    || { echo "selftest FAIL: dependents outside ALL_SERVICES made the script exit non-zero" >&2; fail=1; }
  # (c) The positive path still works end to end.
  out="$(printf 'openbank-libs-temporal/src/main/kotlin/X.kt\n' \
    | bash "$0" "openbank-fx-service openbank-fraud-service" 2>/dev/null)" \
    || { echo "selftest FAIL: a real libs change exited non-zero" >&2; fail=1; }
  command grep -qx openbank-fx-service <<< "$out" || {
    echo "selftest FAIL: a libs-temporal change did not yield fx-service end to end" >&2; fail=1; }

  [ "$fail" -eq 0 ] && echo "selftest OK: matchers verified in both directions, and the exit status verified end to end."
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
# "No dependents" is the NORMAL answer, not an error — most pushes touch no libs module at all.
# Under `set -euo pipefail` this block had two silent routes to exit 1, and the caller consumes it
# as `LIBS_DEPENDENTS="$( … | bash this-script … )"` under `set -e`, so either one killed the
# "Detect changed services" step with **no output whatsoever** and every push-triggered auto-deploy
# run went red without deploying anything (era 2 of #3403; runs from 14:56 on 2026-08-02 onward):
#   1. an empty $DEPENDENTS means `grep -v '^$'` matches nothing and exits 1, which pipefail
#      propagates as the pipeline's status;
#   2. even with dependents, the `while` loop's status is its body's last command — so if the last
#      service read is NOT in $ALL_SERVICES, the inner `for` ends on a failed `[ … ]` and the loop
#      exits 1 too.
# Hence the guarded grep AND the explicit `exit 0`: this script's contract is "print the dependents,
# possibly none", and only a real error (a missing argument, an unreadable build file) may be
# non-zero. `--selftest` covers both routes.
# `if` rather than `[ … ] && echo && break`: a `for` whose last statement is a FAILED `[ … ]`
# returns 1, which becomes the `while` body's status, then the loop's, then the pipeline's — and
# `set -e` aborts there, before any trailing `exit 0` can run. An `if` with no `else` returns 0
# when its condition is false, so the loop ends cleanly whether or not the last service matched.
printf '%s\n' $DEPENDENTS | { command grep -v '^$' || true; } | sort -u | while read -r svc; do
  for known in $ALL_SERVICES; do
    if [ "$svc" = "$known" ]; then
      echo "$svc"
      break
    fi
  done
done

exit 0
