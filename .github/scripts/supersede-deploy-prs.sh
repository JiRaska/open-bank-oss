#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Close every OLDER open bot deploy PR when a newer one opens.
#
# Why this exists: a deploy PR rewrites an image tag to the newest build. Two of them are therefore
# never additive — the newer one already contains everything the older one would have done, and the
# older one is worthless the moment it exists. But both edit the SAME manifest line, so whichever
# merges first leaves the rest DIRTY, and a DIRTY PR with green checks and auto-merge armed reads as
# healthy from every angle except mergeability. Measured 2026-07-26: SEVEN open admin-ui deploy PRs
# out of 23 open PRs in the whole repo — 30% of the review queue was superseded deploy noise, all of
# it accumulated within three hours, and it grew by two while this script was being written. One of
# them (#2594) would have REVERTED 851 lines of main had it been merged to clear the backlog, which
# is what makes the pile actively dangerous rather than merely untidy.
#
# branch-cleanup.yml already assumes this: its comment reads "a superseded deploy PR is CLOSED-
# unmerged (a newer deploy won the race)" and it deletes the branch on close. Nothing ever performed
# that close. This script is the missing half, not a new mechanism — with it, exactly one deploy PR
# per prefix is open at a time, it is always the newest, and it is always based on current main, so
# the conflict class cannot arise.
#
# ANCESTRY, NOT RECENCY (issue #6231)
# "Newer" was originally read off PR creation time — the PR that opened last kept, every other one
# closed. That is not what "newer" means here: a deploy PR is created when its BUILD finishes, and
# builds do not finish in commit order. Measured 2026-08-21: #6222 pinned `579dda8e` (merged to main
# 14:08:59Z) and #6225 pinned `a9e9d082` (merged 13:58:07Z, an ANCESTOR of it) — but a9e9d082's build
# ran ~12 min longer, so its PR was created at 14:47:01Z, twelve minutes after #6222's, and this
# script closed #6222 at 14:47:04Z as "superseded". The survivor rewound copilot-service from
# sandbox-579dda8e to sandbox-a9e9d082 and left agent-service short of #6204's code, with every job
# in both runs green. Fail-open, and invisible: check-deploy-drift compares version.txt, which those
# commits share (release-please bumps in a separate commit), so the drift watch reads "in sync".
#
# So the decision is now ancestry, asked of the GitHub compare API (the job's checkout is shallow,
# `git merge-base --is-ancestor` cannot answer there):
#   * other is an ancestor of keep (`ahead`/`identical`) -> genuinely superseded, close it.
#   * keep is an ancestor of other (`behind`)            -> THIS run is the stale one. Close nothing,
#     ::error, exit 1. The step fails, so the `Enable auto-merge` step that follows it never runs and
#     the rollback PR cannot merge itself. Loud beats a silent rewind.
#   * diverged, or a branch name with no parsable sha    -> ::warning, leave it open. Unknown is not
#     permission to close someone's PR.
#
# Deliberately scoped:
#   * Only the bot prefixes (chore/gitops-auto-deploy-, chore/admin-ui-deploy-). A hand-cut deploy PR
#     — they exist, see record-deployment-on-merge.yml — is never touched.
#   * Only same-repo heads, never a fork.
#   * Never the PR just opened.
#   * A comment is left on every PR it closes. A silent close of someone's PR is not acceptable even
#     when the PR is bot-generated: the next person to look must find out why from the PR itself.
#
# Usage: supersede-deploy-prs.sh <branch-prefix> <keep-pr-number>
#   e.g. supersede-deploy-prs.sh chore/admin-ui-deploy- 2604
#        supersede-deploy-prs.sh --self-test     # falsify the ancestry decision, no network
# Requires: gh, jq, GH_TOKEN with pull-requests: write.

set -euo pipefail

# ── ancestry ────────────────────────────────────────────────────────────────────────────────────
# Extract the 40-hex commit a deploy branch pins. Anything else yields "" and is treated as unknown.
sha_of_branch() {
  local sha="${1#"$2"}"
  # 40 hex characters exactly. A truncated, suffixed or hand-cut branch name yields "" (unknown),
  # and unknown must never mean "close it" — that was the pre-#6231 behaviour.
  case "$sha" in
    *[!0-9a-f]*) printf '' ;;
    *) if [ "${#sha}" -eq 40 ]; then printf '%s' "$sha"; else printf ''; fi ;;
  esac
}

