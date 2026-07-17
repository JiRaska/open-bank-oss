#!/usr/bin/env bash
# Guard the ADR-0106 identifier intent split: a PR must not ADD a bare
# java.util.UUID.randomUUID() in src/main — new code mints identifiers via
# com.openbank.libs.domain.identifiers.Ids:
#
#   Ids.newId()    — UUIDv7, time-ordered, for durable/indexed identifiers (entity
#                    ids, outbox event_id) → B-tree insert locality.
#   Ids.randomId() — UUIDv4, for values that must NOT carry a creation timestamp or
#                    ordering: idempotency keys, correlation/trace ids, nonces, tokens.
#
# DIFF-SCOPED on purpose: it inspects only the lines THIS change adds, so it never
# fails because some other change elsewhere in the fleet still uses randomUUID(). The
# ~100 pre-existing call sites migrate to Ids as-touched (#1699); they are not flagged
# here. The sanctioned wrapper itself (domain/identifiers/Ids.kt) is excluded.
#
# Usage: check-new-random-uuid.sh <base-ref-or-sha>
#   <base> is the commit to diff HEAD against (the PR base). Empty/unset => no-op
#   (e.g. a push build with no base), so this is safe to wire unconditionally.
set -euo pipefail
BASE="${1:-}"

if [ -z "$BASE" ]; then
  echo "check-new-random-uuid: no base ref (not a PR) — skipping."
  exit 0
fi
if ! git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null; then
  echo "::error::check-new-random-uuid: base ref '$BASE' not found (fetch it before running)."
  exit 1
fi

# Added (+) lines in src/main *.kt — excluding the Ids wrapper — that introduce a bare
# UUID.randomUUID(). awk tracks the current file from each `+++ b/<path>` diff header.
#
# Pathspec note (do NOT "fix" to :(glob)/**): a git pathspec WITHOUT the `:(glob)` magic
# word matches `*` across `/` (fnmatch, FNM_PATHNAME *unset*), so `*/src/main/*.kt` and
# `:(exclude)*/domain/identifiers/Ids.kt` correctly hit deep paths like
# `openbank-ledger-service/src/main/kotlin/.../Foo.kt`. `:(glob)` is what would RESTRICT
# `*` to a single segment (requiring `**`). Verified: a deep src/main change is flagged
# and a deep Ids.kt change is excluded.
violations="$(
  git diff --unified=0 "$BASE" HEAD -- \
      '*/src/main/*.kt' ':(exclude)*/domain/identifiers/Ids.kt' 2>/dev/null \
    | awk '
        /^\+\+\+ /        { f=$2; sub(/^b\//,"",f); next }
        /^\+/ && /UUID\.randomUUID\(\)/ {
          line=substr($0,2)
          trimmed=line
          sub(/^[ \t]+/, "", trimmed)
          if (trimmed !~ /^\/\//) print f ":  " line
        }
      ' || true
)"

if [ -n "$violations" ]; then
  count="$(printf '%s\n' "$violations" | grep -c . || true)"
  echo "::error::This change adds $count bare UUID.randomUUID() call(s) in src/main (ADR-0106)."
  echo "Mint identifiers via com.openbank.libs.domain.identifiers.Ids:"
  echo "    Ids.newId()    — UUIDv7, for durable/indexed identifiers (entity ids, outbox event_id)"
  echo "    Ids.randomId() — UUIDv4, for idempotency keys, correlation/trace ids, nonces, tokens"
  echo ""
  printf '%s\n' "$violations"
  exit 1
fi

echo "check-new-random-uuid: this change adds no bare UUID.randomUUID() in src/main."
