#!/usr/bin/env bash
# Guard: no service may register its own jakarta.ws.rs.ext.ExceptionMapper for a JDK
# exception type openbank-libs-runtime already maps
# (com/openbank/libs/api/error/CommonExceptionMappers.kt): IllegalArgumentException,
# IllegalStateException, NoSuchElementException, Exception, WebApplicationException.
# Also covers two libs-domain four-eyes types moved into the same file (issue #1394):
# SelfApprovalNotAllowedException, InvalidApprovalStateException — these were previously
# duplicated per-service (10+ byte-identical copies plus a divergent shape in
# notification-service) specifically because no shared mapper existed yet.
#
# JAX-RS provider selection between two ExceptionMapper<T>s registered for the identical
# type T is not deterministically ordered — a second local mapper for a libs-owned type
# does not "override" it, it wins or loses per-request at random. Found live: ledger-service
# had ExceptionMapper<IllegalArgumentException> (422) and ExceptionMapper<IllegalStateException>
# (409) racing libs' 400/422, and psd2-service had ExceptionMapper<IllegalArgumentException>
# (Berlin Group tppMessages envelope) racing libs' generic ApiError envelope for the same
# 400 status — a TPP client could get either response SHAPE non-deterministically (issue #526).
#
# Fix: introduce a dedicated exception type + mapper (see
# openbank-ledger-service's LedgerValidationException/LedgerConflictException, or
# openbank-psd2-service's Psd2RequestFormatException, for the pattern) instead of
# re-mapping the JDK type directly. A service needing a different status code for what
# would otherwise be an IllegalArgumentException/IllegalStateException throws the
# dedicated type from the call site (Kotlin's require()/check() cannot be redirected —
# see requireValid()/checkConflict() in LedgerExceptions.kt for a drop-in replacement).
#
# stdlib-only (grep); no Kotlin-parser dependency, matching
# check-no-service-principal-type.sh / check-domain-purity.py. ENFORCED.
# Usage: check-exception-mapper-collision.sh [root]   (default root: .)
set -euo pipefail

# --- self-test ------------------------------------------------------------------------
# libs-runtime owns the mappers for the shared JDK exception types. A service-local
# ExceptionMapper for the SAME type collides non-deterministically — whichever CDI bean wins
# decides the HTTP status, and it can differ between builds (#526). The rule the fleet relies
# on is "map IllegalArgumentException to 400 in libs-runtime, never locally".
if [ "${1:-}" = "--self-test" ]; then
  set +e
  td=$(mktemp -d); trap 'rm -rf "$td"' EXIT
  fails=0
  put() { mkdir -p "$(dirname "$1")"; printf '%b' "$2" > "$1"; }
  expect() { local label="$1" root="$2" want="$3" sub="${4:-}" out rc
    out=$(bash "$0" "$root" 2>&1); rc=$?
    if [ "$rc" -ne "$want" ]; then echo "::error::self-test: $label — want rc=$want got $rc: $out" >&2; fails=$((fails+1))
    elif [ -n "$sub" ] && ! printf '%s' "$out" | grep -qF -- "$sub"; then
      echo "::error::self-test: $label — rc right, reason wrong (no '$sub'): $out" >&2; fails=$((fails+1)); fi; }
  K() { echo "$1/openbank-x/src/main/kotlin/M.kt"; }

  # THE DEFECT: a local mapper for a libs-runtime-owned type.
  a="$td/bad"; put "$(K "$a")" 'class M : ExceptionMapper<IllegalArgumentException> {\n}\n'
  expect "a local mapper for IllegalArgumentException is FLAGGED" "$a" 1 "collides non-deterministically"
  # The sanctioned fix: a dedicated exception type of the service's own.
  b="$td/good"; put "$(K "$b")" 'class M : ExceptionMapper<MyOwnDomainException> {\n}\n'
  expect "a mapper for a service-owned type is clean" "$b" 0 "none collide"
  # Another owned type from the list, so the case does not pin one name only.
  c="$td/state"; put "$(K "$c")" 'class M : ExceptionMapper<IllegalStateException> {\n}\n'
  expect "IllegalStateException is covered too" "$c" 1 "collides non-deterministically"
  # PROSE: the comment explaining the ban names the very type it bans.
  d="$td/comment"; # The comment must carry the FULL signature `: ExceptionMapper<...>`, or the pattern never
  # matches it and the comment filter is never exercised — a fixture that cannot reach the
  # branch it claims to test.
  put "$(K "$d")" '// never write : ExceptionMapper<IllegalArgumentException> here (#526)\nclass M : ExceptionMapper<MyOwnDomainException> {\n}\n'
  expect "the type named in a comment is not a hit" "$d" 0 "none collide"
  # A tree with no Kotlin at all must not read like a clean fleet.
  e="$td/empty"; mkdir -p "$e/openbank-x/src/main/kotlin"
  expect "an empty tree reports 0 checked" "$e" 0 "0 "

  if [ "$fails" -gt 0 ]; then echo "self-test FAILED ($fails case(s))" >&2; exit 1; fi
  echo "self-test ok: ExceptionMapper collision guard is falsifiable (5 cases)"
  exit 0
fi
root="${1:-.}"
fail=0
checked=0

# Anchored to an actual supertype declaration (": ExceptionMapper<Type>"), not just any
# mention of the string — explanatory comments about this exact defect class (including
# this script's own error message, and the fix's inline documentation) legitimately
# reference these type names in prose and must not self-trigger.
pattern=': *ExceptionMapper<(IllegalArgumentException|IllegalStateException|NoSuchElementException|Exception|WebApplicationException|SelfApprovalNotAllowedException|InvalidApprovalStateException)>'

while IFS= read -r f; do
  checked=$((checked + 1))
  # Exclude Kotlin line-comments (// ...) so prose mentioning the pattern doesn't self-trigger.
  hits=$(grep -nE "$pattern" "$f" | grep -vE '^[0-9]+:[[:space:]]*//' || true)
  if [ -n "$hits" ]; then
    fail=1
    while IFS= read -r hit; do
      lineno="${hit%%:*}"
      echo "::error file=$f,line=$lineno::a service-local ExceptionMapper for a libs-runtime-owned JDK exception type collides non-deterministically (issue #526) — introduce a dedicated exception type + mapper instead."
    done <<< "$hits"
  fi
done < <(find "$root"/openbank-*/src/main/kotlin -name '*.kt' \
  -not -path '*/openbank-libs-runtime/*' \
  -not -path '*/build/*' | sort)

echo "SUBJECTS=$checked"
echo "check-exception-mapper-collision: $checked service source file(s) checked, $( [ "$fail" -eq 0 ] && echo "none collide with a libs-runtime-owned ExceptionMapper type." || echo "VIOLATIONS above." )"
exit "$fail"
