#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Derives the scope of the contract-drift gate (`.github/workflows/pact-drift-check.yml`,
# ADR-0063 Phase 2, issue #468) instead of restating it by hand.
#
# WHY DERIVED: the gate's core assertion is `git diff --exit-code -- pacts/`, which can only see
# files the regeneration step actually rewrote, so a consumer module missing from the scope is not
# merely "unchecked" — it reads as PASSING, forever, silently. That is what happened to
# `:openbank-interest-service` (a committed pact, a green gate, and nothing regenerating it) and to
# `:openbank-fx-service` before PR #2284 added it by hand. A hand-maintained list of the very thing
# being checked IS the bug. #2309 added a runtime mtime check that *detects* the omission; this
# removes the omission's source. The two compose: this decides the scope, that proves it held.
#
# MODES
#   (no args)     print the Gradle arguments that regenerate every gated consumer pact, one per line
#   --uncovered   print the pacts/ paths this gate cannot cover, one per line (may be empty)
#
# EXIT 1 + diagnostics on stderr when any of these no longer holds:
#   1. every `pacts/*.json` is produced by some `*PactConsumerTest.kt` — no orphan committed pact;
#   2. every pact a consumer test generates is committed under `pacts/`;
#   3. every `@Pact(consumer = .., provider = ..)` resolves to a consumer and provider name;
#   4. the set of pacts left ungated by SKIP_MODULES matches UNGATED_PACTS exactly, both ways — so
#      dropping a module out of the gate costs an explicit, reviewable line naming what it strands.
#
# Deliberately bash-3.2 clean (no mapfile/readarray, no associative arrays): it must be runnable —
# and falsifiable — on a stock macOS dev machine, not only on the ubuntu runner. A gate you cannot
# feed a failing case locally is a gate nobody has ever seen fail.

set -euo pipefail

cd "$(dirname "$0")/../.."

MODE="${1:-args}"

# Modules whose consumer pact tests cannot run in a CI job at all. EMPTY as of #2319: openbank-
# swift-service was the only entry, and the reason recorded for it did not hold — measured, its two
# consumer tests run under CI=true in 17s with a broker URL set and never attempt to publish.
# Adding an entry costs a reason here AND the pacts it strands in UNGATED_PACTS below; the checks
# fail on a stale declaration in either direction, so the pair cannot drift apart.
SKIP_MODULES=""

# The pacts SKIP_MODULES strands, as paths under pacts/. Checked against what is actually derived,
# in both directions, and consumed verbatim by the workflow's coverage step.
UNGATED_PACTS=""

fail=0
err() {
  echo "$*" >&2
  fail=1
}

# `contains <needle> <newline-separated haystack>`
contains() {
  [ -n "$2" ] || return 1
  printf '%s\n' "$2" | grep -qxF -- "$1"
}

# Resolve one `@Pact` argument to the string pact-jvm will use. It is written either as a literal
# ("openbank-mcp-service") or as an identifier referring to a constant in the same test class
# (`@Pact(consumer = CONSUMER, provider = PROVIDER)` — the shape openbank-mcp-service,
# openbank-vop-service and openbank-psd2-service use). Resolving only literals silently matched
# nothing for those files, which is exactly the class of quiet miss this script exists to prevent.
resolve_token() {
  _tok="$1"
  _file="$2"
  case "$_tok" in
    '"'*'"')
      printf '%s' "$_tok" | sed 's/^"//; s/"$//'
      ;;
    *)
      # Strip any qualifier (Companion.CONSUMER -> CONSUMER); constants live in the same file.
      _name="${_tok##*.}"
      # `|| true` again: an unresolvable constant is a case this script must REPORT, and without it
      # `set -o pipefail` turns grep's no-match into a silent abort of the whole run — the caller's
      # `err` line never executes. Every grep here whose empty result is a normal outcome needs it.
      { grep -ohE "val[[:space:]]+${_name}[[:space:]]*(:[[:space:]]*String[[:space:]]*)?=[[:space:]]*\"[^\"]+\"" "$_file" 2>/dev/null || true; } |
        head -1 | sed -E 's/.*=[[:space:]]*"([^"]+)".*/\1/'
      ;;
  esac
}

test_files="$(find . -path './*/src/test/kotlin/*/contract/*PactConsumerTest.kt' -not -path './.git/*' | sort)"
if [ -z "$test_files" ]; then
  echo "ERROR: no *PactConsumerTest.kt found — the discovery glob is broken" >&2
  exit 1
fi

gated_args=""
gated_pacts=""
ungated_pacts=""
gated_modules=""
skipped_modules=""

