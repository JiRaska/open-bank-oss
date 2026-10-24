#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Gather the two live facts deploy-window.py decides on, into files in <outdir>:
#   commits.json — main's newest 50 commits as [{message, epoch}], newest first
#   prs.json     — eligible deploy PRs plus explicitly labelled open repair PRs; a draft or
#                  `blocked` PR is a deployment hold (issue #11503) and is never a candidate
# Separate from the decision so the decision stays pure and self-tested. Fails LOUDLY on an
# API error: an empty list would read as "no recent deploy" and arm, which is the safe
# direction, but a silent empty PR list would make the flusher think nothing is pending.
# Usage: deploy-window-inputs.sh <outdir>   (needs GH_TOKEN, GITHUB_REPOSITORY)
set -euo pipefail
out="${1:?usage: deploy-window-inputs.sh <outdir>}"
repo="${GITHUB_REPOSITORY:?}"
mkdir -p "$out"
selected_main=$(gh api "repos/${repo}/git/ref/heads/main" --jq .object.sha)
printf '%s\n' "$selected_main" > "$out/main.sha"
gh api "repos/${repo}/commits?sha=${selected_main}&per_page=50" \
  --jq '[.[] | {message: .commit.message, epoch: (.commit.committer.date | fromdateiso8601)}]' \
  > "$out/commits.json"
# Paginate the complete open inventory; a capped pr-list can silently miss a repair.
# Labels are repository metadata requiring GitHub triage/write authority, never PR body text.
gh api --paginate --slurp "repos/${repo}/pulls?state=open&per_page=100" > "$out/open-pr-pages.json"
jq -e 'type == "array" and all(.[]; type == "array" and all(.[];
  (.number | type == "number") and (.state == "open") and
  (.head.ref | type == "string") and (.head.sha | type == "string") and
  (.head.repo.full_name | type == "string") and
  (.base.repo.full_name | type == "string") and (.created_at | type == "string") and
  (.draft | type == "boolean") and has("auto_merge") and
  (.labels | type == "array" and all(.[]; .name | type == "string"))))' \
  "$out/open-pr-pages.json" >/dev/null
jq '[.[][] | ([.labels[].name] | index("merge-priority:repair") != null) as $repair
  | select($repair or (.head.repo.full_name == .base.repo.full_name and (.draft | not)
      and ([.labels[].name] | index("blocked") | not)
      and (.head.ref | startswith("chore/gitops-auto-deploy-") or startswith("chore/admin-ui-deploy-"))))
  | {number, head: .head.ref, head_sha: .head.sha, created_at: .created_at, armed: (.auto_merge != null),
     state, repair_priority: $repair}]' "$out/open-pr-pages.json" > "$out/prs.json"
jq -e 'type == "array"' "$out/commits.json" "$out/prs.json" >/dev/null
echo "deploy-window inputs: $(jq length "$out/commits.json") commits, $(jq length "$out/prs.json") open deploy PR(s), $(jq '[.[]|select(.armed)]|length' "$out/prs.json") armed"
