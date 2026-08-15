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
# --- self-test ------------------------------------------------------------------------
# A `DomainEvent()` subclass that does not pass `occurredAt` to the super constructor takes
# the base default, and this repo's base default was `Instant.EPOCH` — a lie every test agrees
# with, because `isNotNull()` passes against 1970-01-01. 23 of 25 fleet call sites took that
# default (#3882). The guard is a substring search, so its own falsification matters more than
# usual: nothing about it is obviously right or obviously wrong by reading.
if [ "${1:-}" = "--self-test" ]; then
  set +e
  td=$(mktemp -d); trap 'rm -rf "$td"' EXIT
  fails=0

  put() { mkdir -p "$(dirname "$1")"; printf '%b' "$2" > "$1"; }
  expect() { # expect <label> <root> <want-rc> [substring]
    local label="$1" root="$2" want="$3" sub="${4:-}" out rc
    out=$(bash "$0" "$root" 2>&1); rc=$?
    if [ "$rc" -ne "$want" ]; then
      echo "::error::self-test: $label — expected rc=$want, got rc=$rc: $out" >&2; fails=$((fails+1))
    elif [ -n "$sub" ] && ! printf '%s' "$out" | grep -qF -- "$sub"; then
      echo "::error::self-test: $label — rc right, reason wrong (no '$sub'): $out" >&2; fails=$((fails+1))
    fi
  }

  # THE DEFECT: an empty super call, so the base default (once Instant.EPOCH) wins.
  a="$td/bad"; put "$a/src/main/kotlin/Ev.kt" 'data class Paid(val id: String) : DomainEvent()\n'
  expect "an empty DomainEvent() super call is FLAGGED" "$a" 1

  # The correct shape: occurredAt passed explicitly.
  b="$td/good"; put "$b/src/main/kotlin/Ev.kt" 'data class Paid(val id: String) : DomainEvent(occurredAt = now)\n'
  expect "passing occurredAt is clean" "$b" 0 "all DomainEvent subclasses pass occurredAt"

  # SCOPE: tests routinely construct events with defaults on purpose; flagging them would make
  # the gate unusable, which is why the production filter excludes them.
  c="$td/test"; put "$c/src/test/kotlin/EvTest.kt" 'val e = Paid("x") : DomainEvent()\n'
  expect "a test file is out of scope" "$c" 0 "all DomainEvent subclasses pass occurredAt"

  # ...and build output, which is a copy of source and would double every finding.
  d="$td/build"; put "$d/build/generated/Ev.kt" 'data class Paid(val id: String) : DomainEvent()\n'
  expect "build output is out of scope" "$d" 0 "all DomainEvent subclasses pass occurredAt"

  # A tree with no Kotlin at all: the count must say ZERO rather than let a moved layout read
  # like a clean fleet. The gate prints SUBJECTS for exactly this reason.
  e="$td/empty"; mkdir -p "$e"
  expect "an empty tree reports 0 subjects" "$e" 0 "SUBJECTS=0"

  if [ "$fails" -gt 0 ]; then echo "self-test FAILED ($fails case(s))" >&2; exit 1; fi
  echo "self-test ok: DomainEvent occurredAt guard is falsifiable (5 cases)"
  exit 0
fi

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
