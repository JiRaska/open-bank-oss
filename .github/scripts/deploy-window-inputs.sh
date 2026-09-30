#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Gather the two live facts deploy-window.py decides on, into files in <outdir>:
#   commits.json — main's newest 50 commits as [{message, epoch}], newest first
#   prs.json     — open bot deploy PRs as [{number, head, created_at, armed}]
# Separate from the decision so the decision stays pure and self-tested. Fails LOUDLY on an
# API error: an empty list would read as "no recent deploy" and arm, which is the safe
# direction, but a silent empty PR list would make the flusher think nothing is pending.
# Usage: deploy-window-inputs.sh <outdir>   (needs GH_TOKEN, GITHUB_REPOSITORY)
set -euo pipefail
out="${1:?usage: deploy-window-inputs.sh <outdir>}"
repo="${GITHUB_REPOSITORY:?}"
mkdir -p "$out"
gh api "repos/${repo}/commits?sha=main&per_page=50" \
  --jq '[.[] | {message: .commit.message, epoch: (.commit.committer.date | fromdateiso8601)}]' \
  > "$out/commits.json"
gh pr list --repo "$repo" --state open --limit 200 \
  --json number,headRefName,createdAt,autoMergeRequest,isCrossRepository \
  --jq '[.[] | select(.isCrossRepository | not)
             | select(.headRefName | startswith("chore/gitops-auto-deploy-") or startswith("chore/admin-ui-deploy-"))
             | {number, head: .headRefName, created_at: .createdAt, armed: (.autoMergeRequest != null)}]' \
  > "$out/prs.json"
jq -e 'type == "array"' "$out/commits.json" "$out/prs.json" >/dev/null
echo "deploy-window inputs: $(jq length "$out/commits.json") commits, $(jq length "$out/prs.json") open deploy PR(s), $(jq '[.[]|select(.armed)]|length' "$out/prs.json") armed"