# Ask GitHub how `head` relates to `base`: ahead | behind | identical | diverged.
# Overridable for the self-test (SUPERSEDE_COMPARE_HOOK), which is the ONLY seam — the classify /
# act loop below is the same code in both lanes.
compare_status() {
  local base="$1" head="$2"
  if [ -n "${SUPERSEDE_COMPARE_HOOK:-}" ]; then
    "$SUPERSEDE_COMPARE_HOOK" "$base" "$head"
    return
  fi
  gh api "repos/$REPO/compare/$base...$head" --jq '.status' 2>/dev/null || printf 'unknown'
}

# CLOSE | STALE_KEEP | SKIP, from (keep sha, other sha). Pure — no I/O, so the self-test exercises
# the real decision and not a paraphrase of it.
classify() {
  local keep_sha="$1" other_sha="$2" status
  if [ -z "$keep_sha" ] || [ -z "$other_sha" ]; then printf 'SKIP'; return; fi
  if [ "$keep_sha" = "$other_sha" ]; then printf 'CLOSE'; return; fi
  status="$(compare_status "$other_sha" "$keep_sha")"
  case "$status" in
    ahead|identical) printf 'CLOSE' ;;       # keep contains other — genuinely superseded
    behind)          printf 'STALE_KEEP' ;;  # keep is an ANCESTOR of other — this run is the stale one
    *)               printf 'SKIP' ;;        # diverged / unknown — never close on a guess
  esac
}

# ── coverage (issue #7621) ─────────────────────────────────────────────────────────────────────
# Ancestry alone is NOT sufficient: an auto-deploy PR bumps only the services *its own run*
# detected as changed, so a descendant commit's PR can touch a completely disjoint set of gitops
# components. Measured live: #7313 (bumps balance-service.yaml) was closed "superseded by #7314"
# (bumps kyc-service.yaml), which was in turn closed "superseded by #7319" (bumps
# account-service.yaml) — #7319's diff never touched either of the other two files, so both
# services silently stopped deploying while every job read green. Ancestry is still required (it
# is what generation 1 / issue #6231 fixed, and removing it reopens the createdAt rewind) — this
# is an ADDITIONAL gate, never a replacement.
#
# List the file paths a PR's diff touches. Overridable via SUPERSEDE_FILES_HOOK for the self-test,
# the same seam pattern as compare_status.
changed_files() {
  local pr="$1"
  if [ -n "${SUPERSEDE_FILES_HOOK:-}" ]; then
    "$SUPERSEDE_FILES_HOOK" "$pr"
    return
  fi
  gh pr diff "$pr" --repo "$REPO" --name-only 2>/dev/null
}

# rc 0 iff every non-empty line of $2 (the PR being considered for closure) appears in $1 (the
# survivor). An EMPTY subset is deliberately never treated as "covered" here — that decision
# belongs to the caller, which must refuse to close on empty/unreadable file lists (unknown must
# never mean "close it", same rule as classify() above).
covers() {
  local superset="$1" subset="$2" line
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    grep -qxF -- "$line" <<<"$superset" || return 1
  done <<<"$subset"
  return 0
}

# CLOSE | SKIP, from (keep's changed files, other's changed files). Pure, same reason as classify().
# Fails safe in every direction: no keep files, no other files, or other not a subset -> SKIP.
classify_coverage() {
  local keep_files="$1" other_files="$2"
  if [ -z "$keep_files" ] || [ -z "$other_files" ]; then printf 'SKIP'; return; fi
  if covers "$keep_files" "$other_files"; then printf 'CLOSE'; else printf 'SKIP'; fi
}

