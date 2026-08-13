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

# NOTE: there is no `grep -v ": Unit"` here any more, and there must not be. The detection
# pattern already requires `) = runBlocking`, so the FIXED form `(): Unit = runBlocking` cannot
# match it — the filter was redundant against the case it was written for. What it did instead
# was drop any line containing ": Unit" ANYWHERE, so a genuine violation whose parameter list
# happened to carry that type — `fun \`pays\`(p: Unit) = runBlocking {` — was silently skipped
# by the gate whose whole job is to stop tests being silently skipped. Measured: flagged after
# the removal, clean before it.

# --- self-test ------------------------------------------------------------------------
# `fun foo() = runBlocking { }` infers a non-Unit return type and JUnit5 SILENTLY IGNORES the
# method — the test never runs, and never fails. This guard is the only thing between that and
# a suite reporting green about assertions nobody executed.
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

  a="$td/bad"; put "$a/svc/src/test/kotlin/T.kt" '@Test\nfun `pays`() = runBlocking {\n  assertTrue(true)\n}\n'
  expect "an untyped = runBlocking test is FLAGGED" "$a" 1 "SILENTLY DROPPED"
  b="$td/good"; put "$b/svc/src/test/kotlin/T.kt" '@Test\nfun `pays`(): Unit = runBlocking {\n  assertTrue(true)\n}\n'
  expect ": Unit is clean" "$b" 0
  c="$td/plain"; put "$c/svc/src/test/kotlin/T.kt" '@Test\nfun pays() = runBlocking {\n  assertTrue(true)\n}\n'
  expect "a plain function name is FLAGGED too" "$c" 1 "SILENTLY DROPPED"
  # THE FALSE NEGATIVE the redundant ": Unit" line-filter used to create: a real violation
  # whose PARAMETER carries that type. This is the case that break "unit-filter" must fail on.
  f="$td/paramunit"; put "$f/svc/src/test/kotlin/T.kt" '@Test\nfun `pays`(p: Unit) = runBlocking {\n  assertTrue(true)\n}\n'
  expect "a violation with a ': Unit' PARAMETER is still FLAGGED" "$f" 1 "SILENTLY DROPPED"
  # ...and the fixed form must stay clean, which the detection pattern alone already ensures.
  g="$td/retunit"; put "$g/svc/src/test/kotlin/T.kt" '@Test\nfun `pays`(p: String): Unit = runBlocking {\n  assertTrue(true)\n}\n'
  expect "': Unit' in RETURN position is clean" "$g" 0

  # SCOPE: main-source runBlocking is a different rule with its own gate; flagging it here
  # would double-report and make both noisy.
  d="$td/mainsrc"; put "$d/svc/src/main/kotlin/M.kt" 'fun helper() = runBlocking {\n  x()\n}\n'
  expect "main source is out of scope" "$d" 0
  # A tree with no tests must say ZERO rather than let a moved layout read like a clean suite.
  e="$td/empty"; mkdir -p "$e"
  expect "an empty tree reports 0 subjects" "$e" 0 "SUBJECTS=0"

  if [ "$fails" -gt 0 ]; then echo "self-test FAILED ($fails case(s))" >&2; exit 1; fi
  echo "self-test ok: test runBlocking Unit guard is falsifiable (7 cases)"
  exit 0
fi
ROOT="${1:-.}"

# Match a function declaration whose body is `= runBlocking {` with no `: Unit`
# return type. The leading `fun .*)` anchors it to a function (not a `val x =
# runBlocking`), and `grep -v ': Unit'` drops the already-safe explicit form.
violations="$(
  find "$ROOT" \
       \( -type d \( -name node_modules -o -name build -o -name .claude -o -name .git \) -prune \) -o \
       \( -path '*/src/test/*' -name '*.kt' -exec \
            grep -nE 'fun [A-Za-z`].*\) = runBlocking ?\{' {} + \) 2>/dev/null \
    || true
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
