#!/usr/bin/env bash
# Guard against a silent Kotlin+JUnit5 footgun:
#
#   @Test fun `x`() = runBlocking { assertThat(...).isEqualTo(...) }
#
# An expression-body test whose runBlocking lambda's last expression is a
# non-Unit value (e.g. a fluent assertion) infers a NON-Unit return type. JUnit5
# only runs methods returning void/Unit, so such a test is SILENTLY DROPPED — it
# compiles, shows green, and asserts nothing. The fix is a one-token annotation:
#
#   @Test fun `x`(): Unit = runBlocking { ... }
#
# This check fails CI if any test function uses the bare `) = runBlocking {`
# expression-body form without an explicit `: Unit` return type. Scope: src/test
# only. Usage: check-test-runblocking-unit.sh [root-dir] (default: .)
set -euo pipefail
ROOT="${1:-.}"

# Match a function declaration whose body is `= runBlocking {` with no `: Unit`
# return type. The leading `fun .*)` anchors it to a function (not a `val x =
# runBlocking`), and `grep -v ': Unit'` drops the already-safe explicit form.
violations="$(
  find "$ROOT" \
       \( -type d \( -name node_modules -o -name build -o -name .claude -o -name .git \) -prune \) -o \
       \( -path '*/src/test/*' -name '*.kt' -exec \
            grep -nE 'fun [A-Za-z`].*\) = runBlocking ?\{' {} + \) 2>/dev/null \
    | grep -v ': Unit' || true
)"

scanned="$(find "$ROOT" \
     \( -type d \( -name node_modules -o -name build -o -name .claude -o -name .git \) -prune \) -o \
     \( -path '*/src/test/*' -name '*.kt' -print \) 2>/dev/null | wc -l | tr -d ' ')"
echo "SUBJECTS=$scanned"

if [ -n "$violations" ]; then
  count="$(printf '%s\n' "$violations" | grep -c . || true)"
  echo "::error::Found $count test function(s) using the unsafe '= runBlocking {' form without ': Unit'."
  echo "These tests may be SILENTLY DROPPED by JUnit5 (non-Unit return). Add ': Unit':"
  echo "    @Test fun \`x\`(): Unit = runBlocking { ... }"
  echo ""
  printf '%s\n' "$violations"
  exit 1
fi

echo "check-test-runblocking-unit: no unsafe '= runBlocking {' test functions found."