# ── main ────────────────────────────────────────────────────────────────────────────────────────
run() {
  local PREFIX="$1" KEEP="$2" KEEP_SHA="$3" PAIRS="$4"
  local n ref other_sha verdict failed=0 stale_keep=0 closed=0 skipped=0
  local KEEP_FILES other_files cov_verdict

  if [ -z "$PAIRS" ]; then
    echo "supersede: no other open '$PREFIX*' PRs besides #$KEEP — nothing to close."
    return 0
  fi

  # Fetched once, reused for every candidate. An unreadable diff yields "" and classify_coverage()
  # then refuses to close anything against it — never treated as "covers everything".
  KEEP_FILES="$(changed_files "$KEEP")"

  while IFS=$'\t' read -r n ref; do
    [ -n "$n" ] || continue
    other_sha="$(sha_of_branch "$ref" "$PREFIX")"
    verdict="$(classify "$KEEP_SHA" "$other_sha")"
    if [ "$verdict" = "CLOSE" ]; then
      other_files="$(changed_files "$n")"
      cov_verdict="$(classify_coverage "$KEEP_FILES" "$other_files")"
      if [ "$cov_verdict" != "CLOSE" ]; then
        skipped=$((skipped + 1))
        echo "::warning::supersede: #$KEEP is a descendant of #$n's commit, but #$KEEP's changed" \
             "files do not cover #$n's (coverage check, issue #7621) — leaving #$n OPEN." \
             "keep_files=[$(printf '%s' "$KEEP_FILES" | tr '\n' ' ')]" \
             "other_files=[$(printf '%s' "$other_files" | tr '\n' ' ')]"
        continue
      fi
    fi
    case "$verdict" in
      STALE_KEEP)
        stale_keep=1
        echo "::error::supersede: #$KEEP pins ${KEEP_SHA:0:8}, which is an ANCESTOR of #$n's ${other_sha:0:8}." \
             "This build finished later than a NEWER commit's, so #$KEEP would roll those services BACK." \
             "Closing nothing and failing the step: #$n stays open, and auto-merge is not armed on #$KEEP." \
             "Re-dispatch auto-deploy for the current main once #$n has landed (issue #6231)."
        ;;
      SKIP)
        skipped=$((skipped + 1))
        echo "::warning::supersede: cannot establish that #$KEEP supersedes #$n (keep=${KEEP_SHA:-?} other=${other_sha:-?}, unrelated or unparsable) — leaving #$n OPEN."
        ;;
      CLOSE)
        if [ "${DRY_RUN:-}" = "true" ]; then
          echo "supersede: DRY_RUN — would close #$n (${other_sha:0:8} is an ancestor of ${KEEP_SHA:0:8})."
          closed=$((closed + 1))
          continue
        fi
        # Comment first, then close: if the close succeeds but the comment fails, the PR is shut with
        # no stated reason, which is the outcome this is trying to avoid.
        if ! gh pr comment "$n" --repo "$REPO" --body \
"Superseded by #$KEEP and closed automatically.

