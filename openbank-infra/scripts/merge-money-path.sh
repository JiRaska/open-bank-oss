#!/usr/bin/env bash
# merge-money-path.sh — review & squash-merge the OPEN PRs that are money-path
# (i.e. the ones ADR-0030 says need 2 approvals + a threat model).
#
# SAFE BY DEFAULT:
#   - dry-run unless you pass --merge
#   - NEVER uses --admin / any branch-protection bypass (plain squash merge)
#   - refuses to merge a PR whose CI is failing OR still pending
#   - refuses to merge a PR with < 2 approvals UNLESS you also pass
#     --allow-missing-approvals (so the governance gap is a conscious choice)
#
# Usage:
#   ./merge-money-path.sh                              # list candidates + status (no writes)
#   ./merge-money-path.sh --merge                      # squash-merge the green, 2-approved ones
#   ./merge-money-path.sh --merge --allow-missing-approvals   # also merge < 2 approvals (CI still must be green)
#   REPO=/path/to/open-bank-oss ./merge-money-path.sh  # override repo dir
set -euo pipefail

# Default to the repo this script lives in (openbank-infra/scripts/../..); override with REPO=.
REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
RULES="$REPO/openbank-libs/governance/rules.yaml"
DO_MERGE=0
ALLOW_MISSING=0
for a in "$@"; do
  case "$a" in
    --merge) DO_MERGE=1 ;;
    --allow-missing-approvals) ALLOW_MISSING=1 ;;
    *) echo "unknown arg: $a" >&2; exit 2 ;;
  esac
done

cd "$REPO"
command -v gh >/dev/null || { echo "gh not found" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq not found" >&2; exit 1; }

# Authoritative money-path module list, parsed live from rules.yaml (never hardcode — it drifts).
# while-read instead of mapfile: macOS ships bash 3.2, which has no mapfile.
MONEY_PATH=()
while IFS= read -r line; do
  [ -n "$line" ] && MONEY_PATH+=("$line")
done < <(awk '
  /^money_path_services:/ {inblk=1; next}
  inblk && /^[[:space:]]*-[[:space:]]*openbank-/ {
    sub(/^[[:space:]]*-[[:space:]]*/,"");   # strip the "  - " list marker only
    sub(/[[:space:]].*$/,"");               # drop any trailing whitespace/comment
    print; next
  }
  inblk && /^[^[:space:]-]/ {inblk=0}
' "$RULES")
[ "${#MONEY_PATH[@]}" -gt 0 ] || { echo "could not parse money_path_services from $RULES" >&2; exit 1; }
echo "money-path modules (${#MONEY_PATH[@]}): ${MONEY_PATH[*]}"
echo

# Is a PR money-path? -> has the money-path label, OR touches any money-path module dir.
is_money_path() { # $1 = pr json (labels[].name + files[].path)
  local j="$1"
  if echo "$j" | jq -e '.labels[]?.name | select(. == "money-path")' >/dev/null; then return 0; fi
  local m
  for m in "${MONEY_PATH[@]}"; do
    if echo "$j" | jq -e --arg d "$m/" '.files[]?.path | select(startswith($d))' >/dev/null; then return 0; fi
  done
  return 1
}

# Count distinct users whose LATEST review is APPROVED.
approvals() { # $1 = pr number
  gh pr view "$1" --json reviews \
    --jq '[.reviews[] | {u:.author.login, s:.state}]
          | group_by(.u) | map(last) | map(select(.s=="APPROVED")) | length'
}

# CI verdict: "green" (all done, none failing), "pending", or "failing".
ci_state() { # $1 = pr number
  gh pr view "$1" --json statusCheckRollup --jq '
    [.statusCheckRollup[]? |
      (.conclusion // .state // "PENDING") as $c |
      if   ($c|ascii_upcase) as $u | ($u=="FAILURE" or $u=="ERROR" or $u=="CANCELLED" or $u=="TIMED_OUT") then "failing"
      elif ($c|ascii_upcase) as $u | ($u=="PENDING" or $u=="EXPECTED" or $u=="") then "pending"
      else "ok" end ]
    | if any(.=="failing") then "failing" elif any(.=="pending") then "pending" else "green" end'
}

echo "Scanning open PRs…"
prs=$(gh pr list --state open --limit 200 \
  --json number,title,isDraft,labels,files,mergeStateStatus)

merged=0; skipped=0; candidates=0
while IFS= read -r pr; do
  num=$(echo "$pr"   | jq -r '.number')
  title=$(echo "$pr" | jq -r '.title')
  draft=$(echo "$pr" | jq -r '.isDraft')
  mss=$(echo "$pr"   | jq -r '.mergeStateStatus')   # CLEAN | BLOCKED | DIRTY | BEHIND | UNSTABLE …
  is_money_path "$pr" || continue
  candidates=$((candidates+1))

  appr=$(approvals "$num")
  ci=$(ci_state "$num")
  printf '\n#%-5s %s\n' "$num" "$title"
  printf '        draft=%s  approvals=%s/2  ci=%s  mergeState=%s\n' "$draft" "$appr" "$ci" "$mss"

  if [ "$DO_MERGE" -ne 1 ]; then continue; fi

  # ---- gates (all must hold to merge) ----
  if [ "$draft" = "true" ]; then echo "        SKIP: draft"; skipped=$((skipped+1)); continue; fi
  if [ "$mss" = "DIRTY" ]; then
    echo "        SKIP: merge CONFLICT with main — rebase needed (gh pr checkout $num && git rebase origin/main)"
    skipped=$((skipped+1)); continue
  fi
  if [ "$ci" != "green" ]; then echo "        SKIP: CI $ci"; skipped=$((skipped+1)); continue; fi
  if [ "$appr" -lt 2 ] && [ "$ALLOW_MISSING" -ne 1 ]; then
    echo "        SKIP: only $appr/2 approvals (pass --allow-missing-approvals to override)"
    skipped=$((skipped+1)); continue
  fi

  echo "        MERGING (squash, no --admin)…"
  if gh pr merge "$num" --squash --delete-branch; then
    merged=$((merged+1))
  else
    echo "        NOT MERGED — see gh message above (conflict, required check/approval, or race)."
    echo "        No --admin used; a genuine protection block here is the correct final state."
    skipped=$((skipped+1))
  fi
done < <(echo "$prs" | jq -c '.[]')

echo
echo "money-path candidates: $candidates | merged: $merged | skipped: $skipped"
[ "$DO_MERGE" -eq 1 ] || echo "(dry-run — re-run with --merge to act)"