while IFS= read -r f; do
  [ -n "$f" ] || continue
  module="$(printf '%s\n' "$f" | cut -d/ -f2)"

  # `@Pact(consumer = <c>, provider = <p>)` is what names the file pact-jvm writes into pacts/.
  # Parse it rather than guessing from the module name — openbank-swift-service holds a test whose
  # consumer is openbank-transaction-service, so module-name inference would mis-map it.
  #
  # `|| true`: grep exits 1 on no match, and under `set -o pipefail` that would abort the whole
  # script at the assignment — killing it *before* the diagnostic below could ever print. That is
  # how the first version of this script failed on CI: exit 1, zero output, nothing to act on.
  raw="$(
    grep -ohE '@Pact\([[:space:]]*consumer[[:space:]]*=[[:space:]]*("[^"]+"|[A-Za-z_][A-Za-z0-9_.]*)[[:space:]]*,[[:space:]]*provider[[:space:]]*=[[:space:]]*("[^"]+"|[A-Za-z_][A-Za-z0-9_.]*)' "$f" || true
  )"
  if [ -z "$raw" ]; then
    err "ERROR: $f declares no @Pact(consumer = .., provider = ..) — cannot map it to a pacts/ file"
    continue
  fi

  pairs=""
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    c_tok="$(printf '%s' "$line" | sed -E 's/.*consumer[[:space:]]*=[[:space:]]*("[^"]+"|[A-Za-z_][A-Za-z0-9_.]*).*/\1/')"
    p_tok="$(printf '%s' "$line" | sed -E 's/.*provider[[:space:]]*=[[:space:]]*("[^"]+"|[A-Za-z_][A-Za-z0-9_.]*).*/\1/')"
    c="$(resolve_token "$c_tok" "$f")"
    p="$(resolve_token "$p_tok" "$f")"
    if [ -z "$c" ] || [ -z "$p" ]; then
      err "ERROR: $f — cannot resolve @Pact(consumer = $c_tok, provider = $p_tok) to names; if these are constants, they must be declared in the same file as val <name> = \"...\""
      continue
    fi
    pairs="$pairs$c-$p.json
"
  done <<EOF
$raw
EOF

  pairs="$(printf '%s' "$pairs" | grep -v '^$' | sort -u || true)"
  [ -n "$pairs" ] || continue

  if contains "$module" "$SKIP_MODULES"; then
    contains "$module" "$skipped_modules" || skipped_modules="$skipped_modules$module
"
    ungated_pacts="$ungated_pacts$pairs
"
    continue
  fi

  gated_pacts="$gated_pacts$pairs
"
  if ! contains "$module" "$gated_modules"; then
    gated_modules="$gated_modules$module
"
    # One `--tests` filter per module; Gradle applies it to the `:test` task it follows.
    # `--rerun` is load-bearing (#2291, #2309): the pact JSON is not a declared task output, so a
    # warm build cache reports `test` UP-TO-DATE, nothing is rewritten, and the diff below passes
    # having read nothing.
    gated_args="$gated_args:$module:test
--rerun
--tests
*.contract.*PactConsumerTest
"
  fi
done <<EOF
$test_files
EOF

gated_pacts="$(printf '%s' "$gated_pacts" | grep -v '^$' | sort -u || true)"
ungated_pacts="$(printf '%s' "$ungated_pacts" | grep -v '^$' | sort -u || true)"
all_generated="$(printf '%s\n%s\n' "$gated_pacts" "$ungated_pacts" | grep -v '^$' | sort -u || true)"
committed="$(find pacts -maxdepth 1 -name '*.json' -exec basename {} \; | sort)"

# 1. orphan committed pact — nothing generates it, so nothing can ever prove it is current.
while IFS= read -r p; do
  [ -n "$p" ] || continue
  contains "$p" "$all_generated" ||
    err "ERROR: pacts/$p is generated by no *PactConsumerTest.kt — orphan pact, nothing can prove it is current"
done <<EOF
$committed
EOF

# 2. a generated pact that was never committed.
while IFS= read -r p; do
  [ -n "$p" ] || continue
  contains "$p" "$committed" ||
    err "ERROR: a consumer test generates $p but pacts/$p is not committed — regenerate and commit it"
done <<EOF
$all_generated
EOF

# 3. the ungated set must match the declared allow-list exactly, in both directions.
while IFS= read -r p; do
  [ -n "$p" ] || continue
  contains "pacts/$p" "$UNGATED_PACTS" ||
    err "ERROR: pacts/$p is left ungated by SKIP_MODULES but is not declared in UNGATED_PACTS — declare it with a reason, or un-skip its module"
done <<EOF
$ungated_pacts
EOF
while IFS= read -r p; do
  [ -n "$p" ] || continue
  contains "${p#pacts/}" "$ungated_pacts" ||
    err "ERROR: UNGATED_PACTS lists $p but it is now gated (or gone) — drop the stale entry"
done <<EOF
$UNGATED_PACTS
EOF

[ "$fail" -eq 0 ] || exit 1

if [ "$MODE" = "--uncovered" ]; then
  printf '%s\n' "$UNGATED_PACTS"
  exit 0
fi

{
  echo "gated modules:"
  printf '%s' "$gated_modules" | sed 's/^/  /'
  echo "skipped modules: $(printf '%s' "$skipped_modules" | tr '\n' ' ')"
  echo "ungated pacts: $(printf '%s' "$ungated_pacts" | tr '\n' ' ')"
} >&2

printf '%s' "$gated_args"