#$KEEP pins \`${KEEP_SHA:0:8}\`, a **descendant** of this PR's \`${other_sha:0:8}\` — it therefore already
contains everything this PR would have applied. (Ancestry, not creation time: a slower build can open a
newer PR for an older commit, which is issue #6231.)

Left open, both PRs edit the same manifest line and whichever merges first leaves the other conflicting,
which reads as healthy — green checks, auto-merge armed — right up to the point someone tries to merge it.

If you believe otherwise, reopen it and say what it carries that #$KEEP does not.

(\`.github/scripts/supersede-deploy-prs.sh\`)"; then
          echo "::warning::supersede: could not comment on #$n — leaving it OPEN rather than closing it silently."
          failed=1
          continue
        fi
        if ! gh pr close "$n" --repo "$REPO" --delete-branch; then
          echo "::warning::supersede: could not close #$n."
          failed=1
        else
          closed=$((closed + 1))
        fi
        ;;
    esac
  done <<EOF
$PAIRS
EOF

  echo "supersede: #$KEEP (${KEEP_SHA:0:8}) — closed=$closed skipped=$skipped stale_keep=$stale_keep"

  # A stale KEEP is the one condition that must be loud. Everything else is tidiness: the image is
  # already built and pushed, and a leftover stale PR does not break a deploy.
  if [ "$stale_keep" -ne 0 ]; then
    return 1
  fi
  if [ "$failed" -ne 0 ]; then
    echo "::warning::supersede: one or more superseded deploy PRs could not be closed; close them by hand."
  fi
  return 0
}

# ── self-test ───────────────────────────────────────────────────────────────────────────────────
# Falsification, not decoration: case 1 is the losing interleaving from #6231 (an older commit's PR
# created LAST) and must fail; case 2 is the ordinary case and must still close the older PR.
self_test() {
  local tmp rc out ok=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  # Fake compare: OLD..NEW is `ahead`, NEW..OLD is `behind`, anything else `diverged`.
  cat > "$tmp/compare" <<'HOOK'
#!/usr/bin/env bash
base="$1"; head="$2"
old=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
new=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
if [ "$base" = "$old" ] && [ "$head" = "$new" ]; then echo ahead
elif [ "$base" = "$new" ] && [ "$head" = "$old" ]; then echo behind
else echo diverged; fi
HOOK
  chmod +x "$tmp/compare"
  export SUPERSEDE_COMPARE_HOOK="$tmp/compare"
  export DRY_RUN=true

  # Fake file lists, keyed by PR number. Cases 1-4 predate the coverage gate (issue #7621) and are
  # about ancestry only, so every PR in them shares one file — coverage is trivially satisfied and
  # cannot mask an ancestry regression. Cases 5-7 give distinct PRs distinct files to exercise the
  # coverage gate itself.
  cat > "$tmp/files" <<'HOOK'
#!/usr/bin/env bash
pr="$1"
case "$pr" in
  6222|6225|1|2) echo "openbank-infra/gitops/components/shared/shared-service.yaml" ;;
  7313) printf '%s\n' "openbank-infra/gitops/components/balances/balance-service.yaml" ;;
  7319) printf '%s\n' "openbank-infra/gitops/components/accounts/account-service.yaml" ;;
  7314) printf '%s\n' "openbank-infra/gitops/components/kyc/kyc-service.yaml" ;;
  7327) printf '%s\n%s\n' \
          "openbank-infra/gitops/components/aml/aml-service.yaml" \
          "openbank-infra/gitops/components/kyc/kyc-service.yaml" ;;
  7320) printf '%s\n' "openbank-infra/gitops/components/aml/aml-service.yaml" ;;
  *) echo "" ;;
esac
HOOK
  chmod +x "$tmp/files"
  export SUPERSEDE_FILES_HOOK="$tmp/files"
  local OLD=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  local NEW=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
  local P=chore/gitops-auto-deploy-

  # case 1 — the #6231 interleaving: KEEP is the OLDER commit, the open PR is the NEWER one.
  out="$(run "$P" 6225 "$OLD" "$(printf '6222\t%s%s' "$P" "$NEW")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -eq 0 ]; then
    echo "SELF-TEST FAIL case 1: stale KEEP exited 0 (must be non-zero)"; ok=1
  elif ! printf '%s' "$out" | grep -q '::error::supersede: #6225 pins aaaaaaaa, which is an ANCESTOR'; then
    echo "SELF-TEST FAIL case 1: no ::error naming the ancestry; got: $out"; ok=1
  elif printf '%s' "$out" | grep -q 'would close'; then
    echo "SELF-TEST FAIL case 1: it still closed the newer PR"; ok=1
  else
    echo "self-test case 1 OK (stale keep -> rc=$rc, ::error, nothing closed)"
  fi

  # case 2 — the ordinary case: KEEP is the NEWER commit. The older PR must still be closed.
  out="$(run "$P" 6222 "$NEW" "$(printf '6225\t%s%s' "$P" "$OLD")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "SELF-TEST FAIL case 2: ordinary supersede exited $rc; got: $out"; ok=1
  elif ! printf '%s' "$out" | grep -q 'would close #6225'; then
    echo "SELF-TEST FAIL case 2: the older PR was not closed; got: $out"; ok=1
  else
    echo "self-test case 2 OK (ordinary supersede -> rc=$rc, older PR closed)"
  fi

  # case 3 — unrelated shas must never be closed on a guess.
  out="$(run "$P" 1 cccccccccccccccccccccccccccccccccccccccc "$(printf '2\t%s%s' "$P" "$OLD")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ] || printf '%s' "$out" | grep -q 'would close'; then
    echo "SELF-TEST FAIL case 3: diverged pair was closed or failed; got: $out"; ok=1
  else
    echo "self-test case 3 OK (diverged -> warning, left open)"
  fi

  # case 4 — an unparsable branch name is unknown, not permission.
  out="$(run "$P" 1 "$NEW" "$(printf '2\t%snot-a-sha' "$P")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ] || printf '%s' "$out" | grep -q 'would close'; then
    echo "SELF-TEST FAIL case 4: unparsable branch was closed or failed; got: $out"; ok=1
  else
    echo "self-test case 4 OK (unparsable sha -> warning, left open)"
  fi

  # case 5 — coverage gate (issue #7621), the exact production shape: #7314 (kyc) is a genuine
  # ancestry-supersede of #7313 (balance), but #7314's diff never touches balance-service.yaml.
  # Ancestry alone would CLOSE this; the coverage gate must refuse.
  out="$(run "$P" 7314 "$NEW" "$(printf '7313\t%s%s' "$P" "$OLD")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "SELF-TEST FAIL case 5: coverage-insufficient run exited $rc (must be 0, tidiness only); got: $out"; ok=1
  elif printf '%s' "$out" | grep -q 'would close #7313'; then
    echo "SELF-TEST FAIL case 5: #7313 was closed despite #7314 never touching balance-service.yaml; got: $out"; ok=1
  elif ! printf '%s' "$out" | grep -q 'coverage check, issue #7621.*leaving #7313 OPEN'; then
    echo "SELF-TEST FAIL case 5: no coverage warning naming #7313; got: $out"; ok=1
  else
    echo "self-test case 5 OK (ancestor but disjoint files -> #7313 left OPEN, #7313/#7314/#7319 shape)"
  fi

  # case 6 — coverage gate, the ordinary (should-close) case: #7327 bumps BOTH aml and kyc
  # manifests, a genuine superset of #7320's aml-only diff. Must still close.
  out="$(run "$P" 7327 "$NEW" "$(printf '7320\t%s%s' "$P" "$OLD")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "SELF-TEST FAIL case 6: ordinary coverage-covered run exited $rc; got: $out"; ok=1
  elif ! printf '%s' "$out" | grep -q 'would close #7320'; then
    echo "SELF-TEST FAIL case 6: #7320 was not closed despite #7327's superset diff; got: $out"; ok=1
  else
    echo "self-test case 6 OK (ancestor and superset files -> #7320 closed)"
  fi

  if [ "$ok" -ne 0 ]; then
    echo "self-test: FAILED"
    return 1
  fi
  echo "self-test: all 6 cases OK"
  return 0
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi

PREFIX="${1:?usage: supersede-deploy-prs.sh <branch-prefix> <keep-pr-number> | --self-test}"
KEEP="${2:?usage: supersede-deploy-prs.sh <branch-prefix> <keep-pr-number> | --self-test}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set}"

# The sha THIS run's deploy PR pins. Without it nothing can be classified, and "cannot classify"
# must mean "close nothing" — never "close everything", which is the pre-#6231 behaviour.
KEEP_SHA="$(sha_of_branch "$(gh pr view "$KEEP" --repo "$REPO" --json headRefName --jq '.headRefName')" "$PREFIX")"
if [ -z "$KEEP_SHA" ]; then
  echo "::warning::supersede: could not read a commit sha off #$KEEP's branch — closing nothing."
  exit 0
fi

# `gh pr list` caps at 30 by default; a backlog can exceed that, and a cap that silently truncates
# would leave the oldest — the very ones most likely to be conflicting — untouched.
#
# Read into a single variable, NOT `mapfile`: mapfile is a bash-4 builtin, absent from macOS's system
# bash 3.2 and from zsh, where it fails with "command not found" and leaves the array EMPTY. The
# script would then print "nothing to close" and exit 0 — a broken probe reporting a clean result,
# which is the failure mode this repo has been bitten by repeatedly.
PAIRS="$(gh pr list --repo "$REPO" --state open --limit 200 \
    --json number,headRefName,headRepositoryOwner,createdAt \
    --jq "[.[]
           | select(.headRefName | startswith(\"$PREFIX\"))
           | select(.number != ($KEEP | tonumber))
           | select(.headRepositoryOwner.login == \"${REPO%%/*}\")]
          | sort_by(.createdAt)
          | .[] | \"\(.number)\t\(.headRefName)\"")"

run "$PREFIX" "$KEEP" "$KEEP_SHA" "$PAIRS"
exit $?
