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
# Requires: gh, jq, GH_TOKEN with pull-requests: write.

set -euo pipefail

PREFIX="${1:?usage: supersede-deploy-prs.sh <branch-prefix> <keep-pr-number>}"
KEEP="${2:?usage: supersede-deploy-prs.sh <branch-prefix> <keep-pr-number>}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set}"

# `gh pr list` caps at 30 by default; a backlog can exceed that, and a cap that silently truncates
# would leave the oldest — the very ones most likely to be conflicting — untouched.
#
# Read with `while read`, NOT `mapfile`: mapfile is a bash-4 builtin, absent from macOS's system
# bash 3.2 and from zsh, where it fails with "command not found" and leaves the array EMPTY. The
# script would then print "nothing to close" and exit 0 — a broken probe reporting a clean result,
# which is the failure mode this repo has been bitten by repeatedly. `while read` runs everywhere,
# so the same command can be dry-run locally as it behaves in CI.
STALE=""
STALE_COUNT=0
while IFS= read -r n; do
  [ -n "$n" ] || continue
  STALE="$STALE $n"
  STALE_COUNT=$((STALE_COUNT + 1))
done <<EOF
$(gh pr list --repo "$REPO" --state open --limit 200 \
    --json number,headRefName,headRepositoryOwner,createdAt \
    --jq "[.[]
           | select(.headRefName | startswith(\"$PREFIX\"))
           | select(.number != ($KEEP | tonumber))
           | select(.headRepositoryOwner.login == \"${REPO%%/*}\")]
          | sort_by(.createdAt)
          | .[].number")
EOF

if [ "$STALE_COUNT" -eq 0 ]; then
  echo "supersede: no older open '$PREFIX*' PRs besides #$KEEP — nothing to close."
  exit 0
fi

echo "supersede: #$KEEP is the current deploy PR; closing $STALE_COUNT superseded:$STALE"

if [ "${DRY_RUN:-}" = "true" ]; then
  echo "supersede: DRY_RUN — would close:$STALE"
  exit 0
fi

failed=0
for n in $STALE; do
  # Comment first, then close: if the close succeeds but the comment fails, the PR is shut with no
  # stated reason, which is the outcome this is trying to avoid.
  if ! gh pr comment "$n" --repo "$REPO" --body \
"Superseded by #$KEEP and closed automatically.

A deploy PR rewrites an image tag to the newest build, so two of them are never additive — #$KEEP
already contains everything this PR would have applied. Left open, both edit the same manifest line
and whichever merges first leaves the other conflicting, which reads as healthy (green checks,
auto-merge armed) right up to the point someone tries to merge it.

Nothing is lost by this close: the tag this PR carried is older than the one in #$KEEP. If you
believe otherwise, reopen it and say what it carries that #$KEEP does not.

(\`.github/scripts/supersede-deploy-prs.sh\`)"; then
    echo "::warning::supersede: could not comment on #$n — leaving it OPEN rather than closing it silently."
    failed=1
    continue
  fi
  if ! gh pr close "$n" --repo "$REPO" --delete-branch; then
    echo "::warning::supersede: could not close #$n."
    failed=1
  fi
done

# A failure here must not fail the deploy — the image is already built and pushed, and a leftover
# stale PR is a tidiness problem, not a broken deploy. Warn loudly instead.
if [ "$failed" -ne 0 ]; then
  echo "::warning::supersede: one or more superseded deploy PRs could not be closed; close them by hand."
fi
exit 0
