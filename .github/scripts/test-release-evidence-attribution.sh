#!/usr/bin/env bash
# Self-test for build-release-evidence.sh's AI-attribution coverage block (issue #5838).
#
# Negative-first, because the defect being fixed was a SILENT one: the previous version rendered
# "no commits could be measured" and "commits carry no AI attribution" identically to a healthy
# bundle — an empty `contributors` array either way. Each case below therefore asserts a state the
# old code could not express, so this file goes red against it, and goes red again if anyone
# re-collapses the three states into one.
#
# Run: bash .github/scripts/test-release-evidence-attribution.sh
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/build-release-evidence.sh"
FAILURES=0

fail() { echo "FAIL: $*" >&2; FAILURES=$((FAILURES + 1)); }
pass() { echo "ok: $*"; }

# field <evidence.json> <key under ai_attribution>
field() {
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1]))["ai_attribution"]; print(json.dumps(d.get(sys.argv[2])))' \
    "$1" "$2"
}

# make_repo <workdir> <commit-spec...>  where each spec is "ai" or "human"
make_repo() {
  local dir="$1"; shift
  mkdir -p "$dir/mod"
  git -C "$dir" init -q
  git -C "$dir" config user.email t@example.com
  git -C "$dir" config user.name Tester
  git -C "$dir" config commit.gpgsign false
  local i=0
  for spec in "$@"; do
    i=$((i + 1))
    echo "$i" > "$dir/mod/file$i.txt"
    git -C "$dir" add "mod/file$i.txt"
    if [ "$spec" = "ai" ]; then
      git -C "$dir" commit -q -m "feat(mod): change $i

Co-Authored-By: Claude <noreply@anthropic.com>"
    else
      git -C "$dir" commit -q -m "feat(mod): change $i"
    fi
  done
  echo '{"bomFormat":"CycloneDX"}' > "$dir/sbom.json"
}

run_case() {
  local dir="$1"
  ( cd "$dir" && bash "$SCRIPT" widget 1.0.0 widget-v1.0.0 mod sbom.json >/dev/null 2>&1 )
  echo "$dir/widget-v1.0.0.evidence.json"
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ── Case 1: no AI attribution anywhere — must be a finding, not a clean bundle ──────────
make_repo "$TMP/none" human human
EV="$(run_case "$TMP/none")"
[ "$(field "$EV" status)" = '"NO_ATTRIBUTION"' ] \
  && pass "commits with no AI trailer report NO_ATTRIBUTION" \
  || fail "expected NO_ATTRIBUTION, got $(field "$EV" status)"
[ "$(field "$EV" coverage_pct)" = "0.0" ] \
  || fail "expected coverage_pct 0.0, got $(field "$EV" coverage_pct)"
[ "$(field "$EV" commits_total)" = "2" ] \
  || fail "expected commits_total 2, got $(field "$EV" commits_total)"

# ── Case 2: partial coverage — the denominator must be every commit, not only attributed ─
make_repo "$TMP/partial" ai human ai human
EV="$(run_case "$TMP/partial")"
[ "$(field "$EV" status)" = '"REPORTED"' ] \
  && pass "mixed commits report REPORTED" \
  || fail "expected REPORTED, got $(field "$EV" status)"
[ "$(field "$EV" coverage_pct)" = "50.0" ] \
  && pass "coverage is 2/4 = 50%, i.e. the denominator is all commits" \
  || fail "expected coverage_pct 50.0, got $(field "$EV" coverage_pct)"

# ── Case 3: full coverage ───────────────────────────────────────────────────────────────
make_repo "$TMP/full" ai ai
EV="$(run_case "$TMP/full")"
[ "$(field "$EV" coverage_pct)" = "100.0" ] \
  && pass "fully attributed range reports 100%" \
  || fail "expected coverage_pct 100.0, got $(field "$EV" coverage_pct)"

# ── Case 4: nothing measurable — must NOT read as success ───────────────────────────────
# The module path does not exist, so the range yields no commits: the old code emitted an
# empty contributors list here and nothing else, which is indistinguishable from a clean run.
make_repo "$TMP/empty" ai
mkdir -p "$TMP/empty/other"
( cd "$TMP/empty" && bash "$SCRIPT" widget 1.0.0 widget-v1.0.0 other sbom.json >/dev/null 2>&1 )
EV="$TMP/empty/widget-v1.0.0.evidence.json"
[ "$(field "$EV" status)" = '"UNAVAILABLE"' ] \
  && pass "an unmeasurable range reports UNAVAILABLE, not an empty success" \
  || fail "expected UNAVAILABLE, got $(field "$EV" status)"
[ "$(field "$EV" coverage_pct)" = "null" ] \
  && pass "UNAVAILABLE claims no coverage figure at all" \
  || fail "expected coverage_pct null, got $(field "$EV" coverage_pct)"

if [ "$FAILURES" -gt 0 ]; then
  echo "$FAILURES check(s) failed" >&2
  exit 1
fi
echo "all release-evidence attribution checks passed"
