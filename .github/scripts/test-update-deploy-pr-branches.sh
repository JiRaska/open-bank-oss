#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Unit test for update-deploy-pr-branches.sh. Stubs `gh` with a fixture binary that
# serves canned PR listings and records every mutating call, then asserts which deploy
# PRs got their branch updated and — just as importantly — that feature PRs were never
# touched. No network. Runs in CI on every PR that touches either file (gates.yaml).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPDATER="${SCRIPT_DIR}/update-deploy-pr-branches.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
UPDATES="$WORK/updates.log"
: > "$UPDATES"

# NOW: 2026-10-15T14:00:00Z. Head ages in fixtures are relative to it.
NOW_EPOCH="$(date -u -j -f '%Y-%m-%dT%H:%M:%SZ' '2026-10-15T14:00:00Z' +%s 2>/dev/null \
               || date -u -d '2026-10-15T14:00:00Z' +%s)"

cat > "$WORK/bin/gh" <<'EOF'
#!/usr/bin/env bash
# Fixture gh. Subcommands used by the updater:
#   pr list  …                       → canned open-PR listing (PR_LIST_JSON)
#   api … repos/OWNER/REPO/pulls/N/commits… → last-commit date per PR (PR_DATE_<N>)
#   api -X PUT …/pulls/N/update-branch → recorded into $UPDATES
set -uo pipefail
case "$1" in
  pr)
    printf '%s' "${PR_LIST_JSON:-[]}"
    ;;
  api)
    url=""
    for a in "$@"; do case "$a" in repos/*) url="$a" ;; esac; done
    if [[ "$url" == *"/update-branch" ]]; then
      n="$(sed -E 's|.*/pulls/([0-9]+)/update-branch.*|\1|' <<< "$url")"
      echo "update-branch #$n" >> "$UPDATES"
      echo '{}'
    elif [[ "$url" == *"/commits"* ]]; then
      n="$(sed -E 's|.*/pulls/([0-9]+)/commits.*|\1|' <<< "$url")"
      var="PR_DATE_${n}"
      printf '%s' "${!var:-2026-10-15T11:00:00Z}"
    else
      echo "fixture gh: unhandled api path: $url" >&2
      exit 2
    fi
    ;;
  *)
    echo "fixture gh: unhandled args: $*" >&2
    exit 2
    ;;
esac
EOF
chmod +x "$WORK/bin/gh"

run_updater() {
  env GH_BIN="$WORK/bin/gh" GITHUB_REPOSITORY="JiRaska/open-bank-oss" \
      NOW_EPOCH="$NOW_EPOCH" MIN_HEAD_AGE_MINUTES=60 UPDATES="$UPDATES" \
      PR_LIST_JSON="$1" bash "$UPDATER"
}

# --- case 1: the MGM shape -----------------------------------------------------------
# #10 deploy PR, BEHIND, head 3h old (> 60m throttle)        → updated
# #11 deploy PR, UNKNOWN, head 30m old (< throttle)           → skipped (fresh)
# #12 feature PR on feat/, BEHIND, head 3h old                → NEVER touched
# #13 auto-deploy PR, CLEAN                                   → untouched
export PR_DATE_10="2026-10-15T11:00:00Z" PR_DATE_11="2026-10-15T13:30:00Z" \
       PR_DATE_12="2026-10-15T11:00:00Z" PR_DATE_13="2026-10-15T11:00:00Z"
LIST='[
  {"number":10,"headRefName":"chore/admin-ui-deploy-18fd3216e","mergeStateStatus":"BEHIND"},
  {"number":11,"headRefName":"chore/gitops-auto-deploy-4b2ff61d","mergeStateStatus":"UNKNOWN"},
  {"number":12,"headRefName":"feat/referral-link","mergeStateStatus":"BEHIND"},
  {"number":13,"headRefName":"chore/gitops-auto-deploy-afc77263","mergeStateStatus":"CLEAN"}
]'
: > "$UPDATES"
OUT="$(run_updater "$LIST")"
grep -q '^update-branch #10$' "$UPDATES" \
  || { echo "FAIL: stale BEHIND deploy PR #10 must be updated. out: $OUT"; exit 1; }
if grep -q '#11' "$UPDATES"; then
  echo "FAIL: fresh-head deploy PR #11 must be throttled, not updated"; exit 1
fi
if grep -q '#1[23]' "$UPDATES"; then
  echo "FAIL: feature/clean PR must never be updated: $(cat "$UPDATES")"; exit 1
fi
[ "$(grep -c . "$UPDATES")" = 1 ] \
  || { echo "FAIL: exactly one update expected, got: $(cat "$UPDATES")"; exit 1; }

# --- case 2: nothing to do ------------------------------------------------------------
: > "$UPDATES"
OUT="$(run_updater '[{"number":20,"headRefName":"feat/x","mergeStateStatus":"BEHIND"}]')"
[ ! -s "$UPDATES" ] || { echo "FAIL: no deploy PRs → no updates. got: $(cat "$UPDATES")"; exit 1; }

echo "PASS: update-deploy-pr-branches.sh — stale deploy PR updated, fresh head throttled, feature PRs never touched, clean PRs untouched"
