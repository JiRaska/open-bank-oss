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
