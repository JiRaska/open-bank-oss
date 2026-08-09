#!/usr/bin/env bash
# check-domain-event-occuredat.sh — DomainEvent constructor guard
#
# After ADR-0002 / PR #2662, DomainEvent requires `occurredAt: Instant` as a
# mandatory constructor parameter. A subclass that calls `DomainEvent()` without
# the argument fails to compile on a FULL build — but path-scoped CI only rebuilds
# changed services, so an untouched service silently carries the broken call until
# its next release build (which is a full build). The pattern:
#
#   data class FooEvent(...) : DomainEvent() {  ← WRONG — occurredAt missing
#
# This guard greps the entire source tree for `DomainEvent()` (no argument) in
# production Kotlin files on EVERY PR, independent of which services changed. It
# catches the defect class that caused three simultaneous release build failures
# (ledger, transaction, consent — issue companion to PR #2709/#2710/#2711).
#
# The correct pattern is:
#
#   data class FooEvent(
#       override val occurredAt: Instant,
#       ...
#   ) : DomainEvent(occurredAt) {
#
# ENFORCED: hard fail on any hit. The fleet was swept clean (0 occurrences) before
# this guard was added — any new hit is a genuine defect.
#
# Usage: bash .github/scripts/check-domain-event-occuredat.sh [root-dir]
# Exit:  0 = clean, 1 = violations found

set -euo pipefail
ROOT="${1:-.}"

violations="$(
  grep -r --include="*.kt" "DomainEvent()" "$ROOT" \
    | grep -v "[Tt]est" \
    | grep -v "/build/" \
    | grep -v "\.claude/" \
    || true
)"

if [ -z "$violations" ]; then
  count="$(find "$ROOT" -name "*.kt" -not -path "*/build/*" -not -path "*/.claude/*" | wc -l | tr -d ' ')"
  echo "check-domain-event-occuredat: $count .kt files checked — all DomainEvent subclasses pass occurredAt to super constructor."
  echo "SUBJECTS=$count"
  exit 0
fi

count="$(printf '%s\n' "$violations" | grep -c . || true)"
echo "::error::Found $count DomainEvent() call(s) without occurredAt argument in production code."
echo ""
echo "Every DomainEvent subclass must pass occurredAt to the super constructor:"
echo ""
echo "  data class FooEvent("
echo "      override val occurredAt: Instant,"
echo "      ..."
echo "  ) : DomainEvent(occurredAt) {"
echo ""
echo "Path-scoped CI will NOT catch this in untouched services — it only surfaces"
echo "on the next full release build. Fix before merging."
echo ""
echo "Violations:"
printf '%s\n' "$violations"
exit 1
